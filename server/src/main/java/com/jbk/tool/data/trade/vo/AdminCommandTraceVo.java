package com.jbk.tool.data.trade.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 指令追溯 Vo（ws_command，订单详情区块二）。
 * <p>对齐前端 order.ts 的 CommandTrace 契约。deviceNo 复用订单已关联设备编号。</p>
 *
 * @author dakang
 * @since 2026-07-20
 */
@Data
@Schema(name = "AdminCommandTraceVo", description = "指令追溯")
public class AdminCommandTraceVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "指令ID")
    private Long cmdId;

    @Schema(description = "平台指令号")
    private String cmdNo;

    @Schema(description = "指令类型(1320)")
    private Integer cmdType;

    @Schema(description = "指令状态(1321)")
    private Integer cmdStatus;

    @Schema(description = "目标设备编号")
    private String deviceNo;

    @Schema(description = "下发报文JSON")
    private String payload;

    @Schema(description = "指令时间线（下发/回执/结果/超时）")
    private List<CommandTraceNodeVo> timeline;

    @Schema(description = "订单-指令关联校验结果：ok=共键一致；mismatch=关联异常（不提供报文与时间线）")
    private String linkStatus;

    @Schema(description = "关联异常原因（linkStatus=mismatch 时给出）")
    private String linkReason;
}
