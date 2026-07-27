package com.jbk.tool.data.mini.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 小程序绑定手机号入参（POST /mini/auth/bind-phone）。
 *
 * @author dakang
 * @since 2026-07-21
 */
@Data
@Schema(name = "MiniBindPhoneBo", description = "小程序绑定手机号入参")
public class MiniBindPhoneBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "login 返回的一次性绑定票据 bindTicket")
    @NotBlank(message = "绑定票据不能为空")
    @Size(max = 128, message = "绑定票据格式非法")
    private String bindTicket;

    @Schema(description = "getPhoneNumber 回调的一次性 phoneCode")
    @NotBlank(message = "手机号授权 code 不能为空")
    @Size(max = 128, message = "手机号授权 code 过长")
    private String phoneCode;
}
