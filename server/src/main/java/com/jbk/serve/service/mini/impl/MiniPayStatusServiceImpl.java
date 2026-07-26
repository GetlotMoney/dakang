package com.jbk.serve.service.mini.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.jbk.serve.mapper.trade.RechargeIdentityMapper;
import com.jbk.serve.service.mini.IMiniPayStatusService;
import com.jbk.serve.service.mini.recharge.RechargePayExpire;
import com.jbk.serve.service.mini.recharge.RechargePayStatus;
import com.jbk.serve.service.mini.recharge.RechargeSnapshot;
import com.jbk.tool.data.mini.bo.MiniPayStatusBo;
import com.jbk.tool.data.mini.vo.MiniPayStatusVo;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsPayment;
import com.jbk.tool.data.trade.po.WsPaymentEvent;
import com.jbk.tool.exception.JbkException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 充值订单支付状态查询（L2 契约 §9.1）。
 *
 * <p>顺序：本人过滤 → 关键对象跨全部 DATA_STATUS 读取 → 逐项共键校验 → 精确组合矩阵。
 * 任一关键对象缺失、重复、错位或来源不一致均 fail-closed 返回结构化 mismatch，<b>不回退 Mock</b>。</p>
 */
@Service
@RequiredArgsConstructor
public class MiniPayStatusServiceImpl implements IMiniPayStatusService {

    private static final int ORDER_TYPE_RECHARGE = 2;
    private static final int PAY_WAY_WECHAT = 1;
    /** 允许客户端继续轮询的状态（尚在推进中）。 */
    private static final Set<String> RETRYABLE = Set.of("WAITING_PAYMENT", "PAID_CREDIT_PENDING");

    private final RechargeIdentityMapper identityMapper;

    @Override
    public MiniPayStatusVo query(MiniPayStatusBo bo, Long userId) {
        if (userId == null || StrUtil.isBlank(bo.getOrderNo())) {
            throw new JbkException("参数不完整");
        }
        List<WsOrder> orders = identityMapper.selectOrdersByOrderNoIncludingDeleted(bo.getOrderNo().trim());
        if (orders == null || orders.size() != 1) {
            // 不区分"不存在"与"非本人"，避免成为订单号探测信道
            throw new JbkException("订单不存在或无权访问");
        }
        WsOrder order = orders.get(0);
        if (!ObjectUtil.equals(order.getUserId(), userId)
                || !ObjectUtil.equals(order.getDataStatus(), 0)
                || !ObjectUtil.equals(order.getOrderType(), ORDER_TYPE_RECHARGE)) {
            throw new JbkException("订单不存在或无权访问");
        }

        List<WsPayment> payments = identityMapper.selectPaymentsByOrderIdIncludingDeleted(order.getId());
        if (payments == null || payments.size() != 1) {
            return mismatch(order, null, "支付单必须恰好一条");
        }
        WsPayment payment = payments.get(0);
        List<WsPaymentEvent> events = identityMapper.selectEventsByOrderNoIncludingDeleted(order.getOrderNo());
        events = events == null ? List.of() : events;

        String keyReason = verifyKeys(order, payment, events);
        if (keyReason != null) {
            return mismatch(order, payment, keyReason);
        }

        if (identityMapper.countDeletedRechargeFlows(order.getId()) > 0) {
            // §8/§9.1：被逻辑删除的资金流水本身即污染，必须整体 mismatch
            return mismatch(order, payment, "存在被逻辑删除的充值流水");
        }
        long flows = identityMapper.countLiveRechargeFlows(order.getId());
        RechargePayStatus.Resolved resolved = RechargePayStatus.resolve(order, payment, events, flows);

        MiniPayStatusVo vo = base(order, payment);
        vo.setPayStatusCode(resolved.statusCode());
        vo.setStatusMessage(resolved.statusMessage());
        vo.setProcessingStatus(RechargePayStatus.aggregateProcessing(events));
        vo.setRetryable(resolved.ok() && RETRYABLE.contains(resolved.statusCode()));
        return vo;
    }

