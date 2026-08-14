package com.jbk.tool.data.mall.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 库存动作结果 Vo（E2E-09 R1-P1-2）。首次执行与幂等重放都从同一条幂等流水行构造：
 * 重放返回当初动作的冻结结果，绝不读"重放时刻的当前库存"冒充原结果。
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
@Accessors(chain = true)
public class MallStockAdjustResultVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "请求号（幂等锚，UUID）")
    private String requestId;

    @Schema(description = "前置仓ID（string）")
    private String warehouseId;

    @Schema(description = "SKU ID（string）")
    private String skuId;

    @Schema(description = "流水类型(1391)：1入库 2出库 3盘点调增 4盘点调减")
    private Integer flowType;

    @Schema(description = "可售增减（件，出库为负）")
    private Long availableChange;

    @Schema(description = "动作后可售数量（件，冻结于原动作时刻）")
    private Long availableAfter;

    @Schema(description = "动作后预占数量（件，冻结于原动作时刻）")
    private Long reservedAfter;
}
