package com.jbk.tool.data.trade.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 订单列表项 Vo（字段严格对齐 miniapp order.ts OrderItem，保证小程序零改）。
 * <p>金额分、水量毫升；后端 Jackson 全局把 Long 序列化为字符串防 JS 精度丢失，
 * 前端适配器按数值字符串归一化。字段名 orderId/orderAmountFen 与小程序一致（非 id/orderAmount）。</p>
 *
 * @author dakang
 * @since 2026-07-19
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "OrderItemVo", description = "订单列表项")
public class OrderItemVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "订单ID")
    private Long orderId;

    @Schema(description = "订单号")
    private String orderNo;

    @Schema(description = "下单用户ID")
    private Long userId;

    @Schema(description = "订单类型(1340)")
    private Integer orderType;

    @Schema(description = "订单状态(1341)")
    private Integer orderStatus;

    @Schema(description = "订单金额(分)")
    private Long orderAmountFen;

    @Schema(description = "支付方式(1346)")
    private Integer payWay;

    @Schema(description = "水站ID")
    private Long stationId;

    @Schema(description = "水站名称")
    private String stationName;

    @Schema(description = "设备编号")
    private String deviceNo;

    @Schema(description = "支付水卡ID")
    private Long cardId;

    @Schema(description = "计划水量(毫升)")
    private Long planMl;

    @Schema(description = "实际水量(毫升)")
    private Long actualMl;

    /**
     * 套餐/价格快照 JSON。
     *
     * <p><b>orderType=2（充值单）时必须为空</b>：契约 v2 §9.2 禁止向页面输出原始 PACKAGE_SNAP，
     * 充值信息一律走下方结构化的 {@link #recharge} 区块。取水单沿用原字段不变。</p>
     */
    @Schema(description = "套餐/价格快照JSON（充值单不下发，见 recharge 区块）")
    private String packageSnapshot;

    @Schema(description = "充值订单结构化详情；仅 orderType=2 有值")
    private MiniRechargeDetailVo recharge;

    @Schema(description = "创建时间 yyyyMMddHHmmss")
    private String createTime;

    @Schema(description = "完成时间 yyyyMMddHHmmss")
    private String finishTime;
}
