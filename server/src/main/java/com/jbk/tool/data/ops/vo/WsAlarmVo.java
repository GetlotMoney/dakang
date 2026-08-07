package com.jbk.tool.data.ops.vo;

import com.jbk.tool.data.BaseEntityVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 设备告警响应对象（一期只读提醒，工单闭环为商业一期）
 *
 * @author dakang
 * @since 2026-07-12
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsAlarmVo", description = "设备告警响应对象")
public class WsAlarmVo extends BaseEntityVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "设备ID")
    private Long deviceId;

    @Schema(description = "告警类型(1360)：1设备离线 2故障码 3TDS超标 4滤芯超时 5SIM异常 6指令超时 7出水异常")
    private Integer alarmType;

    @Schema(description = "告警等级(1304)：1提示 2一般 3严重")
    private Integer alarmLevel;

    @Schema(description = "告警内容(max500)")
    private String alarmContent;

    @Schema(description = "来源引用(max64)")
    private String sourceRef;

    @Schema(description = "告警状态(1361)：1待处理 2已转工单 3已忽略 4自动恢复")
    private Integer alarmStatus;

    @Schema(description = "恢复时间")
    private String recoverTime;

    @Schema(description = "设备编号（联查）")
    private String deviceNo;

    @Schema(description = "转出的工单ID")
    private Long workOrderId;

    @Schema(description = "转出的工单号（详情联查，告警中心回看关联工单）")
    private String workOrderNo;

    @Schema(description = "处置人（忽略/转工单时回填）")
    private Long handleBy;

    @Schema(description = "处置时间")
    private String handleTime;
}
