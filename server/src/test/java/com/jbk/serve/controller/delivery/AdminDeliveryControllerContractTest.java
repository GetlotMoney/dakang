package com.jbk.serve.controller.delivery;

import com.jbk.tool.annotation.LogOperation;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.annotation.RepeatSubmit;
import com.jbk.tool.utils.satoken.StpKit;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E-03 包C 控制器契约测试：裁决是配送域 PC 侧唯一写操作，
 * 操作审计（@LogOperation）、防重复提交（@RepeatSubmit）与
 * order:appeal:handle 功能点是验收硬条件——注解被删/改型即测试失败，
 * 防止后续重构悄悄摘掉审计落痕。
 */
class AdminDeliveryControllerContractTest {

    private Method method(Class<?> controller, String name) {
        return Arrays.stream(controller.getDeclaredMethods())
                .filter(m -> m.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少端点方法：" + name));
    }

    @Test
    void decideCarriesAuditRepeatGuardAndPermission() {
        Method decide = method(AdminDeliveryAppealController.class, "decide");
        assertNotNull(decide.getAnnotation(LogOperation.class), "裁决必须落操作审计");
        assertNotNull(decide.getAnnotation(RepeatSubmit.class), "裁决必须防重复提交");
        MySaCheckOr guard = decide.getAnnotation(MySaCheckOr.class);
        assertNotNull(guard, "裁决必须声明鉴权");
        assertEquals(1, guard.login().length);
        assertEquals(StpKit.DRIVER_MANAGE, guard.login()[0].type());
        assertEquals(1, guard.permission().length);
        assertEquals("order:appeal:handle", guard.permission()[0].value()[0]);
        assertEquals(StpKit.DRIVER_MANAGE, guard.permission()[0].type());
    }

    @Test
    void readEndpointsRequireManageLoginWithoutWriteDecorators() {
        for (Method method : new Method[]{
                method(AdminDeliveryTaskController.class, "page"),
                method(AdminDeliveryTaskController.class, "detail"),
                method(AdminDeliveryAppealController.class, "page"),
                method(AdminDeliveryAppealController.class, "evidence")}) {
            MySaCheckOr guard = method.getAnnotation(MySaCheckOr.class);
            assertNotNull(guard, method.getName() + " 必须声明后台登录鉴权");
            assertTrue(guard.login().length >= 1);
            assertEquals(StpKit.DRIVER_MANAGE, guard.login()[0].type());
            // 只读端点不挂审计/防重注解：与订单中心读接口口径一致
            assertNull(method.getAnnotation(LogOperation.class));
            assertNull(method.getAnnotation(RepeatSubmit.class));
        }
    }
}
