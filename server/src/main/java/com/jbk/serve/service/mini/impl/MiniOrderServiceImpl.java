package com.jbk.serve.service.mini.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jbk.serve.mapper.device.WsCommandMapper;
import com.jbk.serve.service.settlement.IInviteService;
import com.jbk.serve.mapper.device.WsDeviceMapper;
import com.jbk.serve.mapper.device.WsDeviceOutletMapper;
import com.jbk.serve.mapper.station.WsStationMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.service.device.IWsCommandService;
import com.jbk.serve.service.mini.IMiniDeviceService;
import com.jbk.serve.service.mini.IMiniOrderService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.serve.service.trade.ITradeOrderTxService;
import com.jbk.serve.service.trade.WaterBillingMath;
import com.jbk.tool.consts.mini.MiniRejectCode;
import com.jbk.serve.service.trade.WaterOrderSnapshot;
import com.jbk.tool.config.system.redis.utils.RedisUtils;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.device.po.WsCommand;
import com.jbk.tool.data.device.po.WsDevice;
import com.jbk.tool.data.device.po.WsDeviceOutlet;
import com.jbk.tool.data.mini.vo.ScanSessionInfo;
import com.jbk.tool.data.mini.vo.WaterEligibilityVo;
import com.jbk.tool.data.station.po.WsStation;
import com.jbk.tool.data.trade.bo.CreateWaterOrderBo;
import com.jbk.tool.data.trade.bo.MiniOrderDetailBo;
import com.jbk.tool.data.trade.bo.MiniOrderQueryBo;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsWalletFlow;
import com.jbk.tool.data.trade.vo.OrderDetailVo;
import com.jbk.tool.data.trade.vo.OrderItemVo;
import com.jbk.tool.data.trade.vo.OrderTraceNodeVo;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.jbk.tool.data.trade.po.WsPayment;
import com.jbk.tool.data.trade.vo.MiniRechargeDetailVo;
import com.jbk.serve.service.mini.recharge.RechargeDetailVerifier;

/**
 * 小程序 C 端订单服务实现（L1b）。
 * <p>编排层：幂等（requestId=scanSessionId + 确定性 ORDER_NO + uk_order_no 兜底 + M1 归属校验）、
 * 下单前复验（设备可用性 + 水种一致）、金额取整（v1.3：payWay2 ceil 到分，payWay3 金额0）；
 * 资金原子扣减/落库/流水在 {@link ITradeOrderTxService} 单事务内完成，本层不直接写卡。</p>
 *
 * @author dakang
 * @since 2026-07-19
 */
@Slf4j
@Service
public class MiniOrderServiceImpl implements IMiniOrderService {

    /** 幂等缓存：用户 + 请求(scanSessionId) → 已生成订单号；数据库唯一订单号才是最终幂等依据。 */
    private static final String ORDER_REQ_PREFIX = "order:req:";
    private static final long ORDER_REQ_TTL = 24 * 60 * 60L;
    private static final String REQUEST_PARAMETER_CONFLICT = "同一请求参数已发生变化，请勿重复提交";
    private static final String ORDER_IDEMPOTENCY_DATA_INVALID = "既有订单幂等数据异常，请联系客服处理";
    private static final String ORDER_NO_COLLISION = "订单号生成冲突，请重新扫码下单";

    @Autowired
    private IInviteService inviteService;
    @Autowired
    private IMiniDeviceService miniDeviceService;
    @Autowired
    private ITradeOrderTxService tradeOrderTxService;
    @Autowired
    private IWsCommandService commandService;
    @Autowired
    private WsOrderMapper wsOrderMapper;
    @Autowired
    private WsCommandMapper commandMapper;
    @Autowired
    private WsWalletFlowMapper walletFlowMapper;
    @Autowired
    private com.jbk.serve.mapper.trade.RechargeIdentityMapper rechargeIdentityMapper;
    @Autowired
    private RechargeDetailVerifier rechargeDetailVerifier;
    @Autowired
    private com.jbk.serve.mapper.trade.TradeCardMapper tradeCardMapper;
    @Autowired
    private com.jbk.serve.mapper.delivery.WsDeliveryTaskMapper deliveryTaskMapper;
    @Autowired
    private com.jbk.serve.mapper.delivery.WsDeliveryAppealMapper deliveryAppealMapper;
    @Autowired
    private WsDeviceOutletMapper outletMapper;
    @Autowired
    private WsDeviceMapper deviceMapper;
    @Autowired
    private WsStationMapper stationMapper;
    @Autowired
    private IWsDomainEventService domainEventService;

    @Resource(name = "redisTemplate1")
    private RedisTemplate<String, Object> redis;

