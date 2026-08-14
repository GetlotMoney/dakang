package com.jbk.serve.service.aftersale.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.aftersale.WsAfterSaleActionMapper;
import com.jbk.serve.mapper.device.WsCommandMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.service.aftersale.AfterSaleNo;
import com.jbk.serve.service.aftersale.IWaterAbnormalReconcileTxService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.serve.service.trade.WaterBillingMath;
import com.jbk.tool.consts.aftersale.AfterSaleEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.data.aftersale.po.WsAfterSaleAction;
import com.jbk.tool.data.aftersale.vo.AdminWaterAbnormalPreviewVo;
import com.jbk.tool.data.device.po.WsCommand;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsWalletFlow;
import com.jbk.tool.exception.JbkException;
import com.jbk.serve.service.settlement.ISplitService;
import com.jbk.tool.consts.settlement.SettlementEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * 取水异常核账事务实现（E2E-04 包A，售后来源 3取水异常核账）。
 *
 * <p><b>依赖清单即安全边界</b>：本类<b>刻意不注入 {@code TradeCardMapper}，也不注入售后返还内核事务</b>
 * ——本类没有加钱能力。「不该动」如果只写在注释和 if 分支里，任何一次后续维护都可能顺手加一行返还调用，
 * 且改动看起来完全合理（"顺便把没退的那笔补上"）。把加钱能力从依赖清单里删掉，这类改动就必须先新增一个
 * 字段和构造参数——那是评审一眼能看见的动作，而不是埋在三百行分支里的一行。评审可机械核验：
 * 本类的字段声明区与构造参数里没有任何具备写卡能力的依赖。</p>
 *
 * <p>状态 6 有两条语义相反的来源：<b>来源A（已退差待复核）</b>＝
 * {@code TradeOrderTxServiceImpl.settleWaterOrder(success=false)}，在同一事务内回填 {@code ACTUAL_ML}、落 6，
 * 并按「预扣 − 实际应扣」补偿入账 + 写一条 {@code FLOW_TYPE=4} 补偿流水；<b>来源B（未退差）</b>＝
 * {@code WaterCommandFailureTxServiceImpl}（下发失败 / 超时）、{@code WsCommandAckTxServiceImpl}（设备拒绝）与
 * {@code MiniOrderServiceImpl.markAbnormalWhenDispatchLeftNoTrace}（下发异常且未留任何指令痕迹），
 * 这些路径<b>一分钱没退</b>，且都不写 {@code ACTUAL_ML}。</p>
 *
 * <p>判别必须同时看两个信号：ACTUAL_ML 非空 ⨯ 存在 FLOW_TYPE=4 补偿流水——只看其一会误判
 * （来源A 退差为 0 时不写流水；流水可能被改写）。两真判 A、两假判 B，
 * <b>一真一假一律 UNKNOWN 并 fail-closed 转人工</b>。{@code ACTUAL_ML} 之所以能当判别信号，
 * 是因为全仓只有 {@code settleWaterOrder} 写它，而三条落 6 的路径互斥（各自的 CAS 前态都是 2/3，
 * 先到者把后到者的影响行打成 0），一张订单不可能同时经过结算与指令异常两条路。
 * 来源B 一律显式拒绝：把它当"已退过"确认终态即无偿吞掉用户的钱。被逻辑删除的退差流水只会让判定阻断转人工，
 * 不构成放行后门。</p>
 *
 * @author dakang
 * @since 2026-07-29
 */
@Service
@RequiredArgsConstructor
public class WaterAbnormalReconcileTxServiceImpl implements IWaterAbnormalReconcileTxService {

    /** 已退差待复核：可确认。 */
    private static final String VERDICT_REFUNDED = "A";
    /** 未退差：必须拒绝，补退差属后续包。 */
    private static final String VERDICT_UNSETTLED = "B";
    /** 证据不足或账本断裂：fail-closed 转人工。 */
    private static final String VERDICT_UNKNOWN = "UNKNOWN";

    /** 领域事件幂等键前缀；与 EVENT_KEY(varchar 64) 一起使用，orderNo 最长 32，不会越界。 */
    private static final String RECONCILE_KEY_PREFIX = "WATER_RECONCILE:";
    private static final String REJECT_KEY_PREFIX = "WATER_RECONCILE_REJECT:";
    private static final int REMARK_MAX = 200;
    private static final int TIME_LEN = 14;

