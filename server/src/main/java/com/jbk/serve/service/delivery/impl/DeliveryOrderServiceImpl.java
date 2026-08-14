package com.jbk.serve.service.delivery.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.delivery.WsDeliveryAutoRuleMapper;
import com.jbk.serve.service.settlement.IInviteService;
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
import com.jbk.serve.service.mini.notify.WechatNotifyEnqueue;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.delivery.DeliveryEnum;
import com.jbk.tool.consts.mini.WechatNotifyEnum;
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
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@Service
public class DeliveryOrderServiceImpl implements IDeliveryOrderService {

    /** 配送方式：1即时 2预约 3自动补货（Bo 契约值，未注册字典——是接口入参形态而非落库状态）。 */
    private static final int MODE_IMMEDIATE = 1;
    private static final int MODE_SCHEDULED = 2;
    private static final int MODE_AUTO_REFILL = 3;
    private static final Pattern PHONE = Pattern.compile("^1\\d{10}$");
    private static final int AUTO_REFILL_MIN_DAYS = 3;
    private static final int AUTO_REFILL_MAX_DAYS = 90;

    /** 订阅通知登记。失败路径走 enqueueIndependent，理由见调用点注释。 */
    @Autowired
    private WechatNotifyEnqueue notifyEnqueue;
    @Autowired
    private IInviteService inviteService;
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
        int payWay = normalizePayWay(bo);
        validateModeStructure(bo, payWay);
        validateReceiver(bo.getReceiveAddress(), bo.getReceivePhone());
        long cardId = decimalId(bo.getCardId(), "cardId");
        long stationId = decimalId(bo.getStationId(), "stationId");
        long waterTypeId = decimalId(bo.getWaterTypeId(), "waterTypeId");

        String orderNo = DeliveryOrderNo.derive(userId, requestId);
        // 幂等预检：同号订单已存在 → 核验冻结参数后返回既有单（不重复扣款）。必须先于预约时效
        // 校验——重放是回看历史动作，拿「新建单时效」拦重放会把合法重试打成 540
        WsOrder existing = orderMapper.selectOne(Wrappers.lambdaQuery(WsOrder.class)
                .eq(WsOrder::getOrderNo, orderNo));
        if (ObjectUtil.isNotNull(existing)) {
            return verifyIdempotentHit(existing, userId, requestId, bo, payWay, cardId, stationId, waterTypeId);
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
            requireNoAliveSameShapeRule(autoRule);
        }
        String scheduledTime = bo.getDeliveryMode() == MODE_SCHEDULED ? bo.getScheduledTime() : null;

