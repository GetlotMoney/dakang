package com.jbk.serve.service.mall.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.jbk.tool.consts.mall.MallEnum;
import com.jbk.tool.data.mall.po.WsMallFulfillment;
import com.jbk.tool.data.mall.po.WsMallLogisticsEvent;
import com.jbk.tool.data.mall.po.WsMallOrder;
import com.jbk.tool.data.mall.po.WsMallShipment;
import com.jbk.tool.utils.DateUtils;

/**
 * 出库包裹共键校验器（E2E-09 L1）：订单/履约/运单/明细共键的唯一出处。
 * 判据返回 null 表示通过，否则返回不一致原因文本，由调用方决定抛出或转人工
 * （与 {@code MallPayFactGate}/{@code MallAfterSaleGate} 同形）。
 *
 * @author dakang
 * @since 2026-08-11
 */
public final class MallShipmentGate {

    /** 自营承运商编码：自营包裹不走适配器，但仍要占住 PROVIDER_CODE 以让运单号唯一键可判定。 */
    public static final String SELF_PROVIDER = "SELF";
    /** 包裹幂等键前缀。 */
    public static final String SHIPMENT_KEY_PREFIX = "MSHIP:";
    /** 出站动作幂等键前缀。 */
    public static final String ACTION_KEY_PREFIX = "MLOG:";

    private MallShipmentGate() {
    }

    /** 包裹幂等键：确定性派生，同一（订单、方向、序号）恒得同一把键。 */
    public static String shipmentKey(String orderNo, int direction, int seq) {
        return SHIPMENT_KEY_PREFIX + orderNo + ":" + direction + ":" + seq;
    }

    /** 出站动作幂等键：同一包裹的同一动作只登记一次。 */
    public static String actionKey(Long shipmentId, int actionType) {
        return ACTION_KEY_PREFIX + shipmentId + ":" + actionType;
    }

    /**
     * 包裹 ↔ 履约总单 ↔ 订单 三方共键。逐项核到底：只比 FULFILL_ID 挡不住
     * 「包裹的 ORDER_NO 指向另一张订单」的错位。
     */
    public static String linkMismatch(WsMallShipment shipment, WsMallFulfillment task,
                                      WsMallOrder order) {
        if (ObjectUtil.isNull(shipment) || !ObjectUtil.equal(shipment.getDataStatus(), 0)) {
            return "包裹不存在或已删除";
        }
        if (ObjectUtil.isNull(task) || !ObjectUtil.equal(task.getDataStatus(), 0)) {
            return "履约任务不存在或已删除";
        }
        if (ObjectUtil.isNull(order) || !ObjectUtil.equal(order.getDataStatus(), 0)) {
            return "订单不存在或已删除";
        }
        if (!ObjectUtil.equal(shipment.getFulfillId(), task.getId())) {
            return "包裹与履约任务不匹配";
        }
        if (!ObjectUtil.equal(shipment.getOrderId(), order.getId())
                || !ObjectUtil.equal(shipment.getOrderNo(), order.getOrderNo())) {
            return "包裹与订单共键不一致";
        }
        if (!ObjectUtil.equal(task.getOrderId(), order.getId())
                || !ObjectUtil.equal(task.getOrderNo(), order.getOrderNo())) {
            return "履约任务与订单共键不一致";
        }
        if (!ObjectUtil.equal(shipment.getFulfillMode(), task.getFulfillMode())) {
            return "包裹承运渠道与履约任务冻结值不一致";
        }
        if (!MallEnum.ShipmentDirection.isKnown(shipment.getDirection())) {
            return "包裹方向不合法：" + shipment.getDirection();
        }
        if (!MallEnum.ShipmentStatus.isKnown(shipment.getShipmentStatus())) {
            return "包裹状态不合法：" + shipment.getShipmentStatus();
        }
        return null;
    }

