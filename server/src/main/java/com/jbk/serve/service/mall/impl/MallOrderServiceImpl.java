package com.jbk.serve.service.mall.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jbk.serve.mapper.mall.WsMallCartItemMapper;
import com.jbk.serve.mapper.mall.WsMallOrderItemMapper;
import com.jbk.serve.mapper.mall.WsMallOrderMapper;
import com.jbk.serve.mapper.mall.WsMallPaymentMapper;
import com.jbk.serve.mapper.mall.WsMallProductMapper;
import com.jbk.serve.mapper.mall.WsMallSkuMapper;
import com.jbk.serve.mapper.mall.WsMallStockFlowMapper;
import com.jbk.serve.mapper.mall.WsMallStockMapper;
import com.jbk.serve.mapper.mall.WsMallWarehouseMapper;
import com.jbk.serve.service.mall.IMallOrderService;
import com.jbk.serve.service.mini.IMiniFamilyService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.mall.MallEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.mall.bo.MallCheckoutBo;
import com.jbk.tool.data.mall.bo.MallOrderActionBo;
import com.jbk.tool.data.mall.bo.MallOrderQueryBo;
import com.jbk.tool.data.mall.po.WsMallCartItem;
import com.jbk.tool.data.mall.po.WsMallOrder;
import com.jbk.tool.data.mall.po.WsMallOrderItem;
import com.jbk.tool.data.mall.po.WsMallPayment;
import com.jbk.tool.data.mall.po.WsMallProduct;
import com.jbk.tool.data.mall.po.WsMallSku;
import com.jbk.tool.data.mall.po.WsMallStock;
import com.jbk.tool.data.mall.po.WsMallStockFlow;
import com.jbk.tool.data.mall.po.WsMallWarehouse;
import com.jbk.tool.data.mall.vo.MallOrderDetailVo;
import com.jbk.tool.data.mall.vo.MallOrderVo;
import com.jbk.tool.data.mall.vo.MiniMallCartVo;
import com.jbk.tool.data.mall.vo.MiniMallCheckoutVo;
import com.jbk.tool.data.user.po.WsUserAddress;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import com.jbk.tool.utils.PhoneMask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 商城订单服务实现（E2E-09 S2）。创单事务顺序固定：幂等预读 → 地址解引用 → SKU 权威读 →
 * 选仓 → 服务端重算金额 → 按 (WAREHOUSE_ID, SKU_ID) 升序原子预占 → 写单/明细/流水/支付单/审计
 * → 清购物车；任一 SKU 预占失败整事务回滚，「部分预占」绝不落地。选仓一单一仓，多个满足时
 * 按仓库 ID 升序取第一个并冻结（确定性可复现，不编造最优仓规则）。幂等锚是
 * uk(USER_ID, REQUEST_ID)，重放须逐字核对地址与全部行，任一不符即拒绝。
 *
 * @author dakang
 * @since 2026-08-08
 */
@Slf4j
@Service
public class MallOrderServiceImpl implements IMallOrderService {

    /** 预占幂等键前缀：MALLRSV:<orderNo>:<skuId>，与释放/实销分属不同命名空间。 */
    static final String RESERVE_KEY_PREFIX = "MALLRSV:";
    /** 释放幂等键前缀。 */
    static final String RELEASE_KEY_PREFIX = "MALLREL:";

    /** 内部闭环期配送费固定 0：落快照但不是正式商业运费规则。 */
    private static final long DELIVERY_FEE_FEN = 0L;

    @Value("${mall.pay-expire-minutes:30}")
    private int payExpireMinutes;

    /** 绑号闸：商城资金链与配送上门，两类判据同时命中。 */
    @Autowired
    private com.jbk.serve.service.mini.auth.MiniPhoneGate phoneGate;
    @Autowired
    private WsMallOrderMapper orderMapper;
    @Autowired
    private WsMallOrderItemMapper orderItemMapper;
    @Autowired
    private WsMallPaymentMapper paymentMapper;
    @Autowired
    private WsMallCartItemMapper cartItemMapper;
    @Autowired
    private WsMallSkuMapper skuMapper;
    @Autowired
    private WsMallProductMapper productMapper;
    @Autowired
    private WsMallWarehouseMapper warehouseMapper;
    @Autowired
    private WsMallStockMapper stockMapper;
    @Autowired
    private WsMallStockFlowMapper flowMapper;
    @Autowired
    private IMiniFamilyService familyService;
    @Autowired
    private IWsDomainEventService domainEventService;
    @Autowired
    private PlatformTransactionManager transactionManager;

    // ==================== 结算预览 ====================

