package com.jbk.tool.data.api.vo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jbk.tool.data.BaseEntityVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * <p>
 * 
 * </p>
 *
 * @author xs
 * @since 2025-09-05
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("api_position")
@Schema(name = "ApiPositionVo", description = "$!{table.comment}")
public class ApiPositionVo extends BaseEntityVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "职务名称max20")
    @TableField("POSITION_NAME")
    private String positionName;

    @Schema(description = "职级")
    @TableField("POSITION_LEVEL")
    private String positionLevel;

    @Schema(description = "排序max10000")
    @TableField("POSITION_SORT")
    private Integer positionSort;

    @Schema(description = "备注max300")
    @TableField("POSITION_REMARK")
    private String positionRemark;
}


