package com.jbk.serve.service.delivery.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.delivery.WsDeliveryAutoRuleMapper;
import com.jbk.serve.mapper.delivery.WsDeliveryTaskMapper;
import com.jbk.serve.mapper.product.WsWaterTypeMapper;
import com.jbk.serve.mapper.station.WsStationMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.service.delivery.DeliveryClock;
import com.jbk.serve.service.delivery.DeliveryOrderNo;
import com.jbk.serve.service.delivery.DeliveryPricing;
import com.jbk.serve.service.delivery.IDeliveryOrderService;
import com.jbk.serve.service.delivery.IDeliveryOrderTxService;
import com.jbk.serve.service.mini.recharge.RechargeOrderNo;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.delivery.DeliveryEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.data.delivery.bo.DeliveryCreateBo;
import com.jbk.tool.data.delivery.po.WsDeliveryAutoRule;
import com.jbk.tool.data.delivery.po.WsDeliveryTask;
import com.jbk.tool.data.product.po.WsWaterType;
import com.jbk.tool.data.station.po.WsStation;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 配送创单编排实现。
 *
 * <p>幂等结构（规则3/4）：订单号由 userId+requestId 确定性派生 → 预检既有单直接返回；
 * 并发穿透预检时，资金事务撞任一唯一键（订单号/流水键/任务键/规则键）整体回滚，
 * 编排层捕获 DuplicateKey 后重读既有单核验归属再返回——任何路径都不会双扣款双建任务。</p>
 *
 * @author dakang
 * @since 2026-07-23
 */
@Service
public class DeliveryOrderServiceImpl implements IDeliveryOrderService {

    /** 配送方式：1即时 2预约 3自动补货（Bo 契约值，未注册字典——是接口入参形态而非落库状态）。 */
    private static final int MODE_IMMEDIATE = 1;
    private static final int MODE_SCHEDULED = 2;
    private static final int MODE_AUTO_REFILL = 3;
    private static final Pattern PHONE = Pattern.compile("^1\\d{10}$");
    private static final int AUTO_REFILL_MIN_DAYS = 3;
    private static final int AUTO_REFILL_MAX_DAYS = 90;

    @Autowired
    private WsOrderMapper orderMapper;
    @Autowired
    private WsDeliveryTaskMapper taskMapper;
    @Autowired
    private WsStationMapper stationMapper;
    @Autowired
    private WsWaterTypeMapper waterTypeMapper;
    @Autowired
    private WsDeliveryAutoRuleMapper autoRuleMapper;
    @Autowired
    private IDeliveryOrderTxService orderTxService;
    @Autowired
    private IWsDomainEventService domainEventService;

