package com.jbk.tool.data.product.vo;

import com.jbk.tool.data.BaseEntityVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 水种字典响应对象
 *
 * @author dakang
 * @since 2026-07-12
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsWaterTypeVo", description = "水种字典响应对象")
public class WsWaterTypeVo extends BaseEntityVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "水种名称(max20)")
    private String waterName;

    @Schema(description = "排序")
    private Integer waterSort;

    @Schema(description = "是否默认水种(1)：1否 2是")
    private Integer defaultFlag;

    @Schema(description = "状态(10)：1正常 2禁用")
    private Integer waterStatus;

    @Schema(description = "被出水口引用数（关联派生，删除前置校验参考）")
    private Long outletRefCount;

    @Schema(description = "水种说明(max500)")
    private String waterDesc;
}
