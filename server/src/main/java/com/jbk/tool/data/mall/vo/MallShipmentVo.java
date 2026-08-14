package com.jbk.tool.data.mall.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 商城出库包裹 Vo（E2E-09 L1）。
 *
 * <p>刻意不下发原始报文与承运方内部字段：那些只用于留证与排障，
 * 出网只会让页面开始解析承运商的私有格式，而那格式换一家就变。</p>
 *
 * @author dakang
 * @since 2026-08-11
 */
@Data
@Accessors(chain = true)
public class MallShipmentVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "包裹ID")
    private String shipmentId;

    @Schema(description = "商城订单号")
    private String orderNo;

    @Schema(description = "包裹方向(1405)：1正向发货 2退货 3换货补发")
    private Integer direction;

    @Schema(description = "包裹方向名称")
    private String directionName;

    @Schema(description = "承运渠道(1404)：1自营配送 2第三方物流")
    private Integer fulfillMode;

    @Schema(description = "承运渠道名称")
    private String fulfillModeName;

    @Schema(description = "承运商编码：自营恒 SELF")
    private String providerCode;

    @Schema(description = "服务类型编码：由承运商定义，平台不解释")
    private String serviceCode;

    @Schema(description = "运单号：未取得前为空")
    private String waybillNo;

    @Schema(description = "包裹状态(1406)")
    private Integer shipmentStatus;

    @Schema(description = "包裹状态名称")
    private String shipmentStatusName;

    @Schema(description = "包裹创建时间")
    private String createShipTime;

    @Schema(description = "揽收时间")
    private String pickupTime;

    @Schema(description = "送达时间")
    private String deliverTime;

    @Schema(description = "包裹明细")
    private List<Line> lines;

    @Schema(description = "第三方物流轨迹：自营包裹恒为空（自营轨迹在履约时间线上）")
    private List<TraceNode> logisticsTraces;

    /** 一行 = 一条订单明细在本包裹内的件数。 */
    @Data
    @Accessors(chain = true)
    public static class Line implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Schema(description = "原订单明细ID")
        private String orderItemId;

        @Schema(description = "SKU ID")
        private String skuId;

        @Schema(description = "件数")
        private Integer quantity;
    }

    /** 承运方轨迹节点：只展示状态名、时间与承运方文案。 */
    @Data
    @Accessors(chain = true)
    public static class TraceNode implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Schema(description = "事件状态(1407)")
        private String eventState;

        @Schema(description = "事件状态名称")
        private String eventStateName;

        @Schema(description = "事件时间")
        private String eventTime;

        @Schema(description = "承运方描述文案")
        private String eventDesc;
    }
}
