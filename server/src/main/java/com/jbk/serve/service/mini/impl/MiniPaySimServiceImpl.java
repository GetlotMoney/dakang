package com.jbk.serve.service.mini.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.jbk.serve.mapper.trade.RechargeCreditMapper;
import com.jbk.serve.mapper.trade.RechargeIdentityMapper;
import com.jbk.serve.service.mini.IMiniPaySimService;
import com.jbk.serve.service.mini.recharge.IRechargePayFactService;
import com.jbk.serve.service.mini.recharge.IRechargePaySourceAdapter;
import com.jbk.serve.service.mini.recharge.RechargePayEligibility;
import com.jbk.serve.service.mini.recharge.RechargePayExpire;
import com.jbk.serve.service.mini.recharge.RechargePayStatus;
import com.jbk.serve.service.mini.recharge.RechargeQueryEventKey;
import com.jbk.tool.data.mini.bo.MiniPaySimBo;
import com.jbk.tool.data.mini.vo.MiniPaySimVo;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsPayment;
import com.jbk.tool.data.trade.po.WsPaymentEvent;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Pay-Sim 实现（仅 {@code mini.pay-sim.enabled=true} 时注册）。
 *
 * <p>外部事件号刻意<b>由订单号稳定派生</b>而非随机：这样重复点"支付"就等价于支付方重复通知，
 * 第二次会命中 {@code uk_payment_event_source_channel_key} 而复用同一条事实，
 * 于是「重复回调不会重复入账」在演示时可以被真正点出来，而不是只写在文档里。</p>
 *
 * <p>本类不写任何资金字段——它只造事实，钱由 {@code IRechargePayFactService} 那条唯一路径去动。</p>
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mini.pay-sim.enabled", havingValue = "true")
public class MiniPaySimServiceImpl implements IMiniPaySimService {

    private static final int ORDER_TYPE_RECHARGE = 2;
    /** 事实渠道：2 QUERY（主动查单）。真实微信查单接进来后用的是同一个通道号与同一条处理路径。 */
    private static final int FACT_CHANNEL_QUERY = 2;
    /** 事实渠道：3 Pay-Sim。 */
    private static final int FACT_CHANNEL_PAY_SIM = 3;
    /**
     * 校验方式：3 服务端内部模拟校验（1 微信签名 / 2 微信查询）。刻意不称 HMAC——
     * 只有 SHA-256 完整性摘要、无共享密钥，称 HMAC 会被误以为具备不可伪造性。
     */
    private static final int VERIFY_METHOD_SIM_INTERNAL = 3;
    private static final String CURRENCY_CNY = "CNY";

    private final RechargeIdentityMapper identityMapper;
    private final RechargeCreditMapper creditMapper;
    private final IRechargePayFactService factService;
    private final IRechargePaySourceAdapter paySourceAdapter;

    @Override
    public MiniPaySimVo pay(MiniPaySimBo bo, Long userId) {
        Target target = resolve(bo, userId, "拒绝模拟支付");
        WsOrder order = target.order();
        WsPayment payment = target.payment();
        String orderNo = order.getOrderNo();
        int paySource = target.paySource();

        String now = DateUtils.time();
        // 可支付性前置校验必须在造事实之前。若先落 SUCCESS 事实再由事务 A 判超时，
        // 结果是「钱没真收到、订单却被推进到 6 转人工」——比直接拒绝更糟。
        String reject = rejectReason(order, payment, now);
        if (reject != null) {
            throw new JbkException(reject);
        }

        String providerEventKey = "SIM-" + orderNo;
        String transactionId = "SIMTX" + orderNo;

        WsPaymentEvent existing = creditMapper.selectEventByProviderKey(
                paySource, FACT_CHANNEL_PAY_SIM, providerEventKey);
        Long eventId;
        if (existing != null) {
            eventId = existing.getId();
        } else {
            // RAW_BODY_SHA256 是正文完整性摘要，塞占位符会让事实失去证据价值
            String rawBody = simRawBody(orderNo, transactionId, payment.getPayAmount(), now);
            try {
                creditMapper.insertEvent(orderNo, order.getId(), payment.getId(), paySource,
                        FACT_CHANNEL_PAY_SIM, providerEventKey, RechargePayStatus.SUCCESS,
                        transactionId, payment.getPayAmount(), CURRENCY_CNY, now, userId, now,
                        rawBody, sha256Hex(rawBody), VERIFY_METHOD_SIM_INTERNAL);
            } catch (DuplicateKeyException e) {
                // 并发下另一方先落了同一条事实：唯一键挡住了第二条，下面重读复用即可
            }
            WsPaymentEvent saved = creditMapper.selectEventByProviderKey(
                    paySource, FACT_CHANNEL_PAY_SIM, providerEventKey);
            if (saved == null) {
                throw JbkException.internal("支付事实落库失败");
            }
            eventId = saved.getId();
        }

        IRechargePayFactService.Outcome outcome = factService.process(eventId);

        MiniPaySimVo vo = new MiniPaySimVo();
        vo.setOrderNo(orderNo);
        vo.setResultCode(outcome.code());
        vo.setMessage(outcome.message());
        vo.setTransactionId(transactionId);
        return vo;
    }

