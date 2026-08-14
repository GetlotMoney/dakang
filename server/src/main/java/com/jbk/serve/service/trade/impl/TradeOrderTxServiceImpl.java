package com.jbk.serve.service.trade.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.device.WsQrcodeMapper;
import com.jbk.serve.mapper.station.WsStationMapper;
import com.jbk.serve.mapper.trade.TradeCardMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.mapper.user.WsCardMemberMapper;
import com.jbk.serve.service.device.DeviceAvailability;
import com.jbk.serve.service.device.DeviceAvailabilityGuard;
import com.jbk.serve.service.settlement.ISplitService;
import com.jbk.tool.consts.settlement.SettlementEnum;
import com.jbk.serve.service.aftersale.batch.EntitlementLedger;
import com.jbk.serve.service.mini.card.CardMemberRule;
import com.jbk.serve.service.mini.card.WaterCardScope;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.serve.service.trade.ITradeOrderTxService;
import com.jbk.serve.service.trade.MemberDayLimitMath;
import com.jbk.serve.service.trade.WaterBillingMath;
import com.jbk.serve.service.trade.WaterOrderSnapshot;
import com.jbk.tool.consts.mini.MiniRejectCode;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.data.device.po.WsDevice;
import com.jbk.tool.data.device.po.WsDeviceOutlet;
import com.jbk.tool.data.device.po.WsQrcode;
import com.jbk.tool.data.mini.vo.ScanSessionInfo;
import com.jbk.tool.data.station.po.WsStation;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsWalletFlow;
import com.jbk.tool.data.user.po.WsCard;
import com.jbk.tool.data.user.po.WsCardMember;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 订单资金事务服务实现（资金铁律1 核心）。
 *
 * @author dakang
 * @since 2026-07-19
 */
@Service
public class TradeOrderTxServiceImpl implements ITradeOrderTxService {

    /** 绑号闸：卡资金流出前要求发起人已绑手机号（游客态账号可无手机号）。 */
    @Autowired
    private com.jbk.serve.service.mini.auth.MiniPhoneGate phoneGate;
    @Autowired
    private TradeCardMapper tradeCardMapper;
    @Autowired
    private WsCardMemberMapper cardMemberMapper;
    @Autowired
    private WsOrderMapper wsOrderMapper;
    @Autowired
    private WsWalletFlowMapper walletFlowMapper;
    @Autowired
    private WsQrcodeMapper qrcodeMapper;
    @Autowired
    private WsStationMapper stationMapper;
    @Autowired
    private ISplitService splitService;
    @Autowired
    private IWsDomainEventService domainEventService;
    /** 设备可用性唯一加载器（B20）：事务内以当前读判定，判定规则仍在 DeviceAvailability。 */
    @Autowired
    private DeviceAvailabilityGuard availabilityGuard;
    /**
     * 权益批次台账（E2E-04 包D-4）：扣减写分摊、退差回补批次，与卡的两列变动同事务。
     * 它没有自己的事务边界，故本类的 {@code @Transactional} 就是它的边界。
     */
    @Autowired
    private EntitlementLedger entitlementLedger;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WsOrder createWaterOrder(WsOrder order, ScanSessionInfo session, String now) {
        Long cardId = order.getCardId();
        // 区分双主体（CARD-MEMBER）：actorUserId=扫码取水的实际使用人（订单/流水/UPDATE_BY 归属），
        // cardOwnerUserId=锁内读出的卡主（扣减/退差的卡归属条件）；卡主本人取水时两者相同。
        Long actorUserId = order.getUserId();
        boolean byBalance = ObjectUtil.equal(order.getPayWay(), TradeEnum.PayWay.CARD_BALANCE.getValue());

        // 绑号闸：拦 actorUserId（订单/流水/UPDATE_BY 的责任主体）而非卡主；
        // 位置在任何写操作之前（settleExpired 会扣卡），拒绝路径零副作用。
        phoneGate.requirePhoneBound(actorUserId, "扫码取水扣款");

        // ①② 以服务端铸造会话为唯一设备来源，事务内重核共键（CARD-SCOPE）——预检不是安全边界，
        // 预检到下单之间码可被改绑、档案可被停用。重核产出唯一权威三元组（P1-A/B20），
        // 后续范围判定只吃该值；判定不过整体回滚，卡、订单、流水、分摊、指令一概不产生。
        VerifiedWaterResource verified = verifyArchiveCoKeys(order, session);

        // ③~⑦ SELECT ... FOR UPDATE 锁卡后按固定顺序校验归属（卡主或有效成员）/状态/范围/日限额
        //（失败即整体回滚，零副作用）
        WsCard lockedCard = verifyLockedCard(order, verified, now);
        Long cardOwnerUserId = lockedCard.getUserId();

        // ⑦b 批次到期清算（审计 P0-1，唯一入口）：带期批次可挂在永久卡上（D-415），
        //    卡级过期校验兜不住批次级；清算后余量不足由 ⑧ 的 CAS 正确拒绝。
        entitlementLedger.settleExpired(lockedCard, actorUserId, now);

        // ⑧ 原子扣减：单条 UPDATE ... WHERE 余量>=X 且 USER_ID=卡主，校验影响行数
        //（铁律1，绝不退化为读-算-写；USER_ID 条件绝不能删，改为显式传锁内卡主）
        int affected = byBalance
                ? tradeCardMapper.deductBalance(cardId, order.getOrderAmount(), cardOwnerUserId, actorUserId, now)
                : tradeCardMapper.deductMl(cardId, order.getPlanMl(), cardOwnerUserId, actorUserId, now);
        if (affected != 1) {
            // 影响行数=0：锁内已排除归属/状态/过期，剩余原因基本是余量不足；仍统一诊断后抛出回滚
            throw diagnoseDeductFailure(cardId, cardOwnerUserId, byBalance);
        }

        // 扣减后 AFTER 快照（同事务可见自身修改；行锁保证快照准确）
        WsCard after = tradeCardMapper.selectById(cardId);
        if (ObjectUtil.isNull(after)) {
            throw new JbkException("水卡数据异常，请重试");
        }

        // ⑨ 先落订单拿自增 ID（uk_order_no 冲突抛 DuplicateKey，编排层做 M1 归属校验）再落流水；
        //    ⑩ 扫码会话消费与设备指令由编排层在本事务提交成功后才执行。
        wsOrderMapper.insert(order);
        WsWalletFlow consumeFlow = buildConsumeFlow(order, after, byBalance);
        walletFlowMapper.insert(consumeFlow);

        // ⑪ 权益批次分摊（E2E-04 包D-4，REQ-061）：与卡扣减同事务落到具体批次；
        //    额度与 ⑧ 逐维相等：payWay=2 扣余额、payWay=3 扣水量，绝不换算。
        entitlementLedger.allocateOnConsume(
                new EntitlementLedger.ConsumeRef(cardId, cardOwnerUserId, order.getId(),
                        consumeFlow.getId(), EntitlementLedger.consumeKey(order)),
                byBalance ? order.getOrderAmount() : 0L,
                byBalance ? 0L : order.getPlanMl(),
                actorUserId, now);
        return order;
    }

