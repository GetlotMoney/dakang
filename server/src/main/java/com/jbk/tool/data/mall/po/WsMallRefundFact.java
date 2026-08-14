package com.jbk.tool.data.mall.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jbk.tool.data.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 商城退款事实 Po（E2E-09 S4）：渠道事实收件箱。
 *
 * <p>事实只留证，不直接改订单、库存或售后状态——推进由事务B 在锁内重读后决定。
 * 与支付事实同一结构：事实丢了补不回来，推进可以重试。</p>
 *
 * @author dakang
 * @since 2026-08-10
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("ws_mall_refund_fact")
public class WsMallRefundFact extends BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "退款来源")
    @TableField("REFUND_SOURCE")
    private Integer refundSource;

    @Schema(description = "事实渠道")
    @TableField("FACT_CHANNEL")
    private Integer factChannel;

    @Schema(description = "渠道事件键")
    @TableField("PROVIDER_EVENT_KEY")
    private String providerEventKey;

    @Schema(description = "商城退款单ID")
    @TableField("REFUND_ID")
    private Long refundId;

    @Schema(description = "售后单ID")
    @TableField("AFTER_SALE_ID")
    private Long afterSaleId;

    @Schema(description = "原商城订单号")
    @TableField("ORDER_NO")
    private String orderNo;

    @Schema(description = "退款单号")
    @TableField("REFUND_NO")
    private String refundNo;

    @Schema(description = "规范化退款状态")
    @TableField("REFUND_STATE")
    private String refundState;

    @Schema(description = "渠道退款交易号")
    @TableField("REFUND_TRANSACTION_ID")
    private String refundTransactionId;

    @Schema(description = "渠道回报退款金额(分)")
    @TableField("REFUND_AMOUNT_FEN")
    private Long refundAmountFen;

    @Schema(description = "币种")
    @TableField("CURRENCY")
    private String currency;

    @Schema(description = "渠道退款成功时间")
    @TableField("REFUND_SUCCESS_TIME")
    private String refundSuccessTime;

    @Schema(description = "原始报文")
    @TableField("RAW_BODY")
    private String rawBody;

    @Schema(description = "原始报文SHA-256")
    @TableField("RAW_BODY_SHA256")
    private String rawBodySha256;

    @Schema(description = "校验方式")
    @TableField("VERIFY_METHOD")
    private Integer verifyMethod;

    @Schema(description = "处理状态(1403)")
    @TableField("PROCESSING_STATUS")
    private Integer processingStatus;

    @Schema(description = "重试次数")
    @TableField("RETRY_COUNT")
    private Integer retryCount;

    @Schema(description = "下次重试时间")
    @TableField("NEXT_RETRY_TIME")
    private String nextRetryTime;

    @Schema(description = "认领时间")
    @TableField("CLAIM_TIME")
    private String claimTime;

    @Schema(description = "租约到期")
    @TableField("LEASE_UNTIL")
    private String leaseUntil;

    @Schema(description = "最近失败原因")
    @TableField("LAST_ERROR")
    private String lastError;

    @Schema(description = "收单时间")
    @TableField("RECEIVED_TIME")
    private String receivedTime;

    @Schema(description = "处理完成时间")
    @TableField("PROCESSED_TIME")
    private String processedTime;
}
