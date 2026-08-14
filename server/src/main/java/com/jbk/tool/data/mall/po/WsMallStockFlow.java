package com.jbk.tool.data.mall.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jbk.tool.data.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 商城库存流水 Po（E2E-09 S1）：只增不改；uk(BIZ_IDEMPOTENCY_KEY) 不含 DATA_STATUS，误删不解锁重放。
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("ws_mall_stock_flow")
public class WsMallStockFlow extends BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "幂等键：人工动作=MALLADJ:<requestId>")
    @TableField("BIZ_IDEMPOTENCY_KEY")
    private String bizIdempotencyKey;

    @Schema(description = "前置仓ID")
    @TableField("WAREHOUSE_ID")
    private Long warehouseId;

    @Schema(description = "SKU ID")
    @TableField("SKU_ID")
    private Long skuId;

    @Schema(description = "流水类型(1391)：1入库 2出库 3盘点调增 4盘点调减")
    @TableField("FLOW_TYPE")
    private Integer flowType;

    @Schema(description = "可售变化量(件)：带符号")
    @TableField("AVAILABLE_CHANGE")
    private Long availableChange;

    @Schema(description = "预占变化量(件)：本期恒0")
    @TableField("RESERVED_CHANGE")
    private Long reservedChange;

    @Schema(description = "动作后可售终值(件)")
    @TableField("AVAILABLE_AFTER")
    private Long availableAfter;

    @Schema(description = "动作后预占终值(件)")
    @TableField("RESERVED_AFTER")
    private Long reservedAfter;

    @Schema(description = "动作原因：必填")
    @TableField("FLOW_REASON")
    private String flowReason;

    @Schema(description = "操作人ID（管理端账号）")
    @TableField("OPERATOR_ID")
    private Long operatorId;
}
