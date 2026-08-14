package com.jbk.serve.service.mall.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.mall.WsMallOrderItemMapper;
import com.jbk.serve.mapper.mall.WsMallOrderMapper;
import com.jbk.serve.mapper.mall.WsMallPaymentFactMapper;
import com.jbk.serve.mapper.mall.WsMallPaymentMapper;
import com.jbk.serve.mapper.mall.WsMallStockFlowMapper;
import com.jbk.serve.mapper.mall.WsMallStockMapper;
import com.jbk.serve.service.mall.IMallPayApplyTx;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.mall.MallEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.mall.po.WsMallOrder;
import com.jbk.tool.data.mall.po.WsMallOrderItem;
import com.jbk.tool.data.mall.po.WsMallPayment;
import com.jbk.tool.data.mall.po.WsMallPaymentFact;
import com.jbk.tool.data.mall.po.WsMallStock;
import com.jbk.tool.data.mall.po.WsMallStockFlow;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 商城支付推进段实现（事务B，E2E-09 S2）。共键：事实订单号须唯一定位订单与支付单且
 * ORDER_ID/PAYMENT_ID 相符，错位转人工（指错订单的成功事实会把别人的货发出去）。
 * 金额三方一致：事实金额=支付单应付=订单总额，差一分不推进。幂等三保险：
 * ①实销流水唯一键 MALLSALE:&lt;orderNo&gt;:&lt;skuId&gt;；②订单状态 CAS 1→2；③事实处理状态 claim。
 *
 * @author dakang
 * @since 2026-08-08
 */
@Slf4j
@Service
public class MallPayApplyTxImpl implements IMallPayApplyTx {

    /** 实销幂等键前缀。 */
    static final String SELL_KEY_PREFIX = "MALLSALE:";

