package com.jbk.tool.data.mall.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * 商城售后 Vo（E2E-09 S4）。
 *
 * <p>只下发结论性字段：退款金额、状态、时间线与商品行。原始支付事实、退款报文与数据库
 * 内部字段一律不进展示出口。</p>
 *
 * @author dakang
 * @since 2026-08-10
 */
@Data
@Accessors(chain = true)
public class MallAfterSaleVo {

    @Schema(description = "售后单号")
    private String afterSaleNo;

    @Schema(description = "原商城订单号")
    private String orderNo;

    @Schema(description = "售后类型(1400)")
    private Integer afterSaleType;

    @Schema(description = "售后类型名称")
    private String afterSaleTypeName;

    @Schema(description = "售后状态(1399)")
    private Integer afterSaleStatus;

    @Schema(description = "售后状态名称")
    private String afterSaleStatusName;

    @Schema(description = "应退商品金额(分)：服务端按原明细算出")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long refundAmountFen;

    @Schema(description = "申请原因")
    private String applyReason;

    @Schema(description = "申请时间")
    private String applyTime;

    @Schema(description = "质检结论(1401)")
    private Integer inspectResult;

    @Schema(description = "质检结论名称")
    private String inspectResultName;

    @Schema(description = "质检说明")
    private String inspectRemark;

    @Schema(description = "驳回原因")
    private String rejectReason;

    @Schema(description = "完成时间")
    private String finishTime;

    @Schema(description = "退款状态(1402)：无退款单时为空")
    private Integer refundStatus;

    @Schema(description = "退款成功时间")
    private String refundSuccessTime;

    @Schema(description = "换货补发订单号：换货且已补发时有值")
    private String exchangeOrderNo;

    @Schema(description = "售后商品行")
    private List<Line> lines;

    @Schema(description = "售后时间线")
    private List<MallFulfillTraceVo> timeline;

    /** 一行售后商品。 */
    @Data
    @Accessors(chain = true)
    public static class Line {

        @Schema(description = "原订单明细ID")
        @JsonSerialize(using = ToStringSerializer.class)
        private Long orderItemId;

        @Schema(description = "SKU ID")
        @JsonSerialize(using = ToStringSerializer.class)
        private Long skuId;

        @Schema(description = "商品名")
        private String productName;

        @Schema(description = "规格名")
        private String skuName;

        @Schema(description = "单价(分)")
        @JsonSerialize(using = ToStringSerializer.class)
        private Long unitPriceFen;

        @Schema(description = "申请数量(件)")
        private Integer quantity;

        @Schema(description = "行金额(分)")
        @JsonSerialize(using = ToStringSerializer.class)
        private Long itemAmountFen;
    }
}
