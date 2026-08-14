package com.jbk.serve.service.settlement.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jbk.serve.mapper.device.WsDeviceMapper;
import com.jbk.serve.mapper.settlement.WsSplitComponentMapper;
import com.jbk.serve.mapper.settlement.WsSplitConfigMapper;
import com.jbk.serve.mapper.settlement.WsSplitRecordMapper;
import com.jbk.serve.mapper.station.WsStationMapper;
import com.jbk.serve.service.mini.card.CardEligibility;
import com.jbk.serve.service.settlement.IIncomeService;
import com.jbk.serve.service.settlement.IOwnerAttributionService;
import com.jbk.serve.service.settlement.ISplitPlanService;
import com.jbk.serve.service.settlement.ISplitService;
import com.jbk.serve.service.settlement.split.SplitCalcInput;
import com.jbk.serve.service.settlement.split.SplitComponentDraft;
import com.jbk.serve.service.settlement.split.SplitPlanCalculator;
import com.jbk.serve.service.settlement.split.SplitPlanSnapshot;
import com.jbk.tool.consts.settlement.SettlementEnum;
import com.jbk.tool.consts.settlement.SplitV2Enum;
import com.jbk.tool.data.settlement.po.WsOwnerAttribution;
import com.jbk.tool.data.settlement.po.WsSplitComponent;
import com.jbk.tool.data.settlement.po.WsSplitConfig;
import com.jbk.tool.data.settlement.po.WsSplitRecord;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 分账服务实现。不开独立事务：enqueueForOrder 加入订单完成事务（完成回滚分账同灭）。
 *
 * <p>金额拆分：各非平台收款方按万分比向下取整，平台行=基数-其余各行合计——
 * 整除余数天然归平台（任务书口径3），且全行合计恒等于基数（对账不变式）。</p>
 *
 * @author dakang
 * @since 2026-07-31
 */
@Slf4j
@Service
public class SplitServiceImpl extends ServiceImpl<WsSplitRecordMapper, WsSplitRecord> implements ISplitService {

    /**
     * 分润冻结期天数（D-421）：分账行创建满 N 天才允许结算入账。冻结期内钱未进收益账户，
     * 退款冲减=行直接置回退零资金动作——与 24h 申诉窗对齐，把 D-420 冲减的负余额问题
     * 压缩为冻结期外残余场景。0=立即结算（测试基线语义；生产 yml 配 1）。IncomeServiceImpl 读同一 key（循环依赖禁注入），改 key 两处同步。
     */
    @org.springframework.beans.factory.annotation.Value("${settlement.split-freeze-days:1}")
    private int freezeDays;

    /**
     * 分润 V2 开关（D-412/D-428）：true 时完成挂点在本类内分流到 V2 整版计划路径；
     * 该时点无生效计划则回落 V1——切换期连续性由「版本锚=订单创建时点」天然保证，
     * 计划生效前的旧单永远走 V1 口径。三个完成事务类不感知本开关（单入口原则）。
     */
    @Value("${settlement.split-v2.enabled:false}")
    private boolean v2Enabled;

    @Autowired
    private WsSplitConfigMapper configMapper;
    @Autowired
    private IIncomeService incomeService;
    @Autowired
    private WsDeviceMapper deviceMapper;
    @Autowired
    private WsStationMapper stationMapper;
    @Autowired
    private ISplitPlanService planService;
    @Autowired
    private IOwnerAttributionService attributionService;
    @Autowired
    private WsSplitComponentMapper componentMapper;

