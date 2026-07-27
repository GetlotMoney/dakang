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
@TableName("api_dept")
@Schema(name = "ApiDeptVo", description = "$!{table.comment}")
public class ApiDeptVo extends BaseEntityVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "部门名称max20")
    @TableField("DEPT_NAME")
    private String deptName;

    @Schema(description = "负责人ID")
    @TableField("DEPT_MANAGER_ID")
    private Long deptManagerId;

    @Schema(description = "负责人")
    private ApiEmployeeVo deptManagerIdToEmployee;

    @Schema(description = "部门父级id")
    @TableField("DEPT_PARENT_ID")
    private Long deptParentId;

    @Schema(description = "排序max10000")
    @TableField("DEPT_SORT")
    private Integer deptSort;

    @Schema(description = "部门描述max300")
    @TableField("DEPT_DESC")
    private String deptDesc;
}


