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
 * 商城订单明细 Po（E2E-09 S2）：商品/SKU/规格/单价/重量下单即冻结。
 *
 * <p>行金额恒等式（行金额=单价×数量）由库层 CHECK 兜底——与订单金额恒等式一起，
 * 把"前端传金额"这条路径在数据库层封死。</p>
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("ws_mall_order_item")
public class WsMallOrderItem extends BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "所属订单ID")
    @TableField("ORDER_ID")
    private Long orderId;

    @Schema(description = "商品ID")
    @TableField("PRODUCT_ID")
    private Long productId;

    @Schema(description = "SKU ID")
    @TableField("SKU_ID")
    private Long skuId;

    @Schema(description = "商品名称快照")
    @TableField("PRODUCT_NAME")
    private String productName;

    @Schema(description = "SKU名称快照")
    @TableField("SKU_NAME")
    private String skuName;

    @Schema(description = "规格快照 JSON：编解码同 MallSpecSnapshot 约束")
    @TableField("SPEC_SNAP")
    private String specSnap;

    @Schema(description = "成交单价(分)：下单时 SKU 售价快照")
    @TableField("UNIT_PRICE_FEN")
    private Long unitPriceFen;

    @Schema(description = "购买数量(件)：恒>0")
    @TableField("QUANTITY")
    private Integer quantity;

    @Schema(description = "行金额(分)：恒等于单价×数量")
    @TableField("ITEM_AMOUNT_FEN")
    private Long itemAmountFen;

    @Schema(description = "单件重量(克)快照")
    @TableField("WEIGHT_GRAM")
    private Long weightGram;
}