    @Override
    public void enqueueForOrder(Long orderId, String orderNo, long amountFen,
                                SettlementEnum.ProductLine productLine,
                                Long ownerUserId, Long courierUserId, String orderCreateTime) {
        if (orderId == null || amountFen <= 0 || productLine == null) {
            // 零元/异常单不产分账：静默返回而非报错——完成动作不因分账口径边界失败
            return;
        }
        // V2 分流（仅售水线；配送走 enqueueForDeliveryOrder）：该时点有生效整版计划才走 V2
        if (v2Enabled && productLine == SettlementEnum.ProductLine.WATER
                && v2EnqueueWater(orderId, orderNo, amountFen, ownerUserId, orderCreateTime)) {
            return;
        }
        // 收款方 → 用户ID（平台为 NULL）；LinkedHashMap 保证平台行最后算（吃余数）
        Map<SettlementEnum.ReceiverType, Long> receivers = new LinkedHashMap<>();
        if (ObjectUtil.isNotNull(ownerUserId)) {
            receivers.put(SettlementEnum.ReceiverType.OWNER, ownerUserId);
        }
        if (productLine == SettlementEnum.ProductLine.DELIVERY && ObjectUtil.isNotNull(courierUserId)) {
            receivers.put(SettlementEnum.ReceiverType.COURIER, courierUserId);
        }
        // 平台行 RECEIVER_USER_ID 用 0 哨兵而非 NULL：NULL 不参与 MySQL 唯一约束，
        // 会让 uk_split_order_receiver 对平台行失效（重放重复插入，SettlementDbTest 实测抓获）
        receivers.put(SettlementEnum.ReceiverType.PLATFORM, 0L);

        long assigned = 0;
        List<WsSplitRecord> rows = new ArrayList<>();
        for (Map.Entry<SettlementEnum.ReceiverType, Long> entry : receivers.entrySet()) {
            SettlementEnum.ReceiverType type = entry.getKey();
            long amount;
            String rateSnap;
            if (type == SettlementEnum.ReceiverType.PLATFORM) {
                // 平台=基数-已派发合计：整除余数归平台，全行合计恒等于基数
                amount = amountFen - assigned;
                rateSnap = "REMAINDER";
            }
            else {
                int rate = effectiveRate(productLine, type, orderCreateTime);
                amount = amountFen * rate / 10_000;
                rateSnap = String.valueOf(rate);
            }
            assigned += (type == SettlementEnum.ReceiverType.PLATFORM) ? 0 : amount;
            if (amount < 0) {
                // 配置比例合计超 100% 属配置错误：fail-closed 拒绝完成动作，绝不产生负数平台行
                throw new JbkException("分账比例配置合计超出 100%，请检查 " + productLine.getDesc() + " 线配置");
            }
            rows.add(new WsSplitRecord()
                    .setOrderId(orderId)
                    .setReceiverType(type.getValue())
                    .setReceiverUserId(entry.getValue())
                    .setSplitAmount(amount)
                    .setSplitRateSnap(rateSnap)
                    .setSplitStatus(SettlementEnum.SplitStatus.PENDING.getValue())
                    .setSplitRemark(orderNo));
        }
        for (WsSplitRecord row : rows) {
            try {
                save(row);
            }
            catch (DuplicateKeyException e) {
                // uk_split_order_receiver 撞键=同单重放（完成动作幂等路径），既有行为准
                log.info("分账行已存在，幂等跳过：order={} receiver={}", orderId, row.getReceiverType());
            }
        }
    }

