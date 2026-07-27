package com.jbk.tool.data.product.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jbk.tool.data.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 水种字典表 Po（REQ-073：启停/排序/默认水种/后台维护）
 *
 * @author dakang
 * @since 2026-07-12
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_water_type")
@Schema(name = "WsWaterType", description = "水种字典表")
public class WsWaterType extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "水种名称(max20)")
    @TableField("WATER_NAME")
    private String waterName;

    @Schema(description = "排序")
    @TableField("WATER_SORT")
    private Integer waterSort;

    @Schema(description = "是否默认水种(1)：1否 2是")
    @TableField("DEFAULT_FLAG")
    private Integer defaultFlag;

    @Schema(description = "状态(10)：1正常 2禁用")
    @TableField("WATER_STATUS")
    private Integer waterStatus;

    @Schema(description = "水种说明(max500)")
    @TableField("WATER_DESC")
    private String waterDesc;
}
