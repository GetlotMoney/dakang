package com.jbk.tool.data.device.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 批量指令任务视图（E2E-05 包B）。聚合数量直读 ws_command_batch——
 * 该表由子指令终态原子回写驱动，不在查询时现算，保证列表与详情口径一致。
 *
 * @author dakang
 * @since 2026-07-30
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsCommandBatchVo", description = "批量指令任务视图")
public class WsCommandBatchVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "批次号")
    private String batchNo;

    @Schema(description = "范围类型(1366)")
    private Integer scopeType;

    @Schema(description = "目标快照JSON")
    private String scopeSnapshot;

    @Schema(description = "指令类型(1320)")
    private Integer cmdType;

    @Schema(description = "参数本体JSON")
    private String cmdPayload;

    @Schema(description = "参数摘要")
    private String paramDigest;

    @Schema(description = "子指令总数")
    private Integer totalCount;

    @Schema(description = "成功数")
    private Integer successCount;

    @Schema(description = "失败数（含设备拒绝/执行失败/部分完成）")
    private Integer failCount;

    @Schema(description = "超时数")
    private Integer timeoutCount;

    @Schema(description = "聚合状态(1367)：1处理中 2全部成功 3部分成功 4全部失败")
    private Integer batchStatus;

    @Schema(description = "聚合终态时间")
    private String finishTime;

    @Schema(description = "创建时间")
    private String createTime;

    @Schema(description = "子指令明细（仅详情返回）")
    private List<WsCommandVo> commands;
}
