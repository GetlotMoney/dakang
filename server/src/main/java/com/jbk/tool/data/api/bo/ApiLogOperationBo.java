package com.jbk.tool.data.api.bo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jbk.tool.data.PageBo;
import com.jbk.tool.validator.group.IdGroup;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.Accessors;

import jakarta.validation.constraints.NotNull;
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
@Builder
@TableName("api_log_operation")
@Schema(name = "ApiLogOperationBo", description = "$!{table.comment}")
@NoArgsConstructor
@AllArgsConstructor
public class ApiLogOperationBo extends PageBo implements Serializable {
    private static final long serialVersionUID = 1L;


    @Schema(description = "主键")
    @NotNull(groups = IdGroup.class, message = "信息不为空")
    private Long id;

    @Schema(description = "用户姓名")
    @TableField("LOG_USER_NAME")
    private String logUserName;

    @Schema(description = "操作模块")
    @TableField("LOG_MODULE")
    private String logModule;

    @Schema(description = "操作时间")
    @TableField("LOG_EXECUTE_TIME")
    private String logExecuteTimeBegin;

    @Schema(description = "操作时间")
    @TableField("LOG_EXECUTE_TIME")
    private String logExecuteTimeEnd;

    @Schema(description = "是否成功1")
    @TableField("LOG_SUCCESS_FLAG")
    private Integer logSuccessFlag;

    @Schema(description = "IP")
    @TableField("LOG_IP")
    private String logIp;
}