    @Override
    public OrderDetailVo createWaterOrder(CreateWaterOrderBo bo, Long userId) {
        int payWay = bo.getPayWay();
        if (ObjectUtil.equal(payWay, TradeEnum.PayWay.WECHAT.getValue())) {
            throw new JbkException("微信支付暂未开通，请使用水卡余额或水量支付");
        }
        if (ObjectUtil.notEqual(payWay, TradeEnum.PayWay.CARD_BALANCE.getValue())
                && ObjectUtil.notEqual(payWay, TradeEnum.PayWay.CARD_ML.getValue())) {
            throw new JbkException("不支持的支付方式");
        }
        String requestId = bo.getScanSessionId();
        String orderNo = buildOrderNo(userId, requestId);
        String cacheKey = buildRequestCacheKey(userId, requestId);

        // Redis 仅作提示，不作为幂等事实源。无论缓存命中、丢失或进程重启，都先按确定性单号查库；
        // 该查询必须早于扫码会话读取，否则首次成功已消费会话后，重试将无法返回原订单。
        Object cachedOrderNo = null;
        try {
            cachedOrderNo = RedisUtils.get(redis, cacheKey);
        } catch (Exception cacheFailure) {
            // Redis 是提示层；缓存不可用不能阻断数据库幂等兜底，更不能诱发重复扣卡。
            log.warn("读取取水订单幂等缓存失败，继续按数据库订单核验 userId={} requestId={} reason={}",
                    userId, requestId, cacheFailure.getMessage());
        }
        if (ObjectUtil.isNotNull(cachedOrderNo) && !StrUtil.equals(orderNo, cachedOrderNo.toString())) {
            log.warn("取水订单幂等缓存与确定性单号不一致 userId={} requestId={} cached={} expected={}",
                    userId, requestId, cachedOrderNo, orderNo);
        }
        WsOrder persistedOrder = getByOrderNo(orderNo);
        if (ObjectUtil.isNotNull(persistedOrder)) {
            requireSameRequestOrder(persistedOrder, userId, requestId, bo);
            // P0：幂等命中不许直接返回——首次创单可能停在「2 已支付、CMD_ID 空、无指令」，
            // 重试是补齐指令的唯一可靠时机，统一交 post-commit 闸补发或转异常终态
            ensureDispatchOrTerminal(persistedOrder);
            return buildDetail(latestOrElse(orderNo, persistedOrder));
        }

        // 会话取设备/出水口（校验归属登录人+未过期），设备可用性+指定卡复验。
        // CARD-SCOPE：预检与下单用同一 cardId——预检只是提前给出友好拒因，安全边界在下单事务内的二次校验。
        ScanSessionInfo session = miniDeviceService.loadScanSession(requestId, userId);
        WaterEligibilityVo eligibility = miniDeviceService.checkEligibility(requestId, bo.getCardId(), userId);
        if (!"AVAILABLE".equals(eligibility.getAvailability())) {
            throw new JbkException(StrUtil.blankToDefault(eligibility.getReason(), "设备当前不可用，暂不能下单"));
        }
        if (ObjectUtil.isNotNull(eligibility.getCardBlock())) {
            // P1-C：真实提交因范围被拒时（无论拦在编排层复检还是事务内）都必须留下可靠拒绝审计
            recordScopeDenyAtSubmit(orderNo, bo.getCardId(), userId, session, eligibility.getCardBlock());
            throw new JbkException(StrUtil.blankToDefault(
                    eligibility.getCardBlock().getMessage(), "水卡当前不可用，暂不能下单"));
        }
        WsDeviceOutlet outlet = requireOutlet(session.getOutletId());
        WsDevice device = requireDevice(session.getDeviceId());
        // 前端自报水种只做一致性核验，绝不作为权威来源：权威是扫码会话里冻结的那一份。
        if (ObjectUtil.notEqual(session.getWaterTypeId(), bo.getWaterTypeId())) {
            throw new JbkException("水种与本次扫码不一致，请重新扫码");
        }
        // 报价漂移即拒：确认页看到的价与此刻档案价不同，不允许静默按新价扣款。
        requireQuoteUnchanged(session, outlet);

        // 单价一律取会话冻结值，不再读当前档案——读当前价正是「页面一个价、扣款另一个价」的根因。
        int unitPrice = session.getUnitPriceFenPerLiter();
        long planMl = bo.getPlanMl();
        // v1.3 缺口B：payWay2 预扣按 ceil 向上取整到分（不少扣）；payWay3 水量支付订单金额记 0
        long orderAmount = ObjectUtil.equal(payWay, TradeEnum.PayWay.CARD_BALANCE.getValue())
                ? WaterBillingMath.ceilAmount(planMl, unitPrice) : 0L;

        String now = DateUtils.time();
        WsOrder order = new WsOrder()
                .setOrderNo(orderNo)
                .setOrderType(TradeEnum.OrderType.WATER.getValue())
                .setUserId(userId)
                .setStationId(device.getStationId())
                .setDeviceId(device.getId())
                .setOutletId(outlet.getId())
                .setCardId(bo.getCardId())
                .setPackageSnap(buildSnap(requestId, unitPrice, planMl, payWay, session.getWaterTypeId()))
                .setPlanMl(planMl)
                .setOrderAmount(orderAmount)
                .setPayWay(payWay)
                // E2E-08 归因快照：下单时刻的推荐人（绑定前恒 NULL，绑定不回溯）
                .setReferrerUserId(inviteService.referrerSnapshotOf(userId))
                .setOrderStatus(TradeEnum.OrderStatus.PAID.getValue());

        WsOrder saved;
        try {
            // 事务内完成共键重核+锁卡校验+范围放行+原子扣减（CARD-SCOPE ①~⑨）；失败整体回滚零副作用
            saved = tradeOrderTxService.createWaterOrder(order, session, now);
        } catch (DuplicateKeyException e) {
            // 并发输方命中 uk_order_no 后，必须重新读取并执行与普通重试完全相同的完整参数核验。
            WsOrder existed = getByOrderNo(orderNo);
            if (ObjectUtil.isNotNull(existed)) {
                try {
                    requireSameRequestOrder(existed, userId, requestId, bo);
                } catch (JbkException conflict) {
                    if (!StrUtil.equals(REQUEST_PARAMETER_CONFLICT, conflict.getMessage())) {
                        recordOrderNoCollision(orderNo, requestId, userId);
                    }
                    throw conflict;
                }
                // P0：并发输方与普通幂等重试同一口径——同样必须补齐指令或转终态，不返回悬挂单
                ensureDispatchOrTerminal(existed);
                return buildDetail(latestOrElse(orderNo, existed));
            }
            recordOrderNoCollision(orderNo, requestId, userId);
            throw new JbkException(ORDER_NO_COLLISION);
        }

        // 创单成功后：写幂等键 + 消费扫码会话（M4，仅成功消费，失败可重扫重试）
        try {
            RedisUtils.set(redis, cacheKey, orderNo, ORDER_REQ_TTL);
        } catch (Exception cacheFailure) {
            // 订单与扣款已经提交；缓存失败不得阻断会话消费与指令下发，后续重试仍由确定性单号查库兜底。
            log.warn("写入取水订单幂等缓存失败，继续完成创单后流程 userId={} orderNo={} reason={}",
                    userId, orderNo, cacheFailure.getMessage());
        }
        // P0：会话消费 best-effort——删除失败绝不能把已扣款订单表现成下单失败或阻断指令创建，
        // 安全边界始终在下单事务内的二次校验
        try {
            miniDeviceService.consumeScanSession(requestId);
        } catch (Exception sessionFailure) {
            log.error("扫码会话消费失败（不阻断指令创建，等待 TTL 过期） userId={} orderNo={} requestId={}",
                    userId, orderNo, requestId, sessionFailure);
        }
        // 事务提交后统一走终态闸（L1c/P0）：补发出水指令或转入可追溯异常终态。
        ensureDispatchOrTerminal(saved);
        // 终态闸可能已改状态，返回最新；读失败回退刚落库实例——已扣款的创单绝不因回读抖动返回失败
        return buildDetail(latestOrElse(orderNo, saved));
    }

