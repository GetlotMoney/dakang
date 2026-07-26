package com.jbk.serve.service.mini.auth;

/**
 * KH_USER 会话令牌（tokenName 即 sa-token token-name，前端按此原样回传）。
 *
 * @author dakang
 * @since 2026-07-21
 */
public record KhUserSession(String tokenName, String tokenValue) {
}
