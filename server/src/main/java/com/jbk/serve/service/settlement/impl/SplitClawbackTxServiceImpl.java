package com.jbk.serve.service.settlement.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.aftersale.WsAfterSaleActionMapper;
import com.jbk.serve.mapper.delivery.WsDeliveryAppealMapper;
import com.jbk.serve.mapper.delivery.WsDeliveryTaskMapper;
import com.jbk.serve.mapper.settlement.WsIncomeAccountMapper;
import com.jbk.serve.mapper.settlement.WsIncomeFlowMapper;
import com.jbk.serve.mapper.settlement.WsSplitClawbackActionMapper;
import com.jbk.serve.mapper.settlement.WsSplitClawbackMapper;
import com.jbk.serve.mapper.settlement.WsSplitRecordMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.service.settlement.ISplitClawbackTxService;
import com.jbk.tool.consts.aftersale.AfterSaleEnum;
import com.jbk.tool.consts.settlement.SettlementEnum;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.data.aftersale.po.WsAfterSaleAction;
import com.jbk.tool.data.delivery.po.WsDeliveryTask;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.settlement.po.WsIncomeAccount;
import com.jbk.tool.data.settlement.po.WsIncomeFlow;
import com.jbk.tool.data.settlement.po.WsSplitClawback;
import com.jbk.tool.data.settlement.po.WsSplitClawbackAction;
import com.jbk.tool.data.settlement.po.WsSplitRecord;
import com.jbk.tool.exception.JbkException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分润退款冲减实现（D-420 R2 两段式：动作级 outbox）。
 *
 * <h3>段1·登记＝不可失败路径（R2-P0-1）</h3>
 * <p>运行在客户退款成功事务内，只做一件事：单行 INSERT 动作级 outbox
 * （ws_split_clawback_action，ACTION_ID 唯一，冻结 orderId/actionType/refundProductFen）。
 * 零分账行读、零任务行读、零快照解析、零额度校验；撞键走不可变参数等价核验——
 * 同参数=幂等返回，参数漂移=既有登记置需人工（fail-closed）。两种情况都不抛异常、
 * 不产生第二份事实、不回滚退款。登记仅剩的失败面是这一条 INSERT 本身的基础设施
 * 故障——彼时退款事务自身也无法提交，不构成「冲减拖垮已完成退款」。</p>
 *
 * <h3>段2·执行＝对账后动账（R2-P0-2 / R3-P1-1 / R4）</h3>
 * <p>REQUIRES_NEW 独立事务：锁 outbox 行 → 权威动作复核（selectById 过滤逻辑删；
 * SUCCESS；类型仍为冲减型；三项冻结参数与权威动作逐字相等）→ 权威订单与来源共键
 * （{@link ClawbackSourceGate}：来源↔订单类型、售后号确定性派生、直锚来源
 * sourceId==orderId、申诉实体三共键；<b>无分账收敛仅限白名单来源</b>——待接单取消
 * （另过取消终态确认：订单已退款+任务已取消）与充值退款；申诉/取水异常等履约后
 * 来源分账行缺失=证据丢失转人工，未知来源恒 fail-closed）→ 锁分账行（平台行完整
 * 身份三元组恰一行：RECEIVER_TYPE 平台+收款方 0+REMAINDER 快照，伪装/错置/缺失/
 * 重复均转人工，防份额转嫁与错扣普通收款人）→ 锁明细事实 → 以锁内权威份额重算
 * 期望明细集（订单累计排除本动作，R2-P1-1；份额合计为 0 而退款额为正=证据矛盾
 * 转人工）→ 对账（集合完备、双键匹配、逐行金额相等、合计恒等退款额、行累计不破
 * 份额上限、收益账户在位且流水键未被占用）→ <b>全部合格才动第一笔账</b>。任何一处
 * 不合格=整动作置需人工、零资金/份额更新，不存在部分 DONE 部分 MANUAL 的混合
 * 中间态；结构性失败（CAS 争用等）抛出由 Worker 下轮重试。</p>
 *
 * <h3>分摊公式（R1-2，保持不变）</h3>
 * <p>行水费线<b>原始份额</b> originalShare：取水行=SPLIT_AMOUNT 全额；配送分线行
 * "W&lt;a&gt;+D&lt;b&gt;"=水费基数×a/10000（与派发同式）；"D&lt;b&gt;" 行=0；
 * 平台 REMAINDER=基数−其余行份额。<b>本次冲减</b>：非平台行=
 * floor(originalShare×REFUND_PRODUCT_FEN÷水费基数)，平台行=REFUND_PRODUCT_FEN−
 * Σ非平台行（吃舍入余数）——全行合计精确等于本次实际退款额。
 * 行累计恒 ≤ originalShare、订单累计恒 ≤ 水费基数。</p>
 *
 * <h3>锁序</h3>
 * <p>执行段：outbox（按动作）→ split（按订单 ORDER BY ID FOR UPDATE）→
 * clawback 明细（ORDER BY ID）→ income_account。登记段不再持任何分账/任务锁
 * （退款事务与结算零竞争）；执行段与结算 settleOne（split→account）同向，
 * 无对向死锁；同动作并发执行被 outbox 行锁完全串行化，同订单多动作被
 * 分账行锁串行化。</p>
 */
