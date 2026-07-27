package com.jbk.tool.data.mini.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 测试登录入参（仅隔离测试环境）。
 *
 * <p>刻意只收<b>手机号</b>而不是 userId：手机号必须命中库里一个已存在且可用的真实账号，
 * 而 userId 是可枚举的自增主键，收 userId 等于给了一个"输入 1 就变成任意用户"的开关。</p>
 */
@Data
@Schema(name = "MiniTestLoginBo", description = "测试登录入参（仅测试环境）")
public class MiniTestLoginBo implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "phone 不能为空")
    @Schema(description = "已存在账号的手机号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String phone;
}
