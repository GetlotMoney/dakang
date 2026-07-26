package com.jbk.serve.service.mini.auth;

/**
 * code2session 解析结果（服务端内部使用，绝不序列化返回前端）。
 * <p>openid / sessionKey 属微信身份密钥，只在后端流转，任何响应体不得包含。</p>
 *
 * @author dakang
 * @since 2026-07-21
 */
public record WechatCode2SessionResult(String openid, String sessionKey) {
}
