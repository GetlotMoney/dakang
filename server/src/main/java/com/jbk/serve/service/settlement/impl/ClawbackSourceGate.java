package com.jbk.serve.service.settlement.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.delivery.WsDeliveryAppealMapper;
import com.jbk.serve.mapper.delivery.WsDeliveryTaskMapper;
import com.jbk.serve.service.aftersale.AfterSaleNo;
import com.jbk.tool.consts.aftersale.AfterSaleEnum;
import com.jbk.tool.consts.delivery.DeliveryEnum;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.data.aftersale.po.WsAfterSaleAction;
import com.jbk.tool.data.delivery.po.WsDeliveryAppeal;
import com.jbk.tool.data.delivery.po.WsDeliveryTask;
import com.jbk.tool.data.trade.po.WsOrder;

/**
 * 分润冲减来源共键校验器（R4-P1-1）——processAction 的统一证据闸，全部来源归属
 * 判定集中在此，不在事务方法里堆分支。
 *
 * <p>只核对枚举与订单类型不足以证明来源归属：损坏或错绑的动作（sourceId 指向别处、
 * 售后号被改写、申诉挂错订单）只要类型对得上就能穿过分类闸。本校验器要求：</p>
 * <ol>
 *   <li><b>来源↔订单类型</b>：配送取消/配送申诉→配送单、取水异常→取水单、
 *       充值退款→购卡充值单；未知来源恒不匹配（fail-closed，新来源必须显式接入）。</li>
 *   <li><b>售后号确定性派生</b>：AFTER_SALE_NO 必须等于 AfterSaleNo.derive(sourceType,
 *       sourceId)——它是 uk_after_sale_source 撞键幂等的语义基准，改写即证据断链。</li>
 *   <li><b>来源主体锚定</b>：直锚订单的来源（配送取消/取水异常/充值退款，SOURCE_ID
 *       语义=ws_order.ID）必须 sourceId==order.ID；配送申诉必须当前读到在位且未逻辑删
 *       的申诉行，且 appeal.ORDER_ID==order.ID、appeal.USER_ID==action.USER_ID、
 *       申诉任务在位且 task.ORDER_ID==order.ID（用户/任务/订单三共键一致）。</li>
 *   <li><b>取消终态确认</b>（仅零分账收敛前调用）：「待接单取消」不能只凭 sourceType
 *       推断——订单必须已退款、配送任务必须已取消（签收态即履约发生，恒不放行）。</li>
 * </ol>
 *
 * <p>任一共键不符：调用方整动作转人工，零资金、零份额更新。本类只做当前读，
 * 不产生任何写入。</p>
 */
final class ClawbackSourceGate {

    private final WsDeliveryAppealMapper appealMapper;
    private final WsDeliveryTaskMapper deliveryTaskMapper;

    ClawbackSourceGate(WsDeliveryAppealMapper appealMapper, WsDeliveryTaskMapper deliveryTaskMapper) {
        this.appealMapper = appealMapper;
        this.deliveryTaskMapper = deliveryTaskMapper;
    }

    /** 全来源共键核验：返回不合格原因；null=合格。 */
    String coKeyMismatch(WsAfterSaleAction action, WsOrder order) {
        Integer sourceType = action.getSourceType();
        if (!sourceMatchesOrderType(sourceType, order.getOrderType())) {
            return "售后来源与订单类型不一致：source=" + sourceType
                    + " orderType=" + order.getOrderType();
        }
        // 非正 sourceId（损坏数据）在 derive 前拦停（复验遗留加固）：derive 对其抛出，
        // 而本校验器在执行段证据 catch 之外——放它过去=执行段异常、Worker 每 30 秒空转重试；
        // 转人工才是终态
        if (action.getSourceId() == null || action.getSourceId() <= 0) {
            return "来源主体ID非法（" + action.getSourceId() + "），无法核验来源归属";
        }
        // 售后号=确定性派生（此时来源已知且 sourceId 为正，derive 不会抛出）
        String derived = AfterSaleNo.derive(sourceType, action.getSourceId());
        if (ObjectUtil.notEqual(derived, action.getAfterSaleNo())) {
            return "售后号与来源派生不一致（sourceType=" + sourceType + " sourceId="
                    + action.getSourceId() + "），证据链断裂";
        }
        if (isOrderAnchoredSource(sourceType)) {
            if (ObjectUtil.notEqual(action.getSourceId(), order.getId())) {
                return "来源主体与订单不一致：sourceId=" + action.getSourceId()
                        + " order=" + order.getId();
            }
            return null;
        }
        // DELIVERY_APPEAL：申诉实体三共键（selectById 走 @TableLogic，逻辑删=查不到）
        WsDeliveryAppeal appeal = appealMapper.selectById(action.getSourceId());
        if (ObjectUtil.isNull(appeal)) {
            return "申诉记录不存在或已被逻辑删除：appeal=" + action.getSourceId();
        }
        if (ObjectUtil.notEqual(appeal.getOrderId(), order.getId())) {
            return "申诉归属订单不一致：appeal.orderId=" + appeal.getOrderId()
                    + " order=" + order.getId();
        }
        if (ObjectUtil.notEqual(appeal.getUserId(), action.getUserId())) {
            return "申诉用户与售后用户不一致：appeal.userId=" + appeal.getUserId()
                    + " action.userId=" + action.getUserId();
        }
        if (appeal.getTaskId() == null) {
            return "申诉缺任务共键：appeal=" + appeal.getId();
        }
        WsDeliveryTask task = deliveryTaskMapper.selectById(appeal.getTaskId());
        if (ObjectUtil.isNull(task) || ObjectUtil.notEqual(task.getOrderId(), order.getId())) {
            return "申诉任务与订单不一致：task=" + appeal.getTaskId() + " order=" + order.getId();
        }
        return null;
    }

