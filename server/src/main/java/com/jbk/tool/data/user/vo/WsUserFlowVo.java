package com.jbk.tool.data.user.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 用户资金流水行（ws_wallet_flow 只读投影，用户档案「资金流水」分区）。
 *
 * <p>AMOUNT_AFTER / ML_AFTER 是写入时冻结的权威快照，直接透出即可，
 * 绝不能拿卡当前余额倒推——后续任何一笔充值或取水都会让倒推值与事实不符。
 * 本投影没有任何写侧字段：资产在本模块恒为只读事实。</p>
 *
 * @author dakang
 * @since 2026-08-13
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsUserFlowVo", description = "用户资金流水行")
public class WsUserFlowVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "流水ID")
    private Long id;

    @Schema(description = "水卡ID")
    private Long cardId;

    @Schema(description = "卡号")
    private String cardNo;

    @Schema(description = "流水类型(1344)")
    private Integer flowType;

    @Schema(description = "余额变动(分)，正入负出")
    private Long amountChange;

    @Schema(description = "水量变动(毫升)，正入负出")
    private Long mlChange;

    @Schema(description = "变动后余额(分)快照")
    private Long amountAfter;

    @Schema(description = "变动后水量(毫升)快照")
    private Long mlAfter;

    @Schema(description = "关联订单ID")
    private Long orderId;

    @Schema(description = "备注")
    private String flowRemark;

    @Schema(description = "发生时间")
    private String createTime;
}
