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
        // ③ 状态：冻结/注销/过期/未知一律 fail-closed
        verifyCardUsable(card, now);

        // ④ 原子扣减（复用取水域同一 SQL，绝不写第二份）：金额余额支付，不扣水量（规则1）
        int affected = tradeCardMapper.deductBalance(order.getCardId(), order.getOrderAmount(), userId, userId, now);
        if (affected != 1) {
            throw diagnoseDeductFailure(order.getCardId(), userId);
        }
        WsCard after = tradeCardMapper.selectById(order.getCardId());
        if (ObjectUtil.isNull(after)) {
            throw new JbkException("水卡数据异常，请重试");
        }

        // ⑤ 订单：uk_order_no 撞键说明同请求并发/重放，由编排层捕获后转幂等返回（本事务整体回滚，扣减一并撤销）
        orderMapper.insert(order);

        // ⑥ 唯一流水：业务幂等键 DELIVERY:<orderNo>（规则3；uk_wallet_flow_biz_key 数据库层防双扣）
        walletFlowMapper.insert(buildConsumeFlow(order, after));

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
                        .set("waterAmountFen", task.getWaterAmount())
                        .set("deliveryFeeFen", task.getDeliveryFee())
                        .set("totalFen", order.getOrderAmount())
                        .set("scheduledTime", task.getScheduledTime())
                        .set("decidedAt", now));
        return order;
    }

    /**
     * 落库前的最后一道自洽闸：订单/任务共键一致 + 总额恒等式（规则2）。
     * 编排层装配错位（金额拆分与总额不等、任务归属漂移）宁可拒单也不能带病入库。
     */
    private void requireConsistentDraft(WsOrder order, WsDeliveryTask task) {
        if (ObjectUtil.isNull(order) || ObjectUtil.isNull(task)) {
            throw new JbkException("配送创单数据不完整");
        }
        boolean consistent = ObjectUtil.equal(order.getOrderType(), TradeEnum.OrderType.DELIVERY.getValue())
                && ObjectUtil.equal(order.getOrderStatus(), TradeEnum.OrderStatus.PAID.getValue())
                && ObjectUtil.equal(order.getPayWay(), TradeEnum.PayWay.CARD_BALANCE.getValue())
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

    /** 扣减 0 行时的精确拒因（同事务读取当前值；锁内已排除归属/状态，剩余基本是余额不足）。 */
    private JbkException diagnoseDeductFailure(Long cardId, Long expectedOwnerUserId) {
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
        return new JbkException("水卡余额不足以支付本单水费与配送费");
    }

    private WsWalletFlow buildConsumeFlow(WsOrder order, WsCard after) {
        WsWalletFlow flow = new WsWalletFlow()
                .setCardId(order.getCardId())
                .setUserId(order.getUserId())
                .setFlowType(TradeEnum.FlowType.DELIVERY_CONSUME.getValue())
                .setAmountChange(-order.getOrderAmount())
                .setMlChange(0L)
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