    // ------------------------------------------------------------------
    // 模拟支付方主动查单（FACT_CHANNEL=2 QUERY）
    // ------------------------------------------------------------------

    @Override
    public MiniPaySimVo query(MiniPaySimBo bo, Long userId) {
        Target target = resolve(bo, userId, "拒绝模拟查单");
        WsOrder order = target.order();
        WsPayment payment = target.payment();
        String orderNo = order.getOrderNo();
        int paySource = target.paySource();
        String now = DateUtils.time();

        String tradeState = simulateTradeState(orderNo, payment, now);
        // 键由事实内容派生（契约 §335）：NOTPAY→CLOSED 得到不同键，先落的不挡后来的；
        // 非成功事实的外部字段一律 null——用内部金额补造的"外部事实"在对账时是伪证
        String providerEventKey = RechargeQueryEventKey.derive(
                paySource, orderNo, tradeState, null, null, null, null);
        String rawBody = queryRawBody(orderNo, tradeState);
        String sha256 = sha256Hex(rawBody);

        WsPaymentEvent existing = creditMapper.selectEventByProviderKey(
                paySource, FACT_CHANNEL_QUERY, providerEventKey);
        Long eventId;
        if (existing != null) {
            eventId = requireSameDigest(existing.getId(), sha256);
        } else {
            try {
                creditMapper.insertEvent(orderNo, order.getId(), payment.getId(), paySource,
                        FACT_CHANNEL_QUERY, providerEventKey, tradeState,
                        null, null, null, null, userId, now,
                        rawBody, sha256, VERIFY_METHOD_SIM_INTERNAL);
            } catch (DuplicateKeyException e) {
                // 并发查单：唯一键挡住第二条，下面重读复用
            }
            WsPaymentEvent saved = creditMapper.selectEventByProviderKey(
                    paySource, FACT_CHANNEL_QUERY, providerEventKey);
            if (saved == null) {
                throw JbkException.internal("查单事实落库失败");
            }
            eventId = requireSameDigest(saved.getId(), sha256);
        }

        // 与真实查单完全同路：造完事实就交给同一个处理器，Pay-Sim 不自己推进任何状态
        IRechargePayFactService.Outcome outcome = factService.process(eventId);

        MiniPaySimVo vo = new MiniPaySimVo();
        vo.setOrderNo(orderNo);
        vo.setTradeState(tradeState);
        vo.setResultCode(outcome.code());
        vo.setMessage(outcome.message());
        return vo;
    }

    /**
     * 模拟支付方对这笔单的认知，返回 NOTPAY 或 CLOSED。这不是本地超时关单：
     * payment 4/order 5 只能由支付方权威结果驱动（契约 §369/446），本方法扮演支付方，
     * 判据只有付款截止时间是否已过；关单动作仍由事实处理器按 CAS 完成。
     * 本地已有成功事实时直接拒绝——绝不返回与已知事实矛盾的答案。
     */
    private String simulateTradeState(String orderNo, WsPayment payment, String now) {
        List<WsPaymentEvent> events = identityMapper.selectEventsByOrderNoIncludingDeleted(orderNo);
        if (events != null && events.stream()
                .anyMatch(e -> RechargePayStatus.SUCCESS.equals(e.getTradeState()))) {
            throw JbkException.internal("模拟支付方已记录该订单的支付成功事实，查单不会返回未支付或已关闭");
        }
        if (StrUtil.isBlank(payment.getPayExpireTime())) {
            // 截止时间缺失时支付方无从判断是否该关单，fail-closed 拒绝而不是默认关闭
            throw new JbkException("订单付款截止时间缺失，模拟支付方无法给出查单结论");
        }
        return RechargePayExpire.paidInTime(now, payment.getPayExpireTime())
                ? RechargePayStatus.NOTPAY
                : RechargePayStatus.CLOSED;
    }

