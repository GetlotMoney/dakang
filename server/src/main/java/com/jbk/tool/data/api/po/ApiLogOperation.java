package com.jbk.tool.data.api.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;

import com.jbk.tool.data.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import jakarta.validation.constraints.NotNull;

/**
 * @author xs
 * @since 2025-09-05
 */
@Getter
@Setter
@Accessors(chain = true)
@Builder
@TableName("api_log_operation")
@Schema(name = "ApiLogOperation", description = "$!{table.comment}")
public class ApiLogOperation implements Serializable {

    private static final long serialVersionUID = 1L;


    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "用户ID")
    @TableField("LOG_USER_ID")
    private Long logUserId;

    @Schema(description = "用户类型")
    @TableField("LOG_USER_TYPE")
    private Integer logUserType;

    @Schema(description = "用户姓名")
    @TableField("LOG_USER_NAME")
    private String logUserName;

    @Schema(description = "操作模块")
    @TableField("LOG_MODULE")
    private String logModule;

    @Schema(description = "操作内容")
    @TableField("LOG_CONTENT")
    private String logContent;

    @Schema(description = "请求方法")
    @TableField("LOG_METHOD")
    private String logMethod;

    @Schema(description = "操作时间")
    @TableField("LOG_EXECUTE_TIME")
    private String logExecuteTime;

    @Schema(description = "是否成功1")
    @TableField("LOG_SUCCESS_FLAG")
    private Integer logSuccessFlag;

    @Schema(description = "IP")
    @TableField("LOG_IP")
    private String logIp;

    @Schema(description = "客户端使用的浏览器或其他应用程序的信息")
    @TableField("LOG_USER_AGENT")
    private String logUserAgent;

    @Schema(description = "耗时ms")
    @TableField("LOG_CONSUMER_TIME")
    private Integer logConsumerTime;

    @Schema(description = "请求URL")
    @TableField("LOG_URL")
    private String logUrl;

    @Schema(description = "请求参数")
    @TableField("LOG_REQUEST_PARAM")
    private String logRequestParam;

    @Schema(description = "返回参数")
    @TableField("LOG_RESPONSE_PARAM")
    private String logResponseParam;

    @Schema(description = "监控信息")
    @TableField("LOG_MONITOR_INFO")
    private String logMonitorInfo;
}