    @Override
    public CreatedDelivery createDeliveryOrder(DeliveryCreateBo bo, Long userId) {
        String requestId = RechargeOrderNo.requireCanonicalUuid(StrUtil.trim(bo.getRequestId()));
        String now = DateUtils.time();
        validateModeStructure(bo);
        validateReceiver(bo.getReceiveAddress(), bo.getReceivePhone());
        long cardId = decimalId(bo.getCardId(), "cardId");
        long stationId = decimalId(bo.getStationId(), "stationId");
        long waterTypeId = decimalId(bo.getWaterTypeId(), "waterTypeId");

        String orderNo = DeliveryOrderNo.derive(userId, requestId);
        // 幂等预检：同号订单已存在 → 核验归属/类型/冻结参数后返回既有订单+任务（不重复扣款）。
        // 必须先于预约时效校验：同参重放是回看历史动作，预约时间早已过去是常态，
        // 拿「新建单时效」拦重放会把合法幂等重试打成 540（预约时间必须晚于当前时间）。
        WsOrder existing = orderMapper.selectOne(Wrappers.lambdaQuery(WsOrder.class)
                .eq(WsOrder::getOrderNo, orderNo));
        if (ObjectUtil.isNotNull(existing)) {
            return verifyIdempotentHit(existing, userId, requestId, bo, cardId, stationId, waterTypeId);
        }

        // 确认无历史订单、真正新建时才校验预约时效（与当前时间比较仅对新单有意义）
        requireScheduledInFuture(bo, now);

        // 档案校验：水站营业中、水种启用（下单时点档案态；停用 fail-closed）
        WsStation station = stationMapper.selectById(stationId);
        if (ObjectUtil.isNull(station) || ObjectUtil.notEqual(station.getStationStatus(), 1)) {
            throw new JbkException("该水站检修或暂停营业中，暂不支持配送下单");
        }
        WsWaterType waterType = waterTypeMapper.selectById(waterTypeId);
        if (ObjectUtil.isNull(waterType) || ObjectUtil.notEqual(waterType.getWaterStatus(), 1)) {
            throw new JbkException("水种无效或已停用");
        }

        DeliveryPricing.Quote quote = DeliveryPricing.quote(bo.getContainerSpec(), bo.getDeliveryCount());
        int planReturn = DeliveryPricing.requireReturnCount(bo.getPlanReturnCount());

        WsDeliveryAutoRule autoRule = null;
        if (bo.getDeliveryMode() == MODE_AUTO_REFILL) {
            autoRule = buildAutoRule(bo, userId, cardId, stationId, waterTypeId, planReturn, requestId, now);
        }
        String scheduledTime = bo.getDeliveryMode() == MODE_SCHEDULED ? bo.getScheduledTime() : null;

        WsOrder order = buildOrder(orderNo, userId, cardId, stationId, quote, now,
                buildSnap(requestId, bo, quote, waterTypeId, scheduledTime));
        WsDeliveryTask task = buildTask(orderNo, userId, stationId, waterType, bo.getContainerSpec(),
                bo.getDeliveryCount(), planReturn, quote, bo.getReceiveAddress(), bo.getReceivePhone(),
                scheduledTime, now);
        try {
            WsOrder saved = orderTxService.createPaidDeliveryOrder(order, task, autoRule, now);
            return new CreatedDelivery(saved, task);
        } catch (DuplicateKeyException e) {
            // 并发同请求穿透预检：事务已整体回滚（扣减一并撤销），按幂等命中处理
            WsOrder concurrent = orderMapper.selectOne(Wrappers.lambdaQuery(WsOrder.class)
                    .eq(WsOrder::getOrderNo, orderNo));
            if (ObjectUtil.isNull(concurrent)) {
                throw new JbkException("配送下单冲突，请重试");
            }
            return verifyIdempotentHit(concurrent, userId, requestId, bo, cardId, stationId, waterTypeId);
        }
    }

    @Override
    public int generateAutoRefillDueOrders(String now) {
        DeliveryClock.requireTime(now, "当前时间");
        List<WsDeliveryAutoRule> rules = autoRuleMapper.selectList(Wrappers.lambdaQuery(WsDeliveryAutoRule.class)
                .eq(WsDeliveryAutoRule::getRuleStatus, DeliveryEnum.AutoRuleStatus.ENABLED.getValue()));
        int generated = 0;
        for (WsDeliveryAutoRule rule : rules) {
            long period = DeliveryClock.periodIndex(rule.getAnchorTime(), now, rule.getIntervalDays());
            if (period < 1) {
                // 第0期是创建规则时的首单，不在生成范围
                continue;
            }
            // 只补当前到期期序（跳过久停机积压的历史期）：一次性连环生成会连环扣款，
            // 用户停机三个月回来被扣三期钱比漏一期更不可接受。
            String orderNo = DeliveryOrderNo.deriveAutoRefill(rule.getUserId(), rule.getId(), period);
            boolean exists = orderMapper.selectCount(Wrappers.lambdaQuery(WsOrder.class)
                    .eq(WsOrder::getOrderNo, orderNo)) > 0;
            if (exists) {
                continue;
            }
            try {
                generateOne(rule, orderNo, period, now);
                generated++;
            } catch (DuplicateKeyException e) {
                // 并发生成：本期已被另一实例生成，幂等跳过
            } catch (JbkException e) {
                // 卡不足/档案停用等业务失败：可靠留痕后继续其他规则，不让单条规则阻塞全局
                domainEventService.recordReliable(OpsEnum.EventType.ORDER_STATUS, orderNo, null,
                        "自动补货第" + period + "期创单失败（规则" + rule.getId() + "）：" + e.getMsg());
            }
        }
        return generated;
    }

