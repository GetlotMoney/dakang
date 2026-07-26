package com.jbk.serve.service.mini.recharge;

import com.jbk.serve.controller.mini.MiniPaySimController;
import com.jbk.serve.controller.mini.MiniTestLoginController;
import com.jbk.serve.service.mini.impl.MiniPaySimServiceImpl;
import com.jbk.serve.service.mini.impl.MiniTestLoginServiceImpl;
import com.jbk.serve.service.mini.recharge.impl.PaySimSourceAdapter;
import com.jbk.serve.service.mini.recharge.impl.WechatPaySourceAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 隔离测试环境能力的<b>门控</b>本身的回归测试。
 *
 * <p>模拟支付与测试登录都是"生产绝不能有"的能力。它们能不能出现在生产，
 * 完全取决于几个注解属性——而注解属性被改错时编译器不会吭声，
 * 上线前也很难有人肉眼发现少了个 {@code havingValue}。这里把它们钉死：</p>
 *
 * <ul>
 *   <li>四个类都必须挂在<b>同一个</b>开关上，不允许各挂各的；</li>
 *   <li>模拟能力必须 {@code havingValue="true"} 且<b>不得</b> {@code matchIfMissing}——
 *       漏配即关闭；</li>
 *   <li>微信来源适配器必须与 Pay-Sim 适配器<b>互斥</b>，否则容器里可能同时存在两个来源，
 *       模拟收款会被记成真实微信收款。</li>
 * </ul>
 */
class TestEnvGatingTest {

    private static final String SWITCH = "mini.pay-sim.enabled";

    /** 四个仅测试环境的类：漏配开关时必须一个都不注册。 */
    @Test
    void testOnlyBeansRequireExplicitTrueAndNeverDefaultOn() {
        for (Class<?> type : new Class<?>[] {
                MiniPaySimController.class, MiniPaySimServiceImpl.class,
                MiniTestLoginController.class, MiniTestLoginServiceImpl.class,
                PaySimSourceAdapter.class }) {
            ConditionalOnProperty c = type.getAnnotation(ConditionalOnProperty.class);
            assertNotNull(c, type.getSimpleName() + " 必须带 @ConditionalOnProperty，否则生产也会注册");
            assertArrayEquals(new String[] { SWITCH }, c.name(),
                    type.getSimpleName() + " 必须挂在统一开关 " + SWITCH + " 上");
            assertEquals("true", c.havingValue(),
                    type.getSimpleName() + " 必须显式 true 才注册");
            assertFalse(c.matchIfMissing(),
                    type.getSimpleName() + " 漏配开关时必须关闭，绝不能默认开启");
        }
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
        assertArrayEquals(new String[] { SWITCH }, wechat.name());
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