        WsOrder order = buildOrder(orderNo, userId, cardId, stationId, quote, payWay, now,
                buildSnap(requestId, bo, quote, payWay, waterTypeId, scheduledTime));
        WsDeliveryTask task = buildTask(orderNo, userId, stationId, waterType, bo.getContainerSpec(),
                bo.getDeliveryCount(), planReturn, quote, payWay, bo.getReceiveAddress(), bo.getReceivePhone(),
                scheduledTime, now);
        try {
            WsOrder saved = orderTxService.createPaidDeliveryOrder(order, task, autoRule, now);
            return new CreatedDelivery(saved, task);
        } catch (DuplicateKeyException e) {
            // 撞同款去重键而非订单号：库层唯一索引裁出赢家，输方按业务语义拒绝，
            // 不能当幂等命中（那会把「已有同款规则」说成「这单已下过」）
            if (isSameShapeConflict(e)) {
                throw new JbkException(SAME_SHAPE_RULE_RACE_REJECT);
            }
            // 并发同请求穿透预检：事务已整体回滚（扣减一并撤销），按幂等命中处理
            WsOrder concurrent = orderMapper.selectOne(Wrappers.lambdaQuery(WsOrder.class)
                    .eq(WsOrder::getOrderNo, orderNo));
            if (ObjectUtil.isNull(concurrent)) {
                throw new JbkException("配送下单冲突，请重试");
            }
            return verifyIdempotentHit(concurrent, userId, requestId, bo, payWay, cardId, stationId, waterTypeId);
        }
    }

    @Override
    public int generateAutoRefillDueOrders(String now) {
        DeliveryClock.requireTime(now, "当前时间");
        List<WsDeliveryAutoRule> rules = autoRuleMapper.selectList(Wrappers.lambdaQuery(WsDeliveryAutoRule.class)
                .eq(WsDeliveryAutoRule::getRuleStatus, DeliveryEnum.AutoRuleStatus.ENABLED.getValue()));
        int generated = 0;
        for (WsDeliveryAutoRule rule : rules) {
            try {
                if (generateForRule(rule, now)) {
                    generated++;
                }
            }
            catch (RuntimeException isolated) {
                // 逐规则隔离（B08-S1）：任何一条规则的异常都不得阻断后续规则——
                // 一条脏规则中断循环，排在后面的用户当期都收不到水
                log.error("自动补货规则处理异常，跳过该规则 ruleId={} now={}", rule.getId(), now, isolated);
            }
        }
        return generated;
    }

    /**
     * 单条规则的当期生成。
     *
     * @return 是否真的生成了本期订单
     */
    private boolean generateForRule(WsDeliveryAutoRule rule, String now) {
        long period = DeliveryClock.periodIndex(rule.getAnchorTime(), now, rule.getIntervalDays());
        if (period < 1) {
            // 第0期是创建规则时的首单，不在生成范围
            return false;
        }
        // 只补当前到期期序（跳过久停机积压的历史期）：一次性连环生成会连环扣款，
        // 用户停机三个月回来被扣三期钱比漏一期更不可接受。
        String orderNo = DeliveryOrderNo.deriveAutoRefill(rule.getUserId(), rule.getId(), period);
        // 这只是快路径，不是正确性判据：多实例同时扫描时两边都会读到「不存在」。
        // 真正的互斥在 uk_order_no —— 输方撞唯一键，资金事务整体回滚，零订单零任务零流水。
        boolean exists = orderMapper.selectCount(Wrappers.lambdaQuery(WsOrder.class)
                .eq(WsOrder::getOrderNo, orderNo)) > 0;
        if (exists) {
            return false;
        }
        try {
            generateOne(rule, orderNo, period, now);
            return true;
        }
        catch (DuplicateKeyException e) {
            // 并发生成：本期已被另一实例生成，幂等跳过（赢方的单即本期唯一一单）
            return false;
        }
        catch (JbkException e) {
            // 业务失败留痕后继续其他规则。幂等键按「规则+期序」：无键留痕会按扫描频率刷爆事件表；
            // 键只覆盖留痕不覆盖生成，条件恢复后下一轮仍正常生成本期。
            domainEventService.recordReliableOnceIndependent(OpsEnum.EventType.ORDER_STATUS, orderNo,
                    autoRefillFailKey(rule.getId(), period), null,
                    "自动补货第" + period + "期创单失败（规则" + rule.getId() + "）：" + e.getMsg());
            // 失败通知走独立事务：将来扫描循环若被套上事务，跟随传播的登记会随回滚消失——
            // 成功通知必须随回滚消失，失败通知必须在回滚后活着，方向相反。
            notifyEnqueue.enqueueIndependent(WechatNotifyEnum.EventType.AUTO_REFILL_FAILED,
                    WechatNotifyEnum.BizObjectType.REFILL_RULE,
                    // 对象编号带期序：同一规则不同期是不同的失败，各自该通知一次
                    rule.getId() + ":" + period, rule.getUserId(),
                    JSONUtil.createObj().set("period", period).set("reason", e.getMsg()));
            return false;
        }
    }

    /** 自动补货失败证据的幂等键：同一规则同一期全库最多一条。 */
    static String autoRefillFailKey(Long ruleId, long period) {
        return "AUTO_REFILL_FAIL:" + ruleId + ":" + period;
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
        // 周期单恒走全余额（D-214 边界）：规则表无支付方式列，周期扣款口径由创建规则时的
        // 结构校验冻结为 payWay=2（见 validateModeStructure 对 3+自动补货的拒绝）。
        int payWay = TradeEnum.PayWay.CARD_BALANCE.getValue();
        JSONObject snap = JSONUtil.createObj()
                .set("autoRuleId", rule.getId())
                .set("periodIndex", period)
                .set("containerSpec", rule.getContainerSpec())
                .set("deliveryCount", rule.getDeliveryCount())
                .set("planReturnCount", rule.getPlanReturnCount())
                .set("waterTypeId", rule.getWaterTypeId())
                .set("waterAmountFen", quote.waterAmountFen())
                .set("deliveryFeeFen", quote.deliveryFeeFen())
                .set("payWay", payWay)
                .set("waterMl", quote.waterMl())
                .set("priceWaterAmountFen", quote.waterAmountFen())
                .set("deliveryMode", MODE_AUTO_REFILL);
        WsOrder order = buildOrder(orderNo, rule.getUserId(), rule.getCardId(), rule.getStationId(),
                quote, payWay, now, snap.toString());
        WsDeliveryTask task = buildTask(orderNo, rule.getUserId(), rule.getStationId(), waterType,
                rule.getContainerSpec(), rule.getDeliveryCount(), rule.getPlanReturnCount(), quote, payWay,
                rule.getReceiveAddress(), rule.getReceivePhone(), null, now);
        // S2：期次单走带规则锁定复核的事务入口——扫描快照后被取消/停用的规则在事务内 fail-closed
        orderTxService.createAutoRefillPeriodOrder(rule.getId(), order, task, now);
    }

    // ==================== 装配与校验 ====================

    /**
     * 支付方式归一化（D-214 结构校验）：null 按 2 兼容封板前无 payWay 的老调用方；
     * 白名单外一律拒绝——1（微信）不是配送资金链的合法入参。
     */
    private int normalizePayWay(DeliveryCreateBo bo) {
        Integer payWay = bo.getPayWay();
        if (ObjectUtil.isNull(payWay)) {
            return TradeEnum.PayWay.CARD_BALANCE.getValue();
        }
        if (payWay != TradeEnum.PayWay.CARD_BALANCE.getValue()
                && payWay != TradeEnum.PayWay.CARD_ML.getValue()) {
            throw new JbkException("配送支付方式不合法");
        }
        return payWay;
    }

    /**
     * 参数结构校验（可安全用于任何时点，包括历史重放）：只看形态不看时钟——
     * 与当前时间比较的预约时效属于「仅新建单」语义，放 {@link #requireScheduledInFuture}。
     */
    private void validateModeStructure(DeliveryCreateBo bo, int payWay) {
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
            // D-214：规则表无支付方式列，周期生成恒走全余额；放行首单水量抵扣会形成
            // 「首单扣水量、后续悄悄扣钱」的口径漂移，fail-closed 待扩列评审后再放开
            if (payWay == TradeEnum.PayWay.CARD_ML.getValue()) {
                throw new JbkException("自动补货暂仅支持水卡余额支付");
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
     * 幂等命中核验（E2E-03 P1-1，口径对齐 MiniRechargeServiceImpl#verifyIdempotent）：
     * 归属/类型/任务在位，且重放关键参数与创单冻结快照逐字段一致——同 requestId 换任何关键参数
     * 是冲突而非重放，拒绝且零副作用。冻结位置复用既有列（PACKAGE_SNAP/任务行/订单行），
     * 不造第二套快照格式。
     */
    private CreatedDelivery verifyIdempotentHit(WsOrder existing, Long userId, String requestId,
                                                DeliveryCreateBo bo, int payWay, long cardId, long stationId,
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
        // 支付方式属冻结参数（D-214）：旧单快照缺 payWay 按 2 对待（封板前全余额唯一口径），
        // 同 requestId 换 payWay 是换一笔资金动作，必须按冲突拒绝而不是重放
        int frozenPayWay = ObjectUtil.defaultIfNull(snap.getInt("payWay"),
                TradeEnum.PayWay.CARD_BALANCE.getValue());
        boolean conflict = ObjectUtil.notEqual(existing.getCardId(), cardId)
                || frozenPayWay != payWay
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
        // 水费/配送费冻结自洽：快照、任务行与订单总额三方恒等（快照被改写即拒绝）；
        // D-214 下 ORDER_AMOUNT = waterAmountFen + deliveryFeeFen 对两种支付方式一体成立
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

    /** 预检拒因：库里已经有一条同款规则，用户自己去取消。 */
    private static final String SAME_SHAPE_RULE_REJECT =
            "您已有一条相同水种、规格与收货地址的自动补货规则，请先在补货规则页取消后再新建";
    /**
     * 并发输方拒因，与预检拒因刻意分成两句：「你早就有一条」要去取消、「刚刚被抢先」刷新即可；
     * 两句也让两层判据各自可被用例钉住。
     */
    private static final String SAME_SHAPE_RULE_RACE_REJECT =
            "自动补货规则刚刚被创建，请刷新后查看已有规则";

    /**
     * 同款补货规则预检：只负责可读拒因；并发安全由库层唯一索引 uk_dauto_active_shape 负责——
     * 仅应用层判重时两个并发请求会各建一条，Worker 每期各扣一次款。
     */
    private void requireNoAliveSameShapeRule(WsDeliveryAutoRule candidate) {
        Long alive = autoRuleMapper.selectCount(Wrappers.lambdaQuery(WsDeliveryAutoRule.class)
                .eq(WsDeliveryAutoRule::getUserId, candidate.getUserId())
                .eq(WsDeliveryAutoRule::getWaterTypeId, candidate.getWaterTypeId())
                .eq(WsDeliveryAutoRule::getContainerSpec, candidate.getContainerSpec())
                .eq(WsDeliveryAutoRule::getReceiveAddress, candidate.getReceiveAddress())
                .in(WsDeliveryAutoRule::getRuleStatus,
                        DeliveryEnum.AutoRuleStatus.ENABLED.getValue(),
                        DeliveryEnum.AutoRuleStatus.DISABLED.getValue()));
        if (alive != null && alive > 0) {
            throw new JbkException(SAME_SHAPE_RULE_REJECT);
        }
    }

    /** 区分撞的是哪一把唯一键：订单号冲突是幂等，同款键冲突是业务拒绝。 */
    private static boolean isSameShapeConflict(DuplicateKeyException e) {
        String text = e.getMessage() == null ? "" : e.getMessage();
        return text.contains("uk_dauto_active_shape");
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
                               DeliveryPricing.Quote quote, int payWay, String now, String snap) {
        WsOrder order = new WsOrder()
                .setOrderNo(orderNo)
                .setOrderType(TradeEnum.OrderType.DELIVERY.getValue())
                .setUserId(userId)
                // E2E-08 归因快照：下单时刻的推荐人（绑定前恒 NULL，绑定不回溯）
                .setReferrerUserId(inviteService.referrerSnapshotOf(userId))
                .setStationId(stationId)
                .setCardId(cardId)
                .setPackageSnap(snap)
                // D-214 金额口径：ORDER_AMOUNT = 本单实际应扣金额 =
                // (payWay==2 ? 水费 : 0) + 配送费；payWay=3 的水费以水量抵扣，不计入金额
                .setOrderAmount(payWay == TradeEnum.PayWay.CARD_ML.getValue()
                        ? quote.deliveryFeeFen() : quote.totalFen())
                .setPayWay(payWay)
                // 规则5：卡即时扣减成功才建单，直接落已支付态进入待履约
                .setOrderStatus(TradeEnum.OrderStatus.PAID.getValue());
        order.setCreateTime(now);
        order.setUpdateTime(now);
        return order;
    }

    private WsDeliveryTask buildTask(String orderNo, Long userId, long stationId, WsWaterType waterType,
                                     String containerSpec, Integer deliveryCount, int planReturn,
                                     DeliveryPricing.Quote quote, int payWay, String address, String phone,
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
                // WATER_AMOUNT 与订单金额同口径（D-214）：payWay=3 水费以水量抵扣，金额记 0，
                // 保持「ORDER_AMOUNT = WATER_AMOUNT + DELIVERY_FEE」不变式对全部支付方式成立
                .setWaterAmount(payWay == TradeEnum.PayWay.CARD_ML.getValue() ? 0L : quote.waterAmountFen())
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

    /**
     * 创单冻结快照。D-214 起 waterAmountFen 语义为「本单实际应扣水费」（payWay=3 恒 0），
     * 原价目参考语义由 priceWaterAmountFen 承接（供展示与后续 E2E-04 补偿折算）；
     * waterMl 为容器总水量（毫升），payWay=3 时即事务内 BALANCE_ML 抵扣量的唯一权威值。
     */
    private String buildSnap(String requestId, DeliveryCreateBo bo, DeliveryPricing.Quote quote,
                             int payWay, long waterTypeId, String scheduledTime) {
        boolean payByMl = payWay == TradeEnum.PayWay.CARD_ML.getValue();
        JSONObject snap = JSONUtil.createObj()
                .set("requestId", requestId)
                .set("containerSpec", bo.getContainerSpec())
                .set("deliveryCount", bo.getDeliveryCount())
                .set("planReturnCount", bo.getPlanReturnCount())
                .set("waterTypeId", waterTypeId)
                .set("unitWaterPriceFen", DeliveryPricing.requireUnitWaterPrice(bo.getContainerSpec()))
                .set("deliveryFeePerContainerFen", DeliveryPricing.DELIVERY_FEE_PER_CONTAINER_FEN)
                .set("waterAmountFen", payByMl ? 0L : quote.waterAmountFen())
                .set("deliveryFeeFen", quote.deliveryFeeFen())
                .set("payWay", payWay)
                .set("waterMl", quote.waterMl())
                .set("priceWaterAmountFen", quote.waterAmountFen())
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
