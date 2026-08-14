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
 * 商城售后明细 Po（E2E-09 S4）：退款金额的唯一来源。
 *
 * <p>单价取自原订单明细快照而不是当前 SKU 价：商品事后调价不该改变已经卖出去那一单该退多少。</p>
 *
 * @author dakang
 * @since 2026-08-10
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("ws_mall_after_sale_item")
public class WsMallAfterSaleItem extends BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "售后单ID")
    @TableField("AFTER_SALE_ID")
    private Long afterSaleId;

    @Schema(description = "原订单明细ID")
    @TableField("ORDER_ITEM_ID")
    private Long orderItemId;

    @Schema(description = "SKU ID 快照")
    @TableField("SKU_ID")
    private Long skuId;

    @Schema(description = "商品名快照")
    @TableField("PRODUCT_NAME")
    private String productName;

    @Schema(description = "规格名快照")
    @TableField("SKU_NAME")
    private String skuName;

    @Schema(description = "单价快照(分)")
    @TableField("UNIT_PRICE_FEN")
    private Long unitPriceFen;

    @Schema(description = "申请数量(件)")
    @TableField("QUANTITY")
    private Integer quantity;

    @Schema(description = "行金额(分)")
    @TableField("ITEM_AMOUNT_FEN")
    private Long itemAmountFen;
}