    @Override
    public MiniMallCheckoutVo preview(Long userId, MallCheckoutBo bo) {
        List<MallCheckoutBo.Line> lines = normalizeLines(bo);
        WsUserAddress address = familyService.requireOwnAddress(userId, bo.getAddressId());
        MiniMallCheckoutVo vo = new MiniMallCheckoutVo()
                .setDeliveryFeeFen(DELIVERY_FEE_FEN)
                .setReceiverName(address.getContactName())
                .setMaskedPhone(PhoneMask.mask(address.getContactPhone()))
                .setReceiverRegion(address.getRegion())
                .setReceiverAddress(address.getAddressDetail());

        if (StrUtil.isBlank(address.getDistrictCode())) {
            return blocked(vo, lines, "该地址还没有选择所在区县，请编辑地址后重试");
        }
        Catalog catalog;
        long productAmount;
        List<MiniMallCartVo.CartLine> previewLines;
        try {
            catalog = loadCatalog(lines);
            // 金额试算也要在同一道保护里：单价×数量溢出同样是"这一单不能提交"，
            // 而不是让预览接口直接抛异常，那样端上只会看到一个失败态而不知道为什么
            productAmount = catalog.amountOf(lines);
            previewLines = catalog.toCartLines(lines);
        }
        catch (JbkException blockedReason) {
            return blocked(vo, lines, blockedReason.getMessage());
        }
        vo.setLines(previewLines)
                .setProductAmountFen(productAmount)
                .setOrderAmountFen(MallMoney.add(productAmount, DELIVERY_FEE_FEN));

        WsMallWarehouse warehouse = pickWarehouse(address.getDistrictCode(), lines);
        if (ObjectUtil.isNull(warehouse)) {
            return vo.setSubmittable(false).setBlockReason("当前地址暂无可履约库存");
        }
        // 未绑手机号同样是「不可提交」的一种，必须在预览里如实说出来。
        // 预览是服务端对「这一单现在能不能下」的唯一裁决，端上按 submittable 控制按钮；
        // 这里报 true 而创单被 627 拦死，用户会得到一个点得动、却永远失败的按钮，
        // 而失败原因只在 toast 里一闪而过——他不知道该去绑号，只会反复点。
        // 用 isPhoneBound 只问不拦：预览是只读动作，不该因为没绑号就整个报错。
        if (!phoneGate.isPhoneBound(userId)) {
            return vo.setWarehouseName(warehouse.getWarehouseName())
                    .setSubmittable(false)
                    .setBlockReason("请先绑定手机号");
        }
        return vo.setWarehouseName(warehouse.getWarehouseName()).setSubmittable(true);
    }

    private MiniMallCheckoutVo blocked(MiniMallCheckoutVo vo, List<MallCheckoutBo.Line> lines,
                                       String reason) {
        long fallbackAmount = 0L;
        return vo.setLines(List.of())
                .setProductAmountFen(fallbackAmount)
                .setOrderAmountFen(MallMoney.add(fallbackAmount, DELIVERY_FEE_FEN))
                .setSubmittable(false)
                .setBlockReason(reason);
    }

    // ==================== 创建订单 ====================

    /**
     * 创单编排：本方法**没有**事务。
     *
     * <p>唯一键输方必须能读到胜方已提交的订单，而这在输方自己那个已被标记回滚的事务里
     * 做不到——那里既看不见别人的提交，任何查询也只会污染一个注定回滚的事务。所以事务
     * 边界收在 {@link #createInTx} 里，输方在它回滚返回之后，以全新视野重读并按重放处理。</p>
     */
    @Override
    public MallOrderVo create(Long userId, MallCheckoutBo bo) {
        String requestId = MallOrderNo.requireCanonicalUuid(bo.getRequestId());
        List<MallCheckoutBo.Line> lines = normalizeLines(bo);

        // 幂等预读（快路径）：命中即逐字核对参数，一致返回原订单、漂移拒绝
        WsMallOrder existed = orderMapper.selectByUserRequestIncludingDeleted(userId, requestId);
        if (ObjectUtil.isNotNull(existed)) {
            assertReplayEquivalent(existed, bo.getAddressId(), lines);
            return voOf(existed);
        }

        // 绑号闸：商城走微信支付、不经水卡，是与充值链彼此独立的第二条资金链，必须单独挂闸。
        // 除资金外还命中「有人要按这个账号上门」——配送员要联系收货人。
        // 位置在幂等预读之后：重放路径上面已原样返回，不该在那条路径上做任何新判断。
        phoneGate.requirePhoneBound(userId, "商城下单");

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        try {
            return template.execute(status -> createInTx(userId, bo, requestId, lines));
        }
        catch (DuplicateRequestException race) {
            // 输方事务已回滚（它做的预占也一并回滚），此刻胜方已提交：重读并按重放裁决
            WsMallOrder winner = orderMapper.selectByUserRequestIncludingDeleted(userId, requestId);
            if (ObjectUtil.isNull(winner)) {
                throw new JbkException("订单创建冲突，请稍后重试");
            }
            assertReplayEquivalent(winner, bo.getAddressId(), lines);
            return voOf(winner);
        }
    }

