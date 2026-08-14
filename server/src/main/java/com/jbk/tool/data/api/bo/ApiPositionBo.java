package com.jbk.tool.data.api.bo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jbk.tool.data.PageBo;
import com.jbk.tool.validator.group.IdGroup;
import com.jbk.tool.validator.group.InsertGroup;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * @author xs
 * @since 2025-09-05
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("api_position")
@Schema(name = "ApiPositionBo", description = "$!{table.comment}")
public class ApiPositionBo extends PageBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @NotNull(groups = IdGroup.class,message = "职务不为空")
    private Long id;

    @Schema(description = "职务名称max20")
    @NotEmpty(groups = InsertGroup.class,message = "职务名称不为空")
    private String positionName;

    @Schema(description = "职级")
    @TableField("POSITION_LEVEL")
    private String positionLevel;

    @Schema(description = "排序max10000")
    @NotNull(groups = InsertGroup.class,message = "排序不为空")
    private Integer positionSort;

    @Schema(description = "备注max300")
    @TableField("POSITION_REMARK")
    private String positionRemark;
}