    private void generateOne(WsDeliveryAutoRule rule, String orderNo, long period, String now) {
        WsStation station = stationMapper.selectById(rule.getStationId());
        if (ObjectUtil.isNull(station) || ObjectUtil.notEqual(station.getStationStatus(), 1)) {
            throw new JbkException("水站停业，本期不生成");
        }
        WsWaterType waterType = waterTypeMapper.selectById(rule.getWaterTypeId());
        if (ObjectUtil.isNull(waterType) || ObjectUtil.notEqual(waterType.getWaterStatus(), 1)) {
            throw new JbkException("水种停用，本期不生成");
        }
        DeliveryPricing.Quote quote = DeliveryPricing.quote(rule.getContainerSpec(), rule.getDeliveryCount());
        JSONObject snap = JSONUtil.createObj()
                .set("autoRuleId", rule.getId())
                .set("periodIndex", period)
                .set("containerSpec", rule.getContainerSpec())
                .set("deliveryCount", rule.getDeliveryCount())
                .set("planReturnCount", rule.getPlanReturnCount())
                .set("waterTypeId", rule.getWaterTypeId())
                .set("waterAmountFen", quote.waterAmountFen())
                .set("deliveryFeeFen", quote.deliveryFeeFen())
                .set("deliveryMode", MODE_AUTO_REFILL);
        WsOrder order = buildOrder(orderNo, rule.getUserId(), rule.getCardId(), rule.getStationId(),
                quote, now, snap.toString());
        WsDeliveryTask task = buildTask(orderNo, rule.getUserId(), rule.getStationId(), waterType,
                rule.getContainerSpec(), rule.getDeliveryCount(), rule.getPlanReturnCount(), quote,
                rule.getReceiveAddress(), rule.getReceivePhone(), null, now);
        orderTxService.createPaidDeliveryOrder(order, task, null, now);
    }

    // ==================== 装配与校验 ====================

    /**
     * 参数结构校验（可安全用于任何时点，包括历史重放）：只看形态不看时钟——
     * 与当前时间比较的预约时效属于「仅新建单」语义，放 {@link #requireScheduledInFuture}。
     */
    private void validateModeStructure(DeliveryCreateBo bo) {
        Integer mode = bo.getDeliveryMode();
        if (ObjectUtil.isNull(mode)
                || (mode != MODE_IMMEDIATE && mode != MODE_SCHEDULED && mode != MODE_AUTO_REFILL)) {
            throw new JbkException("配送方式不合法");
        }
        if (mode == MODE_SCHEDULED) {
            String scheduled = bo.getScheduledTime();
            if (StrUtil.isBlank(scheduled)) {
                throw new JbkException("预约配送必须选择预约时间");
            }
            DeliveryClock.requireTime(scheduled, "预约时间");
        }
        if (mode == MODE_AUTO_REFILL) {
            Integer interval = bo.getAutoRefillIntervalDays();
            if (ObjectUtil.isNull(interval) || interval < AUTO_REFILL_MIN_DAYS || interval > AUTO_REFILL_MAX_DAYS) {
                throw new JbkException("自动补货需配置 3~90 天的固定周期");
            }
        }
    }

    /** 新建单专属时效闸：预约必须晚于当前时间。只能在幂等预检未命中之后调用（重放不适用）。 */
    private void requireScheduledInFuture(DeliveryCreateBo bo, String now) {
        if (ObjectUtil.equal(bo.getDeliveryMode(), MODE_SCHEDULED)
                && bo.getScheduledTime().compareTo(now) <= 0) {
            throw new JbkException("预约时间必须晚于当前时间");
        }
    }

    /** 地址/电话输入校验（无地址簿域，包A 冻结为快照式输入；电话入库原文供配送联系，展示必须脱敏）。 */
    private void validateReceiver(String address, String phone) {
        if (StrUtil.isBlank(address) || address.length() > 200) {
            throw new JbkException("收水地址不合法");
        }
        if (phone == null || !PHONE.matcher(phone).matches()) {
            throw new JbkException("收货电话格式不合法");
        }
    }

