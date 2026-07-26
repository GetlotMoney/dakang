package com.jbk.serve.service.mini.auth;

/**
 * 微信 code2session 适配器：用一次性 code 换取 openid/session_key。
 * <p>真实实现走 sns/jscode2session；测试注入假实现返回固定 openid，避免外部联调（L2-AUTH 禁止真实微信外呼）。</p>
 *
 * @author dakang
 * @since 2026-07-21
 */
public interface IWechatCode2SessionAdapter {

    /**
     * @param code uni.login 返回的一次性 code
     * @return openid + session_key；解析失败抛业务异常（fail-closed，绝不返回空 openid 建会话）
     */
    WechatCode2SessionResult resolve(String code);
}