    /**
     * P1-A：事务内重读核验后的唯一权威「站-设备-出水口」三元组；范围判定只吃本值——
     * 会话铸造值在预检到下单之间可能已过期（如设备迁站），拿旧语境放行新档案即范围绕过。
     */
    record VerifiedWaterResource(Long stationId, Long deviceId, Long outletId) {
    }

    /**
     * ② 共键重核：qrcode/station/device/outlet 存在、未删除、状态可用，四方共键并与订单/会话
     * 铸造值逐一对齐；任一错位 fail-closed 不建单——少一条对齐就有「扫 A 码、扣 B 口」的窗口。
     *
     * @return 事务内核验后的唯一权威三元组（P1-A），供范围判定与拒绝审计使用
     */
    private VerifiedWaterResource verifyArchiveCoKeys(WsOrder order, ScanSessionInfo session) {
        if (ObjectUtil.isNull(session) || ObjectUtil.isNull(session.getQrcodeId())
                || ObjectUtil.isNull(session.getDeviceId()) || ObjectUtil.isNull(session.getOutletId())) {
            // 旧版本铸造的会话缺 qrcodeId：宁可要求重扫，也不跳过共键重核
            throw new JbkException("扫码会话数据不完整，请重新扫码");
        }
        // 权威当前读一次读齐（锁序 设备X → 出水口X → 故障字典S → 二维码S → 水站S，随后才锁卡），
        // 全部判定只吃这一批数据；普通 selectById 读的是事务首次一致性读快照，迁站/改绑在快照里不存在。
        DeviceAvailabilityGuard.CheckedDeviceContext checked =
                availabilityGuard.loadForUpdate(session.getDeviceId(), session.getOutletId());
        WsQrcode qrcode = qrcodeMapper.selectByIdForShare(session.getQrcodeId());
        if (checked.archiveMissing() || ObjectUtil.isNull(qrcode)) {
            throw coKeyRejected(order, "二维码/设备/出水口档案缺失或已删除");
        }
        WsDevice device = checked.device();
        WsDeviceOutlet outlet = checked.outlet();
        WsStation station = stationMapper.selectByIdForShare(device.getStationId());
        if (ObjectUtil.isNull(station)) {
            throw coKeyRejected(order, "设备所属水站档案缺失或已删除");
        }
        // 档案状态可用（1正常 2禁用）；设备的运行态可用性在本方法末尾用同一批数据判定。
        if (ObjectUtil.notEqual(qrcode.getQrcodeStatus(), 1)) {
            throw coKeyRejected(order, "二维码已停用");
        }
        if (ObjectUtil.notEqual(station.getStationStatus(), 1)) {
            throw coKeyRejected(order, "水站已停用");
        }
        if (ObjectUtil.notEqual(outlet.getOutletStatus(), 1)) {
            throw coKeyRejected(order, "出水口已停用");
        }
        boolean aligned = ObjectUtil.equal(qrcode.getDeviceId(), device.getId())
                && ObjectUtil.equal(qrcode.getOutletId(), outlet.getId())
                && ObjectUtil.equal(outlet.getDeviceId(), device.getId())
                && ObjectUtil.equal(device.getStationId(), station.getId())
                // 订单上的落库共键必须与锁内档案一致，防编排层装配漂移
                && ObjectUtil.equal(order.getDeviceId(), device.getId())
                && ObjectUtil.equal(order.getOutletId(), outlet.getId())
                && ObjectUtil.equal(order.getStationId(), station.getId());
        if (!aligned) {
            throw coKeyRejected(order, "二维码与设备/出水口档案共键错位");
        }
        // P1-A：会话铸造三元组与锁内档案完全一致才放行（fail-closed）；stationId 是真正的活口——
        // 预检后设备被迁站时会话仍带旧站，放行即拿旧站授权判新站扣款。任一不等要求重扫。
        boolean sessionAligned = ObjectUtil.equal(session.getStationId(), station.getId())
                && ObjectUtil.equal(session.getDeviceId(), device.getId())
                && ObjectUtil.equal(session.getOutletId(), outlet.getId());
        if (!sessionAligned) {
            throw coKeyRejected(order, "扫码会话三元组与当前档案不一致");
        }
        // 冻结快照核验：水种是出水口的业务身份，共键全对得上但水种被改，用户买的已不是
        // 他确认的水——不核这条，改水种就成了无痕换货
        verifyFrozenSnapshot(order, outlet, session);
        // 运行可用性与共键判定用同一批当前读数据，不另读一份（防判定/落库版本分叉）
        if (!checked.available()) {
            String reason = StrUtil.blankToDefault(checked.reason(), "设备当前不可用");
            // REQUIRES_NEW 独立留痕：主事务随后回滚正是拒绝的业务结果，同事务写会连证据一起滚掉
            domainEventService.recordReliable(OpsEnum.EventType.ORDER_STATUS, order.getOrderNo(), null,
                    "取水下单事务设备可用性拒绝：" + checked.verdict().code() + " " + reason);
            throw new JbkException(reason);
        }
        return new VerifiedWaterResource(station.getId(), device.getId(), outlet.getId());
    }