    /**
     * 物流事实 ↔ 包裹 共键。事件只带运单号，须逐项核承运商/运单号/渠道；
     * 自营包裹永远不该收到第三方事件。
     */
    public static String factMismatch(WsMallLogisticsEvent event, WsMallShipment shipment) {
        if (ObjectUtil.isNull(event)) {
            return "物流事实不存在";
        }
        if (ObjectUtil.isNull(shipment) || !ObjectUtil.equal(shipment.getDataStatus(), 0)) {
            return "事实对应的包裹不存在或已删除";
        }
        if (!ObjectUtil.equal(shipment.getFulfillMode(),
                MallEnum.FulfillMode.THIRD_PARTY.getValue())) {
            return "自营包裹不接受第三方物流事实";
        }
        if (StrUtil.isBlank(event.getWaybillNo())
                || !ObjectUtil.equal(event.getWaybillNo(), shipment.getWaybillNo())) {
            return "事实运单号与包裹不一致";
        }
        if (!ObjectUtil.equal(event.getProviderCode(), shipment.getProviderCode())) {
            return "事实承运商与包裹不一致";
        }
        if (!MallEnum.LogisticsEventState.isKnown(event.getEventState())) {
            return "未知物流事件状态，不做语义猜测：" + event.getEventState();
        }
        if (!DateUtils.isCanonicalBusinessTime(event.getEventTime())) {
            return "物流事实的事件时间不是合法业务时间";
        }
        return null;
    }

    /**
     * 事件状态 → 包裹状态的唯一映射。返回 null 表示不推进（异常与取消由人工处置）。
     */
    public static Integer shipmentStatusOf(String eventState) {
        if (MallEnum.LogisticsEventState.CREATED.equals(eventState)) {
            return MallEnum.ShipmentStatus.ACCEPTED.getValue();
        }
        if (MallEnum.LogisticsEventState.PICKED_UP.equals(eventState)) {
            return MallEnum.ShipmentStatus.PICKED_UP.getValue();
        }
        if (MallEnum.LogisticsEventState.IN_TRANSIT.equals(eventState)) {
            return MallEnum.ShipmentStatus.IN_TRANSIT.getValue();
        }
        if (MallEnum.LogisticsEventState.DELIVERED.equals(eventState)
                || MallEnum.LogisticsEventState.SIGNED.equals(eventState)) {
            // 承运方签收≠订单完成：订单完成恒由用户确认收货落定（自动确认期限未获业务决策）
            return MallEnum.ShipmentStatus.DELIVERED.getValue();
        }
        return null;
    }

    /**
     * 包裹状态 → 平台履约状态的唯一映射。返回 null 表示不推进；
     * 平台的 7 已签收永远只由用户确认收货写入，不由物流事件写入。
     */
    public static Integer fulfillStatusOf(int shipmentStatus) {
        if (shipmentStatus == MallEnum.ShipmentStatus.ACCEPTED.getValue()) {
            return MallEnum.FulfillStatus.PENDING_FETCH.getValue();
        }
        if (shipmentStatus == MallEnum.ShipmentStatus.PICKED_UP.getValue()
                || shipmentStatus == MallEnum.ShipmentStatus.IN_TRANSIT.getValue()) {
            return MallEnum.FulfillStatus.DELIVERING.getValue();
        }
        if (shipmentStatus == MallEnum.ShipmentStatus.DELIVERED.getValue()) {
            return MallEnum.FulfillStatus.ARRIVED.getValue();
        }
        return null;
    }

    /**
     * 仅 FORWARD 方向可驱动正向履约状态（fail-closed，退货/换货物流本轮不实现，见 D-424）：
     * 其余方向的事实照常落库但不推进，否则一件退货签收会把原订单推成「已送达待确认」。
     */
    public static boolean drivesForwardFulfillment(WsMallShipment shipment) {
        return ObjectUtil.isNotNull(shipment)
                && ObjectUtil.equal(shipment.getDirection(),
                        MallEnum.ShipmentDirection.FORWARD.getValue());
    }

    /**
     * 状态是否从 from 前进到 to（同值视为重放）：物流事件会乱序迟到，
     * 只判「不等于就更新」会让迟到的「运输中」把「已送达」推回去。
     */
    public static boolean isForward(int from, int to) {
        return to > from;
    }
}
