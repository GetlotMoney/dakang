package com.jbk.tool.data.api.bo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jbk.tool.data.PageBo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * @author xs
 * @since 2025-09-08
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("api_log_login")
@Schema(name = "ApiLogLoginBo", description = "$!{table.comment}")
public class ApiLogLoginBo extends PageBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键，自增ID")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "用户姓名")
    @TableField("LOG_USER_NAME")
    private String logUserName;

    @Schema(description = "操作时间")
    @TableField("LOG_EXECUTE_TIME")
    private String logExecuteTimeBegin;

    @Schema(description = "操作时间")
    @TableField("LOG_EXECUTE_TIME")
    private String logExecuteTimeEnd;

    @Schema(description = "登录类型120")
    @TableField("LOG_TYPE")
    private Integer logType;

    @Schema(description = "IP")
    @TableField("LOG_IP")
    private String logIp;
}


