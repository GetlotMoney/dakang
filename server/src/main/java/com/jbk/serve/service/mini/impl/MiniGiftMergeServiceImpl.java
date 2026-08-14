package com.jbk.serve.service.mini.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.aftersale.WsCardEntitlementBatchMapper;
import com.jbk.serve.mapper.trade.GiftMergeMapper;
import com.jbk.serve.mapper.trade.TradeCardMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.service.mini.IMiniGiftMergeService;
import com.jbk.serve.service.aftersale.batch.EntitlementLedger;
import com.jbk.serve.service.mini.card.CardEligibility;
import com.jbk.serve.service.mini.card.WaterCardScope;
import com.jbk.serve.service.mini.recharge.RechargeExpiry;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.serve.service.user.IWsCardService;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.data.aftersale.po.WsCardEntitlementBatch;
import com.jbk.tool.data.mini.vo.MiniCardMergeVo;
import com.jbk.tool.data.trade.po.WsWalletFlow;
import com.jbk.tool.data.user.po.WsCard;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 赠卡合并入正式水卡（D-415）。锁序固定：两张卡按 ID 升序 FOR UPDATE，再锁批次
 * （lockConsumableByCard 自带 ORDER BY 防死锁）；校验全部基于锁内读取，四条资金 SQL 全 CAS。
 * 目标必须是永久付费卡：卡 EXPIRE_TIME 是全部批次有效期的聚合上界（E2E-04 包D-5），
 * 往有限期存量卡合并到期更晚的批次会戳穿上界（D-213 后新付费卡恒永久，拒绝即可）。
 * 幂等锚 {@code MERGE-OUT:<赠卡ID>}（一张赠卡至多合并一次）：重放按该键组装既有结果，
 * 并发双击在唯一键上回滚。
 *
 * @author dakang
 * @since 2026-08-07
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MiniGiftMergeServiceImpl implements IMiniGiftMergeService {

    /** 合并转出/转入流水的幂等键前缀（键含赠卡ID，两条流水共享同一次合并的锚）。 */
    static final String MERGE_OUT_PREFIX = "MERGE-OUT:";
    static final String MERGE_IN_PREFIX = "MERGE-IN:";

    private static final int CARD_STATUS_NORMAL = 1;
    private static final int CARD_STATUS_EXPIRED = 3;
    private static final int CARD_STATUS_CANCELLED = 4;

    private final TradeCardMapper tradeCardMapper;
    private final GiftMergeMapper giftMergeMapper;
    private final WsCardEntitlementBatchMapper batchMapper;
    private final WsWalletFlowMapper walletFlowMapper;
    private final IWsCardService wsCardService;
    private final IWsDomainEventService domainEventService;
    private final EntitlementLedger entitlementLedger;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MiniCardMergeVo merge(Long giftCardId, Long userId) {
        if (giftCardId == null || userId == null) {
            throw new JbkException("参数不完整，无法合并");
        }
        // 锁外只取 ID，结论以锁内重验为准；名额口径（审计 P1-2）：注销(4)付费卡也在列——
        // 收不了权益但占「一人一卡」名额，赠卡既不能转正也没有可用主卡，只能走人工
        List<WsCard> paidCards = wsCardService.list(Wrappers.lambdaQuery(WsCard.class)
                .eq(WsCard::getUserId, userId)
                .ne(WsCard::getId, giftCardId)
                .apply(CardEligibility.SQL_NOT_GIFT));
        if (paidCards.isEmpty()) {
            throw new JbkException("暂无正式水卡可接收合并；赠卡权益用完或到期后可直接充值转为正式水卡");
        }
        if (paidCards.size() > 1) {
            // D-417 付费卡应仅一张；出现多张属数据异常，自动挑一张等于替用户做资金决定
            throw new JbkException("名下存在多张正式水卡，请联系客服处理后再合并");
        }
        Long mainCardId = paidCards.get(0).getId();

        // 固定锁序：按 ID 升序锁两张卡（跨 DATA_STATUS，删除态锁到后显式拒绝）
        String now = DateUtils.time();
        WsCard first = tradeCardMapper.selectByIdForUpdate(Math.min(giftCardId, mainCardId));
        WsCard second = tradeCardMapper.selectByIdForUpdate(Math.max(giftCardId, mainCardId));
        WsCard gift = giftCardId < mainCardId ? first : second;
        WsCard main = giftCardId < mainCardId ? second : first;

        // ---- 赠卡锁内核验 ----
        if (ObjectUtil.isNull(gift) || ObjectUtil.notEqual(gift.getDataStatus(), 0)
                || ObjectUtil.notEqual(gift.getUserId(), userId)) {
            throw new JbkException("水卡不存在或无权访问");
        }
        if (!CardEligibility.isGiftCard(gift)) {
            // 含实体带期卡（D-416「均限虚拟卡」）：不享受合并出路
            throw new JbkException("只有活动赠卡可以合并入正式水卡");
        }
        if (ObjectUtil.equals(gift.getCardStatus(), CARD_STATUS_CANCELLED)) {
            return replayResult(gift, userId);
        }
        if (ObjectUtil.notEqual(gift.getCardStatus(), CARD_STATUS_NORMAL)
                && ObjectUtil.notEqual(gift.getCardStatus(), CARD_STATUS_EXPIRED)) {
            throw new JbkException("赠卡当前状态不支持合并，请联系客服");
        }

        // ---- 主卡锁内核验（锁外定位结论此刻重验）----
        if (ObjectUtil.isNull(main) || ObjectUtil.notEqual(main.getDataStatus(), 0)
                || ObjectUtil.notEqual(main.getUserId(), userId)) {
            throw new JbkException("正式水卡状态已变化，请刷新后重试");
        }
        if (ObjectUtil.equals(main.getCardStatus(), CARD_STATUS_CANCELLED)) {
            // 审计 P1-2：注销付费卡占名额但收不了权益——不得当合并目标，也不得放行转正
            throw new JbkException("原正式水卡已注销仍占用名额，请联系客服处理后再操作赠卡");
        }
        if (ObjectUtil.notEqual(main.getCardStatus(), CARD_STATUS_NORMAL)) {
            throw new JbkException("正式水卡当前不可入账（冻结或已过期），暂无法合并");
        }
        if (StrUtil.isNotBlank(main.getExpireTime())) {
            // 有限期存量付费卡：接收合并会破坏「卡有效期=批次聚合上界」，拒绝（理由见类注释）
            throw new JbkException("正式水卡为历史有限期卡，暂不支持接收合并，请联系客服");
        }

        // ---- 批次锁定（审计 R2：全状态正剩余视野；按卡 ID 升序与卡锁同序防死锁）----
        List<WsCardEntitlementBatch> giftBatches;
        List<WsCardEntitlementBatch> mainBatches;
        if (gift.getId() < main.getId()) {
            giftBatches = batchMapper.lockAllPositiveRemainByCard(gift.getId());
            mainBatches = batchMapper.lockAllPositiveRemainByCard(main.getId());
        } else {
            mainBatches = batchMapper.lockAllPositiveRemainByCard(main.getId());
            giftBatches = batchMapper.lockAllPositiveRemainByCard(gift.getId());
        }
        // 非可消费态（退款锁定2等）的正余额批次：显式拒绝而不是让等式差额报「账本不一致」——
        // 迁移与注销只作用于 1/6 子集，放行会把锁定额度从卡上抹掉而批次仍在，当场断裂
        requireAllConsumableStatus(giftBatches, "赠卡");
        requireAllConsumableStatus(mainBatches, "正式水卡");
        long giftFen = nvl(gift.getBalanceAmount());
        long giftMl = nvl(gift.getBalanceMl());
        int movableBatches = requireLedgerIntact(giftBatches, giftFen, giftMl, "赠卡");
        // 主卡侧同一等式必须在任何写入前成立：账本已断裂再合并只会把断口撕大且无法回查
        requireLedgerIntact(mainBatches, nvl(main.getBalanceAmount()), nvl(main.getBalanceMl()), "正式水卡");

        // ---- 范围闸（审计 P1-5 + R2 P1-1，fail-closed）：受限权益并入更宽范围=越权使用。
        // 放行条件：两卡卡级范围语义精确相等，且每个正余额批次范围与所在卡精确相等；
        // 批次范围空/非法同样拒绝
        try {
            WaterCardScope giftScope = WaterCardScope.normalize(gift.getScopeJson(), "赠卡");
            WaterCardScope mainScope = WaterCardScope.normalize(main.getScopeJson(), "正式水卡");
            if (!giftScope.sameAuthorityAs(mainScope)) {
                throw new JbkException("赠卡与正式水卡的可用范围不一致，暂不支持合并，请联系客服");
            }
            requireBatchScopesMatchCard(giftBatches, giftScope, "赠卡");
            requireBatchScopesMatchCard(mainBatches, mainScope, "正式水卡");
        } catch (JbkException e) {
            throw e;
        } catch (Exception unparsable) {
            throw new JbkException("水卡可用范围配置异常，暂不支持合并，请联系客服");
        }

        boolean expired = RechargeExpiry.naturallyExpired(gift.getExpireTime(), now);
        MiniCardMergeVo vo = new MiniCardMergeVo();
        vo.setExpiredCleared(expired);

        if (expired) {
            // 过期作废走唯一清算入口 settleExpired（审计 P0-1）：批次置5清零+卡扣减+EXPIRE_CLEAR 流水；
            // 赠卡批次到期日恒等于卡到期日，清算后终值必须归零，非零即账本断裂
            EntitlementLedger.CardAfter settled = entitlementLedger.settleExpired(gift, userId, now);
            if (settled.amountAfter() != 0L || settled.mlAfter() != 0L) {
                throw new JbkException("过期赠卡清算后仍有余额，账本异常，请联系客服");
            }
            requireRows(giftMergeMapper.cancelGiftCardForMerge(gift.getId(), userId, now,
                    0L, 0L, gift.getExpireTime()), 1, "赠卡注销");
            // 零变动 MERGE-OUT：合并动作的幂等锚与注销留痕（作废金额已在 EXPIRE_CLEAR 流水）。
            // 审计 P2-1：过期清理与主卡无关，首次与重放一致地不下发 main 字段。
            insertFlow(gift.getId(), userId, TradeEnum.FlowType.ADJUST.getValue(),
                    0L, 0L, 0L, 0L, MERGE_OUT_PREFIX + gift.getId(),
                    "赠卡已过期，权益作废并注销（合并清理）", now);
            vo.setMovedFen(0L);
            vo.setMovedMl(0L);
        } else {
            // 有效期内整体转移：批次改挂主卡（携原到期），主卡原子加，赠卡清零注销
            requireRows(giftMergeMapper.migrateBatchesToCard(gift.getId(), main.getId(), userId, now),
                    movableBatches, "权益批次迁移");
            if (giftFen > 0 || giftMl > 0) {
                requireRows(giftMergeMapper.mergeCreditPaidCard(main.getId(), giftFen, giftMl,
                        userId, now, nvl(main.getBalanceAmount()), nvl(main.getBalanceMl()),
                        main.getExpireTime()), 1, "正式水卡入账");
            }
            requireRows(giftMergeMapper.cancelGiftCardForMerge(gift.getId(), userId, now,
                    giftFen, giftMl, gift.getExpireTime()), 1, "赠卡注销");
            long mainFenAfter = Math.addExact(nvl(main.getBalanceAmount()), giftFen);
            long mainMlAfter = Math.addExact(nvl(main.getBalanceMl()), giftMl);
            insertFlow(gift.getId(), userId, TradeEnum.FlowType.ADJUST.getValue(),
                    -giftFen, -giftMl, 0L, 0L, MERGE_OUT_PREFIX + gift.getId(),
                    "赠卡合并转出：并入水卡 " + main.getCardNo(), now);
            // 零转移合并同样写 MERGE-IN（审计 P2-1）：它是「合并入了哪张主卡」的唯一持久
            // 记录，重放靠它复原 mainCardId/mainCardNo——缺了它，首次与重放的响应必然不一致。
            insertFlow(main.getId(), userId, TradeEnum.FlowType.ADJUST.getValue(),
                    giftFen, giftMl, mainFenAfter, mainMlAfter, MERGE_IN_PREFIX + gift.getId(),
                    "赠卡合并转入：来自赠卡 " + gift.getCardNo() + "，权益 "
                            + readableDate(gift.getExpireTime()) + " 到期", now);
            vo.setMainCardId(main.getId());
            vo.setMainCardNo(main.getCardNo());
            vo.setMovedFen(giftFen);
            vo.setMovedMl(giftMl);
            vo.setBundleExpireTime(gift.getExpireTime());
            vo.setMainBalanceFen(mainFenAfter);
            vo.setMainBalanceMl(mainMlAfter);
        }

        // 关键审计：用户侧资金动作留可靠痕，与合并同事务同灭
        domainEventService.recordReliableOnceAs(OpsEnum.ActorPortal.USER, userId,
                OpsEnum.EventType.ORDER_STATUS, gift.getCardNo(), "GIFT_MERGE:" + gift.getId(),
                null, (expired ? "赠卡过期作废注销" : "赠卡合并入正式水卡 " + main.getCardNo())
                        + "：余额 " + giftFen + "分 水量 " + giftMl + "mL");
        log.info("赠卡合并完成：giftCardId={} mainCardId={} 转移 {}分/{}mL 过期作废={}",
                gift.getId(), main.getId(), giftFen, giftMl, expired);
        return vo;
    }

    /**
     * 重放分支：赠卡已注销时，凭 {@code MERGE-OUT:<赠卡ID>} 流水断定它是被合并注销的，
     * 组装既有结果幂等返回；查不到该流水说明是别的路径注销的，明确拒绝。
     */
    private MiniCardMergeVo replayResult(WsCard gift, Long userId) {
        WsWalletFlow out = walletFlowMapper.selectOne(Wrappers.lambdaQuery(WsWalletFlow.class)
                .eq(WsWalletFlow::getBizIdempotencyKey, MERGE_OUT_PREFIX + gift.getId()));
        if (ObjectUtil.isNull(out)) {
            throw new JbkException("该赠卡已注销，无法合并");
        }
        // 分支复原（审计 P2-1 一致性口径）：未过期合并恒写 MERGE-IN（含零转移），
        // 过期清理恒不写——「有无 IN」即两分支的持久判据；转移额恒等于 OUT 的负变动
        //（过期分支 OUT 是零变动流水，作废金额留在 EXPIRE_CLEAR 流水里）。
        WsWalletFlow in = walletFlowMapper.selectOne(Wrappers.lambdaQuery(WsWalletFlow.class)
                .eq(WsWalletFlow::getBizIdempotencyKey, MERGE_IN_PREFIX + gift.getId()));
        boolean expiredCleared = ObjectUtil.isNull(in);
        MiniCardMergeVo vo = new MiniCardMergeVo();
        vo.setExpiredCleared(expiredCleared);
        vo.setMovedFen(-nvl(out.getAmountChange()));
        vo.setMovedMl(-nvl(out.getMlChange()));
        if (ObjectUtil.isNotNull(in)) {
            vo.setMainCardId(in.getCardId());
            WsCard main = wsCardService.getById(in.getCardId());
            if (ObjectUtil.isNotNull(main)) {
                vo.setMainCardNo(main.getCardNo());
                vo.setMainBalanceFen(nvl(main.getBalanceAmount()));
                vo.setMainBalanceMl(nvl(main.getBalanceMl()));
            }
            vo.setBundleExpireTime(gift.getExpireTime());
        }
        return vo;
    }

    private void insertFlow(Long cardId, Long userId, int flowType, long amountChange, long mlChange,
                            long amountAfter, long mlAfter, String bizKey, String remark, String now) {
        WsWalletFlow flow = new WsWalletFlow()
                .setCardId(cardId)
                .setUserId(userId)
                .setFlowType(flowType)
                .setAmountChange(amountChange)
                .setMlChange(mlChange)
                .setAmountAfter(amountAfter)
                .setMlAfter(mlAfter)
                .setBizIdempotencyKey(bizKey)
                .setFlowRemark(StrUtil.brief(remark, 200));
        // 显式写 0 而不依赖 @TableLogic 的全局自动填充（EntitlementBatchWriter 同一先例）：
        // 逻辑删除值留 null 会让这条流水在幂等回查里隐身，重放分支据此误判为「别的路径注销」
        flow.setDataStatus(0);
        flow.setCreateBy(userId);
        flow.setCreateTime(now);
        flow.setUpdateBy(userId);
        flow.setUpdateTime(now);
        // 撞 BIZ_IDEMPOTENCY_KEY 唯一键=并发重复合并，整事务回滚，天然幂等（铁律②）
        if (walletFlowMapper.insert(flow) != 1) {
            throw new JbkException("合并流水写入失败");
        }
    }

    /**
     * 逐卡不变式自检（审计 P1-4 双卡口径）：锁内可消费批次剩余合计必须与卡余额/水量精确相等。
     * 存在退款锁定批次时它不在可消费集合里，合计必然对不上——正是该拒绝的形态。
     *
     * @return 剩余>0 的批次数（迁移影响行数的核对基准）
     */
    private int requireLedgerIntact(List<WsCardEntitlementBatch> batches, long cardFen, long cardMl,
                                    String label) {
        long batchFen = 0L;
        long batchMl = 0L;
        int movable = 0;
        for (WsCardEntitlementBatch b : batches) {
            long remainFen = nvl(b.getRemainAmountFen());
            long remainMl = nvl(b.getRemainWaterMl());
            batchFen = Math.addExact(batchFen, remainFen);
            batchMl = Math.addExact(batchMl, remainMl);
            if (remainFen > 0 || remainMl > 0) {
                movable++;
            }
        }
        if (batchFen != cardFen || batchMl != cardMl) {
            throw new JbkException(label + "权益账本不一致，暂无法合并，请联系客服");
        }
        return movable;
    }

    /** 全量正剩余批次必须都是可消费态(1/6)：退款锁定(2)等在途状态一律拒绝合并（审计 R2）。 */
    private void requireAllConsumableStatus(List<WsCardEntitlementBatch> batches, String label) {
        for (WsCardEntitlementBatch b : batches) {
            Integer status = b.getBatchStatus();
            if (!ObjectUtil.equals(status, 1) && !ObjectUtil.equals(status, 6)) {
                throw new JbkException(label + "有退款处理中的权益批次，暂无法合并，请稍后再试");
            }
        }
    }

    /**
     * 每个正余额批次的规范化范围必须与所在卡的卡级范围语义精确相等（审计 R2 P1-1）。
     * 批次范围为空或非法时 {@code normalize} 抛出（未配置=默认拒绝），由调用方统一转拒绝文案。
     */
    private void requireBatchScopesMatchCard(List<WsCardEntitlementBatch> batches,
                                             WaterCardScope cardScope, String label) {
        for (WsCardEntitlementBatch b : batches) {
            WaterCardScope batchScope = WaterCardScope.normalize(b.getScopeJson(), label + "权益批次");
            if (!batchScope.sameAuthorityAs(cardScope)) {
                throw new JbkException(label + "存在与卡范围不一致的权益批次，暂不支持合并，请联系客服");
            }
        }
    }

    private void requireRows(int affected, int expected, String step) {
        if (affected != expected) {
            throw new JbkException("合并失败（" + step + "前置状态已变化），请刷新后重试");
        }
    }

    private static long nvl(Long v) {
        return v == null ? 0L : v;
    }

    /** yyyyMMddHHmmss → yyyy-MM-dd（仅用于流水备注展示；格式异常时原样返回，不因展示阻断资金事务）。 */
    private static String readableDate(String bizTime) {
        if (bizTime == null || bizTime.length() != 14) {
            return String.valueOf(bizTime);
        }
        return bizTime.substring(0, 4) + "-" + bizTime.substring(4, 6) + "-" + bizTime.substring(6, 8);
    }
}
