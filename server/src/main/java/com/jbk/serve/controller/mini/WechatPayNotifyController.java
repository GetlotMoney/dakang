package com.jbk.serve.controller.mini;

import com.jbk.serve.service.mini.wxpay.WechatPayNotifyInbox;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 微信支付回调端点（WX-ECO S3）。刻意不带任何 @SaCheck：回调来自微信服务器，身份认证是
 * 平台签名验证（{@link WechatPayNotifyInbox} 第一道门），加会话检查会把所有回调挡在门外。
 * 控制器只做搬运：原文与四个验签头原样交收件箱，应答按微信 {@code {"code","message"}} 结构。
 * 【缺口】退款通知（REFUND.*）本轮未接入：收件箱留痕并回 500（微信持续重试），绝不静默 ACK；
 * 接入需新增退款报文解析接进 ws_refund_event，与 {@code WechatRefundSourceAdapter} 出站改造同轮。
 * notify_url 随下单请求逐笔携带；本路径挂 /mini 前缀复用既有代理与备案域名。
 *
 * @author dakang
 * @since 2026-08-13
 */
@Tag(name = "微信支付回调")
@RestController
@RequiredArgsConstructor
@RequestMapping("/mini/wxpay")
public class WechatPayNotifyController {

    /** 回调体上限。真实通知几 KB，64KB 留了充分余量。 */
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final WechatPayNotifyInbox inbox;

    @PostMapping(value = "/notify/payment", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "支付结果通知（验签为唯一门禁；应答码决定微信是否重试）")
    public ResponseEntity<String> payment(
            @RequestHeader(value = "Wechatpay-Serial", required = false) String serial,
            @RequestHeader(value = "Wechatpay-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "Wechatpay-Nonce", required = false) String nonce,
            @RequestHeader(value = "Wechatpay-Signature", required = false) String signature,
            HttpServletRequest request) throws java.io.IOException {
        // 上限 64KB：真实微信通知只有几 KB，而 nginx 全局 client_max_body_size 是 100m——
        // 公网无认证端点若无界缓冲，攻击者用并发大包在走到验签之前就能把堆打爆。
        // 读 MAX+1 字节而不是信 Content-Length：后者可以撒谎。
        byte[] head = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (head.length > MAX_BODY_BYTES) {
            return ResponseEntity.status(413).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"code\":\"FAIL\",\"message\":\"body too large\"}");
        }
        // 原始字节按 UTF-8 自行解码，不走 @RequestBody String：后者按 Content-Type 的
        // charset 解码，微信不带 charset 时可能落到 ISO-8859-1，中文重编码后字节变了，
        // 表现为"验签恒失败且无从排查"。
        String rawBody = new String(head, java.nio.charset.StandardCharsets.UTF_8);
        WechatPayNotifyInbox.Outcome outcome =
                inbox.onPaymentNotify(serial, timestamp, nonce, signature, rawBody);
        // 微信只认 HTTP 状态码 + {"code","message"}；SUCCESS 停止重试，FAIL 触发退避重试
        return ResponseEntity.status(outcome.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"" + outcome.code() + "\",\"message\":\""
                        + outcome.message().replace("\"", "'") + "\"}");
    }
}