    private final WsOrderMapper wsOrderMapper;
    private final WsWalletFlowMapper walletFlowMapper;
    private final WsCommandMapper commandMapper;
    private final WsAfterSaleActionMapper afterSaleActionMapper;
    private final IWsDomainEventService domainEventService;
    // 事后新增的协作方走字段注入：不动 @RequiredArgsConstructor 构造器签名（既有测试手工拼装）
    @Autowired
    private ISplitService splitService;

    /**
     * 只读预览，不加事务、不写拒绝证据：确认路径会在自己的事务内用同一份 {@link #analyze} 重判。
     */
    @Override
    public AdminWaterAbnormalPreviewVo preview(Long orderId) {
        WsOrder order = orderId == null || orderId <= 0 ? null : wsOrderMapper.selectById(orderId);
        return toVo(orderId, order, analyze(order));
    }

    /**
     * 运营确认核账。READ_COMMITTED：RR 下读视图在首条 SELECT 固定，会拿旧账本核出终态
     * （权威先例与不用锁定读的理由见 {@code RechargeIssueTxImpl.issue}）。
     * 核验与 CAS 间的 TOCTOU 窗口无害：流水只插不改，终态由 ORDER_STATUS=6 的 CAS 独占。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void confirm(Long orderId, String handleRemark, Long opUserId, String now) {
        // 传播保持 REQUIRED（终态、台账与领域事件须与调用方同生共死），代价是被外层事务包住时
        // 声明的 READ_COMMITTED 会被静默丢弃且零报错——此断言让那一天立刻炸掉
        // （与 AfterSaleActionTxServiceImpl.executeInTx 的同款断言成对）
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if (isolation == null || isolation != TransactionDefinition.ISOLATION_READ_COMMITTED) {
            throw new JbkException("取水核账事务隔离级别不是 READ_COMMITTED（实际 " + isolation
                    + "），已退额度会读到旧快照并被低估，拒绝执行");
        }
        if (orderId == null || orderId <= 0) {
            throw new JbkException("订单ID非法");
        }
        if (opUserId == null || opUserId <= 0) {
            throw new JbkException("核账操作人缺失，拒绝确认");
        }
        if (now == null || now.length() != TIME_LEN) {
            // 不做取当前时间的兜底：会让订单、台账与审计出现两套时钟
            throw new JbkException("业务时间格式非法，拒绝确认");
        }
        String remark = StrUtil.maxLength(StrUtil.trim(handleRemark), REMARK_MAX);
        if (StrUtil.isBlank(remark)) {
            throw new JbkException("核账说明不能为空");
        }

        WsOrder order = wsOrderMapper.selectById(orderId);
        Reconcile verdict = analyze(order);
        if (verdict.blockReason() != null) {
            throw rejected(orderId, order, verdict.blockReason(), now);
        }

        // 订单终态 CAS：WHERE 带 ID + ORDER_STATUS=6，影响行必须 == 1（铁律①）。
        // 刻意不改写 FINISH_TIME/CANCEL_REASON——它们是核账依据的证据，覆盖即把证据改成结论；
        // 核账时刻与说明落在台账行与领域事件里
        int moved = wsOrderMapper.update(null, Wrappers.lambdaUpdate(WsOrder.class)
                .set(WsOrder::getOrderStatus, verdict.targetStatus())
                .set(WsOrder::getUpdateBy, opUserId)
                .set(WsOrder::getUpdateTime, now)
                .eq(WsOrder::getId, orderId)
                .eq(WsOrder::getOrderStatus, TradeEnum.OrderStatus.ABNORMAL.getValue()));
        if (moved != 1) {
            throw rejected(orderId, order, "订单已被并发推进出异常待补偿状态，核账确认未生效", now);
        }

        // E2E-08：核账确认 6→4 与结算路径是同一个「完成」事实，必须同产分账
        // （基数=实扣=订单金额-已退差）；6→7 零出水退款不分账，撞键幂等由 enqueueForOrder 保证
        if (ObjectUtil.equal(verdict.targetStatus(), TradeEnum.OrderStatus.FINISHED.getValue())
                && ObjectUtil.equal(order.getPayWay(), TradeEnum.PayWay.CARD_BALANCE.getValue())) {
            splitService.enqueueForOrder(order.getId(), order.getOrderNo(),
                    order.getOrderAmount() - verdict.refundedFen(),
                    SettlementEnum.ProductLine.WATER,
                    splitService.resolveWaterOwner(order.getDeviceId(), order.getStationId()),
                    null, order.getCreateTime());
        }

        JSONObject snapshot = buildSnapshot(order, verdict, remark, opUserId);
        insertLedgerAction(order, snapshot, opUserId, now);

        // 正向状态审计与业务同事务（铁律④）；撞幂等键时按语义比对，不一致即整体回滚
        domainEventService.recordReliableOnce(OpsEnum.EventType.ORDER_STATUS, order.getOrderNo(),
                RECONCILE_KEY_PREFIX + order.getOrderNo(),
                TradeEnum.OrderStatus.ABNORMAL.getDesc(),
                "取水异常核账确认：" + snapshot);
    }

    // ------------------------------------------------------------------
    // 判定：preview 与 confirm 共用同一份，页面看到的阻断原因就是确认时的拒绝原因
    // ------------------------------------------------------------------

    /**
     * 核账判定结论。{@code blockReason != null} 时 {@code targetStatus} 恒为 null——
     * 否则只读 targetStatus 的调用方会绕过阻断。
     */
    private record Reconcile(String verdict, Long planMl, Long actualMl,
                             long refundedFen, long refundedMl,
                             Integer targetStatus, String blockReason) {
    }

