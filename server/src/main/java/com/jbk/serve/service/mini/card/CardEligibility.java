package com.jbk.serve.service.mini.card;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.jbk.tool.data.user.po.WsCard;

/**
 * 卡业务形态判定——<b>单一出处</b>（审计 P1-2 整改，2026-08-07）。
 *
 * <p>此前「赠卡/付费卡」判据散落在充值创单、卡列表、合并三个 Service 里各写一份，
 * 且都漏了两条决议约束：D-416「赠卡两条出路均限<b>虚拟卡</b>」（判据没看 CARD_TYPE），
 * D-417「冻结、过期、<b>注销</b>但未删除的付费卡都占一人一卡名额」（名额统计排除了状态 4）。
 * 三份漂移的判据正是这类缺陷的温床，收敛到本类后不许再复制。</p>
 *
 * <h3>与 SQL 侧的一致性</h3>
 * <p>首购发卡资格（{@code RechargeCreditMapper#countLiveCardsByUser}）与创单资格
 * （{@code RechargeIdentityMapper#selectCountLiveCardsByUser}）两条 COUNT SQL 的排除条件
 * 必须与 {@link #isGiftCard} 逐条同构：{@code CARD_TYPE=1 AND EXPIRE_TIME IS NOT NULL
 * AND ISSUE_ORDER_ID IS NULL}。SQL 无法调用本类，一致性由注释互指与
 * {@code CardEligibilityTest} 看守——改任何一处必须三处同步。</p>
 *
 * @author dakang
 * @since 2026-08-07
 */
public final class CardEligibility {

    private CardEligibility() {
    }

    /**
     * 赠卡形态的 SQL 谓词——<b>唯一出处</b>（审计 R2 P1-2 / R3 P1）。
     *
     * <p>有效期三段判据与 {@link #isGiftCard} 逐段同构（R3：只看长度会把
     * {@code 'abcdefghijklmn'} 这类 14 位垃圾当成赠卡排除出付费名额）：</p>
     * <ol>
     *   <li>{@code REGEXP '^[0-9]{14}$'}：原始值恰为 14 位 ASCII 数字——<b>不做 TRIM</b>，
     *       MySQL 的 TRIM 只去空格而 Java 的 trim 连制表符一起去，两个「去空白」语义不同，
     *       带任何空白的值一律按非赠卡占名额；</li>
     *   <li>{@code STR_TO_DATE} 能解析出真实日期（{@code 20260230000000} 这类不存在的
     *       日期得到 NULL——外层 {@code COALESCE(...,'')} 把 NULL 钉成不相等的空串：
     *       否则 {@code NOT(NULL)=NULL}，三值逻辑会让无效日期行从占名额计数里静默消失，
     *       恰好复现被修的缺陷）；</li>
     *   <li>{@code DATE_FORMAT(...) = EXPIRE_TIME} 往返一致——解析结果重新格式化必须
     *       与原文完全相等，堵死任何宽松归一。</li>
     * </ol>
     * <p>畸形有效期（空串/空白/非法长度/非数字/不存在日期）两侧一致按<b>非赠卡</b>占名额，
     * fail-closed。注解 SQL 通过编译期常量拼接引用本谓词，Wrapper 通过 {@code apply}
     * 原生片段引用——改判据只改这里，六个使用点自动同步。</p>
     */
    public static final String SQL_GIFT = "CARD_TYPE = 1 AND EXPIRE_TIME IS NOT NULL "
            + "AND EXPIRE_TIME REGEXP '^[0-9]{14}$' "
            + "AND COALESCE(DATE_FORMAT(STR_TO_DATE(EXPIRE_TIME, '%Y%m%d%H%i%s'), '%Y%m%d%H%i%s'), '') = EXPIRE_TIME "
            + "AND ISSUE_ORDER_ID IS NULL";

    /** 非赠卡（占付费名额）谓词：{@link #SQL_GIFT} 的整体取反，禁止手写德摩根展开副本。 */
    public static final String SQL_NOT_GIFT = "NOT (" + SQL_GIFT + ")";

    /**
     * 活动赠卡：<b>虚拟卡</b>（CARD_TYPE=1，D-416 明令）+ 严格可往返解析的 14 位有效期
     * + 无购卡订单锚（赠卡是唯一无 ISSUE_ORDER_ID 的发卡路径）。
     *
     * <p>实体卡（CARD_TYPE=2）即使带期无锚也<b>不是</b>赠卡；有效期判据与
     * {@link #SQL_GIFT} 逐段同构（R3 P1）：14 位字母、不存在的日期（20260230000000）、
     * 含制表符等控制空白、空串/空白/非法长度——全部按异常付费卡占名额，
     * 绝不进入转正/合并两条出路。这也是全仓「有效期是否合法」的唯一定义，
     * 与 {@code RechargeExpiry} 的处理侧不再各持一套。</p>
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
     * yyyyMMddHHmmss 的严格往返判定（R3 P1，与 SQL 侧三段判据同构）：
     * ①原始值恰 14 位 ASCII 数字（不 trim——Java trim 与 MySQL TRIM 的空白集合不同，
     * 任何空白直接判非法）；②能解析为真实日期时间；③解析结果重新格式化必须与原文
     * 完全相等（复用 {@code DateUtils.COMPACT_FORMATTER}，往返检查堵死宽松归一）。
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
     * 是否占用「一人一张付费卡」名额（D-417）：非赠卡即占。
     *
     * <p><b>不看 CARD_STATUS</b>：冻结(2)、过期(3)、注销(4) 但 DATA_STATUS=0 的付费卡
     * 全部占名额并阻断赠卡转正——这是决议原文，不是实现偏好。逻辑删除（DATA_STATUS≠0）
     * 的卡由查询侧 {@code @TableLogic}/显式条件排除，不在本判定范围。</p>
     */
    public static boolean occupiesPaidSlot(WsCard card) {
        return ObjectUtil.isNotNull(card) && !isGiftCard(card);
    }
}
