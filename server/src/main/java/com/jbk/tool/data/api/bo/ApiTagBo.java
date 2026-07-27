package com.jbk.tool.data.api.bo;

import com.baomidou.mybatisplus.annotation.TableField;
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
@TableName("api_tag")
@Schema(name = "ApiTagBo", description = "标签业务对象")
public class ApiTagBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @NotNull(groups = IdGroup.class, message = "标签ID不能为空")
    private Long id;


    @Schema(description ="人员标签")
    @NotEmpty(groups = InsertGroup.class, message = "人员标签不为空")
    private  String tagName;
}


