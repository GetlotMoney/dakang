package com.jbk.serve.service.settlement.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jbk.serve.mapper.device.WsDeviceMapper;
import com.jbk.serve.mapper.settlement.WsSplitConfigMapper;
import com.jbk.serve.mapper.settlement.WsSplitRecordMapper;
import com.jbk.serve.mapper.station.WsStationMapper;
import com.jbk.serve.service.mini.card.CardEligibility;
import com.jbk.serve.service.settlement.IIncomeService;
import com.jbk.serve.service.settlement.ISplitService;
import com.jbk.tool.consts.settlement.SettlementEnum;
import com.jbk.tool.data.settlement.po.WsSplitConfig;
import com.jbk.tool.data.settlement.po.WsSplitRecord;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    @Autowired
    private WsSplitConfigMapper configMapper;
    @Autowired
    private IIncomeService incomeService;
    @Autowired
    private WsDeviceMapper deviceMapper;
    @Autowired
    private WsStationMapper stationMapper;

    @Override
    public void enqueueForOrder(Long orderId, String orderNo, long amountFen,
                                SettlementEnum.ProductLine productLine,
                                Long ownerUserId, Long courierUserId, String orderCreateTime) {
        if (orderId == null || amountFen <= 0 || productLine == null) {
            // 零元/异常单不产分账：静默返回而非报错——完成动作不因分账口径边界失败
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
