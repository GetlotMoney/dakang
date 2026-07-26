package com.jbk.serve.service.mini.auth.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.jbk.serve.service.mini.auth.IWechatPhoneAdapter;
import com.jbk.tool.config.wechat.WechatXcxConfig;
import com.jbk.tool.exception.JbkException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 真实手机号解析适配器（微信 wxa/business/getuserphonenumber）。
 * <p>服务端用 phoneCode 换手机号，绝不接收前端直传手机号；凭据未配置时 fail-closed 抛错。</p>
 *
 * @author dakang
 * @since 2026-07-21
 */
@Component
@RequiredArgsConstructor
public class WechatPhoneAdapterImpl implements IWechatPhoneAdapter {

    private final WechatXcxConfig wechatXcxConfig;

    @Override
    public String resolvePhone(String phoneCode) {
        if (StrUtil.isBlank(phoneCode)) {
            throw new JbkException("手机号授权 code 不能为空");
        }
        if (StrUtil.isBlank(wechatXcxConfig.getAppid()) || StrUtil.isBlank(wechatXcxConfig.getAppsecret())) {
            throw new JbkException("微信小程序凭据未配置，无法解析手机号");
        }
        String url = WechatXcxConfig.getPhoneURL(wechatXcxConfig.getAccessToken());
        String body = "{\"code\":\"" + phoneCode + "\"}";
        JSONObject json = JSON.parseObject(HttpUtil.post(url, body));
        Integer errcode = json.getInteger("errcode");
        if (ObjectUtil.isNotNull(errcode) && errcode != 0) {
            throw new JbkException("手机号解析失败");
        }
        JSONObject phoneInfo = json.getJSONObject("phone_info");
        String phone = phoneInfo == null ? null : phoneInfo.getString("phoneNumber");
        if (StrUtil.isBlank(phone) || !phone.matches("^1\\d{10}$")) {
            throw new JbkException("未获取到有效手机号");
        }
        return phone;
    }
}
