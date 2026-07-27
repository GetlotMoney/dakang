package com.jbk.tool.data.mini.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 小程序登录/绑定结果判别联合（BOUND / UNBOUND）。
 * <ul>
 *   <li>BOUND：tokenName + tokenValue + accountContext（已建立合法 KH_USER 会话）；</li>
 *   <li>UNBOUND：bindTicket + expiresInSeconds（未绑定，凭票据授权手机号后调 bind-phone）。</li>
 * </ul>
 * <p>**绝不包含 openid / session_key**；判别字段 {@code result} 供前端分支。</p>
 *
 * @author dakang
 * @since 2026-07-21
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "MiniAuthResultVo", description = "小程序登录/绑定结果（BOUND/UNBOUND 判别联合，不含微信身份密钥）")
public class MiniAuthResultVo implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String RESULT_BOUND = "BOUND";
    public static final String RESULT_UNBOUND = "UNBOUND";

    @Schema(description = "判别：BOUND 已建会话 / UNBOUND 待绑定手机号")
    private String result;

    // ---- BOUND ----
    @Schema(description = "会话头名（= sa-token token-name，前端按此原样回传）")
    private String tokenName;

    @Schema(description = "会话 token 值")
    private String tokenValue;

    @Schema(description = "账号上下文")
    private MiniAccountContextVo accountContext;

    // ---- UNBOUND ----
    @Schema(description = "一次性绑定票据")
    private String bindTicket;

    @Schema(description = "绑定票据有效期（秒）")
    private Long expiresInSeconds;

    public static MiniAuthResultVo bound(String tokenName, String tokenValue, MiniAccountContextVo ctx) {
        MiniAuthResultVo vo = new MiniAuthResultVo();
        vo.setResult(RESULT_BOUND);
        vo.setTokenName(tokenName);
        vo.setTokenValue(tokenValue);
        vo.setAccountContext(ctx);
        return vo;
    }

    public static MiniAuthResultVo unbound(String bindTicket, long expiresInSeconds) {
        MiniAuthResultVo vo = new MiniAuthResultVo();
        vo.setResult(RESULT_UNBOUND);
        vo.setBindTicket(bindTicket);
        vo.setExpiresInSeconds(expiresInSeconds);
        return vo;
    }
}
