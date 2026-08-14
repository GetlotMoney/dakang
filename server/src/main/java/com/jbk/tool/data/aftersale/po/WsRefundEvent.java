package com.jbk.tool.data.aftersale.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jbk.tool.data.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 退款事实收件箱 Po（E2E-04 包B，R0-8）。形状与 {@code WsPaymentEvent} 逐项对齐。
 * 本表是退款成功的唯一入口：「退款成功」必须先落成一条事实，再由 Worker 核验后推进 ws_refund；
 * 三元幂等键 (REFUND_SOURCE, FACT_CHANNEL, PROVIDER_EVENT_KEY) 由数据库唯一索引保证只落一行、只推进一次。
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_refund_event")
@Schema(name = "WsRefundEvent", description = "退款事实收件箱表")
public class WsRefundEvent extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "退款来源(1373)：服务端适配器常量，不取自报文")
    private Integer refundSource;

    @Schema(description = "事实渠道：1通知 2查询 3Refund-Sim")
    private Integer factChannel;

    @Schema(description = "外部事实键；与来源+渠道构成幂等键")
    private String providerEventKey;

    @Schema(description = "可信共键关联后的退款单ID，未知/错位时为空")
    private Long refundId;

    @Schema(description = "可信共键关联后的售后动作ID，未知/错位时为空")
    private Long afterSaleId;

    @Schema(description = "可信共键关联后的订单ID，未知/错位时为空")
    private Long orderId;

    @Schema(description = "已验证的商户退款单号")
    private String refundNo;

    @Schema(description = "事实携带的商户订单号，用于交叉核对")
    private String orderNo;

    @Schema(description = "规范化退款事实状态：SUCCESS/PROCESSING/CLOSED/ABNORMAL/UNKNOWN")
    private String refundState;

    @Schema(description = "支付机构退款单号；SUCCESS必填")
    private String providerRefundId;

    @Schema(description = "支付机构返回退款金额(分)；SUCCESS必填，未返回必须为空，禁止用内部值顶替")
    private Long refundAmount;

    @Schema(description = "支付机构返回币种；SUCCESS必填且为CNY")
    private String currency;

    @Schema(description = "退款成功时间；SUCCESS必填")
    private String refundSuccessTime;

    @Schema(description = "原始正文/受保护查询证据；禁止出接口")
    private String rawBody;

    @Schema(description = "正文完整性摘要：同键重复到达时比对，不一致即转人工")
    private String rawBodySha256;

    @Schema(description = "校验方式：1微信签名 2微信查询 3Refund-Sim HMAC")
    private Integer verifyMethod;

    @Schema(description = "处理状态：1待处理 2处理中 3已处理 4待重试 5需对账")
    private Integer processingStatus;

    @Schema(description = "重试次数")
    private Integer retryCount;

    @Schema(description = "下次可claim时间")
    private String nextRetryTime;

    @Schema(description = "Worker claim 时间")
    private String claimTime;

    @Schema(description = "claim 租约到期时间（处理者崩溃后据此恢复）")
    private String leaseUntil;

    @Schema(description = "最近一次结构化失败原因；不写密钥与报文原文")
    private String lastError;

    @Schema(description = "服务端接收时间")
    private String receivedTime;

    @Schema(description = "该事实完成业务处理的时间")
    private String processedTime;
}
