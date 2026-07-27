package com.jbk.tool.data;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

/**
 * @ClassName BaseEntity
 * @Author xs
 * @Date 2024/6/7 14:35
 * @Version 1.0
 */
@Data
@Getter
@Setter
public abstract class BaseEntityVo implements Serializable {

    @Schema(description = "创建时间")
    private String createTime;

    @Schema(description = "更新时间")
    private String updateTime;

}
