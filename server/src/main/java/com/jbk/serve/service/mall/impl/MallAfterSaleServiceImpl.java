package com.jbk.serve.service.mall.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.jbk.serve.mapper.mall.WsMallAfterSaleItemMapper;
import com.jbk.serve.mapper.mall.WsMallAfterSaleMapper;
import com.jbk.serve.mapper.mall.WsMallAfterSaleTraceMapper;
import com.jbk.serve.mapper.mall.WsMallFulfillmentMapper;
import com.jbk.serve.mapper.mall.WsMallOrderItemMapper;
import com.jbk.serve.mapper.mall.WsMallOrderMapper;
import com.jbk.serve.mapper.mall.WsMallPaymentMapper;
import com.jbk.serve.mapper.mall.WsMallRefundMapper;
import com.jbk.serve.mapper.mall.WsMallWarehouseOperatorMapper;
import com.jbk.serve.service.mall.IMallAfterSaleService;
import com.jbk.serve.service.mall.IMallExchangeTx;
import com.jbk.serve.service.message.IWsMessageService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.mall.MallEnum;
import com.jbk.tool.consts.message.MessageEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.mall.bo.MallAfterSaleAbortBo;
import com.jbk.tool.data.mall.bo.MallAfterSaleApplyBo;
import com.jbk.tool.data.mall.bo.MallAfterSaleAuditBo;
import com.jbk.tool.data.mall.bo.MallAfterSaleInspectBo;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.mall.bo.MallAfterSaleNoBo;
import com.jbk.tool.data.mall.bo.MallAfterSaleQueryBo;
import com.jbk.tool.data.mall.po.WsMallAfterSale;
import com.jbk.tool.data.mall.po.WsMallAfterSaleItem;
import com.jbk.tool.data.mall.po.WsMallAfterSaleTrace;
import com.jbk.tool.data.mall.po.WsMallFulfillment;
import com.jbk.tool.data.mall.po.WsMallOrder;
import com.jbk.tool.data.mall.po.WsMallOrderItem;
import com.jbk.tool.data.mall.po.WsMallPayment;
import com.jbk.tool.data.mall.po.WsMallRefund;
import com.jbk.tool.data.mall.vo.MallAfterSaleVo;
import com.jbk.tool.data.mall.vo.MallFulfillTraceVo;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商城售后服务实现（E2E-09 S4）。三条不可越过的线：金额只来自原订单不可变明细的
 * 单价×批准数量（入参无金额字段）；退款与库存分开判（可重新销售才回库，不可销售照退不回库）；
 * 累计不越界（同明细处理中+已成功 ≤ 购买数量，同订单累计成功退款 ≤ 实付，事务内当前读判定）。
 * 状态推进只有一种写法：精确前态+版本号 CAS 判影响行数，轨迹/消息/审计写在 CAS 命中之后，
 * 轨迹幂等键被占用即证据冲突整事务回滚。
 *
 * @author dakang
 * @since 2026-08-10
 */
@Slf4j
@Service
public class MallAfterSaleServiceImpl implements IMallAfterSaleService {

    /** 售后节点审计幂等键前缀。 */
    static final String AUDIT_KEY_PREFIX = "MALL_AFTERSALE:";
    private static final long SYSTEM_OPERATOR = 0L;

    /**
     * 售后申请窗（天）：**内部模拟默认值**，可配置。
     * 甲方正式规则未定，这个 7 不得对外表述为最终业务规则。
     */
    @Value("${mall.after-sale-window-days:7}")
    private int afterSaleWindowDays;

    @Autowired
    private WsMallAfterSaleMapper afterSaleMapper;
    @Autowired
    private WsMallAfterSaleItemMapper afterSaleItemMapper;
    @Autowired
    private WsMallAfterSaleTraceMapper traceMapper;
    @Autowired
    private WsMallOrderMapper orderMapper;
    @Autowired
    private MallAfterSaleTraceWriter traceWriter;
    @Autowired
    private WsMallOrderItemMapper orderItemMapper;
    @Autowired
    private WsMallPaymentMapper paymentMapper;
    @Autowired
    private WsMallFulfillmentMapper fulfillMapper;
    @Autowired
    private WsMallRefundMapper refundMapper;
    @Autowired
    private WsMallWarehouseOperatorMapper whOperatorMapper;
    @Autowired
    private MallAfterSaleStock afterSaleStock;
    @Autowired
    private IMallExchangeTx exchangeTx;
    @Autowired
    private IWsMessageService messageService;
    @Autowired
    private IWsDomainEventService domainEventService;

