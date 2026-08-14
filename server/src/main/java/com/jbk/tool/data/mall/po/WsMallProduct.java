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
 * 商城商品 SPU Po（E2E-09 S1）：展示主体，定价与库存在 SKU。
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("ws_mall_product")
public class WsMallProduct extends BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "商品业务编号：唯一，不复用")
    @TableField("PRODUCT_NO")
    private String productNo;

    @Schema(description = "分类ID")
    @TableField("CATEGORY_ID")
    private Long categoryId;

    @Schema(description = "商品名称")
    @TableField("PRODUCT_NAME")
    private String productName;

    @Schema(description = "副标题")
    @TableField("PRODUCT_SUBTITLE")
    private String productSubtitle;

    @Schema(description = "主图（既有合法 URL/相对路径，本期无上传）")
    @TableField("COVER_URL")
    private String coverUrl;

    @Schema(description = "商品说明")
    @TableField("PRODUCT_DESC")
    private String productDesc;

    @Schema(description = "商品状态(1388)：1草稿 2已上架 3已下架")
    @TableField("PRODUCT_STATUS")
    private Integer productStatus;

    @Schema(description = "乐观锁版本：上下架 CAS 用")
    @TableField("VERSION")
    private Integer version;
}
