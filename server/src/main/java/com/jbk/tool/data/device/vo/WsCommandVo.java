package com.jbk.tool.data.device.vo;

import com.jbk.tool.data.BaseEntityVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 设备指令响应对象
 *
 * @author dakang
 * @since 2026-07-12
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsCommandVo", description = "设备指令响应对象")
public class WsCommandVo extends BaseEntityVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "平台指令号(max64)")
    private String cmdNo;

    @Schema(description = "目标设备ID")
    private Long deviceId;

    @Schema(description = "设备编号（关联派生）")
    private String deviceNo;

    @Schema(description = "设备名称（关联派生）")
    private String deviceName;

    @Schema(description = "关联订单ID")
    private Long orderId;

    @Schema(description = "指令类型(1320)：1开始出水 2停止出水 3查询状态 4锁机 5解锁 6参数同步 7重启")
    private Integer cmdType;

    @Schema(description = "下发报文JSON")
    private String cmdPayload;

    @Schema(description = "指令状态(1321)：1待下发 2已下发 3已回执 4执行成功 5执行失败 6超时 7部分完成")
    private Integer cmdStatus;

    @Schema(description = "下发时间")
    private String sentTime;

    @Schema(description = "设备回执时间")
    private String ackTime;

    @Schema(description = "终态时间")
    private String finishTime;

    @Schema(description = "执行结果报文JSON")
    private String resultPayload;

    @Schema(description = "失败/超时原因(max500)")
    private String failReason;

    @Schema(description = "重试次数")
    private Integer retryCount;
}
