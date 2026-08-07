package com.jbk.tool.data.settlement.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 邀请码补绑入参（E2E-08 包D）。无 userId 字段（铁律6：身份只认会话）。
 *
 * @author dakang
 * @since 2026-07-31
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "MiniInviteBo", description = "邀请码补绑入参")
public class MiniInviteBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "推荐人的邀请码")
    @NotBlank(message = "邀请码不能为空")
    @Size(max = 20, message = "邀请码格式不合法")
    private String inviteCode;
}
