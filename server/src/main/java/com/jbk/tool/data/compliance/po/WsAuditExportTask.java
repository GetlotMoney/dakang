package com.jbk.tool.data.compliance.po;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jbk.tool.data.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 审计导出任务（B23 / REQ-024、REQ-066）。
 *
 * <p>承载"谁在什么时候申请导出了什么范围"这项合规事实。文件生成依赖对象存储尚未接入，
 * 故 {@code fileDigest} 与 {@code expireTime} 在真实文件产出前恒为 null，
 * 任务也不得被平台推进到「已生成」。</p>
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_audit_export_task")
@Schema(name = "WsAuditExportTask", description = "审计导出任务")
public class WsAuditExportTask extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "任务号 AUD-EXP-yyyyMMdd-NNN，业务唯一")
    @TableField("TASK_NO")
    private String taskNo;

    @Schema(description = "导出范围，多项以、分隔(max200)")
    @TableField("EXPORT_SCOPE")
    private String exportScope;

    @Schema(description = "筛选条件快照，申请时冻结(max500)")
    @TableField("FILTER_SUMMARY")
    private String filterSummary;

    @Schema(description = "申请原因(max500)")
    @TableField("APPLY_REASON")
    private String applyReason;

    @Schema(description = "脱敏规则快照(max200)")
    @TableField("MASKING_RULE")
    private String maskingRule;

    @Schema(description = "任务状态(1382)：1待生成 2生成中 3失败 4已生成 5已过期")
    @TableField("TASK_STATUS")
    private Integer taskStatus;

    @Schema(description = "申请人姓名快照(max30)")
    @TableField("APPLY_BY_NAME")
    private String applyByName;

    @Schema(description = "失败原因；仅失败态非空(max500)")
    @TableField("FAILURE_REASON")
    private String failureReason;

    /**
     * 导出文件摘要；对象存储未接入时恒为空。
     *
     * <p>{@code FieldStrategy.NEVER} 让 ORM 层根本写不出这一列——源级守卫只能拦住
     * "写成某种文本形式"的代码，拦不住换个写法绕过去。对象存储接入时必须显式改掉这个注解，
     * 于是"能力边界被打开"这件事会出现在 diff 里，而不是藏在某个 setter 调用中。</p>
     */
    @Schema(description = "导出文件摘要；对象存储未接入时恒为空（ORM 层禁写）")
    @TableField(value = "FILE_DIGEST",
            insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String fileDigest;

    /** 下载过期时间；同 {@link #fileDigest}，ORM 层禁写。 */
    @Schema(description = "下载过期时间yyyyMMddHHmmss；无真实文件即为空（ORM 层禁写）")
    @TableField(value = "EXPIRE_TIME",
            insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String expireTime;
}
