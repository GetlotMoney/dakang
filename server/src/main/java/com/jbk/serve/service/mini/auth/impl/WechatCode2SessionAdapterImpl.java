package com.jbk.serve.service.mini.auth.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.jbk.serve.service.mini.auth.IWechatCode2SessionAdapter;
import com.jbk.serve.service.mini.auth.WechatCode2SessionResult;
import com.jbk.tool.config.wechat.WechatXcxConfig;
import com.jbk.tool.exception.JbkException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 真实 code2session 适配器（微信 sns/jscode2session）。
 * <p>fail-closed：appid/secret 未配置时直接抛错，绝不发起外呼、绝不返回空 openid；上层据此拒绝登录。</p>
 * <p>openid/session_key 仅在此适配器返回给后端服务内部流转，任何 Controller 响应不得携带。</p>
 *
 * @author dakang
 * @since 2026-07-21
 */
@Component
@RequiredArgsConstructor
public class WechatCode2SessionAdapterImpl implements IWechatCode2SessionAdapter {

    private final WechatXcxConfig wechatXcxConfig;

    @Override
    public WechatCode2SessionResult resolve(String code) {
        if (StrUtil.isBlank(code)) {
            throw new JbkException("登录 code 不能为空");
        }
        String appid = wechatXcxConfig.getAppid();
        String secret = wechatXcxConfig.getAppsecret();
        if (StrUtil.isBlank(appid) || StrUtil.isBlank(secret)) {
            // 真实微信凭据未配置：不外呼、不放行，返回明确阻塞（等待真实微信环境授权）。
            throw new JbkException("微信小程序凭据未配置，无法完成登录");
        }
        String url = WechatXcxConfig.getJscode2sessionURL(appid, secret, code);
        JSONObject json = JSON.parseObject(HttpUtil.get(url));
        Integer errcode = json.getInteger("errcode");
        if (ObjectUtil.isNotNull(errcode) && errcode != 0) {
            throw new JbkException("微信登录校验失败");
        }
        String openid = json.getString("openid");
        if (StrUtil.isBlank(openid)) {
            throw new JbkException("微信登录未返回身份标识");
        }
        return new WechatCode2SessionResult(openid, json.getString("session_key"));
    }
}