    /**
     * P0 post-commit 终态闸：对「已扣款且 CMD_ID 空」的取水单调用幂等的
     * {@link IWsCommandService#sendDispenseForOrder}（CMD_ID 抢占 CAS），下发异常转「6 异常待补偿」；
     * 禁止停留在悬挂态。本方法绝不抛异常——资金事务已提交，编排故障不得表现成下单失败。
     */
    // 包级可见：供同包单测直接覆盖「补发/终态/跳过」三类分支。
    void ensureDispatchOrTerminal(WsOrder order) {
        if (ObjectUtil.isNull(order)
                || ObjectUtil.notEqual(order.getOrderType(), TradeEnum.OrderType.WATER.getValue())
                || ObjectUtil.notEqual(order.getOrderStatus(), TradeEnum.OrderStatus.PAID.getValue())
                || ObjectUtil.isNotNull(order.getCmdId())) {
            // 非取水/已推进/已绑指令：既有指令状态机负责，不重复触发
            return;
        }
        try {
            commandService.sendDispenseForOrder(order.getId());
        } catch (Exception e) {
            log.error("订单已扣款但出水指令下发触发异常 orderId={} orderNo={}", order.getId(), order.getOrderNo(), e);
            markAbnormalWhenDispatchLeftNoTrace(order.getId(), order.getOrderNo(), e);
        }
    }

    /**
     * P1-C 范围拒绝审计：仅 CARD_SCOPE_INVALID/DENIED 两码落痕，与事务内
     * {@code TradeOrderTxServiceImpl#scopeRejected} 共用幂等键 {@code CARD_SCOPE_DENY:<orderNo>}，
     * 同一请求全库至多一条。必须走 recordReliableOnceIndependent 的「撞键即已留痕」——
     * 两层证据 decidedAt 各自取时报文必不相同，读回核验版会把正常撞键炸成审计异常。
     * 独立预检接口不经过本方法（预检不落审计）。
     */
    private void recordScopeDenyAtSubmit(String orderNo, Long cardId, Long userId,
                                         ScanSessionInfo session, WaterEligibilityVo.CardBlockVo cardBlock) {
        String code = cardBlock.getCode();
        if (!"CARD_SCOPE_INVALID".equals(code) && !"CARD_SCOPE_DENIED".equals(code)) {
            return;
        }
        JSONObject evidence = JSONUtil.createObj()
                .set("actorUserId", userId)
                .set("cardId", cardId)
                .set("stationId", session.getStationId())
                .set("deviceId", session.getDeviceId())
                .set("outletId", session.getOutletId())
                .set("rejectCode", code)
                .set("decidedAt", DateUtils.time());
        domainEventService.recordReliableOnceIndependent(OpsEnum.EventType.ORDER_STATUS, orderNo,
                "CARD_SCOPE_DENY:" + orderNo, null, "取水下单范围拒绝：" + evidence);
    }

