package com.jbk.tool.data.mini.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 小程序登录入参（POST /mini/auth/login）。
 *
 * @author dakang
 * @since 2026-07-21
 */
@Data
@Schema(name = "MiniLoginBo", description = "小程序登录入参")
public class MiniLoginBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "uni.login 返回的一次性 code")
    @NotBlank(message = "登录 code 不能为空")
    @Size(max = 128, message = "登录 code 过长")
    private String code;
}
