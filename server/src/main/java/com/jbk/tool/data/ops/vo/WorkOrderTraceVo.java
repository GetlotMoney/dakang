package com.jbk.tool.data.ops.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 工单状态轨迹条目（详情页完整轨迹，取自领域事件表）。
 *
 * @author dakang
 * @since 2026-07-30
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WorkOrderTraceVo", description = "工单状态轨迹条目")
public class WorkOrderTraceVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "发生时间")
    private String eventTime;

    @Schema(description = "操作端口(1364)：1公司后台 2用户端 3机主端 6系统")
    private Integer actorPortal;

    @Schema(description = "操作人ID")
    private Long actorId;

    @Schema(description = "变化内容（旧值→新值）")
    private String payload;
}
