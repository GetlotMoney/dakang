package com.jbk.tool.data.trade.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 订单追溯节点 Vo（详情页时间线，对齐契约 v1.1 修订4：node/label/time/detail/tone）。
 *
 * @author dakang
 * @since 2026-07-19
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "OrderTraceNodeVo", description = "订单追溯节点")
public class OrderTraceNodeVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "节点标识：CREATED/PAID/DISPENSING/FINISHED/ABNORMAL 等")
    private String node;

    @Schema(description = "节点显示名")
    private String label;

    @Schema(description = "节点时间 yyyyMMddHHmmss")
    private String time;

    @Schema(description = "节点明细说明")
    private String detail;

    @Schema(description = "节点色调：info/success/warning/danger")
    private String tone;
}
