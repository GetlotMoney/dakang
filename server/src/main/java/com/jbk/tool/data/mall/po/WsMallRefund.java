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
 * 商城退款单 Po（E2E-09 S4）：一售后一退款单，原路退回原支付单。
 *
 * <p>交易号唯一键是「同一笔渠道退款不得记到两张退款单」的库层防线；
 * 成功时间取自渠道事实而非本地时钟——本地时钟会让对账时间线与支付方对不上。</p>
 *
 * @author dakang
 * @since 2026-08-10
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("ws_mall_refund")
public class WsMallRefund extends BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "退款单号")
    @TableField("REFUND_NO")
    private String refundNo;

    @Schema(description = "售后单ID")
    @TableField("AFTER_SALE_ID")
    private Long afterSaleId;

    @Schema(description = "售后单号快照")
    @TableField("AFTER_SALE_NO")
    private String afterSaleNo;

    @Schema(description = "原商城订单ID")
    @TableField("ORDER_ID")
    private Long orderId;

    @Schema(description = "原商城订单号快照")
    @TableField("ORDER_NO")
    private String orderNo;

    @Schema(description = "原商城支付单ID")
    @TableField("PAYMENT_ID")
    private Long paymentId;

    @Schema(description = "收款用户ID")
    @TableField("USER_ID")
    private Long userId;

    @Schema(description = "退款金额(分)")
    @TableField("REFUND_AMOUNT_FEN")
    private Long refundAmountFen;

    @Schema(description = "币种")
    @TableField("CURRENCY")
    private String currency;

    @Schema(description = "退款状态(1402)")
    @TableField("REFUND_STATUS")
    private Integer refundStatus;

    @Schema(description = "退款来源")
    @TableField("REFUND_SOURCE")
    private Integer refundSource;

    @Schema(description = "渠道退款交易号")
    @TableField("REFUND_TRANSACTION_ID")
    private String refundTransactionId;

    @Schema(description = "渠道退款成功时间")
    @TableField("REFUND_SUCCESS_TIME")
    private String refundSuccessTime;
}
