package com.jbk.serve.controller.mall;

import com.jbk.serve.controller.mini.MiniMallPaySimController;
import com.jbk.serve.controller.mini.MiniMallTradeController;
import com.jbk.serve.service.mall.impl.MallPaySimQueryAdapter;
import com.jbk.serve.service.mall.impl.MallPaySimServiceImpl;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.annotation.RepeatSubmit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 商城交易入口合同（E2E-09 S2 R1-P1-2）。
 *
 * <p>创单与模拟支付都自带数据库级持久幂等键——创单是 uk(USER_ID, REQUEST_ID)，
 * 支付事实是 uk(PAY_SOURCE, FACT_CHANNEL, PROVIDER_EVENT_KEY)。这类接口叠加短时
 * {@link RepeatSubmit} 会让合法的网络重试在进入领域幂等核验前就被切面拒绝：
 * 用户拿到的是"请勿重复提交"，而不是他那张已经创建好的订单或已经成功的支付。
 * 与 MallStockControllerContractTest 同一条规矩，这里把它扩到交易两个入口。</p>
 *
 * <p>同时钉住鉴权没有被顺手摘掉——移除防重切面时误删相邻注解是常见事故。</p>
 */
@DisplayName("商城交易入口合同")
class MallTradeControllerContractTest {

    @Test
    @DisplayName("创单不得叠加短时防重切面，且鉴权仍在")
    void orderCreateMustNotCarryShortWindowRepeatGuard() throws Exception {
        Method create = MiniMallTradeController.class.getMethod(
                "orderCreate", com.jbk.tool.data.mall.bo.MallCheckoutBo.class);
        assertNull(create.getAnnotation(RepeatSubmit.class),
                "创单幂等由 uk(USER_ID,REQUEST_ID) 提供，禁止叠加 @RepeatSubmit");
        assertNotNull(create.getAnnotation(MySaCheckOr.class), "创单鉴权注解不得缺失");
    }

    @Test
    @DisplayName("模拟支付不得叠加短时防重切面，且鉴权仍在")
    void paySimMustNotCarryShortWindowRepeatGuard() throws Exception {
        Method pay = MiniMallPaySimController.class.getMethod(
                "pay", com.jbk.tool.data.mall.bo.MallOrderActionBo.class);
        assertNull(pay.getAnnotation(RepeatSubmit.class),
                "支付幂等由事实唯一键提供，禁止叠加 @RepeatSubmit");
        assertNotNull(pay.getAnnotation(MySaCheckOr.class), "模拟支付鉴权注解不得缺失");
    }

    /**
     * 反面：取消订单没有请求级持久幂等键（幂等靠状态 CAS，重复取消本就会被拒），
     * 短时防重在那里是合理的。本条断言它仍在，防止有人把上面两处的结论过度推广。
     */
    @Test
    @DisplayName("取消订单保留短时防重：它没有请求级持久幂等键")
    void orderCancelKeepsRepeatGuard() throws Exception {
        Method cancel = MiniMallTradeController.class.getMethod(
                "orderCancel", com.jbk.tool.data.mall.bo.MallOrderActionBo.class);
        assertNotNull(cancel.getAnnotation(RepeatSubmit.class),
                "取消没有请求级幂等键，短时防重应保留");
    }

    /**
     * 生产配置支持环境变量不代表容器会自动收到它；Compose 必须显式透传。
     * 缺少这条接线时，代码和测试都能通过，但获授权的商城 Pay-Sim 验收端点永远不会注册。
     */
    @Test
    @DisplayName("商城 Pay-Sim 必须显式开启、由 Compose 透传且公开默认关闭")
    void paySimGateIsClosedByDefaultAndReachableWhenAuthorized() throws Exception {
        String property = "mall.pay-sim.enabled";
        for (Class<?> type : new Class<?>[] {
                MiniMallPaySimController.class, MallPaySimServiceImpl.class, MallPaySimQueryAdapter.class }) {
            ConditionalOnProperty gate = type.getAnnotation(ConditionalOnProperty.class);
            assertNotNull(gate, type.getSimpleName() + " 必须受商城 Pay-Sim 开关保护");
            assertArrayEquals(new String[] { property }, gate.name());
            assertEquals("true", gate.havingValue());
            assertFalse(gate.matchIfMissing(), "缺少配置时不得开启模拟收款");
        }

        Path root = Path.of("..").toAbsolutePath().normalize();
        String compose = Files.readString(root.resolve("deploy/docker-compose.yml"));
        String envExample = Files.readString(root.resolve(".env.example"));
        assertTrue(compose.contains("MALL_PAYSIM_ENABLED: ${MALL_PAYSIM_ENABLED:-false}"),
                "Compose 必须把获授权的商城 Pay-Sim 开关传入 prod 容器");
        assertTrue(envExample.contains("MALL_PAYSIM_ENABLED=false"),
                "公开环境模板必须保持商城 Pay-Sim 默认关闭");
    }
}
