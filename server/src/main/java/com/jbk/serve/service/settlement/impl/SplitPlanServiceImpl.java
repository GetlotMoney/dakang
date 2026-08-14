package com.jbk.serve.service.settlement.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jbk.serve.mapper.settlement.WsSplitLineLockMapper;
import com.jbk.serve.mapper.settlement.WsSplitPlanItemMapper;
import com.jbk.serve.mapper.settlement.WsSplitPlanMapper;
import com.jbk.serve.service.settlement.ISplitPlanService;
import com.jbk.serve.service.settlement.split.SplitPlanSnapshot;
import com.jbk.tool.consts.settlement.SettlementEnum;
import com.jbk.tool.consts.settlement.SplitV2Enum;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.settlement.bo.SplitPlanBo;
import com.jbk.tool.data.settlement.po.WsSplitPlan;
import com.jbk.tool.data.settlement.po.WsSplitPlanItem;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 分润 V2 整版计划实现（D-412/D-428）。
 *
 * <p>发布在两条商品线锁锚内串行（固定顺序 WATER→DELIVERY 取锁，防死锁）：
 * 既防两笔计划并发交叉，也与 V1 单行版本写入互斥——切换期两套配置不会在
 * 同一瞬间各自"校验通过"后交叉落库。整版校验复用 {@link SplitPlanSnapshot#validate()}，
 * 发布与计算两个时点跑同一套判据。</p>
 *
 * @author dakang
 * @since 2026-08-14
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SplitPlanServiceImpl implements ISplitPlanService {

    private final WsSplitPlanMapper planMapper;
    private final WsSplitPlanItemMapper itemMapper;
    private final WsSplitLineLockMapper lineLockMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long publish(SplitPlanBo bo, Long opUserId) {
        if (ObjectUtil.hasNull(bo.getWaterOwnerBp(), bo.getWaterReferrerBp(),
                bo.getRegionProvinceCumBp(), bo.getRegionCityCumBp(),
                bo.getRegionCountyCumBp(), bo.getDeliveryCourierBp())) {
            // 整版语义：六个数缺一即拒——「显式 0」是决策，「缺失」是漏配（纠 V1 偏差 3）
            throw new JbkException("六方比例必须全部给出（可为 0，不可缺省）");
        }
        String now = DateUtils.time();
        String effect = StrUtil.blankToDefault(bo.getEffectTime(), now);
        if (!effect.matches("^\\d{14}$") || effect.compareTo(now) < 0) {
            throw new JbkException("生效时间必须是不早于当前的 yyyyMMddHHmmss");
        }
        // 固定顺序锁两条线锚：与 V1 configCreate 的单线锁互斥，切换期不并发交叉
        if (ObjectUtil.isNull(lineLockMapper.lockLine(SettlementEnum.ProductLine.WATER.getValue()))
                || ObjectUtil.isNull(lineLockMapper.lockLine(SettlementEnum.ProductLine.DELIVERY.getValue()))) {
            throw new JbkException("商品线锁锚未初始化（请先执行 2026-08-07-split-line-lock 迁移），拒绝发布");
        }

        // 版本号=发布时刻，同秒并发发布按序号让位（uk_split_plan_version 兜底，最多 9 次）
        String version = nextFreeVersion(now);
        SplitPlanSnapshot snapshot = new SplitPlanSnapshot(version, List.of(
                item(SplitV2Enum.BasisLine.WATER_SALE, SplitV2Enum.RoleCode.WATER_OWNER,
                        SplitV2Enum.RegionLevel.NONE, bo.getWaterOwnerBp(), SplitV2Enum.RateMode.FIXED),
                item(SplitV2Enum.BasisLine.WATER_SALE, SplitV2Enum.RoleCode.WATER_DIRECT_REFERRER,
                        SplitV2Enum.RegionLevel.NONE, bo.getWaterReferrerBp(), SplitV2Enum.RateMode.FIXED),
                item(SplitV2Enum.BasisLine.WATER_SALE, SplitV2Enum.RoleCode.REGION_PROVINCE,
                        SplitV2Enum.RegionLevel.PROVINCE, bo.getRegionProvinceCumBp(),
                        SplitV2Enum.RateMode.REGIONAL_CUMULATIVE),
                item(SplitV2Enum.BasisLine.WATER_SALE, SplitV2Enum.RoleCode.REGION_CITY,
                        SplitV2Enum.RegionLevel.CITY, bo.getRegionCityCumBp(),
                        SplitV2Enum.RateMode.REGIONAL_CUMULATIVE),
                item(SplitV2Enum.BasisLine.WATER_SALE, SplitV2Enum.RoleCode.REGION_COUNTY,
                        SplitV2Enum.RegionLevel.COUNTY, bo.getRegionCountyCumBp(),
                        SplitV2Enum.RateMode.REGIONAL_CUMULATIVE),
                item(SplitV2Enum.BasisLine.DELIVERY_FEE, SplitV2Enum.RoleCode.DELIVERY_COURIER,
                        SplitV2Enum.RegionLevel.NONE, bo.getDeliveryCourierBp(),
                        SplitV2Enum.RateMode.FIXED)));
        // 整版校验：搭配合法性、必需角色齐备、省≥市≥区县、非平台合计≤100%——一处不过整版拒绝
        snapshot.validate();

        WsSplitPlan plan = new WsSplitPlan()
                .setPlanVersion(version)
                .setEffectTime(effect)
                .setPlanStatus(SplitV2Enum.PlanStatus.ACTIVE.getValue())
                .setPlanRemark(StrUtil.brief(bo.getRemark(), 200));
        if (planMapper.insert(plan) != 1 || plan.getId() == null) {
            throw new JbkException("计划写入失败");
        }
        for (SplitPlanSnapshot.Item it : snapshot.items()) {
            WsSplitPlanItem row = new WsSplitPlanItem()
                    .setPlanId(plan.getId())
                    .setProductLine(it.line().name())
                    .setRoleCode(it.roleCode().name())
                    .setRegionLevel(it.regionLevel().name())
                    .setRateBp(it.rateBp())
                    .setRateMode(it.rateMode().name());
            if (itemMapper.insert(row) != 1) {
                throw new JbkException("计划项写入失败：" + it.roleCode());
            }
        }
        log.info("分润V2整版计划发布：version={} effect={} 六方=[{},{},{},{},{},{}] op={}",
                version, effect, bo.getWaterOwnerBp(), bo.getWaterReferrerBp(),
                bo.getRegionProvinceCumBp(), bo.getRegionCityCumBp(),
                bo.getRegionCountyCumBp(), bo.getDeliveryCourierBp(), opUserId);
        return plan.getId();
    }

    @Override
    public Optional<SplitPlanSnapshot> activePlanAt(String bizTime) {
        if (StrUtil.isBlank(bizTime)) {
            return Optional.empty();
        }
        WsSplitPlan plan = planMapper.selectOne(Wrappers.lambdaQuery(WsSplitPlan.class)
                .eq(WsSplitPlan::getPlanStatus, SplitV2Enum.PlanStatus.ACTIVE.getValue())
                .le(WsSplitPlan::getEffectTime, bizTime)
                .orderByDesc(WsSplitPlan::getEffectTime)
                .orderByDesc(WsSplitPlan::getId)
                .last("LIMIT 1"));
        if (plan == null) {
            return Optional.empty();
        }
        List<WsSplitPlanItem> rows = itemsOf(plan.getId());
        List<SplitPlanSnapshot.Item> items = new ArrayList<>();
        for (WsSplitPlanItem row : rows) {
            items.add(new SplitPlanSnapshot.Item(
                    SplitV2Enum.BasisLine.of(row.getProductLine())
                            .orElseThrow(() -> new JbkException("计划项基数线不合法：" + row.getProductLine())),
                    SplitV2Enum.RoleCode.of(row.getRoleCode())
                            .orElseThrow(() -> new JbkException("计划项角色不合法：" + row.getRoleCode())),
                    parseLevel(row.getRegionLevel()),
                    row.getRateBp(),
                    parseMode(row.getRateMode())));
        }
        SplitPlanSnapshot snapshot = new SplitPlanSnapshot(plan.getPlanVersion(), items);
        // 计算时点复跑整版校验（D-412：发布与计算跑同一套判据）；库行被人为改坏时
        // fail-closed 拒算，绝不带着半套计划分钱
        snapshot.validate();
        return Optional.of(snapshot);
    }

    @Override
    public PageDataVo<WsSplitPlan> page(SplitPlanBo bo) {
        Page<WsSplitPlan> page = planMapper.selectPage(new Page<>(bo.pageOrDefault(), bo.sizeOrDefault()),
                Wrappers.lambdaQuery(WsSplitPlan.class)
                        .orderByDesc(WsSplitPlan::getEffectTime)
                        .orderByDesc(WsSplitPlan::getId));
        return PageDataVo.getPageData(page.getRecords(), page.getTotal());
    }

    @Override
    public List<WsSplitPlanItem> itemsOf(Long planId) {
        if (planId == null) {
            return List.of();
        }
        return itemMapper.selectList(Wrappers.lambdaQuery(WsSplitPlanItem.class)
                .eq(WsSplitPlanItem::getPlanId, planId)
                .orderByAsc(WsSplitPlanItem::getId));
    }

    /**
     * 同秒多次发布的版本号让位：两条线锁锚已把发布串行化，锁内查库定序即可靠；
     * 上限 9 次纯属防御（人手不可能一秒发十版），超限报错而非死循环。
     */
    private String nextFreeVersion(String now) {
        for (int seq = 1; seq <= 9; seq++) {
            String candidate = "V2-" + now + (seq == 1 ? "" : "-" + seq);
            Long exists = planMapper.selectCount(Wrappers.lambdaQuery(WsSplitPlan.class)
                    .eq(WsSplitPlan::getPlanVersion, candidate));
            if (exists == null || exists == 0) {
                return candidate;
            }
        }
        throw new JbkException("同一秒内计划发布过于频繁，请稍后重试");
    }

    private static SplitPlanSnapshot.Item item(SplitV2Enum.BasisLine line, SplitV2Enum.RoleCode role,
                                               SplitV2Enum.RegionLevel level, int rateBp,
                                               SplitV2Enum.RateMode mode) {
        return new SplitPlanSnapshot.Item(line, role, level, rateBp, mode);
    }

    private static SplitV2Enum.RegionLevel parseLevel(String raw) {
        try {
            return SplitV2Enum.RegionLevel.valueOf(raw);
        }
        catch (IllegalArgumentException | NullPointerException e) {
            throw new JbkException("计划项区域层级不合法：" + raw);
        }
    }

    private static SplitV2Enum.RateMode parseMode(String raw) {
        try {
            return SplitV2Enum.RateMode.valueOf(raw);
        }
        catch (IllegalArgumentException | NullPointerException e) {
            throw new JbkException("计划项比例模式不合法：" + raw);
        }
    }
}
