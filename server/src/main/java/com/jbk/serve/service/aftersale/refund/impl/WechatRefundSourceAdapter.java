package com.jbk.serve.service.aftersale.refund.impl;

import com.jbk.serve.service.aftersale.refund.IRefundSourceAdapter;
import com.jbk.serve.service.aftersale.refund.RefundAcceptance;
import com.jbk.tool.exception.JbkException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 默认退款来源适配器：微信。<b>当前是 fail-closed 空实现</b>（E2E-04 包B，R0-8）。
 *
 * <h3>为什么默认实现是「拒绝」而不是「什么都不做」</h3>
 * <p>支付侧的 {@code WechatPaySourceAdapter} 可以只返回一个来源常量，因为收款由用户在微信侧完成，
 * 我方不需要任何主动能力。退款相反——它是我方主动发起的出账动作。真实微信退款接口在本期未接入，
 * 商户号与 API v3 证书也尚未配置（{@code application-*.yml} 只有 {@code wechat.xcx} 小程序凭据，
 * 没有任何 {@code wechat.pay.*}）。此时若本类沉默地返回一个「成功受理」，
 * 上层就会写出一张 REFUND_SOURCE=1微信 的退款单，而实际上一分钱都没有发给微信——
 * 账面显示已退款、用户没收到钱，且没有任何报错指向真相。</p>
 *
 * <p>R0-8 因此规定：<b>真实微信凭据未配置时 fail-closed，禁止回退 Refund-Sim</b>。
 * 「禁止回退」由与 {@link RefundSimSourceAdapter} 的互斥门控在容器层保证
 * （两者不可能同时存在，也就没有任何运行期降级路径）；「fail-closed」由本类抛出保证。</p>
 *
 * <h3>接入真实微信退款时要改什么</h3>
 * <p>把 {@link #requireOperable()} 改为校验商户号/证书/密钥三项齐备，
 * {@link #acceptRefund} 改为调用 v3 退款接口并把 refund_id 装进 {@link RefundAcceptance}。
 * <b>返回态仍只能是 PROCESSING/CLOSED</b>：微信退款受理成功也不等于退款成功，
 * 成功必须由退款通知或主动查单产生的事实经收件箱推进（与支付侧同一条纪律）。</p>
 *
 * @author dakang
 * @since 2026-07-29
 */
@Component
@ConditionalOnProperty(name = "mini.refund-sim.enabled", havingValue = "false", matchIfMissing = true)
public class WechatRefundSourceAdapter implements IRefundSourceAdapter {

    /**
     * 商户号占位。真值只能经 .env 注入，本类不持有任何明文凭据。
     * 目前配置里根本没有 {@code wechat.pay.mchid} 这一项，故恒为空串——
     * 这不是缺陷，而是「未接入」的准确表示，{@link #requireOperable()} 据此拒绝。
     */
    @Value("${wechat.pay.mchid:}")
    private String merchantId;

    @Override
    public int currentSource() {
        return WECHAT;
    }

    /**
     * 恒拒绝：真实微信退款未接入。
     *
     * <p>刻意<b>不</b>写成「凭据齐全就放行、缺失才拒绝」——那会让「配了个商户号」
     * 被误当成「退款能力已就绪」，而实际的 v3 调用代码根本不存在，
     * 上层会写出退款单再在调用处炸掉，回到本类注释开头描述的那种最坏形状。
     * 等真正实现 {@link #acceptRefund} 时，再把这里换成凭据校验。</p>
     */
    @Override
    public void requireOperable() {
        throw new JbkException("真实微信退款尚未接入"
                + (merchantId == null || merchantId.isBlank() ? "（且商户号未配置）" : "")
                + "，拒绝发起退款；隔离环境请显式开启 mini.refund-sim.enabled，不存在自动降级");
    }

    @Override
    public RefundAcceptance acceptRefund(String refundNo, String orderNo, long amountFen, String currency) {
        // 正常流程下调用方必须先过 requireOperable()，走不到这里；
        // 这一层是防「有人绕过前置闸直接调受理」，同样 fail-closed。
        throw new JbkException("真实微信退款尚未接入，拒绝受理退款请求");
    }
}
