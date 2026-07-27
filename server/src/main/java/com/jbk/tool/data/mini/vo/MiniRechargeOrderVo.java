package com.jbk.tool.data.mini.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 充值订单创建结果（L2-ORDER）。订单一律停在待支付，创单接口不模拟支付成功。
 */
@Data
@Schema(name = "MiniRechargeOrderVo", description = "充值订单")
public class MiniRechargeOrderVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "订单号（RC + 30 位十六进制）")
    private String orderNo;

    @Schema(description = "订单状态(1341)：创建后固定为 1 待支付")
    private Integer orderStatus;

    @Schema(description = "支付状态(1342)：创建后固定为 1 待支付")
    private Integer payStatus;

    @Schema(description = "订单金额(分)，取自套餐售价快照")
    private Long orderAmountFen;

    @Schema(description = "不可变付款截止时间(yyyyMMddHHmmss)")
    private String payExpireTime;

    @Schema(description = "套餐名称（下单时快照）")
    private String packageName;

    @Schema(description = "目标水卡ID")
    private Long cardId;

    @Schema(description = "套餐ID")
    private Long packageId;

    @Schema(description = "订单创建时间")
    private String createTime;

    @Schema(description = "是否为幂等命中的既有订单（true=未新建）")
    private Boolean idempotentHit;
}