    @Autowired
    private WsMallPaymentFactMapper factMapper;
    @Autowired
    private WsMallOrderMapper orderMapper;
    @Autowired
    private WsMallOrderItemMapper orderItemMapper;
    @Autowired
    private WsMallPaymentMapper paymentMapper;
    @Autowired
    private WsMallStockMapper stockMapper;
    @Autowired
    private WsMallStockFlowMapper flowMapper;
    @Autowired
    private IWsDomainEventService domainEventService;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class,
            isolation = Isolation.READ_COMMITTED)
    public Outcome apply(Long factId) {
        WsMallPaymentFact fact = factMapper.selectById(factId);
        if (ObjectUtil.isNull(fact)) {
            return Outcome.reconcile("支付事实不存在");
        }
        if (!MallEnum.TradeState.isKnown(fact.getTradeState())) {
            // 第二道白名单：准入之后事实仍可能被改写。"所有非 SUCCESS 都当没事"
            // 会把 REFUND、UNKNOWN 这类读不懂的状态盖成已处理，从此无人再看。
            return Outcome.reconcile("未知交易状态，不得推进：" + fact.getTradeState());
        }
        if (!MallEnum.TradeState.SUCCESS.equals(fact.getTradeState())) {
            // 非成功事实在本段无资金动作：NOTPAY 等待、CLOSED 由关单链路处理
            return Outcome.already("非成功事实，无需推进");
        }
        WsMallOrder order = orderMapper.selectByOrderNoIncludingDeleted(fact.getOrderNo());
        WsMallPayment payment = paymentMapper.selectByOrderNoIncludingDeleted(fact.getOrderNo());
        // 基础共键与事务A 同一份判据（含逻辑删除与业务时间真实性），不复制第二套
        String mismatch = MallPayFactGate.linkMismatch(fact.getOrderId(), fact.getPaymentId(),
                fact.getPaySource(), fact.getPayAmountFen(), fact.getCurrency(),
                fact.getTransactionId(), fact.getPaySuccessTime(), order, payment);
        if (mismatch != null) {
            return Outcome.reconcile(mismatch);
        }
        // 事务B 独有：支付单必须仍精确处于**这一条事实**推成的成功态。两段之间隔着一次
        // 提交，支付单可能被另一笔交易改过——此刻实销等于把货记到别人那笔钱上
        String settle = MallPayFactGate.settleMismatch(fact, payment);
        if (settle != null) {
            return Outcome.reconcile(settle);
        }

        int orderStatus = order.getOrderStatus() == null ? 0 : order.getOrderStatus();
        if (orderStatus == MallEnum.OrderStatus.CANCELLED.getValue()) {
            // 已取消/已关闭却收到成功事实：钱可能真的收了，但库存早已释放。
            // 绝不能重新扣库存或把订单拉回已支付——只留证据转人工。
            return Outcome.reconcile("订单已取消/关闭，收到支付成功事实需人工对账");
        }

        List<WsMallOrderItem> items = orderItemMapper.selectByOrderIdOrderBySku(order.getId());
        if (items.isEmpty()) {
            return Outcome.reconcile("订单无明细，无法实销");
        }
        if (orderStatus != MallEnum.OrderStatus.PENDING_PAY.getValue()) {
            return settledOrReconcile(fact.getOrderNo(), items);
        }
        String now = DateUtils.time();
        // 先推进订单状态：CAS 命中者唯一，后续实销因此只会发生一次
        int moved = orderMapper.casStatus(order.getId(),
                MallEnum.OrderStatus.PENDING_PAY.getValue(),
                MallEnum.OrderStatus.PAID.getValue(), order.getUserId(), now);
        if (moved != 1) {
            // 0 行不等于"别人已经替我做完了"：也可能订单刚被删除、被取消、或推进了却没实销。
            // 必须核到证据齐全才算幂等完成，否则转人工。
            return settledOrReconcile(fact.getOrderNo(), items);
        }
        for (WsMallOrderItem item : items) {
            sellOne(order, item, now);
        }
        domainEventService.recordReliableInTx(OpsEnum.EventType.MALL,
                "MALLORDER:" + order.getOrderNo(), "支付成功实销",
                "交易号 " + fact.getTransactionId() + "，实销 " + items.size() + " 个规格");
        return Outcome.applied();
    }

    /**
     * 订单已处于推进后状态时，判定是"确已完成"还是"证据缺失需人工"。
     *
     * <p>判据是实销流水：每个明细都必须有一条 MALLSALE 流水且预占扣减量与数量相符。
     * 只看订单状态是不够的——状态被别的路径改过、而实销从未发生，正是最需要人工介入
     * 的那种情况，却最容易被当成"幂等已完成"放过去。</p>
     */
    private Outcome settledOrReconcile(String orderNo, List<WsMallOrderItem> items) {
        // 必须自己重读订单：两个调用点里有一个拿的是本方法开头那次读，中间还夹着几次库查询，
        // 期间订单可能已被并发推进、取消或删除。拿陈旧快照判"是否已完成"等于没判。
        WsMallOrder latest = orderMapper.selectByOrderNoIncludingDeleted(orderNo);
        if (ObjectUtil.isNull(latest) || !ObjectUtil.equal(latest.getDataStatus(), 0)) {
            return Outcome.reconcile("订单已被删除，无法判定实销是否完成");
        }
        if (!MallPayFactGate.isAdvancedBeyondPay(latest)) {
            return Outcome.reconcile("订单状态与本支付事实不相容（当前状态 "
                    + latest.getOrderStatus() + "），需人工对账");
        }
        for (WsMallOrderItem item : items) {
            if (!hasSellEvidence(latest, item)) {
                return Outcome.reconcile("订单已推进但实销流水缺失或不符，需人工核查");
            }
        }
        return Outcome.already("订单已推进且实销证据齐全");
    }

    /**
     * 一条明细的实销证据是否成立：流水存在、未被删除、类型与仓/SKU/数量全部对得上。
     *
     * <p>读的是"含逻辑删除"的流水，因为幂等键本就不含 DATA_STATUS。于是必须在这里自己
     * 把删除的排除掉：一条被删掉的流水足以挡住重复插入，却不足以证明货真的卖出去过。</p>
     */
    private boolean hasSellEvidence(WsMallOrder order, WsMallOrderItem item) {
        if (ObjectUtil.isNull(item.getQuantity())) {
            return false;
        }
        WsMallStockFlow sold = flowMapper.selectByKeyIncludingDeleted(
                SELL_KEY_PREFIX + order.getOrderNo() + ":" + item.getSkuId());
        return ObjectUtil.isNotNull(sold)
                && ObjectUtil.equal(sold.getDataStatus(), 0)
                && ObjectUtil.equal(sold.getFlowType(), MallEnum.StockFlowType.ORDER_SELL.getValue())
                && ObjectUtil.equal(sold.getWarehouseId(), order.getWarehouseId())
                && ObjectUtil.equal(sold.getSkuId(), item.getSkuId())
                && ObjectUtil.equal(sold.getReservedChange(),
                        Long.valueOf(-item.getQuantity().longValue()));
    }

    /** 单 SKU 原子实销 + 只增流水；幂等键撞键=已实销，跳过。 */
    private void sellOne(WsMallOrder order, WsMallOrderItem item, String now) {
        String bizKey = SELL_KEY_PREFIX + order.getOrderNo() + ":" + item.getSkuId();
        if (ObjectUtil.isNotNull(flowMapper.selectByKeyIncludingDeleted(bizKey))) {
            return;
        }
        long qty = item.getQuantity();
        int sold = stockMapper.sellAtomic(order.getWarehouseId(), item.getSkuId(),
                qty, order.getUserId(), now);
        if (sold != 1) {
            // 预占不足以实销：订单持有的预占不见了，属证据链断裂，整事务回滚等人工
            throw new JbkException("实销失败（预占证据异常），订单 " + order.getOrderNo());
        }
        WsMallStock after = stockMapper.selectOne(Wrappers.lambdaQuery(WsMallStock.class)
                .eq(WsMallStock::getWarehouseId, order.getWarehouseId())
                .eq(WsMallStock::getSkuId, item.getSkuId()));
        WsMallStockFlow flow = new WsMallStockFlow()
                .setBizIdempotencyKey(bizKey)
                .setWarehouseId(order.getWarehouseId())
                .setSkuId(item.getSkuId())
                .setFlowType(MallEnum.StockFlowType.ORDER_SELL.getValue())
                .setAvailableChange(0L)
                .setReservedChange(-qty)
                .setAvailableAfter(after.getAvailableQty())
                .setReservedAfter(after.getReservedQty())
                .setFlowReason("支付实销 " + order.getOrderNo())
                .setOperatorId(order.getUserId());
        flow.setCreateTime(now);
        try {
            flowMapper.insert(flow);
        }
        catch (DuplicateKeyException race) {
            throw new JbkException("订单正在处理中，请稍后再试");
        }
    }
}
