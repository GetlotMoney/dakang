package com.jbk.serve.controller.aftersale;

import com.jbk.serve.controller.mini.MiniRefundSimController;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.utils.satoken.StpKit;
import org.junit.jupiter.api.Test;
import org.springframework.validation.annotation.Validated;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 售后资金入口的权限合同，防止通用处理权限绕过独立退款审核权限。 */
class AdminAfterSaleControllerContractTest {

    @Test
    void everyExternalRefundEntryRequiresDedicatedRefundPermission() {
        assertRefundPermission(method("requestRefund"));
        assertRefundPermission(method("requestRechargeRefund"));
        assertRefundPermission(method("requestUnsettledRechargeRefund"));
        assertRefundPermission(method(MiniRefundSimController.class, "notifyFact"));
    }

    @Test
    void idAndAfterSaleNumberUseDefaultValidationGroup() {
        assertDefaultValidation(method("requestRefund"));
        assertDefaultValidation(method("generateResend"));
    }

    private Method method(String name) {
        return method(AdminAfterSaleController.class, name);
    }

    private Method method(Class<?> controllerType, String name) {
        return Arrays.stream(controllerType.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private void assertRefundPermission(Method method) {
        MySaCheckOr guard = method.getAnnotation(MySaCheckOr.class);
        assertNotNull(guard, method.getName() + " 缺少权限守卫");
        assertEquals(1, guard.permission().length);
        assertEquals("order:aftersale:refund", guard.permission()[0].value()[0]);
        assertEquals(StpKit.DRIVER_MANAGE, guard.permission()[0].type());
    }

    private void assertDefaultValidation(Method method) {
        Validated validated = Arrays.stream(method.getParameterAnnotations()[0])
                .filter(annotation -> annotation instanceof Validated)
                .map(annotation -> (Validated) annotation)
                .findFirst()
                .orElseThrow(() -> new AssertionError(method.getName() + " 缺少参数校验"));
        assertEquals(0, validated.value().length,
                method.getName() + " 必须启用 Default 组，才能同时校验 id 与 afterSaleNo");
    }
}