    /** 本单在钱包流水上的账本切片；{@code otherCount} 是本核账口径之外的流水条数。 */
    private record Ledger(long consumeFen, long consumeMl, long refundedFen, long refundedMl, int otherCount) {
    }

    private Reconcile analyze(WsOrder order) {
        String structural = structuralBlock(order);
        if (structural != null) {
            return new Reconcile(VERDICT_UNKNOWN, order == null ? null : order.getPlanMl(),
                    order == null ? null : order.getActualMl(), 0L, 0L, null, structural);
        }
        long planMl;
        try {
            // 计划水量的值域校验只有 WaterBillingMath 一份实现，不在此处重写阈值
            planMl = WaterBillingMath.requirePlanMl(order.getPlanMl());
        } catch (JbkException e) {
            return new Reconcile(VERDICT_UNKNOWN, order.getPlanMl(), order.getActualMl(),
                    0L, 0L, null, e.getMsg());
        }

        Ledger ledger = readLedger(order.getId());
        Long actualMl = order.getActualMl();
        boolean settled = actualMl != null;
        boolean compensated = ledger.refundedFen() > 0L || ledger.refundedMl() > 0L;
        String verdict;
        if (settled && compensated) {
            verdict = VERDICT_REFUNDED;
        } else if (!settled && !compensated) {
            verdict = VERDICT_UNSETTLED;
        } else {
            verdict = VERDICT_UNKNOWN;
        }

        Integer target = targetStatus(planMl, actualMl);
        String block = blockReason(order, planMl, actualMl, ledger, verdict, target);
        return new Reconcile(verdict, planMl, actualMl, ledger.refundedFen(), ledger.refundedMl(),
                block == null ? target : null, block);
    }

    /** 结构性前提；任一不成立则连账本都不必读。 */
    private String structuralBlock(WsOrder order) {
        if (order == null) {
            return "订单不存在或已删除，无法核账";
        }
        if (StrUtil.isBlank(order.getOrderNo())) {
            return "订单号缺失，核账无法留痕，需人工对账";
        }
        if (!ObjectUtil.equal(order.getOrderType(), TradeEnum.OrderType.WATER.getValue())) {
            return "非扫码取水订单，不适用取水异常核账";
        }
        if (!ObjectUtil.equal(order.getOrderStatus(), TradeEnum.OrderStatus.ABNORMAL.getValue())) {
            return "订单不在 6异常待补偿 状态（当前 " + order.getOrderStatus() + "），不可核账";
        }
        if (order.getUserId() == null || order.getCardId() == null) {
            return "订单未关联归属用户或水卡，核账台账无法归属，需人工对账";
        }
        boolean byBalance = ObjectUtil.equal(order.getPayWay(), TradeEnum.PayWay.CARD_BALANCE.getValue());
        boolean byMl = ObjectUtil.equal(order.getPayWay(), TradeEnum.PayWay.CARD_ML.getValue());
        if (!byBalance && !byMl) {
            return "订单支付方式不是水卡余额或水卡水量，没有卡内账本可核";
        }
        return null;
    }

