package com.jbk.serve.service.dashboard.impl;

import com.jbk.serve.mapper.dashboard.DashboardStatsMapper;
import com.jbk.serve.service.dashboard.IWsDashboardService;
import com.jbk.tool.data.dashboard.vo.DashboardOverviewVo;
import com.jbk.tool.utils.DateUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 运营总览聚合实现。九条聚合 SQL 全部只读；时间边界在本层生成
 * （与全仓 varchar(14) 口径一致的字符串前缀比较），SQL 内零时区运算。
 *
 * @author dakang
 * @since 2026-08-02
 */
@Service
@RequiredArgsConstructor
public class WsDashboardServiceImpl implements IWsDashboardService {

    private static final int TREND_DAYS = 7;

    private final DashboardStatsMapper statsMapper;

    @Override
    public DashboardOverviewVo overview() {
        Date now = new Date();
        String today = DateUtils.format(now, "yyyyMMdd");
        String since24h = DateUtils.timeTransition(DateUtils.addDateHours(now, -24));
        String trendSinceDay = DateUtils.format(DateUtils.addDateDays(now, -(TREND_DAYS - 1)), "yyyyMMdd");

        Map<Integer, Long> waterByStatus = toCountMap(statsMapper.countTodayWaterOrdersByStatus(today));
        long waterTotal = waterByStatus.values().stream().mapToLong(Long::longValue).sum();

        Map<Integer, Long> cmd = toCountMap(statsMapper.countCommandTerminalsSince(since24h));
        long success = cmd.getOrDefault(4, 0L);
        long fail = cmd.getOrDefault(5, 0L);
        long timeout = cmd.getOrDefault(6, 0L);
        long terminals = success + fail + timeout;

        return new DashboardOverviewVo()
                .setTodayWaterOrders(waterTotal)
                .setTodayWaterDone(waterByStatus.getOrDefault(4, 0L))
                .setTodayWaterDispensing(waterByStatus.getOrDefault(3, 0L))
                .setTodayWaterException(waterByStatus.getOrDefault(6, 0L))
                .setTodayWaterCanceled(waterByStatus.getOrDefault(5, 0L))
                .setTodayRevenueFen(statsMapper.sumTodayRevenueFen(today))
                .setOnlineDevices(statsMapper.countOnlineDevices())
                .setTotalDevices(statsMapper.countTotalDevices())
                .setCmd24hSuccess(success)
                .setCmd24hFail(fail)
                .setCmd24hTimeout(timeout)
                // 无终态样本时不下发成功率：宁可空着也不给假 100%（口径见 VO 注释）
                .setCmd24hSuccessRate(terminals == 0 ? null : Math.toIntExact(Math.round(success * 100.0 / terminals)))
                .setPendingExceptionOrders(statsMapper.countPendingExceptionOrders())
                .setPendingAppeals(statsMapper.countPendingAppeals())
                .setPendingCouriers(statsMapper.countPendingCouriers())
                .setPendingDeliveries(statsMapper.countPendingDeliveries())
                .setTrend(buildTrend(now, statsMapper.countOrdersByDayAndType(trendSinceDay)));
    }

    /** 近 7 日趋势：按日×类型铺满（缺日补零），日序升序，最后一格恒为今日。 */
    private List<DashboardOverviewVo.TrendPoint> buildTrend(Date now, List<Map<String, Object>> rows) {
        Map<String, long[]> byDay = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String day = String.valueOf(row.get("d"));
            int type = Integer.parseInt(String.valueOf(row.get("t")));
            long count = Long.parseLong(String.valueOf(row.get("v")));
            long[] slot = byDay.computeIfAbsent(day, k -> new long[4]);
            if (type >= 1 && type <= 3) {
                slot[type] = count;
            }
        }
        List<DashboardOverviewVo.TrendPoint> trend = new ArrayList<>(TREND_DAYS);
        for (int offset = TREND_DAYS - 1; offset >= 0; offset--) {
            String day = DateUtils.format(DateUtils.addDateDays(now, -offset), "yyyyMMdd");
            long[] slot = byDay.getOrDefault(day, new long[4]);
            trend.add(new DashboardOverviewVo.TrendPoint()
                    .setDate(day)
                    .setWaterOrders(slot[1])
                    .setRechargeOrders(slot[2])
                    .setDeliveryOrders(slot[3]));
        }
        return trend;
    }

    /** GROUP BY 结果转计数表；JDBC 数值类型因驱动而异，一律经字符串安全转换。 */
    private static Map<Integer, Long> toCountMap(List<Map<String, Object>> rows) {
        Map<Integer, Long> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            result.put(Integer.parseInt(String.valueOf(row.get("k"))),
                    Long.parseLong(String.valueOf(row.get("v"))));
        }
        return result;
    }
}
