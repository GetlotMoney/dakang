package com.jbk.serve.service.aftersale.refund;

import com.jbk.serve.service.aftersale.refund.impl.RefundSimSourceAdapter;
import com.jbk.serve.service.aftersale.refund.impl.WechatRefundSourceAdapter;
import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 退款来源适配器的<b>装配</b>测试（E2E-04 包B，R0-8）。
 *
 * <p>钉两件事，缺一都会让「模拟退款被当成真实微信退款」成为可能：</p>
 * <ol>
 *   <li><b>互斥</b>：任何开关取值下，容器里有且只有一个 {@link IRefundSourceAdapter}。
 *       两个并存会让注入点按扫描顺序随机取一个；一个都没有会让退款接口在启动期就崩，
 *       前者比后者危险得多——它是静默的。</li>
 *   <li><b>不降级</b>：开关关闭时装配的是微信适配器，而它对发起退款一律拒绝。
 *       「凭据没配就退回用模拟」正是 R0-8 明令禁止的那条路径。</li>
 * </ol>
 *
 * <p>用 {@link ApplicationContextRunner} 而不是 {@code @SpringBootTest}：
 * 本类要的是「条件装配在两种属性下各是什么结果」，起整个应用既慢又会把结论
 * 掺进数据源、MQTT、Sa-Token 等无关因素。</p>
 */
class RefundSourceAdapterWiringTest {