    /**
     * 终态派生：零出水→7已退款，部分出水→4已完成；实际量 ≥ 计划量返回 null——
     * 水已足量出完却落 6，原因超出本核账口径，只能交人工。
     */
    private Integer targetStatus(long planMl, Long actualMl) {
        if (actualMl == null || actualMl < 0L || actualMl >= planMl) {
            return null;
        }
        return actualMl == 0L
                ? TradeEnum.OrderStatus.REFUNDED.getValue()
                : TradeEnum.OrderStatus.FINISHED.getValue();
    }

    /** 阻断原因链；返回 null 即可确认。顺序按「越基础越先报」排，保证运营看到的是根因。 */
    private String blockReason(WsOrder order, long planMl, Long actualMl, Ledger ledger,
                               String verdict, Integer target) {
        if (ledger.otherCount() > 0) {
            return "订单账本存在取水扣减/补偿之外的流水 " + ledger.otherCount()
                    + " 条，账本超出核账口径，需人工对账";
        }
        if (VERDICT_UNSETTLED.equals(verdict)) {
            return "该单尚未退差：订单落 6异常待补偿 由出水指令链异常直接置位，既无实际水量也无退差流水，"
                    + "不能直接确认终态。补退差不在本包范围，请转人工处理";
        }
        if (!VERDICT_REFUNDED.equals(verdict)) {
            return actualMl == null
                    ? "订单无实际水量却已存在补偿流水，结算记录与账本自相矛盾，需人工对账"
                    : "订单已结算但查不到补偿流水（零差额或流水缺失），无法证明已退差，需人工对账";
        }
        String coKeyBroken = commandCoKeyBroken(order);
        if (coKeyBroken != null) {
            return coKeyBroken;
        }
        if (target == null) {
            return actualMl != null && actualMl >= planMl
                    ? "实际出水量(" + actualMl + ")已达计划量(" + planMl + ")，无差额核账口径，需人工对账"
                    : "实际水量非法(" + actualMl + ")，需人工对账";
        }
        return ledgerMismatch(order, planMl, actualMl, ledger);
    }

    /**
     * 账本与「计划-实际」的一致性核对。payWay=3 与零出水是不依赖单价的精确等式；
     * payWay=2 部分出水只核方向/维度/上界——单价快照的解析只有结算路径一份实现（铁律⑤），
     * 此处再写一份必然漂移成无人能裁决的对账争议，精确性由退差路径自身断言保证。
     */
    private String ledgerMismatch(WsOrder order, long planMl, long actualMl, Ledger ledger) {
        long settledActual = Math.min(actualMl, planMl);
        if (ObjectUtil.equal(order.getPayWay(), TradeEnum.PayWay.CARD_ML.getValue())) {
            if (ledger.consumeFen() != 0L || ledger.consumeMl() != -planMl) {
                return "原扣款流水与订单计划水量不符（流水水量合计 " + ledger.consumeMl()
                        + "，金额合计 " + ledger.consumeFen() + "，计划 " + planMl + "），账实不符，需人工对账";
            }
            if (ledger.refundedFen() != 0L) {
                return "水量支付订单出现金额退差流水（" + ledger.refundedFen() + " 分），维度错位，需人工对账";
            }
            long expected = planMl - settledActual;
            if (ledger.refundedMl() != expected) {
                return "退差水量(" + ledger.refundedMl() + ")与计划-实际差额(" + expected + ")不符，需人工对账";
            }
            return null;
        }
        long orderAmount = order.getOrderAmount() == null ? -1L : order.getOrderAmount();
        if (orderAmount < 0L) {
            return "订单金额缺失，无法核对退差金额，需人工对账";
        }
        if (ledger.consumeMl() != 0L || ledger.consumeFen() != -orderAmount) {
            return "原扣款流水与订单金额不符（流水金额合计 " + ledger.consumeFen()
                    + "，水量合计 " + ledger.consumeMl() + "，订单 " + orderAmount + " 分），账实不符，需人工对账";
        }
        if (ledger.refundedMl() != 0L) {
            return "余额支付订单出现水量退差流水（" + ledger.refundedMl() + " 毫升），维度错位，需人工对账";
        }
        if (ledger.refundedFen() > orderAmount) {
            return "退差金额(" + ledger.refundedFen() + " 分)超过订单预扣金额(" + orderAmount
                    + " 分)，账本断裂，需人工对账";
        }
        if (actualMl == 0L && ledger.refundedFen() != orderAmount) {
            return "零出水应全额退差，实际退差 " + ledger.refundedFen() + " 分、订单预扣 "
                    + orderAmount + " 分，需人工对账";
        }
        return null;
    }

