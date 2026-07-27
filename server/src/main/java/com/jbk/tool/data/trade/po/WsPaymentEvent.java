package com.jbk.tool.data.trade.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jbk.tool.data.BaseEntity;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 支付回调事实收件箱（L2 契约 §5.3）。L2-ORDER 只读取用于 pay-status 判定；写入属 L2-T。
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_payment_event")
public class WsPaymentEvent extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;
    @TableField("PAYMENT_ID")
    private Long paymentId;
    @TableField("ORDER_ID")
    private Long orderId;
    @TableField("ORDER_NO")
    private String orderNo;
    @TableField("PAY_SOURCE")
    private Integer paySource;
    @TableField("FACT_CHANNEL")
    private Integer factChannel;
    @TableField("PROVIDER_EVENT_KEY")
    private String providerEventKey;
    @TableField("TRADE_STATE")
    private String tradeState;
    @TableField("TRANSACTION_ID")
    private String transactionId;
    @TableField("PAY_AMOUNT")
    private Long payAmount;
    @TableField("CURRENCY")
    private String currency;
    @TableField("PAY_SUCCESS_TIME")
    private String paySuccessTime;
    @TableField("PROCESSING_STATUS")
    private Integer processingStatus;
    @TableField("RETRY_COUNT")
    private Integer retryCount;
    @TableField("NEXT_RETRY_TIME")
    private String nextRetryTime;
    @TableField("CLAIM_TIME")
    private String claimTime;
    @TableField("LEASE_UNTIL")
    private String leaseUntil;
    @TableField("RECOVERY_APPROVAL_GROUP_KEY")
    private String recoveryApprovalGroupKey;
    @TableField("RECOVERY_APPROVED_BY")
    private Long recoveryApprovedBy;
    @TableField("RECOVERY_APPROVED_TIME")
    private String recoveryApprovedTime;
    @TableField("RECOVERY_APPROVAL_REASON")
    private String recoveryApprovalReason;
    @TableField("LAST_ERROR")
    private String lastError;
    @TableField("PROCESSED_TIME")
    private String processedTime;
}
