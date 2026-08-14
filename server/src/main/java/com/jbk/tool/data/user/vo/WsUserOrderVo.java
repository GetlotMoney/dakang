package com.jbk.tool.data.user.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 用户订单摘要行（ws_order 只读投影，用户档案「订单」分区）。
 *
 * <p>类型与状态原样透出 ORDER_TYPE(1340) / ORDER_STATUS(1341) 两列，与订单中心同一份字典、
 * 同一列取值——本分区只是换了一个入口看同一批事实，不引入第二套状态口径，也不提供任何推进动作。</p>
 *
 * @author dakang
 * @since 2026-08-13
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsUserOrderVo", description = "用户订单摘要行")
public class WsUserOrderVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "订单ID")
    private Long id;

    @Schema(description = "订单号")
    private String orderNo;

    @Schema(description = "订单类型(1340)")
    private Integer orderType;

    @Schema(description = "订单状态(1341)")
    private Integer orderStatus;

    @Schema(description = "订单金额(分)")
    private Long orderAmount;

    @Schema(description = "计划水量(毫升)")
    private Long planMl;

    @Schema(description = "实际水量(毫升)")
    private Long actualMl;

    @Schema(description = "下单时间")
    private String createTime;

    @Schema(description = "完成时间")
    private String finishTime;
}
