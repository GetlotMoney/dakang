package com.jbk.tool.data.device.po;

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
 * 批量指令任务 Po（E2E-05 REQ-063）。
 *
 * <p>一批一行；子指令逐设备落 {@code ws_command} 并回指 {@code BATCH_ID}。
 * 聚合数量由子指令终态回写驱动（{@code WsCommandBatchMapper} 的原子累加 CAS），
 * 单设备失败绝不伪装整批成功。</p>
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_command_batch")
@Schema(name = "WsCommandBatch", description = "批量指令任务表")
public class WsCommandBatch extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @TableField("BATCH_NO")
    @Schema(description = "批次号，三端贯穿")
    private String batchNo;

    @TableField("SCOPE_TYPE")
    @Schema(description = "范围类型(1366)：1指定设备 2指定水站 3全部设备")
    private Integer scopeType;

    @TableField("SCOPE_SNAPSHOT")
    @Schema(description = "目标快照JSON：confirm 时后端解析结果冻结，事后审计只认它")
    private String scopeSnapshot;

    @TableField("CMD_TYPE")
    @Schema(description = "指令类型(1320)：批量只允许 3查询 4锁机 5解锁 6参数同步 7重启 8价格同步")
    private Integer cmdType;

    @TableField("CMD_PAYLOAD")
    @Schema(description = "参数本体JSON（参数同步/价格同步携带）")
    private String cmdPayload;

    @TableField("PARAM_DIGEST")
    @Schema(description = "命令类型+参数摘要 sha256：ticket 绑定与 confirm 一致性校验依据")
    private String paramDigest;

    @TableField("TOTAL_COUNT")
    @Schema(description = "子指令总数（=目标设备数）")
    private Integer totalCount;

    @TableField("SUCCESS_COUNT")
    @Schema(description = "成功数")
    private Integer successCount;

    @TableField("FAIL_COUNT")
    @Schema(description = "失败数（含设备拒绝/执行失败/部分完成——部分完成不是成功）")
    private Integer failCount;

    @TableField("TIMEOUT_COUNT")
    @Schema(description = "超时数")
    private Integer timeoutCount;

    @TableField("BATCH_STATUS")
    @Schema(description = "聚合状态(1367)：1处理中 2全部成功 3部分成功 4全部失败")
    private Integer batchStatus;

    @TableField("FINISH_TIME")
    @Schema(description = "聚合终态时间")
    private String finishTime;

    @TableField("VERSION")
    @Schema(description = "乐观锁版本")
    private Integer version;
}