    /** 回读最新订单；读失败回退传入实例——扣款已提交的请求绝不因回读抖动返回失败。 */
    private WsOrder latestOrElse(String orderNo, WsOrder fallback) {
        try {
            WsOrder latest = getByOrderNo(orderNo);
            return ObjectUtil.isNull(latest) ? fallback : latest;
        } catch (Exception e) {
            log.error("回读最新订单失败，返回已知实例 orderNo={}", orderNo, e);
            return fallback;
        }
    }

    @Override
    public PageDataVo<OrderItemVo> pageMyOrders(MiniOrderQueryBo bo, Long userId) {
        Page<WsOrder> page = wsOrderMapper.selectPage(new Page<>(bo.getCurrent(), bo.getSize()),
                Wrappers.lambdaQuery(WsOrder.class)
                        .eq(WsOrder::getUserId, userId)
                        .eq(ObjectUtil.isNotNull(bo.getOrderType()), WsOrder::getOrderType, bo.getOrderType())
                        .eq(ObjectUtil.isNotNull(bo.getOrderStatus()), WsOrder::getOrderStatus, bo.getOrderStatus())
                        .orderByDesc(WsOrder::getCreateTime)
                        .orderByDesc(WsOrder::getId));
        List<WsOrder> records = page.getRecords();
        Map<Long, String> stationNames = loadStationNames(records);
        Map<Long, String> deviceNos = loadDeviceNos(records);
        List<OrderItemVo> list = records.stream()
                .map(order -> buildItem(order, stationNames.get(order.getStationId()), deviceNos.get(order.getDeviceId())))
                .collect(Collectors.toList());
        return PageDataVo.getPageData(list, page.getTotal());
    }

    @Override
    public OrderDetailVo getMyOrderDetail(MiniOrderDetailBo bo, Long userId) {
        WsOrder order;
        if (StrUtil.isNotBlank(bo.getOrderNo())) {
            order = getByOrderNo(bo.getOrderNo());
        } else if (ObjectUtil.isNotNull(bo.getOrderId())) {
            order = wsOrderMapper.selectById(bo.getOrderId());
        } else {
            throw new JbkException("请提供订单号或订单ID");
        }
        // 归属校验：非本人订单一律按“不存在”处理，不泄露他人订单存在性（铁律6）
        if (ObjectUtil.isNull(order) || ObjectUtil.notEqual(order.getUserId(), userId)) {
            throw new JbkException("订单不存在或无权查看");
        }
        return buildDetail(order);
    }

    /**
     * 出水指令下发异常后的兜底（S0-C）：仅当库中无任何可兜底事实（无 CMD_ID、无指令行）时，
     * 才把订单 CAS 从「2 已支付」转「6 异常待补偿」，不踩踏并发推进；已有指令的归既有状态机管。
     * 本方法绝不抛异常，不影响下单响应。
     */
    // 包级可见：供同包单测直接覆盖「下发异常后是否留下可兜底事实」的三种分支。
    void markAbnormalWhenDispatchLeftNoTrace(Long orderId, String orderNo, Exception cause) {
        try {
            WsOrder fresh = wsOrderMapper.selectById(orderId);
            if (ObjectUtil.isNull(fresh) || ObjectUtil.isNotNull(fresh.getCmdId())) {
                return;
            }
            Long commandRows = commandMapper.selectCount(
                    Wrappers.lambdaQuery(WsCommand.class).eq(WsCommand::getOrderId, orderId));
            if (ObjectUtil.isNotNull(commandRows) && commandRows > 0) {
                return;
            }
            // 截断到 490（与 WsCommandServiceImpl 同口径）：CANCEL_REASON 超长写失败会让兜底退化回"无痕"
            String reason = StrUtil.maxLength("出水指令下发失败且未生成指令，转异常待补偿："
                    + StrUtil.blankToDefault(cause.getMessage(), cause.getClass().getSimpleName()), 490);
            int updated = wsOrderMapper.update(null, Wrappers.lambdaUpdate(WsOrder.class)
                    .set(WsOrder::getOrderStatus, TradeEnum.OrderStatus.ABNORMAL.getValue())
                    .set(WsOrder::getCancelReason, reason)
                    .set(WsOrder::getUpdateTime, DateUtils.time())
                    .eq(WsOrder::getId, orderId)
                    .eq(WsOrder::getOrderStatus, TradeEnum.OrderStatus.PAID.getValue())
                    .isNull(WsOrder::getCmdId));
            if (updated == 1) {
                domainEventService.record(OpsEnum.EventType.ORDER_STATUS, orderNo,
                        TradeEnum.OrderStatus.PAID.getDesc(), reason);
            }
        } catch (Exception ignore) {
            // 兜底本身失败不得影响下单响应；已在上层记录原始异常。
            log.error("订单出水指令下发失败后的异常兜底未成功 orderId={} orderNo={}", orderId, orderNo, ignore);
        }
    }

