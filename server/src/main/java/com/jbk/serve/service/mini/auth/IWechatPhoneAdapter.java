package com.jbk.serve.service.mini.auth;

/**
 * 微信手机号解析适配器：用 getPhoneNumber 返回的 phoneCode 换取手机号（服务端解析，不信前端传入手机号）。
 * <p>真实实现走 wxa/business/getuserphonenumber；测试注入假实现返回固定手机号，避免外部联调。</p>
 *
 * @author dakang
 * @since 2026-07-21
 */
public interface IWechatPhoneAdapter {

    /**
     * @param phoneCode getPhoneNumber 回调的一次性 code
     * @return 11 位手机号；解析失败或空号抛业务异常（fail-closed）
     */
    String resolvePhone(String phoneCode);
}
