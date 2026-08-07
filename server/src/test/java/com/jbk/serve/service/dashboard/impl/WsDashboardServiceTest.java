package com.jbk.serve.service.dashboard.impl;

import com.jbk.serve.mapper.dashboard.DashboardStatsMapper;
import com.jbk.tool.data.dashboard.vo.DashboardOverviewVo;
import com.jbk.tool.utils.DateUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 总览聚合组装测试（mock 聚合 Mapper）：分状态映射、成功率口径（无样本不给假值、四舍五入）、
 * 趋势补零与日序。聚合 SQL 本身的正确性由验收库实测覆盖（见交付核验记录）。
 */
class WsDashboardServiceTest {

    private DashboardStatsMapper mapper;
    private WsDashboardServiceImpl service;

    private static Map<String, Object> row(Object k, Object v) {
        return Map.of("k", k, "v", v);
    }

    @BeforeEach
    void setup() {
        mapper = Mockito.mock(DashboardStatsMapper.class);
        service = new WsDashboardServiceImpl(mapper);
        when(mapper.countTodayWaterOrdersByStatus(anyString())).thenReturn(List.of());
        when(mapper.countCommandTerminalsSince(anyString())).thenReturn(List.of());
        when(mapper.countOrdersByDayAndType(anyString())).thenReturn(List.of());
    }

    // 今日取水分状态映射 + 总数为各状态之和
    @Test
    void waterBreakdownMapsByStatus() {
        when(mapper.countTodayWaterOrdersByStatus(anyString()))
                .thenReturn(List.of(row(4, 3L), row(3, 1L), row(6, 2L), row(5, 1L), row(1, 5L)));

        DashboardOverviewVo vo = service.overview();

        assertEquals(12L, vo.getTodayWaterOrders()); // 含待支付(1) 5 单：总数=全部状态之和
        assertEquals(3L, vo.getTodayWaterDone());
        assertEquals(1L, vo.getTodayWaterDispensing());
        assertEquals(2L, vo.getTodayWaterException());
        assertEquals(1L, vo.getTodayWaterCanceled());
    }

    // 指令成功率：round(成功/终态和)；6/(6+2+5)=46.15→46（对齐原演示卡口径）
    @Test
    void cmdSuccessRateRounds() {
        when(mapper.countCommandTerminalsSince(anyString()))
                .thenReturn(List.of(row(4, 6L), row(5, 2L), row(6, 5L)));

        DashboardOverviewVo vo = service.overview();

        assertEquals(6L, vo.getCmd24hSuccess());
        assertEquals(2L, vo.getCmd24hFail());
        assertEquals(5L, vo.getCmd24hTimeout());
        assertEquals(46, vo.getCmd24hSuccessRate());
    }

    // 无终态样本 → 成功率不下发（绝不给假 100%/0%）
    @Test
    void cmdSuccessRateNullWhenNoTerminals() {
        DashboardOverviewVo vo = service.overview();
        assertNull(vo.getCmd24hSuccessRate());
        assertEquals(0L, vo.getCmd24hSuccess());
    }

    // 趋势：恒 7 格、日序升序、末格=今日、缺日与缺类型补零、按类型正确分列
    @Test
    void trendFillsMissingDaysAndTypes() {
        String today = DateUtils.format(new Date(), "yyyyMMdd");
        String twoDaysAgo = DateUtils.format(DateUtils.addDateDays(new Date(), -2), "yyyyMMdd");
        when(mapper.countOrdersByDayAndType(anyString())).thenReturn(List.of(
                Map.of("d", today, "t", 1, "v", 4L),
                Map.of("d", today, "t", 3, "v", 2L),
                Map.of("d", twoDaysAgo, "t", 2, "v", 1L)));

        List<DashboardOverviewVo.TrendPoint> trend = service.overview().getTrend();

        assertEquals(7, trend.size());
        assertEquals(today, trend.get(6).getDate());
        assertEquals(4L, trend.get(6).getWaterOrders());
        assertEquals(0L, trend.get(6).getRechargeOrders());
        assertEquals(2L, trend.get(6).getDeliveryOrders());
        assertEquals(twoDaysAgo, trend.get(4).getDate());
        assertEquals(1L, trend.get(4).getRechargeOrders());
        // 无数据日全零且日期连续升序
        assertEquals(0L, trend.get(0).getWaterOrders() + trend.get(0).getRechargeOrders() + trend.get(0).getDeliveryOrders());
        for (int i = 1; i < trend.size(); i++) {
            org.junit.jupiter.api.Assertions.assertTrue(trend.get(i).getDate().compareTo(trend.get(i - 1).getDate()) > 0);
        }
    }
}
