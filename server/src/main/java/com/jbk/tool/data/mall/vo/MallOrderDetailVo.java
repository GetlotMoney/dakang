package com.jbk.tool.data.mall.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 商城订单详情 Vo（E2E-09 S2）。
 *
 * <p>明细字段全部取自下单时冻结的快照，不回查商品表——商品改名改价后，
 * 历史订单必须仍显示成交当时的名称与价格。</p>
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
@Accessors(chain = true)
public class MallOrderDetailVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "订单概要")
    private MallOrderVo summary;

    @Schema(description = "收货地区文本快照")
    private String receiverRegion;

    @Schema(description = "收货详细地址快照")
    private String receiverAddress;

    @Schema(description = "收货区县行政区码快照")
    private String receiverDistrictCode;

    @Schema(description = "取消原因")
    private String cancelReason;

    @Schema(description = "支付来源：1微信 2Pay-Sim")
    private Integer paySource;

    @Schema(description = "支付方交易号（成功后有值）")
    private String transactionId;

    @Schema(description = "支付成功时间")
    private String paySuccessTime;

    @Schema(description = "订单明细")
    private List<OrderItemLine> items;

    /** 订单明细行：全部为下单时刻快照。 */
    @Data
    @Accessors(chain = true)
    public static class OrderItemLine implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Schema(description = "商品ID（string）")
        private String productId;

        @Schema(description = "订单明细ID：申请售后时按它定位原明细，退款金额只来自这一行的快照")
        private String orderItemId;

        @Schema(description = "SKU ID（string）")
        private String skuId;

        @Schema(description = "商品名称快照")
        private String productName;

        @Schema(description = "SKU 名称快照")
        private String skuName;

        @Schema(description = "规格快照（扁平 string→string）")
        private Map<String, String> specs;

        @Schema(description = "成交单价(分)")
        private Long unitPriceFen;

        @Schema(description = "数量(件)")
        private Integer quantity;

        @Schema(description = "行金额(分)")
        private Long itemAmountFen;

        @Schema(description = "单件重量(克)")
        private Long weightGram;
    }
}