    /**
     * ③~⑦ 锁内卡校验：FOR UPDATE 后按固定顺序判定。校验必须基于锁内行——
     * 预检/编排层读到的卡状态在进入本事务前随时可能变化。
     *
     * @return 锁内读出的卡行（含卡主 USER_ID，供 ⑧ 扣减的归属条件使用）
     */
    private WsCard verifyLockedCard(WsOrder order, VerifiedWaterResource verified, String now) {
        // ③ 锁卡（读全部 DATA_STATUS，删除态也要锁到再显式拒绝）
        WsCard card = tradeCardMapper.selectByIdForUpdate(order.getCardId());
        if (ObjectUtil.isNull(card) || ObjectUtil.notEqual(card.getDataStatus(), 0)) {
            throw new JbkException("水卡不存在或不属于当前用户");
        }
        // ④ 使用人判定（CARD-MEMBER）：卡主直通；非卡主须持当前有效成员授权。成员关系行必须
        //    FOR UPDATE——日限额无独立统计表，这把单行锁是同成员同卡并发下单的唯一串行化点。
        //    无关用户/已撤销/未生效/已失效统一口径拒绝，不泄露他人卡与授权历史。
        WsCardMember member = null;
        if (ObjectUtil.notEqual(card.getUserId(), order.getUserId())) {
            member = cardMemberMapper.selectByCardAndUserForUpdate(order.getCardId(), order.getUserId());
            if (!CardMemberRule.isActive(member, now)) {
                throw new JbkException("水卡不存在或不属于当前用户");
            }
        }
        // ⑤ 状态正常/未过期
        if (ObjectUtil.equal(card.getCardStatus(), 2)) {
            throw new JbkException("水卡已冻结，暂不可取水");
        }
        if (ObjectUtil.equal(card.getCardStatus(), 4)) {
            throw new JbkException("水卡已注销");
        }
        boolean expiredByTime = StrUtil.isNotBlank(card.getExpireTime())
                && card.getExpireTime().compareTo(now) <= 0;
        if (ObjectUtil.equal(card.getCardStatus(), 3) || expiredByTime) {
            throw new JbkException("水卡已过期");
        }
        if (ObjectUtil.notEqual(card.getCardStatus(), 1)) {
            // 未知状态一律 fail-closed：新增状态必须显式登记后才可取水
            throw new JbkException("水卡状态异常，暂不可取水");
        }
        // ⑥ 范围放行：与预检同一解析器（全仓唯一），空/非法 JSON 默认拒绝；成员继承主卡范围。
        //    P1-A：判定只吃事务内权威三元组；P1-C：真实提交被拒时落可靠审计。
        WaterCardScope scope;
        try {
            scope = WaterCardScope.normalize(card.getScopeJson(), "水卡");
        } catch (JbkException invalid) {
            throw scopeRejected(order, verified, "CARD_SCOPE_INVALID", now,
                    "水卡可用范围未配置或非法（默认拒绝）：" + invalid.getMsg());
        }
        if (!scope.allows(verified.stationId(), verified.deviceId(), verified.outletId())) {
            throw scopeRejected(order, verified, "CARD_SCOPE_DENIED", now,
                    "该水卡不适用于当前设备，请更换设备或水卡");
        }
        // ⑦ 成员日限额（CARD-MEMBER）：仅成员且配置了限额时校验；统计在成员关系行锁之后执行，
        //    并发请求在 ④ 的行锁上排队，读到的都是前一笔已提交后的占用。
        if (ObjectUtil.isNotNull(member) && ObjectUtil.isNotNull(member.getDayLimitMl())) {
            enforceMemberDayLimit(order, member, now);
        }
        return card;
    }

