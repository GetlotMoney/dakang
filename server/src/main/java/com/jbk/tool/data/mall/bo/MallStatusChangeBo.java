package com.jbk.tool.data.mall.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 启停状态变更 Bo（E2E-09 S1）：分类/前置仓共用；前置仓走 VERSION CAS。
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
public class MallStatusChangeBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "业务主键ID")
    @NotNull(message = "缺少必要参数")
    private Long id;

    @Schema(description = "目标状态：1启用 2停用")
    @NotNull(message = "请选择目标状态")
    private Integer targetStatus;

    @Schema(description = "当前版本（前置仓必填，并发防覆盖锚）")
    private Integer version;
}
