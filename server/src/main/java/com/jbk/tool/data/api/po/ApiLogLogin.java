package com.jbk.tool.data.api.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
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
 * @since 2025-09-08
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("api_log_login")
@Schema(name = "ApiLogLogin", description = "$!{table.comment}")
public class ApiLogLogin implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键，自增ID")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "用户ID")
    @TableField("LOG_USER_ID")
    private Long logUserId;

    @Schema(description = "用户类型")
    @TableField("LOG_USER_TYPE")
    private String logUserType;

    @Schema(description = "用户姓名")
    @TableField("LOG_USER_NAME")
    private String logUserName;

    @Schema(description = "操作时间")
    @TableField("LOG_EXECUTE_TIME")
    private String logExecuteTime;

    @Schema(description = "登录类型120")
    @TableField("LOG_TYPE")
    private Integer logType;

    @Schema(description = "IP")
    @TableField("LOG_IP")
    private String logIp;

    @Schema(description = "客户端使用的浏览器或其他应用程序的信息")
    @TableField("LOG_USER_AGENT")
    private String logUserAgent;

    @Schema(description = "是否为移动平台1")
    @TableField("UA_IS_MOBILE")
    private Integer uaIsMobile;

    @Schema(description = "平台")
    @TableField("UA_PLATFORM")
    private String uaPlatform;

    @Schema(description = "浏览器")
    @TableField("UA_BROWSER")
    private String uaBrowser;

    @Schema(description = "浏览器版本")
    @TableField("UA_BROWSER_VERSION")
    private String uaBrowserVersion;

    @Schema(description = "引擎")
    @TableField("UA_ENGINE")
    private String uaEngine;

    @Schema(description = "引擎版本")
    @TableField("UA_ENGINE_VERSION")
    private String uaEngineVersion;

    @Schema(description = "操作系统")
    @TableField("UA_OS")
    private String uaOs;

    @Schema(description = "操作系统版本")
    @TableField("UA_OS_VERSION")
    private String uaOsVersion;
}


