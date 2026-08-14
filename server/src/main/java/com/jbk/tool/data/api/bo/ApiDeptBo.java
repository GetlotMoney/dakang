package com.jbk.tool.data.api.bo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
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
@TableName("api_dept")
@Schema(name = "ApiDeptBo", description = "$!{table.comment}")
public class ApiDeptBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @NotNull(groups = IdGroup.class,message = "部门信息不为空")
    private Long id;

    @Schema(description = "部门名称max20")
    @NotEmpty(groups = InsertGroup.class,message = "部门名称不为空")
    private String deptName;

    @Schema(description = "负责人ID")
    @TableField("DEPT_MANAGER_ID")
    private Long deptManagerId;

    @Schema(description = "部门父级id")
    @NotNull(groups = InsertGroup.class,message = "父级部门不为空")
    private Long deptParentId;

    @Schema(description = "排序max10000")
    @NotNull(groups = InsertGroup.class,message = "排序不为空")
    private Integer deptSort;

    @Schema(description = "部门描述max300")
    @TableField("DEPT_DESC")
    private String deptDesc;
}


