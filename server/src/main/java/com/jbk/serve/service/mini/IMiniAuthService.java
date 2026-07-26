package com.jbk.serve.service.mini;

import com.jbk.tool.data.mini.bo.MiniBindPhoneBo;
import com.jbk.tool.data.mini.bo.MiniLoginBo;
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

    /** uni.login code 登录：已绑定→BOUND 建会话；未绑定→UNBOUND 下发一次性 bindTicket。 */
    MiniAuthResultVo login(MiniLoginBo bo);

    /** 凭 bindTicket + phoneCode 绑定手机号并建立 KH_USER 会话（BOUND）。 */
    MiniAuthResultVo bindPhone(MiniBindPhoneBo bo);
}
