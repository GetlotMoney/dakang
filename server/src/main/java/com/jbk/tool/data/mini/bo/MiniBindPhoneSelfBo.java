package com.jbk.tool.data.mini.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 登录后自助补绑手机号入参。
 *
 * <p>与 {@link MiniBindPhoneBo} 的区别：本请求由已建立的 KH_USER 会话授权，不需要 bindTicket。
 * 号码一律由服务端凭 {@code phoneCode} 向微信换取，前端<b>绝不</b>直传号码。</p>
 *
 * @author dakang
 * @since 2026-08-01
 */
@Data
@Schema(name = "MiniBindPhoneSelfBo", description = "登录后自助补绑手机号入参")
public class MiniBindPhoneSelfBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "手机号授权凭证不能为空")
    @Schema(description = "getPhoneNumber 回调返回的一次性 code", requiredMode = Schema.RequiredMode.REQUIRED)
    private String phoneCode;
}
