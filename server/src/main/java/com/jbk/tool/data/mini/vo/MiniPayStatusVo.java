package com.jbk.tool.data.mini.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/** 充值订单支付状态（§9.1）。任一关键对象缺失/重复/错位均 fail-closed 返回 mismatch，不回退 Mock。 */
@Data
@Schema(name = "MiniPayStatusVo", description = "充值订单支付状态")
public class MiniPayStatusVo implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "订单号")
    private String orderNo;
    @Schema(description = "结构化状态码：WAITING_PAYMENT/PAID_CREDIT_PENDING/COMPLETED/CLOSED/RECONCILIATION_REQUIRED/REFUNDED/PART_REFUNDED/MISMATCH")
    private String payStatusCode;
    @Schema(description = "支付状态(1342)原值")
    private Integer payStatus;
    @Schema(description = "订单状态(1341)原值")
    private Integer orderStatus;
    @Schema(description = "支付来源：1微信 2Pay-Sim")
    private Integer paySource;
    @Schema(description = "事件处理态聚合（仅展示，不替代组合校验）")
    private String processingStatus;
    @Schema(description = "是否可由客户端重试轮询")
    private Boolean retryable;
    @Schema(description = "面向用户的状态文案")
    private String statusMessage;
    @Schema(description = "不可变付款截止时间")
    private String payExpireTime;
    @Schema(description = "订单完成时间（未完成为空）")
    private String finishTime;
}
