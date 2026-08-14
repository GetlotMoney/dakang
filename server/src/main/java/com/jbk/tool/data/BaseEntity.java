package com.jbk.tool.data;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

@Data
@Getter
@Setter
public abstract class BaseEntity implements Serializable {
    @Schema(description = "状态[0：可用，1：不可用]")
    @TableLogic
    @TableField(value = "DATA_STATUS", fill = FieldFill.INSERT)
    private Integer dataStatus;

    @Schema(description = "创建者")
    @TableField(value = "CREATE_BY", fill = FieldFill.INSERT)
    private Long createBy;

    @Schema(description = "创建时间")
    @TableField(value = "CREATE_TIME", fill = FieldFill.INSERT)
    private String createTime;

    @Schema(description = "更新者")
    @TableField(value = "UPDATE_BY", fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;

    @Schema(description = "更新时间")
    @TableField(value = "UPDATE_TIME", fill = FieldFill.INSERT_UPDATE)
    private String updateTime;

}