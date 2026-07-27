package com.jbk.tool.data.trade.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jbk.tool.data.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 支付单（L2-ORDER：与订单同事务创建，一单一支付单由 uk_payment_order_no/uk_payment_order_id 兜底）。
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_payment")
@Schema(name = "WsPayment", description = "支付单")
public class WsPayment extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "订单ID")
    @TableField("ORDER_ID")
    private Long orderId;

    @Schema(description = "商户订单号(out_trade_no)")
    @TableField("ORDER_NO")
    private String orderNo;

    @Schema(description = "支付方交易号，回调后回填")
    @TableField("TRANSACTION_ID")
    private String transactionId;

    @Schema(description = "支付金额(分)")
    @TableField("PAY_AMOUNT")
    private Long payAmount;

    @Schema(description = "支付状态(1342)：1待支付 2支付成功 3支付失败 4已关闭")
    @TableField("PAY_STATUS")
    private Integer payStatus;

    @Schema(description = "支付来源：1微信 2Pay-Sim（服务端适配器写入，创建后不可改）")
    @TableField("PAY_SOURCE")
    private Integer paySource;

    @Schema(description = "币种，一期固定 CNY")
    @TableField("CURRENCY")
    private String currency;

    @Schema(description = "创单冻结的付款截止时间，创建后不可修改")
    @TableField("PAY_EXPIRE_TIME")
    private String payExpireTime;

    @Schema(description = "权威支付成功时间")
    @TableField("PAY_SUCCESS_TIME")
    private String paySuccessTime;

    @Schema(description = "预支付ID")
    @TableField("PREPAY_ID")
    private String prepayId;

    @Schema(description = "回调时间")
    @TableField("CALLBACK_TIME")
    private String callbackTime;

    @Schema(description = "回调原文JSON")
    @TableField("CALLBACK_PAYLOAD")
    private String callbackPayload;
}
