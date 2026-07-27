package com.jbk.tool.data.api.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;

import com.jbk.tool.data.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * <p>
 * 
 * </p>
 *
 * @author xs
 * @since 2025-09-15
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("api_employee_tag")
@Schema(name = "ApiEmployeeTag", description = "$!{table.comment}")
public class ApiEmployeeTag extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "标签ID")
    @TableField("TAG_ID")
    private Long tagId;

    @Schema(description = "人员ID")
    @TableField("EMPLOYEE_ID")
    private Long employeeId;
}


