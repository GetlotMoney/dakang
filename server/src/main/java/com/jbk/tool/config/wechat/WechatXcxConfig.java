package com.jbk.tool.config.wechat;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.jbk.tool.config.system.redis.consts.RedisExpire;
import com.jbk.tool.config.system.redis.consts.RedisKeys;
import com.jbk.tool.config.system.redis.utils.RedisUtils;
import com.jbk.tool.exception.JbkException;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 *@ClassName WechatXcxConfig
 *@Author xs
 *@Date 2025/9/12 10:55
 *@Version 1.0
 */
@Component
@Data
public class WechatXcxConfig {
    @Value("${wechat.xcx.appid}")
    private String appid;
    @Value("${wechat.xcx.appsecret}")
    private String appsecret;

    @Resource(name = "redisTemplate1")
    private RedisTemplate<String, Object> redis1;

    /**
     * 获取 Access_token
     */
    public String getAccessToken() {
        String redisToken = (String) RedisUtils.get(redis1, RedisKeys.Wechat.WECHAT_XCX_ACCESS_TOKEN);
        if (StrUtil.isNotEmpty(redisToken)) {
            return redisToken;
        }
        // 凭据未配时直接拒绝，不发这一次外呼：URL 会把 appid/secret 拼进 query，
        // 用空值去请求只会换回一条无用的错误响应，还多一次带残缺凭据的出网请求。
        if (StrUtil.isBlank(appid) || StrUtil.isBlank(appsecret)) {
            throw new JbkException("小程序凭据未配置");
        }
        String json = HttpUtil.get(getAccessTokenURL(appid, appsecret));
        JSONObject jsonObject = JSON.parseObject(json);
        Integer errcode = jsonObject.getInteger("errcode");
        if (ObjectUtil.isNotNull(errcode) && errcode != 0) {
            throw new JbkException("小程序认证错误");
        }
        String access_token = jsonObject.getString("access_token");
        // 微信限流/降级时会返回既无 errcode 也无 access_token 的响应体：
        // 此时必须抛错，不能把 null 缓存进 Redis 让两小时内每次重试都命中空值
        if (StrUtil.isBlank(access_token)) {
            throw new JbkException("小程序凭据换取失败");
        }
        RedisUtils.set(redis1, RedisKeys.Wechat.WECHAT_XCX_ACCESS_TOKEN, access_token, RedisExpire.HOUR_TWO_EXPIRE);
        return access_token;
    }

    // access_token
    public static String getAccessTokenURL(String appid, String appsecret) {
        return StrUtil.format("https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid={}&secret={}", appid, appsecret);
    }

    // 小程序登录
    public static String getJscode2sessionURL(String appid, String appsecret, String jscode) {
        return StrUtil.format("https://api.weixin.qq.com/sns/jscode2session?appid={}&secret={}&js_code={}&grant_type=authorization_code", appid, appsecret, jscode);
    }
    // phone
    public static String getPhoneURL(String accessToken) {
        return StrUtil.format("https://api.weixin.qq.com/wxa/business/getuserphonenumber?access_token={}", accessToken);
    }
}
