package com.jbk.tool.data.compliance.bo;

import com.jbk.tool.data.PageBo;
import com.jbk.tool.validator.group.IdGroup;
import com.jbk.tool.validator.group.InsertGroup;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 审计导出任务业务对象（B23）。
 *
 * <p>申请参数在服务端组装成不可变快照后入库；任务号、状态、申请人与脱敏规则
 * 一律由服务端生成，不接受客户端传入——否则申请留痕可被伪造，失去合规意义。</p>
 *
 * @author dakang
 * @since 2026-08-06
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsAuditExportTaskBo", description = "审计导出任务业务对象")
public class WsAuditExportTaskBo extends PageBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @NotNull(groups = IdGroup.class, message = "任务信息不为空")
    private Long id;

    // ===== 申请参数（InsertGroup）=====

    @Schema(description = "导出范围，至少一项，如 操作日志/订单追溯/设备事件")
    @NotEmpty(groups = InsertGroup.class, message = "导出范围不为空")
    private List<String> exportScope;

    @Schema(description = "起始时间yyyyMMddHHmmss")
    @NotBlank(groups = InsertGroup.class, message = "起始时间不为空")
    @Size(groups = InsertGroup.class, max = 14, message = "起始时间格式不合法")
    private String startTime;

    @Schema(description = "截止时间yyyyMMddHHmmss")
    @NotBlank(groups = InsertGroup.class, message = "截止时间不为空")
    @Size(groups = InsertGroup.class, max = 14, message = "截止时间格式不合法")
    private String endTime;

    @Schema(description = "操作人关键字（可空）")
    @Size(groups = InsertGroup.class, max = 50, message = "操作人关键字不能超过50字")
    private String operatorKeyword;

    @Schema(description = "业务对象关键字（可空）")
    @Size(groups = InsertGroup.class, max = 50, message = "业务对象关键字不能超过50字")
    private String businessKeyword;

    @Schema(description = "申请原因，合规留痕必填")
    @NotBlank(groups = InsertGroup.class, message = "申请原因不为空")
    @Size(groups = InsertGroup.class, max = 200, message = "申请原因不能超过200字")
    private String applyReason;

    // ===== 查询条件 =====

    @Schema(description = "任务状态(1382) 过滤，可空")
    private Integer taskStatus;
}
