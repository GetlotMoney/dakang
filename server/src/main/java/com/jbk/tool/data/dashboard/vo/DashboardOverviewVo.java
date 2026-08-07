package com.jbk.tool.data.dashboard.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 运营总览聚合快照（PC 5+1 之一）。
 *
 * <p>口径与页面卡片一一对应并在此固化，防止前后端各自解释：
 * <ul>
 *   <li>今日=服务端本地日（CREATE_TIME 前 8 位），随自然日切换；</li>
 *   <li>今日营收=今日创建的取水+配送订单（ORDER_TYPE 1/3）中已支付族
 *       （2已支付/3出水中/4完成/6异常待补偿/8部分退款）的金额和，单位分；
 *       充值单不计入当日营收（预收），5取消/7全额退款不计；</li>
 *   <li>指令 24h 三态只数终态：4成功/5失败/6超时；成功率=成功/三态和，
 *       无终态样本时为 null（前端显示占位，不给假 100%）。</li>
 * </ul></p>
 *
 * @author dakang
 * @since 2026-08-02
 */
@Data
@Accessors(chain = true)
@Schema(name = "DashboardOverviewVo", description = "运营总览聚合快照（真实库聚合，非演示数据）")
public class DashboardOverviewVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "今日取水订单总数（ORDER_TYPE=1）")
    private Long todayWaterOrders;
    @Schema(description = "今日取水：已完成(4)")
    private Long todayWaterDone;
    @Schema(description = "今日取水：出水中(3)")
    private Long todayWaterDispensing;
    @Schema(description = "今日取水：异常待补偿(6)")
    private Long todayWaterException;
    @Schema(description = "今日取水：已取消(5)")
    private Long todayWaterCanceled;

    @Schema(description = "今日营收(分)，口径见类注释")
    private Long todayRevenueFen;

    @Schema(description = "在线设备数（ONLINE_STATUS=1）")
    private Long onlineDevices;
    @Schema(description = "设备总数")
    private Long totalDevices;

    @Schema(description = "24h 指令成功数(状态4)")
    private Long cmd24hSuccess;
    @Schema(description = "24h 指令失败数(状态5)")
    private Long cmd24hFail;
    @Schema(description = "24h 指令超时数(状态6)")
    private Long cmd24hTimeout;
    @Schema(description = "24h 指令成功率(%)，无终态样本时不下发")
    private Integer cmd24hSuccessRate;

    @Schema(description = "待处理：异常待补偿订单(状态6，全量)")
    private Long pendingExceptionOrders;
    @Schema(description = "待处理：待处理申诉(状态1)")
    private Long pendingAppeals;
    @Schema(description = "待处理：待审核配送员(状态1)")
    private Long pendingCouriers;
    @Schema(description = "待处理：待接单配送任务(状态1)")
    private Long pendingDeliveries;

    @Schema(description = "近 7 日订单趋势（含今日，缺日补零）")
    private List<TrendPoint> trend;

    @Data
    @Accessors(chain = true)
    @Schema(name = "DashboardTrendPoint", description = "单日订单量（按类型分列）")
    public static class TrendPoint implements Serializable {
        private static final long serialVersionUID = 1L;

        @Schema(description = "日期 yyyyMMdd")
        private String date;
        @Schema(description = "扫码取水单数")
        private Long waterOrders;
        @Schema(description = "购卡充值单数")
        private Long rechargeOrders;
        @Schema(description = "水配送单数")
        private Long deliveryOrders;
    }
}
