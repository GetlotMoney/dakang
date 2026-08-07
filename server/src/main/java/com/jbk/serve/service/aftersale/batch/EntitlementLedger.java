package com.jbk.serve.service.aftersale.batch;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.jbk.serve.mapper.aftersale.WsCardEntitlementBatchMapper;
import com.jbk.serve.mapper.aftersale.WsEntitlementAllocationMapper;
import com.jbk.serve.mapper.trade.TradeCardMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.service.delivery.DeliveryConsumeFlow;
import com.jbk.serve.service.mini.recharge.RechargeExpiry;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.data.aftersale.po.WsCardEntitlementBatch;
import com.jbk.tool.data.aftersale.po.WsEntitlementAllocation;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsWalletFlow;
import com.jbk.tool.data.user.po.WsCard;
import com.jbk.tool.exception.JbkException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 权益批次消费分摊与返还回补——<b>单一出处</b>（E2E-04 包D-4，REQ-061）。
 *
 * <h3>本类维护的唯一不变式</h3>
 * <pre>
 *   逐卡：SUM(ws_card_entitlement_batch.REMAIN_*) == ws_card.BALANCE_*
 * </pre>
 * <p>这条等式是退款折算能成立的全部前提，也是 D-2 回填脚本的后置不变式。它一旦破：</p>
 * <ul>
 *   <li>批次合计<b>低于</b>卡：用户账面有余额却凑不出批次额度，下一次取水/配送在
 *       「卡扣成功、批次不足」处整笔回滚，表现为「有钱下不了单」；</li>
 *   <li>批次合计<b>高于</b>卡：某批次的「已消费量 = 发放量 − 剩余量」被低估，
 *       退款折算据此多退，直接是资金事故。</li>
 * </ul>
 * <p>因此凡改动 {@code ws_card.BALANCE_*} 的事务都必须在<b>同一事务内</b>经过本类：
 * 扣减走 {@link #allocateOnConsume}，加回走 {@link #restoreOnRefundBack}，
 * 充值发放走 {@link EntitlementBatchWriter}（新建批次），到期作废走
 * {@link #settleExpired}（审计 P0-1，2026-08-07）。四个入口之外没有第五条路。</p>
 *
 * <h3>为什么是无 @Transactional 的 Bean</h3>
 * <p>本类需要注入两个 Mapper，故不能像 {@link EntitlementBatchWriter} 那样做纯静态工具；
 * 但它<b>一个方法都不带 {@code @Transactional}</b>——事务边界必须由调用方（资金事务）持有。
 * 自带传播级别的那一刻，「卡与批次同生共死」就多了一个可以被写成 REQUIRES_NEW 的缺口，
 * 而那个缺口的表现是「卡扣了、批次没扣」，且两边各自提交成功、日志上毫无异常。</p>
 *
 * @author dakang
 * @since 2026-07-29
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EntitlementLedger {

    /** 取水消费的分摊幂等键前缀。取水流水没有业务幂等键，故分摊键由本类自持一份口径。 */
    private static final String DISPENSE_KEY_PREFIX = "DISPENSE:";

    private final WsCardEntitlementBatchMapper batchMapper;
    private final WsEntitlementAllocationMapper allocationMapper;
    private final TradeCardMapper tradeCardMapper;
    private final WsWalletFlowMapper walletFlowMapper;

    /** 批次到期作废流水的幂等键前缀：键含批次 ID，同一批次一生至多作废一次。 */
    public static final String EXPIRE_BATCH_KEY_PREFIX = "EXPIRE-BATCH:";

    /**
     * 消费分摊的幂等键：取水 {@code DISPENSE:<订单号>}，配送沿用 {@code DELIVERY:<订单号>}。
     *
     * <p>配送侧刻意<b>转调</b> {@link DeliveryConsumeFlow#bizKey}，不在这里重写一份前缀：
     * 那个前缀同时是配送扣款流水的幂等键，两处各写一份则任一处改动都会让
     * 「返还要回补哪次消费」按一个查不到的键去找，静默退化成「没有分摊可回补」。</p>
     *
     * <p>取水侧另起 {@code DISPENSE:} 前缀而非复用订单号裸值：{@code uk_alloc_biz_batch}
     * 是全表唯一键，同一个订单号若在两种业务里出现，裸值会让它们互相撞键。</p>
     */
    public static String consumeKey(WsOrder order) {
        if (ObjectUtil.isNull(order) || StrUtil.isBlank(order.getOrderNo())) {
            throw new JbkException("订单号缺失，无法生成消费分摊幂等键");
        }
        Integer type = order.getOrderType();
        if (ObjectUtil.equals(type, TradeEnum.OrderType.WATER.getValue())) {
            return DISPENSE_KEY_PREFIX + order.getOrderNo();
        }
        if (ObjectUtil.equals(type, TradeEnum.OrderType.DELIVERY.getValue())) {
            return DeliveryConsumeFlow.bizKey(order.getOrderNo());
        }
        // 购卡充值订单是权益的来源而不是消费，落到这里说明调用点接错了业务
        throw new JbkException("订单类型不产生权益消费分摊：" + type);
    }

    /**
     * 批次到期清算——全仓<b>唯一</b>的权益到期作废实现（审计 P0-1/P0-2 整改，2026-08-07）。
     *
     * <h3>为什么必须是主动清算而不是查询过滤</h3>
     * <p>批次选取（{@code lockConsumableByCard}）与 {@link EntitlementBatchOrder#requireConsumable}
     * 都只看状态不看时间；带期批次挂在永久卡上时（D-415 合并、存量有限期批次），卡级过期
     * 守卫不再兜底，过期权益会被继续扣减。只在查询侧过滤则卡聚合值与批次合计立即脱节
     * （卡上仍挂着过期余额），逐卡不变式当场断裂。唯一正确形态是：过期批次清零的同时
     * 卡聚合值同步扣减、流水留痕，三者同一事务。</p>
     *
     * <h3>完整账本等式（审计 R2 P0-2）</h3>
     * <p>批次视野是 {@code lockAllPositiveRemainByCard} 的<b>全状态</b>正剩余集合：有作废动作时，
     * 写入前验证「SUM(全部正剩余) == 卡聚合值」、写入后核对「卡终值 == 剩余批次合计」，
     * 任一不成立整体回滚转人工。可作废子集仍限状态 1/6 的到期批次；过期的退款锁定(2)批次
     * 保持原样（余额留在卡上等退款裁决，消费侧因分摊凑不满而天然拒绝动用）。</p>
     *
     * <h3>调用契约</h3>
     * <p><b>必须在持有该卡行锁的事务内调用</b>（{@code selectByIdForUpdate}/{@code lockCard} 之后），
     * 且必须发生在本事务对该卡的任何扣减/入账之前——返回的清算后终值就是后续 CAS 的前态。
     * 消费链（取水、配送）与赠卡转正入账共用本入口，任何一处都不得自行复制到期算法。</p>
     *
     * <p>每个被作废批次写一条 {@code EXPIRE_CLEAR} 流水，幂等键
     * {@code EXPIRE-BATCH:<批次ID>}（唯一键兜底：并发重复清算撞键整体回滚）；
     * AFTER 值逐批次递减，账本链条连续。无过期剩余批次时零动作返回原值。</p>
     *
     * @param lockedCard 锁内读取的卡行（前态与归属都取自它）
     * @param opUserId   操作人（流水与批次的 UPDATE_BY）
     * @param now        业务时间，与外层事务同一时钟
     * @return 清算后的卡终值（无清算时即入参原值）
     */
    public CardAfter settleExpired(WsCard lockedCard, Long opUserId, String now) {
        if (ObjectUtil.isNull(lockedCard) || lockedCard.getId() == null) {
            throw new JbkException("到期清算目标卡缺失");
        }
        requireOperator(opUserId);
        requireTime(now);
        Long cardId = lockedCard.getId();
        Long ownerUserId = lockedCard.getUserId();
        long fenNow = requireNonNegative(lockedCard.getBalanceAmount(), "卡余额前态");
        long mlNow = requireNonNegative(lockedCard.getBalanceMl(), "卡水量前态");

        // 单次全量锁定（审计 R2 P0-2）：该卡全部未删除、正剩余批次——不限状态。
        // 只锁 1/6 可消费子集就做清算，状态 2（退款锁定）等非消费态的正余额完全不可见，
        // 「卡余额 > 批次合计」的断裂账本也会被部分扣成功掩盖。
        List<WsCardEntitlementBatch> allPositive =
                new ArrayList<>(batchMapper.lockAllPositiveRemainByCard(cardId));
        long allFen = 0L;
        long allMl = 0L;
        List<WsCardEntitlementBatch> expired = new ArrayList<>();
        long totalFen = 0L;
        long totalMl = 0L;
        for (WsCardEntitlementBatch batch : allPositive) {
            long remainFen = requireNonNegative(batch.getRemainAmountFen(), "批次剩余金额");
            long remainMl = requireNonNegative(batch.getRemainWaterMl(), "批次剩余水量");
            allFen = Math.addExact(allFen, remainFen);
            allMl = Math.addExact(allMl, remainMl);
            String expire = batch.getExpireTime();
            Integer status = batch.getBatchStatus();
            boolean settleable = ObjectUtil.equals(status, EntitlementBatchOrder.BatchStatus.AVAILABLE)
                    || ObjectUtil.equals(status, EntitlementBatchOrder.BatchStatus.NON_REFUNDABLE);
            if (settleable && StrUtil.isNotBlank(expire) && RechargeExpiry.naturallyExpired(expire, now)) {
                // 只有可消费态(1/6)的到期批次允许作废；过期的退款锁定(2)批次保持原样——
                // 它的余额留在卡上等退款裁决，消费侧因分摊凑不满而天然拒绝动用
                expired.add(batch);
                totalFen = Math.addExact(totalFen, remainFen);
                totalMl = Math.addExact(totalMl, remainMl);
            }
        }
        if (expired.isEmpty()) {
            return new CardAfter(cardId, ownerUserId, lockedCard.getExpireTime(), fenNow, mlNow);
        }
        // 写入前的完整账本等式（审计 R2 P0-2）：SUM(全部正剩余) 必须与卡聚合值精确相等。
        // 卡余额高于批次合计（无批次支撑的余额）与低于合计（批次虚高）都在任何写入前 fail-closed。
        if (allFen != fenNow || allMl != mlNow) {
            throw new JbkException("到期清算前账本等式不成立：卡 " + cardId + " 余额 " + fenNow + "/" + mlNow
                    + " 批次合计 " + allFen + "/" + allMl + "，账本断裂转人工");
        }
        expired.sort(EntitlementBatchOrder.consumeOrder());

        // 先减卡（reverseCardAssets：双列前态 + 余量>= + 状态白名单 1,2,3），再逐批次作废。
        // 卡侧 0 行=前态漂移或余额不够扣（批次合计高于卡，账本本就断裂），整体回滚转人工。
        if (tradeCardMapper.reverseCardAssets(cardId, totalFen, totalMl, fenNow, mlNow,
                ownerUserId, opUserId, now) != 1) {
            throw new JbkException("到期清算卡扣减影响行数异常：卡 " + cardId + " 前态漂移或账本断裂");
        }
        long fenAfter = fenNow;
        long mlAfter = mlNow;
        for (WsCardEntitlementBatch batch : expired) {
            long remainFen = batch.getRemainAmountFen();
            long remainMl = batch.getRemainWaterMl();
            if (batchMapper.expireBatch(batch.getId(), batch.getVersion(), opUserId, now) != 1) {
                throw new JbkException("批次到期作废影响行数异常：批次 " + batch.getId() + " 前态漂移，账本断裂");
            }
            fenAfter = Math.subtractExact(fenAfter, remainFen);
            mlAfter = Math.subtractExact(mlAfter, remainMl);
            WsWalletFlow flow = new WsWalletFlow()
                    .setCardId(cardId)
                    .setUserId(ownerUserId)
                    .setFlowType(TradeEnum.FlowType.EXPIRE_CLEAR.getValue())
                    .setAmountChange(-remainFen)
                    .setMlChange(-remainMl)
                    .setAmountAfter(fenAfter)
                    .setMlAfter(mlAfter)
                    .setBizIdempotencyKey(EXPIRE_BATCH_KEY_PREFIX + batch.getId())
                    .setFlowRemark(StrUtil.brief("权益批次到期作废：批次 " + batch.getId()
                            + " 有效期 " + batch.getExpireTime(), 200));
            // 显式写 0：脱离全局 @TableLogic 填充配置时 NULL 会让流水在幂等回查里隐身
            flow.setDataStatus(0);
            flow.setCreateBy(opUserId);
            flow.setCreateTime(now);
            flow.setUpdateBy(opUserId);
            flow.setUpdateTime(now);
            if (walletFlowMapper.insert(flow) != 1) {
                throw new JbkException("到期作废流水写入失败");
            }
        }
        // 写入后的终值核对（审计 R2 P0-2 要求 4）：卡终值必须等于剩余批次合计
        // （= 全量合计 − 本次作废合计；含仍保留的退款锁定批次）。防御性再核一遍而不是信任推导。
        long remainFenTotal = Math.subtractExact(allFen, totalFen);
        long remainMlTotal = Math.subtractExact(allMl, totalMl);
        if (fenAfter != remainFenTotal || mlAfter != remainMlTotal) {
            throw new JbkException("到期清算后账本等式不成立：卡 " + cardId + " 终值 " + fenAfter + "/" + mlAfter
                    + " 剩余批次合计 " + remainFenTotal + "/" + remainMlTotal + "，整体回滚");
        }
        log.info("批次到期清算：cardId={} 作废批次 {} 个，金额 -{} 水量 -{}，终值 {}/{}",
                cardId, expired.size(), totalFen, totalMl, fenAfter, mlAfter);
        return new CardAfter(cardId, ownerUserId, lockedCard.getExpireTime(), fenAfter, mlAfter);
    }

    /**
     * 消费分摊：把本次扣减按批次选取次序摊到该卡的可消费批次上。
     *
     * <p><b>必须在扣减卡余额、写入消费流水的同一事务内调用</b>，且必须在卡侧 CAS 成功之后——
     * 卡侧成功是「这笔消费确实发生了」的判据，批次侧只负责把它落到具体批次上。</p>
     *
     * <p>算法：{@code lockConsumableByCard} 取回按次序排好且已加行锁的批次，
     * 逐个贪心填满金额与水量两个维度（同一批次同时供两维时只产生一条分摊行，
     * {@code uk_alloc_biz_batch} 按 (BIZ_KEY, BATCH_ID) 唯一，本来也只允许一条）。
     * 两维<b>各自独立</b>推进：金额可能全部来自 A 批次而水量全部来自 B 批次。</p>
     *
     * <p>凑不满即抛：这说明批次剩余合计低于卡聚合值，账本已断裂。此时唯一正确的结果是
     * 整笔消费回滚——继续放行等于承认「卡上有一部分权益不属于任何批次」，
     * 而那部分权益在退款折算里会被算成某个批次的剩余而多退。</p>
     *
     * @return 实际被分摊到的批次数（0 表示本次消费两维额度均为 0，未产生分摊）
     */
    public int allocateOnConsume(ConsumeRef ref, long amountFen, long waterMl, Long opUserId, String now) {
        requireRef(ref);
        requireOperator(opUserId);
        requireTime(now);
        requireNonNegative(amountFen, "分摊金额");
        requireNonNegative(waterMl, "分摊水量");
        if (amountFen == 0L && waterMl == 0L) {
            // 零额度消费（例如包C 的零金额补送子订单）不产生分摊，也不该产生空分摊行
            return 0;
        }

        List<WsCardEntitlementBatch> batches = new ArrayList<>(batchMapper.lockConsumableByCard(ref.cardId()));
        // SQL 的 ORDER BY 决定<b>加锁顺序</b>（防死锁），比较器决定<b>扣减顺序</b>（决定退多少钱）。
        // 两者本该一致，但「本该一致」不是保证：这里显式按比较器重排，让 EntitlementBatchOrder
        // 成为扣减次序的唯一出处——SQL 侧哪天被改宽或改错，扣减结果也不会跟着漂。
        batches.sort(EntitlementBatchOrder.consumeOrder());
        long needFen = amountFen;
        long needMl = waterMl;
        int seq = 0;
        for (WsCardEntitlementBatch batch : batches) {
            if (needFen == 0L && needMl == 0L) {
                break;
            }
            // SQL 的 BATCH_STATUS IN (1, 6) 已经筛过一遍；这里再断言一次是为了让
            // 「SQL 被改宽」立刻在内存侧暴露，而不是等到某笔退款锁定的批次被扣空之后
            EntitlementBatchOrder.requireConsumable(batch);
            requireSameCard(batch, ref.cardId());
            long takeFen = Math.min(needFen, requireNonNegative(batch.getRemainAmountFen(), "批次剩余金额"));
            long takeMl = Math.min(needMl, requireNonNegative(batch.getRemainWaterMl(), "批次剩余水量"));
            if (takeFen == 0L && takeMl == 0L) {
                continue;
            }
            if (batchMapper.consume(batch.getId(), batch.getVersion(), takeFen, takeMl, opUserId, now) != 1) {
                // 行锁在手，前态是锁内读到的：这里 0 行只可能是同事务内数据已不自洽
                throw new JbkException("权益批次扣减影响行数异常：批次 " + batch.getId() + " 前态漂移，账本断裂");
            }
            insertAllocation(ref, batch.getId(), ++seq, takeFen, takeMl, opUserId, now);
            needFen = Math.subtractExact(needFen, takeFen);
            needMl = Math.subtractExact(needMl, takeMl);
        }
        if (needFen > 0L || needMl > 0L) {
            throw new JbkException("水卡权益批次剩余不足，无法完成消费分摊（缺口 金额 " + needFen
                    + " 分 / 水量 " + needMl + " 毫升），账本断裂");
        }
        return seq;
    }

    /**
     * 返还回补：把一笔返还的额度还回它当初扣走的那些批次。
     *
     * <p><b>必须在给卡加回权益的同一事务内调用</b>。回补量恒等于本次给卡加回的量：
     * 卡加多少、批次就补多少，不变式才不会漂。</p>
     *
     * <p>回补按分摊行的<b>逆序</b>（后扣先还，理由见
     * {@link WsEntitlementAllocationMapper#selectByBizKeyForRestore}），每行最多还回它当初扣走的额度
     * ——上限取分摊行而不是批次发放量，这样一个批次永远不可能因为返还而涨出它没丢过的权益。</p>
     *
     * <p><b>残量与历史消费</b>：D-4 上线前的消费没有分摊行，对它的返还找不到可回补的批次。
     * 若就此不管，这张卡的聚合值会永久高于批次合计，那部分余额从此扣不动。故用
     * 「卡新聚合值 − 批次剩余合计」算出真实缺口，把残量并入该卡的历史聚合批次（不可退桶）。
     * 缺口 ≤ 0 时一分不补——上线前那笔消费没扣过批次，批次侧本来就没少，
     * 再补一次就是凭空造权益。这个口径让本类对存量数据自愈，且方向永远偏向「不可退」。</p>
     */
    public void restoreOnRefundBack(CardAfter card, String consumeBizKey, long amountFen, long waterMl,
                                    Long opUserId, String now) {
        requireCardAfter(card);
        requireOperator(opUserId);
        requireTime(now);
        requireNonNegative(amountFen, "回补金额");
        requireNonNegative(waterMl, "回补水量");
        if (StrUtil.isBlank(consumeBizKey)) {
            throw new JbkException("原消费分摊键缺失，无法确定回补目标批次");
        }
        if (amountFen == 0L && waterMl == 0L) {
            return;
        }

        long needFen = amountFen;
        long needMl = waterMl;
        for (WsEntitlementAllocation alloc : allocationMapper.selectByBizKeyForRestore(consumeBizKey)) {
            if (needFen == 0L && needMl == 0L) {
                break;
            }
            if (ObjectUtil.equals(alloc.getReversedFlag(), 1)) {
                // 该分摊已被退款冲正（reverseByBatch）：那批权益连本带利退回用户支付账户了。
                // 再把它补回卡上，就成了同一笔权益既退了钱又留着权益。
                // 本次返还的额度不会因此丢失——它会落到下面的残量逻辑里，
                // 并入该卡的不可退桶（方向偏保守，绝不制造可套现额度）。
                continue;
            }
            long allocFen = requireNonNegative(alloc.getAllocAmountFen(), "分摊金额前态");
            long allocMl = requireNonNegative(alloc.getAllocWaterMl(), "分摊水量前态");
            long backFen = Math.min(needFen, allocFen);
            long backMl = Math.min(needMl, allocMl);
            if (backFen == 0L && backMl == 0L) {
                continue;
            }
            WsCardEntitlementBatch batch = batchMapper.lockById(alloc.getBatchId());
            if (ObjectUtil.isNull(batch)) {
                throw new JbkException("分摊记录指向的权益批次不存在，账本断裂：batchId=" + alloc.getBatchId());
            }
            // 分摊行指向别人的卡：要么键被复用，要么数据被改写。回补下去就是把钱补进别人的批次
            requireSameCard(batch, card.cardId());
            if (batchMapper.restoreAllocated(batch.getId(), batch.getVersion(), backFen, backMl,
                    opUserId, now) != 1) {
                throw new JbkException("权益批次回补影响行数异常：批次 " + batch.getId()
                        + " 状态或剩余已变（退款锁定/已退款/超出发放量），转人工处理");
            }
            // 分摊行的额度前态进 CAS：这是返还幂等的锚点，重放时前态已变 → 0 行 → 整体回滚
            if (allocationMapper.reduceAllocated(alloc.getId(), allocFen, allocMl, backFen, backMl,
                    opUserId, now) != 1) {
                throw new JbkException("权益分摊冲减影响行数异常：分摊 " + alloc.getId() + " 已被并发冲减");
            }
            needFen = Math.subtractExact(needFen, backFen);
            needMl = Math.subtractExact(needMl, backMl);
        }
        if (needFen == 0L && needMl == 0L) {
            return;
        }

        // 残量落地前先看真实缺口：同事务内已完成的回补对本次查询可见，故这里读到的就是净缺口
        long gapFen = Math.subtractExact(card.amountAfter(), batchMapper.sumRemainFenByCard(card.cardId()));
        long gapMl = Math.subtractExact(card.mlAfter(), batchMapper.sumRemainMlByCard(card.cardId()));
        long growFen = Math.max(0L, Math.min(needFen, gapFen));
        long growMl = Math.max(0L, Math.min(needMl, gapMl));
        if (growFen == 0L && growMl == 0L) {
            return;
        }
        growLegacyBucket(card, growFen, growMl, opUserId, now);
    }

    /**
     * 把无归属残量并入该卡的历史聚合批次；该卡没有这样的批次时新建一条。
     *
     * <p>新建走 {@code SOURCE_TYPE=3 / BATCH_STATUS=6 / PAY_AMOUNT_FEN=0 / ORDER_ID=NULL} 的固定形态，
     * 与 D-2 回填脚本逐列一致——它必须永远退不了款：这部分权益对应哪一笔付款、付了多少，
     * 账本里没有答案，猜一个填进去就是凭空造出可退款基准。</p>
     *
     * <p>有效期取<b>卡的聚合有效期</b>而不是 NULL：NULL 表示永久，会让这桶权益在消费次序里
     * 排到所有有限期批次之后，并且在将来批次级过期生效时活得比卡本身还长。</p>
     */
    private void growLegacyBucket(CardAfter card, long growFen, long growMl, Long opUserId, String now) {
        WsCardEntitlementBatch legacy = batchMapper.lockLegacyByCard(card.cardId());
        if (ObjectUtil.isNotNull(legacy)) {
            if (batchMapper.growLegacy(legacy.getId(), legacy.getVersion(), growFen, growMl, opUserId, now) != 1) {
                throw new JbkException("历史聚合批次扩容影响行数异常：批次 " + legacy.getId() + " 形态不符或已被并发改动");
            }
            log.info("返还残量并入历史聚合批次：cardId={} batchId={} 金额+{} 水量+{}",
                    card.cardId(), legacy.getId(), growFen, growMl);
            return;
        }
        WsCardEntitlementBatch created = new WsCardEntitlementBatch()
                .setCardId(card.cardId())
                .setUserId(card.ownerUserId())
                .setSourceType(EntitlementBatchOrder.SourceType.LEGACY)
                .setPayAmountFen(0L)
                .setGrantAmountFen(growFen)
                .setGrantBonusFen(0L)
                .setGrantWaterMl(growMl)
                .setRemainAmountFen(growFen)
                .setRemainWaterMl(growMl)
                .setExpireTime(card.expireTime())
                .setBatchStatus(EntitlementBatchOrder.BatchStatus.NON_REFUNDABLE)
                .setRefundedAmountFen(0L)
                .setVersion(1);
        created.setDataStatus(0);
        created.setCreateBy(opUserId);
        created.setCreateTime(now);
        created.setUpdateBy(opUserId);
        created.setUpdateTime(now);
        if (batchMapper.insert(created) != 1) {
            throw new JbkException("历史聚合批次创建失败");
        }
        log.info("返还残量新建历史聚合批次：cardId={} batchId={} 金额={} 水量={}",
                card.cardId(), created.getId(), growFen, growMl);
    }

    private void insertAllocation(ConsumeRef ref, Long batchId, int seq, long allocFen, long allocMl,
                                  Long opUserId, String now) {
        WsEntitlementAllocation alloc = new WsEntitlementAllocation()
                .setBatchId(batchId)
                .setCardId(ref.cardId())
                .setFlowId(ref.flowId())
                .setOrderId(ref.orderId())
                .setBizKey(ref.bizKey())
                .setAllocSeq(seq)
                .setAllocAmountFen(allocFen)
                .setAllocWaterMl(allocMl)
                .setReversedFlag(0);
        alloc.setDataStatus(0);
        alloc.setCreateBy(opUserId);
        alloc.setCreateTime(now);
        alloc.setUpdateBy(opUserId);
        alloc.setUpdateTime(now);
        // 撞 uk_alloc_biz_batch 即同一次消费重放：不捕获，整事务回滚才是正确结果（铁律②）。
        // 捕获后「按已分摊继续」会让卡被扣两次而批次只扣一次
        if (allocationMapper.insert(alloc) != 1) {
            throw new JbkException("权益分摊记录插入影响行数异常");
        }
    }

    private void requireRef(ConsumeRef ref) {
        if (ObjectUtil.isNull(ref)) {
            throw new JbkException("消费分摊上下文缺失");
        }
        requireId(ref.cardId(), "水卡ID");
        requireId(ref.orderId(), "订单ID");
        if (StrUtil.isBlank(ref.bizKey())) {
            throw new JbkException("消费分摊幂等键缺失");
        }
    }

    private void requireCardAfter(CardAfter card) {
        if (ObjectUtil.isNull(card)) {
            throw new JbkException("回补目标卡上下文缺失");
        }
        requireId(card.cardId(), "水卡ID");
        requireId(card.ownerUserId(), "卡主用户ID");
        requireNonNegative(card.amountAfter(), "卡余额终值");
        requireNonNegative(card.mlAfter(), "卡水量终值");
    }

    /** 批次/分摊必须与目标卡同属一张卡：跨卡操作一律 fail-closed，绝不「就近取一个」。 */
    private void requireSameCard(WsCardEntitlementBatch batch, Long cardId) {
        if (ObjectUtil.notEqual(batch.getCardId(), cardId)) {
            throw new JbkException("权益批次归属错位：批次 " + batch.getId() + " 不属于卡 " + cardId);
        }
    }

    private void requireId(Long id, String label) {
        if (ObjectUtil.isNull(id) || id <= 0) {
            throw new JbkException(label + "非法");
        }
    }

    private void requireOperator(Long opUserId) {
        if (ObjectUtil.isNull(opUserId) || opUserId < 0) {
            throw new JbkException("操作人ID非法");
        }
    }

    private void requireTime(String now) {
        if (StrUtil.isBlank(now) || now.length() != 14) {
            throw new JbkException("业务时间格式非法，必须为 yyyyMMddHHmmss");
        }
    }

    private long requireNonNegative(Number value, String label) {
        if (ObjectUtil.isNull(value)) {
            throw new JbkException(label + "缺失");
        }
        long amount = value.longValue();
        if (amount < 0) {
            throw new JbkException(label + "不能为负：" + amount);
        }
        return amount;
    }

    /**
     * 一次消费的分摊上下文。
     *
     * @param cardId      被扣减的卡
     * @param ownerUserId 卡主（分摊行的审计归属）
     * @param orderId     触发消费的订单
     * @param flowId      同事务写入的钱包流水ID；可为空（流水尚未落库时不勉强关联）
     * @param bizKey      分摊幂等键，只能由 {@link #consumeKey} 产出
     */
    public record ConsumeRef(Long cardId, Long ownerUserId, Long orderId, Long flowId, String bizKey) {
    }

    /**
     * 回补时卡的终态。
     *
     * @param cardId      卡ID
     * @param ownerUserId 卡主
     * @param expireTime  卡的聚合有效期；null=永久
     * @param amountAfter 本次加回之后的卡余额（分）
     * @param mlAfter     本次加回之后的卡水量（毫升）
     */
    public record CardAfter(Long cardId, Long ownerUserId, String expireTime, long amountAfter, long mlAfter) {
    }
}