    /**
     * 幂等命中核验（E2E-03 验收 P1-1，写法与文案口径对齐 MiniRechargeServiceImpl#verifyIdempotent）：
     * 订单必须属于当前会话用户且确为配送单，任务必须存在（一单一任务），且本次重放的
     * 关键参数必须与创单时冻结的服务端快照逐字段一致——同 requestId 是同一次购买动作，
     * 改数量/地址/水种等任何关键参数都是冲突而不是重放，拒绝且零副作用（不扣款不建单）。
     *
     * <p>冻结位置复用既有列，不造第二套快照格式：水种/数量/回收数/配送方式/预约时间/
     * 自动补货周期/水费/配送费在创单 PACKAGE_SNAP（见 {@link #buildSnap}），
     * 地址/电话在任务行 RECEIVE_ADDRESS/RECEIVE_PHONE，水站/水卡在订单行列。</p>
     */
    private CreatedDelivery verifyIdempotentHit(WsOrder existing, Long userId, String requestId,
                                                DeliveryCreateBo bo, long cardId, long stationId,
                                                long waterTypeId) {
        if (ObjectUtil.notEqual(existing.getUserId(), userId)
                || ObjectUtil.notEqual(existing.getOrderType(), TradeEnum.OrderType.DELIVERY.getValue())) {
            // 号撞但归属/类型不符：不同请求派生出同号（理论上不可能）或跨用户重放，留痕拒绝
            domainEventService.record(OpsEnum.EventType.ORDER_STATUS, existing.getOrderNo(), null,
                    "配送订单号幂等核验不通过：requestId=" + requestId + " loginUser=" + userId);
            throw new JbkException("请求标识冲突，请更换 requestId 重试");
        }
        WsDeliveryTask task = taskMapper.selectOne(Wrappers.lambdaQuery(WsDeliveryTask.class)
                .eq(WsDeliveryTask::getOrderId, existing.getId()));
        if (ObjectUtil.isNull(task)) {
            // 订单在而任务不在：创建是同事务的，出现此态说明数据被外力破坏，fail-closed
            throw new JbkException("配送订单数据异常，请联系客服");
        }
        JSONObject snap;
        try {
            snap = JSONUtil.parseObj(existing.getPackageSnap());
        } catch (Exception e) {
            // 快照缺失/不可解析：无从核验冻结参数，宁可拒绝也不当幂等成功
            throw new JbkException("配送订单数据异常，请联系客服");
        }
        if (!requestId.equals(snap.getStr("requestId"))) {
            throw new JbkException("订单快照与 requestId 不一致，拒绝");
        }
        // 逐字段核验冻结参数：任一不一致按参数冲突拒绝（零副作用，无任何写入）
        String frozenScheduled = snap.getStr("scheduledTime");
        String replayScheduled = ObjectUtil.equal(bo.getDeliveryMode(), MODE_SCHEDULED)
                ? bo.getScheduledTime() : null;
        Integer frozenInterval = snap.getInt("autoRefillIntervalDays");
        Integer replayInterval = ObjectUtil.equal(bo.getDeliveryMode(), MODE_AUTO_REFILL)
                ? bo.getAutoRefillIntervalDays() : null;
        boolean conflict = ObjectUtil.notEqual(existing.getCardId(), cardId)
                || ObjectUtil.notEqual(existing.getStationId(), stationId)
                || ObjectUtil.notEqual(snap.getLong("waterTypeId"), waterTypeId)
                || ObjectUtil.notEqual(snap.getStr("containerSpec"), bo.getContainerSpec())
                || ObjectUtil.notEqual(snap.getInt("deliveryCount"), bo.getDeliveryCount())
                || ObjectUtil.notEqual(snap.getInt("planReturnCount"), bo.getPlanReturnCount())
                || ObjectUtil.notEqual(snap.getInt("deliveryMode"), bo.getDeliveryMode())
                || ObjectUtil.notEqual(frozenScheduled, replayScheduled)
                || ObjectUtil.notEqual(frozenInterval, replayInterval)
                || ObjectUtil.notEqual(task.getReceiveAddress(), bo.getReceiveAddress())
                || ObjectUtil.notEqual(task.getReceivePhone(), bo.getReceivePhone());
        if (conflict) {
            throw new JbkException("同一 requestId 不可更换配送参数");
        }
        // 水费/配送费冻结自洽：快照、任务行与订单总额三方恒等（快照被改写即拒绝）
        Long snapWater = snap.getLong("waterAmountFen");
        Long snapFee = snap.getLong("deliveryFeeFen");
        boolean amountConsistent = ObjectUtil.isNotNull(snapWater) && ObjectUtil.isNotNull(snapFee)
                && ObjectUtil.equal(task.getWaterAmount(), snapWater)
                && ObjectUtil.equal(task.getDeliveryFee(), snapFee)
                && ObjectUtil.equal(existing.getOrderAmount(), snapWater + snapFee);
        if (!amountConsistent) {
            throw new JbkException("订单金额与快照不一致，拒绝");
        }
        return new CreatedDelivery(existing, task);
    }

