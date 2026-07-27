package com.jbk.tool.data.trade.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 指令时间线节点 Vo（订单追溯：下发→回执→结果/超时）。
 * <p>对齐前端 order.ts 的 CommandTraceNode 契约；由 ws_command 的时间戳与状态重建。</p>
 *
 * @author dakang
 * @since 2026-07-20
 */
@Data
@Schema(name = "CommandTraceNodeVo", description = "指令时间线节点")
public class CommandTraceNodeVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "节点：created下发 / ack回执 / result结果 / timeout超时")
    private String node;

    @Schema(description = "节点标签")
    private String nodeLabel;

    @Schema(description = "节点时间")
    private String time;

    @Schema(description = "附加说明（失败原因/实际水量等）")
    private String detail;

    @Schema(description = "时间线着色：success/warning/danger/info/primary")
    private String tone;
}