    /** 装配订单出参（概要 + 明细 + 支付单 + 仓名），供快路径与并发重读共用。 */
    private MallOrderVo voOf(WsMallOrder order) {
        List<WsMallOrderItem> items = orderItemMapper.selectByOrderIdOrderBySku(order.getId());
        return orderVoOf(order, items,
                paymentMapper.selectByOrderNoIncludingDeleted(order.getOrderNo()),
                warehouseNameOf(order.getWarehouseId()), coverUrlOf(items));
    }

    /** 列表摘要用的首个商品主图；无明细或商品已删时为空（不留下永远不渲染的死字段）。 */
    private String coverUrlOf(List<WsMallOrderItem> items) {
        if (items.isEmpty()) {
            return null;
        }
        WsMallProduct product = productMapper.selectById(items.get(0).getProductId());
        return ObjectUtil.isNull(product) ? null : product.getCoverUrl();
    }

    /** 并发同请求号的输方信号：只在编排层被捕获，不外泄给调用方。 */
    private static final class DuplicateRequestException extends RuntimeException {
        private DuplicateRequestException() {
            super("并发同请求号创单");
        }
    }

    /** 创单事务体：由编排层以 REQUIRES_NEW 执行。 */
    private MallOrderVo createInTx(Long userId, MallCheckoutBo bo, String requestId,
                                   List<MallCheckoutBo.Line> lines) {
        String orderNo = MallOrderNo.derive(userId, requestId);
        WsUserAddress address = familyService.requireOwnAddress(userId, bo.getAddressId());
        if (StrUtil.isBlank(address.getDistrictCode())) {
            throw new JbkException("该地址还没有选择所在区县，请编辑地址后重试");
        }
        Catalog catalog = loadCatalog(lines);
        WsMallWarehouse warehouse = pickWarehouse(address.getDistrictCode(), lines);
        if (ObjectUtil.isNull(warehouse)) {
            throw new JbkException("当前地址暂无可履约库存");
        }

        String now = DateUtils.time();
        String payExpireTime = DateUtils.plusSeconds(now, payExpireMinutes * 60L);
        long productAmount = catalog.amountOf(lines);

        WsMallOrder order = new WsMallOrder()
                .setOrderNo(orderNo)
                .setUserId(userId)
                .setWarehouseId(warehouse.getId())
                .setRequestId(requestId)
                .setAddressId(address.getId())
                .setProductAmountFen(productAmount)
                .setDeliveryFeeFen(DELIVERY_FEE_FEN)
                .setOrderAmountFen(MallMoney.add(productAmount, DELIVERY_FEE_FEN))
                .setOrderStatus(MallEnum.OrderStatus.PENDING_PAY.getValue())
                .setPayExpireTime(payExpireTime)
                .setReceiverName(address.getContactName())
                .setReceiverPhone(address.getContactPhone())
                .setReceiverRegion(address.getRegion())
                .setReceiverAddress(address.getAddressDetail())
                .setReceiverDistrictCode(address.getDistrictCode())
                .setVersion(1);
        try {
            // 先落订单：并发同请求号在此撞唯一键，尚未动过任何库存
            orderMapper.insert(order);
        }
        catch (DuplicateKeyException race) {
            // 并发同请求号：交给编排层在新事务视野里重读胜方订单并按重放裁决
            throw new DuplicateRequestException();
        }

        List<WsMallOrderItem> items = new ArrayList<>(lines.size());
        for (MallCheckoutBo.Line line : lines) {
            WsMallSku sku = catalog.sku(line.getSkuId());
            WsMallProduct product = catalog.product(sku.getProductId());
            long itemAmount = MallMoney.multiply(sku.getSalePrice(), line.getQuantity());
            WsMallOrderItem item = new WsMallOrderItem()
                    .setOrderId(order.getId())
                    .setProductId(product.getId())
                    .setSkuId(sku.getId())
                    .setProductName(product.getProductName())
                    .setSkuName(sku.getSkuName())
                    .setSpecSnap(sku.getSpecSnap())
                    .setUnitPriceFen(sku.getSalePrice())
                    .setQuantity(line.getQuantity())
                    .setItemAmountFen(itemAmount)
                    .setWeightGram(sku.getWeightGram());
            orderItemMapper.insert(item);
            items.add(item);
        }

        // 锁序：一单一仓，故按 SKU_ID 升序即等价于 (WAREHOUSE_ID, SKU_ID) 升序
        for (MallCheckoutBo.Line line : lines) {
            reserveOne(order, warehouse.getId(), line, userId, now);
        }

        WsMallPayment payment = new WsMallPayment()
                .setOrderId(order.getId())
                .setOrderNo(orderNo)
                .setPayAmountFen(order.getOrderAmountFen())
                .setPayStatus(MallEnum.PayStatus.PENDING.getValue())
                .setPaySource(MallEnum.PaySource.PAY_SIM.getValue())
                .setCurrency("CNY")
                .setPayExpireTime(payExpireTime);
        paymentMapper.insert(payment);

        // 清理本次已下单的购物车行（未下单的行保留）
        for (MallCheckoutBo.Line line : lines) {
            cartItemMapper.removeBySku(userId, line.getSkuId(), now);
        }

        domainEventService.recordReliableInTx(OpsEnum.EventType.MALL, "MALLORDER:" + orderNo,
                "创建订单 " + lines.size() + " 个规格",
                "应付 " + order.getOrderAmountFen() + " 分，履约仓 " + warehouse.getWarehouseName());
        return orderVoOf(order, items, payment, warehouse.getWarehouseName(), coverUrlOf(items));
    }