@Slf4j
@Service
public class SplitClawbackTxServiceImpl implements ISplitClawbackTxService {

    @Autowired
    private WsAfterSaleActionMapper actionMapper;
    @Autowired
    private WsOrderMapper orderMapper;
    @Autowired
    private WsSplitRecordMapper splitRecordMapper;
    @Autowired
    private WsSplitClawbackActionMapper actionOutboxMapper;
    @Autowired
    private WsSplitClawbackMapper clawbackMapper;
    @Autowired
    private WsDeliveryTaskMapper deliveryTaskMapper;
    @Autowired
    private WsDeliveryAppealMapper appealMapper;
    @Autowired
    private WsIncomeAccountMapper incomeAccountMapper;
    @Autowired
    private WsIncomeFlowMapper incomeFlowMapper;

    // ==================== 段1·登记（随退款成功事务，不可失败） ====================

    @Override
    public void registerForAction(WsAfterSaleAction action, String now) {
        // 铁律（R2-P0-1）：本方法在客户退款成功事务内被调——除单行 outbox INSERT 外
        // 不做任何事，也绝不因冲减侧问题向上抛异常拖垮已完成的退款
        if (action == null || action.getId() == null) {
            log.error("冲减登记入参缺售后动作ID，无法留痕（退款不受影响），待对账兜底");
            return;
        }
        if (!isClawbackActionType(action.getActionType())) {
            // CARD_COMPENSATE 补偿及其他类型：明确不属退款冲减（R1-1.3）
            return;
        }
        long refundFen = zeroIfNull(action.getRefundProductFen());
        if (refundFen <= 0) {
            // 纯水量返还或只退配送费：零货币冲减（R1-1.5/1.6）
            return;
        }
        if (action.getOrderId() == null) {
            log.error("冲减登记缺订单ID，无法登记，转线下核查：action={}", action.getId());
            return;
        }
        WsSplitClawbackAction outbox = new WsSplitClawbackAction()
                .setActionId(action.getId())
                .setOrderId(action.getOrderId())
                .setActionType(action.getActionType())
                .setRefundProductFen(refundFen)
                .setOutboxStatus(SettlementEnum.ClawbackStatus.PENDING.getValue())
                .setCreateTime(now);
        try {
            actionOutboxMapper.insert(outbox);
        }
        catch (DuplicateKeyException e) {
            reconcileReplayRegistration(action.getId(), action.getOrderId(),
                    action.getActionType(), refundFen);
        }
    }

    /**
     * 登记撞键分流（R2-P1-1）：同 actionId 再次登记时做不可变参数等价核验。
     * 逐字相同=幂等命中直接返回（不重算、不追加、不动账）；参数漂移=既有登记
     * 置需人工（fail-closed），绝不产生第二份事实集，也绝不抛异常回滚本次退款。
     */
    private void reconcileReplayRegistration(Long actionId, Long orderId, Integer actionType,
                                             long refundFen) {
        // 锁定读：RR 事务快照可能看不见撞键那行（它在本事务快照之后提交），
        // FOR UPDATE 恒读最新已提交——outbox 行是叶子锁，无对向持锁方
        WsSplitClawbackAction existing = actionOutboxMapper.lockByActionIdForUpdate(actionId);
        if (existing == null) {
            // 撞键却锁不到行：理论不可达（uk 冲突证明行已提交且未逻辑删），只留日志，
            // 执行段的权威复核是兜底闸
            log.error("冲减登记撞键但读不到既有行，留观：action={}", actionId);
            return;
        }
        boolean same = ObjectUtil.equal(existing.getOrderId(), orderId)
                && ObjectUtil.equal(existing.getActionType(), actionType)
                && ObjectUtil.equal(existing.getRefundProductFen(), refundFen);
        if (same) {
            log.info("冲减登记幂等命中（同动作同参数重放）：action={}", actionId);
            return;
        }
        actionOutboxMapper.update(null, Wrappers.lambdaUpdate(WsSplitClawbackAction.class)
                .eq(WsSplitClawbackAction::getId, existing.getId())
                .set(WsSplitClawbackAction::getOutboxStatus,
                        SettlementEnum.ClawbackStatus.MANUAL.getValue())
                .set(WsSplitClawbackAction::getProcessRemark, clampRemark(
                        "重复登记参数漂移（原状态" + existing.getOutboxStatus() + "）：既有 order/type/fen="
                                + existing.getOrderId() + "/" + existing.getActionType() + "/"
                                + existing.getRefundProductFen() + "，来件=" + orderId + "/"
                                + actionType + "/" + refundFen)));
        log.error("冲减登记参数漂移，动作置需人工：action={}", actionId);
    }

