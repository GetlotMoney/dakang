package com.jbk.serve.service.mini.auth.impl;

import cn.dev33.satoken.stp.SaLoginModel;
import com.jbk.serve.service.mini.auth.IKhUserSessionIssuer;
import com.jbk.serve.service.mini.auth.KhUserSession;
import com.jbk.tool.utils.satoken.StpKit;
import org.springframework.stereotype.Component;

/**
 * 仅签发 {@link StpKit#KH_USER} 会话（C 端小程序用户），绝不建立后台 MANAGE 会话。
 *
 * @author dakang
 * @since 2026-07-21
 */
@Component
public class KhUserSessionIssuerImpl implements IKhUserSessionIssuer {

    @Override
    public KhUserSession issue(Long userId, String userName) {
        StpKit.KH_USER.login(userId, SaLoginModel.create().setExtra(StpKit.EXTRA_NAME, userName));
        return new KhUserSession(StpKit.KH_USER.getTokenName(), StpKit.KH_USER.getTokenValue());
    }
}
