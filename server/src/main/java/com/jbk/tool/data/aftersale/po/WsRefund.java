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
 * 退款单 Po（E2E-04 包B；严格对齐 2026-07-29-aftersale-e2e04-b.sql 与 02-ws-business.sql 同一份 schema）。
 * 只表示支付机构金额退款，内部水卡返还不得伪造 ws_refund/ws_payment（任务书 3.2），后者走 ws_after_sale_action + ws_wallet_flow。
 * {@code AFTER_SALE_ID} 唯一键 uk_refund_after_sale 是重复退款的物理闸（铁律②）；该列可空，唯一键对 NULL 不去重。
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_refund")
@Schema(name = "WsRefund", description = "退款单表")
public class WsRefund extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "商户退款单号(out_refund_no)，由 RefundNo 按售后动作ID确定性派生")
    private String refundNo;

    @Schema(description = "订单ID")
    private Long orderId;

    @Schema(description = "商户订单号")
    private String orderNo;

    @Schema(description = "支付单ID；累计退款封顶按本列聚合")
    private Long paymentId;

    @Schema(description = "关联售后动作ID；唯一键保证一个售后动作至多一张退款单")
    private Long afterSaleId;

    @Schema(description = "退款金额(分)")
    private Long refundAmount;

    @Schema(description = "退款来源(1373)：服务端适配器常量，不取自报文或前端")
    private Integer refundSource;

    @Schema(description = "币种；退款事实回填时必须与本列一致")
    private String currency;

    @Schema(description = "退款原因(max500)")
    private String refundReason;

    @Schema(description = "退款状态(1343)：1退款中 2成功 3失败 4待重试 5需人工对账")
    private Integer refundStatus;

    @Schema(description = "支付机构退款单号；受理时回填")
    private String providerRefundId;

    @Schema(description = "退款请求发出时间")
    private String requestTime;

    @Schema(description = "支付机构确认退款成功的时间（取自事实，不取本地时钟）")
    private String successTime;

    @Schema(description = "乐观锁版本，退款状态机CAS的前态条件之一")
    private Integer version;

    @Schema(description = "重试次数")
    private Integer retryCount;

    @Schema(description = "下次可重试时间")
    private String nextRetryTime;

    @Schema(description = "最近一次失败原因；不写密钥、报文原文与本机路径")
    private String lastError;

    @Schema(description = "退款回调时间")
    private String callbackTime;

    @Schema(description = "退款回调原文JSON；禁止出接口")
    private String callbackPayload;
}