    /**
     * 扫描生产类本身而非在测试里复制条件注解：复制版验的是副本，
     * 生产类 havingValue 改坏照样全绿。
     */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(
            basePackageClasses = RefundSimSourceAdapter.class,
            // 只放这两个类进来。不加过滤会把同包的 RefundRequestTxServiceImpl 一起扫进来，
            // 它需要 Mapper 依赖，最小上下文起不来——本类的结论就会被无关的装配失败淹没。
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = { RefundSimSourceAdapter.class, WechatRefundSourceAdapter.class }))
    static class ScanRealAdapters {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ScanRealAdapters.class);

    @Test
    void simSwitchOnRegistersExactlyTheSimAdapter() {
        runner.withPropertyValues("mini.refund-sim.enabled=true").run(ctx -> {
            assertEquals(1, ctx.getBeanNamesForType(IRefundSourceAdapter.class).length,
                    "开关为 true 时必须恰有一个退款来源适配器");
            IRefundSourceAdapter adapter = ctx.getBean(IRefundSourceAdapter.class);
            assertTrue(adapter instanceof RefundSimSourceAdapter, "应装配 Refund-Sim 适配器");
            assertEquals(IRefundSourceAdapter.REFUND_SIM, adapter.currentSource());
            adapter.requireOperable();
        });
    }

    @Test
    void simSwitchOffRegistersExactlyTheWechatAdapter() {
        runner.withPropertyValues("mini.refund-sim.enabled=false").run(ctx -> {
            assertEquals(1, ctx.getBeanNamesForType(IRefundSourceAdapter.class).length,
                    "开关为 false 时必须恰有一个退款来源适配器");
            assertTrue(ctx.getBean(IRefundSourceAdapter.class) instanceof WechatRefundSourceAdapter);
        });
    }

    /** 缺省即生产语义：没写这一项的环境必须拿到 fail-closed 的微信适配器，而不是没有适配器。 */
    @Test
    void missingSwitchDefaultsToWechatAdapter() {
        runner.run(ctx -> {
            assertEquals(1, ctx.getBeanNamesForType(IRefundSourceAdapter.class).length,
                    "开关缺省时必须恰有一个退款来源适配器");
            IRefundSourceAdapter adapter = ctx.getBean(IRefundSourceAdapter.class);
            assertTrue(adapter instanceof WechatRefundSourceAdapter, "缺省必须落到微信适配器");
            assertEquals(IRefundSourceAdapter.WECHAT, adapter.currentSource());
        });
    }

    /**
     * 关键的一条：开关关闭时，退款能力必须<b>拒绝</b>而不是静默可用。
     * 这里断言错因文本，是因为「任何异常都算过」会让本条在实现被改成
     * 抛 NPE 或别的偶发异常时依然绿——而那时 fail-closed 的语义其实已经没了。
     */
    @Test
    void wechatAdapterRefusesToOperateAndToAccept() {
        WechatRefundSourceAdapter adapter = new WechatRefundSourceAdapter();
        JbkException operable = assertThrows(JbkException.class, adapter::requireOperable);
        assertTrue(operable.getMessage().contains("真实微信退款尚未接入"), "实际=" + operable.getMessage());
        assertTrue(operable.getMessage().contains("不存在自动降级"),
                "错因必须点明「不会退回 Refund-Sim」，否则运维会以为开个模拟就能继续");

        JbkException accept = assertThrows(JbkException.class,
                () -> adapter.acceptRefund("RF1", "WO1", 100L, "CNY"));
        assertTrue(accept.getMessage().contains("拒绝受理退款请求"), "实际=" + accept.getMessage());
    }

    /** 模拟号必须由退款单号确定性派生：重试一次多出一个服务方退款号，事实回来就对不上账。 */
    @Test
    void simProviderRefundIdIsDeterministicAndLabelled() {
        RefundSimSourceAdapter adapter = new RefundSimSourceAdapter();
        RefundAcceptance first = adapter.acceptRefund("RF20260729001", "WO20260729001", 1300L, "CNY");
        RefundAcceptance again = adapter.acceptRefund("RF20260729001", "WO20260729001", 1300L, "CNY");
        assertEquals(first.providerRefundId(), again.providerRefundId(), "同一退款单重复受理必须得同一个服务方号");
        assertTrue(first.providerRefundId().startsWith("SIMRF"),
                "模拟退款号必须自带 SIM 标识，展示时一眼可辨不是真实微信退款号");
        assertTrue(first.accepted());
        assertEquals(RefundAcceptance.STATE_PROCESSING, first.state(), "受理阶段不得给出成功");

        RefundAcceptance other = adapter.acceptRefund("RF20260729002", "WO20260729001", 1300L, "CNY");
        assertTrue(!first.providerRefundId().equals(other.providerRefundId()),
                "不同退款单必须得到不同的服务方号");
    }

    /** 受理入参闸：金额非正、币种非 CNY、单号为空一律拒绝，且各自可辨。 */
    @Test
    void simAdapterRejectsIllegalAcceptanceInput() {
        RefundSimSourceAdapter adapter = new RefundSimSourceAdapter();
        assertTrue(assertThrows(JbkException.class,
                () -> adapter.acceptRefund(" ", "WO1", 100L, "CNY")).getMessage().contains("入参缺失"));
        assertTrue(assertThrows(JbkException.class,
                () -> adapter.acceptRefund("RF1", "WO1", 0L, "CNY")).getMessage().contains("金额必须为正"));
        assertTrue(assertThrows(JbkException.class,
                () -> adapter.acceptRefund("RF1", "WO1", 100L, "USD")).getMessage().contains("只支持 CNY"));
    }

    /**
     * 受理凭据的类型边界：适配器实现若越权直接给出 SUCCESS，必须在构造期就被拒绝。
     * 这是 R0-8「不直接改退款成功」在类型层面的最后一道闸。
     */
    @Test
    void acceptanceRejectsSuccessStateAtConstruction() {
        JbkException ex = assertThrows(JbkException.class,
                () -> new RefundAcceptance("SIMRF001", "SUCCESS"));
        assertTrue(ex.getMessage().contains("受理阶段只允许 PROCESSING/CLOSED"), "实际=" + ex.getMessage());
        assertThrows(JbkException.class, () -> new RefundAcceptance("", RefundAcceptance.STATE_PROCESSING));
    }
}