    /** 订单-指令共键核验：出水事实必须能从订单追到同一条指令、同一台设备。 */
    private String commandCoKeyBroken(WsOrder order) {
        if (order.getCmdId() == null) {
            return "订单未绑定出水指令，出水事实无从追溯，需人工对账";
        }
        WsCommand command = commandMapper.selectById(order.getCmdId());
        if (command == null) {
            return "订单绑定的出水指令不存在或已删除，需人工对账";
        }
        if (!ObjectUtil.equal(command.getOrderId(), order.getId())
                || !ObjectUtil.equal(command.getDeviceId(), order.getDeviceId())) {
            return "订单与出水指令共键错位（指令归属订单 " + command.getOrderId()
                    + "、设备 " + command.getDeviceId() + "），需人工对账";
        }
        return null;
    }

    /** 一次取齐本单流水后在内存分类聚合：核账要同时看扣款与退差两侧，分两次查会取到不同视图。 */
    private Ledger readLedger(Long orderId) {
        List<WsWalletFlow> flows = walletFlowMapper.selectList(
                Wrappers.lambdaQuery(WsWalletFlow.class).eq(WsWalletFlow::getOrderId, orderId));
        long consumeFen = 0L;
        long consumeMl = 0L;
        long refundedFen = 0L;
        long refundedMl = 0L;
        int other = 0;
        try {
            for (WsWalletFlow flow : flows) {
                if (ObjectUtil.equal(flow.getFlowType(), TradeEnum.FlowType.CONSUME.getValue())) {
                    consumeFen = Math.addExact(consumeFen, zeroIfNull(flow.getAmountChange()));
                    consumeMl = Math.addExact(consumeMl, zeroIfNull(flow.getMlChange()));
                } else if (ObjectUtil.equal(flow.getFlowType(), TradeEnum.FlowType.COMPENSATE.getValue())) {
                    refundedFen = Math.addExact(refundedFen, zeroIfNull(flow.getAmountChange()));
                    refundedMl = Math.addExact(refundedMl, zeroIfNull(flow.getMlChange()));
                } else {
                    other++;
                }
            }
        } catch (ArithmeticException overflow) {
            // 回绕出来的"账对得上"比拒绝危险得多
            throw new JbkException("订单账本合计溢出，拒绝核账，需人工对账");
        }
        return new Ledger(consumeFen, consumeMl, refundedFen, refundedMl, other);
    }

    // ------------------------------------------------------------------
    // 写入：台账留痕与证据
    // ------------------------------------------------------------------

    /**
     * 台账留痕行：SOURCE_TYPE=3、ACTION_TYPE=1卡内退款、四元额度全 0、直接落 SUCCESS。
     * 只 insert 不调返还 API；额度全 0 表达零资金变化，且不影响按订单聚合的累计封顶。
     * 幂等由唯一键承担（铁律②），撞键必须炸——终态 CAS 已独占 6→终态，还撞键即有第三方写入。
     */
    private void insertLedgerAction(WsOrder order, JSONObject snapshot, Long opUserId, String now) {
        WsAfterSaleAction action = new WsAfterSaleAction();
        action.setAfterSaleNo(AfterSaleNo.derive(
                        AfterSaleEnum.SourceType.WATER_ABNORMAL.getValue(), order.getId()))
                .setSourceType(AfterSaleEnum.SourceType.WATER_ABNORMAL.getValue())
                .setSourceId(order.getId())
                .setOrderId(order.getId())
                .setUserId(order.getUserId())
                .setCardId(order.getCardId())
                .setActionType(AfterSaleEnum.ActionType.CARD_REFUND.getValue())
                .setRefundProductFen(0L)
                .setRefundServiceFen(0L)
                .setRefundProductMl(0L)
                .setRefundAmount(0L)
                .setCalcSnapshot(snapshot.toString())
                .setActionStatus(AfterSaleEnum.ActionStatus.SUCCESS.getValue())
                .setVersion(1)
                .setRetryCount(0)
                .setApproveBy(opUserId)
                .setApproveTime(now)
                .setFinishTime(now);
        action.setCreateBy(opUserId);
        action.setCreateTime(now);
        action.setUpdateBy(opUserId);
        action.setUpdateTime(now);
        if (afterSaleActionMapper.insert(action) != 1) {
            throw new JbkException("取水核账台账写入失败");
        }
    }

