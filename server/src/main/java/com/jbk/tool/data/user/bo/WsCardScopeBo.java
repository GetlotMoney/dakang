package com.jbk.tool.data.user.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 水卡授权范围维护 Bo（S4）。
 *
 * <p>只收结构化维度（站/设备/出水口 ID 集），SCOPE_JSON 由服务端统一构造——
 * 禁止运营直接编辑 JSON 原文；规范化与校验唯一入口是 WaterCardScope。</p>
 *
 * @author dakang
 * @since 2026-08-07
 */
@Data
public class WsCardScopeBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "水卡ID")
    @NotNull(message = "水卡信息不为空")
    private Long cardId;

    @Schema(description = "范围类型：all=全场通用 specified=指定范围")
    @NotBlank(message = "请选择范围类型")
    private String scopeType;

    @Schema(description = "水站ID集（specified 时与设备/出水口至少一维非空）")
    private List<Long> stationIds;

    @Schema(description = "设备ID集")
    private List<Long> deviceIds;

    @Schema(description = "出水口ID集")
    private List<Long> outletIds;

    @Schema(description = "修改原因（审计必填）")
    @NotBlank(message = "请填写修改原因")
    @Size(max = 200, message = "修改原因不能超过 200 字")
    private String reason;

    @Schema(description = "加载详情时的范围JSON原文（并发防覆盖锚；首次配置为空）")
    private String expectedScopeJson;
}
