package com.jbk.serve.service.mini.recharge;

import com.jbk.serve.controller.mini.MiniPaySimController;
import com.jbk.serve.controller.mini.MiniTestLoginController;
import com.jbk.serve.service.mini.impl.MiniPaySimServiceImpl;
import com.jbk.serve.service.mini.impl.MiniTestLoginServiceImpl;
import com.jbk.serve.service.mini.recharge.impl.PaySimSourceAdapter;
import com.jbk.serve.service.mini.recharge.impl.WechatPaySourceAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 隔离测试环境能力门控回归（D-425）。钉死四条：模拟能力必须 havingValue="true" 且不得
 * matchIfMissing（漏配即关闭）；同一能力的控制器与 Service 挂同一开关；不同能力各挂各的
 * 开关可分别授权（Pay-Sim 与测试登录绝不能被一个开关连带打开）；微信来源适配器与
 * Pay-Sim 适配器互斥，否则模拟收款会被记成真实微信收款。
 */
class TestEnvGatingTest {

    private static final String PAY_SIM_SWITCH = "mini.pay-sim.enabled";
    private static final String TEST_LOGIN_SWITCH = "mini.test-login.enabled";

    /** 五个仅测试环境的类：漏配开关时必须一个都不注册，且同一能力的类同开关。 */
    @Test
    void testOnlyBeansRequireExplicitTrueAndNeverDefaultOn() {
        Map<Class<?>, String> expected = new LinkedHashMap<>();
        expected.put(MiniPaySimController.class, PAY_SIM_SWITCH);
        expected.put(MiniPaySimServiceImpl.class, PAY_SIM_SWITCH);
        expected.put(PaySimSourceAdapter.class, PAY_SIM_SWITCH);
        // 测试登录与 Pay-Sim 分属两种能力，各挂各的开关（D-425）
        expected.put(MiniTestLoginController.class, TEST_LOGIN_SWITCH);
        expected.put(MiniTestLoginServiceImpl.class, TEST_LOGIN_SWITCH);

        for (Map.Entry<Class<?>, String> e : expected.entrySet()) {
            Class<?> type = e.getKey();
            ConditionalOnProperty c = type.getAnnotation(ConditionalOnProperty.class);
            assertNotNull(c, type.getSimpleName() + " 必须带 @ConditionalOnProperty，否则生产也会注册");
            assertArrayEquals(new String[] { e.getValue() }, c.name(),
                    type.getSimpleName() + " 必须挂在 " + e.getValue() + " 上");
            assertEquals("true", c.havingValue(),
                    type.getSimpleName() + " 必须显式 true 才注册");
            assertFalse(c.matchIfMissing(),
                    type.getSimpleName() + " 漏配开关时必须关闭，绝不能默认开启");
        }
    }

    /**
     * 测试登录与 Pay-Sim 必须能<b>分别</b>授权。
     *
     * <p>这条是上面那张表的语义断言：表里写错成同一个开关时，上面的循环仍然全绿
     * （它只核"实际注解 == 表里写的"），而"能不能分别关"这个真正的性质会悄悄失效。</p>
     */
    @Test
    void testLoginAndPaySimAreSeparatelyAuthorizable() {
        String testLogin = MiniTestLoginController.class
                .getAnnotation(ConditionalOnProperty.class).name()[0];
        String paySim = MiniPaySimController.class
                .getAnnotation(ConditionalOnProperty.class).name()[0];
        assertNotEquals(paySim, testLogin,
                "测试登录与 Pay-Sim 共用开关：开模拟支付会连带打开按手机号直签会话的入口");
    }

    /**
     * 微信适配器与 Pay-Sim 适配器互斥：一个 {@code havingValue=false + matchIfMissing}，
     * 一个 {@code havingValue=true}。少了任何一半，容器里都可能出现两个来源适配器，
     * 或者开了 Pay-Sim 却仍以 PAY_SOURCE=1 记账——那是对账口径被污染的最坏方向。
     */
    @Test
    void paySourceAdaptersAreMutuallyExclusive() {
        ConditionalOnProperty wechat = WechatPaySourceAdapter.class.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(wechat);
        assertArrayEquals(new String[] { PAY_SIM_SWITCH }, wechat.name());
        assertEquals("false", wechat.havingValue(), "微信适配器只在开关非 true 时注册");
        assertTrue(wechat.matchIfMissing(), "漏配开关时必须回落到真实微信来源");

        ConditionalOnProperty sim = PaySimSourceAdapter.class.getAnnotation(ConditionalOnProperty.class);
        assertEquals("true", sim.havingValue());
        assertFalse(sim.matchIfMissing());
        // 两者的 havingValue 必须相反，否则不构成互斥
        assertFalse(wechat.havingValue().equals(sim.havingValue()),
                "两个来源适配器的 havingValue 必须相反才构成互斥");
    }

    /** 来源常量不得漂移：PAY_SOURCE 已落库且不可变，改了会让历史支付单的来源解释错位。 */
    @Test
    void paySourceConstantsAreFrozen() {
        assertEquals(1, IRechargePaySourceAdapter.WECHAT);
        assertEquals(2, IRechargePaySourceAdapter.PAY_SIM);
        assertEquals(IRechargePaySourceAdapter.PAY_SIM, new PaySimSourceAdapter().currentSource());
        assertEquals(IRechargePaySourceAdapter.WECHAT, new WechatPaySourceAdapter().currentSource());
    }
}
