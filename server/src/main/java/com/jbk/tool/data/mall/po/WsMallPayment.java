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
 * 商城支付单 Po（E2E-09 S2）：一单一支付单。
 *
 * <p>ORDER_ID 指向 ws_mall_order，与一期 ws_payment.ORDER_ID（指向 ws_order）
 * 分属两个命名空间——两域订单 ID 存在碰撞可能，混用会让支付单指错订单。</p>
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("ws_mall_payment")
public class WsMallPayment extends BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "商城订单ID（ws_mall_order.ID）")
    @TableField("ORDER_ID")
    private Long orderId;

    @Schema(description = "商城订单号(out_trade_no)")
    @TableField("ORDER_NO")
    private String orderNo;

    @Schema(description = "支付方交易号：Pay-Sim 用不重叠命名空间")
    @TableField("TRANSACTION_ID")
    private String transactionId;

    @Schema(description = "应付金额(分)：创单冻结")
    @TableField("PAY_AMOUNT_FEN")
    private Long payAmountFen;

    @Schema(description = "支付状态(1394)：1待支付 2支付成功 3支付失败 4已关闭")
    @TableField("PAY_STATUS")
    private Integer payStatus;

    @Schema(description = "支付来源：1微信 2Pay-Sim；创建后不可改")
    @TableField("PAY_SOURCE")
    private Integer paySource;

    @Schema(description = "币种：内部闭环期固定CNY")
    @TableField("CURRENCY")
    private String currency;

    @Schema(description = "支付截止时间：与订单同源冻结")
    @TableField("PAY_EXPIRE_TIME")
    private String payExpireTime;

    @Schema(description = "权威支付成功时间：取自支付事实，不取本地时钟")
    @TableField("PAY_SUCCESS_TIME")
    private String paySuccessTime;

    @Schema(description = "支付关闭时间")
    @TableField("CLOSE_TIME")
    private String closeTime;
}
