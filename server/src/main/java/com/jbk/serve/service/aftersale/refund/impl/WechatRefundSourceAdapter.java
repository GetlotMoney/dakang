package com.jbk.serve.service.aftersale.refund.impl;

import com.jbk.serve.service.aftersale.refund.IRefundSourceAdapter;
import com.jbk.serve.service.aftersale.refund.RefundAcceptance;
import com.jbk.tool.exception.JbkException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 默认退款来源适配器：微信，当前是 fail-closed 空实现（E2E-04 包B，R0-8）。
 * 真实微信退款未接入：沉默受理会写出账面已退款、钱没发出、零报错的退款单，故一律拒绝；
 * 「禁止回退 Refund-Sim」由与 {@link RefundSimSourceAdapter} 的互斥门控在容器层保证。
 * 接入时：{@link #requireOperable()} 改为校验凭据齐备，{@link #acceptRefund} 调 v3 接口，
 * 返回态仍只能是 PROCESSING/CLOSED——受理成功不等于退款成功（R0-8）。
 *
 * @author dakang
 * @since 2026-07-29
 */
@Component
@ConditionalOnProperty(name = "mini.refund-sim.enabled", havingValue = "false", matchIfMissing = true)
public class WechatRefundSourceAdapter implements IRefundSourceAdapter {

    /**
     * 商户号占位，真值只经 .env 注入（WX-ECO S3 起 {@code wechat.pay.mchid} 进 yml，未填即空串）。
     * 凭据齐备也不自动放行——退款出站要等本类真正实现。
     */
    @Value("${wechat.pay.mchid:}")
    private String merchantId;

    @Override
    public int currentSource() {
        return WECHAT;
    }

    /**
     * 恒拒绝：真实微信退款未接入。刻意不写成「凭据齐全就放行」——v3 调用代码不存在时，
     * 放行只会让上层写出退款单再在调用处炸掉。
     */
    @Override
    public void requireOperable() {
        throw new JbkException("真实微信退款尚未接入"
                + (merchantId == null || merchantId.isBlank() ? "（且商户号未配置）" : "")
                + "，拒绝发起退款；隔离环境请显式开启 mini.refund-sim.enabled，不存在自动降级");
    }

    @Override
    public RefundAcceptance acceptRefund(String refundNo, String orderNo, long amountFen, String currency) {
        // 防绕过前置闸直接调受理，同样 fail-closed
        throw new JbkException("真实微信退款尚未接入，拒绝受理退款请求");
    }
}
