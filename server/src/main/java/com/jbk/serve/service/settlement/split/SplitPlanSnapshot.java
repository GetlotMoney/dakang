package com.jbk.serve.service.settlement.split;

import com.jbk.tool.consts.settlement.SplitV2Enum.BasisLine;
import com.jbk.tool.consts.settlement.SplitV2Enum.RateMode;
import com.jbk.tool.consts.settlement.SplitV2Enum.RegionLevel;
import com.jbk.tool.consts.settlement.SplitV2Enum.RoleCode;
import com.jbk.tool.exception.JbkException;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 分润计划完整快照（S1 计划模型，任务书 5.1）。
 *
 * <p><b>完整</b>二字是对 V1 最大的纠偏：V1 的 {@code ws_split_config} 让每个收款方
 * 独立选取生效版本（任务书偏差 4），两个不同时间发布的比例可能被拼成一张谁也没
 * 审过的计划。V2 的计划是一个整体——要么整版生效，要么整版不生效，
 * {@link #validate()} 在发布与计算两个时点都跑同一套校验。</p>
 *
 * <p>纯值对象，无 Spring / MyBatis 依赖：计算正确性只该由输入决定。</p>
 *
 * @param planVersion 计划版本号（业务唯一）
 * @param items       计划项全集（两条基数线的所有角色比例）
 */
public record SplitPlanSnapshot(String planVersion, List<Item> items) {

    /**
     * 计划项。
     *
     * @param line        基数线
     * @param roleCode    角色编码
     * @param regionLevel 区域层级；非区域角色恒为 NONE
     * @param rateBp      万分比。FIXED 模式=直接比例；REGIONAL_CUMULATIVE 模式=该层累计上限（M7）
     * @param rateMode    比例模式
     */
    public record Item(BasisLine line, RoleCode roleCode, RegionLevel regionLevel,
                       int rateBp, RateMode rateMode) {
    }

    /** WATER_SALE 线必须显式配齐的角色：缺项即拒绝，绝不静默按 0（纠偏差 3）。 */
    private static final Set<RoleCode> WATER_REQUIRED = Set.of(
            RoleCode.WATER_OWNER, RoleCode.WATER_DIRECT_REFERRER,
            RoleCode.REGION_PROVINCE, RoleCode.REGION_CITY, RoleCode.REGION_COUNTY);

    /**
     * 整版校验（任务书 5.1「计划必须一次性完成校验后才能发布」+ 5.3 规则 3/4/12）。
     *
     * <p>「必须显式配齐」的含义：允许某角色比例为 0，但必须写出来——
     * 「显式 0」是决策，「缺失」是漏配。V1 的 effectiveRate 把两者都当 0 处理，
     * 已启用角色会静默丢收益（偏差 3），V2 从模型上排除这种歧义。</p>
     */
    public void validate() {
        if (planVersion == null || planVersion.isBlank()) {
            throw new JbkException("分润计划缺少版本号");
        }
        if (items == null || items.isEmpty()) {
            throw new JbkException("分润计划为空");
        }
        Set<String> seen = new HashSet<>();
        Map<RegionLevel, Integer> cumulative = new EnumMap<>(RegionLevel.class);
        for (Item it : items) {
            if (it == null || it.line() == null || it.roleCode() == null
                    || it.regionLevel() == null || it.rateMode() == null) {
                throw new JbkException("分润计划项字段缺失");
            }
            if (it.rateBp() < 0 || it.rateBp() > 10_000) {
                throw new JbkException("分润比例必须在 0~10000 万分比之间：" + it.roleCode());
            }
            if (!seen.add(it.line() + ":" + it.roleCode())) {
                throw new JbkException("分润计划同一线上角色重复：" + it.line() + "/" + it.roleCode());
            }
            validateRoleShape(it);
            if (it.rateMode() == RateMode.REGIONAL_CUMULATIVE) {
                cumulative.put(it.regionLevel(), it.rateBp());
            }
        }
        validateLineCompleteness();
        validateRegionMonotonic(cumulative);
        validateCeilings(cumulative);
    }

    /** 角色与线/层级/模式的合法搭配；配送费线只许配送员参与（M5）。 */
    private void validateRoleShape(Item it) {
        boolean isRegion = it.roleCode() == RoleCode.REGION_PROVINCE
                || it.roleCode() == RoleCode.REGION_CITY
                || it.roleCode() == RoleCode.REGION_COUNTY;
        if (it.roleCode() == RoleCode.PLATFORM_REMAINDER) {
            // 平台余数是恒等式规则，不是可配比例：配了就说明有人想绕开「余数归平台」
            throw new JbkException("平台余数不可作为计划项配置");
        }
        if (it.line() == BasisLine.DELIVERY_FEE && it.roleCode() != RoleCode.DELIVERY_COURIER) {
            // M5：机主、推荐人、区域服务商不得默认参与配送费。想参与=改需求，不是改配置
            throw new JbkException("配送费线只允许配置配送员比例：" + it.roleCode());
        }
        if (it.line() == BasisLine.WATER_SALE && it.roleCode() == RoleCode.DELIVERY_COURIER) {
            throw new JbkException("售水线不允许配置配送员比例");
        }
        if (isRegion) {
            RegionLevel expect = switch (it.roleCode()) {
                case REGION_PROVINCE -> RegionLevel.PROVINCE;
                case REGION_CITY -> RegionLevel.CITY;
                default -> RegionLevel.COUNTY;
            };
            if (it.regionLevel() != expect) {
                throw new JbkException("区域角色与层级不匹配：" + it.roleCode() + "/" + it.regionLevel());
            }
            if (it.rateMode() != RateMode.REGIONAL_CUMULATIVE) {
                throw new JbkException("区域角色必须使用级差累计模式：" + it.roleCode());
            }
        }
        else {
            if (it.regionLevel() != RegionLevel.NONE) {
                throw new JbkException("非区域角色层级必须为 NONE：" + it.roleCode());
            }
            if (it.rateMode() != RateMode.FIXED) {
                throw new JbkException("非区域角色必须使用固定比例模式：" + it.roleCode());
            }
        }
    }

    private void validateLineCompleteness() {
        Set<RoleCode> waterRoles = new HashSet<>();
        boolean courierPresent = false;
        for (Item it : items) {
            if (it.line() == BasisLine.WATER_SALE) {
                waterRoles.add(it.roleCode());
            }
            else if (it.roleCode() == RoleCode.DELIVERY_COURIER) {
                courierPresent = true;
            }
        }
        for (RoleCode required : WATER_REQUIRED) {
            if (!waterRoles.contains(required)) {
                throw new JbkException("售水线缺少必需角色项（比例可为 0 但必须显式配置）：" + required);
            }
        }
        if (!courierPresent) {
            throw new JbkException("配送费线缺少配送员比例项");
        }
    }

    /** M7：区域比例是累计上限，省 ≥ 市 ≥ 区县 ≥ 0；倒挂即整版拒绝（矩阵 9）。 */
    private void validateRegionMonotonic(Map<RegionLevel, Integer> cumulative) {
        int province = cumulative.getOrDefault(RegionLevel.PROVINCE, 0);
        int city = cumulative.getOrDefault(RegionLevel.CITY, 0);
        int county = cumulative.getOrDefault(RegionLevel.COUNTY, 0);
        if (province < city || city < county) {
            throw new JbkException(String.format(
                    "区域累计比例倒挂：省 %d < 市 %d 或市 %d < 区县 %d（万分比）", province, city, city, county));
        }
    }

    /**
     * 静态上限：售水线非平台占用 = 机主 + 一级推荐 + 省级累计（级差三层合计恒等于
     * 省级累计，这正是级差模型的性质）；配送线 = 配送员比例。超 10000 即整版拒绝，
     * 不等到算出负数平台行才发现（规则 5/6 的发布时点防线）。
     */
    private void validateCeilings(Map<RegionLevel, Integer> cumulative) {
        int owner = 0;
        int referrer = 0;
        int courier = 0;
        for (Item it : items) {
            if (it.line() == BasisLine.WATER_SALE && it.roleCode() == RoleCode.WATER_OWNER) {
                owner = it.rateBp();
            }
            if (it.line() == BasisLine.WATER_SALE && it.roleCode() == RoleCode.WATER_DIRECT_REFERRER) {
                referrer = it.rateBp();
            }
            if (it.roleCode() == RoleCode.DELIVERY_COURIER) {
                courier = it.rateBp();
            }
        }
        int waterTotal = owner + referrer + cumulative.getOrDefault(RegionLevel.PROVINCE, 0);
        if (waterTotal > 10_000) {
            throw new JbkException("售水线非平台比例合计超出 100%：" + waterTotal + " 万分比");
        }
        if (courier > 10_000) {
            throw new JbkException("配送费线配送员比例超出 100%：" + courier + " 万分比");
        }
    }

    /** 取某线某角色的比例项；调用方保证 validate 已通过，故缺失即编程错误。 */
    Item itemOf(BasisLine line, RoleCode role) {
        return items.stream()
                .filter(it -> it.line() == line && it.roleCode() == role)
                .findFirst()
                .orElseThrow(() -> new JbkException("计划缺少角色项：" + line + "/" + role));
    }
}
