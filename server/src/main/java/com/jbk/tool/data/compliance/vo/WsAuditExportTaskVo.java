package com.jbk.tool.data.compliance.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 审计导出任务展示对象（B23）。
 *
 * <p>{@code fileDigest}/{@code expireTime} 在对象存储接入前恒为 null，页面据此
 * 不渲染下载入口——不提供指向空文件的假下载按钮。</p>
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsAuditExportTaskVo", description = "审计导出任务")
public class WsAuditExportTaskVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "任务号")
    private String taskNo;

    @Schema(description = "导出范围")
    private String exportScope;

    @Schema(description = "筛选条件快照")
    private String filterSummary;

    @Schema(description = "申请原因")
    private String applyReason;

    @Schema(description = "脱敏规则快照")
    private String maskingRule;

    @Schema(description = "任务状态(1382)")
    private Integer taskStatus;

    @Schema(description = "任务状态名称，由服务端枚举常量下发（非运行时查字典）；"
            + "字典 1382 供后台展示配置，两者文案一致由 AuditExportContractTest 钉住")
    private String taskStatusName;

    @Schema(description = "申请人姓名快照")
    private String applyByName;

    @Schema(description = "申请时间yyyyMMddHHmmss")
    private String createTime;

    @Schema(description = "失败原因；仅失败态非空")
    private String failureReason;

    @Schema(description = "文件摘要；对象存储未接入时为空")
    private String fileDigest;

    @Schema(description = "下载过期时间；无真实文件时为空")
    private String expireTime;
}