    /**
     * 成员单日限额闸（⑦）：当天已占用 + 本次计划量 <= DAY_LIMIT_ML（口径见 {@link MemberDayLimitMath}）；
     * 统计跨全部 DATA_STATUS，删单不得成为绕过限额的后门。
     */
    private void enforceMemberDayLimit(WsOrder order, WsCardMember member, String now) {
        long planMl;
        try {
            planMl = WaterBillingMath.requirePlanMl(order.getPlanMl());
        } catch (JbkException invalid) {
            throw new JbkException("计划水量非法，无法进行成员日限额校验");
        }
        // 必须锁定读：普通 SELECT 读的是等锁前的快照，看不见并发刚提交的订单，限额会被突破
        List<WsOrder> todayOrders = wsOrderMapper.selectMemberDayWaterOrdersForUpdate(
                order.getCardId(), order.getUserId(),
                MemberDayLimitMath.dayStart(now), MemberDayLimitMath.dayEnd(now));
        long occupiedMl;
        try {
            occupiedMl = MemberDayLimitMath.occupiedMl(todayOrders);
        } catch (ArithmeticException overflow) {
            // 占用累加溢出：数据已异常，按超限拒绝（fail-closed），不得回绕放行
            throw new JbkException("超出成员单日取水限额");
        }
        if (!MemberDayLimitMath.allows(occupiedMl, planMl, member.getDayLimitMl())) {
            throw new JbkException("超出成员单日取水限额");
        }
    }

    /**
     * P1-C 范围拒绝审计：仅真实提交内 CARD_SCOPE_INVALID/DENIED 时落一条可靠事件。
     * REQUIRES_NEW 独立提交（主事务回滚正是拒绝的业务结果，证据必须存活）；幂等键取确定性单号，
     * 重复强提交撞 uk_domain_event_biz_key 最多一条；事件只含判定要素不存 SCOPE_JSON 原文；
     * 预检不经过本方法，天然零审计。
     */
    private JbkException scopeRejected(WsOrder order, VerifiedWaterResource verified,
                                       String rejectCode, String now, String message) {
        JSONObject evidence = new JSONObject();
        evidence.set("actorUserId", order.getUserId());
        evidence.set("cardId", order.getCardId());
        evidence.set("stationId", verified.stationId());
        evidence.set("deviceId", verified.deviceId());
        evidence.set("outletId", verified.outletId());
        evidence.set("rejectCode", rejectCode);
        evidence.set("decidedAt", now);
        domainEventService.recordReliableOnceIndependent(OpsEnum.EventType.ORDER_STATUS, order.getOrderNo(),
                "CARD_SCOPE_DENY:" + order.getOrderNo(), null, "取水下单范围拒绝：" + evidence);
        return new JbkException(message);
    }

    /**
     * 冻结快照与当前档案一致性核验（唯一解析在 {@link WaterOrderSnapshot}）：快照合法、
     * 订单落库值与快照自洽（防装配漂移）、快照水种与锁内出水口一致（防改水种）。任一不符整体回滚。
     */
    private void verifyFrozenSnapshot(WsOrder order, WsDeviceOutlet outlet, ScanSessionInfo session) {
        WaterOrderSnapshot.Frozen frozen;
        try {
            frozen = WaterOrderSnapshot.parseStrict(order.getPackageSnap());
        }
        catch (JbkException invalid) {
            throw snapshotRejected(order, "下单快照不合法（" + invalid.getMsg() + "）");
        }
        if (ObjectUtil.notEqual(order.getPlanMl(), frozen.planMl())
                || ObjectUtil.notEqual(order.getPayWay(), frozen.payWay())) {
            throw snapshotRejected(order, "订单水量/支付方式与下单快照不一致");
        }
        long expectedAmount = WaterOrderSnapshot.expectedOrderAmount(frozen);
        if (ObjectUtil.notEqual(order.getOrderAmount(), expectedAmount)) {
            throw snapshotRejected(order, "订单金额与下单快照的水量、单价不自洽");
        }
        if (ObjectUtil.notEqual(frozen.waterTypeId(), outlet.getWaterTypeId())) {
            throw quoteChanged(order, "出水口水种已变更，请重新扫码确认");
        }
        // S2 三方一致：Redis 会话报价 == 订单快照 == 锁内当前出水口，少核任何一边都留着一条缝
        if (ObjectUtil.notEqual(frozen.requestId(), session.getScanSessionId())) {
            throw quoteChanged(order, "下单快照与本次扫码会话不一致，请重新扫码");
        }
        if (ObjectUtil.notEqual(frozen.waterTypeId(), session.getWaterTypeId())
                || ObjectUtil.notEqual(frozen.unitPriceFenPerLiter(), session.getUnitPriceFenPerLiter())) {
            throw quoteChanged(order, "下单快照与扫码冻结报价不一致，请重新扫码");
        }
        int currentPrice;
        try {
            currentPrice = WaterBillingMath.requireOutletPrice(outlet.getOutletPrice());
        }
        catch (JbkException invalid) {
            throw snapshotRejected(order, "出水口单价配置非法（" + invalid.getMsg() + "）");
        }
        if (ObjectUtil.notEqual(frozen.unitPriceFenPerLiter(), currentPrice)) {
            throw quoteChanged(order, "取水价格已变更，请重新扫码确认");
        }
    }

