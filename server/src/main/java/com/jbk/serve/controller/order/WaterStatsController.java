package com.jbk.serve.controller.order;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.jbk.serve.mapper.trade.WaterStatsMapper;
import com.jbk.serve.service.delivery.DeliveryPricing;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.domain.R;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.satoken.StpKit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按水种业务用量统计（S5，只读）。
 *
 * <p>只统计现有订单与配送任务能证明的业务事实；「工厂生产量」没有权威数据源，
 * VO 恒不给该字段、页面明确显示未提供——不用销售量冒充。穿透：行内水种名+时间
 * 窗即订单查询/配送任务页的筛选组合，明细以原单事实为准。</p>
 *
 * @author dakang
 * @since 2026-08-07
 */
@RestController
@RequestMapping("/order/waterStats")
@Tag(name = "水种用量统计")
public class WaterStatsController {

    @Autowired
    private WaterStatsMapper waterStatsMapper;

    /** 统计行：取水线（毫升）与配送线（桶/折算毫升）并列，补送单独立标识。 */
    @Data
    @Accessors(chain = true)
    public static class WaterStatsRow {
        private String waterTypeName;
        private long planMl;
        private long actualMl;
        private long shortfallMl;
        private long abnormalCount;
        private long deliveryBuckets;
        private long deliveryMl;
        private long resendBuckets;
        private long resendMl;
    }

    @Data
    public static class StatsQuery {
        private String startTime;
        private String endTime;
        private Long stationId;
        private String waterTypeName;
    }

    @PostMapping("/summary")
    @Operation(summary = "按水种汇总（取水计划/实际/退差 + 配送桶数/折算水量 + 异常单）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) })
    public R<List<WaterStatsRow>> summary(@RequestBody StatsQuery query) {
        String start = requireBizTime(query.getStartTime(), "开始时间");
        String end = requireBizTime(query.getEndTime(), "结束时间");
        if (start.compareTo(end) > 0) {
            throw new JbkException("开始时间不得晚于结束时间");
        }
        Map<String, WaterStatsRow> byName = new LinkedHashMap<>();
        for (WaterStatsMapper.WaterLineRow row : waterStatsMapper.aggregateWaterLine(
                start, end, query.getStationId())) {
            WaterStatsRow target = byName.computeIfAbsent(row.getWaterTypeName(),
                    name -> new WaterStatsRow().setWaterTypeName(name));
            target.setPlanMl(zeroIfNull(row.getPlanMl()));
            target.setActualMl(zeroIfNull(row.getActualMl()));
            target.setShortfallMl(zeroIfNull(row.getShortfallMl()));
            target.setAbnormalCount(zeroIfNull(row.getAbnormalCount()));
        }
        for (WaterStatsMapper.DeliveryLineRow row : waterStatsMapper.aggregateDeliveryLine(
                start, end, query.getStationId())) {
            WaterStatsRow target = byName.computeIfAbsent(row.getWaterTypeName(),
                    name -> new WaterStatsRow().setWaterTypeName(name));
            // 折算水量唯一出处 DeliveryPricing（与下单/扣款同表），SQL 里绝不写第二份换算
            long unitMl = DeliveryPricing.requireUnitWaterMl(row.getContainerSpec());
            long sale = zeroIfNull(row.getSaleBuckets());
            long resend = zeroIfNull(row.getResendBuckets());
            target.setDeliveryBuckets(target.getDeliveryBuckets() + sale);
            target.setDeliveryMl(target.getDeliveryMl() + sale * unitMl);
            target.setResendBuckets(target.getResendBuckets() + resend);
            target.setResendMl(target.getResendMl() + resend * unitMl);
        }
        List<WaterStatsRow> rows = byName.values().stream()
                .filter(row -> query.getWaterTypeName() == null || query.getWaterTypeName().isBlank()
                        || row.getWaterTypeName().contains(query.getWaterTypeName().trim()))
                .toList();
        return R.ok(rows);
    }

    private static long zeroIfNull(Long value) {
        return value == null ? 0L : value;
    }

    private static String requireBizTime(String time, String label) {
        // 严格业务时间唯一谓词源（S5 R1）：14 位 ASCII 数字+可解析+round-trip，
        // 与发卡/结算同一判定，不再各写一份正则口径
        if (!com.jbk.serve.service.mini.card.CardEligibility.isStrictBizTime(time)) {
            throw new JbkException(label + "必须为完整业务时间");
        }
        return time;
    }
}
