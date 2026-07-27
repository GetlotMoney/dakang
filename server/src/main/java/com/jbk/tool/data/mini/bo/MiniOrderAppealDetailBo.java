package com.jbk.tool.data.mini.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 小程序本人申诉详情入参（E2E-03 包B / U06 申诉区块、消息深链）。
 * <p>appealId 按契约规则20 收正十进制字符串；归属校验在 Service（非本人按不存在拒绝）。</p>
 */
@Data
@Schema(name = "MiniOrderAppealDetailBo", description = "本人申诉详情入参")
public class MiniOrderAppealDetailBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "appealId 不能为空")
    @Size(max = 20, message = "appealId 不合法")
    @Schema(description = "申诉ID（正十进制字符串）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String appealId;
}
