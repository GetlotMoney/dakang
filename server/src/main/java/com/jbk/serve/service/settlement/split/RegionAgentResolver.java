package com.jbk.serve.service.settlement.split;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jbk.serve.mapper.settlement.WsRegionAgentMapper;
import com.jbk.tool.consts.settlement.SplitV2Enum;
import com.jbk.tool.data.settlement.po.WsRegionAgent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 区域服务商领地登记的查询原语：谁在什么时间段负责哪个行政区。
 *
 * <p>【边界】本类不决定订单归属，禁止接入分润计算——D-406 定归属以推荐关系链为核心并在
 * 建立时冻结，不按地缘重算；本类只服务 D-407 人工分配台账与运营筛选。护栏见
 * {@code SplitV2GuardrailTest#regionAgentResolverStaysOutOfSplitCalculation}。</p>
 *
 * <p>按时间版本化：取 {@code EFFECT_TIME <= atTime} 的最大一行；该行 AGENT_STATUS=2（停用）
 * 即返回空，不回溯上一个生效版本（会把已解约的人当成在任），也不兜底到上级行政区。</p>
 *
 * @author dakang
 * @since 2026-08-12
 */
@Service
@RequiredArgsConstructor
public class RegionAgentResolver {

    /** 生效状态；与建表注释 AGENT_STATUS:1生效 2停用 同源。 */
    public static final int STATUS_ACTIVE = 1;

    /** 停用状态：该版本行表示自 EFFECT_TIME 起该区域无服务商。 */
    public static final int STATUS_RETIRED = 2;

    private final WsRegionAgentMapper regionAgentMapper;

    /**
     * 水站的三级区划码；字段名与 {@code ws_station} 列名一致，
     * COUNTY ↔ districtCode 的对应由 {@link #codeOf} 一处钉死。
     */
    public record StationRegion(String provinceCode, String cityCode, String districtCode) {
    }

    /**
     * 区划码到层级的取值。COUNTY ↔ districtCode 是唯一一处名字不一致
     * （库列 DISTRICT_CODE 与 {@link SplitV2Enum.RegionLevel} 的 COUNTY 两边都改不动），
     * 在此映射一次并由测试钉住。
     */
    public static String codeOf(StationRegion region, SplitV2Enum.RegionLevel level) {
        return switch (level) {
            case PROVINCE -> region.provinceCode();
            case CITY -> region.cityCode();
            case COUNTY -> region.districtCode();
            case NONE -> null;
        };
    }

    /**
     * 解析某一级在 {@code atTime} 时点的服务商用户 ID。
     *
     * @param region 水站三级区划码；对应层级的码为空即返回空（水站没录区划码时不猜）
     * @param level  要解析的层级；传 NONE 返回空
     * @param atTime 业务时点 yyyyMMddHHmmss，通常是订单创建时间
     */
    public Optional<Long> resolve(StationRegion region, SplitV2Enum.RegionLevel level, String atTime) {
        if (region == null || level == null || StrUtil.isBlank(atTime)) {
            return Optional.empty();
        }
        String code = codeOf(region, level);
        if (StrUtil.isBlank(code)) {
            return Optional.empty();
        }
        LambdaQueryWrapper<WsRegionAgent> wrapper = new LambdaQueryWrapper<WsRegionAgent>()
                .eq(WsRegionAgent::getRegionLevel, level.name())
                .eq(WsRegionAgent::getRegionCode, code)
                // EFFECT_TIME 是定长 yyyyMMddHHmmss，字典序即时间序，字符串比较不失索引
                .le(WsRegionAgent::getEffectTime, atTime)
                .orderByDesc(WsRegionAgent::getEffectTime)
                .last("LIMIT 1");
        WsRegionAgent latest = regionAgentMapper.selectOne(wrapper);
        if (latest == null || latest.getAgentStatus() == null
                || latest.getAgentStatus() != STATUS_ACTIVE) {
            return Optional.empty();
        }
        return Optional.ofNullable(latest.getAgentUserId());
    }

    /**
     * 一次解析三级；返回 Map 只含解析出人的层级，空缺层级不放 null 值。
     */
    public Map<SplitV2Enum.RegionLevel, Long> resolveAll(StationRegion region, String atTime) {
        Map<SplitV2Enum.RegionLevel, Long> resolved = new EnumMap<>(SplitV2Enum.RegionLevel.class);
        for (SplitV2Enum.RegionLevel level : List.of(SplitV2Enum.RegionLevel.PROVINCE,
                SplitV2Enum.RegionLevel.CITY, SplitV2Enum.RegionLevel.COUNTY)) {
            resolve(region, level, atTime).ifPresent(userId -> resolved.put(level, userId));
        }
        return resolved;
    }
}
