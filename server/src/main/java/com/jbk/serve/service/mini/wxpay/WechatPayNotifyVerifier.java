package com.jbk.serve.service.mini.wxpay;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.util.Base64;

/**
 * 微信支付回调验签（WX-ECO S3）：公网无会话端点的唯一身份认证，验签不过的报文
 * 不解密、不入库、不留业务痕。三道门顺序固定：serial 匹配平台公钥 ID → 时间窗
 * |now-ts|≤5 分钟（防重放刷处理开销）→ SHA256withRSA 验规范串 {@code timestamp\nnonce\nbody\n}。
 * fail-closed：凭据未配置恒返回 false（否则任何人都能给本系统记收款）。
 *
 * @author dakang
 * @since 2026-08-13
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WechatPayNotifyVerifier {

    /** 时间窗（秒）。微信官方建议 5 分钟；收紧不明显提升安全，放宽直接扩大重放面。 */
    static final long MAX_SKEW_SECONDS = 300L;

    /** 日志最多记录的 serial 字符数。 */
    private static final int SERIAL_LOG_MAX = 48;

    private final WechatPayCredentials credentials;

    /**
     * 校验一条回调。任何一道门不过都返回 false，真实原因只落日志——
     * 响应体不区分"签名错/过期/序列号不符"，那是给伪造者的排错提示。
     */
    public boolean verify(String serial, String timestamp, String nonce, String body, String signatureB64) {
        if (!credentials.configured()) {
            log.warn("微信支付凭据未配置，回调一律拒收");
            return false;
        }
        if (StrUtil.hasBlank(serial, timestamp, nonce, signatureB64) || body == null) {
            log.warn("微信支付回调头不完整，拒收");
            return false;
        }
        if (!credentials.platformPublicKeyId().equals(serial)) {
            // serial 是攻击者可控值：只落白名单字符且截长，防日志注入与告警刷量
            log.warn("微信支付回调 serial 与配置的平台公钥 ID 不符，拒收 serial={}",
                    sanitizedForLog(serial));
            return false;
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        }
        catch (NumberFormatException e) {
            log.warn("微信支付回调时间戳非数字，拒收");
            return false;
        }
        long now = System.currentTimeMillis() / 1000L;
        if (Math.abs(now - ts) > MAX_SKEW_SECONDS) {
            log.warn("微信支付回调时间戳超窗，拒收 ts={}", ts);
            return false;
        }
        String message = timestamp + "\n" + nonce + "\n" + body + "\n";
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(credentials.platformPublicKey());
            verifier.update(message.getBytes(StandardCharsets.UTF_8));
            boolean ok = verifier.verify(Base64.getDecoder().decode(signatureB64));
            if (!ok) {
                log.warn("微信支付回调签名验证失败，拒收");
            }
            return ok;
        }
        catch (Exception e) {
            // Base64 非法、公钥文件损坏等一律按验签失败处理：这条路径上没有"部分可信"
            log.warn("微信支付回调验签异常，拒收", e);
            return false;
        }
    }

    /**
     * serial 的日志安全形态：这是公网无认证端点上唯一会进日志的攻击者可控值。
     *
     * <p>先按白名单过滤（挡 CRLF 日志注入与控制字符），再<b>用过滤后的长度</b>截断——
     * 用原串长度当上限时，输入 "!" 会得到空串上限 1，直接 StringIndexOutOfBounds，
     * 异常逃到全局处理器后本该 401 的拒收变成另一种响应。过滤后为空用固定占位。</p>
     */
    static String sanitizedForLog(String serial) {
        if (serial == null) {
            return "<null>";
        }
        String sanitized = serial.replaceAll("[^0-9A-Za-z_-]", "");
        if (sanitized.isEmpty()) {
            return "<empty>";
        }
        return sanitized.substring(0, Math.min(sanitized.length(), SERIAL_LOG_MAX));
    }
}
