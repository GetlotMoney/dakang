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
 * 商城库存 Po（E2E-09 S1）：仓×SKU 一行；一切扣减原子条件 UPDATE，禁止读出后内存回写。
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("ws_mall_stock")
public class WsMallStock extends BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "前置仓ID")
    @TableField("WAREHOUSE_ID")
    private Long warehouseId;

    @Schema(description = "SKU ID")
    @TableField("SKU_ID")
    private Long skuId;

    @Schema(description = "可售数量(件)：恒≥0")
    @TableField("AVAILABLE_QTY")
    private Long availableQty;

    @Schema(description = "预占数量(件)：恒≥0；本期恒0，S2 预占启用")
    @TableField("RESERVED_QTY")
    private Long reservedQty;

    @Schema(description = "乐观锁版本：随库存动作+1")
    @TableField("VERSION")
    private Integer version;
}
