package com.jbk.tool.data.mini.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/** Pay-Sim 模拟支付结果。 */
@Data
@Schema(name = "MiniPaySimVo", description = "模拟支付结果")
public class MiniPaySimVo implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "订单号")
    private String orderNo;

    @Schema(description = "处理结果：CREDITED 本次入账 / ALREADY 之前已入账 / RECONCILIATION 已转人工 / MISMATCH 错位 / SKIPPED 未推进")
    private String resultCode;

    @Schema(description = "可读原因")
    private String message;

    @Schema(description = "模拟生成的外部交易号")
    private String transactionId;

    @Schema(description = "查单返回的支付事实状态：NOTPAY 未支付 / CLOSED 已关闭（仅查单接口返回）")
    private String tradeState;
}
