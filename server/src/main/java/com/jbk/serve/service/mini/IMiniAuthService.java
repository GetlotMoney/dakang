package com.jbk.serve.service.mini;

import com.jbk.tool.data.mini.bo.MiniBindPhoneBo;
import com.jbk.tool.data.mini.bo.MiniBindPhoneSelfBo;
import com.jbk.tool.data.mini.bo.MiniLoginBo;
import com.jbk.tool.data.mini.vo.MiniAccountContextVo;
import com.jbk.tool.data.mini.vo.MiniAuthResultVo;

/**
 * 小程序 L2-AUTH：正式微信登录与手机号绑定。
 * <p>建立 KH_USER 会话；全程 fail-closed（禁用/注销/删除拒绝、身份冲突拒绝、一次性票据不可重放）；
 * 响应绝不携带 openid / session_key；不提供任何免鉴权或测试账号入口。</p>
 *
 * @author dakang
 * @since 2026-07-21
 */
public interface IMiniAuthService {

    /**
     * uni.login code 登录。
     * <p>已建号→BOUND 建会话；新 openid 视开关分流：
     * {@code mini.auth.phoneless-register.enabled=true} 直接建号并 BOUND（手机号留空，后续自助补绑），
     * 否则 UNBOUND 下发一次性 bindTicket。</p>
     */
    MiniAuthResultVo login(MiniLoginBo bo);

    /** 凭 bindTicket + phoneCode 绑定手机号并建立 KH_USER 会话（BOUND）。 */
    MiniAuthResultVo bindPhone(MiniBindPhoneBo bo);

    /**
     * 已登录用户自助补绑手机号，返回刷新后的账号上下文（不换发会话）。
     * <p>只允许「未绑 → 已绑」；号码归属他人或本账号已绑一律 fail-closed。</p>
     */
    MiniAccountContextVo bindPhoneForCurrentUser(Long userId, MiniBindPhoneSelfBo bo);

    /**
     * 按当前会话用户重建账号上下文（资料更新后刷新用；不签发会话）。
     * <p>账号删除/禁用/注销一律 fail-closed 抛出，绝不吐出不可用账号的上下文。</p>
     */
    MiniAccountContextVo currentContext(Long userId);
}
