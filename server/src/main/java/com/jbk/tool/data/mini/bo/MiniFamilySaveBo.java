package com.jbk.tool.data.mini.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 家庭资料保存入参（全部自愿字段；首次保存必须显式同意隐私说明）。
 *
 * @author dakang
 * @since 2026-08-02
 */
@Data
@Schema(name = "MiniFamilySaveBo", description = "家庭资料保存入参")
public class MiniFamilySaveBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "已阅读并同意隐私说明；首次保存必须为 true", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean privacyAccepted;

    @Min(value = 1, message = "家庭人数至少为 1")
    @Max(value = 99, message = "家庭人数超出合理范围")
    @Schema(description = "家庭人数（自愿）")
    private Integer memberCount;

    @Size(max = 200, message = "用水习惯备注不能超过200字")
    @Schema(description = "用水习惯备注（自愿）")
    private String waterHabitNote;
}
