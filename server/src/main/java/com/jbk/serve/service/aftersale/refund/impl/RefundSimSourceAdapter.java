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
 * 与 {@link WechatRefundSourceAdapter} 同属性反相互斥门控：容器里恒只有一个退款来源适配器，
 * 这是 R0-8「禁止运行时自动降级」的物理实现。开关与 Pay-Sim 刻意分开：退款是出账，须独立授权。
 * 不判定退款成功：{@link #acceptRefund} 恒返回 PROCESSING（R0-8）。
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

    /** Sim 恒可用：不依赖任何外部凭据。 */
    @Override
    public void requireOperable() {
        // no-op：模拟适配器无外部依赖
    }

    /**
     * 受理退款：模拟号由 {@code refundNo} 确定性派生而非随机——重复受理必须拿到同一个号，
     * 否则重试即多出一个服务方退款单号，事实回来无从判断哪个是本单的。
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