    // ==================== 段2·执行（独立事务：对账后动账） ====================

    /**
     * READ_COMMITTED：锁定读醒来后必须看见最新已提交事实（N-15 先例），
     * RR 快照会让对账与账户扣回对旧值恒 CAS 失败。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED)
    public void processAction(Long actionId, String now) {
        if (actionId == null || actionId <= 0) {
            throw new JbkException("售后动作ID非法，冲减执行中止");
        }
        if (now == null || now.length() != 14) {
            throw new JbkException("冲减执行时间参数非法");
        }
        // ① 执行权威入口：锁动作级 outbox（R2-P0-2 发现源），同动作并发执行完全串行化
        WsSplitClawbackAction outbox = actionOutboxMapper.lockByActionIdForUpdate(actionId);
        if (outbox == null) {
            // 未登记动作（补偿/纯水量等非冲减型进入执行驱动）：合法 no-op
            return;
        }
        if (!statusIs(outbox.getOutboxStatus(), SettlementEnum.ClawbackStatus.PENDING)) {
            // 已完成=幂等重放零副作用；需人工=冻结待人工处置，绝不自动重试
            return;
        }
        long refundFen = zeroIfNull(outbox.getRefundProductFen());
        // ② 权威动作复核：selectById 走 @TableLogic（逻辑删=查不到）；
        //    冻结参数必须与权威动作逐字相等——不等即证据被动过，整动作转人工
        WsAfterSaleAction action = actionMapper.selectById(actionId);
        if (ObjectUtil.isNull(action)) {
            parkAction(outbox, List.of(), "权威售后动作不存在或已被逻辑删除");
            return;
        }
        if (ObjectUtil.notEqual(action.getActionStatus(),
                AfterSaleEnum.ActionStatus.SUCCESS.getValue())) {
            parkAction(outbox, List.of(),
                    "权威售后动作不是成功状态（" + action.getActionStatus() + "）");
            return;
        }
        if (!isClawbackActionType(action.getActionType())
                || ObjectUtil.notEqual(action.getActionType(), outbox.getActionType())
                || ObjectUtil.notEqual(action.getOrderId(), outbox.getOrderId())
                || zeroIfNull(action.getRefundProductFen()) != refundFen) {
            parkAction(outbox, List.of(), "权威动作与登记参数不一致：登记 order/type/fen="
                    + outbox.getOrderId() + "/" + outbox.getActionType() + "/" + refundFen
                    + "，权威=" + action.getOrderId() + "/" + action.getActionType() + "/"
                    + action.getRefundProductFen());
            return;
        }
        // ②′ 权威订单与来源共键（R3-P1-1 / R4-P1-1）：订单在位后，来源归属交给统一
        //     校验器证明——枚举与订单类型只是先决条件，还必须核 SOURCE_ID 锚定、
        //     售后号确定性派生、申诉实体三共键。任一不符=证据不一致，整动作转人工
        WsOrder order = orderMapper.selectById(outbox.getOrderId());
        if (ObjectUtil.isNull(order)) {
            parkAction(outbox, List.of(), "权威订单不存在或已被逻辑删除：order=" + outbox.getOrderId());
            return;
        }
        Integer sourceType = action.getSourceType();
        ClawbackSourceGate sourceGate = new ClawbackSourceGate(appealMapper, deliveryTaskMapper);
        String coKeyMismatch = sourceGate.coKeyMismatch(action, order);
        if (coKeyMismatch != null) {
            parkAction(outbox, List.of(), coKeyMismatch);
            return;
        }
        // ③ 锁序：split（订单全行 ORDER BY ID）→ 本动作明细事实（ORDER BY ID）
        List<WsSplitRecord> rows = splitRecordMapper.lockByOrderIdForUpdate(outbox.getOrderId());
        List<WsSplitClawback> facts = clawbackMapper.lockByActionForUpdate(actionId);
        if (rows.isEmpty()) {
            if (!facts.isEmpty()) {
                parkAction(outbox, facts, "订单无分账行但存在冲减明细，证据不一致");
                return;
            }
            // 零分账收敛必须过来源分类（R3-P1-1）：待接单取消=履约未发生、充值退款=充值单
            // 无分账线，这两类「无分账」是业务事实；申诉/取水异常只在履约完成后出现，
            // 分账行理应存在——缺失即证据丢失，未知来源同样 fail-closed，
            // 绝不以「没有分账行」统一收敛掩盖证据缺失
            if (isZeroSplitLegalSource(sourceType)) {
                if (ObjectUtil.equal(sourceType, AfterSaleEnum.SourceType.DELIVERY_CANCEL.getValue())) {
                    // R4-P1-1：「待接单取消」不能只凭 sourceType 推断——收敛前按取消事务
                    // 终态精确确认（订单已退款+任务已取消+未签收），不符即转人工
                    String stageMismatch = sourceGate.cancelStageMismatch(order);
                    if (stageMismatch != null) {
                        parkAction(outbox, List.of(), stageMismatch);
                        return;
                    }
                }
                finishOutbox(outbox, "来源【" + sourceDesc(sourceType) + "】处于无分账阶段，合法零冲减");
            }
            else {
                parkAction(outbox, List.of(), "来源【" + sourceDesc(sourceType)
                        + "】预期存在分账，但分账行缺失，证据不完整");
            }
            return;
        }
        // 平台行完整身份（R3-P1-1 / R4-P1-2）：三元组（RECEIVER_TYPE=平台、收款方=0、
        // 快照=REMAINDER）恰一行。只认快照字符串会被普通收款行伪装——删真平台行+把机主行
        // 快照改成 REMAINDER 即可骗过行数检查，随后机主既吃平台份额又被账户扣回
        String platformMismatch = platformEvidenceMismatch(rows);
        if (platformMismatch != null) {
            parkAction(outbox, facts, platformMismatch);
            return;
        }
        // ④ 期望明细集重算（权威口径）：形状/基数证据异常=转人工，绝不无限重试
        List<ShareRow> shares;
        long shareTotal;
        List<WsSplitClawback> expected;
        try {
            long waterBase = resolveWaterBase(outbox.getOrderId(), rows);
            shares = shareRowsOf(rows, waterBase);
            shareTotal = shares.stream().mapToLong(ShareRow::originalShare).sum();
            if (shareTotal <= 0) {
                // 登记闸保证 refundFen>0：水费线份额为 0 却登记了正额水品退款=证据与
                // 订单事实矛盾（R3-P1-1），转人工而非收敛完成
                parkAction(outbox, facts, "水费线份额为0但登记了正额水品退款（" + refundFen
                        + "），证据与订单事实不一致");
                return;
            }
            // 订单累计上限：其它动作已登记明细 + 本动作 ≤ 水费线份额
            // （排除本动作既有明细——R2-P1-1 整额退款重放不自撞）
            long registeredOther = 0;
            for (ShareRow share : shares) {
                registeredOther += zeroIfNull(
                        clawbackMapper.sumBySplitExcludingAction(share.row().getId(), actionId));
            }
            if (registeredOther + refundFen > shareTotal) {
                parkAction(outbox, facts, "累计冲减(" + (registeredOther + refundFen)
                        + ")超出订单水费线份额(" + shareTotal + ")");
                return;
            }
            expected = expectedFactsOf(outbox, shares, shareTotal, refundFen);
        }
        catch (JbkException evidence) {
            parkAction(outbox, facts, "分账证据异常：" + evidence.getMessage());
            return;
        }
        // ⑤ 对账（R2-P0-2）：既有明细必须与期望集完全一致——半套明细绝不当完整集执行
        if (!facts.isEmpty()) {
            String mismatch = reconcileMismatch(facts, expected, outbox);
            if (mismatch != null) {
                parkAction(outbox, facts, mismatch);
                return;
            }
        }
        // ⑥ 动账前置校验（零写）：行累计上限、收益账户在位、流水幂等键未被占用——
        //    全部合格才动第一笔账（parkAction 只改状态列，天然零资金更新）
        List<WsSplitClawback> working = facts.isEmpty() ? expected : facts;
        Map<Long, WsSplitRecord> rowById = new HashMap<>();
        Map<Long, Long> shareBySplit = new HashMap<>();
        for (ShareRow share : shares) {
            rowById.put(share.row().getId(), share.row());
            shareBySplit.put(share.row().getId(), share.originalShare());
        }
        for (WsSplitClawback fact : working) {
            WsSplitRecord row = rowById.get(fact.getSplitId());
            if (row == null) {
                parkAction(outbox, facts, "明细指向的分账行不存在：split=" + fact.getSplitId());
                return;
            }
            long amount = zeroIfNull(fact.getClawbackAmount());
            long reversed = zeroIfNull(row.getReversedAmount());
            long share = shareBySplit.getOrDefault(row.getId(), 0L);
            if (amount <= 0 || reversed + amount > share) {
                parkAction(outbox, facts, "冲减额与行份额上限冲突：split=" + row.getId()
                        + " 累计 " + reversed + "+" + amount + " > " + share);
                return;
            }
            if (needAccountClaw(row)) {
                WsIncomeAccount account = incomeAccountMapper
                        .selectByUserIdForUpdate(row.getReceiverUserId());
                if (ObjectUtil.isNull(account)) {
                    parkAction(outbox, facts, "收款方收益账户不存在：user=" + row.getReceiverUserId());
                    return;
                }
                Long occupied = incomeFlowMapper.selectCount(Wrappers.lambdaQuery(WsIncomeFlow.class)
                        .eq(WsIncomeFlow::getBizIdempotencyKey, flowKeyOf(actionId, row.getId())));
                if (occupied != null && occupied > 0) {
                    parkAction(outbox, facts, "冲减流水已存在但动作未完成，证据链不一致：split="
                            + row.getId());
                    return;
                }
            }
        }
        // ⑦ 动账段（全部校验已过）：首执插明细 → 行累计 → 已入账扣回 → 明细置完成 → outbox 置完成
        if (facts.isEmpty()) {
            for (WsSplitClawback fact : expected) {
                // uk(ACTION_ID,SPLIT_ID)：outbox 行锁在手，撞键=结构性异常，抛出重试
                clawbackMapper.insert(fact);
            }
        }
        for (WsSplitClawback fact : working) {
            WsSplitRecord row = rowById.get(fact.getSplitId());
            long amount = fact.getClawbackAmount();
            long reversed = zeroIfNull(row.getReversedAmount());
            // 分账行累计冲减（R1-3.1）：REVERSED_AMOUNT 为累计口径，结算/入账按净额
            int updated = splitRecordMapper.update(null, Wrappers.lambdaUpdate(WsSplitRecord.class)
                    .eq(WsSplitRecord::getId, row.getId())
                    .eq(WsSplitRecord::getReversedAmount, reversed)
                    .set(WsSplitRecord::getReversedAmount, reversed + amount));
            if (updated != 1) {
                // 行锁在手 CAS 仍 0 行=结构性异常
                throw new JbkException("分账行累计冲减 CAS 失败，执行中止：split=" + row.getId());
            }
            row.setReversedAmount(reversed + amount);
            // 已入账（DONE）份额需从收益账户扣回；PENDING 行冻结期内零资金动作。
            // 扣回额=本次冲减全额：行已结算即整行曾按当时净额入账，账不会扣穿，缺口进差额
            boolean settled = needAccountClaw(row);
            if (settled) {
                clawbackFromAccount(row.getReceiverUserId(), amount, actionId,
                        row.getId(), row.getSplitRemark(), now);
            }
            int done = clawbackMapper.update(null, Wrappers.lambdaUpdate(WsSplitClawback.class)
                    .eq(WsSplitClawback::getId, fact.getId())
                    .eq(WsSplitClawback::getClawbackStatus,
                            SettlementEnum.ClawbackStatus.PENDING.getValue())
                    .set(WsSplitClawback::getClawbackStatus,
                            SettlementEnum.ClawbackStatus.DONE.getValue())
                    .set(WsSplitClawback::getProcessRemark,
                            settled ? "已入账份额收益扣回" : "冻结期内登记回退，零资金动作"));
            if (done != 1) {
                throw new JbkException("冲减明细状态推进失败：fact=" + fact.getId());
            }
        }
        finishOutbox(outbox, "冲减完成：明细 " + working.size() + " 行，合计 " + refundFen);
    }

    /** 期望明细集：非平台行按原始份额比例向下取整，平台行吃舍入余数——合计精确等于退款额（R1-2）。 */
    private List<WsSplitClawback> expectedFactsOf(WsSplitClawbackAction outbox, List<ShareRow> shares,
                                                  long shareTotal, long refundFen) {
        long assigned = 0;
        ShareRow platform = null;
        List<WsSplitClawback> expected = new ArrayList<>();
        for (ShareRow share : shares) {
            if (share.isPlatform()) {
                platform = share;
                continue;
            }
            if (share.originalShare() <= 0) {
                continue;
            }
            long amount = Math.multiplyExact(share.originalShare(), refundFen) / shareTotal;
            assigned += amount;
            if (amount > 0) {
                expected.add(factOf(outbox, share.row(), amount));
            }
        }
        long remainder = refundFen - assigned;
        if (remainder < 0) {
            throw new JbkException("冲减分摊异常（合计超出退款额）");
        }
        if (remainder > 0) {
            if (platform == null) {
                throw new JbkException("订单缺少平台分账行，舍入余数无处归属");
            }
            expected.add(factOf(outbox, platform.row(), remainder));
        }
        return expected;
    }