    /** 逐项共键与不可变字段校验；返回 null 表示全部通过。 */
    private String verifyKeys(WsOrder order, WsPayment payment, List<WsPaymentEvent> events) {
        if (!ObjectUtil.equals(payment.getDataStatus(), 0)) {
            return "支付单被逻辑删除，视为污染";
        }
        if (!ObjectUtil.equals(payment.getOrderId(), order.getId())
                || !StrUtil.equals(payment.getOrderNo(), order.getOrderNo())
                || !ObjectUtil.equals(payment.getPayAmount(), order.getOrderAmount())) {
            return "支付单与订单共键错位";
        }
        if (!ObjectUtil.equals(order.getPayWay(), PAY_WAY_WECHAT)) {
            return "订单支付方式不符";
        }
        if (StrUtil.isBlank(payment.getPayExpireTime())) {
            return "付款截止时间缺失";
        }
        // 截止时间必须与订单资格快照按冻结算法精确一致（证明创建后未被改写）
        RechargeSnapshot.Parsed snap;
        try {
            snap = RechargeSnapshot.parse(order.getPackageSnap());
        } catch (JbkException e) {
            return "订单快照错位：" + e.getMessage();
        }
        if (!StrUtil.equals(snap.capturedTime(), order.getCreateTime())) {
            return "快照采集时间与订单创建时间不一致";
        }
        String expected = RechargePayExpire.compute(snap.capturedTime(), snap.expireTimeAtCreate());
        if (!StrUtil.equals(payment.getPayExpireTime(), expected)) {
            return "付款截止时间与资格快照不一致";
        }
        for (WsPaymentEvent e : events) {
            if (!ObjectUtil.equals(e.getDataStatus(), 0)) {
                return "存在被逻辑删除的支付事件，视为污染";
            }
            // §5.3：ORDER_ID/PAYMENT_ID 为空恰恰表示"未知/错位"（事务 A 未能完成可信关联），
            // 不能因为字段是 null 就短路放行——那等于把未关联的事件当成已核对通过。
            if (!StrUtil.equals(e.getOrderNo(), order.getOrderNo())
                    || !ObjectUtil.equals(e.getOrderId(), order.getId())
                    || !ObjectUtil.equals(e.getPaymentId(), payment.getId())) {
                return "支付事件与订单/支付单共键错位或未完成可信关联";
            }
            if (!ObjectUtil.equals(e.getPaySource(), payment.getPaySource())) {
                return "支付事件与支付单来源不一致";
            }
            if (RechargePayStatus.SUCCESS.equals(e.getTradeState())) {
                if (StrUtil.isBlank(e.getTransactionId()) || e.getPayAmount() == null
                        || StrUtil.isBlank(e.getCurrency()) || StrUtil.isBlank(e.getPaySuccessTime())) {
                    return "成功事实缺少交易号/金额/币种/成功时间";
                }
                if (!ObjectUtil.equals(e.getPayAmount(), payment.getPayAmount())) {
                    return "成功事实金额与支付单不一致";
                }
                if (!"CNY".equals(e.getCurrency())) {
                    return "成功事实币种非 CNY";
                }
                // payment 已回填权威交易号/成功时间时（L2-T 事务 A 负责），事件必须与之精确一致，
                // 否则跨单交易号或错位成功时间会被拼进本单。
                if (StrUtil.isNotBlank(payment.getTransactionId())
                        && !StrUtil.equals(e.getTransactionId(), payment.getTransactionId())) {
                    return "成功事实交易号与支付单不一致";
                }
                if (StrUtil.isNotBlank(payment.getPaySuccessTime())
                        && !StrUtil.equals(e.getPaySuccessTime(), payment.getPaySuccessTime())) {
                    return "成功事实成功时间与支付单不一致";
                }
            } else if (e.getPayAmount() != null && !ObjectUtil.equals(e.getPayAmount(), payment.getPayAmount())) {
                // 非成功事实：支付方返回金额时同样必须一致
                return "支付事实金额与支付单不一致";
            }
        }
        return null;
    }

    private MiniPayStatusVo mismatch(WsOrder order, WsPayment payment, String reason) {
        MiniPayStatusVo vo = base(order, payment);
        vo.setPayStatusCode("MISMATCH");
        vo.setStatusMessage("订单支付数据不一致（" + reason + "），请联系客服");
        vo.setRetryable(false);
        return vo;
    }

    private MiniPayStatusVo base(WsOrder order, WsPayment payment) {
        MiniPayStatusVo vo = new MiniPayStatusVo();
        vo.setOrderNo(order.getOrderNo());
        vo.setOrderStatus(order.getOrderStatus());
        vo.setFinishTime(order.getFinishTime());
        if (payment != null) {
            vo.setPayStatus(payment.getPayStatus());
            vo.setPaySource(payment.getPaySource());
            vo.setPayExpireTime(payment.getPayExpireTime());
        }
        return vo;
    }
}
