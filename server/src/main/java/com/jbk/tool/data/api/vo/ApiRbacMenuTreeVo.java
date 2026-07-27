package com.jbk.tool.data.api.vo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

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
@TableName("api_rabc_menu")
@Schema(name = "ApiRbacMenuTreeVo", description = "$!{table.comment}")
public class ApiRbacMenuTreeVo extends ApiRbacMenuVo implements Serializable {

    @Schema(description = "下级内容")
    private List<ApiRbacMenuTreeVo> children;
}


