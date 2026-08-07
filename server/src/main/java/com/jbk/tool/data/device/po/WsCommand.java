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
 * 设备指令表 Po（安全铁律 4：所有下行指令必须持久化，并由状态机跟踪至终态）
 *
 * @author dakang
 * @since 2026-07-12
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_command")
@Schema(name = "WsCommand", description = "设备指令表")
public class WsCommand extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "平台指令号(max64)，下发携带、回执按此关联")
    @TableField("CMD_NO")
    private String cmdNo;

    @Schema(description = "目标设备ID")
    @TableField("DEVICE_ID")
    private Long deviceId;

    @Schema(description = "关联订单ID（出水类指令必填，查询/锁机类为空）")
    @TableField("ORDER_ID")
    private Long orderId;

    @Schema(description = "指令类型(1320)：1开始出水 2停止出水 3查询状态 4锁机 5解锁 6参数同步 7重启")
    @TableField("CMD_TYPE")
    private Integer cmdType;

    @Schema(description = "下发报文JSON（出水口/水种/计划水量等）")
    @TableField("CMD_PAYLOAD")
    private String cmdPayload;

    @Schema(description = "指令状态(1321)：1待下发 2已下发 3已回执 4执行成功 5执行失败 6超时 7部分完成")
    @TableField("CMD_STATUS")
    private Integer cmdStatus;

    @Schema(description = "下发时间")
    @TableField("SENT_TIME")
    private String sentTime;

    @Schema(description = "设备回执时间")
    @TableField("ACK_TIME")
    private String ackTime;

    @Schema(description = "终态时间（成功/失败/超时/部分完成）")
    @TableField("FINISH_TIME")
    private String finishTime;

    @Schema(description = "执行结果报文JSON（实际水量等，异常补偿依据）")
    @TableField("RESULT_PAYLOAD")
    private String resultPayload;

    @Schema(description = "失败/超时原因(max500)")
    @TableField("FAIL_REASON")
    private String failReason;

    @Schema(description = "重试次数")
    @TableField("RETRY_COUNT")
    private Integer retryCount;

    @TableField("BATCH_ID")
    @Schema(description = "批量任务ID(ws_command_batch.ID)；单发指令为空。uk_cmd_batch_device 保证同批次同设备只有一条子指令")
    private Long batchId;
}
