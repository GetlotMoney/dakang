package com.jbk.serve.service.delivery;

import com.jbk.tool.data.delivery.po.WsDeliveryTask;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 共键栅栏与范围解析单测（申诉/举证/履约权限分支的纯函数部分）：
 * 订单缺失/类型错位/归属错位/外键错位/非待履约态 逐支拒绝；范围解析 fail-closed。
 */
class DeliveryGuardsTest {

    private WsOrder order(long id, int type, long userId, int status) {
        WsOrder order = new WsOrder()
                .setOrderType(type)
                .setUserId(userId)
                .setOrderStatus(status);
        order.setId(id);
        order.setDataStatus(0);
        return order;
    }

    private WsDeliveryTask task(long orderId, long userId) {
        return new WsDeliveryTask().setOrderId(orderId).setUserId(userId);
    }

    @Test
    void linkedOrderPassesAndReturnsSameInstance() {
        WsOrder order = order(3L, 3, 1L, 2);
        assertSame(order, DeliveryLinkGuard.requireLinked(order, task(3L, 1L)));
        assertSame(order, DeliveryLinkGuard.requireFulfillable(order, task(3L, 1L)));
    }

    @Test
    void missingOrDeletedOrderIsBlocked() {
        JbkException missing = assertThrows(JbkException.class,
                () -> DeliveryLinkGuard.requireLinked(null, task(3L, 1L)));
        assertEquals(DeliveryLinkGuard.MSG_ORDER_MISSING, missing.getMsg());

        WsOrder deleted = order(3L, 3, 1L, 2);
        deleted.setDataStatus(1);
        assertThrows(JbkException.class, () -> DeliveryLinkGuard.requireLinked(deleted, task(3L, 1L)));
    }

    @Test
    void anyLinkKeyMismatchIsBlocked() {
        // 订单类型错位（取水单被挂到配送任务）
        assertThrows(JbkException.class,
                () -> DeliveryLinkGuard.requireLinked(order(3L, 1, 1L, 2), task(3L, 1L)));
        // 用户归属错位
        assertThrows(JbkException.class,
                () -> DeliveryLinkGuard.requireLinked(order(3L, 3, 9L, 2), task(3L, 1L)));
        // 任务外键指向别的订单
        assertThrows(JbkException.class,
                () -> DeliveryLinkGuard.requireLinked(order(3L, 3, 1L, 2), task(4L, 1L)));
    }

    @Test
    void onlyPaidStatusIsFulfillable() {
        for (int status : new int[]{1, 3, 4, 5, 6, 7, 8}) {
            JbkException ex = assertThrows(JbkException.class,
                    () -> DeliveryLinkGuard.requireFulfillable(order(3L, 3, 1L, status), task(3L, 1L)),
                    "订单状态 " + status + " 不可履约");
            assertEquals(DeliveryLinkGuard.MSG_ORDER_NOT_FULFILLABLE, ex.getMsg());
        }
    }

    @Test
    void courierScopeParsesCsvAndFailsClosed() {
        assertEquals(Set.of(1L, 2L), CourierScope.parseStationIds("1,2"));
        assertEquals(Set.of(41L), CourierScope.parseStationIds(" 41 , "));
        assertTrue(CourierScope.parseStationIds(null).isEmpty(), "空=未配置=默认拒绝");
        assertTrue(CourierScope.parseStationIds("  ").isEmpty());
        assertTrue(CourierScope.parseStationIds("1,abc").isEmpty(), "任一片段非法整体按未配置处理");
        assertTrue(CourierScope.parseStationIds("0,-2").isEmpty(), "非正ID不入范围");
    }
}
