package com.jbk.serve.service.trade.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.device.WsDeviceMapper;
import com.jbk.serve.mapper.device.WsDeviceOutletMapper;
import com.jbk.serve.mapper.device.WsQrcodeMapper;
import com.jbk.serve.mapper.station.WsStationMapper;
import com.jbk.serve.mapper.trade.TradeCardMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.mapper.user.WsCardMemberMapper;
import com.jbk.serve.service.mini.card.CardMemberRule;
import com.jbk.serve.service.mini.card.WaterCardScope;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.serve.service.trade.ITradeOrderTxService;
import com.jbk.serve.service.trade.MemberDayLimitMath;
import com.jbk.serve.service.trade.WaterBillingMath;
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

import java.math.BigDecimal;
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
    private WsDeviceMapper deviceMapper;
    @Autowired
    private WsDeviceOutletMapper outletMapper;
    @Autowired
    private WsStationMapper stationMapper;
    @Autowired
    private IWsDomainEventService domainEventService;

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
        VerifiedWaterResource verified = verifyArchiveCoKeys(order, session);

        // ③~⑦ SELECT ... FOR UPDATE 锁卡后按固定顺序校验归属（卡主或有效成员）/状态/范围/日限额
        //（失败即整体回滚，零副作用）
        WsCard lockedCard = verifyLockedCard(order, verified, now);
        Long cardOwnerUserId = lockedCard.getUserId();

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
        walletFlowMapper.insert(buildConsumeFlow(order, after, byBalance));
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
        // selectById 走 @TableLogic：逻辑删除档案 = 查不到 = 拒绝（未删除校验内含于此）。
        WsQrcode qrcode = qrcodeMapper.selectById(session.getQrcodeId());
        WsDevice device = deviceMapper.selectById(session.getDeviceId());
        WsDeviceOutlet outlet = outletMapper.selectById(session.getOutletId());
        if (ObjectUtil.isNull(qrcode) || ObjectUtil.isNull(device) || ObjectUtil.isNull(outlet)) {
            throw coKeyRejected(order, "二维码/设备/出水口档案缺失或已删除");
        }
        WsStation station = stationMapper.selectById(device.getStationId());
        if (ObjectUtil.isNull(station)) {
            throw coKeyRejected(order, "设备所属水站档案缺失或已删除");
        }
        // 档案状态可用（1正常 2禁用）；设备无档案停用列，其瞬时可用性由编排层 eligibility 复验。
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

    private JbkException coKeyRejected(WsOrder order, String reason) {
        // 共键错位是档案被改绑/停用或会话被篡改的信号，用 REQUIRES_NEW 独立留痕——
        // 主事务随后回滚，用 record 会连审计一起滚掉，错位就成了无痕事件。
        domainEventService.recordReliable(OpsEnum.EventType.ORDER_STATUS, order.getOrderNo(), null,
                "取水下单事务共键重核拒绝：" + reason);
        return new JbkException("设备信息已变化，请重新扫码（" + reason + "）");
    }

    @Override
    public int claimCommandSlot(Long orderId, Long commandId) {
        // 原子闸：仅当 CMD_ID 仍为空时抢占，保证一个订单只绑定一条有效出水指令（单条条件 UPDATE）
        return wsOrderMapper.update(null, Wrappers.lambdaUpdate(WsOrder.class)
                .set(WsOrder::getCmdId, commandId)
                .set(WsOrder::getUpdateTime, DateUtils.time())
                .eq(WsOrder::getId, orderId)
                .isNull(WsOrder::getCmdId));
    }

    @Override
    public int onCommandAcked(Long orderId) {
        // 条件 UPDATE：仅 2已支付 → 3出水中；已推进/非 2 态 affected==0 不覆盖（幂等）
        return wsOrderMapper.update(null, Wrappers.lambdaUpdate(WsOrder.class)
                .set(WsOrder::getOrderStatus, TradeEnum.OrderStatus.DISPENSING.getValue())
                .set(WsOrder::getUpdateTime, DateUtils.time())
                .eq(WsOrder::getId, orderId)
                .eq(WsOrder::getOrderStatus, TradeEnum.OrderStatus.PAID.getValue()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean settleWaterOrder(Long orderId, boolean success, Long actualMl) {
        WsOrder order = wsOrderMapper.selectById(orderId);
        if (order == null) {
            return false;
        }
        Integer st = order.getOrderStatus();
        boolean settleable = ObjectUtil.equal(st, TradeEnum.OrderStatus.PAID.getValue())
                || ObjectUtil.equal(st, TradeEnum.OrderStatus.DISPENSING.getValue());
        if (!settleable) {
            // 幂等：已结算（4/6/7/8）或非可结算态 → 跳过不重复补偿
            return false;
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
            Long ownerUserId = requireCardOwnerForCompensation(order);
            if (tradeCardMapper.compensateBalance(order.getCardId(), refundFen, ownerUserId, order.getUserId(), now) != 1) {
                throw new JbkException("退差补偿入账失败（余额）");
            }
            WsCard after = tradeCardMapper.selectById(order.getCardId());
            walletFlowMapper.insert(buildCompensateFlow(order, after, refundFen, 0L, now));
        } else if (!byBalance && refundMl > 0) {
            Long ownerUserId = requireCardOwnerForCompensation(order);
            if (tradeCardMapper.compensateMl(order.getCardId(), refundMl, ownerUserId, order.getUserId(), now) != 1) {
                throw new JbkException("退差补偿入账失败（水量）");
            }
            WsCard after = tradeCardMapper.selectById(order.getCardId());
            walletFlowMapper.insert(buildCompensateFlow(order, after, 0L, refundMl, now));
        }
        return true;
    }

    /**
     * 结算补偿前读取当前卡主（{@code ws_card.USER_ID} 恒为卡主）：补偿 SQL 的归属条件必须显式给出，
     * 卡缺失/已删除时抛出并整体回滚（selectById 走 {@code @TableLogic}，删除卡=查不到=拒绝补偿）。
     */
    private Long requireCardOwnerForCompensation(WsOrder order) {
        WsCard card = tradeCardMapper.selectById(order.getCardId());
        if (ObjectUtil.isNull(card)) {
            throw new JbkException("退差补偿入账失败（水卡不存在或已删除）");
        }
        return card.getUserId();
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
     * 从 PACKAGE_SNAP 读取下单单价快照（分/升）。缺失或 JSON 结构损坏按既有产品 A 回退单价 0，
     * 但必须先独立提交可靠领域事件。数值存在但为负数、小数、越界或溢出时 fail-closed，禁止全额退款掩盖篡改。
     */
    private UnitPriceSnapshot parseUnitPriceFromSnap(WsOrder order) {
        if (StrUtil.isBlank(order.getPackageSnap())) {
            recordPriceFallback(order, "结算单价快照缺失");
            return UnitPriceSnapshot.fallbackValue();
        }
        JSONObject snapshot;
        try {
            snapshot = JSONUtil.parseObj(order.getPackageSnap());
        } catch (Exception e) {
            recordPriceFallback(order, "结算单价快照不是合法 JSON 对象");
            return UnitPriceSnapshot.fallbackValue();
        }
        Object raw = snapshot.get("unitPriceFenPerLiter");
        if (raw == null) {
            recordPriceFallback(order, "结算单价字段缺失");
            return UnitPriceSnapshot.fallbackValue();
        }
        try {
            BigDecimal decimal;
            if (raw instanceof Number) {
                decimal = new BigDecimal(raw.toString());
            } else if (raw instanceof String && ((String) raw).matches("^(?:0|[1-9]\\d*)$")) {
                decimal = new BigDecimal((String) raw);
            } else {
                throw new ArithmeticException("单价不是规范整数");
            }
            if (decimal.stripTrailingZeros().scale() > 0) {
                throw new ArithmeticException("单价包含小数");
            }
            return UnitPriceSnapshot.valid(WaterBillingMath.requireUnitPrice(decimal.intValueExact()));
        } catch (ArithmeticException | JbkException e) {
            String reason = e instanceof JbkException ? ((JbkException) e).getMsg() : e.getMessage();
            throw settlementRejected(order, "结算单价快照数值非法：" + reason);
        }
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
                .setFlowRemark("扫码取水 " + order.getOrderNo());
    }
}
