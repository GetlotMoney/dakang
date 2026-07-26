package com.jbk.serve.service.mini.auth;

/**
 * KH_USER 会话签发器：把 sa-token 静态登录调用隔离到实现类，
 * 使 {@code MiniAuthService} 可脱离 Web 上下文单测，且保证只签发 KH_USER 会话（禁止后台账号会话）。
 *
 * @author dakang
 * @since 2026-07-21
 */
public interface IKhUserSessionIssuer {

    /** 为用户建立 KH_USER 会话，返回 tokenName/tokenValue。 */
    KhUserSession issue(Long userId, String userName);
}
