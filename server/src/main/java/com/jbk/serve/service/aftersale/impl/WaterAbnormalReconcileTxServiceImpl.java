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
 * <h3>依赖清单即安全边界：本类构造器里没有任何加钱能力</h3>
 * <p>本类<b>刻意不注入 {@code TradeCardMapper}，也不注入售后返还内核事务</b>。
 * 核账的全部业务价值是「核对既有账本后推进订单终态」，一分钱一毫升都不该动；
 * 而「不该动」如果只写在注释和 if 分支里，任何一次后续维护都可能顺手加一行返还调用，
 * 且改动看起来完全合理（"顺便把没退的那笔补上"）。把加钱能力从依赖清单里删掉，
 * 这类改动就必须先新增一个字段和构造参数——那是评审一眼能看见的动作，而不是埋在
 * 三百行分支里的一行。评审可机械核验：本类的字段声明区与构造参数里没有任何具备写卡能力的依赖。</p>
 *
 * <h3>状态 6异常待补偿 有两条语义完全相反的来源，必须先判别再处理</h3>
 * <ul>
 *   <li><b>来源A（已退差待复核）</b>：{@code TradeOrderTxServiceImpl.settleWaterOrder(success=false)}。
 *       它在同一事务内回填 {@code ACTUAL_ML}、落 6，并按「预扣 − 实际应扣」补偿入账 +
 *       写一条 {@code FLOW_TYPE=4} 补偿流水。钱已经退过，落 6 只是等运营复核。</li>
     *   <li><b>来源B（未退差）</b>：{@code WaterCommandFailureTxServiceImpl}（下发失败 / 超时）、
     *       {@code WsCommandAckTxServiceImpl}（设备拒绝）与
     *       {@code MiniOrderServiceImpl.markAbnormalWhenDispatchLeftNoTrace}
     *       （下发异常且未留任何指令痕迹）。这些路径会推进订单异常态并落相应状态证据，
     *       但<b>一分钱没退</b>，且都不写 {@code ACTUAL_ML}。</li>
 * </ul>
 *
 * <p><b>判别依据：{@code ACTUAL_ML} 是否非空 ⨯ 是否存在 FLOW_TYPE=4 补偿流水，两个信号都要。</b>
 * 只看补偿流水会误判——来源A 在「实际出水已达计划量」时退差为 0、不写流水，
 * 看起来与来源B 一模一样；只看 {@code ACTUAL_ML} 又无法发现流水被改写。因此：
 * 两个信号同时成立判 A，同时不成立判 B，<b>一真一假一律 UNKNOWN 并 fail-closed 转人工</b>。
 * {@code ACTUAL_ML} 之所以能当判别信号，是因为全仓只有 {@code settleWaterOrder} 写它，
 * 而三条落 6 的路径互斥（各自的 CAS 前态都是 2/3，先到者把后到者的影响行打成 0），
 * 一张订单不可能同时经过结算与指令异常两条路。</p>
 *
 * <p><b>来源B 一律显式拒绝，不静默放行。</b>它需要的是真实资金返还（补退差），属后续包；
 * 本包若把它当成"已退过"直接确认终态，用户的钱就被无偿吞掉，且账面显示订单正常完成。</p>
 *
 * <p>本类只读 {@code DATA_STATUS=0} 的流水（走 MyBatis-Plus 逻辑删除）。被逻辑删除的退差流水
 * 会让判定退化为「查不到已退差」→ 落 UNKNOWN 或 B → 一律阻断转人工。方向是 fail-closed，
 * 不会把「删了流水」变成绕过核验放行的后门。</p>
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
     * 只读预览。不加事务：三条读之间的短暂不一致对展示无害，
     * 而确认路径会在自己的事务内用同一份 {@link #analyze} 重新判定，绝不采信本方法的结论。
     * 预览也不写任何拒绝证据——运营点开看一眼不构成一次业务判定。
     */
    @Override
    public AdminWaterAbnormalPreviewVo preview(Long orderId) {
        WsOrder order = orderId == null || orderId <= 0 ? null : wsOrderMapper.selectById(orderId);
        return toVo(orderId, order, analyze(order));
    }

    /**
     * 运营确认核账。
     *
     * <p><b>为什么是 READ_COMMITTED</b>：账本核验（退差流水聚合）是普通一致性读，
     * MySQL 默认 REPEATABLE READ 下读视图在事务首条 SELECT（读订单）时就固定，
     * 此后即使有并发售后刚提交了本单的返还流水也看不见，等于拿旧账本核出终态。
     * 本事务零资金写入，后果不是超额返还而是「按过期账本确认终态」，但结论同样驱动
     * 订单终态，没有理由容忍旧视图。权威先例与不改用锁定读的理由见
     * {@code RechargeIssueTxImpl.issue}（对不存在的行做二级索引锁定读会留 gap 锁，
     * 两笔不相关的并发操作会在相邻间隙上互等死锁；降隔离级别零新增锁面）。</p>
     *
     * <p>核验与 CAS 之间存在极短的 TOCTOU 窗口，但危害为零：流水表只插不改，
     * 账本只会增长；而订单终态由 {@code ORDER_STATUS=6} 的 CAS 独占，
     * 并发确认必然只有一个赢家，输家影响行为 0 并整体回滚。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void confirm(Long orderId, String handleRemark, Long opUserId, String now) {
        // 传播级别保持 REQUIRED（订单终态、台账与领域事件必须与调用方同生共死，
        // 不能像返还内核那样独立提交），代价是 Spring 在加入外层事务时会静默丢弃上面声明的
        // READ_COMMITTED（validateExistingTransaction 默认 false）——隔离级别没了，
        // 上面 javadoc 论证的「不拿旧账本核出终态」这条前提也就没了，而且没有任何报错。
        // 今天唯一调用方 AdminAfterSaleServiceImpl 不带事务，声明值实际生效；这道断言是为了
        // 哪天有人在外面包一层事务时立刻炸掉。与 AfterSaleActionTxServiceImpl.executeInTx
        // 的同款断言成对存在：两处都靠隔离级别成立，就不该只有一处有护栏。
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
            // 不做「为空就取当前时间」的兜底：那会让订单、台账与审计出现两套时钟
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
        // 刻意不改写 FINISH_TIME 与 CANCEL_REASON：前者是结算完成时刻、后者是原始异常原因，
        // 都是本次核账所依据的证据，用核账时间和核账备注覆盖它们等于把证据改成结论。
        // 核账时刻与说明落在售后台账行与领域事件里。
        int moved = wsOrderMapper.update(null, Wrappers.lambdaUpdate(WsOrder.class)
                .set(WsOrder::getOrderStatus, verdict.targetStatus())
                .set(WsOrder::getUpdateBy, opUserId)
                .set(WsOrder::getUpdateTime, now)
                .eq(WsOrder::getId, orderId)
                .eq(WsOrder::getOrderStatus, TradeEnum.OrderStatus.ABNORMAL.getValue()));
        if (moved != 1) {
            throw rejected(orderId, order, "订单已被并发推进出异常待补偿状态，核账确认未生效", now);
        }

        // E2E-08 口径补齐：人工核账确认 6→4 与结算路径是同一个「完成」事实，必须同产分账
        // （FINISHED+余额支付，基数=实扣=订单金额-已退差；两路口径分叉=同类单分润有无全看走哪条路）。
        // 6→7 零出水退款不分账；撞唯一键幂等跳过由 enqueueForOrder 自身保证。
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

        // 正向状态审计与业务同事务（铁律④）：核账结论若提交、审计必须一起在；
        // 撞幂等键时 recordReliableOnce 会读回核验语义一致性，不一致即整体回滚。
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
     * 不允许出现「有阻断原因但仍带着一个建议终态」的中间对象，那种对象一旦被调用方
     * 只读了 targetStatus 就会绕过阻断。
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
     * 终态派生：零出水等同退款完成(7)，部分出水等同正常完成(4)。
     *
     * <p>实际量达到或超过计划量时返回 null 而不是"也算完成"：那说明水已足量出完，
     * 结算路径本不该判失败，落 6 的原因超出本核账口径，只能交人工判断。</p>
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
     * 账本与「计划-实际」的一致性核对。
     *
     * <p><b>payWay=3 与零出水场景是精确等式</b>：退差水量必须恰等于 {@code 计划 − min(实际, 计划)}；
     * 零出水的余额单必须恰等于订单预扣金额（实际应扣为 0）。两者都不依赖单价。</p>
     *
     * <p><b>payWay=2 的部分出水只能核到强不变量而非精确值</b>：精确差额 =
     * {@code 预扣 − ceil(实际 × 单价 ÷ 1000)}，需要订单快照里的单价。本类刻意不解析该快照——
     * 单价快照的解析与回退口径只有结算路径一份实现（铁律⑤），在核账里再写一份，
     * 两份从落地第一天起就可能漂移，而漂移的表现是「核账说账不对、结算说账对」这种
     * 无人能裁决的对账争议。此处核到方向、维度与上界（退差为正、不退错维度、不超过预扣金额），
     * 精确性由退差路径自身的断言保证。</p>
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
     *
     * <p><b>本方法只 insert 一行，不调用任何返还 API</b>——这正是不注入返还能力的意义所在：
     * 想在这里"顺手把钱退了"必须先改依赖清单。动作类型登记为卡内退款是因为本单的资金形态
     * 确实是卡内退款（钱已由结算路径退回卡内），本行记录的是「这笔卡内退款经核账确认」，
     * 而不是「本行执行了一笔卡内退款」；额度三列全 0 精确表达了「本次动作零资金变化」，
     * 也让按订单聚合的累计封顶不会因为这条留痕而少算可返额度。</p>
     *
     * <p>幂等由 {@code uk_after_sale_source(SOURCE_TYPE, SOURCE_ID)} 与 {@code uk_after_sale_no}
     * 承担（铁律②），不做应用层查重。撞键即整事务回滚而不是"当成已完成返回"：
     * 订单终态 CAS 已经在本事务前面独占了 6→终态这一步，能走到这里还撞键说明有本路径之外
     * 的写入方，属账本异常，必须炸出来。</p>
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
     * 拒绝证据独立提交（铁律④，REQUIRES_NEW）：主事务随后回滚正是拒绝的业务结果，
     * 证据若跟着回滚，"拒绝过"就成了无痕事件，运营与审计都无从得知曾有人试图确认。
     * 手法与 {@code TradeOrderTxServiceImpl.scopeRejected} 一致：固定业务键 + 报文带判定时刻。
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