    private WsDeliveryAutoRule buildAutoRule(DeliveryCreateBo bo, Long userId, long cardId, long stationId,
                                             long waterTypeId, int planReturn, String requestId, String now) {
        WsDeliveryAutoRule rule = new WsDeliveryAutoRule()
                .setRuleKey(SecureUtil.sha256(userId + ":" + requestId))
                .setUserId(userId)
                .setCardId(cardId)
                .setStationId(stationId)
                .setWaterTypeId(waterTypeId)
                .setContainerSpec(bo.getContainerSpec())
                .setDeliveryCount(bo.getDeliveryCount())
                .setPlanReturnCount(planReturn)
                .setReceiveAddress(bo.getReceiveAddress())
                .setReceivePhone(bo.getReceivePhone())
                .setIntervalDays(bo.getAutoRefillIntervalDays())
                .setAnchorTime(now)
                .setRuleStatus(DeliveryEnum.AutoRuleStatus.ENABLED.getValue());
        rule.setCreateTime(now);
        rule.setUpdateTime(now);
        return rule;
    }

    private WsOrder buildOrder(String orderNo, Long userId, long cardId, long stationId,
                               DeliveryPricing.Quote quote, String now, String snap) {
        WsOrder order = new WsOrder()
                .setOrderNo(orderNo)
                .setOrderType(TradeEnum.OrderType.DELIVERY.getValue())
                .setUserId(userId)
                .setStationId(stationId)
                .setCardId(cardId)
                .setPackageSnap(snap)
                .setOrderAmount(quote.totalFen())
                .setPayWay(TradeEnum.PayWay.CARD_BALANCE.getValue())
                // 规则5：卡余额即时扣款成功才建单，直接落已支付态进入待履约
                .setOrderStatus(TradeEnum.OrderStatus.PAID.getValue());
        order.setCreateTime(now);
        order.setUpdateTime(now);
        return order;
    }

    private WsDeliveryTask buildTask(String orderNo, Long userId, long stationId, WsWaterType waterType,
                                     String containerSpec, Integer deliveryCount, int planReturn,
                                     DeliveryPricing.Quote quote, String address, String phone,
                                     String scheduledTime, String now) {
        WsDeliveryTask task = new WsDeliveryTask()
                .setTaskNo(DeliveryOrderNo.deriveTaskNo(orderNo))
                .setUserId(userId)
                .setStationId(stationId)
                .setWaterTypeId(waterType.getId())
                .setWaterType(waterType.getWaterName())
                .setContainerSpec(containerSpec)
                .setDeliveryCount(deliveryCount)
                .setPlanReturnCount(planReturn)
                .setWaterAmount(quote.waterAmountFen())
                .setDeliveryFee(quote.deliveryFeeFen())
                .setReceiveAddress(address)
                .setReceivePhone(phone)
                .setTaskStatus(DeliveryEnum.TaskStatus.PENDING.getValue())
                .setVersion(1)
                .setScheduledTime(scheduledTime);
        task.setCreateTime(now);
        task.setUpdateTime(now);
        return task;
    }

    private String buildSnap(String requestId, DeliveryCreateBo bo, DeliveryPricing.Quote quote,
                             long waterTypeId, String scheduledTime) {
        JSONObject snap = JSONUtil.createObj()
                .set("requestId", requestId)
                .set("containerSpec", bo.getContainerSpec())
                .set("deliveryCount", bo.getDeliveryCount())
                .set("planReturnCount", bo.getPlanReturnCount())
                .set("waterTypeId", waterTypeId)
                .set("unitWaterPriceFen", DeliveryPricing.requireUnitWaterPrice(bo.getContainerSpec()))
                .set("deliveryFeePerContainerFen", DeliveryPricing.DELIVERY_FEE_PER_CONTAINER_FEN)
                .set("waterAmountFen", quote.waterAmountFen())
                .set("deliveryFeeFen", quote.deliveryFeeFen())
                .set("deliveryMode", bo.getDeliveryMode());
        if (StrUtil.isNotBlank(scheduledTime)) {
            snap.set("scheduledTime", scheduledTime);
        }
        if (ObjectUtil.equal(bo.getDeliveryMode(), MODE_AUTO_REFILL)) {
            snap.set("autoRefillIntervalDays", bo.getAutoRefillIntervalDays());
        }
        return snap.toString();
    }

    private long decimalId(String raw, String field) {
        if (raw == null || !raw.trim().matches("^[1-9]\\d{0,17}$")) {
            throw new JbkException(field + " 必须是正十进制字符串");
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new JbkException(field + " 越界");
        }
    }
}