    private static WsSplitClawback factOf(WsSplitClawbackAction outbox, WsSplitRecord row, long amount) {
        return new WsSplitClawback()
                .setActionId(outbox.getActionId())
                .setOrderId(outbox.getOrderId())
                .setSplitId(row.getId())
                .setClawbackAmount(amount)
                .setClawbackStatus(SettlementEnum.ClawbackStatus.PENDING.getValue());
    }

    /**
     * 明细对账（R2-P0-2.4）：既有明细必须与期望集完全一致才允许动账——集合完备
     * （无缺行无多行；uk 保证明细 splitId 互异，行数相等+逐行命中即双射）、每行
     * (actionId,splitId,orderId) 双键匹配、逐行金额与重算相等、全体待处理、
     * 合计精确等于 REFUND_PRODUCT_FEN。返回 null=合格，否则返回不合格原因。
     */
    private String reconcileMismatch(List<WsSplitClawback> facts, List<WsSplitClawback> expected,
                                     WsSplitClawbackAction outbox) {
        if (facts.size() != expected.size()) {
            return "明细集不完备：现存 " + facts.size() + " 行，期望 " + expected.size() + " 行";
        }
        Map<Long, Long> expectedBySplit = new HashMap<>();
        for (WsSplitClawback e : expected) {
            expectedBySplit.put(e.getSplitId(), e.getClawbackAmount());
        }
        long sum = 0;
        for (WsSplitClawback fact : facts) {
            if (!statusIs(fact.getClawbackStatus(), SettlementEnum.ClawbackStatus.PENDING)) {
                return "明细状态异常（非待处理）：fact=" + fact.getId()
                        + " status=" + fact.getClawbackStatus();
            }
            if (ObjectUtil.notEqual(fact.getOrderId(), outbox.getOrderId())) {
                return "明细订单与登记不一致：fact=" + fact.getId();
            }
            Long want = expectedBySplit.get(fact.getSplitId());
            if (want == null) {
                return "明细含期望集外的分账行：split=" + fact.getSplitId();
            }
            long amount = zeroIfNull(fact.getClawbackAmount());
            if (amount != want) {
                return "明细金额与重算不符：split=" + fact.getSplitId()
                        + " 现存 " + amount + " 期望 " + want;
            }
            sum += amount;
        }
        if (sum != zeroIfNull(outbox.getRefundProductFen())) {
            return "明细合计(" + sum + ")不等于登记退款额(" + outbox.getRefundProductFen() + ")";
        }
        return null;
    }

