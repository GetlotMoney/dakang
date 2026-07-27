package com.jbk.tool.data.trade.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 流水追溯 Vo（ws_wallet_flow 简化投影，订单详情区块三）。
 * <p>
 * 对齐前端 order.ts 的 FlowTrace 契约：一条流水按其非零变动投影为余额(分)或水量(毫升)。
 * unit 取值 "分" 或 "毫升"；amount 正入负出。
 * </p>
 *
 * @author dakang
 * @since 2026-07-20
 */
@Data
@Schema(name = "FlowTraceVo", description = "流水追溯")
public class FlowTraceVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "流水ID")
    private Long flowId;

    @Schema(description = "流水类型标签（由 FLOW_TYPE 1344 映射）")
    private String flowType;

    @Schema(description = "变动值（分或毫升，见 unit）")
    private Long amount;

    @Schema(description = "计量单位：分 / 毫升")
    private String unit;

    @Schema(description = "该笔流水后的卡余额快照(分)（AMOUNT_AFTER）；本单最后一条有效流水的该值即「本单结算后余额」")
    private Long amountAfter;

    @Schema(description = "该笔流水后的卡水量快照(毫升)（ML_AFTER）；本单最后一条有效流水的该值即「本单结算后水量」")
    private Long mlAfter;

    @Schema(description = "流水时间")
    private String time;

    @Schema(description = "备注")
    private String remark;
}
