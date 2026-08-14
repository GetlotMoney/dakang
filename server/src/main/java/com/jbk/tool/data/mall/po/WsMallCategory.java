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
 * 商城商品分类 Po（E2E-09 S1）。
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("ws_mall_category")
public class WsMallCategory extends BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "分类业务编码：唯一，逻辑删除后不复用")
    @TableField("CATEGORY_CODE")
    private String categoryCode;

    @Schema(description = "分类名称")
    @TableField("CATEGORY_NAME")
    private String categoryName;

    @Schema(description = "排序号：小在前")
    @TableField("CATEGORY_SORT")
    private Integer categorySort;

    @Schema(description = "分类状态(1392)：1启用 2停用")
    @TableField("CATEGORY_STATUS")
    private Integer categoryStatus;
}