    /**
     * 整动作转人工（R2-P0-2 全-或-人工）：outbox 置3留因，既有待处理明细一并置3。
     * 只改状态列，零资金/份额更新——「部分行完成部分行人工」的混合中间态不存在。
     */
    private void parkAction(WsSplitClawbackAction outbox, List<WsSplitClawback> facts, String reason) {
        int updated = actionOutboxMapper.update(null, Wrappers.lambdaUpdate(WsSplitClawbackAction.class)
                .eq(WsSplitClawbackAction::getId, outbox.getId())
                .eq(WsSplitClawbackAction::getOutboxStatus,
                        SettlementEnum.ClawbackStatus.PENDING.getValue())
                .set(WsSplitClawbackAction::getOutboxStatus,
                        SettlementEnum.ClawbackStatus.MANUAL.getValue())
                .set(WsSplitClawbackAction::getProcessRemark, clampRemark(reason)));
        if (updated != 1) {
            // outbox 行锁在手仍写不动=结构性异常
            throw new JbkException("冲减动作转人工写入失败：action=" + outbox.getActionId());
        }
        for (WsSplitClawback fact : facts) {
            if (statusIs(fact.getClawbackStatus(), SettlementEnum.ClawbackStatus.PENDING)) {
                clawbackMapper.update(null, Wrappers.lambdaUpdate(WsSplitClawback.class)
                        .eq(WsSplitClawback::getId, fact.getId())
                        .eq(WsSplitClawback::getClawbackStatus,
                                SettlementEnum.ClawbackStatus.PENDING.getValue())
                        .set(WsSplitClawback::getClawbackStatus,
                                SettlementEnum.ClawbackStatus.MANUAL.getValue())
                        .set(WsSplitClawback::getProcessRemark, clampRemark("随动作转人工：" + reason)));
            }
        }
        log.error("冲减动作转人工：action={} {}", outbox.getActionId(), reason);
    }

