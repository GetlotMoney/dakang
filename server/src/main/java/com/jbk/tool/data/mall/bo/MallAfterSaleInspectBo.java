package com.jbk.tool.data.mall.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * 商城退货质检入参（E2E-09 S4）。
 *
 * <p>结论必须带说明：质检是退款与回库的唯一依据，一个没有原因的结论事后无从复核。</p>
 *
 * @author dakang
 * @since 2026-08-10
 */
@Data
@Accessors(chain = true)
public class MallAfterSaleInspectBo {

    @Schema(description = "售后单号")
    @NotBlank(message = "售后单号不能为空")
    @Size(max = 32, message = "售后单号过长")
    private String afterSaleNo;

    @Schema(description = "质检结论(1401)：1通过可重新销售 2通过不可重新销售 3不通过")
    @NotNull(message = "请给出质检结论")
    private Integer inspectResult;

    @Schema(description = "质检说明")
    @NotBlank(message = "请填写质检说明")
    @Size(max = 200, message = "质检说明不超过200字")
    private String inspectRemark;
}
