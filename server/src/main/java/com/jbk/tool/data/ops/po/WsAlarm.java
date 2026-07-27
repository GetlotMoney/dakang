package com.jbk.tool.data.ops.po;

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
 * 设备告警表 Po（离线/故障/TDS超标/滤芯超时/SIM异常/指令超时统一入口）
 * <p>状态机必须能走到终态：1待处理 → 2已转工单 / 3已忽略 / 4自动恢复</p>
 *
 * @author dakang
 * @since 2026-07-12
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_alarm")
@Schema(name = "WsAlarm", description = "设备告警表")
public class WsAlarm extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "设备ID")
    @TableField("DEVICE_ID")
    private Long deviceId;

    @Schema(description = "告警类型(1360)：1设备离线 2故障码 3TDS超标 4滤芯超时 5SIM异常 6指令超时 7出水异常")
    @TableField("ALARM_TYPE")
    private Integer alarmType;

    @Schema(description = "告警等级(1304 复用故障等级)：1提示 2一般 3严重")
    @TableField("ALARM_LEVEL")
    private Integer alarmLevel;

    @Schema(description = "告警内容(max500)")
    @TableField("ALARM_CONTENT")
    private String alarmContent;

    @Schema(description = "来源引用(max64)：故障码/指令号/遥测ID")
    @TableField("SOURCE_REF")
    private String sourceRef;

    @Schema(description = "告警状态(1361)：1待处理 2已转工单 3已忽略 4自动恢复")
    @TableField("ALARM_STATUS")
    private Integer alarmStatus;

    @Schema(description = "转出的工单ID")
    @TableField("WORK_ORDER_ID")
    private Long workOrderId;

    @Schema(description = "恢复时间（自动恢复时回填）")
    @TableField("RECOVER_TIME")
    private String recoverTime;
}
