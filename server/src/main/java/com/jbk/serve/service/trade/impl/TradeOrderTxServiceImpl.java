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

        // ①② 以服务端铸造的会话为唯一设备来源，事务内重核二维码/档案共键（CARD-SCOPE）。
        // 预检不是安全边界：预检到下单之间码可被改绑、档案可被停用，这里不重核就会照单扣款。
        // P1-A：重核通过后产出唯一权威三元组，后续范围判定只吃该值，不再采信会话铸造值。
        // 共键、档案状态、会话三元组与设备运行可用性全在这一步用同一批权威当前数据判完（B20）：
        // 编排层 eligibility 在扣款之前，两者之间隔着装配与锁卡等待，期间设备离线/维护/锁定/
        // 出水中/上报阻断故障码、迁站、出水口改绑都不会回滚已通过的预检结论。判定必须在锁卡与
        // 扣款之前完成——判定不过就整体回滚，卡、订单、流水、分摊、指令一概不产生。
        VerifiedWaterResource verified = verifyArchiveCoKeys(order, session);

        // ③~⑦ SELECT ... FOR UPDATE 锁卡后按固定顺序校验归属（卡主或有效成员）/状态/范围/日限额
        //（失败即整体回滚，零副作用）
        WsCard lockedCard = verifyLockedCard(order, verified, now);
        Long cardOwnerUserId = lockedCard.getUserId();

        // ⑦b 批次到期清算（审计 P0-1）：扣减前作废已到期批次并同步扣卡（唯一入口，禁止复制算法）。
        //    带期批次可能挂在永久卡上（D-415 合并），卡级过期校验兜不住批次级过期；
        //    清算后余量若不足，⑧ 的 CAS 会正确拒绝——过期权益不得再参与支付。
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

        // ⑨ 先落订单拿自增 ID（uk_order_no 冲突在此抛 DuplicateKey，由编排层做 M1 归属校验），
        //    再落流水，ORDER_ID 关联订单，AFTER 记扣减后快照（流水只插不改）。
        //    ⑩ 扫码会话消费与设备指令由编排层在本事务提交成功后才执行。
        wsOrderMapper.insert(order);
        WsWalletFlow consumeFlow = buildConsumeFlow(order, after, byBalance);
        walletFlowMapper.insert(consumeFlow);

        // ⑪ 权益批次分摊（E2E-04 包D-4，REQ-061）：与卡扣减同事务落到具体批次。
        //    不摊到批次，「这笔充值的权益被用掉了多少」就只能靠卡聚合值去猜，而退款正按它折算。
        //    额度与 ⑧ 的扣减逐维相等：payWay=2 扣余额、payWay=3 扣水量，绝不换算。
        entitlementLedger.allocateOnConsume(
                new EntitlementLedger.ConsumeRef(cardId, cardOwnerUserId, order.getId(),
                        consumeFlow.getId(), EntitlementLedger.consumeKey(order)),
                byBalance ? order.getOrderAmount() : 0L,
                byBalance ? 0L : order.getPlanMl(),
                actorUserId, now);
        return order;
    }

    /**
     * P1-A：事务内重读并核验后的唯一权威「站-设备-出水口」三元组（不可变）。
     * 值只来自 {@link #verifyArchiveCoKeys} 锁内重读并逐项核验后的 station/device/outlet/qrcode 档案，
     * 范围判定（{@link WaterCardScope#allows}）只吃本三元组——会话铸造值在预检到下单之间
     * 可能已过期（如设备迁站），拿旧语境放行新档案就是范围绕过。
     */
    record VerifiedWaterResource(Long stationId, Long deviceId, Long outletId) {
    }

    /**
     * ② 共键重核：qrcode/station/device/outlet 存在、未删除、档案状态可用，且
     * qrcode.deviceId==device.id、qrcode.outletId==outlet.id、outlet.deviceId==device.id、
     * device.stationId==station.id，并与订单/会话铸造值逐一对齐。任一错位 fail-closed 不建单——
     * 少一条对齐就存在「扫 A 码、扣 B 口」的错位扣款窗口。
     *
     * @return 事务内核验后的唯一权威三元组（P1-A），供范围判定与拒绝审计使用
     */
    private VerifiedWaterResource verifyArchiveCoKeys(WsOrder order, ScanSessionInfo session) {
        if (ObjectUtil.isNull(session) || ObjectUtil.isNull(session.getQrcodeId())
                || ObjectUtil.isNull(session.getDeviceId()) || ObjectUtil.isNull(session.getOutletId())) {
            // 旧版本铸造的会话缺 qrcodeId：宁可让用户重扫一次，也不跳过共键重核。
            throw new JbkException("扫码会话数据不完整，请重新扫码");
        }
        // 权威当前读一次读齐（锁序 设备X → 出水口X → 故障字典S → 二维码S → 水站S，随后才锁卡）。
        // 全部判定——共键、档案状态、会话三元组、运行可用性、以及下游的水卡范围——只吃这一批数据。
        // 用普通 selectById 会读到事务首次一致性读的快照：设备迁站、出水口改绑、字典改判在快照里
        // 根本不存在，判了也白判。逻辑删除行由各当前读语句的 DATA_STATUS=0 条件排除。
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
        // P1-A：会话铸造三元组必须与事务内重读核验后的档案完全一致（fail-closed）。
        // deviceId/outletId 因按会话值回查而天然相等，仍显式核对以防未来查询口径漂移；
        // stationId 是真正的活口——设备在预检后被迁站时，会话仍带旧站，若继续放行
        // 就会拿「用户扫码时看到的旧站授权」判定「新站的扣款」。任一不等一律要求重扫。
        boolean sessionAligned = ObjectUtil.equal(session.getStationId(), station.getId())
                && ObjectUtil.equal(session.getDeviceId(), device.getId())
                && ObjectUtil.equal(session.getOutletId(), outlet.getId());
        if (!sessionAligned) {
            throw coKeyRejected(order, "扫码会话三元组与当前档案不一致");
        }
        // 冻结快照核验：下单当时用户认可的交易身份（水量/支付方式/金额/水种）必须与此刻的档案一致。
        // 水种是出水口的业务身份——同一个口把水种从 8 改成 9，共键全都对得上，但用户买的已经不是
        // 他确认的那种水了；不核这一条，改水种就成了无痕换货。
        verifyFrozenSnapshot(order, outlet, session);
        // 运行可用性：与上面的共键判定同一批权威数据（同一次当前读的 device/outlet/fault），
        // 不再另读一份——判定用一个版本、落库用另一个版本，中间的改绑与迁站就是从那条缝里漏的。
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
        // ④ 使用人判定（CARD-MEMBER）：卡主直通；非卡主必须持有当前有效的成员授权。
        //    成员关系行必须 FOR UPDATE 锁定（uk_card_member_user 单行锁）——日限额没有独立统计表，
        //    这把锁是同成员同卡并发下单的唯一串行化点，撤销/改限额与下单也靠它互斥。
        //    无关用户、已撤销、未生效、已失效与不存在卡统一口径拒绝，不泄露他人卡与授权历史。
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
        // ⑥ 范围放行：与预检同一解析器（全仓唯一），空/非法 JSON 即默认拒绝。
        //    成员继承主卡范围（无独立成员 SCOPE_JSON），故成员与卡主走同一判定。
        //    P1-A：判定只吃事务内核验后的权威三元组；P1-C：真实提交被拒时落可靠审计。
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
     * 成员单日限额闸（⑦）：判定「当天已占用 + 本次计划量 <= DAY_LIMIT_ML」。
     * 占用口径与溢出语义见 {@link MemberDayLimitMath}；统计跨全部 DATA_STATUS 的取水订单，
     * 删单不得成为绕过限额的后门。
     */
    private void enforceMemberDayLimit(WsOrder order, WsCardMember member, String now) {
        long planMl;
        try {
            planMl = WaterBillingMath.requirePlanMl(order.getPlanMl());
        } catch (JbkException invalid) {
            throw new JbkException("计划水量非法，无法进行成员日限额校验");
        }
        // 必须用锁定读：普通 SELECT 在 REPEATABLE READ 下读的是等锁前的快照，
        // 拿到成员关系行锁后仍看不见前序并发事务刚提交的订单，限额会被并发突破。
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
     * P1-C 范围拒绝审计：仅在用户真实提交（本下单事务）内因 CARD_SCOPE_INVALID / CARD_SCOPE_DENIED
     * 被拒时落一条可靠领域事件。要点：
     * <ul>
     *   <li>recordReliableOnceIndependent（REQUIRES_NEW）独立提交——主事务随后回滚正是拒绝的
     *       业务结果，证据必须存活；用同事务的 recordReliableOnce 会连审计一起滚掉，拒绝成无痕事件；</li>
     *   <li>幂等键取既定确定性单号（ORDER_NO 由 userId+requestId 派生）：同一请求的重复强提交
     *       命中 {@code uk_domain_event_biz_key} 唯一键，数据库层保证最多一条、不重复刷
     *       （证据含 decidedAt，跨次重试报文不同，故撞键按已留痕处理、不做读回核验）；</li>
     *   <li>事件只含判定要素（actorUserId/cardId/权威三元组/拒绝码/判断时间），不存 SCOPE_JSON 原文；</li>
     *   <li>预检（eligibility）不经过本方法，天然零审计；卡/订单/流水/指令零业务副作用由整体回滚保证。</li>
     * </ul>
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
     * 冻结快照与当前档案的一致性核验（唯一解析实现在 {@link WaterOrderSnapshot}）。
     *
     * <p>核三件事：快照本身合法；订单落库的水量/支付方式/金额与快照自洽（防编排层装配漂移）；
     * 快照水种与当前锁定出水口的水种完全一致（防预检到扣款之间改水种）。任一不符整体回滚。</p>
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
        // S2 三方一致：Redis 会话报价 == 订单快照 == 锁内当前出水口。少核任何一边都留着一条缝——
        // 只核快照与档案，装配层就能拿别的会话装单；只核会话与快照，调价仍会照旧价扣款。
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
     * 报价漂移拒绝（S2）：稳定拒绝码 SCAN_QUOTE_CHANGED，与「快照本身非法」区分开——
     * 前者是价格/水种变了要用户重扫，后者是数据被篡改要运维查。
     *
     * <p>留痕走 recordReliableOnceIndependent：幂等键取确定性单号，用户连点多次也只落一条，
     * 不会把一次调价刷成一串事件；主事务随后回滚，证据仍独立存活。</p>
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
        // 共键错位是档案被改绑/停用或会话被篡改的信号，用 REQUIRES_NEW 独立留痕——
        // 主事务随后回滚，用 record 会连审计一起滚掉，错位就成了无痕事件。
        domainEventService.recordReliable(OpsEnum.EventType.ORDER_STATUS, order.getOrderNo(), null,
                "取水下单事务共键重核拒绝：" + reason);
        return new JbkException("设备信息已变化，请重新扫码（" + reason + "）");
    }

    @Override
    public int claimCommandSlot(Long orderId, Long commandId,
                                Long stationId, Long deviceId, Long outletId) {
        // 原子闸：单条条件 UPDATE，影响行数=1 才算抢到。谓词逐条都是安全边界，缺一条就有下发窗口：
        //   CMD_ID IS NULL —— 一个订单只绑定一条有效出水指令（铁律4 配套）；
        //   ORDER_TYPE=1 / ORDER_STATUS=2 —— 只有已支付的取水订单可下发，
        //     调用方读单到这一行之间订单可能已被并发转 6异常/7退款/4完成；
        //   三共键 —— 调用方判定所依据的档案必须仍是订单当前指向的档案，
        //     期间设备迁站或出水口改绑一律不发。
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
        // ② 锁序对齐 createWaterOrder（卡 → 订单）：本事务后续要写 ws_card（退差/退款补偿），
        // 若等到那时才取卡锁，锁序就是「订单 → 卡」，与下单事务的「卡 → 成员日限额订单范围(FOR UPDATE)」
        // 构成 ABBA——成员卡配了 DAY_LIMIT_ML 且同卡同成员当日已有订单在结算时必然互等。
        // 必须无条件前置：只在有退差的分支里锁，反向路径原封不动还在。
        WsCard lockedCard = lockCardForSettlement(routing);
        // ③ 订单当前读并锁定：等卡锁期间订单可能已被并发推进，全部结算口径
        //（planMl/orderAmount/payWay/packageSnap/cardId 与可结算态）必须取自锁内行，
        // 否则拿的是等锁之前的旧快照，而 CAS 只复核 ORDER_STATUS 一项，兜不住其余字段。
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
        // REQ-035 超量出水：设备报回的量超过计划量，账实不符，业务终态必须是「待人工核实」。
        // 此前按 min(actual, plan) 静默封顶后仍落 4已完成——超量在库里查不出、也没人处理。
        // 本包冻结的模拟口径：不追加扣款、不生成退款/退差流水、保留设备原始 actualMl，
        // 订单精确 CAS 到 6异常待补偿并留可靠审计。指令 result 可以是执行成功，
        // 那是设备侧事实；订单业务终态与它是两回事，不得混为一谈。
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
        // CARD-MEMBER：order.USER_ID 是实际使用人（可能是成员），退差必须回到同一张卡主的卡——
        // 卡归属条件用当前卡主，UPDATE_BY 记实际使用人；流水 USER_ID 仍写实际使用人。
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

        // E2E-08 分账挂点（任务书口径1/2）：终态=已完成且余额支付才产分账，基数=实扣（预扣-退差）。
        // 水量支付 ORDER_AMOUNT=0 不分账——其水费在充值环节结算（与 E2E-06 预付披露同口径）；
        // 异常(6)/零出水退款(7) 不分账。同事务插「待分账」行，完成回滚分账同灭。
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
     * 超量出水收口（REQ-035）：订单从可结算态精确 CAS 到 6异常待补偿，落设备原始 actualMl。
     *
     * <p>零资金动作——本包不新增补收、罚款或用户补偿规则，超出部分的计费口径属外部待确认项；
     * 在规则明确前，平台只负责把账实不符标出来交人工，不擅自替甲方定价。</p>
     *
     * <p>幂等：CAS 影响行数为 0 说明已被并发结算，直接跳过且不落审计，避免重投刷出多条证据。
     * {@code plan}/{@code actual} 均已过非负校验，差值不会溢出。</p>
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
     * 退差回补权益批次（E2E-04 包D-4）：卡加回多少，批次就补回多少。
     *
     * <p>只加卡不回补批次，这张卡的聚合值会永久高于批次剩余合计，多出来的那部分余额
     * 在下一次取水时凑不出批次额度，用户看得见却花不掉（{@link EntitlementLedger} 的不变式）。</p>
     *
     * <p>回补目标由本单的消费分摊键定位——退的是这一单的差额，就只还这一单扣过的批次，
     * 不去碰同卡别的消费。上线前的历史单没有分摊行，由台账按真实缺口并入不可退桶。</p>
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
     * 结算入口处无条件锁卡（{@code SELECT ... FOR UPDATE}），确立本事务的「卡 → 订单」锁序。
     *
     * <p>两个作用：① 与下单事务同向取锁，消除 ABBA；② 补偿 SQL 的归属条件取自锁内卡行，
     * 不再另做一次无锁读——同一事务里「无锁读卡主、有锁写卡」是两个时点的两份事实。
     * 锁定读跨全部 DATA_STATUS（见 TradeCardMapper），删除态必须锁到后显式拒绝，
     * 否则「已删除」在锁语义上等于「不存在」，并发恢复/删除窗口会漏判。</p>
     */
    private WsCard lockCardForSettlement(WsOrder order) {
        if (ObjectUtil.isNull(order.getCardId())) {
            return null;
        }
        WsCard card = tradeCardMapper.selectByIdForUpdate(order.getCardId());
        // 卡缺失/已删除不在此提前拒绝：零退差结算原本就不碰卡，提前抛会把这类单拦死在 2/3。
        // 拒绝时机仍留在真正要补偿的分支（requireLockedCardOwner），与既有语义逐字一致。
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
     * 从 PACKAGE_SNAP 读取下单单价快照（分/升）。解析规则唯一实现在 {@link WaterOrderSnapshot}；
     * 本方法只负责把「回退」这件事按结算口径留痕：缺失按既有产品 A 回退单价 0 并独立提交事件，
     * 数值存在但非法则由解析侧抛出，禁止全额退款掩盖篡改。
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
                // 幂等键（E2E-08 包A 补齐）：日对账维度2 按 CONSUME:<orderNo> 判「恰一条扣减流水」；
                // 存量行由 settlement-e2e08-a 迁移回填，此处保证新单同键（同事务插入，天然一单一条）
                .setBizIdempotencyKey("CONSUME:" + order.getOrderNo())
                .setFlowRemark("扫码取水 " + order.getOrderNo());
    }
}