    // ==================== 幂等与订单号 ====================

    private WsOrder getByOrderNo(String orderNo) {
        return wsOrderMapper.selectOne(Wrappers.lambdaQuery(WsOrder.class).eq(WsOrder::getOrderNo, orderNo));
    }

    /**
     * 持久订单幂等核验。既有订单只有在归属、请求和全部下单参数均与本次输入一致时才可返回。
     * 参数冲突只拒绝，不读取扫码会话、不扣卡、不写缓存、不下发设备指令。
     */
    private void requireSameRequestOrder(WsOrder order, Long userId, String requestId, CreateWaterOrderBo bo) {
        if (ObjectUtil.notEqual(order.getUserId(), userId)
                || ObjectUtil.notEqual(order.getOrderType(), TradeEnum.OrderType.WATER.getValue())) {
            throw new JbkException(ORDER_NO_COLLISION);
        }

        // 快照解析与数值规则唯一实现在 WaterOrderSnapshot：再写一份必然漂移，最松的那处就是绕过口
        WaterOrderSnapshot.Frozen frozen;
        long expectedAmount;
        try {
            frozen = WaterOrderSnapshot.parseForIdempotency(order.getPackageSnap());
            expectedAmount = WaterOrderSnapshot.expectedOrderAmount(frozen);
        }
        catch (JbkException | ArithmeticException invalid) {
            throw new JbkException(ORDER_IDEMPOTENCY_DATA_INVALID);
        }
        if (!StrUtil.equals(frozen.requestId(), requestId)) {
            throw new JbkException(ORDER_NO_COLLISION);
        }
        Long snapWaterTypeId = ObjectUtil.isNull(frozen.waterTypeId())
                ? resolveLegacyWaterTypeFromOutlet(order)
                : frozen.waterTypeId();

        boolean snapshotSelfConsistent = WATER_PAID_REACHED_STATUS.contains(order.getOrderStatus())
                && ObjectUtil.equal(order.getPlanMl(), frozen.planMl())
                && ObjectUtil.equal(order.getPayWay(), frozen.payWay())
                && ObjectUtil.equal(order.getOrderAmount(), expectedAmount);
        if (!snapshotSelfConsistent) {
            throw new JbkException(ORDER_IDEMPOTENCY_DATA_INVALID);
        }

        boolean sameParameters = ObjectUtil.equal(order.getCardId(), bo.getCardId())
                && ObjectUtil.equal(order.getPlanMl(), bo.getPlanMl())
                && ObjectUtil.equal(order.getPayWay(), bo.getPayWay())
                && ObjectUtil.equal(snapWaterTypeId, bo.getWaterTypeId());
        if (!sameParameters) {
            throw new JbkException(REQUEST_PARAMETER_CONFLICT);
        }
    }

    private Long resolveLegacyWaterTypeFromOutlet(WsOrder order) {
        if (ObjectUtil.isNull(order.getOutletId())) {
            throw new JbkException(ORDER_IDEMPOTENCY_DATA_INVALID);
        }
        WsDeviceOutlet persistedOutlet = outletMapper.selectById(order.getOutletId());
        if (ObjectUtil.isNull(persistedOutlet) || ObjectUtil.isNull(persistedOutlet.getWaterTypeId())) {
            throw new JbkException(ORDER_IDEMPOTENCY_DATA_INVALID);
        }
        return persistedOutlet.getWaterTypeId();
    }

    private void recordOrderNoCollision(String orderNo, String requestId, Long userId) {
        domainEventService.record(OpsEnum.EventType.ORDER_STATUS, orderNo, null,
                "ORDER_NO 生成冲突且幂等核验不通过：requestId=" + requestId + " loginUser=" + userId);
    }

    /**
     * ORDER_NO 确定性派生：{@code WO + sha256(userId + ":" + requestId) 前 20 位}，总长 22。
     * 不含日期（含日期则跨零点重放生成不同单号、幂等失效、扣第二次钱）；含 userId
     * （否则不同用户同 requestId 撞 uk_order_no）。与 {@code RechargeOrderNo.derive} 同口径：
     * 只由「谁 + 哪次请求」决定，跨重启、跨零点重放恒定。
     */
    static String buildOrderNo(Long userId, String requestId) {
        String hash = SecureUtil.sha256(userId + ":" + requestId).substring(0, 20).toUpperCase();
        return "WO" + hash;
    }

    static String buildRequestCacheKey(Long userId, String requestId) {
        return ORDER_REQ_PREFIX + userId + ":" + requestId;
    }

    private String buildSnap(String requestId, int unitPrice, long planMl, int payWay, Long waterTypeId) {
        // 构造与解析共用 WaterOrderSnapshot 一套键名，不在本类拼 JSON（各写各的迟早对不上）
        return WaterOrderSnapshot.build(requestId, unitPrice, planMl, payWay, waterTypeId);
    }

