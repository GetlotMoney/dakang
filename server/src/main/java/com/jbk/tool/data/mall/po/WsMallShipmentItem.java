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
 * 商城出库包裹明细 Po（E2E-09 L1）。
 *
 * <p>{@code AFTER_SALE_ITEM_ID} 正向发货恒 0 而不是 null：唯一键含它，
 * 而 MySQL 的唯一索引里 null 之间互不冲突——用 null 会让同一订单明细能被重复塞进同一包裹。</p>
 *
 * @author dakang
 * @since 2026-08-11
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("ws_mall_shipment_item")
public class WsMallShipmentItem extends BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "所属包裹ID")
    @TableField("SHIPMENT_ID")
    private Long shipmentId;

    @Schema(description = "原订单明细ID")
    @TableField("ORDER_ITEM_ID")
    private Long orderItemId;

    @Schema(description = "售后明细ID；正向发货恒 0")
    @TableField("AFTER_SALE_ITEM_ID")
    private Long afterSaleItemId;

    @Schema(description = "SKU ID（冗余快照）")
    @TableField("SKU_ID")
    private Long skuId;

    @Schema(description = "本包裹内该明细件数，恒为正")
    @TableField("QUANTITY")
    private Integer quantity;
}
