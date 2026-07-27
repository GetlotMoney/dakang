package com.jbk.serve.service.delivery.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.jbk.serve.mapper.delivery.WsDeliveryAutoRuleMapper;
import com.jbk.serve.mapper.delivery.WsDeliveryTaskMapper;
import com.jbk.serve.mapper.trade.TradeCardMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.service.delivery.IDeliveryOrderTxService;
import com.jbk.serve.service.message.IWsMessageService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.message.MessageEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.consts.user.UserEnum;
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
 * 配送创单资金事务实现（资金铁律1：原子 UPDATE 扣减 + 同事务流水，禁止读-算-写）。
 *
 * <p>D-214 双支付方式：payWay=2 全余额；payWay=3 混合结算（水费按快照 waterMl 扣
 * BALANCE_ML、配送费扣 BALANCE_AMOUNT，两步同事务、任一失败整体回滚）。
 * 补偿边界：payWay=3 单的退款/补偿以水量回补为主，本事务不实现 ML 回补，待 E2E-04。</p>
 *
 * @author dakang
 * @since 2026-07-23
 */
@Service
public class DeliveryOrderTxServiceImpl implements IDeliveryOrderTxService {

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
        walletFlowMapper.insert(buildConsumeFlow(order, after, deductMl));

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
                .setBizIdempotencyKey("DELIVERY:" + order.getOrderNo());
        // 流水时间与订单创建同源（对账时间轴不允许分叉）
        flow.setCreateTime(order.getCreateTime());
        flow.setUpdateTime(order.getCreateTime());
        return flow;
    }
}
