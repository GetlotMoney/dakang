package com.jbk.tool.data.trade.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 创建扫码取水订单入参。
 * <p>不含 userId：下单人一律由 KH_USER 会话取（铁律6）；scanSessionId 既是设备/出水口来源，
 * 也是幂等 requestId（同会话重复提交返同单）。payWay 仅 2水卡余额/3水卡水量；1微信支付占位拒单。</p>
 *
 * @author dakang
 * @since 2026-07-19
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "CreateWaterOrderBo", description = "创建扫码取水订单入参")
public class CreateWaterOrderBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "扫码会话ID（L1a resolve 返回，兼作幂等 requestId）")
    @NotBlank(message = "扫码会话不能为空")
    private String scanSessionId;

    @Schema(description = "支付用水卡ID")
    @NotNull(message = "请选择支付水卡")
    private Long cardId;

    @Schema(description = "水种ID（须与出水口一致）")
    @NotNull(message = "水种不能为空")
    private Long waterTypeId;

    @Schema(description = "计划取水量(毫升)")
    @NotNull(message = "取水量不能为空")
    @Min(value = 1, message = "取水量必须大于0")
    // 单次取水量上限属产品规则 v1 占位（99L），甲方确认后仅改此常量；防超大值穿透到扣款层（H2 D8）
    @Max(value = 99000, message = "单次取水量超出上限")
    private Long planMl;

    @Schema(description = "支付方式(1346)：2水卡余额 3水卡水量")
    @NotNull(message = "支付方式不能为空")
    private Integer payWay;
}