    /**
     * 报价漂移拒绝（S2）：稳定拒绝码 SCAN_QUOTE_CHANGED，与「快照非法」区分（前者要用户重扫，
     * 后者要运维查）；留痕走 recordReliableOnceIndependent，连点多次只落一条，主事务回滚证据仍存活。
     */
    private JbkException quoteChanged(WsOrder order, String reason) {
        domainEventService.recordReliableOnceIndependent(OpsEnum.EventType.ORDER_STATUS, order.getOrderNo(),
                "SCAN_QUOTE_CHANGED:" + order.getOrderNo(), null, "取水下单扫码报价已变化：" + reason);
        return new JbkException(reason, MiniRejectCode.SCAN_QUOTE_CHANGED);
    }

    private JbkException snapshotRejected(WsOrder order, String reason) {
        // 与共键拒绝同一留痕口径：REQUIRES_NEW 独立提交，主事务回滚不带走证据
        domainEventService.recordReliable(OpsEnum.EventType.ORDER_STATUS, order.getOrderNo(), null,
                "取水下单事务冻结快照拒绝：" + reason);
        return new JbkException(reason);
    }

    private JbkException coKeyRejected(WsOrder order, String reason) {
        // 共键错位是档案被改绑/停用或会话被篡改的信号：REQUIRES_NEW 独立留痕，主事务回滚不带走证据
        domainEventService.recordReliable(OpsEnum.EventType.ORDER_STATUS, order.getOrderNo(), null,
                "取水下单事务共键重核拒绝：" + reason);
        return new JbkException("设备信息已变化，请重新扫码（" + reason + "）");
    }

