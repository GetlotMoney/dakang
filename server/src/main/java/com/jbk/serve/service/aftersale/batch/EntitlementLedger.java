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
 * <p>唯一不变式：逐卡 SUM(ws_card_entitlement_batch.REMAIN_*) == ws_card.BALANCE_*，
 * 它是退款折算与 D-2 回填脚本的前提。凡改动 {@code ws_card.BALANCE_*} 的事务必须在
 * <b>同一事务内</b>经过本类：扣减 {@link #allocateOnConsume}、加回 {@link #restoreOnRefundBack}、
 * 充值发放 {@link EntitlementBatchWriter}、到期作废 {@link #settleExpired}（审计 P0-1），别无第五条路。</p>
 *
 * <p>本类不带 {@code @Transactional}：事务边界必须由调用方（资金事务）持有；
 * 自带传播级别（尤其 REQUIRES_NEW）会造成「卡扣了、批次没扣」且两边各自提交成功。</p>
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
     * <p>配送侧必须转调 {@link DeliveryConsumeFlow#bizKey}（两处各写一份前缀，漂移后返还会按查不到的键
     * 静默退化成「没有分摊可回补」）；取水侧另起 {@code DISPENSE:} 前缀防止与其他业务在全表唯一键
     * {@code uk_alloc_biz_batch} 上撞键。</p>
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
     * 批次到期清算——全仓<b>唯一</b>的权益到期作废实现（审计 P0-1/P0-2）。
     *
     * <p>必须主动清算而非查询侧过滤：批次选取只看状态不看时间（D-415 后卡级过期守卫不再兜底），
     * 只过滤查询会让卡聚合值与批次合计立即脱节；过期批次清零、卡聚合扣减、流水留痕必须同一事务。
     * 有作废动作时写入前验证「SUM(全部正剩余) == 卡聚合值」、写入后核对「卡终值 == 剩余批次合计」，
     * 任一不成立整体回滚转人工（审计 R2 P0-2）。</p>
     *
     * <p><b>必须在持有该卡行锁的事务内、对该卡任何扣减/入账之前调用</b>——返回的终值就是后续 CAS 的前态；
     * 消费链与赠卡转正入账共用本入口，不得复制到期算法。每个被作废批次写一条 {@code EXPIRE_CLEAR} 流水，
     * 幂等键 {@code EXPIRE-BATCH:<批次ID>}，并发重复清算撞键整体回滚；无过期剩余批次时零动作返回原值。</p>
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

        // 单次全量锁定（审计 R2 P0-2）：全状态正剩余批次。只锁 1/6 子集会让退款锁定态的
        // 正余额不可见，断裂账本被部分扣成功掩盖。
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
                // 只有可消费态(1/6)的到期批次允许作废；退款锁定(2)批次保持原样等退款裁决
                expired.add(batch);
                totalFen = Math.addExact(totalFen, remainFen);
                totalMl = Math.addExact(totalMl, remainMl);
            }
        }
        if (expired.isEmpty()) {
            return new CardAfter(cardId, ownerUserId, lockedCard.getExpireTime(), fenNow, mlNow);
        }
        // 写入前账本等式（审计 R2 P0-2）：SUM(全部正剩余) 必须与卡聚合值精确相等，不等即 fail-closed
        if (allFen != fenNow || allMl != mlNow) {
            throw new JbkException("到期清算前账本等式不成立：卡 " + cardId + " 余额 " + fenNow + "/" + mlNow
                    + " 批次合计 " + allFen + "/" + allMl + "，账本断裂转人工");
        }
        expired.sort(EntitlementBatchOrder.consumeOrder());

        // 先减卡（reverseCardAssets：双列前态 + 余量>= + 状态白名单 1,2,3），再逐批次作废；
        // 卡侧 0 行=前态漂移或账本断裂，整体回滚转人工
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
        // 写入后终值核对（审计 R2 P0-2）：卡终值必须等于剩余批次合计（含仍保留的退款锁定批次）
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
     * <p><b>必须在扣减卡余额、写入消费流水的同一事务内、卡侧 CAS 成功之后调用</b>。
     * 贪心填满金额与水量两维，两维各自独立推进（同一批次同时供两维时只产生一条分摊行）。
     * 凑不满即抛、整笔消费回滚：放行等于承认卡上有权益不属于任何批次，退款折算会据此多退。</p>
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
            // 零额度消费（如包C 的零金额补送子订单）不产生空分摊行
            return 0;
        }

        List<WsCardEntitlementBatch> batches = new ArrayList<>(batchMapper.lockConsumableByCard(ref.cardId()));
        // SQL 的 ORDER BY 只决定加锁顺序（防死锁）；扣减次序以 EntitlementBatchOrder 为唯一出处，
        // 显式重排后 SQL 被改宽也不会让扣减结果漂移
        batches.sort(EntitlementBatchOrder.consumeOrder());
        long needFen = amountFen;
        long needMl = waterMl;
        int seq = 0;
        for (WsCardEntitlementBatch batch : batches) {
            if (needFen == 0L && needMl == 0L) {
                break;
            }
            // SQL 已按 BATCH_STATUS IN (1, 6) 筛过；再断言一次让「SQL 被改宽」立刻在内存侧暴露
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
     * <p><b>必须在给卡加回权益的同一事务内调用</b>，回补量恒等于本次给卡加回的量。
     * 回补按分摊行逆序（后扣先还，见 {@link WsEntitlementAllocationMapper#selectByBizKeyForRestore}），
     * 每行上限取分摊行而非批次发放量——批次不可能因返还涨出它没丢过的权益。</p>
     *
     * <p>残量（D-4 上线前的消费无分摊行）按「卡新聚合值 − 批次剩余合计」算真实缺口并入
     * 历史聚合批次（不可退桶）；缺口 ≤ 0 时一分不补，否则是凭空造权益。方向永远偏向「不可退」。</p>
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
                // 已被退款冲正的分摊不回补（否则同一笔权益既退了钱又留着权益）；
                // 额度不丢失，落到下面的残量逻辑并入不可退桶
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
     * 把无归属残量并入该卡的历史聚合批次；没有则按 D-2 回填脚本的固定形态新建
     * （SOURCE_TYPE=3 / BATCH_STATUS=6 / PAY_AMOUNT_FEN=0 / ORDER_ID=NULL，必须永远退不了款）。
     * 有效期取卡的聚合有效期而非 NULL——NULL=永久，会让这桶权益活得比卡本身还长。
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
        // 撞 uk_alloc_biz_batch 即同一次消费重放：不捕获、整事务回滚（铁律②）；
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