    /** 单 SKU 原子预占 + 只增流水；影响 0 行即库存不足，抛出让整事务回滚。 */
    private void reserveOne(WsMallOrder order, Long warehouseId, MallCheckoutBo.Line line,
                            Long operator, String now) {
        long qty = line.getQuantity();
        int reserved = stockMapper.reserveAtomic(warehouseId, line.getSkuId(), qty, operator, now);
        if (reserved != 1) {
            throw new JbkException("库存不足，下单失败（请调整数量后重试）");
        }
        WsMallStock after = stockMapper.selectOne(Wrappers.lambdaQuery(WsMallStock.class)
                .eq(WsMallStock::getWarehouseId, warehouseId)
                .eq(WsMallStock::getSkuId, line.getSkuId()));
        if (ObjectUtil.isNull(after)) {
            throw new JbkException("库存行读取失败，下单中止");
        }
        WsMallStockFlow flow = new WsMallStockFlow()
                .setBizIdempotencyKey(RESERVE_KEY_PREFIX + order.getOrderNo() + ":" + line.getSkuId())
                .setWarehouseId(warehouseId)
                .setSkuId(line.getSkuId())
                .setFlowType(MallEnum.StockFlowType.ORDER_RESERVE.getValue())
                .setAvailableChange(-qty)
                .setReservedChange(qty)
                .setAvailableAfter(after.getAvailableQty())
                .setReservedAfter(after.getReservedQty())
                .setFlowReason("下单预占 " + order.getOrderNo())
                .setOperatorId(operator);
        // 动作时间与库存终值同源；其余审计字段由 BaseEntity 填充策略统一写入
        flow.setCreateTime(now);
        try {
            flowMapper.insert(flow);
        }
        catch (DuplicateKeyException race) {
            // 同订单同 SKU 的预占键已存在=同一请求号的并发副本，同样交给编排层重读
            throw new DuplicateRequestException();
        }
    }

