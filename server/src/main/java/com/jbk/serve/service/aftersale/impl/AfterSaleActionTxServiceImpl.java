package com.jbk.serve.service.aftersale.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.aftersale.WsAfterSaleActionMapper;
import com.jbk.serve.mapper.trade.TradeCardMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.service.aftersale.AfterSaleNo;
import com.jbk.serve.service.aftersale.AfterSaleQuota;
import com.jbk.serve.service.aftersale.AfterSaleStrategy;
import com.jbk.serve.service.aftersale.AfterSaleTransitions;
import com.jbk.serve.service.aftersale.IAfterSaleActionTxService;
import com.jbk.serve.service.aftersale.batch.EntitlementLedger;
import com.jbk.serve.service.delivery.DeliveryClock;
import com.jbk.serve.service.delivery.DeliveryConsumeFlow;
import com.jbk.serve.service.delivery.DeliveryRefundSnapshot;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.aftersale.AfterSaleEnum.ActionStatus;
import com.jbk.tool.consts.aftersale.AfterSaleEnum.ActionType;
import com.jbk.tool.consts.aftersale.AfterSaleEnum.SourceType;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.consts.user.UserEnum;
import com.jbk.tool.data.aftersale.po.WsAfterSaleAction;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsWalletFlow;
import com.jbk.tool.data.user.po.WsCard;
import com.jbk.tool.exception.JbkException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 售后返还内核事务实现（E2E-04 包A）：卡内退款 / 卡内补偿共用的唯一资金写入路径。
 * 三个事务边界三种传播不可合并（理由见各方法）；只服务配送取消与配送申诉两条来源，
 * 取水核账由不注入 {@link TradeCardMapper} 的独立事务承担、本入口显式拒绝。
 * 判定逻辑一律外借不重写（铁律⑤），本类只负责编排顺序、锁序与影响行数校验。
 *
 * @author dakang
 * @since 2026-07-29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AfterSaleActionTxServiceImpl implements IAfterSaleActionTxService {

    /**
     * 重试次数上限——单一出处：XML 无此魔数，经 markTerminal 参数传入；
     * 漏传即谓词为 NULL、影响 0 行，fail-closed 升级为需人工对账。
     */
    private static final int MAX_RETRY_COUNT = 3;

    /**
     * LAST_ERROR 是 varchar(500)，StrUtil.maxLength 截断后补 "..." 最长 n+3——
     * 传 500 会在超长错因上撞 data too long，故 497。
     */
    private static final int LAST_ERROR_TRUNCATE = 497;

    private final WsAfterSaleActionMapper actionMapper;
    private final WsOrderMapper orderMapper;
    private final TradeCardMapper tradeCardMapper;
    private final WsWalletFlowMapper walletFlowMapper;
    private final IWsDomainEventService domainEventService;
    /** 权益批次台账（包D-4）：返还回补批次剩余，必须与 executeInTx 同一事务——卡与批次同生共死。 */
    private final EntitlementLedger entitlementLedger;
    /** 分润冲减登记（D-420 R1 段1）：与卡内返还同事务（outbox）；执行段独立事务，失败不回滚返还。 */
    private final com.jbk.serve.service.settlement.ISplitClawbackTxService splitClawbackTxService;

    // ------------------------------------------------------------------
    // ① 登记待执行（并入调用方事务）
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WsAfterSaleAction createPending(WsAfterSaleAction draft, String now) {
        DeliveryClock.requireTime(now, "当前时间");
        if (ObjectUtil.isNull(draft)) {
            throw new JbkException("售后动作数据缺失");
        }
        SourceType source = SourceType.getByValue(draft.getSourceType());
        ActionType type = ActionType.getByValue(draft.getActionType());
        requireId(draft.getSourceId(), source.sourceIdMeaning());
        requireId(draft.getOrderId(), "关联订单ID");
        requireId(draft.getUserId(), "订单归属用户ID");
        // 首次购卡付款成功前不建卡，故仅「充值退款 + 机构退款」允许空卡，其余必须指向确定水卡
        boolean cardOptional = source == SourceType.RECHARGE_REFUND && type == ActionType.GATEWAY_REFUND;
        if (!cardOptional || ObjectUtil.isNotNull(draft.getCardId())) {
            requireId(draft.getCardId(), "返还目标卡ID");
        }
        if (StrUtil.isNotBlank(draft.getStrategyCode())) {
            // 策略码在这里过一次白名单，落库后执行期不再 valueOf
            AfterSaleStrategy.requireStrategy(draft.getStrategyCode());
        }

        // 合计列由 Refund.totalFen() 派生而非信任 draft：分维加总只允许一份实现
        AfterSaleStrategy.Refund refund = new AfterSaleStrategy.Refund(
                requireAmount(draft.getRefundProductFen(), "水品返还金额"),
                requireAmount(draft.getRefundServiceFen(), "配送费返还金额"),
                requireAmount(draft.getRefundProductMl(), "水品返还水量"));
        draft.setRefundAmount(refund.totalFen());

        // 不可伪造列在事务层钉死：编排层只提供业务字段，状态机起点与幂等锚点不接受外部输入
        String afterSaleNo = AfterSaleNo.derive(source.getValue(), draft.getSourceId());
        draft.setAfterSaleNo(afterSaleNo);
        draft.setActionStatus(ActionStatus.PENDING.getValue());
        draft.setVersion(1);
        draft.setRetryCount(0);
        draft.setNextRetryTime(null);
        draft.setFinishTime(null);
        draft.setLastError(null);
        draft.setCreateTime(now);
        draft.setUpdateTime(now);

        try {
            if (actionMapper.insert(draft) != 1) {
                throw new JbkException("售后动作登记影响行数异常");
            }
        } catch (DuplicateKeyException duplicated) {
            // 铁律②：幂等由 uk_after_sale_source / uk_after_sale_no 收敛，不做预查重。
            // 按售后号读回可覆盖两种撞键，且必须绕过 @TableLogic——逻辑删除行仍占键
            WsAfterSaleAction existed = actionMapper.selectByAfterSaleNoIncludingDeleted(afterSaleNo);
            if (ObjectUtil.isNull(existed)) {
                throw new JbkException("售后动作唯一键冲突但读不回既有行，账实不符");
            }
            if (ObjectUtil.notEqual(existed.getSourceType(), source.getValue())
                    || ObjectUtil.notEqual(existed.getSourceId(), draft.getSourceId())
                    || ObjectUtil.notEqual(existed.getOrderId(), draft.getOrderId())) {
                // 同一来源不可能有两种售后诉求：这不是重放，是来源键被复用或被改写
                throw new JbkException("售后号已被其他来源占用，拒绝复用：" + afterSaleNo);
            }
            if (ObjectUtil.notEqual(existed.getUserId(), draft.getUserId())
                    || ObjectUtil.notEqual(existed.getCardId(), draft.getCardId())) {
                // 归属漂移比金额错更危险：幂等返回等于默许把钱退到既有行指向的那张卡
                throw new JbkException("既有售后动作的归属与本次请求不一致，拒绝幂等复用");
            }
            log.info("售后动作幂等命中，返回既有行：afterSaleNo={} sourceType={} actionType={}",
                    afterSaleNo, source.getDesc(), type.getDesc());
            return existed;
        }
        return draft;
    }

    // ------------------------------------------------------------------
    // ② 认领（独立提交）
    // ------------------------------------------------------------------

    /** 认领必须独立于资金事务提交，理由见接口注释。 */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean claimIndependent(Long id, Integer expectedVersion, Long opUserId, String now) {
        DeliveryClock.requireTime(now, "当前时间");
        requireId(id, "售后动作ID");
        if (ObjectUtil.isNull(expectedVersion)) {
            throw new JbkException("售后动作版本缺失，无法认领");
        }
        requireOperator(opUserId);
        // 合法前态集合只能来自状态机，此处枚举一次就是第二份真相
        int to = ActionStatus.PROCESSING.getValue();
        int rows = actionMapper.claimForExecute(id, expectedVersion, to,
                AfterSaleTransitions.requireSources(to), opUserId, now);
        if (rows != 1) {
            log.info("售后动作认领落空（已被他人认领或状态/版本已变）：id={} expectedVersion={}",
                    id, expectedVersion);
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // ③ 执行返还（资金本体）
    // ------------------------------------------------------------------

    /**
     * 必须 READ_COMMITTED——RR 下 read view 在锁卡前的首条 SELECT（读售后动作行）就固定，
     * 锁卡后 {@code sumSuccessRefundByOrderForUpdate} 的额度聚合仍走那份旧视图。
     *
     * <p>攻击序列：同一订单两条合法待执行动作（同任务可多次申诉，{@code uk_after_sale_source}
     * 因 appealId 不同拦不住）→ T2 先读动作行建立旧 read view → T1 走完锁卡/聚合/返还/标记成功并提交
     * → T2 在卡行锁上醒来，聚合读到的 used 仍是 0 → 判定额度充足 → 第二次全额返还。
     * 降到 READ_COMMITTED 后每条语句读最新已提交版本，拿到卡锁再聚合才看得见赢家的成功行。</p>
     *
     * <p><b>为什么不改用 FOR SHARE 锁定读</b>：对二级索引做范围锁定读在 RR 下会留 gap 锁，
     * 两笔不相关的并发售后会在相邻索引间隙上互等死锁；降隔离级别零新增锁面。
     * 同型坑与同一处方见 {@code RechargeIssueTxImpl.issue}（同用户并发发两张卡）。</p>
     *
     * <p>入口断言 ACTION_STATUS=2执行中，本方法不再认领。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED)
    public void executeInTx(Long id, Long opUserId, String now) {
        DeliveryClock.requireTime(now, "当前时间");
        requireId(id, "售后动作ID");
        requireOperator(opUserId);
        // 注解无法自证隔离级别（被外层事务包住时静默沿用 RR 且零报错）：
        // 传播定为 REQUIRES_NEW 物理上不可吞并，再加运行时断言双保险
        Integer actual = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if (actual == null || actual != TransactionDefinition.ISOLATION_READ_COMMITTED) {
            throw new JbkException("售后返还事务隔离级别不是 READ_COMMITTED（实际 " + actual
                    + "），封顶判定会读到锁卡前的旧快照，拒绝执行");
        }

        // ── ① 读动作行并断言执行中（绕过 @TableLogic 读，删除行要能被读到并显式拒绝）──
        WsAfterSaleAction action = actionMapper.selectByIdIncludingDeleted(id);
        if (ObjectUtil.isNull(action)) {
            throw new JbkException("售后动作不存在");
        }
        if (ObjectUtil.notEqual(action.getDataStatus(), 0)) {
            throw new JbkException("售后动作已被逻辑删除，拒绝执行返还");
        }
        if (ObjectUtil.notEqual(action.getActionStatus(), ActionStatus.PROCESSING.getValue())) {
            throw new JbkException("售后动作不在执行中状态，拒绝返还：" + action.getActionStatus());
        }
        SourceType source = SourceType.getByValue(action.getSourceType());
        if (source == SourceType.WATER_ABNORMAL) {
            // 核账是零资金写入，它的执行事务不注入 TradeCardMapper；本事务持有加钱能力，故对开拒绝
            throw new JbkException("取水异常核账不走卡内返还事务");
        }
        ActionType type = ActionType.getByValue(action.getActionType());
        if (type != ActionType.CARD_REFUND && type != ActionType.CARD_COMPENSATE) {
            // 机构退款(包B)/补送(包C)有各自的执行路径，落到这里只会把它们错当成卡内返还
            throw new JbkException("动作类型不属于卡内返还：" + type.getDesc());
        }

        // 额度出账即冻结：执行期只读登记时算定的四元额度，绝不按当前价目或当前快照重算
        AfterSaleStrategy.Refund refund = new AfterSaleStrategy.Refund(
                requireAmount(action.getRefundProductFen(), "水品返还金额"),
                requireAmount(action.getRefundServiceFen(), "配送费返还金额"),
                requireAmount(action.getRefundProductMl(), "水品返还水量"));
        if (refund.isZero()) {
            // 零额度会让 refundCardAssets 静默 0 行、与「卡前态漂移」混淆，先拒绝错因才准
            throw new JbkException("零额度动作不应进入卡内返还事务");
        }
        long totalFen = refund.totalFen();
        if (ObjectUtil.notEqual(action.getRefundAmount(), totalFen)) {
            throw new JbkException("返还金额合计(" + action.getRefundAmount()
                    + ")与分维额度之和(" + totalFen + ")不一致，拒绝返还");
        }

        // ── ② 订单与历史快照（快照是历史价格的唯一真相，缺失/不自洽即 fail-closed）──
        WsOrder order = orderMapper.selectById(action.getOrderId());
        if (ObjectUtil.isNull(order)) {
            throw new JbkException("售后关联订单不存在或已删除");
        }
        if (ObjectUtil.notEqual(order.getOrderType(), TradeEnum.OrderType.DELIVERY.getValue())) {
            throw new JbkException("配送售后关联的不是配送订单，拒绝返还");
        }
        if (ObjectUtil.notEqual(order.getUserId(), action.getUserId())
                || ObjectUtil.notEqual(order.getCardId(), action.getCardId())) {
            throw new JbkException("售后动作与订单的归属错位，拒绝返还");
        }
        DeliveryRefundSnapshot.Parsed snap = DeliveryRefundSnapshot.require(order);

        // ── ③ 锁卡（必须在额度聚合之前：卡锁把同一张卡的返还串行化，醒来后的聚合当前读才有意义）──
        WsCard card = tradeCardMapper.selectByIdForUpdate(action.getCardId());
        // ── ④ 锁内核验卡的存在性/归属/可入账状态（预检结论一概不作数）──
        if (ObjectUtil.isNull(card)) {
            throw new JbkException("返还目标水卡不存在，转人工处理");
        }
        if (ObjectUtil.notEqual(card.getDataStatus(), 0)) {
            throw new JbkException("返还目标水卡已被逻辑删除，转人工处理");
        }
        if (ObjectUtil.notEqual(card.getUserId(), action.getUserId())) {
            // 卡已换主：钱绝不打进现在这个人的卡里
            throw new JbkException("返还目标水卡已换主，拒绝返还");
        }
        int cardStatus = requireCardStatus(card);
        long oldAmount = requireAmount(card.getBalanceAmount(), "水卡余额前态");
        long oldMl = requireAmount(card.getBalanceMl(), "水卡水量前态");

        // ── ⑤ 已用额度（锁卡之后的锁定读；跨 DATA_STATUS 聚合，删行不得成为绕过封顶的后门）──
        WsAfterSaleAction usedRow = actionMapper.sumSuccessRefundByOrderForUpdate(
                action.getOrderId(), ActionStatus.SUCCESS.getValue());
        if (ObjectUtil.isNull(usedRow)) {
            throw new JbkException("同订单已用额度读取失败，拒绝返还");
        }
        AfterSaleQuota.Used used = new AfterSaleQuota.Used(
                requireAmount(usedRow.getRefundProductFen(), "已用水品金额额度"),
                requireAmount(usedRow.getRefundServiceFen(), "已用配送费额度"),
                requireAmount(usedRow.getRefundProductMl(), "已用水品水量额度"));

        // ── ⑥ 四元封顶：额度锚点取快照，并与原扣款流水逐维精确相等（不等即账实不符，拒绝）──
        WsWalletFlow deduct = DeliveryConsumeFlow.require(walletFlowMapper, order);
        AfterSaleQuota.Caps caps = AfterSaleQuota.caps(snap,
                DeliveryConsumeFlow.requireChange(deduct.getAmountChange(), "原扣款流水金额"),
                DeliveryConsumeFlow.requireChange(deduct.getMlChange(), "原扣款流水水量"));
        AfterSaleQuota.requireWithinCap(caps, used, refund);

        // ── ⑦ 原子返还：金额与水量一条 UPDATE，WHERE 带双列前态与归属，影响行必须 == 1 ──
        if (tradeCardMapper.refundCardAssets(card.getId(), totalFen, refund.productMl(),
                oldAmount, oldMl, card.getUserId(), opUserId, now) != 1) {
            // 锁内前态刚读到，此处 0 行只可能是同事务内数据已不自洽（含卡状态被并发改到注销）
            throw new JbkException("水卡权益返还影响行数异常：卡前态或状态漂移，账本断裂");
        }

        // ── ⑧ 唯一流水：一笔混合返还只插一条 ──
        // AFTER 用前态+delta 的预期值（铁律③），不 selectById 重读——重读值可能已被其他写入插队
        long amountAfter = Math.addExact(oldAmount, totalFen);
        long mlAfter = Math.addExact(oldMl, refund.productMl());
        WsWalletFlow flow = new WsWalletFlow()
                .setCardId(card.getId())
                .setUserId(action.getUserId())
                .setFlowType(refundFlowType(source))
                .setAmountChange(totalFen)
                .setMlChange(refund.productMl())
                .setAmountAfter(amountAfter)
                .setMlAfter(mlAfter)
                .setOrderId(action.getOrderId())
                .setFlowRemark(source.getDesc() + "返还 " + order.getOrderNo())
                .setBizIdempotencyKey(bizKey(action.getAfterSaleNo()));
        flow.setCreateBy(opUserId);
        flow.setCreateTime(now);
        flow.setUpdateTime(now);
        // 撞 uk_wallet_flow_biz_key 不捕获、整事务回滚（铁律②）：
        // 捕获后继续会让 markSuccess 给一笔没发生的返还盖成功章
        if (walletFlowMapper.insert(flow) != 1) {
            throw new JbkException("售后返还流水插入影响行数异常");
        }

        // ── ⑧bis 权益批次回补（E2E-04 包D-4，REQ-061）：卡加回多少批次就补回多少，
        // 回补目标由原消费分摊键定位（只加卡不回补，余额看得见花不掉）──
        entitlementLedger.restoreOnRefundBack(
                new EntitlementLedger.CardAfter(card.getId(), card.getUserId(), card.getExpireTime(),
                        amountAfter, mlAfter),
                EntitlementLedger.consumeKey(order), totalFen, refund.productMl(), opUserId, now);

        // ── ⑨ 标记成功：前态取状态机，四元额度在 WHERE 里二次钉死，影响行必须 == 1 ──
        int successStatus = ActionStatus.SUCCESS.getValue();
        if (actionMapper.markSuccess(id, action.getVersion(), successStatus,
                AfterSaleTransitions.requireSources(successStatus),
                refund.productFen(), refund.serviceFen(), refund.productMl(), totalFen,
                opUserId, now) != 1) {
            throw new JbkException("售后动作标记完成影响行数异常：状态或额度在执行期漂移");
        }

        // ── ⑩ 正向状态审计与业务同事务（D-215：REQUIRES_NEW 只留给拒绝/失败证据）──
        // payload 不含执行时刻：撞键时按语义比对，掺时间戳会让幂等重放核验必然失败并拖回滚主事务
        domainEventService.recordReliableOnce(OpsEnum.EventType.AFTER_SALE, action.getAfterSaleNo(),
                "AFTERSALE_DONE:" + action.getAfterSaleNo(),
                ActionStatus.PROCESSING.getDesc(),
                "售后返还完成：" + JSONUtil.createObj()
                        .set("afterSaleNo", action.getAfterSaleNo())
                        .set("sourceType", source.getValue())
                        .set("actionType", type.getValue())
                        .set("orderNo", order.getOrderNo())
                        .set("cardId", card.getId())
                        .set("cardStatus", cardStatus)
                        .set("refundProductFen", refund.productFen())
                        .set("refundServiceFen", refund.serviceFen())
                        .set("refundProductMl", refund.productMl())
                        .set("refundAmount", totalFen)
                        .set("amountAfter", amountAfter)
                        .set("mlAfter", mlAfter));
        // ── ⑪ 分润冲减登记（D-420 R2 段1，同事务动作级 outbox）：单行 INSERT 不可失败路径；
        // 分摊/校验在独立执行事务，冲减侧异常绝不回滚本次退款；基数取 markSuccess 刚钉死的实退额
        WsAfterSaleAction registered = new WsAfterSaleAction();
        registered.setId(action.getId());
        registered.setOrderId(action.getOrderId());
        registered.setActionType(action.getActionType());
        registered.setRefundProductFen(refund.productFen());
        splitClawbackTxService.registerForAction(registered, now);

                log.info("售后返还完成：afterSaleNo={} orderNo={} cardId={} 金额+{} 水量+{}",
                action.getAfterSaleNo(), order.getOrderNo(), card.getId(), totalFen, refund.productMl());
    }

    // ------------------------------------------------------------------
    // ④ 失败/终局落痕（独立提交）
    // ------------------------------------------------------------------

    /** 失败证据独立提交（铁律④后半句）：主事务回滚保「钱没动」，本事务保「为什么没动」。 */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markTerminalIndependent(Long id, Integer expectedVersion, int toStatus,
                                        String nextRetryTime, String lastError, Long opUserId, String now) {
        DeliveryClock.requireTime(now, "当前时间");
        requireId(id, "售后动作ID");
        requireOperator(opUserId);
        ActionStatus target = ActionStatus.getByValue(toStatus);
        if (target != ActionStatus.RETRY_WAIT && target != ActionStatus.RECONCILIATION_REQUIRED
                && target != ActionStatus.TERMINATED) {
            // 成功只能由资金事务写完账后盖章，放行即「不写账也能标成功」的旁路
            throw new JbkException("本方法只受理可重试/需人工对账/已终止三种落点");
        }
        boolean retryable = StrUtil.isNotBlank(nextRetryTime);
        if (retryable != (target == ActionStatus.RETRY_WAIT)) {
            // XML 用 nextRetryTime 是否为空驱动「计数递增 + 上限闸」分支，必须与目标态互为充要
            throw new JbkException("重试排期与目标状态不匹配：" + target.getDesc());
        }
        if (retryable) {
            DeliveryClock.requireTime(nextRetryTime, "下次可重试时间");
        }
        String safeError = truncateError(lastError);

        // 前态版本必须在本事务内重读：claim 已把 VERSION +1 而编排层手上是旧值，
        // 用旧版本做 CAS 必然 0 行，动作会永久停在 2执行中（claimForExecute 只认 1/4 捡不起来）
        WsAfterSaleAction claimed = actionMapper.selectByIdIncludingDeleted(id);
        Integer casVersion = ObjectUtil.isNull(claimed) ? expectedVersion : claimed.getVersion();

        int rows = actionMapper.markTerminal(id, casVersion, target.getValue(),
                AfterSaleTransitions.requireSources(target.getValue()),
                retryable ? nextRetryTime : null, retryable ? MAX_RETRY_COUNT : null,
                safeError, opUserId, now);
        if (rows == 1) {
            log.warn("售后动作落终局态：id={} status={} nextRetryTime={} error={}",
                    id, target.getDesc(), nextRetryTime, safeError);
            return;
        }

        // 影响 0 行：重读现状再分流，绝不沿用已随主事务回滚的对象（同 RechargeCreditFailureTxImpl）
        WsAfterSaleAction current = actionMapper.selectByIdIncludingDeleted(id);
        if (ObjectUtil.isNull(current)) {
            recordTerminalMiss(id, null, target, "售后动作行读不回，落痕失败：" + safeError);
            return;
        }
        ActionStatus actual = ActionStatus.getByValue(current.getActionStatus());
        if (actual == ActionStatus.SUCCESS) {
            // 主事务其实已提交（COMMIT 结果不确定场景）：成功是终态，绝不用失败态覆盖它
            log.warn("售后动作已完成，不用失败态覆盖：id={} afterSaleNo={} error={}",
                    id, current.getAfterSaleNo(), safeError);
            return;
        }
        if (actual == ActionStatus.PROCESSING && retryable
                && requireAmount(current.getRetryCount(), "重试次数") >= MAX_RETRY_COUNT) {
            // 重试耗尽：改判需人工对账，用重读到的 VERSION 而非入参版本
            int upgraded = actionMapper.markTerminal(id, current.getVersion(),
                    ActionStatus.RECONCILIATION_REQUIRED.getValue(),
                    AfterSaleTransitions.requireSources(ActionStatus.RECONCILIATION_REQUIRED.getValue()),
                    null, null,
                    truncateError("重试已达上限(" + MAX_RETRY_COUNT + ")：" + safeError), opUserId, now);
            if (upgraded == 1) {
                log.error("售后动作重试耗尽，转人工对账：id={} afterSaleNo={} error={}",
                        id, current.getAfterSaleNo(), safeError);
                return;
            }
        }
        recordTerminalMiss(id, current, target, safeError);
    }

    // ------------------------------------------------------------------
    // 私有辅助
    // ------------------------------------------------------------------


    /** 来源决定流水分类口径：取消是退款返还，申诉成立是补偿入账，财务侧据此分账。 */
    private int refundFlowType(SourceType source) {
        return switch (source) {
            case DELIVERY_CANCEL -> TradeEnum.FlowType.REFUND.getValue();
            case DELIVERY_APPEAL -> TradeEnum.FlowType.COMPENSATE.getValue();
            // 正向枚举而非 default：新增来源在此立刻暴露而非静默归类
            case WATER_ABNORMAL -> throw new JbkException("取水异常核账不产生资金流水");
            // 充值退款走机构退款路径写自己的负向流水，落到这里说明动作类型判定被绕过
            case RECHARGE_REFUND -> throw new JbkException("充值退款不走卡内返还流水，由权益批次冲正路径写入");
        };
    }

    /** 流水业务幂等键：同一售后号全库最多一条返还流水（uk_wallet_flow_biz_key 兜底）。 */
    private String bizKey(String afterSaleNo) {
        if (StrUtil.isBlank(afterSaleNo)) {
            throw new JbkException("售后号缺失，无法生成流水幂等键");
        }
        return "AFTERSALE:" + afterSaleNo;
    }

    /** 落痕落空的独立证据（REQUIRES_NEW）：状态没能推进这件事本身必须留痕，否则中间态无线索。 */
    private void recordTerminalMiss(Long id, WsAfterSaleAction current, ActionStatus target, String reason) {
        String eventKey = ObjectUtil.isNull(current) || StrUtil.isBlank(current.getAfterSaleNo())
                ? String.valueOf(id) : current.getAfterSaleNo();
        log.error("售后动作终局落痕影响 0 行：id={} target={} actual={} reason={}",
                id, target.getDesc(),
                ObjectUtil.isNull(current) ? null : current.getActionStatus(), reason);
        domainEventService.recordReliableOnceIndependent(OpsEnum.EventType.AFTER_SALE, eventKey,
                "AFTERSALE_MISS:" + eventKey, null,
                "售后动作终局落痕落空：" + JSONUtil.createObj()
                        .set("actionId", id)
                        .set("targetStatus", target.getValue())
                        .set("actualStatus", ObjectUtil.isNull(current) ? null : current.getActionStatus())
                        .set("reason", reason));
    }

    private String truncateError(String lastError) {
        return StrUtil.maxLength(StrUtil.blankToDefault(lastError, "售后返还执行失败"), LAST_ERROR_TRUNCATE);
    }

    private int requireCardStatus(WsCard card) {
        Integer status = card.getCardStatus();
        if (ObjectUtil.equals(status, UserEnum.CardStatus.CANCELLED.getValue())) {
            throw new JbkException("返还目标水卡已注销，拒绝返还");
        }
        // 白名单：refundCardAssets 只认 1/2/3，未登记状态在此拒绝，否则退化成「前态漂移」的假失败
        if (ObjectUtil.equals(status, UserEnum.CardStatus.NORMAL.getValue())
                || ObjectUtil.equals(status, UserEnum.CardStatus.FROZEN.getValue())
                || ObjectUtil.equals(status, UserEnum.CardStatus.EXPIRED.getValue())) {
            return status;
        }
        throw new JbkException("返还目标水卡状态不允许入账：" + status);
    }

    private void requireId(Long id, String label) {
        if (ObjectUtil.isNull(id) || id <= 0) {
            throw new JbkException(label + "非法");
        }
    }

    /** 操作人可为 0（无会话的 Worker 触发），但不允许缺失或为负——审计列不接受来路不明的值。 */
    private void requireOperator(Long opUserId) {
        if (ObjectUtil.isNull(opUserId) || opUserId < 0) {
            throw new JbkException("操作人ID非法");
        }
    }

    /** 额度/余额类字段一律非空非负；null 在这里拒绝，而不是拆箱时抛 NPE 或被当成 0 放行。 */
    private long requireAmount(Number value, String label) {
        if (ObjectUtil.isNull(value)) {
            throw new JbkException(label + "缺失");
        }
        long amount = value.longValue();
        if (amount < 0) {
            throw new JbkException(label + "不能为负：" + amount);
        }
        return amount;
    }

}