    // ==================== 申请与取消 ====================

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public MallAfterSaleVo apply(Long userId, MallAfterSaleApplyBo bo) {
        if (!MallEnum.AfterSaleType.isKnown(bo.getAfterSaleType())) {
            throw new JbkException("售后类型不合法");
        }
        String afterSaleNo = MallAfterSaleNo.derive(userId, bo.getRequestId());
        WsMallAfterSale existed = afterSaleMapper.selectByNoIncludingDeleted(afterSaleNo);
        if (ObjectUtil.isNotNull(existed)) {
            // 同 requestId 重放：逐字核对不可变内容后原样复用，改参一律拒绝
            requireSameApply(existed, bo);
            return detailForUser(userId, existed.getAfterSaleNo());
        }

        // 先取订单行锁，再判资格与累计上限：数量与金额的累计不变式没有单条唯一键可表达，
        // 只能靠把同一订单的售后申请串行化来保证。锁在最外层拿，锁序恒为「订单 → 明细」。
        WsMallOrder order = orderMapper.selectByOrderNoForUpdate(bo.getOrderNo());
        if (ObjectUtil.isNull(order) || !ObjectUtil.equal(order.getDataStatus(), 0)
                || !ObjectUtil.equal(order.getUserId(), userId)) {
            throw new JbkException("订单不存在");
        }
        if (ObjectUtil.isNotNull(order.getSourceAfterSaleId())) {
            throw new JbkException("换货补发单不支持再次申请售后");
        }
        WsMallPayment payment = paymentMapper.selectByOrderNoIncludingDeleted(bo.getOrderNo());
        String payMismatch = MallAfterSaleGate.paymentMismatch(order, payment);
        if (payMismatch != null) {
            throw new JbkException(payMismatch);
        }
        String now = DateUtils.time();
        requireApplicable(order, bo.getAfterSaleType(), now);

        List<WsMallOrderItem> orderItems = orderItemMapper.selectByOrderIdOrderBySku(order.getId());
        if (orderItems.isEmpty()) {
            throw new JbkException("订单无明细，无法申请售后");
        }
        Map<Long, Integer> requested = requestedQuantities(bo, orderItems);

        WsMallAfterSale afterSale = new WsMallAfterSale()
                .setAfterSaleNo(afterSaleNo)
                .setUserId(userId)
                .setRequestId(bo.getRequestId())
                .setOrderId(order.getId())
                .setOrderNo(order.getOrderNo())
                .setWarehouseId(order.getWarehouseId())
                .setAfterSaleType(bo.getAfterSaleType())
                .setAfterSaleStatus(MallEnum.AfterSaleStatus.PENDING_AUDIT.getValue())
                .setVersion(1)
                .setApplyReason(StrUtil.trim(bo.getApplyReason()))
                .setRefundAmountFen(0L)
                .setApplyTime(now);
        afterSale.setCreateTime(now);
        try {
            afterSaleMapper.insert(afterSale);
        }
        catch (DuplicateKeyException race) {
            WsMallAfterSale winner = afterSaleMapper.selectByNoIncludingDeleted(afterSaleNo);
            if (ObjectUtil.isNull(winner)) {
                throw new JbkException("售后申请冲突，请重试");
            }
            requireSameApply(winner, bo);
            return detailForUser(userId, winner.getAfterSaleNo());
        }

        long refundAmount = insertItems(afterSale, orderItems, requested, now);
        // 金额落库前先判累计：多次部分退款累计不得超过原订单商品实付金额
        long succeeded = afterSaleMapper.sumSucceededRefundFen(order.getId());
        long cap = order.getProductAmountFen() == null ? 0L : order.getProductAmountFen();
        if (MallMoney.add(succeeded, refundAmount) > cap) {
            throw new JbkException("累计退款金额将超过本单商品实付金额，申请已拒绝");
        }
        afterSale.setRefundAmountFen(refundAmount);
        WsMallAfterSale amountUpdate = new WsMallAfterSale().setRefundAmountFen(refundAmount);
        amountUpdate.setId(afterSale.getId());
        afterSaleMapper.updateById(amountUpdate);

        writeTrace(afterSale, MallEnum.AfterSaleStatus.PENDING_AUDIT, MallEnum.ActorType.USER,
                userId, null, now, "用户提交售后申请");
        writeAudit(afterSale, MallEnum.AfterSaleStatus.PENDING_AUDIT, OpsEnum.ActorPortal.USER,
                userId, "售后申请：" + typeName(afterSale.getAfterSaleType()));
        return detailForUser(userId, afterSaleNo);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public MallAfterSaleVo cancelByUser(Long userId, MallAfterSaleNoBo bo) {
        WsMallAfterSale afterSale = requireOwnAfterSale(userId, bo.getAfterSaleNo());
        String now = DateUtils.time();
        advance(afterSale, MallEnum.AfterSaleStatus.PENDING_AUDIT,
                MallEnum.AfterSaleStatus.CANCELLED, userId, now);
        writeTrace(afterSale, MallEnum.AfterSaleStatus.CANCELLED, MallEnum.ActorType.USER,
                userId, null, now, "用户撤销售后申请");
        writeAudit(afterSale, MallEnum.AfterSaleStatus.CANCELLED, OpsEnum.ActorPortal.USER,
                userId, "用户撤销售后申请");
        return detailForUser(userId, afterSale.getAfterSaleNo());
    }

    // ==================== PC 审核 / 收货 / 质检 ====================

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public MallAfterSaleVo audit(Long operatorId, MallAfterSaleAuditBo bo) {
        WsMallAfterSale afterSale = requireManageAfterSale(operatorId, bo.getAfterSaleNo());
        // 先取订单行锁再判：申请时那把锁在申请事务提交时就放了，申请与审核之间订单可以
        // 一路被拣货、发货、签收。不在锁内重读，审核就是在对一份过期快照做资金决定。
        orderMapper.selectByOrderNoForUpdate(afterSale.getOrderNo());
        WsMallOrder order = requireLinkedOrder(afterSale, null);
        String now = DateUtils.time();
        boolean approved = Boolean.TRUE.equals(bo.getApproved());
        String remark = StrUtil.emptyToNull(StrUtil.trim(bo.getRemark()));
        if (!approved && remark == null) {
            throw new JbkException("驳回必须填写原因");
        }

        if (approved) {
            // 状态资格必须在锁内重验：申请时「未拣货」不等于审核时仍未拣货。
            // 不重验的代价是同一批货被退款、回库并实际发出三重结算，且账面完全自洽。
            requireStillApplicable(order, afterSale.getAfterSaleType());
            // 审核通过等于承诺一笔退款。原支付单缺失或已失效时通过审核，
            // 就是在承诺一笔退不出去的钱——闸必须放在这里，而不是等到质检后才发现。
            WsMallPayment payment = paymentMapper.selectByOrderNoIncludingDeleted(
                    order.getOrderNo());
            String payMiss = MallAfterSaleGate.paymentMismatch(order, payment);
            if (payMiss != null) {
                throw new JbkException(payMiss);
            }
        }

        MallEnum.AfterSaleStatus target;
        if (!approved) {
            target = MallEnum.AfterSaleStatus.REJECTED;
        }
        else if (ObjectUtil.equal(afterSale.getAfterSaleType(),
                MallEnum.AfterSaleType.CANCEL_REFUND.getValue())) {
            // 未拣货整单取消：货从未离仓，审核通过即可回库并转退款，没有「待退货/待质检」可言
            target = MallEnum.AfterSaleStatus.REFUNDING;
        }
        else {
            target = MallEnum.AfterSaleStatus.PENDING_RETURN;
        }

        int moved = afterSaleMapper.casAudit(afterSale.getId(),
                MallEnum.AfterSaleStatus.PENDING_AUDIT.getValue(), target.getValue(),
                afterSale.getVersion(), remark, approved ? null : remark,
                approved ? null : now, operatorId, now);
        if (moved != 1) {
            throw new JbkException("售后状态已变化（当前不是待审核），请刷新后重试");
        }
        afterSale.setAfterSaleStatus(target.getValue()).setVersion(afterSale.getVersion() + 1);

        if (target == MallEnum.AfterSaleStatus.REFUNDING) {
            // 同事务把订单推出 2「已支付待履约」：拣货的唯一前置就是订单精确处于 2，
            // 不推走就等于审核通过之后这张单仍然可以被正常发货，钱退了货也走了。
            // 退款成功后事务B 再把它从 5 推到 6「已全额退款」。
            int cancelled = orderMapper.casStatus(order.getId(),
                    MallEnum.OrderStatus.PAID.getValue(),
                    MallEnum.OrderStatus.CANCELLED.getValue(), operatorId, now);
            if (cancelled != 1) {
                throw new JbkException("订单状态已变化，取消审核已中止，请刷新后重试");
            }
            restockAll(afterSale, operatorId, now, "未拣货取消，货未离仓直接回库");
            createPendingRefund(afterSale, order, operatorId, now);
        }
        writeTrace(afterSale, target, MallEnum.ActorType.WAREHOUSE, operatorId, null, now,
                approved ? "审核通过" : "审核驳回：" + remark);
        writeAudit(afterSale, target, OpsEnum.ActorPortal.MANAGE, operatorId,
                approved ? "售后审核通过" : "售后审核驳回");
        notifyUser(afterSale, now, approved ? "商城售后已受理" : "商城售后未通过",
                approved ? "您的售后申请已受理，请按提示寄回商品。" : "您的售后申请未通过：" + remark);
        return detailForManage(operatorId, afterSale.getAfterSaleNo());
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public MallAfterSaleVo abortExchange(Long operatorId, MallAfterSaleAbortBo bo) {
        WsMallAfterSale afterSale = requireManageAfterSale(operatorId, bo.getAfterSaleNo());
        requireLinkedOrder(afterSale, null);
        if (!ObjectUtil.equal(afterSale.getAfterSaleStatus(),
                MallEnum.AfterSaleStatus.EXCHANGING.getValue())) {
            throw new JbkException("售后单不处于换货补发中，无需中止");
        }
        WsMallOrder reshipment = orderMapper.selectBySourceAfterSale(afterSale.getId());
        if (ObjectUtil.isNull(reshipment)) {
            throw new JbkException("补发单不存在，请人工核查");
        }
        WsMallFulfillment task = fulfillMapper.selectByOrderNoIncludingDeleted(
                reshipment.getOrderNo());
        if (ObjectUtil.isNotNull(task) && ObjectUtil.equal(task.getDataStatus(), 0)
                && ObjectUtil.equal(task.getFulfillStatus(),
                        MallEnum.FulfillStatus.SIGNED.getValue())) {
            // 已签收就不是「送不出去」而是「已经送到了」，中止等于把已交付的货再退回库存
            throw new JbkException("补发单已签收，换货已完成，不能中止");
        }
        String now = DateUtils.time();
        String reason = StrUtil.trim(bo.getAbortReason());

        // 先把补发单推出可履约态，再释放预占：顺序反过来的话，释放与拣货之间有一个窗口，
        // 库存已经还回可售而补发单还能被正常发货。
        int cancelled = orderMapper.casStatus(reshipment.getId(), reshipment.getOrderStatus(),
                MallEnum.OrderStatus.CANCELLED.getValue(), operatorId, now);
        if (cancelled != 1) {
            throw new JbkException("补发单状态已变化，中止已取消，请刷新后重试");
        }
        for (WsMallAfterSaleItem item : afterSaleItemMapper
                .selectByAfterSaleOrderBySku(afterSale.getId())) {
            afterSaleStock.exchangeRelease(reshipment.getWarehouseId(), item.getSkuId(),
                    item.getQuantity(), afterSale.getAfterSaleNo(), operatorId, now);
        }

        int moved = afterSaleMapper.casFinish(afterSale.getId(),
                MallEnum.AfterSaleStatus.EXCHANGING.getValue(),
                MallEnum.AfterSaleStatus.NEED_MANUAL.getValue(),
                afterSale.getVersion(), operatorId, now);
        if (moved != 1) {
            throw new JbkException("售后状态已变化，中止已取消，请刷新后重试");
        }
        afterSale.setAfterSaleStatus(MallEnum.AfterSaleStatus.NEED_MANUAL.getValue())
                .setVersion(afterSale.getVersion() + 1);
        writeTrace(afterSale, MallEnum.AfterSaleStatus.NEED_MANUAL,
                MallEnum.ActorType.WAREHOUSE, operatorId, null, now, "换货补发已中止：" + reason);
        writeAudit(afterSale, MallEnum.AfterSaleStatus.NEED_MANUAL,
                OpsEnum.ActorPortal.MANAGE, operatorId, "中止换货补发：" + reason);
        notifyUser(afterSale, now, "商城换货需人工处理",
                "您的售后单 " + afterSale.getAfterSaleNo() + " 换货补发已中止，客服将与您联系。");
        return detailForManage(operatorId, afterSale.getAfterSaleNo());
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public MallAfterSaleVo confirmReceive(Long operatorId, MallAfterSaleNoBo bo) {
        WsMallAfterSale afterSale = requireManageAfterSale(operatorId, bo.getAfterSaleNo());
        requireLinkedOrder(afterSale, null);
        String now = DateUtils.time();
        int moved = afterSaleMapper.casReceive(afterSale.getId(),
                MallEnum.AfterSaleStatus.PENDING_RETURN.getValue(),
                MallEnum.AfterSaleStatus.PENDING_INSPECT.getValue(),
                afterSale.getVersion(), operatorId, now);
        if (moved != 1) {
            throw new JbkException("售后状态已变化（当前不是待退货），请刷新后重试");
        }
        afterSale.setAfterSaleStatus(MallEnum.AfterSaleStatus.PENDING_INSPECT.getValue())
                .setVersion(afterSale.getVersion() + 1);
        writeTrace(afterSale, MallEnum.AfterSaleStatus.PENDING_INSPECT,
                MallEnum.ActorType.WAREHOUSE, operatorId, null, now, "前置仓已收到退货，待质检");
        writeAudit(afterSale, MallEnum.AfterSaleStatus.PENDING_INSPECT,
                OpsEnum.ActorPortal.MANAGE, operatorId, "确认收到退货");
        return detailForManage(operatorId, afterSale.getAfterSaleNo());
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public MallAfterSaleVo inspect(Long operatorId, MallAfterSaleInspectBo bo) {
        if (!MallEnum.InspectResult.isKnown(bo.getInspectResult())) {
            // 白名单：未知结论不得默认成通过——那是在替质检员下结论
            throw new JbkException("质检结论不合法");
        }
        WsMallAfterSale afterSale = requireManageAfterSale(operatorId, bo.getAfterSaleNo());
        WsMallOrder order = requireLinkedOrder(afterSale, null);
        String now = DateUtils.time();
        String remark = StrUtil.trim(bo.getInspectRemark());
        boolean rejected = ObjectUtil.equal(bo.getInspectResult(),
                MallEnum.InspectResult.REJECTED.getValue());
        boolean resellable = ObjectUtil.equal(bo.getInspectResult(),
                MallEnum.InspectResult.PASS_RESELLABLE.getValue());
        boolean exchange = ObjectUtil.equal(afterSale.getAfterSaleType(),
                MallEnum.AfterSaleType.EXCHANGE.getValue());

        MallEnum.AfterSaleStatus target = rejected ? MallEnum.AfterSaleStatus.REJECTED
                : exchange ? MallEnum.AfterSaleStatus.EXCHANGING
                        : MallEnum.AfterSaleStatus.REFUNDING;
        int moved = afterSaleMapper.casInspect(afterSale.getId(),
                MallEnum.AfterSaleStatus.PENDING_INSPECT.getValue(), target.getValue(),
                afterSale.getVersion(), bo.getInspectResult(), remark,
                rejected ? remark : null, rejected ? now : null, operatorId, now);
        if (moved != 1) {
            throw new JbkException("售后状态已变化（当前不是待质检），请刷新后重试");
        }
        afterSale.setAfterSaleStatus(target.getValue())
                .setVersion(afterSale.getVersion() + 1)
                .setInspectResult(bo.getInspectResult());

        if (!rejected) {
            // 只有「通过可重新销售」才回库；不可重新销售照样退钱但货不进可售
            if (resellable) {
                restockAll(afterSale, operatorId, now, "质检通过可重新销售");
            }
            if (exchange) {
                exchangeTx.createReshipment(afterSale.getId(), operatorId);
            }
            else {
                createPendingRefund(afterSale, order, operatorId, now);
            }
        }
        writeTrace(afterSale, target, MallEnum.ActorType.WAREHOUSE, operatorId,
                Long.valueOf(bo.getInspectResult()), now,
                "质检：" + inspectName(bo.getInspectResult()) + "——" + remark);
        writeAudit(afterSale, target, OpsEnum.ActorPortal.MANAGE, operatorId,
                "质检结论 " + bo.getInspectResult() + "：" + remark);
        notifyUser(afterSale, now, rejected ? "商城售后未通过质检" : "商城售后质检完成",
                rejected ? "您寄回的商品未通过质检：" + remark
                        : exchange ? "质检通过，换货补发正在安排中。" : "质检通过，退款正在处理中。");
        return detailForManage(operatorId, afterSale.getAfterSaleNo());
    }

    // ==================== 查询 ====================

    @Override
    public List<MallAfterSaleVo> listForUser(Long userId) {
        List<MallAfterSaleVo> list = new ArrayList<>();
        for (WsMallAfterSale row : afterSaleMapper.selectByUser(userId)) {
            list.add(toVo(row, false));
        }
        return list;
    }

    @Override
    public MallAfterSaleVo detailForUser(Long userId, String afterSaleNo) {
        WsMallAfterSale afterSale = requireOwnAfterSaleAnyStatus(userId, afterSaleNo);
        return toVo(afterSale, true);
    }

    @Override
    public PageDataVo<MallAfterSaleVo> pageForManage(Long operatorId, MallAfterSaleQueryBo bo) {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<WsMallAfterSale> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(
                        bo.getCurrent() == null ? 1L : bo.getCurrent(),
                        bo.getSize() == null ? 20L : Math.min(bo.getSize(), 100L));
        var result = afterSaleMapper.selectPageForOperator(page, operatorId,
                StrUtil.emptyToNull(StrUtil.trim(bo.getAfterSaleNo())),
                StrUtil.emptyToNull(StrUtil.trim(bo.getOrderNo())),
                bo.getAfterSaleStatus(), bo.getAfterSaleType());
        List<MallAfterSaleVo> rows = new ArrayList<>();
        for (WsMallAfterSale row : result.getRecords()) {
            rows.add(toVo(row, false));
        }
        return new PageDataVo<>(rows, result.getTotal());
    }

    @Override
    public MallAfterSaleVo detailForManage(Long operatorId, String afterSaleNo) {
        WsMallAfterSale afterSale = requireManageAfterSale(operatorId, afterSaleNo);
        return toVo(afterSale, true);
    }

    // ==================== 校验 ====================

    /** 售后资格：按订单状态与履约进度分流，任一不满足即 fail-closed。 */
    /**
     * 审核时刻的状态复核（不含时间窗）。
     *
     * <p>刻意不重验售后窗口：窗口约束的是「什么时候可以提出申请」，用户在窗口内提交后，
     * 审核人第八天才点通过不该反悔。这里只重验那些**申请之后仍可能被改变**的东西——
     * 订单状态与履约进度。</p>
     */
    private void requireStillApplicable(WsMallOrder order, Integer type) {
        int status = order.getOrderStatus() == null ? 0 : order.getOrderStatus();
        if (ObjectUtil.equal(type, MallEnum.AfterSaleType.CANCEL_REFUND.getValue())) {
            if (status != MallEnum.OrderStatus.PAID.getValue()) {
                throw new JbkException("订单已不处于已支付待履约状态，无法按整单取消通过");
            }
            WsMallFulfillment task = fulfillMapper.selectByOrderNoIncludingDeleted(
                    order.getOrderNo());
            if (ObjectUtil.isNotNull(task) && ObjectUtil.equal(task.getDataStatus(), 0)
                    && task.getFulfillStatus() != null
                    && task.getFulfillStatus() > MallEnum.FulfillStatus.PENDING_PICK.getValue()) {
                throw new JbkException("订单已开始拣货，整单取消不再成立，请改走签收后退货退款");
            }
            return;
        }
        if (status != MallEnum.OrderStatus.COMPLETED.getValue()) {
            throw new JbkException("订单已不处于已完成状态，无法通过该类型售后");
        }
    }

    private void requireApplicable(WsMallOrder order, Integer type, String now) {
        int status = order.getOrderStatus() == null ? 0 : order.getOrderStatus();
        WsMallFulfillment task = fulfillMapper.selectByOrderNoIncludingDeleted(order.getOrderNo());
        if (ObjectUtil.equal(type, MallEnum.AfterSaleType.CANCEL_REFUND.getValue())) {
            if (status != MallEnum.OrderStatus.PAID.getValue()) {
                throw new JbkException("订单已开始履约或不处于已支付状态，无法整单取消");
            }
            if (ObjectUtil.isNotNull(task) && ObjectUtil.equal(task.getDataStatus(), 0)
                    && task.getFulfillStatus() != null
                    && task.getFulfillStatus() > MallEnum.FulfillStatus.PENDING_PICK.getValue()) {
                // 已开始拣货就不再是「取消」而是退货，必须走签收后的退货流程
                throw new JbkException("订单已开始拣货，请在签收后申请退货退款");
            }
            return;
        }
        if (status != MallEnum.OrderStatus.COMPLETED.getValue()) {
            throw new JbkException("订单尚未签收完成，暂不支持该类型售后");
        }
        if (ObjectUtil.isNull(task) || !ObjectUtil.equal(task.getDataStatus(), 0)) {
            throw new JbkException("履约证据缺失，无法判定售后窗口");
        }
        String windowMiss = MallAfterSaleGate.windowMismatch(task.getSignTime(), now,
                afterSaleWindowDays);
        if (windowMiss != null) {
            throw new JbkException(windowMiss);
        }
    }

    /** 申请数量归一：整单取消展开全部明细；其余按入参逐行核共键与累计上限。 */
    private Map<Long, Integer> requestedQuantities(MallAfterSaleApplyBo bo,
                                                   List<WsMallOrderItem> orderItems) {
        Map<Long, Integer> requested = new LinkedHashMap<>();
        if (ObjectUtil.equal(bo.getAfterSaleType(),
                MallEnum.AfterSaleType.CANCEL_REFUND.getValue())) {
            for (WsMallOrderItem item : orderItems) {
                requested.put(item.getId(), item.getQuantity());
            }
            return requested;
        }
        if (bo.getLines() == null || bo.getLines().isEmpty()) {
            throw new JbkException("请选择要申请售后的商品");
        }
        for (MallAfterSaleApplyBo.Line line : bo.getLines()) {
            if (requested.putIfAbsent(line.getOrderItemId(), line.getQuantity()) != null) {
                // 同一明细拆成两行会绕过下面的累计上限判定
                throw new JbkException("同一商品不得重复提交");
            }
        }
        return requested;
    }

    /** 写售后明细并返回应退总额；逐行核共键与「处理中+已成功」累计上限。 */
    private long insertItems(WsMallAfterSale afterSale, List<WsMallOrderItem> orderItems,
                             Map<Long, Integer> requested, String now) {
        Map<Long, WsMallOrderItem> byId = new LinkedHashMap<>();
        for (WsMallOrderItem item : orderItems) {
            byId.put(item.getId(), item);
        }
        long total = 0L;
        for (Map.Entry<Long, Integer> entry : requested.entrySet()) {
            WsMallOrderItem item = byId.get(entry.getKey());
            if (ObjectUtil.isNull(item)) {
                throw new JbkException("售后明细不属于该订单，请人工核查");
            }
            String miss = MallAfterSaleGate.itemsMismatch(orderItems, item.getId(),
                    item.getSkuId(), item.getUnitPriceFen());
            if (miss != null) {
                throw new JbkException(miss);
            }
            int quantity = entry.getValue() == null ? 0 : entry.getValue();
            if (quantity <= 0) {
                throw new JbkException("申请数量必须为正整数");
            }
            int occupied = afterSaleMapper.sumOccupiedQuantity(item.getId());
            if (MallMoney.addCount(occupied, quantity) > item.getQuantity()) {
                throw new JbkException("申请数量超过该商品可售后数量");
            }
            long amount = MallMoney.multiply(item.getUnitPriceFen(), quantity);
            WsMallAfterSaleItem row = new WsMallAfterSaleItem()
                    .setAfterSaleId(afterSale.getId())
                    .setOrderItemId(item.getId())
                    .setSkuId(item.getSkuId())
                    .setProductName(item.getProductName())
                    .setSkuName(item.getSkuName())
                    .setUnitPriceFen(item.getUnitPriceFen())
                    .setQuantity(quantity)
                    .setItemAmountFen(amount);
            row.setCreateTime(now);
            afterSaleItemMapper.insert(row);
            total = MallMoney.add(total, amount);
        }
        if (total <= 0) {
            throw new JbkException("应退金额为零，申请已拒绝");
        }
        return total;
    }

    /** 同 requestId 重放的正文一致性：改订单、改类型一律拒绝。 */
    private static void requireSameApply(WsMallAfterSale existed, MallAfterSaleApplyBo bo) {
        boolean same = ObjectUtil.equal(existed.getOrderNo(), bo.getOrderNo())
                && ObjectUtil.equal(existed.getAfterSaleType(), bo.getAfterSaleType());
        if (!same) {
            throw new JbkException("同一请求号携带了不一致的售后内容，已拒绝");
        }
    }

    private WsMallAfterSale requireOwnAfterSale(Long userId, String afterSaleNo) {
        WsMallAfterSale afterSale = requireOwnAfterSaleAnyStatus(userId, afterSaleNo);
        if (!ObjectUtil.equal(afterSale.getAfterSaleStatus(),
                MallEnum.AfterSaleStatus.PENDING_AUDIT.getValue())) {
            throw new JbkException("仅待审核的申请可以撤销");
        }
        return afterSale;
    }

    private WsMallAfterSale requireOwnAfterSaleAnyStatus(Long userId, String afterSaleNo) {
        WsMallAfterSale afterSale = afterSaleMapper.selectByNoIncludingDeleted(afterSaleNo);
        WsMallOrder order = ObjectUtil.isNull(afterSale) ? null
                : orderMapper.selectByOrderNoIncludingDeleted(afterSale.getOrderNo());
        String miss = MallAfterSaleGate.linkMismatch(afterSale, order, userId);
        if (miss != null) {
            throw new JbkException(miss);
        }
        return afterSale;
    }

    /** PC 侧：操作员必须归属该售后单的原履约仓，跨仓一律按不存在处理。 */
    private WsMallAfterSale requireManageAfterSale(Long operatorId, String afterSaleNo) {
        WsMallAfterSale afterSale = afterSaleMapper.selectByNoIncludingDeleted(afterSaleNo);
        WsMallOrder order = ObjectUtil.isNull(afterSale) ? null
                : orderMapper.selectByOrderNoIncludingDeleted(afterSale.getOrderNo());
        String miss = MallAfterSaleGate.linkMismatch(afterSale, order, null);
        if (miss != null) {
            throw new JbkException(miss);
        }
        if (ObjectUtil.isNull(operatorId)
                || whOperatorMapper.countActiveScope(afterSale.getWarehouseId(), operatorId) <= 0) {
            throw new JbkException("售后单不存在");
        }
        return afterSale;
    }

    private WsMallOrder requireLinkedOrder(WsMallAfterSale afterSale, Long expectUserId) {
        WsMallOrder order = orderMapper.selectByOrderNoIncludingDeleted(afterSale.getOrderNo());
        String miss = MallAfterSaleGate.linkMismatch(afterSale, order, expectUserId);
        if (miss != null) {
            throw new JbkException(miss);
        }
        return order;
    }

    // ==================== 推进与留痕 ====================

    private void advance(WsMallAfterSale afterSale, MallEnum.AfterSaleStatus from,
                         MallEnum.AfterSaleStatus to, Long operator, String now) {
        int moved = afterSaleMapper.casStatus(afterSale.getId(), from.getValue(), to.getValue(),
                afterSale.getVersion(), operator, now);
        if (moved != 1) {
            throw new JbkException("售后状态已变化（当前不是" + from.getDesc() + "），请刷新后重试");
        }
        afterSale.setAfterSaleStatus(to.getValue()).setVersion(afterSale.getVersion() + 1);
    }

    /** 回库：按 SKU 升序逐行，锁序固定，避免两笔售后交叉加锁死锁。 */
    private void restockAll(WsMallAfterSale afterSale, Long operator, String now, String reason) {
        for (WsMallAfterSaleItem item : afterSaleItemMapper
                .selectByAfterSaleOrderBySku(afterSale.getId())) {
            afterSaleStock.restock(afterSale.getWarehouseId(), item.getSkuId(),
                    item.getQuantity(), afterSale.getAfterSaleNo(), operator, now);
        }
        log.debug("售后回库完成 afterSaleNo={} reason={}", afterSale.getAfterSaleNo(), reason);
    }

    /** 建待退款单：金额与币种全部来自售后单与原支付单，调用方不得传入。 */
    private void createPendingRefund(WsMallAfterSale afterSale, WsMallOrder order,
                                     Long operator, String now) {
        WsMallPayment payment = paymentMapper.selectByOrderNoIncludingDeleted(order.getOrderNo());
        String payMiss = MallAfterSaleGate.paymentMismatch(order, payment);
        if (payMiss != null) {
            throw new JbkException(payMiss);
        }
        WsMallRefund existed = refundMapper.selectByAfterSaleIncludingDeleted(afterSale.getId());
        if (ObjectUtil.isNotNull(existed)) {
            return;
        }
        WsMallRefund refund = new WsMallRefund()
                .setRefundNo(MallRefundNo.derive(afterSale.getAfterSaleNo()))
                .setAfterSaleId(afterSale.getId())
                .setAfterSaleNo(afterSale.getAfterSaleNo())
                .setOrderId(order.getId())
                .setOrderNo(order.getOrderNo())
                .setPaymentId(payment.getId())
                .setUserId(order.getUserId())
                .setRefundAmountFen(afterSale.getRefundAmountFen())
                .setCurrency(payment.getCurrency())
                .setRefundStatus(MallEnum.RefundStatus.PENDING.getValue())
                .setRefundSource(payment.getPaySource());
        refund.setCreateTime(now);
        try {
            refundMapper.insert(refund);
        }
        catch (DuplicateKeyException race) {
            // 一售后一退款单由库层兜住；并发建单的输方直接放行，赢家那张就是唯一的
            log.debug("退款单已存在，跳过 afterSaleNo={}", afterSale.getAfterSaleNo());
        }
    }

    /** 轨迹统一走共享写入口：三处推进各写一份的结果就是换货链整个缺了完成节点。 */
    private void writeTrace(WsMallAfterSale afterSale, MallEnum.AfterSaleStatus node,
                            MallEnum.ActorType actorType, Long actorId, Long subjectId,
                            String now, String text) {
        traceWriter.write(afterSale, node, actorType, actorId, subjectId, now, text);
    }

    private void writeAudit(WsMallAfterSale afterSale, MallEnum.AfterSaleStatus node,
                            OpsEnum.ActorPortal portal, Long actorId, String detail) {
        domainEventService.recordReliableOnceAs(portal,
                actorId == null ? SYSTEM_OPERATOR : actorId, OpsEnum.EventType.MALL,
                "MALLAFTERSALE:" + afterSale.getAfterSaleNo(),
                AUDIT_KEY_PREFIX + afterSale.getAfterSaleNo() + ":" + node.getValue(),
                node.getDesc(), detail);
    }

    private void notifyUser(WsMallAfterSale afterSale, String now, String title, String content) {
        messageService.sendInApp(afterSale.getUserId(), MessageEnum.MsgDomain.MALL,
                title, content, "mallAfterSale", afterSale.getAfterSaleNo(), now);
    }

    // ==================== 组装 ====================

    private MallAfterSaleVo toVo(WsMallAfterSale afterSale, boolean withTimeline) {
        MallAfterSaleVo vo = new MallAfterSaleVo()
                .setAfterSaleNo(afterSale.getAfterSaleNo())
                .setOrderNo(afterSale.getOrderNo())
                .setAfterSaleType(afterSale.getAfterSaleType())
                .setAfterSaleTypeName(typeName(afterSale.getAfterSaleType()))
                .setAfterSaleStatus(afterSale.getAfterSaleStatus())
                .setAfterSaleStatusName(statusName(afterSale.getAfterSaleStatus()))
                .setRefundAmountFen(afterSale.getRefundAmountFen())
                .setApplyReason(afterSale.getApplyReason())
                .setApplyTime(afterSale.getApplyTime())
                .setInspectResult(afterSale.getInspectResult())
                .setInspectResultName(inspectName(afterSale.getInspectResult()))
                .setInspectRemark(afterSale.getInspectRemark())
                .setRejectReason(afterSale.getRejectReason())
                .setFinishTime(afterSale.getFinishTime());

        WsMallRefund refund = refundMapper.selectByAfterSaleIncludingDeleted(afterSale.getId());
        if (ObjectUtil.isNotNull(refund) && ObjectUtil.equal(refund.getDataStatus(), 0)) {
            vo.setRefundStatus(refund.getRefundStatus())
                    .setRefundSuccessTime(refund.getRefundSuccessTime());
        }
        WsMallOrder reshipment = orderMapper.selectBySourceAfterSale(afterSale.getId());
        if (ObjectUtil.isNotNull(reshipment)) {
            vo.setExchangeOrderNo(reshipment.getOrderNo());
        }

        List<MallAfterSaleVo.Line> lines = new ArrayList<>();
        for (WsMallAfterSaleItem item : afterSaleItemMapper
                .selectByAfterSaleOrderBySku(afterSale.getId())) {
            lines.add(new MallAfterSaleVo.Line()
                    .setOrderItemId(item.getOrderItemId())
                    .setSkuId(item.getSkuId())
                    .setProductName(item.getProductName())
                    .setSkuName(item.getSkuName())
                    .setUnitPriceFen(item.getUnitPriceFen())
                    .setQuantity(item.getQuantity())
                    .setItemAmountFen(item.getItemAmountFen()));
        }
        vo.setLines(lines);

        if (withTimeline) {
            List<MallFulfillTraceVo> timeline = new ArrayList<>();
            for (WsMallAfterSaleTrace trace : traceMapper
                    .selectTimeline(afterSale.getAfterSaleNo())) {
                timeline.add(new MallFulfillTraceVo()
                        .setTraceNode(trace.getTraceNode())
                        .setTraceNodeName(statusName(trace.getTraceNode()))
                        .setActorType(trace.getActorType())
                        .setActorTypeName(actorName(trace.getActorType()))
                        .setTraceTime(trace.getTraceTime())
                        .setTraceText(trace.getTraceText()));
            }
            vo.setTimeline(timeline);
        }
        return vo;
    }

    private static String statusName(Integer value) {
        for (MallEnum.AfterSaleStatus s : MallEnum.AfterSaleStatus.values()) {
            if (ObjectUtil.equal(s.getValue(), value)) {
                return s.getDesc();
            }
        }
        return null;
    }

    private static String typeName(Integer value) {
        for (MallEnum.AfterSaleType t : MallEnum.AfterSaleType.values()) {
            if (ObjectUtil.equal(t.getValue(), value)) {
                return t.getDesc();
            }
        }
        return null;
    }

    private static String inspectName(Integer value) {
        for (MallEnum.InspectResult r : MallEnum.InspectResult.values()) {
            if (ObjectUtil.equal(r.getValue(), value)) {
                return r.getDesc();
            }
        }
        return null;
    }

    private static String actorName(Integer value) {
        for (MallEnum.ActorType a : MallEnum.ActorType.values()) {
            if (ObjectUtil.equal(a.getValue(), value)) {
                return a.getDesc();
            }
        }
        return null;
    }
}