    @Override
    public void enqueueForDeliveryOrder(Long orderId, String orderNo, long waterFen, long deliveryFeeFen,
                                        Long ownerUserId, Long courierUserId, String orderCreateTime) {
        long total = waterFen + deliveryFeeFen;
        if (orderId == null || total <= 0) {
            return;
        }
        if (waterFen < 0 || deliveryFeeFen < 0) {
            throw new JbkException("配送分账基数不能为负：水费 " + waterFen + " 配送费 " + deliveryFeeFen);
        }
        if (v2Enabled && v2EnqueueDelivery(orderId, orderNo, waterFen, deliveryFeeFen,
                ownerUserId, courierUserId, orderCreateTime)) {
            return;
        }
        // D-419：水费按 WATER 线、配送费按 DELIVERY 线各自取比例。逐线校验非平台比例
        // 合计 ≤100%——整单口径的负数守卫会被另一条线稀释掩盖单线超配，必须分线各验。
        int waterOwnerRate = effectiveRate(SettlementEnum.ProductLine.WATER,
                SettlementEnum.ReceiverType.OWNER, orderCreateTime);
        int deliveryOwnerRate = effectiveRate(SettlementEnum.ProductLine.DELIVERY,
                SettlementEnum.ReceiverType.OWNER, orderCreateTime);
        int deliveryCourierRate = effectiveRate(SettlementEnum.ProductLine.DELIVERY,
                SettlementEnum.ReceiverType.COURIER, orderCreateTime);
        if (waterOwnerRate > 10_000) {
            throw new JbkException("分账比例配置合计超出 100%，请检查售水线配置");
        }
        if (deliveryOwnerRate + deliveryCourierRate > 10_000) {
            throw new JbkException("分账比例配置合计超出 100%，请检查配送线配置");
        }

        long assigned = 0;
        List<WsSplitRecord> rows = new ArrayList<>();
        if (ObjectUtil.isNotNull(ownerUserId)) {
            // 机主：水费线份额 + 配送费线份额（各自基数 × 各自比例，万分比向下取整）
            long ownerAmount = waterFen * waterOwnerRate / 10_000
                    + deliveryFeeFen * deliveryOwnerRate / 10_000;
            if (ownerAmount < 0) {
                // 行级守卫与 legacy 路径对称：负比例只能来自绕过接口闸的脏配置，fail-closed
                throw new JbkException("分账比例配置非法（负值），请检查配送/售水线配置");
            }
            assigned += ownerAmount;
            rows.add(new WsSplitRecord()
                    .setOrderId(orderId)
                    .setReceiverType(SettlementEnum.ReceiverType.OWNER.getValue())
                    .setReceiverUserId(ownerUserId)
                    .setSplitAmount(ownerAmount)
                    .setSplitRateSnap("W" + waterOwnerRate + "+D" + deliveryOwnerRate)
                    .setSplitStatus(SettlementEnum.SplitStatus.PENDING.getValue())
                    .setSplitRemark(orderNo));
        }
        if (ObjectUtil.isNotNull(courierUserId)) {
            // 配送员：只在配送费线有份额（水费线无配送员角色配置语义）
            long courierAmount = deliveryFeeFen * deliveryCourierRate / 10_000;
            if (courierAmount < 0) {
                throw new JbkException("分账比例配置非法（负值），请检查配送线配置");
            }
            assigned += courierAmount;
            rows.add(new WsSplitRecord()
                    .setOrderId(orderId)
                    .setReceiverType(SettlementEnum.ReceiverType.COURIER.getValue())
                    .setReceiverUserId(courierUserId)
                    .setSplitAmount(courierAmount)
                    .setSplitRateSnap("D" + deliveryCourierRate)
                    .setSplitStatus(SettlementEnum.SplitStatus.PENDING.getValue())
                    .setSplitRemark(orderNo));
        }
        long platformAmount = total - assigned;
        if (platformAmount < 0) {
            // 逐线校验已挡住超配；仍留住这道整单防线（两线守卫被改错时的最后一闸）
            throw new JbkException("分账金额拆分异常：合计超出整单，拒绝完成动作");
        }
        rows.add(new WsSplitRecord()
                .setOrderId(orderId)
                .setReceiverType(SettlementEnum.ReceiverType.PLATFORM.getValue())
                .setReceiverUserId(0L)
                .setSplitAmount(platformAmount)
                .setSplitRateSnap("REMAINDER")
                .setSplitStatus(SettlementEnum.SplitStatus.PENDING.getValue())
                .setSplitRemark(orderNo));
        for (WsSplitRecord row : rows) {
            try {
                save(row);
            }
            catch (DuplicateKeyException e) {
                log.info("分账行已存在，幂等跳过：order={} receiver={}", orderId, row.getReceiverType());
            }
        }
    }