    /** 契约 §5.3：同键重复到达必须比对正文摘要，不一致即人工核查，不得复用旧事实。 */
    private Long requireSameDigest(Long eventId, String sha256) {
        if (creditMapper.countEventMatchingDigest(eventId, sha256) != 1) {
            throw JbkException.internal("同一查单事实键下的正文摘要不一致，已拒绝复用，请人工核查");
        }
        return eventId;
    }

    /**
     * 定位并校验目标订单/支付单。归属校验与 pay-status 同一口径：
     * 不存在与非本人返回同一句，不成为订单号探测信道。
     */
    private Target resolve(MiniPaySimBo bo, Long userId, String refuseLabel) {
        if (userId == null || StrUtil.isBlank(bo.getOrderNo())) {
            throw new JbkException("参数不完整");
        }
        String orderNo = bo.getOrderNo().trim();
        List<WsOrder> orders = identityMapper.selectOrdersByOrderNoIncludingDeleted(orderNo);
        if (orders == null || orders.size() != 1) {
            throw new JbkException("订单不存在或无权访问");
        }
        WsOrder order = orders.get(0);
        if (!ObjectUtil.equals(order.getUserId(), userId)
                || !ObjectUtil.equals(order.getDataStatus(), 0)
                || !ObjectUtil.equals(order.getOrderType(), ORDER_TYPE_RECHARGE)) {
            throw new JbkException("订单不存在或无权访问");
        }
        List<WsPayment> payments = identityMapper.selectPaymentsByOrderIdIncludingDeleted(order.getId());
        if (payments == null || payments.size() != 1 || !ObjectUtil.equals(payments.get(0).getDataStatus(), 0)) {
            throw new JbkException("订单支付单异常，请联系客服");
        }
        WsPayment payment = payments.get(0);
        int paySource = paySourceAdapter.currentSource();
        if (!ObjectUtil.equals(payment.getPaySource(), paySource)) {
            // 支付单是微信来源却想用 Pay-Sim 操作：拒绝，绝不让模拟事实混进真实收款口径
            throw JbkException.internal("支付单来源与当前适配器不一致，" + refuseLabel);
        }
        return new Target(order, payment, paySource);
    }

    private record Target(WsOrder order, WsPayment payment, int paySource) {
    }

    /**
     * 可支付性判定：null=放行。只放行契约 §9.1 的 payment 1/order 1 且未过 PAY_EXPIRE_TIME；
     * 拒因按订单态优先给出——笼统文案会让用户反复重试永远付不成的单。
     */
    private static String rejectReason(WsOrder order, WsPayment payment, String now) {
        return RechargePayEligibility.rejectReason(order, payment, now);
    }

    /**
     * 查单证据报文（字段顺序固定保证摘要可复算）。刻意不含金额与交易号（NOTPAY/CLOSED 时
     * 支付方本就不返回，写内部值=伪证）；刻意不含时间戳——同键重复轮询必须产出同一正文，
     * 否则跨秒轮询会在 requireSameDigest（§5.3）被判摘要不一致转人工。
     */
    private static String queryRawBody(String orderNo, String tradeState) {
        return "{\"source\":\"PAY_SIM\",\"channel\":\"QUERY\",\"out_trade_no\":\"" + orderNo
                + "\",\"trade_state\":\"" + tradeState + "\"}";
    }

    /** 与真实微信通知同构的最小报文（字段顺序固定，保证摘要可复算）。 */
    private static String simRawBody(String orderNo, String transactionId, Long payAmount, String successTime) {
        return "{\"source\":\"PAY_SIM\",\"out_trade_no\":\"" + orderNo
                + "\",\"transaction_id\":\"" + transactionId
                + "\",\"trade_state\":\"SUCCESS\",\"amount\":{\"total\":" + payAmount
                + ",\"currency\":\"" + CURRENCY_CNY + "\"},\"success_time\":\"" + successTime + "\"}";
    }

    private static String sha256Hex(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new JbkException("摘要算法不可用");
        }
    }
}
