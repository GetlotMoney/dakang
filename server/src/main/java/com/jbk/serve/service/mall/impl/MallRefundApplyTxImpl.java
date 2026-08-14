package com.jbk.serve.service.mall.impl;

import cn.hutool.json.JSONUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.jbk.serve.mapper.mall.WsMallAfterSaleMapper;
import com.jbk.serve.mapper.mall.WsMallOrderMapper;
import com.jbk.serve.mapper.mall.WsMallPaymentMapper;
import com.jbk.serve.mapper.mall.WsMallRefundFactMapper;
import com.jbk.serve.mapper.mall.WsMallRefundMapper;
import com.jbk.serve.service.mall.IMallPayApplyTx;
import com.jbk.serve.service.mini.notify.WechatNotifyEnqueue;
import com.jbk.serve.service.mall.IMallRefundApplyTx;
import com.jbk.serve.service.message.IWsMessageService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.mall.MallEnum;
import com.jbk.tool.consts.mini.WechatNotifyEnum;
import com.jbk.tool.consts.message.MessageEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.mall.po.WsMallAfterSale;
import com.jbk.tool.data.mall.po.WsMallOrder;
import com.jbk.tool.data.mall.po.WsMallPayment;
import com.jbk.tool.data.mall.po.WsMallRefund;
import com.jbk.tool.data.mall.po.WsMallRefundFact;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商城退款推进段实现（事务B，E2E-09 S4）。两段式：渠道退款事实是外部证据丢了补不回，
 * 推进是内部动作可重试，绑成一个事务会把已收到的外部事实随推进失败一起回滚。
 * 事务B 不信任事务A：所有共键锁内重读重新核验，任一错位转人工，绝不接着往下走。
 *
 * @author dakang
 * @since 2026-08-10
 */
@Slf4j
@Service
public class MallRefundApplyTxImpl implements IMallRefundApplyTx {

    /** 退款轨迹与审计幂等键前缀。 */
    static final String REFUND_AUDIT_KEY_PREFIX = "MALL_REFUND:";
    private static final long SYSTEM_OPERATOR = 0L;

