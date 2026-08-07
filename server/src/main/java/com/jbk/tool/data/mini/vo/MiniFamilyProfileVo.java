package com.jbk.tool.data.mini.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 家庭资料（小程序侧投影）。
 *
 * @author dakang
 * @since 2026-08-02
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "MiniFamilyProfileVo", description = "家庭资料")
public class MiniFamilyProfileVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "资料ID（string）")
    private String profileId;

    @Schema(description = "隐私同意时间 yyyyMMddHHmmss")
    private String privacyConsentTime;

    @Schema(description = "家庭人数")
    private Integer memberCount;

    @Schema(description = "用水习惯备注")
    private String waterHabitNote;

    @Schema(description = "最近更新时间 yyyyMMddHHmmss")
    private String updatedTime;
}