    /**
     * 「待接单取消」零分账收敛前的终态确认：订单已退款 + 配送任务已取消 +
     * 未派单未履约的强证据（复验遗留加固）——取消事务自身以 {@code COURIER_ID IS NULL}
     * 为前态守卫、取消后配送员恒空（DeliveryCancelTxDbTest 钉死的语义），接单/签收
     * 时间只在履约推进时写入；任一痕迹在场即不是待接单取消。任务签收（5）即履约
     * 发生，任务缺失即证据不完整——都不放行。返回原因；null=合格。
     */
    String cancelStageMismatch(WsOrder order) {
        if (ObjectUtil.notEqual(order.getOrderStatus(),
                TradeEnum.OrderStatus.REFUNDED.getValue())) {
            return "订单未处于已退款终态（" + order.getOrderStatus() + "），取消零冲减不成立";
        }
        WsDeliveryTask task = deliveryTaskMapper.selectOne(Wrappers.lambdaQuery(WsDeliveryTask.class)
                .eq(WsDeliveryTask::getOrderId, order.getId()));
        if (ObjectUtil.isNull(task)) {
            return "配送任务缺失，取消终态无法确认：order=" + order.getId();
        }
        if (ObjectUtil.notEqual(task.getTaskStatus(),
                DeliveryEnum.TaskStatus.CANCELLED.getValue())) {
            return "配送任务未处于已取消态（" + task.getTaskStatus() + "），取消零冲减不成立";
        }
        if (task.getCourierId() != null) {
            return "配送任务已挂配送员（" + task.getCourierId() + "），待接单取消不成立";
        }
        if (StrUtil.isNotBlank(task.getAcceptTime()) || StrUtil.isNotBlank(task.getSignTime())) {
            return "配送任务存在履约时间痕迹（接单/签收），待接单取消不成立";
        }
        return null;
    }

    /** SOURCE_ID 语义=ws_order.ID 的直锚来源（AfterSaleEnum.SourceType 注释是口径源）。 */
    private static boolean isOrderAnchoredSource(Integer sourceType) {
        return ObjectUtil.equal(sourceType, AfterSaleEnum.SourceType.DELIVERY_CANCEL.getValue())
                || ObjectUtil.equal(sourceType, AfterSaleEnum.SourceType.WATER_ABNORMAL.getValue())
                || ObjectUtil.equal(sourceType, AfterSaleEnum.SourceType.RECHARGE_REFUND.getValue());
    }

    /** 来源→订单类型一致性：未知来源恒不匹配（fail-closed，新来源必须显式接入分类）。 */
    private static boolean sourceMatchesOrderType(Integer sourceType, Integer orderType) {
        if (ObjectUtil.equal(sourceType, AfterSaleEnum.SourceType.DELIVERY_CANCEL.getValue())
                || ObjectUtil.equal(sourceType, AfterSaleEnum.SourceType.DELIVERY_APPEAL.getValue())) {
            return ObjectUtil.equal(orderType, TradeEnum.OrderType.DELIVERY.getValue());
        }
        if (ObjectUtil.equal(sourceType, AfterSaleEnum.SourceType.WATER_ABNORMAL.getValue())) {
            return ObjectUtil.equal(orderType, TradeEnum.OrderType.WATER.getValue());
        }
        if (ObjectUtil.equal(sourceType, AfterSaleEnum.SourceType.RECHARGE_REFUND.getValue())) {
            return ObjectUtil.equal(orderType, TradeEnum.OrderType.CARD.getValue());
        }
        return false;
    }
}