    /** 动作完成留痕（合法零冲减或全额执行完毕）。 */
    private void finishOutbox(WsSplitClawbackAction outbox, String remark) {
        int updated = actionOutboxMapper.update(null, Wrappers.lambdaUpdate(WsSplitClawbackAction.class)
                .eq(WsSplitClawbackAction::getId, outbox.getId())
                .eq(WsSplitClawbackAction::getOutboxStatus,
                        SettlementEnum.ClawbackStatus.PENDING.getValue())
                .set(WsSplitClawbackAction::getOutboxStatus,
                        SettlementEnum.ClawbackStatus.DONE.getValue())
                .set(WsSplitClawbackAction::getProcessRemark, clampRemark(remark)));
        if (updated != 1) {
            throw new JbkException("冲减动作完成状态写入失败：action=" + outbox.getActionId());
        }
    }

    /** 收益账户扣回：可扣部分原子扣回并写唯一流水，缺口进待补差额（恒不为负）。 */
    private void clawbackFromAccount(Long userId, long amount, Long actionId, Long splitId,
                                     String orderNo, String now) {
        WsIncomeAccount account = incomeAccountMapper.selectByUserIdForUpdate(userId);
        if (ObjectUtil.isNull(account)) {
            throw new JbkException("收款方收益账户不存在，冲减执行中止：user=" + userId);
        }
        long balance = requireNonNegative(account.getBalanceFen(), "收益余额");
        long deficit = requireNonNegative(
                account.getClawbackDeficitFen() == null ? 0L : account.getClawbackDeficitFen(), "待补差额");
        long available = Math.min(balance, amount);
        long deficitDelta = amount - available;
        if (available > 0) {
            WsIncomeFlow flow = new WsIncomeFlow()
                    .setUserId(userId)
                    .setFlowType(SettlementEnum.IncomeFlowType.SPLIT_REVERSE.getValue())
                    .setAmountFen(-available)
                    .setAfterFen(balance - available)
                    .setSplitId(splitId)
                    .setOrderNo(orderNo)
                    .setBizIdempotencyKey(flowKeyOf(actionId, splitId))
                    .setFlowRemark("水费退款分润扣回（D-420）");
            try {
                incomeFlowMapper.insert(flow);
            }
            catch (DuplicateKeyException e) {
                // 动账前置校验已查键占用；此处撞键=校验后被并发写入，结构性异常抛出重试
                throw new JbkException("冲减流水幂等键冲突，执行中止：split=" + splitId);
            }
        }
        int updated = incomeAccountMapper.update(null, Wrappers.lambdaUpdate(WsIncomeAccount.class)
                .eq(WsIncomeAccount::getId, account.getId())
                .eq(WsIncomeAccount::getVersion, account.getVersion())
                .set(WsIncomeAccount::getBalanceFen, balance - available)
                .set(WsIncomeAccount::getClawbackDeficitFen, deficit + deficitDelta)
                .set(WsIncomeAccount::getVersion, account.getVersion() + 1));
        if (updated != 1) {
            throw new JbkException("收益账户扣回写入失败（版本并发漂移），冲减中止：user=" + userId);
        }
    }

