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
 * 商城 SKU Po（E2E-09 S1）：定价与库存主体。
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("ws_mall_sku")
public class WsMallSku extends BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "SKU业务编号：唯一，不复用")
    @TableField("SKU_NO")
    private String skuNo;

    @Schema(description = "商品ID")
    @TableField("PRODUCT_ID")
    private Long productId;

    @Schema(description = "SKU名称")
    @TableField("SKU_NAME")
    private String skuName;

    @Schema(description = "规格快照：扁平 string→string JSON，服务端唯一校验")
    @TableField("SPEC_SNAP")
    private String specSnap;

    @Schema(description = "售价(分)：正整数")
    @TableField("SALE_PRICE")
    private Long salePrice;

    @Schema(description = "划线价(分)：可空；有值时≥售价")
    @TableField("MARKET_PRICE")
    private Long marketPrice;

    @Schema(description = "重量(克)：非负整数")
    @TableField("WEIGHT_GRAM")
    private Long weightGram;

    @Schema(description = "SKU状态(1389)：1启用 2停用")
    @TableField("SKU_STATUS")
    private Integer skuStatus;

    @Schema(description = "乐观锁版本")
    @TableField("VERSION")
    private Integer version;
}
