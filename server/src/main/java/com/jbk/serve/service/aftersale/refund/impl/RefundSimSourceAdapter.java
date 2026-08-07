package com.jbk.serve.service.aftersale.refund.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.jbk.serve.service.aftersale.refund.IRefundSourceAdapter;
import com.jbk.serve.service.aftersale.refund.RefundAcceptance;
import com.jbk.tool.exception.JbkException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Refund-Sim 退款来源适配器（仅 {@code mini.refund-sim.enabled=true} 时注册）。
 *
 * <p>与 {@link WechatRefundSourceAdapter} 用同一个属性做<b>互斥</b>门控：
 * 一个 {@code havingValue="true"}、一个 {@code havingValue="false" matchIfMissing=true}，
 * 因此任何时刻容器里有且只有一个退款来源适配器。R0-8 明令「禁止运行时自动降级」——
 * 这条互斥就是它的物理实现：没有任何代码路径能在运行期从微信「掉」到 Sim，
 * 因为另一个 Bean 根本不在容器里。</p>
 *
 * <p><b>开关与 Pay-Sim 刻意分开</b>：退款是出账，比收款危险一个量级。
 * 复用 {@code mini.pay-sim.enabled} 意味着任何为了跑通支付而打开模拟的环境，
 * 会连模拟退款一起打开；两者必须能独立授权。</p>
 *
 * <p><b>本适配器不判定退款成功</b>：{@link #acceptRefund} 恒返回 PROCESSING，
 * 成功只能由一条独立的退款事实经收件箱与 Worker 推进（R0-8）。</p>
 *
 * @author dakang
 * @since 2026-07-29
 */
@Component
@ConditionalOnProperty(name = "mini.refund-sim.enabled", havingValue = "true")
public class RefundSimSourceAdapter implements IRefundSourceAdapter {

    /** 模拟退款单号前缀。带 SIM 字样是刻意的：这个值会被展示，一眼可辨不是真实微信退款号。 */
    private static final String SIM_REFUND_ID_PREFIX = "SIMRF";

    /** 与商户退款单号一致的长度上限（ws_refund.PROVIDER_REFUND_ID 为 varchar(64)）。 */
    private static final int PROVIDER_ID_HASH_LEN = 27;

    @Override
    public int currentSource() {
        return REFUND_SIM;
    }

    /**
     * Sim 恒可用：它不依赖任何外部凭据，这正是它存在的意义。
     * 空实现而非省略该方法——留一个显式的「我确实检查过」，比让读者去接口默认方法里找答案清楚。
     */
    @Override
    public void requireOperable() {
        // no-op：模拟适配器无外部依赖
    }

    /**
     * 受理退款：派生一个确定性的模拟退款号并返回处理中。
     *
     * <p>模拟号由 {@code refundNo} 确定性派生而非随机：同一张退款单重复受理必须拿到同一个号，
     * 否则重试一次就多出一个「服务方退款单号」，事实回来时无从判断哪个才是本单的。</p>
     */
    @Override
    public RefundAcceptance acceptRefund(String refundNo, String orderNo, long amountFen, String currency) {
        if (StrUtil.isBlank(refundNo) || StrUtil.isBlank(orderNo)) {
            throw new JbkException("Refund-Sim 受理入参缺失：退款单号或订单号为空");
        }
        if (amountFen <= 0) {
            throw new JbkException("Refund-Sim 受理金额必须为正，实际 " + amountFen);
        }
        if (!"CNY".equals(currency)) {
            throw new JbkException("Refund-Sim 只支持 CNY，实际 " + currency);
        }
        String digest = SecureUtil.sha256(refundNo).substring(0, PROVIDER_ID_HASH_LEN).toUpperCase();
        return new RefundAcceptance(SIM_REFUND_ID_PREFIX + digest, RefundAcceptance.STATE_PROCESSING);
    }
}