    @Override
    public int claimCommandSlot(Long orderId, Long commandId,
                                Long stationId, Long deviceId, Long outletId) {
        // 原子闸：单条条件 UPDATE，影响行数=1 才算抢到。谓词逐条都是安全边界：
        //   CMD_ID IS NULL=一单一条有效指令（铁律4）；ORDER_TYPE=1/ORDER_STATUS=2=只有已支付
        //   取水单可下发（期间可能并发转 6/7/4）；三共键=期间迁站或出水口改绑一律不发。
        return wsOrderMapper.update(null, Wrappers.lambdaUpdate(WsOrder.class)
                .set(WsOrder::getCmdId, commandId)
                .set(WsOrder::getUpdateTime, DateUtils.time())
                .eq(WsOrder::getId, orderId)
                .eq(WsOrder::getOrderType, TradeEnum.OrderType.WATER.getValue())
                .eq(WsOrder::getOrderStatus, TradeEnum.OrderStatus.PAID.getValue())
                .isNull(WsOrder::getCmdId)
                .eq(WsOrder::getStationId, stationId)
                .eq(WsOrder::getDeviceId, deviceId)
                .eq(WsOrder::getOutletId, outletId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean settleWaterOrder(Long orderId, boolean success, Long actualMl) {
        // ① 无锁定位读：只用来知道该锁哪张卡，不参与任何结算判定
        WsOrder routing = wsOrderMapper.selectById(orderId);
        if (routing == null) {
            return false;
        }
        if (!isSettleable(routing.getOrderStatus())) {
            // 幂等：已结算（4/6/7/8）或非可结算态 → 跳过不重复补偿
            return false;
        }
        // ② 锁序对齐 createWaterOrder（卡 → 订单）：等到退差分支才锁卡就成「订单 → 卡」，
        // 与下单事务构成 ABBA（成员卡配日限额且当日已有订单时结算必然互等），必须无条件前置。
        WsCard lockedCard = lockCardForSettlement(routing);
        // ③ 订单当前读并锁定：全部结算口径必须取自锁内行——等锁期间订单可能已被并发推进，
        // 而 CAS 只复核 ORDER_STATUS 一项，兜不住其余字段。
        WsOrder order = wsOrderMapper.selectByIdForUpdate(orderId);
        if (order == null) {
            return false;
        }
        if (!isSettleable(order.getOrderStatus())) {
            return false;
        }
        if (ObjectUtil.notEqual(order.getCardId(), routing.getCardId())) {
            // 定位读与锁内行的卡不是同一张：锁到的卡不是该动的那张，fail-closed
            throw settlementRejected(order, "订单水卡在结算过程中发生变化");
        }
        String now = DateUtils.time();
        long plan;
        long actual;
        try {
            plan = WaterBillingMath.requirePlanMl(order.getPlanMl());
            actual = WaterBillingMath.requireNonNegativeMl(actualMl, "实际水量");
        } catch (JbkException e) {
            throw settlementRejected(order, e.getMsg());
        }
        // 结算量封顶计划量（v1 超量不额外计费）；应扣/退差以结算量算
        long settledActual = Math.min(actual, plan);
        boolean byBalance = ObjectUtil.equal(order.getPayWay(), TradeEnum.PayWay.CARD_BALANCE.getValue());
        boolean byMl = ObjectUtil.equal(order.getPayWay(), TradeEnum.PayWay.CARD_ML.getValue());
        if (!byBalance && !byMl) {
            throw settlementRejected(order, "订单支付方式不支持取水结算");
        }
        // REQ-035 超量出水：账实不符，终态必须是 6异常待人工核实（静默封顶落 4已完成会查不出）。
        // 冻结口径：不追加扣款、不生成退款/退差流水、保留设备原始 actualMl；
        // 指令 result 可以是执行成功——那是设备侧事实，与订单业务终态是两回事。
        if (success && actual > plan) {
            return settleOverDispensed(order, plan, actual, now);
        }

        // v1.3 终态：success=false→6异常待核；success 且零出水→7已退款；其余（足量/不足）→4已完成
        int target;
        if (!success) {
            target = TradeEnum.OrderStatus.ABNORMAL.getValue();
        } else if (actual == 0L) {
            target = TradeEnum.OrderStatus.REFUNDED.getValue();
        } else {
            target = TradeEnum.OrderStatus.FINISHED.getValue();
        }

        // 补偿金额/水量：payWay2 退差=预扣-ceil(结算量*单价/1000)；payWay3 退未出水量=plan-结算量
        long refundFen = 0L;
        long refundMl = 0L;
        if (byBalance) {
            UnitPriceSnapshot unitPriceSnapshot = parseUnitPriceFromSnap(order);
            int unitPrice = unitPriceSnapshot.unitPriceFenPerLiter();
            long actualCharge;
            long preCharge = order.getOrderAmount() == null ? -1L : order.getOrderAmount();
            try {
                if (!unitPriceSnapshot.fallback()) {
                    long expectedPreCharge = WaterBillingMath.ceilAmount(plan, unitPrice);
                    if (preCharge != expectedPreCharge) {
                        throw new JbkException("订单预扣金额与计划水量、单价快照不一致");
                    }
                }
                actualCharge = WaterBillingMath.ceilAmount(settledActual, unitPrice);
                if (preCharge < 0 || actualCharge > preCharge) {
                    throw new JbkException("实际应扣金额超出订单预扣金额");
                }
                refundFen = Math.subtractExact(preCharge, actualCharge);
            } catch (JbkException | ArithmeticException e) {
                String reason = e instanceof JbkException ? ((JbkException) e).getMsg() : "退差金额计算溢出";
                throw settlementRejected(order, reason);
            }
        } else {
            try {
                refundMl = Math.subtractExact(plan, settledActual);
            } catch (ArithmeticException e) {
                throw settlementRejected(order, "退差水量计算溢出");
            }
        }
        if (refundFen < 0 || refundMl < 0
                || (byBalance && refundFen > order.getOrderAmount())
                || (!byBalance && refundMl > plan)) {
            throw settlementRejected(order, "退差结果超出订单权益边界");
        }

        // 幂等闸：订单终态条件 UPDATE（WHERE 仍为 2/3），affected==0 说明已被并发结算 → 跳过不补偿
        int moved = wsOrderMapper.update(null, Wrappers.lambdaUpdate(WsOrder.class)
                .set(WsOrder::getActualMl, actual)
                .set(WsOrder::getOrderStatus, target)
                .set(WsOrder::getFinishTime, now)
                .set(!success, WsOrder::getCancelReason,
                        StrUtil.maxLength("出水结果异常(success=false，actualMl=" + actual + ")，待人工核实", 490))
                .set(WsOrder::getUpdateTime, now)
                .eq(WsOrder::getId, orderId)
                .in(WsOrder::getOrderStatus, TradeEnum.OrderStatus.PAID.getValue(),
                        TradeEnum.OrderStatus.DISPENSING.getValue()));
        if (moved == 0) {
            return false;
        }

        // 补偿入账（退差/退款）：原子 UPDATE 加回 + 流水（铁律1）；refund=0 不写流水。
        // CARD-MEMBER：退差回到卡主的卡（归属条件用锁内卡主），流水与 UPDATE_BY 记实际使用人。
        if (byBalance && refundFen > 0) {
            Long ownerUserId = requireLockedCardOwner(lockedCard);
            if (tradeCardMapper.compensateBalance(order.getCardId(), refundFen, ownerUserId, order.getUserId(), now) != 1) {
                throw new JbkException("退差补偿入账失败（余额）");
            }
            WsCard after = tradeCardMapper.selectById(order.getCardId());
            walletFlowMapper.insert(buildCompensateFlow(order, after, refundFen, 0L, now));
            restoreEntitlement(order, after, ownerUserId, refundFen, 0L, now);
        } else if (!byBalance && refundMl > 0) {
            Long ownerUserId = requireLockedCardOwner(lockedCard);
            if (tradeCardMapper.compensateMl(order.getCardId(), refundMl, ownerUserId, order.getUserId(), now) != 1) {
                throw new JbkException("退差补偿入账失败（水量）");
            }
            WsCard after = tradeCardMapper.selectById(order.getCardId());
            walletFlowMapper.insert(buildCompensateFlow(order, after, 0L, refundMl, now));
            restoreEntitlement(order, after, ownerUserId, 0L, refundMl, now);
        }

        // E2E-08 分账挂点：终态=已完成且余额支付才产分账，基数=实扣（预扣-退差）；
        // 水量支付 ORDER_AMOUNT=0 不分账（水费在充值环节结算），6/7 不分账；同事务插行，回滚同灭。
        if (target == TradeEnum.OrderStatus.FINISHED.getValue() && byBalance) {
            splitService.enqueueForOrder(order.getId(), order.getOrderNo(),
                    order.getOrderAmount() - refundFen,
                    SettlementEnum.ProductLine.WATER,
                    splitService.resolveWaterOwner(order.getDeviceId(), order.getStationId()),
                    null, order.getCreateTime());
        }
        return true;
    }

    /**
     * 超量出水收口（REQ-035）：可结算态精确 CAS 到 6异常待补偿，落设备原始 actualMl。
     * 零资金动作——超出部分计费口径属外部待确认项，规则明确前只标账实不符交人工。
     * CAS 影响行数 0 即已被并发结算，跳过且不落审计（避免重投刷证据）。
     */
    private boolean settleOverDispensed(WsOrder order, long plan, long actual, String now) {
        long overMl = actual - plan;
        int moved = wsOrderMapper.update(null, Wrappers.lambdaUpdate(WsOrder.class)
                .set(WsOrder::getActualMl, actual)
                .set(WsOrder::getOrderStatus, TradeEnum.OrderStatus.ABNORMAL.getValue())
                .set(WsOrder::getFinishTime, now)
                .set(WsOrder::getCancelReason, StrUtil.maxLength(
                        "实际出水量超出计划量(planMl=" + plan + "，actualMl=" + actual + ")，待人工核实", 490))
                .set(WsOrder::getUpdateTime, now)
                .eq(WsOrder::getId, order.getId())
                .in(WsOrder::getOrderStatus, TradeEnum.OrderStatus.PAID.getValue(),
                        TradeEnum.OrderStatus.DISPENSING.getValue()));
        if (moved == 0) {
            return false;
        }
        JSONObject evidence = new JSONObject();
        evidence.set("orderNo", order.getOrderNo());
        evidence.set("planMl", plan);
        evidence.set("actualMl", actual);
        evidence.set("overMl", overMl);
        evidence.set("decidedAt", now);
        domainEventService.recordReliable(OpsEnum.EventType.ORDER_STATUS, order.getOrderNo(), null,
                "取水超量出水待核：" + evidence);
        return true;
    }

    /**
     * 退差回补权益批次（E2E-04 包D-4）：卡加回多少批次就补回多少，否则卡聚合值永久高于批次
     * 剩余合计，余额看得见花不掉（{@link EntitlementLedger} 的不变式）；
     * 回补按本单消费分摊键定位，不碰同卡别的消费。
     */
    private void restoreEntitlement(WsOrder order, WsCard after, Long ownerUserId,
                                   long refundFen, long refundMl, String now) {
        entitlementLedger.restoreOnRefundBack(
                new EntitlementLedger.CardAfter(order.getCardId(), ownerUserId, after.getExpireTime(),
                        after.getBalanceAmount(), after.getBalanceMl()),
                EntitlementLedger.consumeKey(order), refundFen, refundMl, order.getUserId(), now);
    }

    /** 取水订单可结算态：2已支付 / 3出水中，显式枚举不做数值比较。 */
    private static boolean isSettleable(Integer status) {
        return ObjectUtil.equal(status, TradeEnum.OrderStatus.PAID.getValue())
                || ObjectUtil.equal(status, TradeEnum.OrderStatus.DISPENSING.getValue());
    }

    /**
     * 结算入口处无条件锁卡，确立「卡 → 订单」锁序（与下单事务同向，消除 ABBA）；
     * 补偿 SQL 的归属条件取自锁内卡行。锁定读跨全部 DATA_STATUS，删除态锁到后显式拒绝。
     */
    private WsCard lockCardForSettlement(WsOrder order) {
        if (ObjectUtil.isNull(order.getCardId())) {
            return null;
        }
        WsCard card = tradeCardMapper.selectByIdForUpdate(order.getCardId());
        // 卡缺失/已删除不提前拒绝（零退差结算不碰卡）；拒绝时机留在补偿分支 requireLockedCardOwner
        return ObjectUtil.isNull(card) || ObjectUtil.notEqual(card.getDataStatus(), 0) ? null : card;
    }

    /** 补偿分支取锁内卡主；卡缺失/已删除时按既有文案拒绝并整体回滚。 */
    private Long requireLockedCardOwner(WsCard lockedCard) {
        if (ObjectUtil.isNull(lockedCard)) {
            throw new JbkException("退差补偿入账失败（水卡不存在或已删除）");
        }
        return lockedCard.getUserId();
    }

    private WsWalletFlow buildCompensateFlow(WsOrder order, WsCard after, long refundFen, long refundMl, String now) {
        return new WsWalletFlow()
                .setCardId(order.getCardId())
                .setUserId(order.getUserId())
                .setFlowType(TradeEnum.FlowType.COMPENSATE.getValue())
                .setAmountChange(refundFen)
                .setMlChange(refundMl)
                .setAmountAfter(after.getBalanceAmount())
                .setMlAfter(after.getBalanceMl())
                .setOrderId(order.getId())
                .setFlowRemark("出水结算退差/退款 " + order.getOrderNo());
    }

    /**
     * 从 PACKAGE_SNAP 读单价快照（唯一解析在 {@link WaterOrderSnapshot}）：缺失按产品 A
     * 回退单价 0 并独立留痕；存在但非法由解析侧抛出，禁止全额退款掩盖篡改。
     */
    private UnitPriceSnapshot parseUnitPriceFromSnap(WsOrder order) {
        WaterOrderSnapshot.UnitPriceRead read;
        try {
            read = WaterOrderSnapshot.readUnitPriceLenient(order.getPackageSnap());
        }
        catch (JbkException illegal) {
            throw settlementRejected(order, illegal.getMsg());
        }
        if (read.fallback()) {
            recordPriceFallback(order, read.fallbackReason());
            return UnitPriceSnapshot.fallbackValue();
        }
        return UnitPriceSnapshot.valid(read.unitPriceFenPerLiter());
    }

    private record UnitPriceSnapshot(int unitPriceFenPerLiter, boolean fallback) {
        private static UnitPriceSnapshot valid(int unitPriceFenPerLiter) {
            return new UnitPriceSnapshot(unitPriceFenPerLiter, false);
        }

        private static UnitPriceSnapshot fallbackValue() {
            return new UnitPriceSnapshot(0, true);
        }
    }

    private void recordPriceFallback(WsOrder order, String reason) {
        domainEventService.recordReliable(OpsEnum.EventType.ORDER_STATUS, order.getOrderNo(), null,
                reason + "，按产品 A 使用单价 0 计算退差");
    }

    private JbkException settlementRejected(WsOrder order, String reason) {
        domainEventService.recordReliable(OpsEnum.EventType.ORDER_STATUS, order.getOrderNo(), null,
                "取水资金结算已拒绝：" + reason);
        return new JbkException(reason);
    }

    /** 影响行数=0 时按卡实际状态给出精确拒因（同事务读取，读到的是未被本次扣减修改的当前值）。 */
    private JbkException diagnoseDeductFailure(Long cardId, Long expectedOwnerUserId, boolean byBalance) {
        WsCard card = tradeCardMapper.selectById(cardId);
        if (ObjectUtil.isNull(card) || ObjectUtil.notEqual(expectedOwnerUserId, card.getUserId())) {
            return new JbkException("水卡不存在或不属于当前用户");
        }
        if (ObjectUtil.equal(card.getCardStatus(), 2)) {
            return new JbkException("水卡已冻结，暂不可取水");
        }
        if (ObjectUtil.equal(card.getCardStatus(), 4)) {
            return new JbkException("水卡已注销");
        }
        boolean expiredByTime = StrUtil.isNotBlank(card.getExpireTime())
                && card.getExpireTime().compareTo(DateUtils.time()) <= 0;
        if (ObjectUtil.equal(card.getCardStatus(), 3) || expiredByTime) {
            return new JbkException("水卡已过期");
        }
        return new JbkException(byBalance ? "水卡余额不足" : "水卡水量不足");
    }

    private WsWalletFlow buildConsumeFlow(WsOrder order, WsCard after, boolean byBalance) {
        return new WsWalletFlow()
                .setCardId(order.getCardId())
                .setUserId(order.getUserId())
                .setFlowType(TradeEnum.FlowType.CONSUME.getValue())
                .setAmountChange(byBalance ? -order.getOrderAmount() : 0L)
                .setMlChange(byBalance ? 0L : -order.getPlanMl())
                .setAmountAfter(after.getBalanceAmount())
                .setMlAfter(after.getBalanceMl())
                .setOrderId(order.getId())
                // 幂等键（E2E-08 包A）：日对账按 CONSUME:<orderNo> 判恰一条扣减流水；存量行由迁移回填
                .setBizIdempotencyKey("CONSUME:" + order.getOrderNo())
                .setFlowRemark("扫码取水 " + order.getOrderNo());
    }
}
