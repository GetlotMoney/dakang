package com.jbk.serve.service.delivery;

import cn.hutool.core.util.ObjectUtil;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.data.delivery.po.WsDeliveryTask;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.exception.JbkException;

/**
 * 任务—订单共键栅栏（E2E-03 规则11；语义对齐 miniapp delivery-link.ts）。
 *
 * <p>fail-closed：订单缺失、类型错位、归属错位、任务外键错位一律阻断；
 * 履约动作（入池/接单/推进/签收/异常）还要求订单精确处于 2已支付待履约。
 * 纯函数：拒绝路径零副作用，由调用方保证在任何写入之前调用。</p>
 */
public final class DeliveryLinkGuard {

    public static final String MSG_ORDER_MISSING = "配送任务缺少关联订单，已阻断该操作";
    public static final String MSG_ORDER_LINK_MISMATCH = "配送任务与订单关联关系不一致，已阻断该操作";
    public static final String MSG_ORDER_NOT_FULFILLABLE = "关联订单不在已支付待履约状态，不能接单或履约";

    private DeliveryLinkGuard() {
    }

    /** 关联校验：订单存在 + ORDER_TYPE=3 + 订单/任务 userId 一致 + 任务 ORDER_ID 指向该订单。 */
    public static WsOrder requireLinked(WsOrder order, WsDeliveryTask task) {
        if (ObjectUtil.isNull(order) || ObjectUtil.notEqual(order.getDataStatus(), 0)) {
            throw new JbkException(MSG_ORDER_MISSING);
        }
        boolean linked = ObjectUtil.equal(order.getOrderType(), TradeEnum.OrderType.DELIVERY.getValue())
                && ObjectUtil.equal(order.getUserId(), task.getUserId())
                && ObjectUtil.equal(task.getOrderId(), order.getId());
        if (!linked) {
            throw new JbkException(MSG_ORDER_LINK_MISMATCH);
        }
        return order;
    }

    /** 履约校验：关联之上要求订单精确为 2（1/3/4/5/6/7/8 一律拒绝，Mock 对抗测试口径）。 */
    public static WsOrder requireFulfillable(WsOrder order, WsDeliveryTask task) {
        WsOrder linked = requireLinked(order, task);
        if (ObjectUtil.notEqual(linked.getOrderStatus(), TradeEnum.OrderStatus.PAID.getValue())) {
            throw new JbkException(MSG_ORDER_NOT_FULFILLABLE);
        }
        return linked;
    }
}