    // ==================== 详情/列表装配 ====================

    /**
     * 取水单中「支付已经发生」的状态集合——显式枚举，替代原先的 {@code status >= PAID} 数值序比较。
     * 已取消(5) 可能从未支付即取消，故不在此集合；已退款(7)/部分退款(8) 曾经支付，故在集合内。
     */
    private static final java.util.Set<Integer> WATER_PAID_REACHED_STATUS = java.util.Set.of(
            TradeEnum.OrderStatus.PAID.getValue(),
            TradeEnum.OrderStatus.DISPENSING.getValue(),
            TradeEnum.OrderStatus.FINISHED.getValue(),
            TradeEnum.OrderStatus.ABNORMAL.getValue(),
            TradeEnum.OrderStatus.REFUNDED.getValue(),
            TradeEnum.OrderStatus.PART_REFUNDED.getValue());

    private OrderDetailVo buildDetail(WsOrder order) {
        String stationName = null;
        if (ObjectUtil.isNotNull(order.getStationId())) {
            WsStation station = stationMapper.selectById(order.getStationId());
            stationName = ObjectUtil.isNull(station) ? null : station.getStationName();
        }
        String deviceNo = null;
        if (ObjectUtil.isNotNull(order.getDeviceId())) {
            WsDevice device = deviceMapper.selectById(order.getDeviceId());
            deviceNo = ObjectUtil.isNull(device) ? null : device.getDeviceNo();
        }
        Long flowCount = walletFlowMapper.selectCount(
                Wrappers.lambdaQuery(WsWalletFlow.class)
                        .eq(WsWalletFlow::getOrderId, order.getId()));
        // 结构对齐 order.ts OrderDetail；commandNo/commandStatus 恒 null（接真属 L2-READ 切片），
        // 前端按「未知/未接入」呈现，不得解读为「无指令」
        OrderItemVo item = buildItem(order, stationName, deviceNo);
        if (ObjectUtil.equals(order.getOrderType(), 2)) {
            // 契约 v2 §9.2：充值单不下发原始 PACKAGE_SNAP，改为服务端已校验的结构化区块
            item.setPackageSnapshot(null);
            item.setRecharge(buildRechargeDetail(order));
        }
        // E2E-03 包B：配送单补挂任务号与最新申诉ID，只给定位键不复制履约证据内容
        String deliveryTaskNo = null;
        Long appealId = null;
        OrderDetailVo.CancelEligibilityVo cancelEligibility = null;
        if (ObjectUtil.equals(order.getOrderType(), TradeEnum.OrderType.DELIVERY.getValue())) {
            com.jbk.tool.data.delivery.po.WsDeliveryTask deliveryTask = deliveryTaskMapper.selectOne(
                    Wrappers.lambdaQuery(com.jbk.tool.data.delivery.po.WsDeliveryTask.class)
                            .eq(com.jbk.tool.data.delivery.po.WsDeliveryTask::getOrderId, order.getId()));
            deliveryTaskNo = ObjectUtil.isNull(deliveryTask) ? null : deliveryTask.getTaskNo();
            com.jbk.tool.data.delivery.po.WsDeliveryAppeal appeal = deliveryAppealMapper.selectOne(
                    Wrappers.lambdaQuery(com.jbk.tool.data.delivery.po.WsDeliveryAppeal.class)
                            .eq(com.jbk.tool.data.delivery.po.WsDeliveryAppeal::getOrderId, order.getId())
                            .orderByDesc(com.jbk.tool.data.delivery.po.WsDeliveryAppeal::getId)
                            .last("LIMIT 1"));
            appealId = ObjectUtil.isNull(appeal) ? null : appeal.getId();
            cancelEligibility = buildCancelEligibility(deliveryTask);
        }
        return new OrderDetailVo()
                .setOrder(item)
                .setCommandNo(null)
                .setCommandStatus(null)
                .setTrace(buildTrace(order))
                .setFlowCount(ObjectUtil.isNull(flowCount) ? 0 : flowCount.intValue())
                .setDeliveryTaskNo(deliveryTaskNo)
                .setAppealId(appealId)
                // 只读投影仍带 USER_ID 本人范围闸；缺少动作即 null，页面不按订单状态推算结果。
                .setAfterSale(wsOrderMapper.selectLatestMiniAfterSaleProgress(order.getId(), order.getUserId()))
                .setCancelEligibility(cancelEligibility);
    }

    /**
     * 订单轨迹按 ORDER_TYPE 分支（链路一 S0-B）：未接入的单型返回空轨迹，
     * 宁可不展示也不复用取水文案造成语义造假。
     */
    private List<OrderTraceNodeVo> buildTrace(WsOrder order) {
        if (ObjectUtil.equal(order.getOrderType(), TradeEnum.OrderType.WATER.getValue())) {
            return buildWaterTrace(order);
        }
        if (ObjectUtil.equal(order.getOrderType(), 2)) {
            return buildRechargeTrace(order);
        }
        return new ArrayList<>();
    }

