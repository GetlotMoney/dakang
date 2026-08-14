package com.jbk.tool.data.mall.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 商城订单列表项 Vo（E2E-09 S2）：小程序与 PC 台账共用。
 *
 * <p>电话恒脱敏；PC 台账同样看不到原号——只读台账没有拨号需求，
 * 泄露面却是全量订单。</p>
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
@Accessors(chain = true)
public class MallOrderVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "订单ID（string）")
    private String id;

    @Schema(description = "商城订单号")
    private String orderNo;

    @Schema(description = "下单用户ID（string）")
    private String userId;

    @Schema(description = "订单状态(1393)")
    private Integer orderStatus;

    @Schema(description = "支付状态(1394)：来自支付单")
    private Integer payStatus;

    @Schema(description = "商品金额(分)")
    private Long productAmountFen;

    @Schema(description = "配送费(分)")
    private Long deliveryFeeFen;

    @Schema(description = "订单总额(分)")
    private Long orderAmountFen;

    @Schema(description = "商品种类数")
    private Integer itemKindCount;

    @Schema(description = "首个商品名称（列表摘要）")
    private String firstProductName;

    @Schema(description = "首个商品主图")
    private String firstCoverUrl;

    @Schema(description = "履约前置仓名称")
    private String warehouseName;

    @Schema(description = "收货人姓名")
    private String receiverName;

    @Schema(description = "收货电话（脱敏）")
    private String maskedPhone;

    @Schema(description = "支付截止时间")
    private String payExpireTime;

    @Schema(description = "下单时间")
    private String createTime;

    @Schema(description = "取消/关闭时间")
    private String cancelTime;
}