    // ==================== 份额模型（与派发同源） ====================

    private record ShareRow(WsSplitRecord row, long originalShare, boolean isPlatform) {
    }

    private static boolean isClawbackActionType(Integer actionType) {
        return ObjectUtil.equal(actionType, AfterSaleEnum.ActionType.CARD_REFUND.getValue())
                || ObjectUtil.equal(actionType, AfterSaleEnum.ActionType.GATEWAY_REFUND.getValue());
    }

    /**
     * 允许「无分账行→合法零冲减完成」的来源白名单（R3-P1-1）：
     * 待接单取消（履约未发生，分账从未派发；收敛前还须过取消终态确认，R4-P1-1）
     * 与充值退款（充值单没有分账线）。申诉/取水异常等履约后来源不在此列——
     * 它们的订单理应带分账证据。来源共键核验统一在 {@link ClawbackSourceGate}。
     */
    private static boolean isZeroSplitLegalSource(Integer sourceType) {
        return ObjectUtil.equal(sourceType, AfterSaleEnum.SourceType.DELIVERY_CANCEL.getValue())
                || ObjectUtil.equal(sourceType, AfterSaleEnum.SourceType.RECHARGE_REFUND.getValue());
    }

    /** 留痕用来源描述：已知来源用枚举文案，未知值原样标注。 */
    private static String sourceDesc(Integer sourceType) {
        for (AfterSaleEnum.SourceType candidate : AfterSaleEnum.SourceType.values()) {
            if (ObjectUtil.equal(sourceType, candidate.getValue())) {
                return candidate.getDesc();
            }
        }
        return "未知来源" + sourceType;
    }

    /**
     * 已入账（DONE）且有真实收款方的行才需要收益账户扣回；平台行自担、
     * 恒不进账户扣回路径（R4-P1-2 显式排除，三元组闸之外的第二道保险）。
     */
    private static boolean needAccountClaw(WsSplitRecord row) {
        return ObjectUtil.equal(row.getSplitStatus(), SettlementEnum.SplitStatus.DONE.getValue())
                && ObjectUtil.notEqual(row.getReceiverType(),
                        SettlementEnum.ReceiverType.PLATFORM.getValue())
                && row.getReceiverUserId() != null && row.getReceiverUserId() != 0L;
    }