    // ==================== 取消 ====================

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public MallOrderVo cancelByUser(Long userId, MallOrderActionBo bo) {
        WsMallOrder order = requireOwnOrder(userId, bo.getOrderNo());
        String reason = StrUtil.blankToDefault(StrUtil.trim(bo.getCancelReason()), "用户取消");
        return releaseAndClose(order, MallEnum.OrderStatus.CANCELLED.getValue(), reason, userId, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public MallOrderVo closeByAuthority(String orderNo, String reason) {
        WsMallOrder order = orderMapper.selectByOrderNoIncludingDeleted(orderNo);
        if (ObjectUtil.isNull(order)) {
            throw new JbkException("订单不存在");
        }
        // 操作人记 0（系统）：与人工取消区分开，台账能看出是谁关的。
        // ownerUserId 传 null：系统关单不按归属收窄，否则订单主人被停用时永远关不掉
        return releaseAndClose(order, MallEnum.OrderStatus.CANCELLED.getValue(), reason, 0L, null);
    }

    /**
     * 取消/关闭共用：先 CAS 订单状态（待支付→终态），命中后再释放预占。
     *
     * <p>顺序不能反：先释放再改状态的话，两个并发取消都会释放一次，库存凭空多出一份。
     * CAS 只有一条能命中，释放因此天然只发生一次。</p>
     */
    MallOrderVo releaseAndClose(WsMallOrder order, int toStatus, String reason, Long operator,
                                Long ownerUserId) {
        if (!ObjectUtil.equal(order.getOrderStatus(), MallEnum.OrderStatus.PENDING_PAY.getValue())) {
            throw new JbkException("当前订单状态不可取消");
        }
        // 支付单已成功=钱已经收了，只是订单还没被事务B 推进（两段式之间的窗口，可长达一个重试退避）。
        // 此刻放行取消会造成"钱收了 + 单取消了 + 货放回去被别人买走"的裸奔终态，且没有任何自动冲正。
        WsMallPayment payment = paymentMapper.selectByOrderNoIncludingDeleted(order.getOrderNo());
        if (ObjectUtil.isNull(payment)) {
            throw new JbkException("支付单缺失，订单处理中止");
        }
        if (ObjectUtil.equal(payment.getPayStatus(), MallEnum.PayStatus.SUCCESS.getValue())) {
            throw new JbkException("订单已支付，无法取消；请稍候刷新查看支付结果");
        }
        String now = DateUtils.time();
        int moved = ownerUserId != null
                ? orderMapper.casCancelByOwner(order.getId(), ownerUserId,
                        MallEnum.OrderStatus.PENDING_PAY.getValue(), toStatus, reason, operator, now)
                : orderMapper.casCancel(order.getId(),
                        MallEnum.OrderStatus.PENDING_PAY.getValue(), toStatus, reason, operator, now);
        if (moved != 1) {
            // 并发路径已把订单推向别的终态（支付成功或已取消）：不重复释放
            throw new JbkException("订单状态已变化，请刷新后查看");
        }
        // 影响行数即裁决，与订单 CAS 同一口径：0 行说明支付单在上面那次读之后被置成了成功，
        // 此刻整事务回滚（含刚命中的订单 CAS 与尚未执行的释放），订单退回待支付让事务B 正常推进
        int closed = paymentMapper.casClose(payment.getId(), operator, now);
        if (closed != 1) {
            throw new JbkException("支付状态已变化（可能已支付成功），取消已中止，请刷新后查看");
        }

        List<WsMallOrderItem> items = orderItemMapper.selectByOrderIdOrderBySku(order.getId());
        for (WsMallOrderItem item : items) {
            releaseOne(order, item, operator, now);
        }
        domainEventService.recordReliableInTx(OpsEnum.EventType.MALL,
                "MALLORDER:" + order.getOrderNo(), "订单取消", reason);

        WsMallOrder latest = orderMapper.selectById(order.getId());
        return orderVoOf(latest, items,
                paymentMapper.selectByOrderNoIncludingDeleted(order.getOrderNo()),
                warehouseNameOf(order.getWarehouseId()), coverUrlOf(items));
    }

    /** 单 SKU 原子释放 + 只增流水；幂等键 MALLREL 撞键=已释放过，按幂等跳过。 */
    private void releaseOne(WsMallOrder order, WsMallOrderItem item, Long operator, String now) {
        String bizKey = RELEASE_KEY_PREFIX + order.getOrderNo() + ":" + item.getSkuId();
        if (ObjectUtil.isNotNull(flowMapper.selectByKeyIncludingDeleted(bizKey))) {
            return;
        }
        long qty = item.getQuantity();
        int released = stockMapper.releaseAtomic(order.getWarehouseId(), item.getSkuId(),
                qty, operator, now);
        if (released != 1) {
            // 预占不足以释放：证据链异常（预占流水在、预占量却没了），必须整事务回滚人工核查
            throw new JbkException("预占释放失败（库存证据异常），请人工核查订单 " + order.getOrderNo());
        }
        WsMallStock after = stockMapper.selectOne(Wrappers.lambdaQuery(WsMallStock.class)
                .eq(WsMallStock::getWarehouseId, order.getWarehouseId())
                .eq(WsMallStock::getSkuId, item.getSkuId()));
        WsMallStockFlow flow = new WsMallStockFlow()
                .setBizIdempotencyKey(bizKey)
                .setWarehouseId(order.getWarehouseId())
                .setSkuId(item.getSkuId())
                .setFlowType(MallEnum.StockFlowType.ORDER_RELEASE.getValue())
                .setAvailableChange(qty)
                .setReservedChange(-qty)
                .setAvailableAfter(after.getAvailableQty())
                .setReservedAfter(after.getReservedQty())
                .setFlowReason("预占释放 " + order.getOrderNo())
                .setOperatorId(operator);
        flow.setCreateTime(now);
        try {
            flowMapper.insert(flow);
        }
        catch (DuplicateKeyException race) {
            throw new JbkException("订单正在处理中，请稍后再试");
        }
    }

    // ==================== 查询 ====================

    @Override
    public PageDataVo<MallOrderVo> pageForUser(Long userId, MallOrderQueryBo bo) {
        return pageInternal(bo, userId);
    }

    @Override
    public PageDataVo<MallOrderVo> pageForAdmin(MallOrderQueryBo bo) {
        return pageInternal(bo, null);
    }

    private PageDataVo<MallOrderVo> pageInternal(MallOrderQueryBo bo, Long scopedUserId) {
        Long userFilter = scopedUserId != null ? scopedUserId : bo.getUserId();
        Page<WsMallOrder> page = orderMapper.selectPage(
                new Page<>(bo.getCurrent() == null ? 1 : Math.max(1, bo.getCurrent()),
                        bo.getSize() == null ? 10 : Math.min(Math.max(1, bo.getSize()), 100)),
                Wrappers.lambdaQuery(WsMallOrder.class)
                        .eq(userFilter != null, WsMallOrder::getUserId, userFilter)
                        .eq(bo.getOrderStatus() != null, WsMallOrder::getOrderStatus, bo.getOrderStatus())
                        .eq(bo.getWarehouseId() != null, WsMallOrder::getWarehouseId, bo.getWarehouseId())
                        .eq(StrUtil.isNotBlank(bo.getOrderNo()), WsMallOrder::getOrderNo, bo.getOrderNo())
                        .orderByDesc(WsMallOrder::getId));
        List<WsMallOrder> records = page.getRecords();
        if (records.isEmpty()) {
            return new PageDataVo<>(List.of(), page.getTotal());
        }
        List<Long> orderIds = records.stream().map(WsMallOrder::getId).toList();
        Map<Long, List<WsMallOrderItem>> itemsByOrder = orderItemMapper.selectList(
                        Wrappers.lambdaQuery(WsMallOrderItem.class)
                                .in(WsMallOrderItem::getOrderId, orderIds)
                                .orderByAsc(WsMallOrderItem::getSkuId))
                .stream().collect(Collectors.groupingBy(WsMallOrderItem::getOrderId));
        Map<String, WsMallPayment> paymentsByNo = paymentMapper.selectList(
                        Wrappers.lambdaQuery(WsMallPayment.class)
                                .in(WsMallPayment::getOrderNo, records.stream()
                                        .map(WsMallOrder::getOrderNo).toList()))
                .stream().collect(Collectors.toMap(WsMallPayment::getOrderNo, Function.identity()));
        Map<Long, String> warehouseNames = warehouseNames();
        // 批量取首个商品主图，避免逐行查商品表
        List<Long> firstProductIds = itemsByOrder.values().stream()
                .filter(list -> !list.isEmpty()).map(list -> list.get(0).getProductId())
                .distinct().toList();
        Map<Long, String> coverByProduct = firstProductIds.isEmpty() ? Map.of()
                : productMapper.selectList(Wrappers.lambdaQuery(WsMallProduct.class)
                                .in(WsMallProduct::getId, firstProductIds)).stream()
                        .filter(p -> p.getCoverUrl() != null)
                        .collect(Collectors.toMap(WsMallProduct::getId, WsMallProduct::getCoverUrl));
        List<MallOrderVo> rows = records.stream()
                .map(o -> {
                    List<WsMallOrderItem> rowItems = itemsByOrder.getOrDefault(o.getId(), List.of());
                    String cover = rowItems.isEmpty() ? null
                            : coverByProduct.get(rowItems.get(0).getProductId());
                    return orderVoOf(o, rowItems, paymentsByNo.get(o.getOrderNo()),
                            warehouseNames.get(o.getWarehouseId()), cover);
                })
                .toList();
        return new PageDataVo<>(rows, page.getTotal());
    }

    @Override
    public MallOrderDetailVo detailForUser(Long userId, String orderNo) {
        return detailOf(requireOwnOrder(userId, orderNo));
    }

    @Override
    public MallOrderDetailVo detailForAdmin(String orderNo) {
        WsMallOrder order = orderMapper.selectOne(Wrappers.lambdaQuery(WsMallOrder.class)
                .eq(WsMallOrder::getOrderNo, orderNo));
        if (ObjectUtil.isNull(order)) {
            throw new JbkException("订单不存在");
        }
        return detailOf(order);
    }

    private MallOrderDetailVo detailOf(WsMallOrder order) {
        List<WsMallOrderItem> items = orderItemMapper.selectByOrderIdOrderBySku(order.getId());
        WsMallPayment payment = paymentMapper.selectByOrderNoIncludingDeleted(order.getOrderNo());
        return new MallOrderDetailVo()
                .setSummary(orderVoOf(order, items, payment,
                        warehouseNameOf(order.getWarehouseId()), coverUrlOf(items)))
                .setReceiverRegion(order.getReceiverRegion())
                .setReceiverAddress(order.getReceiverAddress())
                .setReceiverDistrictCode(order.getReceiverDistrictCode())
                .setCancelReason(order.getCancelReason())
                .setPaySource(ObjectUtil.isNull(payment) ? null : payment.getPaySource())
                .setTransactionId(ObjectUtil.isNull(payment) ? null : payment.getTransactionId())
                .setPaySuccessTime(ObjectUtil.isNull(payment) ? null : payment.getPaySuccessTime())
                .setItems(items.stream().map(it -> new MallOrderDetailVo.OrderItemLine()
                        .setOrderItemId(String.valueOf(it.getId()))
                        .setProductId(String.valueOf(it.getProductId()))
                        .setSkuId(String.valueOf(it.getSkuId()))
                        .setProductName(it.getProductName())
                        .setSkuName(it.getSkuName())
                        .setSpecs(MallSpecSnapshot.decode(it.getSpecSnap()))
                        .setUnitPriceFen(it.getUnitPriceFen())
                        .setQuantity(it.getQuantity())
                        .setItemAmountFen(it.getItemAmountFen())
                        .setWeightGram(it.getWeightGram())).toList());
    }

    /** 归属校验：他人订单一律按不存在处理，不泄露"存在但不是你的"。 */
    WsMallOrder requireOwnOrder(Long userId, String orderNo) {
        WsMallOrder order = orderMapper.selectOne(Wrappers.lambdaQuery(WsMallOrder.class)
                .eq(WsMallOrder::getOrderNo, orderNo)
                .eq(WsMallOrder::getUserId, userId));
        if (ObjectUtil.isNull(order)) {
            throw new JbkException("订单不存在");
        }
        return order;
    }

    // ==================== 内部装配 ====================

    /** 请求行规范化：去空、查重、按 SKU 升序（升序即锁序）。 */
    private List<MallCheckoutBo.Line> normalizeLines(MallCheckoutBo bo) {
        if (ObjectUtil.isNull(bo.getLines()) || bo.getLines().isEmpty()) {
            throw new JbkException("请选择要购买的商品");
        }
        Set<Long> seen = new HashSet<>();
        for (MallCheckoutBo.Line line : bo.getLines()) {
            if (ObjectUtil.isNull(line.getSkuId()) || ObjectUtil.isNull(line.getQuantity())) {
                throw new JbkException("下单行不完整");
            }
            if (!seen.add(line.getSkuId())) {
                throw new JbkException("同一规格重复提交，请合并数量后重试");
            }
        }
        return bo.getLines().stream()
                .sorted(Comparator.comparing(MallCheckoutBo.Line::getSkuId))
                .toList();
    }

    /** 读取并校验 SKU 与商品的权威状态；任一不可售即整单拒绝。 */
    private Catalog loadCatalog(List<MallCheckoutBo.Line> lines) {
        List<Long> skuIds = lines.stream().map(MallCheckoutBo.Line::getSkuId).toList();
        Map<Long, WsMallSku> skus = skuMapper.selectList(Wrappers.lambdaQuery(WsMallSku.class)
                        .in(WsMallSku::getId, skuIds))
                .stream().collect(Collectors.toMap(WsMallSku::getId, Function.identity()));
        if (skus.size() != skuIds.size()) {
            throw new JbkException("部分商品已下架，请返回购物车确认");
        }
        Map<Long, WsMallProduct> products = productMapper.selectList(
                        Wrappers.lambdaQuery(WsMallProduct.class)
                                .in(WsMallProduct::getId, skus.values().stream()
                                        .map(WsMallSku::getProductId).distinct().toList()))
                .stream().collect(Collectors.toMap(WsMallProduct::getId, Function.identity()));
        for (WsMallSku sku : skus.values()) {
            WsMallProduct product = products.get(sku.getProductId());
            if (ObjectUtil.isNull(product)
                    || !ObjectUtil.equal(product.getProductStatus(),
                            MallEnum.ProductStatus.PUBLISHED.getValue())) {
                throw new JbkException("部分商品已下架，请返回购物车确认");
            }
            if (!ObjectUtil.equal(sku.getSkuStatus(), MallEnum.SkuStatus.ENABLED.getValue())) {
                throw new JbkException("部分规格已停售，请返回购物车确认");
            }
            // 规格快照在这里就要能解码：创单是把它原样复制进订单明细的最后一道关口，
            // 放行损坏快照等于让这张订单的详情页从此永远打不开（读侧是 fail-closed 的）
            MallSpecSnapshot.decode(sku.getSpecSnap());
        }
        return new Catalog(skus, products);
    }

    /**
     * 选仓：启用 + 履约区县命中 + 能整单满足；多个满足时按仓库 ID 升序取第一个。
     * 返回 null 表示无仓可履约（调用方 fail-closed）。
     */
    private WsMallWarehouse pickWarehouse(String districtCode, List<MallCheckoutBo.Line> lines) {
        List<WsMallWarehouse> enabled = warehouseMapper.selectList(
                        Wrappers.lambdaQuery(WsMallWarehouse.class)
                                .eq(WsMallWarehouse::getWarehouseStatus,
                                        MallEnum.WarehouseStatus.ENABLED.getValue())
                                .orderByAsc(WsMallWarehouse::getId))
                .stream()
                .filter(w -> MallWarehouseServiceImpl.parseScopeDistricts(w.getServiceScopeJson())
                        .contains(districtCode))
                .toList();
        if (enabled.isEmpty()) {
            return null;
        }
        List<Long> skuIds = lines.stream().map(MallCheckoutBo.Line::getSkuId).toList();
        List<WsMallStock> stocks = stockMapper.selectList(Wrappers.lambdaQuery(WsMallStock.class)
                .in(WsMallStock::getWarehouseId, enabled.stream().map(WsMallWarehouse::getId).toList())
                .in(WsMallStock::getSkuId, skuIds));
        Map<String, Long> availableByKey = stocks.stream().collect(Collectors.toMap(
                st -> st.getWarehouseId() + ":" + st.getSkuId(), WsMallStock::getAvailableQty,
                (a, b) -> a));
        for (WsMallWarehouse warehouse : enabled) {
            boolean fulfillable = lines.stream().allMatch(line ->
                    availableByKey.getOrDefault(warehouse.getId() + ":" + line.getSkuId(), 0L)
                            >= line.getQuantity());
            if (fulfillable) {
                return warehouse;
            }
        }
        return null;
    }

    /** 重放等价：地址与全部行（SKU+数量）逐字一致，否则拒绝。 */
    private void assertReplayEquivalent(WsMallOrder existed, Long addressId,
                                        List<MallCheckoutBo.Line> lines) {
        if (!ObjectUtil.equal(existed.getAddressId(), addressId)) {
            throw new JbkException("同请求号的收货地址与原订单不一致，已拒绝（请更换请求号）");
        }
        List<WsMallOrderItem> items = orderItemMapper.selectByOrderIdOrderBySku(existed.getId());
        if (items.size() != lines.size()) {
            throw new JbkException("同请求号的商品与原订单不一致，已拒绝（请更换请求号）");
        }
        for (int i = 0; i < items.size(); i++) {
            if (!ObjectUtil.equal(items.get(i).getSkuId(), lines.get(i).getSkuId())
                    || !ObjectUtil.equal(items.get(i).getQuantity(), lines.get(i).getQuantity())) {
                throw new JbkException("同请求号的商品或数量与原订单不一致，已拒绝（请更换请求号）");
            }
        }
    }

    private Long paymentIdOf(String orderNo) {
        WsMallPayment payment = paymentMapper.selectByOrderNoIncludingDeleted(orderNo);
        if (ObjectUtil.isNull(payment)) {
            throw new JbkException("支付单缺失，订单处理中止");
        }
        return payment.getId();
    }

    private String warehouseNameOf(Long warehouseId) {
        WsMallWarehouse warehouse = warehouseMapper.selectById(warehouseId);
        return ObjectUtil.isNull(warehouse) ? null : warehouse.getWarehouseName();
    }

    private Map<Long, String> warehouseNames() {
        return warehouseMapper.selectList(Wrappers.lambdaQuery(WsMallWarehouse.class)).stream()
                .collect(Collectors.toMap(WsMallWarehouse::getId, WsMallWarehouse::getWarehouseName));
    }

    static MallOrderVo orderVoOf(WsMallOrder order, List<WsMallOrderItem> items,
                                 WsMallPayment payment, String warehouseName,
                                 String firstCoverUrl) {
        WsMallOrderItem first = items.isEmpty() ? null : items.get(0);
        return new MallOrderVo()
                .setId(String.valueOf(order.getId()))
                .setOrderNo(order.getOrderNo())
                .setUserId(String.valueOf(order.getUserId()))
                .setOrderStatus(order.getOrderStatus())
                .setPayStatus(ObjectUtil.isNull(payment) ? null : payment.getPayStatus())
                .setProductAmountFen(order.getProductAmountFen())
                .setDeliveryFeeFen(order.getDeliveryFeeFen())
                .setOrderAmountFen(order.getOrderAmountFen())
                .setItemKindCount(items.size())
                .setFirstProductName(ObjectUtil.isNull(first) ? null : first.getProductName())
                .setFirstCoverUrl(firstCoverUrl)
                .setWarehouseName(warehouseName)
                .setReceiverName(order.getReceiverName())
                .setMaskedPhone(PhoneMask.mask(order.getReceiverPhone()))
                .setPayExpireTime(order.getPayExpireTime())
                .setCreateTime(order.getCreateTime())
                .setCancelTime(order.getCancelTime());
    }

    /** 创单期间的权威商品快照：一次读齐，避免逐行查询与读到不同时刻的价格。 */
    private record Catalog(Map<Long, WsMallSku> skus, Map<Long, WsMallProduct> products) {

        WsMallSku sku(Long skuId) {
            WsMallSku sku = skus.get(skuId);
            if (ObjectUtil.isNull(sku)) {
                throw new JbkException("商品规格不存在");
            }
            return sku;
        }

        WsMallProduct product(Long productId) {
            WsMallProduct product = products.get(productId);
            if (ObjectUtil.isNull(product)) {
                throw new JbkException("商品不存在");
            }
            return product;
        }

        long amountOf(List<MallCheckoutBo.Line> lines) {
            long total = 0L;
            for (MallCheckoutBo.Line line : lines) {
                total = MallMoney.add(total,
                        MallMoney.multiply(sku(line.getSkuId()).getSalePrice(), line.getQuantity()));
            }
            return total;
        }

        List<MiniMallCartVo.CartLine> toCartLines(List<MallCheckoutBo.Line> lines) {
            List<MiniMallCartVo.CartLine> rows = new ArrayList<>(lines.size());
            for (MallCheckoutBo.Line line : lines) {
                WsMallSku sku = sku(line.getSkuId());
                WsMallProduct product = product(sku.getProductId());
                rows.add(new MiniMallCartVo.CartLine()
                        .setSkuId(String.valueOf(sku.getId()))
                        .setProductId(String.valueOf(product.getId()))
                        .setProductName(product.getProductName())
                        .setSkuName(sku.getSkuName())
                        .setSpecs(MallSpecSnapshot.decode(sku.getSpecSnap()))
                        .setCoverUrl(product.getCoverUrl())
                        .setSalePriceFen(sku.getSalePrice())
                        .setQuantity(line.getQuantity())
                        .setItemAmountFen(MallMoney.multiply(sku.getSalePrice(), line.getQuantity()))
                        .setPurchasable(true));
            }
            return rows;
        }
    }
}