    /** 计算依据快照：出账即冻结，事后只读不重算。同时用作领域事件报文，两处口径不可能分叉。 */
    private JSONObject buildSnapshot(WsOrder order, Reconcile verdict, String remark, Long opUserId) {
        JSONObject snapshot = new JSONObject();
        snapshot.set("sourceVerdict", verdict.verdict());
        snapshot.set("payWay", order.getPayWay());
        snapshot.set("orderAmount", order.getOrderAmount());
        snapshot.set("planMl", verdict.planMl());
        snapshot.set("actualMl", verdict.actualMl());
        snapshot.set("refundedFen", verdict.refundedFen());
        snapshot.set("refundedMl", verdict.refundedMl());
        snapshot.set("cmdId", order.getCmdId());
        snapshot.set("fromStatus", TradeEnum.OrderStatus.ABNORMAL.getValue());
        snapshot.set("toStatus", verdict.targetStatus());
        snapshot.set("handleRemark", remark);
        snapshot.set("opUserId", opUserId);
        return snapshot;
    }

    /**
     * 拒绝证据独立提交（铁律④，REQUIRES_NEW）：证据随主事务回滚则「拒绝过」成无痕事件。
     * 手法同 {@code TradeOrderTxServiceImpl.scopeRejected}：固定业务键 + 报文带判定时刻。
     */
    private JbkException rejected(Long orderId, WsOrder order, String reason, String now) {
        String eventKey = order == null || StrUtil.isBlank(order.getOrderNo())
                ? "ORDER_ID:" + orderId
                : order.getOrderNo();
        JSONObject evidence = new JSONObject();
        evidence.set("orderId", orderId);
        evidence.set("reason", reason);
        evidence.set("decidedAt", now);
        domainEventService.recordReliableOnceIndependent(OpsEnum.EventType.ORDER_STATUS, eventKey,
                REJECT_KEY_PREFIX + eventKey, null, "取水异常核账确认已拒绝：" + evidence);
        return new JbkException(reason);
    }

    // ------------------------------------------------------------------
    // 展示
    // ------------------------------------------------------------------

    private AdminWaterAbnormalPreviewVo toVo(Long orderId, WsOrder order, Reconcile verdict) {
        return new AdminWaterAbnormalPreviewVo()
                .setOrderId(order == null ? orderId : order.getId())
                .setOrderNo(order == null ? null : order.getOrderNo())
                .setOrderStatus(order == null ? null : order.getOrderStatus())
                .setPayWay(order == null ? null : order.getPayWay())
                .setOrderAmount(order == null ? null : order.getOrderAmount())
                .setPlanMl(verdict.planMl())
                .setActualMl(verdict.actualMl())
                .setRefundedFen(verdict.refundedFen())
                .setRefundedMl(verdict.refundedMl())
                .setSourceVerdict(verdict.verdict())
                .setSourceVerdictDesc(describe(verdict.verdict()))
                .setSuggestTargetStatus(verdict.targetStatus())
                .setConfirmable(verdict.blockReason() == null)
                .setBlockReason(verdict.blockReason());
    }

    private String describe(String verdict) {
        return switch (verdict) {
            case VERDICT_REFUNDED -> "已退差待复核：结算路径已按计划-实际差额退回卡内，落 6 仅等待运营复核";
            case VERDICT_UNSETTLED -> "未退差：出水指令链异常直接置位，尚未产生任何退差，需补退后才能收口";
            default -> "证据不足或账本断裂：结算记录与流水互相矛盾，需人工对账";
        };
    }

    private long zeroIfNull(Long value) {
        return value == null ? 0L : value;
    }
}
