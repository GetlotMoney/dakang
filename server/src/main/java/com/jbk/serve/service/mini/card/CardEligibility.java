package com.jbk.serve.service.mini.card;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.jbk.tool.data.user.po.WsCard;

/**
 * 卡业务形态判定的单一出处（审计 P1-2）：赠卡两条出路均限虚拟卡（D-416），
 * 冻结/过期/注销但未删除的付费卡都占一人一卡名额（D-417），不许再复制判据。
 *
 * <p>SQL 侧 {@code RechargeCreditMapper#countLiveCardsByUser} 与
 * {@code RechargeIdentityMapper#selectCountLiveCardsByUser} 的排除条件必须与
 * {@link #isGiftCard} 逐条同构，由 {@code CardEligibilityTest} 看守——改判据必须三处同步。</p>
 *
 * @author dakang
 * @since 2026-08-07
 */
public final class CardEligibility {

    private CardEligibility() {
    }

    /**
     * 赠卡形态 SQL 谓词——唯一出处（R2 P1-2 / R3 P1），与 {@link #isGiftCard} 三段判据逐段同构。
     * 陷阱：不做 TRIM（MySQL TRIM 与 Java trim 的空白集合不同，带空白一律按非赠卡）；
     * STR_TO_DATE 遇不存在的日期得 NULL，必须由 COALESCE 钉成空串——否则三值逻辑会让
     * 无效日期行从占名额计数里静默消失；往返格式化相等堵死宽松归一。
     * 畸形有效期一律按非赠卡占名额（fail-closed）。改判据只改这里，使用点自动同步。
     */
    public static final String SQL_GIFT = "CARD_TYPE = 1 AND EXPIRE_TIME IS NOT NULL "
            + "AND EXPIRE_TIME REGEXP '^[0-9]{14}$' "
            + "AND COALESCE(DATE_FORMAT(STR_TO_DATE(EXPIRE_TIME, '%Y%m%d%H%i%s'), '%Y%m%d%H%i%s'), '') = EXPIRE_TIME "
            + "AND ISSUE_ORDER_ID IS NULL";

    /** 非赠卡（占付费名额）谓词：{@link #SQL_GIFT} 的整体取反，禁止手写德摩根展开副本。 */
    public static final String SQL_NOT_GIFT = "NOT (" + SQL_GIFT + ")";

    /**
     * 活动赠卡：虚拟卡（CARD_TYPE=1，D-416）+ 严格可往返的 14 位有效期 + 无 ISSUE_ORDER_ID。
     * 实体卡（CARD_TYPE=2）即使带期无锚也不是赠卡；畸形有效期一律按异常付费卡占名额，
     * 不进入转正/合并。本方法是全仓「有效期是否合法」的唯一定义。
     */
    public static boolean isGiftCard(WsCard card) {
        if (ObjectUtil.isNull(card)
                || !ObjectUtil.equals(card.getCardType(), 1)
                || card.getIssueOrderId() != null) {
            return false;
        }
        return isStrictBizTime(card.getExpireTime());
    }

    /**
     * yyyyMMddHHmmss 严格往返判定（与 {@link #SQL_GIFT} 同构）：恰 14 位 ASCII 数字
     * （不 trim，任何空白判非法）、可解析为真实日期、重新格式化与原文完全相等。
     */
    public static boolean isStrictBizTime(String expire) {
        if (expire == null || expire.length() != 14) {
            return false;
        }
        for (int i = 0; i < 14; i++) {
            char c = expire.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        try {
            java.time.LocalDateTime parsed =
                    java.time.LocalDateTime.parse(expire, com.jbk.tool.utils.DateUtils.COMPACT_FORMATTER);
            return expire.equals(parsed.format(com.jbk.tool.utils.DateUtils.COMPACT_FORMATTER));
        } catch (Exception invalid) {
            return false;
        }
    }

    /**
     * 是否占「一人一张付费卡」名额（D-417）：非赠卡即占，不看 CARD_STATUS——
     * 冻结(2)/过期(3)/注销(4) 但 DATA_STATUS=0 的付费卡全部占名额（决议原文）；
     * 逻辑删除的卡由查询侧排除，不在本判定范围。
     */
    public static boolean occupiesPaidSlot(WsCard card) {
        return ObjectUtil.isNotNull(card) && !isGiftCard(card);
    }
}
