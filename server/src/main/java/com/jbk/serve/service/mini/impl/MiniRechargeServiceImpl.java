package com.jbk.serve.service.mini.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.product.WsPackageMapper;
import com.jbk.serve.service.settlement.IInviteService;
import com.jbk.serve.mapper.trade.RechargeIdentityMapper;
import com.jbk.serve.service.mini.IMiniPayStatusService;
import com.jbk.serve.service.mini.IMiniRechargeService;
import com.jbk.serve.service.mini.recharge.IRechargeCreateTx;
import com.jbk.serve.service.mini.recharge.IRechargePaySourceAdapter;
import com.jbk.serve.service.mini.recharge.RechargeLimits;
import com.jbk.serve.service.mini.recharge.RechargeOrderNo;
import com.jbk.serve.service.mini.recharge.RechargeExpiry;
import com.jbk.serve.service.mini.recharge.RechargePayExpire;
import com.jbk.serve.service.mini.card.CardEligibility;
import com.jbk.serve.service.mini.card.WaterCardScope;
import com.jbk.serve.service.mini.recharge.RechargeSnapshot;
import com.jbk.serve.service.user.IWsCardService;
import com.jbk.tool.data.mini.bo.MiniRechargeCreateBo;
import com.jbk.tool.data.mini.bo.MiniPayStatusBo;
import com.jbk.tool.data.mini.vo.MiniPayStatusVo;
import com.jbk.tool.data.mini.vo.MiniRechargeOrderVo;
import com.jbk.tool.data.product.po.WsPackage;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsPayment;
import com.jbk.tool.data.user.po.WsCard;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 充值订单创建编排（L2 契约 §4.2）。
 *
 * <p>固定顺序：①规范化输入并按会话用户派生订单号，跨全部 DATA_STATUS 查既有订单→命中即走幂等核验；
 * ②仅在确无既有订单时才读卡与套餐并校验；③以同一服务端 createTime 冻结快照与不可变 PAY_EXPIRE_TIME，
 * 再进入 order+payment 同事务创建；并发命中唯一键的一方重读后走同一核验。</p>
 *
 * <p><b>创单服务不模拟支付成功</b>：订单恒为 1 待支付、支付单恒为 1 待支付，不修改卡余额、不写资金流水。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MiniRechargeServiceImpl implements IMiniRechargeService {

    /** 订单类型(1340)：2 购卡充值。 */
    private static final int ORDER_TYPE_RECHARGE = 2;
    /** 订单状态(1341)：1 待支付。 */
    private static final int ORDER_STATUS_PENDING = 1;
    /** 支付方式(1346)：1 微信支付。 */
    private static final int PAY_WAY_WECHAT = 1;
    /** 卡状态(1332)：1 正常。 */
    private static final int CARD_STATUS_NORMAL = 1;
    private static final int CARD_STATUS_EXPIRED = 3;
    private static final int CARD_STATUS_CANCELLED = 4;
    /** 套餐状态(1330)：1 在售。 */
    private static final int PACKAGE_STATUS_ON_SALE = 1;
    /** 支付状态(1342)：1 待支付。 */
    private static final int PAY_STATUS_PENDING = 1;
    /** 支付状态(1342)：2 成功、4 已关闭。 */
    private static final int PAY_STATUS_SUCCESS = 2;
    private static final int PAY_STATUS_CLOSED = 4;
    /** 订单状态(1341)：4 已完成。 */
    private static final int ORDER_STATUS_FINISHED = 4;
    /** 精确状态矩阵允许幂等返回的当前 L2 状态。 */
    private static final Set<String> IDEMPOTENT_STATUS_CODES = Set.of(
            "WAITING_PAYMENT", "PAID_CREDIT_PENDING", "COMPLETED", "CLOSED", "RECONCILIATION_REQUIRED");
    private static final String CURRENCY_CNY = "CNY";

    private static final Pattern DECIMAL_ID = Pattern.compile("^[1-9]\\d*$");

    private final IInviteService inviteService;
    private final RechargeIdentityMapper identityMapper;
    private final WsPackageMapper wsPackageMapper;
    private final IWsCardService wsCardService;
    private final IRechargeCreateTx createTx;
    private final IRechargePaySourceAdapter paySourceAdapter;
    private final IMiniPayStatusService payStatusService;

    @Override
    public MiniRechargeOrderVo create(MiniRechargeCreateBo bo, Long userId) {
        if (userId == null) {
            throw new JbkException("会话异常，请重新登录");
        }
        // ① 规范化输入 + 派生订单号（不含日期/序列/缓存，跨重启稳定）。
        // 决策 A2/A7：cardId 缺省 = 首次购卡（purchase 态）；传值 = 已有卡充值（recharge 态）
        boolean purchase = StrUtil.isBlank(bo.getCardId());
        Long cardId = purchase ? null : decimalId(bo.getCardId(), "cardId");
        long packageId = decimalId(bo.getPackageId(), "packageId");
        String requestId = RechargeOrderNo.requireCanonicalUuid(bo.getRequestId());
        String orderNo = RechargeOrderNo.derive(userId, requestId);

        // 跨全部 DATA_STATUS 查既有订单：逻辑删除的订单也必须看见，否则同 requestId 会重复建单
        WsOrder existing = findExistingOrder(orderNo);
        if (existing != null) {
            return verifyIdempotent(existing, userId, cardId, packageId, requestId);
        }
        if (purchase) {
            return createPurchase(userId, packageId, requestId, orderNo);
        }
        return createRecharge(userId, cardId, packageId, requestId, orderNo);
    }

    /** 已有卡充值创单（L2-B，逻辑保持原样）。 */
    private MiniRechargeOrderVo createRecharge(Long userId, long cardId, long packageId,
                                               String requestId, String orderNo) {
        // ② 确无既有订单才读卡与套餐
        WsCard card = loadUsableCard(cardId, userId);
        // 赠卡判据=带有效期且无 ISSUE_ORDER_ID 订单锚（赠卡是唯一无订单锚的发卡路径）。
        // 不能只看 EXPIRE_TIME：有限期付费卡（有订单锚）历史上可售，其续期充值走
        // requireSameExpiryKind 的同类校验（D-205），按赠卡整类拒绝会误伤且文案失真。
        //
        // D-416（2026-08-06 甲方确认）：赠卡不再一刀切拒绝——
        //   有效期内且权益未耗尽：仍拒绝（付费余额会被到期日绑架，原口径不变）；
        //   权益用完或自然到期 + 用户无其他付费卡：放行并标记「转正」，入账即转永久付费卡；
        //   权益用完或自然到期 + 已有付费卡：拒绝（该走合并动线，不产生第二张付费卡，D-417）。
        boolean giftCard = CardEligibility.isGiftCard(card);
        boolean promote = false;
        if (giftCard) {
            long balanceFen = card.getBalanceAmount() == null ? 0L : card.getBalanceAmount();
            long balanceMl = card.getBalanceMl() == null ? 0L : card.getBalanceMl();
            boolean drained = balanceFen == 0L && balanceMl == 0L;
            boolean expired = RechargeExpiry.naturallyExpired(card.getExpireTime(), DateUtils.time());
            if (!drained && !expired) {
                throw new JbkException("活动赠卡在有效期内暂不支持充值，权益用完或到期后可充值转为正式水卡");
            }
            if (hasOtherPaidCard(userId, card.getId())) {
                throw new JbkException("已有正式水卡，请在水卡详情中将赠卡合并入正式水卡");
            }
            promote = true;
        }
        WsPackage pkg = loadOnSalePackage(packageId);
        RechargeLimits.validatePackage(pkg);

        // ③ 同一服务端时刻冻结快照与付款截止时间
        String createTime = DateUtils.time();
        WaterCardScope cardScope = WaterCardScope.normalize(card.getScopeJson(), "水卡");
        WaterCardScope pkgScope = requireCompatibleScope(pkg, cardScope);
        if (!promote) {
            // 转正单豁免同类校验：永久套餐 × 有限赠卡正是转正的定义形态
            requireSameExpiryKind(card, pkg);
        }
        // 转正单付款窗按永久卡口径：不被赠卡旧到期日钳制（到期卡的旧钳制会算出过去时间，创单即死单）
        String payExpireTime = RechargePayExpire.compute(createTime, promote ? null : card.getExpireTime());
        String snapshot = RechargeSnapshot.build(requestId, pkg, card, cardScope, pkgScope, createTime, promote);

        WsOrder order = new WsOrder();
        order.setOrderNo(orderNo);
        order.setOrderType(ORDER_TYPE_RECHARGE);
        order.setUserId(userId);
        // E2E-08 归因快照：下单时刻的推荐人（绑定前恒 NULL，绑定不回溯）
        order.setReferrerUserId(inviteService.referrerSnapshotOf(userId));
        order.setCardId(cardId);
        order.setPackageId(packageId);
        order.setPackageSnap(snapshot);
        order.setOrderAmount(pkg.getPayAmount());
        order.setPayWay(PAY_WAY_WECHAT);
        order.setOrderStatus(ORDER_STATUS_PENDING);
        order.setDataStatus(0);
        order.setCreateBy(userId);
        order.setCreateTime(createTime);
        order.setUpdateBy(userId);
        order.setUpdateTime(createTime);

        try {
            WsOrder saved = createTx.create(order, payExpireTime, paySourceAdapter.currentSource());
            return toVo(saved, payExpireTime, pkg.getPackageName(), false, PAY_STATUS_PENDING);
        } catch (DuplicateKeyException e) {
            // 并发下另一方先插入：重读后走同一幂等核验，绝不把 DuplicateKey 当成功
            WsOrder concurrent = findExistingOrder(orderNo);
            if (concurrent == null) {
                throw new JbkException("充值订单创建冲突，请重试");
            }
            return verifyIdempotent(concurrent, userId, cardId, packageId, requestId);
        }
    }

    /**
     * 首次购卡创单（L2-A，决策 A1/A2/A4/A6）：只建 order/payment/快照，
     * {@code CARD_ID=NULL}——不预建"待支付水卡"，卡在支付成功后的发卡事务里才诞生（决策 A2）。
     */
    private MiniRechargeOrderVo createPurchase(Long userId, long packageId,
                                               String requestId, String orderNo) {
        // 决策 A4：存在任意 DATA_STATUS=0 的卡（无论正常/冻结/过期/注销）即不得进入首次购卡。
        // 冻结、注销的卡代表用户与既有卡的关系待厘清，绕开它们发新卡会让一人多首卡失控。
        if (identityMapper.selectCountLiveCardsByUser(userId) > 0) {
            throw new JbkException("已有水卡，请直接充值");
        }
        WsPackage pkg = loadOnSalePackage(packageId);
        RechargeLimits.validatePackage(pkg);
        // 决策 A6：SCOPE_JSON 空 = 未配置默认拒绝——绝不自动扩为 all；非空必须能通过规范化
        if (StrUtil.isBlank(pkg.getScopeJson())) {
            throw new JbkException("套餐未配置可用范围");
        }
        WaterCardScope pkgScope = WaterCardScope.normalize(pkg.getScopeJson(), "套餐");

        // ③ 同一服务端时刻冻结快照与付款截止时间。
        // 决策 A1：首次购卡没有既有卡有效期可截短，PAY_EXPIRE_TIME 固定 createTime+30min
        String createTime = DateUtils.time();
        String payExpireTime = RechargePayExpire.compute(createTime, null);
        String snapshot = RechargeSnapshot.buildForPurchase(requestId, pkg, pkgScope, createTime);

        WsOrder order = new WsOrder();
        order.setOrderNo(orderNo);
        order.setOrderType(ORDER_TYPE_RECHARGE);
        order.setUserId(userId);
        // E2E-08 归因快照：下单时刻的推荐人（绑定前恒 NULL，绑定不回溯）
        order.setReferrerUserId(inviteService.referrerSnapshotOf(userId));
        // 决策 A2：不预建卡，CARD_ID 由发卡事务在支付成功后 CAS 回填
        order.setCardId(null);
        order.setPackageId(packageId);
        order.setPackageSnap(snapshot);
        order.setOrderAmount(pkg.getPayAmount());
        order.setPayWay(PAY_WAY_WECHAT);
        order.setOrderStatus(ORDER_STATUS_PENDING);
        order.setDataStatus(0);
        order.setCreateBy(userId);
        order.setCreateTime(createTime);
        order.setUpdateBy(userId);
        order.setUpdateTime(createTime);

        try {
            WsOrder saved = createTx.create(order, payExpireTime, paySourceAdapter.currentSource());
            return toVo(saved, payExpireTime, pkg.getPackageName(), false, PAY_STATUS_PENDING);
        } catch (DuplicateKeyException e) {
            // 并发下另一方先插入：重读后走同一幂等核验，绝不把 DuplicateKey 当成功
            WsOrder concurrent = findExistingOrder(orderNo);
            if (concurrent == null) {
                throw new JbkException("充值订单创建冲突，请重试");
            }
            return verifyIdempotent(concurrent, userId, null, packageId, requestId);
        }
    }

    // ---------------------------------------------------------------------

    /** 按订单号跨全部 DATA_STATUS 查唯一订单；>1 属身份污染，拒绝。 */
    private WsOrder findExistingOrder(String orderNo) {
        List<WsOrder> rows = identityMapper.selectOrdersByOrderNoIncludingDeleted(orderNo);
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        if (rows.size() > 1) {
            throw new JbkException("订单号数据异常，请联系客服");
        }
        return rows.get(0);
    }

    /**
     * 幂等核验（§4.2）。全部一致才返回原订单；任一错位一律拒绝且零副作用，
     * 不因套餐后来改价/下架而重建替代订单，也不重写历史快照。
     *
     * @param cardId {@code null} = 本次请求是首次购卡（purchase 态）
     */
    private MiniRechargeOrderVo verifyIdempotent(WsOrder order, Long userId, Long cardId,
                                                 long packageId, String requestId) {
        // 哈希截断碰撞到其他用户：必须告警并拒绝，绝不能当作幂等成功
        if (!ObjectUtil.equals(order.getUserId(), userId)) {
            log.error("充值订单号碰撞：orderNo={} 归属 userId={} 但当前会话 userId={}",
                    order.getOrderNo(), order.getUserId(), userId);
            throw new JbkException("订单号冲突，请重新发起充值");
        }
        if (!ObjectUtil.equals(order.getDataStatus(), 0)) {
            // 已作废订单不复活：要求换新的 requestId 重新发起
            throw new JbkException("原订单已作废，请重新发起充值");
        }
        if (!ObjectUtil.equals(order.getOrderType(), ORDER_TYPE_RECHARGE)) {
            throw new JbkException("订单类型不符，拒绝");
        }
        RechargeSnapshot.Parsed snap = RechargeSnapshot.parse(order.getPackageSnap());
        // 模式判定只能看快照：已完成的购卡单 CARD_ID 已被发卡事务回填，
        // 用 order.CARD_ID 是否为空判定会把「购卡单完成后带 cardId 重放」误认成充值命中。
        boolean hitPurchase = snap.purchaseMode() != null;
        if (hitPurchase != (cardId == null)) {
            // 决策 A7：同一 requestId 是同一次购买动作，不允许在购卡/充值两种模式间来回切换
            throw new JbkException("同一 requestId 不可切换购卡/充值模式");
        }
        if (!hitPurchase && !ObjectUtil.equals(order.getCardId(), cardId)) {
            // 同 requestId 改 cardId：拒绝且零副作用（purchase 命中不比对 cardId——本来就是 NULL）
            throw new JbkException("同一 requestId 不可更换水卡或套餐");
        }
        if (!ObjectUtil.equals(order.getPackageId(), packageId)) {
            // 同 requestId 改 packageId：拒绝且零副作用
            throw new JbkException("同一 requestId 不可更换水卡或套餐");
        }
        if (!requestId.equals(snap.requestId())) {
            throw new JbkException("订单快照与 requestId 不一致，拒绝");
        }
        if (!String.valueOf(packageId).equals(snap.packageId())) {
            throw new JbkException("订单快照与套餐不一致，拒绝");
        }
        if (!ObjectUtil.equals(order.getOrderAmount(), snap.payAmount())) {
            throw new JbkException("订单金额与快照不一致，拒绝");
        }
        if (!ObjectUtil.equals(order.getPayWay(), PAY_WAY_WECHAT)) {
            throw new JbkException("订单支付方式不符，拒绝");
        }

        // payment 恰好一条，且共键与不可变截止时间自洽
        List<WsPayment> payments = identityMapper.selectPaymentsByOrderIdIncludingDeleted(order.getId());
        if (payments == null || payments.size() != 1) {
            // 缺 payment 不自动修复，多条属污染
            throw new JbkException("订单支付单异常，请联系客服");
        }
        WsPayment payment = payments.get(0);
        if (!ObjectUtil.equals(payment.getDataStatus(), 0)
                || !ObjectUtil.equals(payment.getOrderId(), order.getId())
                || !StrUtil.equals(payment.getOrderNo(), order.getOrderNo())
                || !ObjectUtil.equals(payment.getPayAmount(), order.getOrderAmount())) {
            throw JbkException.internal("支付单与订单共键错位，拒绝");
        }
        if (!ObjectUtil.equals(payment.getPaySource(), paySourceAdapter.currentSource())) {
            throw JbkException.internal("支付单来源与当前适配器不一致，拒绝");
        }
        requirePaymentEvidence(order, payment);
        // §4.3：capturedTime 必须等于订单 createTime。缺了这一条，下面的 PAY_EXPIRE_TIME 校验就是自指的
        // （expected 与实测值都源自同一份快照），篡改快照即可让两者一致而无人发现。
        if (!StrUtil.equals(snap.capturedTime(), order.getCreateTime())) {
            throw new JbkException("订单快照采集时间与订单创建时间不一致，拒绝");
        }
        // L2-B 资格快照的卡状态固定为通过创单校验的 1；其它值说明快照被改写。
        // purchase 快照没有该字段（创单时无卡），其发卡资格（userHadNoCard=true）已由 parse 强校验。
        if (!hitPurchase) {
            // 审计 P1-3：与 parse 同规则——普通单只受状态 1，转正单允许 1 或自然过期 3
            boolean legal = ObjectUtil.equals(snap.cardStatusAtCreate(), RechargeSnapshot.CARD_STATUS_AT_CREATE)
                    || (snap.promoteToPermanent() && ObjectUtil.equals(snap.cardStatusAtCreate(),
                            RechargeSnapshot.CARD_STATUS_EXPIRED_AT_CREATE));
            if (!legal) {
                throw new JbkException("订单快照资格状态非法，拒绝");
            }
        }
        // 套餐范围非空时必须仍与卡范围语义精确相等（与创建路径同一判据）
        if (snap.packageScope() != null && !snap.packageScope().sameAuthorityAs(snap.cardScope())) {
            throw new JbkException("订单快照范围错位，拒绝");
        }
        // PAY_EXPIRE_TIME 必须与资格快照按冻结算法重算的结果精确相等（证明创建后未被改写）。
        // 审计 P1-3：转正单与创单路径同一公式（promote→按永久口径），否则过期赠卡转正单
        // 的合法重放会被误判为「付款截止被改写」而拒绝。
        String expected = RechargePayExpire.compute(snap.capturedTime(),
                snap.promoteToPermanent() ? null : snap.expireTimeAtCreate());
        if (!StrUtil.equals(payment.getPayExpireTime(), expected)) {
            throw new JbkException("付款截止时间与资格快照不一致，拒绝");
        }
        // 共键与单字段证据通过后，复用 pay-status 的事件、流水和精确状态矩阵。
        // 禁止在创单服务再维护一份较弱的状态表，避免同一污染数据在两个入口得到相反结论。
        MiniPayStatusBo statusBo = new MiniPayStatusBo();
        statusBo.setOrderNo(order.getOrderNo());
        MiniPayStatusVo status = payStatusService.query(statusBo, userId);
        if (!IDEMPOTENT_STATUS_CODES.contains(status.getPayStatusCode())
                || !ObjectUtil.equals(status.getOrderStatus(), order.getOrderStatus())
                || !ObjectUtil.equals(status.getPayStatus(), payment.getPayStatus())) {
            throw JbkException.internal("订单支付状态不自洽，拒绝幂等返回");
        }
        return toVo(order, payment.getPayExpireTime(), snap.packageName(), true, payment.getPayStatus());
    }

    /**
     * payment 自身的不可变字段与状态证据。跨表状态组合、事件和流水由统一 pay-status 矩阵判定。
     */
    private void requirePaymentEvidence(WsOrder order, WsPayment payment) {
        if (!CURRENCY_CNY.equals(payment.getCurrency())
                || !StrUtil.equals(payment.getCreateTime(), order.getCreateTime())) {
            throw new JbkException("支付单币种或创建时间异常，拒绝");
        }
        RechargePayExpire.parse(payment.getPayExpireTime(), "付款截止");
        Integer payStatus = payment.getPayStatus();
        if (ObjectUtil.equals(payStatus, PAY_STATUS_PENDING)) {
            if (StrUtil.isNotBlank(payment.getTransactionId())
                    || StrUtil.isNotBlank(payment.getPaySuccessTime())
                    || StrUtil.isNotBlank(payment.getCallbackTime())) {
                throw new JbkException("待支付单含成功证据，拒绝");
            }
        } else if (ObjectUtil.equals(payStatus, PAY_STATUS_SUCCESS)) {
            if (StrUtil.isBlank(payment.getTransactionId())
                    || StrUtil.isBlank(payment.getPaySuccessTime())
                    || StrUtil.isBlank(payment.getCallbackTime())) {
                throw new JbkException("成功支付单缺少交易或时间证据，拒绝");
            }
            RechargePayExpire.parse(payment.getPaySuccessTime(), "支付成功");
            RechargePayExpire.parse(payment.getCallbackTime(), "支付回调");
        } else if (ObjectUtil.equals(payStatus, PAY_STATUS_CLOSED)) {
            if (StrUtil.isNotBlank(payment.getTransactionId()) || StrUtil.isNotBlank(payment.getPaySuccessTime())) {
                throw new JbkException("已关闭支付单含成功证据，拒绝");
            }
        } else {
            throw new JbkException("支付单状态未登记，拒绝");
        }
        if (ObjectUtil.equals(order.getOrderStatus(), ORDER_STATUS_FINISHED)) {
            if (StrUtil.isBlank(order.getFinishTime())) {
                throw new JbkException("已完成订单缺少完成时间，拒绝");
            }
            RechargePayExpire.parse(order.getFinishTime(), "订单完成");
        } else if (StrUtil.isNotBlank(order.getFinishTime())) {
            throw new JbkException("非完成订单含完成时间，拒绝");
        }
    }

    /** 目标卡：本人、未删除、状态正常。归属写进 WHERE，别人的卡查不出来。 */
    private WsCard loadUsableCard(long cardId, Long userId) {
        WsCard card = wsCardService.getOne(Wrappers.lambdaQuery(WsCard.class)
                .eq(WsCard::getId, cardId)
                .eq(WsCard::getUserId, userId)
                .last("LIMIT 1"));
        if (ObjectUtil.isNull(card)) {
            throw new JbkException("水卡不存在或无权访问");
        }
        boolean naturallyExpiredGift = ObjectUtil.equals(card.getCardStatus(), CARD_STATUS_EXPIRED)
                && card.getIssueOrderId() == null
                && RechargeExpiry.naturallyExpired(card.getExpireTime(), DateUtils.time());
        if (!ObjectUtil.equals(card.getCardStatus(), CARD_STATUS_NORMAL) && !naturallyExpiredGift) {
            // 自然过期的赠卡放行到创单层，由 D-416 转正闸决定去留；
            // 入账链对「状态 3 且自然过期」本就接受（RechargeCreditTxImpl 步骤 4），上下呼应
            throw new JbkException("水卡当前状态不可充值");
        }
        // 永久卡的唯一持久语义是 SQL NULL。空串曾被当作永久卡放行，但事务 B 的 CAS 只接受 NULL，
        // 会形成“允许收款、入账必失败”的状态。所有非 NULL 值必须是严格合法的有限期时间。
        if (card.getExpireTime() != null) {
            if (card.getExpireTime().isEmpty()) {
                throw new JbkException("水卡有效期数据异常，拒绝充值");
            }
            RechargePayExpire.parse(card.getExpireTime(), "水卡到期");
        }
        return card;
    }

    private WsPackage loadOnSalePackage(long packageId) {
        WsPackage pkg = wsPackageMapper.selectOne(Wrappers.lambdaQuery(WsPackage.class)
                .eq(WsPackage::getId, packageId)
                .eq(WsPackage::getPackageStatus, PACKAGE_STATUS_ON_SALE)
                .last("LIMIT 1"));
        if (ObjectUtil.isNull(pkg)) {
            throw new JbkException("套餐不存在或已下架");
        }
        return pkg;
    }

    /**
     * 套餐范围为空时不增加新约束；非空时必须与卡范围规范化后语义精确相等。
     * 不取并集、不取交集、不做字符串表面相等。
     */
    private WaterCardScope requireCompatibleScope(WsPackage pkg, WaterCardScope cardScope) {
        if (StrUtil.isBlank(pkg.getScopeJson())) {
            return null;
        }
        WaterCardScope pkgScope = WaterCardScope.normalize(pkg.getScopeJson(), "套餐");
        if (!pkgScope.sameAuthorityAs(cardScope)) {
            throw new JbkException("套餐可用范围与水卡范围不一致，拒绝充值");
        }
        return pkgScope;
    }

    /**
     * 是否占用「一人一张付费卡」名额的其他卡（D-416/D-417，审计 P1-2 口径）：
     * 与 {@code CardEligibility#occupiesPaidSlot} 同构——冻结/过期/<b>注销</b>
     * (CARD_STATUS 1/2/3/4) 全部占名额，只排逻辑删除与真赠卡（apply 引用 CardEligibility.SQL_NOT_GIFT 唯一谓词）。
     */
    private boolean hasOtherPaidCard(Long userId, Long exceptCardId) {
        return wsCardService.count(Wrappers.lambdaQuery(WsCard.class)
                .eq(WsCard::getUserId, userId)
                .ne(WsCard::getId, exceptCardId)
                .apply(CardEligibility.SQL_NOT_GIFT)) > 0;
    }

    /**
     * 赠卡闸之后到这里的是永久卡或有限期付费卡（有订单锚）。本检查拦「期限种类错配」：
     * 永久卡买有限套餐=引入到期日或送出未兑现天数；有限期卡买永久套餐=静默没收续期语义。
     * 同类才放行（有限期付费卡续充有限套餐即 D-205 续期路径）。
     */
    private void requireSameExpiryKind(WsCard card, WsPackage pkg) {
        boolean cardFinite = StrUtil.isNotBlank(card.getExpireTime());
        boolean pkgFinite = pkg.getExpireDays() != null;
        if (cardFinite != pkgFinite) {
            throw new JbkException("有限期与永久类型不匹配，拒绝充值");
        }
    }

    private long decimalId(String raw, String field) {
        if (raw == null || !DECIMAL_ID.matcher(raw.trim()).matches()) {
            throw new JbkException(field + " 必须是正十进制字符串");
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new JbkException(field + " 越界");
        }
    }

    private MiniRechargeOrderVo toVo(WsOrder order, String payExpireTime, String packageName,
                                     boolean hit, Integer payStatus) {
        MiniRechargeOrderVo vo = new MiniRechargeOrderVo();
        vo.setOrderNo(order.getOrderNo());
        vo.setOrderStatus(order.getOrderStatus());
        // 幂等命中时必须回传支付单**真实**状态：硬编码 1 会让已支付订单返回 orderStatus=4 + payStatus=1 的自相矛盾响应
        vo.setPayStatus(payStatus);
        vo.setOrderAmountFen(order.getOrderAmount());
        vo.setPayExpireTime(payExpireTime);
        vo.setPackageName(packageName);
        vo.setCardId(order.getCardId());
        vo.setPackageId(order.getPackageId());
        vo.setCreateTime(order.getCreateTime());
        vo.setIdempotentHit(hit);
        return vo;
    }
}