    /** 充值轨迹只使用已通过结构化共键校验的 payment/order 事实。 */
    private List<OrderTraceNodeVo> buildRechargeTrace(WsOrder order) {
        List<WsPayment> payments = rechargeIdentityMapper.selectPaymentsByOrderIdIncludingDeleted(order.getId());
        if (payments == null || payments.size() != 1 || !ObjectUtil.equals(payments.get(0).getDataStatus(), 0)) {
            throw new JbkException("充值订单支付数据异常，无法生成轨迹");
        }
        WsPayment payment = payments.get(0);
        List<OrderTraceNodeVo> trace = new ArrayList<>();
        trace.add(node("CREATED", "创建订单", order.getCreateTime(), "充值订单已创建", "info"));
        if (ObjectUtil.equals(payment.getPayStatus(), 2)) {
            trace.add(node("PAID", "支付成功", payment.getPaySuccessTime(), "支付事实已确认", "success"));
        }
        if (ObjectUtil.equals(order.getOrderStatus(), 2)) {
            trace.add(node("CREDIT_PENDING", "权益处理中", order.getUpdateTime(), "支付成功，权益等待入账", "info"));
        } else if (ObjectUtil.equals(order.getOrderStatus(), 4)) {
            trace.add(node("CREDITED", "权益到账", order.getFinishTime(), "充值权益及流水已完成", "success"));
        } else if (ObjectUtil.equals(order.getOrderStatus(), 6)) {
            trace.add(node("RECONCILIATION", "待人工处理", order.getUpdateTime(),
                    StrUtil.blankToDefault(order.getCancelReason(), "支付成功，权益待人工核对"), "danger"));
        } else if (ObjectUtil.equals(order.getOrderStatus(), 5)) {
            trace.add(node("CLOSED", "订单关闭", order.getUpdateTime(),
                    StrUtil.blankToDefault(order.getCancelReason(), "支付方确认订单关闭"), "warning"));
        } else if (ObjectUtil.equals(order.getOrderStatus(), 7)) {
            trace.add(node("REFUNDED", "已退款", order.getFinishTime(),
                    "退款结果与权益冲减已完成", "warning"));
        } else if (ObjectUtil.equals(order.getOrderStatus(), 8)) {
            trace.add(node("PART_REFUNDED", "部分退款", order.getFinishTime(),
                    "退款结果与保留权益已完成核对", "warning"));
        }
        return trace;
    }

    /** 取水单轨迹：状态判定一律用显式集合/等值，不用数值序比较（各单类型状态序不同，数值序会算错）。 */
    private List<OrderTraceNodeVo> buildWaterTrace(WsOrder order) {
        List<OrderTraceNodeVo> trace = new ArrayList<>();
        trace.add(node("CREATED", "下单", order.getCreateTime(), "扫码取水下单", "info"));
        Integer status = order.getOrderStatus();
        if (ObjectUtil.isNotNull(status) && WATER_PAID_REACHED_STATUS.contains(status)) {
            trace.add(node("PAID", "已支付", order.getCreateTime(), payWayLabel(order.getPayWay()) + "扣款成功", "success"));
        }
        if (ObjectUtil.equal(status, TradeEnum.OrderStatus.DISPENSING.getValue())) {
            trace.add(node("DISPENSING", "出水中", order.getUpdateTime(), "设备正在出水", "info"));
        }
        if (ObjectUtil.equal(status, TradeEnum.OrderStatus.FINISHED.getValue())) {
            trace.add(node("FINISHED", "已完成", order.getFinishTime(), "取水完成", "success"));
        }
        if (ObjectUtil.equal(status, TradeEnum.OrderStatus.ABNORMAL.getValue())) {
            trace.add(node("ABNORMAL", "异常待补偿", order.getUpdateTime(),
                    StrUtil.blankToDefault(order.getCancelReason(), "出水异常，待补偿处理"), "danger"));
        }
        if (ObjectUtil.equal(status, TradeEnum.OrderStatus.CANCELLED.getValue())) {
            trace.add(node("CANCELLED", "已取消", order.getUpdateTime(),
                    StrUtil.blankToDefault(order.getCancelReason(), "订单已取消"), "warning"));
        }
        return trace;
    }

    private OrderTraceNodeVo node(String code, String label, String time, String detail, String tone) {
        return new OrderTraceNodeVo().setNode(code).setLabel(label).setTime(time).setDetail(detail).setTone(tone);
    }

    private String payWayLabel(Integer payWay) {
        if (ObjectUtil.equal(payWay, TradeEnum.PayWay.CARD_BALANCE.getValue())) {
            return "水卡余额";
        }
        if (ObjectUtil.equal(payWay, TradeEnum.PayWay.CARD_ML.getValue())) {
            return "水卡水量";
        }
        return "微信";
    }

    private Map<Long, String> loadStationNames(List<WsOrder> orders) {
        List<Long> stationIds = orders.stream()
                .map(WsOrder::getStationId)
                .filter(ObjectUtil::isNotNull)
                .distinct()
                .collect(Collectors.toList());
        if (stationIds.isEmpty()) {
            return new HashMap<>();
        }
        return stationMapper.selectBatchIds(stationIds).stream()
                .collect(Collectors.toMap(WsStation::getId, WsStation::getStationName, (a, b) -> a));
    }