    @Override
    public String settleableCreateTimeThreshold() {
        // 冻结期阈值：CREATE_TIME ≤ 该串才可结算。业务时间为 yyyyMMddHHmmss 字典序即时间序。
        return java.time.LocalDateTime.now().minusDays(Math.max(0, freezeDays))
                .format(com.jbk.tool.utils.DateUtils.COMPACT_FORMATTER);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean settleOne(Long splitId) {
        if (splitId == null) {
            return false;
        }
        // 数据库事实闭合（D-421 R1）：锁定读当前行，冻结判定与入账事实全取锁内 DB 行。
        // 旧签名收整行对象=信任调用方，伪造 createTime=null/旧值即可穿透冻结期（复验实测
        // 探针穿透）；收窄为 ID 后时间/金额/收款人在类型系统层面即不可伪造。
        WsSplitRecord row = baseMapper.selectByIdForUpdate(splitId);
        if (row == null) {
            // 行不存在/已逻辑删：fail-closed 让行，绝不按「可结算」处理
            return false;
        }
        if (ObjectUtil.notEqual(row.getSplitStatus(), SettlementEnum.SplitStatus.PENDING.getValue())) {
            return false;
        }
        // 冻结期守卫：CREATE_TIME 必须为严格 yyyyMMddHHmmss（与发卡口径同一谓词源）且
        // 不晚于阈值。缺失/非法一律按「未满冻结期」拒绝（fail-closed）——时间事实不可信时
        // 放行=冻结期形同虚设；修数后下轮自然恢复
        if (!CardEligibility.isStrictBizTime(row.getCreateTime())
                || row.getCreateTime().compareTo(settleableCreateTimeThreshold()) > 0) {
            return false;
        }
        // 前态 CAS：FOR UPDATE 在手仍保留精确前态+影响行数校验（上方守卫被改错时的最后一闸）
        boolean won = update(Wrappers.lambdaUpdate(WsSplitRecord.class)
                .eq(WsSplitRecord::getId, row.getId())
                .eq(WsSplitRecord::getSplitStatus, SettlementEnum.SplitStatus.PENDING.getValue())
                .set(WsSplitRecord::getSplitStatus, SettlementEnum.SplitStatus.DONE.getValue())
                .set(WsSplitRecord::getSplitTime, DateUtils.time()));
        if (!won) {
            return false;
        }
        // 平台行不入个人钱包（RECEIVER_USER_ID=0 哨兵）；机主/配送员行同事务入账，
        // 入账幂等键 INCOME:<splitId> 兜底「推进成功但重放」的边界。
        // D-420：分线行冻结期内被水费退款部分冲减后（REVERSED_AMOUNT>0、行保持待分账），
        // 入账按净额=SPLIT_AMOUNT−REVERSED_AMOUNT——配送费线份额照常入账，水费线份额已回退
        long reversed = row.getReversedAmount() == null ? 0L : row.getReversedAmount();
        long netAmount = row.getSplitAmount() - reversed;
        if (row.getReceiverUserId() != null && row.getReceiverUserId() != 0L && netAmount > 0) {
            incomeService.creditFromSplit(row.getId(), row.getReceiverUserId(),
                    netAmount, row.getSplitRemark());
        }
        return true;
    }

    @Override
    public Long resolveWaterOwner(Long deviceId, Long stationId) {
        if (ObjectUtil.isNotNull(deviceId)) {
            var device = deviceMapper.selectById(deviceId);
            if (ObjectUtil.isNotNull(device) && ObjectUtil.isNotNull(device.getOwnerUserId())) {
                return device.getOwnerUserId();
            }
        }
        if (ObjectUtil.isNotNull(stationId)) {
            var station = stationMapper.selectById(stationId);
            if (ObjectUtil.isNotNull(station)) {
                return station.getOwnerUserId();
            }
        }
        return null;
    }

    // ==================== 分润 V2（D-412/D-428 六方整版计划） ====================

    /**
     * 售水单 V2 路径。返回 false = 该时点无生效计划，调用方回落 V1。
     * 与 V1 同形：加入完成事务、撞唯一键幂等跳过、行合计恒等于基数。
     */
    private boolean v2EnqueueWater(Long orderId, String orderNo, long amountFen,
                                   Long ownerUserId, String orderCreateTime) {
        Optional<SplitPlanSnapshot> plan = planService.activePlanAt(orderCreateTime);
        if (plan.isEmpty()) {
            return false;
        }
        List<SplitComponentDraft> comps = SplitPlanCalculator.calculate(plan.get(),
                buildInput(SplitV2Enum.BasisLine.WATER_SALE, amountFen, ownerUserId, null,
                        orderNo, orderCreateTime));
        persistV2(orderId, orderNo, comps, false);
        return true;
    }

    /**
     * 配送单 V2 路径：水费/配送费两线各自调用计算器（M3 绝不合并），行层按收款人归并。
     * V2 口径下机主不参与配送费线（计划模型 M5 强制）——与 V1 的机主双线份额是刻意差异。
     */
    private boolean v2EnqueueDelivery(Long orderId, String orderNo, long waterFen, long deliveryFeeFen,
                                      Long ownerUserId, Long courierUserId, String orderCreateTime) {
        Optional<SplitPlanSnapshot> plan = planService.activePlanAt(orderCreateTime);
        if (plan.isEmpty()) {
            return false;
        }
        List<SplitComponentDraft> comps = new ArrayList<>();
        comps.addAll(SplitPlanCalculator.calculate(plan.get(),
                buildInput(SplitV2Enum.BasisLine.WATER_SALE, waterFen, ownerUserId, null,
                        orderNo, orderCreateTime)));
        comps.addAll(SplitPlanCalculator.calculate(plan.get(),
                buildInput(SplitV2Enum.BasisLine.DELIVERY_FEE, deliveryFeeFen, ownerUserId, courierUserId,
                        orderNo, orderCreateTime)));
        persistV2(orderId, orderNo, comps, true);
        return true;
    }

    /** 归属载体供数（D-404/D-406/D-407）：推荐人与区域链按机主查冻结记录，无行=公域未分配。 */
    private SplitCalcInput buildInput(SplitV2Enum.BasisLine line, long basisFen, Long ownerUserId,
                                      Long courierUserId, String orderNo, String orderCreateTime) {
        Long referrer = null;
        List<SplitCalcInput.RegionNode> chain = List.of();
        SplitV2Enum.AttributionSource source = SplitV2Enum.AttributionSource.PUBLIC_UNASSIGNED;
        if (ownerUserId != null) {
            referrer = attributionService.referrerOf(ownerUserId)
                    .map(r -> r.getReferrerUserId()).orElse(null);
            Optional<WsOwnerAttribution> attribution = attributionService.attributionOf(ownerUserId);
            if (attribution.isPresent()) {
                WsOwnerAttribution a = attribution.get();
                source = SplitV2Enum.AttributionSource.valueOf(a.getAttributionSource());
                List<SplitCalcInput.RegionNode> nodes = new ArrayList<>();
                if (a.getProvinceAgentUserId() != null) {
                    nodes.add(new SplitCalcInput.RegionNode(SplitV2Enum.RegionLevel.PROVINCE,
                            a.getProvinceAgentUserId()));
                }
                if (a.getCityAgentUserId() != null) {
                    nodes.add(new SplitCalcInput.RegionNode(SplitV2Enum.RegionLevel.CITY,
                            a.getCityAgentUserId()));
                }
                if (a.getCountyAgentUserId() != null) {
                    nodes.add(new SplitCalcInput.RegionNode(SplitV2Enum.RegionLevel.COUNTY,
                            a.getCountyAgentUserId()));
                }
                chain = nodes;
            }
            else if (referrer != null) {
                // 有推荐关系无区域链：血缘归属成立，推荐人照付，区域份额自然落平台
                source = SplitV2Enum.AttributionSource.PRIVATE_REFERRAL;
            }
        }
        return new SplitCalcInput(line, basisFen, ownerUserId, referrer, chain, source,
                courierUserId, orderNo, orderCreateTime);
    }

    /**
     * 组件 → 分账行 + 组件证据，同事务落库。
     *
     * <p>行层快照形态与 V1/冲减链逐字兼容：取水单非平台行记纯数字比例（冲减按
     * 「金额即份额」路径，不解析）；配送分线单记 W/D 前缀（冲减按 waterBase×比例
     * 重算份额，与组件派发算式同源同舍入）；平台行恒 REMAINDER 且恒存在——
     * 冲减链的平台三元组判据要求恰一行，余数为 0 也补零值平台行。</p>
     *
     * <p>按 (收款方类型, 收款人) 归并：归属链录入已保证三级互不相同，正常不会撞；
     * 防御性归并兜住脏数据，比例快照取各组件比例之和（与金额同源，无取整漂移）。</p>
     */
    private void persistV2(Long orderId, String orderNo, List<SplitComponentDraft> comps,
                           boolean lineSplitOrder) {
        long total = 0;
        Map<String, WsSplitRecord> merged = new LinkedHashMap<>();
        long platformAmount = 0;
        for (SplitComponentDraft d : comps) {
            total = Math.addExact(total, d.splitAmountFen());
            if (d.roleCode() == SplitV2Enum.RoleCode.PLATFORM_REMAINDER) {
                platformAmount = Math.addExact(platformAmount, d.splitAmountFen());
                continue;
            }
            SettlementEnum.ReceiverType type = receiverTypeOf(d.roleCode());
            String key = type.getValue() + ":" + d.receiverUserId();
            WsSplitRecord row = merged.get(key);
            if (row == null) {
                merged.put(key, new WsSplitRecord()
                        .setOrderId(orderId)
                        .setReceiverType(type.getValue())
                        .setReceiverUserId(d.receiverUserId())
                        .setSplitAmount(d.splitAmountFen())
                        .setSplitRateSnap(rateSnapOf(d, lineSplitOrder))
                        .setSplitStatus(SettlementEnum.SplitStatus.PENDING.getValue())
                        .setSplitRemark(orderNo));
            }
            else {
                log.warn("V2 同收款人多组件归并（归属链脏数据？）：order={} key={}", orderId, key);
                row.setSplitAmount(Math.addExact(row.getSplitAmount(), d.splitAmountFen()));
                row.setSplitRateSnap(mergeSnap(row.getSplitRateSnap(), d, lineSplitOrder));
            }
        }
        List<WsSplitRecord> rows = new ArrayList<>(merged.values());
        // 平台行恒存在（冲减三元组判据），余数为 0 也落零值行
        rows.add(new WsSplitRecord()
                .setOrderId(orderId)
                .setReceiverType(SettlementEnum.ReceiverType.PLATFORM.getValue())
                .setReceiverUserId(0L)
                .setSplitAmount(platformAmount)
                .setSplitRateSnap("REMAINDER")
                .setSplitStatus(SettlementEnum.SplitStatus.PENDING.getValue())
                .setSplitRemark(orderNo));
        for (WsSplitRecord row : rows) {
            try {
                save(row);
            }
            catch (DuplicateKeyException e) {
                log.info("分账行已存在，幂等跳过：order={} receiver={}", orderId, row.getReceiverType());
            }
        }
        for (SplitComponentDraft d : comps) {
            WsSplitComponent evidence = new WsSplitComponent()
                    .setOrderId(orderId)
                    .setOrderNo(orderNo)
                    .setProductLine(d.line().name())
                    .setBasisAmount(d.basisAmountFen())
                    .setRoleCode(d.roleCode().name())
                    .setReceiverUserId(d.receiverUserId())
                    .setEffectiveRate(d.effectiveRateBp())
                    .setSplitAmount(d.splitAmountFen())
                    .setPlanVersion(d.planVersion())
                    .setAttributionSource(d.attributionSource().name())
                    .setComponentKey(d.componentKey());
            try {
                componentMapper.insert(evidence);
            }
            catch (DuplicateKeyException e) {
                log.info("分润组件已存在，幂等跳过：key={}", d.componentKey());
            }
        }
    }

    /** V2 角色 → V1 收款方类型（1377）：预留位 5/6 自此被执行面真实消费。 */
    private static SettlementEnum.ReceiverType receiverTypeOf(SplitV2Enum.RoleCode role) {
        return switch (role) {
            case WATER_OWNER -> SettlementEnum.ReceiverType.OWNER;
            case DELIVERY_COURIER -> SettlementEnum.ReceiverType.COURIER;
            case WATER_DIRECT_REFERRER -> SettlementEnum.ReceiverType.REFERRER;
            case REGION_PROVINCE, REGION_CITY, REGION_COUNTY -> SettlementEnum.ReceiverType.REGION;
            case PLATFORM_REMAINDER -> SettlementEnum.ReceiverType.PLATFORM;
        };
    }

    private static String rateSnapOf(SplitComponentDraft d, boolean lineSplitOrder) {
        if (!lineSplitOrder) {
            return String.valueOf(d.effectiveRateBp());
        }
        return (d.line() == SplitV2Enum.BasisLine.DELIVERY_FEE ? "D" : "W") + d.effectiveRateBp();
    }

    private static String mergeSnap(String existing, SplitComponentDraft d, boolean lineSplitOrder) {
        if (!lineSplitOrder) {
            int prev = Integer.parseInt(existing);
            return String.valueOf(prev + d.effectiveRateBp());
        }
        int prev = Integer.parseInt(existing.substring(1));
        return existing.charAt(0) + String.valueOf(prev + d.effectiveRateBp());
    }

    /**
     * 比例版本选取：EFFECT_TIME <= 订单创建时点的最新版本（varchar14 字符串序即时间序）。
     * 无配置=该收款方不参与本单分账（返回 0，不报错——收款方集合由配置驱动收缩）。
     */
    private int effectiveRate(SettlementEnum.ProductLine line, SettlementEnum.ReceiverType type,
                              String orderCreateTime) {
        WsSplitConfig config = configMapper.selectOne(Wrappers.lambdaQuery(WsSplitConfig.class)
                .eq(WsSplitConfig::getProductLine, line.getValue())
                .eq(WsSplitConfig::getReceiverType, type.getValue())
                .le(WsSplitConfig::getEffectTime, orderCreateTime)
                .orderByDesc(WsSplitConfig::getEffectTime)
                .last("LIMIT 1"));
        return ObjectUtil.isNull(config) ? 0 : config.getSplitRate();
    }
}
