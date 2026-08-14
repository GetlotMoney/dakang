package com.jbk.serve.service.mini.wxpay;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Base64;

/**
 * 微信支付 APIv3 商户侧签名（WX-ECO S3）：出站 Authorization 头与小程序 paySign，
 * 同一把商户私钥、同一个 SHA256withRSA。规范串各字段 + \n 逐行拼接，
 * <b>最后一行也带 \n</b>——少末尾换行微信侧恒验签失败且报错不提示差在哪。
 * 时间戳/随机串由本类生成：允许调用方传时间戳=允许旧时间戳重签，破坏防重放。
 *
 * @author dakang
 * @since 2026-08-13
 */
@Component
@RequiredArgsConstructor
public class WechatPaySigner {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] NONCE_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private final WechatPayCredentials credentials;

    /** 小程序 AppID：paySign 规范串第一行。与登录用的是同一个身份，不另配。 */
    @Value("${wechat.xcx.appid:}")
    private String appid;

    /**
     * 出站请求的 Authorization 头（含 mchid/serial/nonce/timestamp/signature 五段）。
     *
     * @param method       HTTP 方法大写（GET/POST）
     * @param canonicalUrl 去掉域名的路径与查询串（如 {@code /v3/pay/transactions/jsapi}）
     * @param body         请求正文；GET 传空串而不是 null
     */
    public String authorization(String method, String canonicalUrl, String body) {
        long timestamp = System.currentTimeMillis() / 1000L;
        String nonce = nonce();
        String message = method + "\n" + canonicalUrl + "\n" + timestamp + "\n" + nonce + "\n"
                + (body == null ? "" : body) + "\n";
        return "WECHATPAY2-SHA256-RSA2048 mchid=\"" + credentials.mchid()
                + "\",nonce_str=\"" + nonce
                + "\",signature=\"" + sign(message)
                + "\",timestamp=\"" + timestamp
                + "\",serial_no=\"" + credentials.merchantSerial() + "\"";
    }

    /** 小程序 requestPayment 的五参数。prepay_id 由服务端下单换取，绝不来自前端。 */
    public MiniPayParams miniPayParams(String prepayId) {
        if (appid == null || appid.isBlank()) {
            throw new IllegalStateException("小程序 AppID 未配置，无法生成支付签名");
        }
        String timeStamp = String.valueOf(System.currentTimeMillis() / 1000L);
        String nonceStr = nonce();
        String packageValue = "prepay_id=" + prepayId;
        String message = appid + "\n" + timeStamp + "\n" + nonceStr + "\n" + packageValue + "\n";
        return new MiniPayParams(timeStamp, nonceStr, packageValue, "RSA", sign(message));
    }

    /**
     * 对规范串做 SHA256withRSA 并 Base64。
     * 包内可见：给测试用确定性入参验证规范串格式；业务方一律走上面两个入口。
     */
    String sign(String message) {
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(credentials.merchantPrivateKey());
            signer.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        }
        catch (Exception e) {
            throw new IllegalStateException("微信支付签名失败", e);
        }
    }

    private static String nonce() {
        char[] out = new char[32];
        for (int i = 0; i < out.length; i++) {
            out[i] = NONCE_ALPHABET[RANDOM.nextInt(NONCE_ALPHABET.length)];
        }
        return new String(out);
    }

    /**
     * requestPayment 的五参数。字段名与端上 API 一字不差，端上原样透传即可。
     *
     * @param packageValue 即协议里的 {@code package}（Java 关键字，故改名）
     */
    public record MiniPayParams(String timeStamp, String nonceStr, String packageValue,
                                String signType, String paySign) {
    }
}
