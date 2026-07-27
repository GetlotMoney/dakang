package com.jbk.tool.data.api.bo;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jbk.tool.data.PageBo;
import com.jbk.tool.validator.group.IdGroup;
import com.jbk.tool.validator.group.InsertGroup;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import jakarta.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * <p>
 * 标签业务对象
 * </p>
 *
 * @author xs
 * @since 2025-09-15
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("api_employee_tag")
@Schema(name = "ApiEmployeeTagBo", description = "员工标签关联对象")
public class ApiEmployeeTagBo extends PageBo  implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    @NotNull(groups = IdGroup.class,message = "主键不为空")
    private Long id;

    @Schema(description = "标签ID")
    @NotNull(groups = InsertGroup.class, message = "标签ID不为空")
    private Long tagId;

    @Schema(description = "人员ID")
    @NotNull(groups = InsertGroup.class, message = "人员ID不为空")
    private Long employeeId;

}


