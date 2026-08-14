package com.jbk.tool.data.mall.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 商城分类 Vo（E2E-09 S1）：ID 恒 string 出参。
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
@Accessors(chain = true)
public class MallCategoryVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "分类ID（string）")
    private String id;

    @Schema(description = "分类业务编码")
    private String categoryCode;

    @Schema(description = "分类名称")
    private String categoryName;

    @Schema(description = "排序号")
    private Integer categorySort;

    @Schema(description = "分类状态(1392)")
    private Integer categoryStatus;

    @Schema(description = "创建时间")
    private String createTime;
}