    /**
     * 平台行完整身份判据（R4-P1-2）：三元组=RECEIVER_TYPE 平台 + 收款方恒 0 +
     * 快照恒 REMAINDER，且恰一行。任一残缺形态（伪装/错置/缺失/重复）都在生成任何
     * 冲减明细与更新任何 REVERSED_AMOUNT 之前整动作拦停。返回原因；null=合格。
     */
    private static String platformEvidenceMismatch(List<WsSplitRecord> rows) {
        int fullPlatformRows = 0;
        for (WsSplitRecord row : rows) {
            boolean platformType = ObjectUtil.equal(row.getReceiverType(),
                    SettlementEnum.ReceiverType.PLATFORM.getValue());
            boolean zeroReceiver = row.getReceiverUserId() != null && row.getReceiverUserId() == 0L;
            boolean remainderSnap = "REMAINDER".equals(row.getSplitRateSnap());
            if (platformType && zeroReceiver && remainderSnap) {
                fullPlatformRows++;
                continue;
            }
            if (platformType && !zeroReceiver) {
                return "平台行收款方非0（伪装/错置收款人）：split=" + row.getId();
            }
            if (platformType) {
                return "平台行快照非 REMAINDER：split=" + row.getId();
            }
            if (remainderSnap) {
                return "非平台行使用 REMAINDER 快照（伪装平台行）：split=" + row.getId();
            }
        }
        if (fullPlatformRows != 1) {
            return "完整平台行缺失或重复（" + fullPlatformRows + " 行），分账证据不完整";
        }
        return null;
    }

    private static String flowKeyOf(Long actionId, Long splitId) {
        return "CLAWBACK:" + actionId + ":" + splitId;
    }

    /** 水费基数：分线单取任务行创单冻结的 WATER_AMOUNT；取水单=-1 哨兵（行金额即份额）。 */
    private long resolveWaterBase(Long orderId, List<WsSplitRecord> rows) {
        boolean lineSplit = rows.stream().anyMatch(r -> {
            String snap = r.getSplitRateSnap();
            return snap != null && (snap.startsWith("W") || snap.startsWith("D"));
        });
        if (!lineSplit) {
            return -1L;
        }
        WsDeliveryTask task = deliveryTaskMapper.selectOne(Wrappers.lambdaQuery(WsDeliveryTask.class)
                .eq(WsDeliveryTask::getOrderId, orderId));
        if (ObjectUtil.isNull(task) || task.getWaterAmount() == null || task.getWaterAmount() < 0) {
            throw new JbkException("配送分线单缺少可信水费基数（任务行缺失或金额非法）：order=" + orderId);
        }
        return task.getWaterAmount();
    }

    /** 行原始水费线份额（与 enqueue 派发算式严格同源）。 */
    private List<ShareRow> shareRowsOf(List<WsSplitRecord> rows, long waterBase) {
        List<ShareRow> shares = new ArrayList<>();
        long assigned = 0;
        ShareRow platformPlaceholder = null;
        for (WsSplitRecord row : rows) {
            String snap = row.getSplitRateSnap();
            if (snap == null || snap.isEmpty()) {
                throw new JbkException("分账行比例快照缺失：split=" + row.getId());
            }
            if ("REMAINDER".equals(snap)) {
                platformPlaceholder = new ShareRow(row, 0L, true);
                continue;
            }
            long share;
            if (waterBase < 0) {
                share = requireNonNegative(row.getSplitAmount(), "分账行金额");
            }
            else if (snap.startsWith("D")) {
                share = 0L;
            }
            else if (snap.startsWith("W")) {
                share = waterBase * parseWaterRate(snap, row.getId()) / 10_000;
            }
            else {
                throw new JbkException("无法识别的分账比例快照形态：split=" + row.getId());
            }
            assigned += share;
            shares.add(new ShareRow(row, share, false));
        }
        if (platformPlaceholder != null) {
            long base = waterBase < 0
                    ? assigned + requireNonNegative(platformPlaceholder.row.getSplitAmount(), "平台行金额")
                    : waterBase;
            long remainder = base - assigned;
            if (remainder < 0) {
                throw new JbkException("水费线份额拆分异常（合计超出基数）");
            }
            shares.add(new ShareRow(platformPlaceholder.row, remainder, true));
        }
        return shares;
    }

    private static int parseWaterRate(String snap, Long splitId) {
        int plus = snap.indexOf('+');
        String waterPart = plus > 0 ? snap.substring(1, plus) : snap.substring(1);
        try {
            int rate = Integer.parseInt(waterPart);
            if (rate < 0 || rate > 10_000) {
                throw new JbkException("水费线比例超出万分比范围：split=" + splitId);
            }
            return rate;
        }
        catch (NumberFormatException e) {
            throw new JbkException("水费线比例快照不可解析：split=" + splitId + " snap=" + snap);
        }
    }

    private static boolean statusIs(Integer status, SettlementEnum.ClawbackStatus expected) {
        return ObjectUtil.equal(status, expected.getValue());
    }

    /** PROCESS_REMARK varchar(500)：留因超长时截断保存（截断丢的是细节，不是事实本身）。 */
    private static String clampRemark(String reason) {
        return reason.length() <= 480 ? reason : reason.substring(0, 480);
    }

    private static long zeroIfNull(Long value) {
        return value == null ? 0L : value;
    }

    private static long requireNonNegative(Long value, String label) {
        if (value == null || value < 0) {
            throw new JbkException(label + "非法（" + value + "），拒绝冲减");
        }
        return value;
    }
}
