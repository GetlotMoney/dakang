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
 * 商城支付事实 Po（E2E-09 S2）：外部支付事实的收件箱。
 *
 * <p>事实与资金推进分离：本行落库即事实成立（事务A），订单与库存的推进另起事务B。
 * B 失败不回滚已落的事实——事实丢了就再也补不回来，而推进可以由 Worker 重试。</p>
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("ws_mall_payment_fact")
public class WsMallPaymentFact extends BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "支付来源：1微信 2Pay-Sim（服务端常量，不取自报文）")
    @TableField("PAY_SOURCE")
    private Integer paySource;

    @Schema(description = "事实渠道：1通知 2查询 3Pay-Sim")
    @TableField("FACT_CHANNEL")
    private Integer factChannel;

    @Schema(description = "外部事实键：与来源、渠道组成幂等锚")
    @TableField("PROVIDER_EVENT_KEY")
    private String providerEventKey;

    @Schema(description = "共键关联后的支付单ID；未知/错位时为空")
    @TableField("PAYMENT_ID")
    private Long paymentId;

    @Schema(description = "共键关联后的订单ID；未知/错位时为空")
    @TableField("ORDER_ID")
    private Long orderId;

    @Schema(description = "外部返回的商户订单号")
    @TableField("ORDER_NO")
    private String orderNo;

    @Schema(description = "规范化事实状态：SUCCESS/NOTPAY/CLOSED")
    @TableField("TRADE_STATE")
    private String tradeState;

    @Schema(description = "支付方交易号；SUCCESS必填")
    @TableField("TRANSACTION_ID")
    private String transactionId;

    @Schema(description = "支付方返回金额(分)；SUCCESS必填，禁止用内部金额补造")
    @TableField("PAY_AMOUNT_FEN")
    private Long payAmountFen;

    @Schema(description = "支付方返回币种；SUCCESS必填且为CNY")
    @TableField("CURRENCY")
    private String currency;

    @Schema(description = "支付成功时间；SUCCESS必填")
    @TableField("PAY_SUCCESS_TIME")
    private String paySuccessTime;

    @Schema(description = "原始事实正文存档")
    @TableField("RAW_BODY")
    private String rawBody;

    @Schema(description = "正文完整性摘要（非不可抵赖证明）")
    @TableField("RAW_BODY_SHA256")
    private String rawBodySha256;

    @Schema(description = "校验方式：1微信签名 2微信查询 3Pay-Sim内部")
    @TableField("VERIFY_METHOD")
    private Integer verifyMethod;

    @Schema(description = "处理状态(1395)：1待处理 2处理中 3已处理 4待重试 5需对账")
    @TableField("PROCESSING_STATUS")
    private Integer processingStatus;

    @Schema(description = "重试次数")
    @TableField("RETRY_COUNT")
    private Integer retryCount;

    @Schema(description = "下次可 claim 时间")
    @TableField("NEXT_RETRY_TIME")
    private String nextRetryTime;

    @Schema(description = "Worker claim 时间")
    @TableField("CLAIM_TIME")
    private String claimTime;

    @Schema(description = "claim 租约到期时间（进程崩溃后可被重新捞取）")
    @TableField("LEASE_UNTIL")
    private String leaseUntil;

    @Schema(description = "最近一次结构化失败原因：不写密钥与敏感正文")
    @TableField("LAST_ERROR")
    private String lastError;

    @Schema(description = "服务端接收时间")
    @TableField("RECEIVED_TIME")
    private String receivedTime;

    @Schema(description = "完成业务处理的时间")
    @TableField("PROCESSED_TIME")
    private String processedTime;
}
