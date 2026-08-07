package com.jbk.serve.service.delivery.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.delivery.WsDeliveryAutoRuleMapper;
import com.jbk.serve.mapper.delivery.WsDeliveryTaskMapper;
import com.jbk.serve.mapper.trade.TradeCardMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.service.aftersale.AfterSaleQuota;
import com.jbk.serve.service.aftersale.batch.EntitlementLedger;
import com.jbk.serve.service.aftersale.AfterSaleStrategy;
import com.jbk.serve.service.aftersale.IAfterSaleActionTxService;
import com.jbk.serve.service.delivery.DeliveryClock;
import com.jbk.serve.service.delivery.DeliveryConsumeFlow;
import com.jbk.serve.service.delivery.DeliveryLinkGuard;
import com.jbk.serve.service.delivery.DeliveryRefundSnapshot;
import com.jbk.serve.service.delivery.DeliveryTransitions;
import com.jbk.serve.service.delivery.IDeliveryOrderTxService;
import com.jbk.serve.service.message.IWsMessageService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.aftersale.AfterSaleEnum;
import com.jbk.tool.consts.delivery.DeliveryEnum;
import com.jbk.tool.consts.message.MessageEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.consts.user.UserEnum;
import com.jbk.tool.data.aftersale.po.WsAfterSaleAction;
import com.jbk.tool.data.delivery.po.WsDeliveryAutoRule;
import com.jbk.tool.data.delivery.po.WsDeliveryTask;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsWalletFlow;
import com.jbk.tool.data.user.po.WsCard;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 配送订单资金事务实现（资金铁律1：原子 UPDATE 扣减 + 同事务流水，禁止读-算-写）。
 *
 * <p>D-214 双支付方式：payWay=2 全余额；payWay=3 混合结算（水费按快照 waterMl 扣
 * BALANCE_ML、配送费扣 BALANCE_AMOUNT，两步同事务、任一失败整体回滚）。</p>
 *
 * <p>E2E-04 包A 追加待接单取消（{@link #cancelPendingDeliveryOrder}）：创单的镜像动作，
 * 只推状态并登记一笔待执行返还，<b>资金写入不在本类</b>——返还由售后内核在独立事务执行。</p>
 *
 * @author dakang
 * @since 2026-07-23
 */
@Service
public class DeliveryOrderTxServiceImpl implements IDeliveryOrderTxService {


    /** 待接单取消的订单取消原因；同时是台账与审计里的口径来源。 */
    private static final String CANCEL_REASON = "用户在配送员接单前自助取消配送订单";

    @Autowired
    private TradeCardMapper tradeCardMapper;
    @Autowired
    private WsOrderMapper orderMapper;
    @Autowired
    private WsWalletFlowMapper walletFlowMapper;
    @Autowired
    private WsDeliveryTaskMapper taskMapper;
    @Autowired
    private WsDeliveryAutoRuleMapper autoRuleMapper;
    @Autowired
    private IWsMessageService messageService;
    @Autowired
    private IWsDomainEventService domainEventService;
    /**
     * 售后执行内核：{@code createPending} 是 {@code REQUIRED} 传播，会并入本类取消事务。
     * 取消若回滚，那条待执行返还必须一并消失，否则会退一笔「订单其实没取消」的钱。
     */
    @Autowired
    private IAfterSaleActionTxService afterSaleActionTxService;
    /** 权益批次台账（包D-4）：创单扣减写分摊。无自有事务边界，本类的事务就是它的边界。 */
    @Autowired
    private EntitlementLedger entitlementLedger;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WsOrder createPaidDeliveryOrder(WsOrder order, WsDeliveryTask task, WsDeliveryAutoRule autoRule, String now) {
        requireConsistentDraft(order, task);
        boolean payByMl = ObjectUtil.equal(order.getPayWay(), TradeEnum.PayWay.CARD_ML.getValue());
        // 抵扣水量的唯一权威值来自创单冻结快照（buildSnap.waterMl）：扣的必须恰是快照里冻结的量
        long deductMl = payByMl ? requireSnapWaterMl(order) : 0L;
        Long userId = order.getUserId();

        // ① 锁卡（绕过 @TableLogic 读全部 DATA_STATUS：删除卡也要锁到并显式拒绝）
        WsCard card = tradeCardMapper.selectByIdForUpdate(order.getCardId());
        if (ObjectUtil.isNull(card) || ObjectUtil.notEqual(card.getDataStatus(), 0)) {
            throw new JbkException("水卡不存在或不属于当前用户");
        }
        // ② 归属：配送单只允许本人卡支付（成员卡不参与配送支付，包A 冻结口径）
        if (ObjectUtil.notEqual(card.getUserId(), userId)) {
            throw new JbkException("水卡不存在或不属于当前用户");
        }
        // ③ 状态：冻结/注销/过期/未知一律 fail-closed。赠卡（有有效期的卡）在此不受额外限制：
        //    D-214 只对充值设闸（D-213），消费路径余额/水量均可正常抵扣
        verifyCardUsable(card, now);

        // ③b 批次到期清算（审计 P0-1）：与取水链共用 EntitlementLedger.settleExpired 唯一入口，
        //    扣减前作废已到期批次并同步扣卡；清算后余量不足由 ④ 的 CAS 正确拒绝。
        entitlementLedger.settleExpired(card, userId, now);

        // ④ 原子扣减（复用取水域两条既有 CAS SQL，绝不写第三份）：
        //    payWay=2 全余额一次扣清；payWay=3 同一事务先扣水量再扣配送费余额——
        //    两步任一影响 0 行即抛出，Spring 事务整体回滚，已扣的另一半一并撤销（原子性关键）
        if (payByMl) {
            int mlAffected = tradeCardMapper.deductMl(order.getCardId(), deductMl, userId, userId, now);
            if (mlAffected != 1) {
                throw diagnoseDeductFailure(order.getCardId(), userId, "水卡水量不足以抵扣本单水量");
            }
            int feeAffected = tradeCardMapper.deductBalance(order.getCardId(), order.getOrderAmount(),
                    userId, userId, now);
            if (feeAffected != 1) {
                throw diagnoseDeductFailure(order.getCardId(), userId, "水卡余额不足以支付配送费");
            }
        } else {
            int affected = tradeCardMapper.deductBalance(order.getCardId(), order.getOrderAmount(),
                    userId, userId, now);
            if (affected != 1) {
                throw diagnoseDeductFailure(order.getCardId(), userId, "水卡余额不足以支付本单水费与配送费");
            }
        }
        WsCard after = tradeCardMapper.selectById(order.getCardId());
        if (ObjectUtil.isNull(after)) {
            throw new JbkException("水卡数据异常，请重试");
        }

        // ⑤ 订单：uk_order_no 撞键说明同请求并发/重放，由编排层捕获后转幂等返回（本事务整体回滚，扣减一并撤销）
        orderMapper.insert(order);

        // ⑥ 唯一流水：业务幂等键 DELIVERY:<orderNo>（规则3；uk_wallet_flow_biz_key 数据库层防双扣）
        //    双支付方式仍恰一条：金额与水量变动记在同一行的两列上
        WsWalletFlow consumeFlow = buildConsumeFlow(order, after, deductMl);
        walletFlowMapper.insert(consumeFlow);

        // ⑥bis 权益批次分摊（E2E-04 包D-4，REQ-061）：与 ④ 的扣减同事务落到具体批次。
        //      两维额度与 ④ 逐维相等——payWay=3 的水量与配送费分别摊，绝不互相折算。
        //      分摊键与 ⑥ 的流水幂等键同源（DeliveryConsumeFlow），返还时才找得回这次消费。
        entitlementLedger.allocateOnConsume(
                new EntitlementLedger.ConsumeRef(order.getCardId(), userId, order.getId(),
                        consumeFlow.getId(), EntitlementLedger.consumeKey(order)),
                order.getOrderAmount(), deductMl, userId, now);

        // ⑦ 唯一任务：uk_dtask_order / uk_dtask_task_no 保证一单一任务（规则5/6）
        task.setOrderId(order.getId());
        taskMapper.insert(task);

        // ⑧ 自动补货：规则与首单同事务冻结（uk_dauto_rule_key 防并发重复建规则）
        if (ObjectUtil.isNotNull(autoRule)) {
            autoRuleMapper.insert(autoRule);
        }

        // ⑨ 站内消息（A6）：与创建动作同事务、同时间源
        messageService.sendInApp(userId, MessageEnum.MsgDomain.DELIVERY, "配送任务已生成",
                "您的配送订单 " + order.getOrderNo() + " 已生成，等待配送员接单。",
                "order", order.getOrderNo(), now);

        // ⑩ 可靠审计（与本事务同生共死 + 业务幂等键）：撞键读回核验语义、写入失败向上抛出整体回滚，
        //    业务回滚时审计一并消失，不留「创单失败、审计称成功」的幽灵记录
        domainEventService.recordReliableOnce(OpsEnum.EventType.ORDER_STATUS, order.getOrderNo(),
                "DELIVERY_CREATE:" + order.getOrderNo(), null,
                "配送创单成功：" + JSONUtil.createObj()
                        .set("orderNo", order.getOrderNo())
                        .set("taskNo", task.getTaskNo())
                        .set("payWay", order.getPayWay())
                        .set("waterAmountFen", task.getWaterAmount())
                        .set("deliveryFeeFen", task.getDeliveryFee())
                        .set("waterMl", deductMl)
                        .set("totalFen", order.getOrderAmount())
                        .set("scheduledTime", task.getScheduledTime())
                        .set("decidedAt", now));
        return order;
    }

    // ------------------------------------------------------------------
    // 待接单取消（E2E-04 包A 第一段事务）
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WsAfterSaleAction cancelPendingDeliveryOrder(Long orderId, Long actorUserId, String now) {
        DeliveryClock.requireTime(now, "当前时间");
        if (ObjectUtil.isNull(orderId) || orderId <= 0 || ObjectUtil.isNull(actorUserId) || actorUserId <= 0) {
            throw new JbkException("取消入参非法");
        }
        // 编排层的只读断言只用于给用户一句准确的拒绝话术，本事务全量重判、一条都不采信：
        // 那次断言与本事务之间隔着一次网络往返，任务完全可能已被配送员接单
        WsOrder order = orderMapper.selectById(orderId);
        WsDeliveryTask task = ObjectUtil.isNull(order) ? null
                : taskMapper.selectOne(Wrappers.lambdaQuery(WsDeliveryTask.class)
                        .eq(WsDeliveryTask::getOrderId, order.getId()));
        if (ObjectUtil.isNull(task)) {
            throw new JbkException("配送任务不存在，无法取消");
        }
        // 共键 + 可履约栅栏（订单精确 2已支付）；归属先行，非本人订单与不存在同一口径
        DeliveryLinkGuard.requireFulfillable(order, task);
        if (ObjectUtil.notEqual(order.getUserId(), actorUserId)) {
            throw new JbkException("订单不存在或无权访问");
        }
        int cancelled = DeliveryEnum.TaskStatus.CANCELLED.getValue();
        if (!DeliveryTransitions.allowed(task.getTaskStatus(), cancelled)) {
            // 状态机是这条边的唯一登记处；下面 CAS 的 WHERE 前态与本断言必须同为「1待接单」
            throw new JbkException("配送员已接单或任务已推进，无法自助取消，请联系客服");
        }

        // 返还额度先算：额度锚点取创单冻结快照，并与创单写下的那条扣款流水逐维精确相等
        // （AfterSaleQuota.caps 内部断言，不等即账实不符 fail-closed）。
        // 取消 = 原样退回，返还恒等于封顶上限，故直接取 fullRefund——此处没有、也不该有 payWay 分支。
        DeliveryRefundSnapshot.Parsed snap = DeliveryRefundSnapshot.require(order);
        WsWalletFlow deduct = DeliveryConsumeFlow.require(walletFlowMapper, order);
        AfterSaleQuota.Caps caps = AfterSaleQuota.caps(snap,
                DeliveryConsumeFlow.requireChange(deduct.getAmountChange(), "原扣款流水金额"),
                DeliveryConsumeFlow.requireChange(deduct.getMlChange(), "原扣款流水水量"));
        AfterSaleStrategy.Refund refund = AfterSaleStrategy.fullRefund(caps);

        // ① 任务 1→6：WHERE 带 ID + 前态 + VERSION + COURIER_ID IS NULL，影响行必须 == 1（铁律①）。
        //    COURIER_ID IS NULL 是与「1待接单」互为印证的第二条件：接单事务同时写状态与配送员，
        //    只认状态时，一个被外力改回 1 却仍挂着配送员的任务会被当成可取消
        int taskMoved = taskMapper.update(null, Wrappers.lambdaUpdate(WsDeliveryTask.class)
                .set(WsDeliveryTask::getTaskStatus, cancelled)
                .set(WsDeliveryTask::getVersion, task.getVersion() + 1)
                .set(WsDeliveryTask::getUpdateBy, actorUserId)
                .set(WsDeliveryTask::getUpdateTime, now)
                .eq(WsDeliveryTask::getId, task.getId())
                .eq(WsDeliveryTask::getTaskStatus, DeliveryEnum.TaskStatus.PENDING.getValue())
                .eq(WsDeliveryTask::getVersion, task.getVersion())
                .isNull(WsDeliveryTask::getCourierId));
        if (taskMoved != 1) {
            throw new JbkException("配送任务已被接单或状态已变化，取消失败");
        }

        // ② 订单 2→7已退款：影响行必须 == 1。这里就把订单落成"已退款"而钱尚未退，是本链路刻意
        //    承担的中间态——订单状态必须先于资金独占，否则两个并发取消会各自登记一笔满额返还。
        //    代价是「订单已是 7 但钱在途」，由售后动作的终态与用户提示兜底（见接口注释）。
        int orderMoved = orderMapper.update(null, Wrappers.lambdaUpdate(WsOrder.class)
                .set(WsOrder::getOrderStatus, TradeEnum.OrderStatus.REFUNDED.getValue())
                .set(WsOrder::getCancelReason, CANCEL_REASON)
                .set(WsOrder::getUpdateBy, actorUserId)
                .set(WsOrder::getUpdateTime, now)
                .eq(WsOrder::getId, order.getId())
                .eq(WsOrder::getUserId, actorUserId)
                .eq(WsOrder::getOrderStatus, TradeEnum.OrderStatus.PAID.getValue()));
        if (orderMoved != 1) {
            throw new JbkException("订单状态已变化，取消失败");
        }

        // ③ 登记待执行卡内退款。STRATEGY_CODE/APPROVED_COUNT/APPROVE_BY 恒为 NULL：
        //    取消没有补偿策略，也没有运营批准人（用户自助）。撞唯一键时内核按幂等返回既有行——
        //    重放取消不会产生第二笔返还（铁律②：幂等靠 uk_after_sale_source，不靠查重）
        WsAfterSaleAction draft = new WsAfterSaleAction()
                .setSourceType(AfterSaleEnum.SourceType.DELIVERY_CANCEL.getValue())
                .setSourceId(order.getId())
                .setOrderId(order.getId())
                .setUserId(order.getUserId())
                .setCardId(order.getCardId())
                .setActionType(AfterSaleEnum.ActionType.CARD_REFUND.getValue())
                .setRefundProductFen(refund.productFen())
                .setRefundServiceFen(refund.serviceFen())
                .setRefundProductMl(refund.productMl())
                .setCalcSnapshot(buildCancelSnapshot(order, snap, caps, refund, deduct, actorUserId, now));
        draft.setCreateBy(actorUserId);
        draft.setUpdateBy(actorUserId);
        WsAfterSaleAction action = afterSaleActionTxService.createPending(draft, now);

        messageService.sendInApp(order.getUserId(), MessageEnum.MsgDomain.DELIVERY, "配送订单已取消",
                "您的配送订单 " + order.getOrderNo() + " 已取消，退款正在处理中，到账后可在水卡明细查看。",
                "order", order.getOrderNo(), now);
        // 正向状态审计与业务同事务（铁律④）：取消若提交、审计必须一起在；幂等键按订单唯一（一单一取消）
        domainEventService.recordReliableOnce(OpsEnum.EventType.ORDER_STATUS, order.getOrderNo(),
                "DELIVERY_CANCEL:" + order.getOrderNo(),
                TradeEnum.OrderStatus.PAID.getValue() + ":" + TradeEnum.OrderStatus.PAID.getDesc(),
                "配送待接单取消：" + JSONUtil.createObj()
                        .set("orderNo", order.getOrderNo())
                        .set("taskNo", task.getTaskNo())
                        .set("afterSaleNo", action.getAfterSaleNo())
                        .set("toOrderStatus", TradeEnum.OrderStatus.REFUNDED.getValue())
                        .set("toTaskStatus", cancelled)
                        .set("refundProductFen", refund.productFen())
                        .set("refundServiceFen", refund.serviceFen())
                        .set("refundProductMl", refund.productMl())
                        .set("cancelledAt", now));
        return action;
    }


    /** 取消的计算依据快照：出账即冻结，事后只读不重算（重算会随价目表漂移，与原扣款对不上）。 */
    private String buildCancelSnapshot(WsOrder order, DeliveryRefundSnapshot.Parsed snap,
                                       AfterSaleQuota.Caps caps, AfterSaleStrategy.Refund refund,
                                       WsWalletFlow deduct, Long actorUserId, String now) {
        return JSONUtil.createObj()
                .set("reason", CANCEL_REASON)
                .set("payWay", snap.payWay())
                .set("orderAmount", order.getOrderAmount())
                .set("snapWaterAmountFen", snap.waterAmountFen())
                .set("snapDeliveryFeeFen", snap.deliveryFeeFen())
                .set("snapWaterMl", snap.waterMl())
                .set("snapDeliveryCount", snap.deliveryCount())
                .set("deductAmountChange", deduct.getAmountChange())
                .set("deductMlChange", deduct.getMlChange())
                .set("capProductFen", caps.capProductFen())
                .set("capServiceFen", caps.capServiceFen())
                .set("capProductMl", caps.capProductMl())
                .set("refundProductFen", refund.productFen())
                .set("refundServiceFen", refund.serviceFen())
                .set("refundProductMl", refund.productMl())
                .set("cancelledBy", actorUserId)
                .set("cancelledAt", now)
                .toString();
    }


    /**
     * payWay=3 的抵扣水量：只认创单冻结快照的 waterMl。快照缺失/非正数说明装配错位或
     * 数据被外力改写，宁可拒单也不能按 0 或猜测值扣减（金额与配额不许猜）。
     */
    private long requireSnapWaterMl(WsOrder order) {
        try {
            Long waterMl = JSONUtil.parseObj(order.getPackageSnap()).getLong("waterMl");
            if (ObjectUtil.isNull(waterMl) || waterMl <= 0) {
                throw new JbkException("配送创单数据不完整");
            }
            return waterMl;
        } catch (JbkException e) {
            throw e;
        } catch (Exception malformed) {
            throw new JbkException("配送创单数据不完整");
        }
    }

    /**
     * 落库前的最后一道自洽闸：订单/任务共键一致 + 总额恒等式（规则2，D-214 口径：
     * ORDER_AMOUNT = WATER_AMOUNT + DELIVERY_FEE 对两种支付方式一体成立——payWay=3 的
     * 水费以水量抵扣，WATER_AMOUNT 必须为 0，订单金额即配送费）。
     * 编排层装配错位（金额拆分与总额不等、任务归属漂移）宁可拒单也不能带病入库。
     */
    private void requireConsistentDraft(WsOrder order, WsDeliveryTask task) {
        if (ObjectUtil.isNull(order) || ObjectUtil.isNull(task)) {
            throw new JbkException("配送创单数据不完整");
        }
        boolean payByBalance = ObjectUtil.equal(order.getPayWay(), TradeEnum.PayWay.CARD_BALANCE.getValue());
        boolean payByMl = ObjectUtil.equal(order.getPayWay(), TradeEnum.PayWay.CARD_ML.getValue());
        boolean consistent = ObjectUtil.equal(order.getOrderType(), TradeEnum.OrderType.DELIVERY.getValue())
                && ObjectUtil.equal(order.getOrderStatus(), TradeEnum.OrderStatus.PAID.getValue())
                && (payByBalance || payByMl)
                && (!payByMl || ObjectUtil.equal(task.getWaterAmount(), 0L))
                && ObjectUtil.equal(order.getUserId(), task.getUserId())
                && ObjectUtil.equal(order.getStationId(), task.getStationId())
                && ObjectUtil.isNotNull(task.getWaterAmount()) && task.getWaterAmount() >= 0
                && ObjectUtil.isNotNull(task.getDeliveryFee()) && task.getDeliveryFee() >= 0
                && ObjectUtil.isNotNull(order.getOrderAmount()) && order.getOrderAmount() > 0
                && order.getOrderAmount() == task.getWaterAmount() + task.getDeliveryFee();
        if (!consistent) {
            throw new JbkException("配送订单与任务快照不一致，拒绝创建");
        }
    }

    /** 卡状态闸（与取水域 verifyLockedCard 同口径；配送包A 只认状态1正常且未过期）。 */
    private void verifyCardUsable(WsCard card, String now) {
        if (ObjectUtil.equal(card.getCardStatus(), UserEnum.CardStatus.FROZEN.getValue())) {
            throw new JbkException("水卡已冻结，无法支付配送单");
        }
        if (ObjectUtil.equal(card.getCardStatus(), UserEnum.CardStatus.CANCELLED.getValue())) {
            throw new JbkException("水卡已注销");
        }
        boolean expiredByTime = StrUtil.isNotBlank(card.getExpireTime())
                && card.getExpireTime().compareTo(now) <= 0;
        if (ObjectUtil.equal(card.getCardStatus(), UserEnum.CardStatus.EXPIRED.getValue()) || expiredByTime) {
            throw new JbkException("水卡已过期");
        }
        if (ObjectUtil.notEqual(card.getCardStatus(), UserEnum.CardStatus.NORMAL.getValue())) {
            // 未知状态 fail-closed：新增状态必须显式登记后才可参与支付
            throw new JbkException("水卡状态异常，无法支付配送单");
        }
    }

    /**
     * 扣减 0 行时的精确拒因（同事务读取当前值；锁内已排除归属/状态，剩余基本是余量不足）。
     * 余量不足的文案由调用点按扣减对象传入：payWay=3 两步扣减分别给出
     * 「水量不足以抵扣」与「余额不足以支付配送费」，不得混用成一句让用户猜。
     */
    private JbkException diagnoseDeductFailure(Long cardId, Long expectedOwnerUserId, String insufficientMessage) {
        WsCard card = tradeCardMapper.selectById(cardId);
        if (ObjectUtil.isNull(card) || ObjectUtil.notEqual(expectedOwnerUserId, card.getUserId())) {
            return new JbkException("水卡不存在或不属于当前用户");
        }
        if (ObjectUtil.equal(card.getCardStatus(), UserEnum.CardStatus.FROZEN.getValue())) {
            return new JbkException("水卡已冻结，无法支付配送单");
        }
        if (ObjectUtil.equal(card.getCardStatus(), UserEnum.CardStatus.CANCELLED.getValue())) {
            return new JbkException("水卡已注销");
        }
        boolean expiredByTime = StrUtil.isNotBlank(card.getExpireTime())
                && card.getExpireTime().compareTo(DateUtils.time()) <= 0;
        if (ObjectUtil.equal(card.getCardStatus(), UserEnum.CardStatus.EXPIRED.getValue()) || expiredByTime) {
            return new JbkException("水卡已过期");
        }
        return new JbkException(insufficientMessage);
    }

    /**
     * 唯一消费流水（规则3）：AMOUNT_CHANGE 记金额部分（payWay=3 即 -配送费），
     * ML_CHANGE 记水量部分（payWay=2 恒 0）；AFTER 双列取扣减后重读的卡面值，
     * 幂等键 DELIVERY:&lt;orderNo&gt; 与 uk_wallet_flow_biz_key 口径不变。
     */
    private WsWalletFlow buildConsumeFlow(WsOrder order, WsCard after, long deductMl) {
        WsWalletFlow flow = new WsWalletFlow()
                .setCardId(order.getCardId())
                .setUserId(order.getUserId())
                .setFlowType(TradeEnum.FlowType.DELIVERY_CONSUME.getValue())
                .setAmountChange(-order.getOrderAmount())
                .setMlChange(-deductMl)
                .setAmountAfter(after.getBalanceAmount())
                .setMlAfter(after.getBalanceMl())
                .setOrderId(order.getId())
                .setFlowRemark("水配送 " + order.getOrderNo())
                .setBizIdempotencyKey(DeliveryConsumeFlow.bizKey(order.getOrderNo()));
        // 流水时间与订单创建同源（对账时间轴不允许分叉）
        flow.setCreateTime(order.getCreateTime());
        flow.setUpdateTime(order.getCreateTime());
        return flow;
    }

}
