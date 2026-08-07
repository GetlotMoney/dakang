package com.jbk.tool.data.settlement.po;

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
 * 日对账批任务 Po（E2E-08 包C）。同账期恒一行（uk_reconcile_task_date）：
 * 重跑=旧差异整批替换 + 任务行原位更新。
 *
 * @author dakang
 * @since 2026-07-31
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_reconcile_task")
@Schema(name = "WsReconcileTask", description = "日对账批任务")
public class WsReconcileTask extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "账期日（yyyyMMdd）")
    @TableField("BIZ_DATE")
    private String bizDate;

    @Schema(description = "任务状态(1380)：1执行中 2平账 3有差异")
    @TableField("TASK_STATUS")
    private Integer taskStatus;

    @Schema(description = "核对项数")
    @TableField("CHECK_TOTAL")
    private Integer checkTotal;

    @Schema(description = "差异项数")
    @TableField("DIFF_TOTAL")
    private Integer diffTotal;

    @Schema(description = "备注")
    @TableField("TASK_REMARK")
    private String taskRemark;
}