    /** 订阅通知登记：与站内信同事务——本事务回滚意味着那件事没发生，通知必须一起消失。 */
    @Autowired
    private WechatNotifyEnqueue notifyEnqueue;
    @Autowired
    private WsMallRefundFactMapper factMapper;
    @Autowired
    private WsMallRefundMapper refundMapper;
    @Autowired
    private WsMallAfterSaleMapper afterSaleMapper;
    @Autowired
    private MallAfterSaleTraceWriter traceWriter;
    @Autowired
    private WsMallOrderMapper orderMapper;
    @Autowired
    private WsMallPaymentMapper paymentMapper;
    @Autowired
    private IWsMessageService messageService;
    @Autowired
    private IWsDomainEventService domainEventService;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class,
            isolation = Isolation.READ_COMMITTED)
    public IMallPayApplyTx.Outcome apply(Long refundFactId) {
        WsMallRefundFact fact = factMapper.selectById(refundFactId);
        if (ObjectUtil.isNull(fact)) {
            return IMallPayApplyTx.Outcome.reconcile("退款事实不存在");
        }
        if (!MallEnum.RefundState.isKnown(fact.getRefundState())) {
            // 白名单第二道：读不懂的状态绝不当成"反正不是成功那就没事"
            return IMallPayApplyTx.Outcome.reconcile("未知退款状态，不得推进：" + fact.getRefundState());
        }
        if (!MallEnum.RefundState.SUCCESS.equals(fact.getRefundState())) {
            // PROCESSING 等待渠道终局；CLOSED 由人工处理，本段无资金动作
            return IMallPayApplyTx.Outcome.already("非成功退款事实，无需推进");
        }

        WsMallRefund refund = refundMapper.selectByNoIncludingDeleted(fact.getRefundNo());
        if (ObjectUtil.isNull(refund) || !ObjectUtil.equal(refund.getDataStatus(), 0)) {
            return IMallPayApplyTx.Outcome.reconcile("退款单不存在或已删除");
        }
        WsMallAfterSale afterSale = afterSaleMapper.selectById(refund.getAfterSaleId());
        // 订单行锁必须在读之前拿到：下面「累计成功退款是否已达商品实付」是一个跨行汇总判据，
        // 没有单条唯一键可表达。两笔部分退款并发推进时，非锁定读让双方都只看见自己那一半，
        // 于是都判「还没退完」，订单永久停在原终态——这是写偏斜，不是慢。
        WsMallOrder order = ObjectUtil.isNull(afterSale) ? null
                : orderMapper.selectByOrderNoForUpdate(afterSale.getOrderNo());
        String linkMiss = MallAfterSaleGate.linkMismatch(afterSale, order, null);
        if (linkMiss != null) {
            return IMallPayApplyTx.Outcome.reconcile(linkMiss);
        }
        WsMallPayment payment = paymentMapper.selectByOrderNoIncludingDeleted(order.getOrderNo());
        String payMiss = MallAfterSaleGate.paymentMismatch(order, payment);
        if (payMiss != null) {
            return IMallPayApplyTx.Outcome.reconcile(payMiss);
        }
        String refundMiss = MallAfterSaleGate.refundMismatch(refund, afterSale, order, payment);
        if (refundMiss != null) {
            return IMallPayApplyTx.Outcome.reconcile(refundMiss);
        }

        String factMiss = factMismatch(fact, refund);
        if (factMiss != null) {
            return IMallPayApplyTx.Outcome.reconcile(factMiss);
        }
        if (!ObjectUtil.equal(afterSale.getAfterSaleStatus(),
                MallEnum.AfterSaleStatus.REFUNDING.getValue())) {
            // 售后不在退款处理中：可能已完成（重放）也可能被撤销，两者结论不同，交人工判
            if (ObjectUtil.equal(afterSale.getAfterSaleStatus(),
                    MallEnum.AfterSaleStatus.COMPLETED.getValue())
                    && ObjectUtil.equal(refund.getRefundStatus(),
                            MallEnum.RefundStatus.SUCCESS.getValue())) {
                return IMallPayApplyTx.Outcome.already("退款已完成，无需重复推进");
            }
            return IMallPayApplyTx.Outcome.reconcile("售后单不处于退款处理中，需人工对账");
        }

        String now = DateUtils.time();
        int moved = refundMapper.casSuccess(refund.getId(), fact.getRefundTransactionId(),
                fact.getRefundSuccessTime(), SYSTEM_OPERATOR, now);
        if (moved != 1) {
            return IMallPayApplyTx.Outcome.reconcile("退款单状态已变化（可能已被另一笔事实推进），需人工对账");
        }
        int finished = afterSaleMapper.casFinish(afterSale.getId(),
                MallEnum.AfterSaleStatus.REFUNDING.getValue(),
                MallEnum.AfterSaleStatus.COMPLETED.getValue(), afterSale.getVersion(),
                SYSTEM_OPERATOR, now);
        if (finished != 1) {
            // 退款单已成功而售后没完成＝账实不符，整事务回滚重来，绝不留半截终态
            throw new JbkException("售后状态已变化，退款推进已中止");
        }

        // 累计成功退款达到商品实付即为全额退款，订单转 6；未达到则主订单保持原终态
        long succeeded = afterSaleMapper.sumSucceededRefundFen(order.getId());
        long productPaid = order.getProductAmountFen() == null ? 0L : order.getProductAmountFen();
        if (succeeded >= productPaid && productPaid > 0) {
            int toRefunded = orderMapper.casStatus(order.getId(), order.getOrderStatus(),
                    MallEnum.OrderStatus.REFUNDED.getValue(), SYSTEM_OPERATOR, now);
            if (toRefunded != 1) {
                throw new JbkException("订单状态已变化，退款推进已中止");
            }
        }

        writeTrace(afterSale, now, "退款成功：" + refund.getRefundAmountFen() + " 分");
        messageService.sendInApp(order.getUserId(), MessageEnum.MsgDomain.MALL,
                "商城退款已到账",
                "您的售后单 " + afterSale.getAfterSaleNo() + " 退款已原路退回。",
                "mallAfterSale", afterSale.getAfterSaleNo(), now);
        notifyEnqueue.enqueue(WechatNotifyEnum.EventType.REFUND_SETTLED,
                WechatNotifyEnum.BizObjectType.AFTER_SALE, afterSale.getAfterSaleNo(), order.getUserId(),
                JSONUtil.createObj().set("time", now).set("refundFen", refund.getRefundAmountFen()));
        domainEventService.recordReliableOnceAs(OpsEnum.ActorPortal.SYSTEM, SYSTEM_OPERATOR,
                OpsEnum.EventType.MALL, "MALLAFTERSALE:" + afterSale.getAfterSaleNo(),
                REFUND_AUDIT_KEY_PREFIX + refund.getRefundNo(),
                "退款成功", "交易号 " + fact.getRefundTransactionId());
        return IMallPayApplyTx.Outcome.applied();
    }

    /** 事实与退款单的逐项共键：金额、币种、交易号与时间缺一不可。 */
    private static String factMismatch(WsMallRefundFact fact, WsMallRefund refund) {
        if (!ObjectUtil.equal(fact.getRefundNo(), refund.getRefundNo())) {
            return "退款事实与退款单号不一致";
        }
        if (StrUtil.isBlank(fact.getRefundTransactionId())) {
            return "成功退款事实缺渠道交易号";
        }
        if (ObjectUtil.isNull(fact.getRefundAmountFen())
                || !ObjectUtil.equal(fact.getRefundAmountFen(), refund.getRefundAmountFen())) {
            return "退款金额与退款单不一致";
        }
        if (!ObjectUtil.equal(fact.getCurrency(), refund.getCurrency())) {
            return "退款币种与退款单不一致";
        }
        if (!DateUtils.isCanonicalBusinessTime(fact.getRefundSuccessTime())) {
            return "成功退款事实的时间不是合法业务时间";
        }
        return null;
    }

    /** 退款完成节点轨迹：走共享写入口，键被占用即证据冲突，整事务回滚。 */
    private void writeTrace(WsMallAfterSale afterSale, String now, String text) {
        traceWriter.write(afterSale, MallEnum.AfterSaleStatus.COMPLETED,
                MallEnum.ActorType.SYSTEM, SYSTEM_OPERATOR, null, now, text);
    }
}
