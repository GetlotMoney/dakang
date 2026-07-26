package com.jbk.tool.config.system.mybatis;

import com.jbk.tool.data.trade.po.WsOrder;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MpMetaObjectHandlerTest {

    private static final String BUSINESS_TIME = "20260723222255";

    @Test
    void preservesExplicitBusinessClockOnInsert() {
        WsOrder order = new WsOrder();
        order.setCreateTime(BUSINESS_TIME);
        order.setUpdateTime(BUSINESS_TIME);

        new MpMetaObjectHandler().insertFill(SystemMetaObject.forObject(order));

        assertEquals(BUSINESS_TIME, order.getCreateTime());
        assertEquals(BUSINESS_TIME, order.getUpdateTime());
    }

    @Test
    void fillsMissingAuditTimesOnInsert() {
        WsOrder order = new WsOrder();

        new MpMetaObjectHandler().insertFill(SystemMetaObject.forObject(order));

        assertNotNull(order.getCreateTime());
        assertNotNull(order.getUpdateTime());
        assertTrue(order.getCreateTime().matches("\\d{14}"));
        assertTrue(order.getUpdateTime().matches("\\d{14}"));
        assertEquals(order.getCreateTime(), order.getUpdateTime());
    }
}