    private Map<Long, String> loadDeviceNos(List<WsOrder> orders) {
        List<Long> deviceIds = orders.stream()
                .map(WsOrder::getDeviceId)
                .filter(ObjectUtil::isNotNull)
                .distinct()
                .collect(Collectors.toList());
        if (deviceIds.isEmpty()) {
            return new HashMap<>();
        }
        return deviceMapper.selectBatchIds(deviceIds).stream()
                .collect(Collectors.toMap(WsDevice::getId, WsDevice::getDeviceNo, (a, b) -> a));
    }

    /** 小程序详情与 PC 追溯共用同一套只读充值证据校验。 */
    private MiniRechargeDetailVo buildRechargeDetail(WsOrder order) {
        return rechargeDetailVerifier.verify(order);
    }

    /** 装配订单主体（字段对齐 order.ts OrderItem：orderId/orderAmountFen 等）。 */
    private OrderItemVo buildItem(WsOrder order, String stationName, String deviceNo) {
        return new OrderItemVo()
                .setOrderId(order.getId())
                .setOrderNo(order.getOrderNo())
                .setUserId(order.getUserId())
                .setOrderType(order.getOrderType())
                .setOrderStatus(order.getOrderStatus())
                .setOrderAmountFen(order.getOrderAmount())
                .setPayWay(order.getPayWay())
                .setStationId(order.getStationId())
                .setStationName(stationName)
                .setDeviceNo(deviceNo)
                .setCardId(order.getCardId())
                .setPlanMl(order.getPlanMl())
                .setActualMl(order.getActualMl())
                .setPackageSnapshot(order.getPackageSnap())
                .setCreateTime(order.getCreateTime())
                .setFinishTime(order.getFinishTime());
    }

    // ==================== 档案读取 ====================

    private WsDeviceOutlet requireOutlet(Long outletId) {
        WsDeviceOutlet outlet = ObjectUtil.isNull(outletId) ? null : outletMapper.selectById(outletId);
        if (ObjectUtil.isNull(outlet)) {
            throw new JbkException("出水口不存在或已下线，请重新扫码");
        }
        return outlet;
    }

    private WsDevice requireDevice(Long deviceId) {
        WsDevice device = ObjectUtil.isNull(deviceId) ? null : deviceMapper.selectById(deviceId);
        if (ObjectUtil.isNull(device)) {
            throw new JbkException("设备不存在或已下线，请重新扫码");
        }
        return device;
    }

    /**
     * 编排层报价漂移闸（S2）：会话冻结的水种/单价须与当前出水口一致。只给友好拒因，
     * 安全边界在下单事务内的三方终判；单价解析唯一实现在 {@link WaterBillingMath#requireOutletPrice}。
     */
    private void requireQuoteUnchanged(ScanSessionInfo session, WsDeviceOutlet outlet) {
        if (ObjectUtil.notEqual(session.getWaterTypeId(), outlet.getWaterTypeId())) {
            throw new JbkException("出水口水种已调整，请重新扫码确认", MiniRejectCode.SCAN_QUOTE_CHANGED);
        }
        int current;
        try {
            current = WaterBillingMath.requireOutletPrice(outlet.getOutletPrice());
        }
        catch (JbkException invalid) {
            domainEventService.recordByDevice(OpsEnum.EventType.DEVICE_STATUS, String.valueOf(outlet.getId()), null,
                    "出水口单价配置非法：outletId=" + outlet.getId() + " price=" + outlet.getOutletPrice());
            throw new JbkException("出水口单价配置异常，请联系管理员");
        }
        if (ObjectUtil.notEqual(session.getUnitPriceFenPerLiter(), current)) {
            throw new JbkException("取水价格已调整，请重新扫码确认", MiniRejectCode.SCAN_QUOTE_CHANGED);
        }
    }

    /**
     * 待接单取消资格（E2E-04 包E）：判定必须复用 {@link com.jbk.serve.service.delivery.DeliveryTransitions#allowed}，
     * 与执行取消是同一份状态机（另写判据=读写两份真相）。只回答「能不能点」不写入，
     * 并发裁决仍在取消事务的 CAS 里。
     */
    private OrderDetailVo.CancelEligibilityVo buildCancelEligibility(
            com.jbk.tool.data.delivery.po.WsDeliveryTask task) {
        OrderDetailVo.CancelEligibilityVo vo = new OrderDetailVo.CancelEligibilityVo();
        if (ObjectUtil.isNull(task)) {
            vo.setAllowed(false);
            vo.setReason("配送任务不存在，无法取消");
            return vo;
        }
        boolean allowed = com.jbk.serve.service.delivery.DeliveryTransitions.allowed(
                task.getTaskStatus(),
                com.jbk.tool.consts.delivery.DeliveryEnum.TaskStatus.CANCELLED.getValue());
        vo.setAllowed(allowed);
        vo.setReason(allowed ? null : "配送员已接单或任务已推进，无法自助取消，请联系客服");
        return vo;
    }
}
