package com.jbk.serve.service.mini.card;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.jbk.tool.data.user.po.WsCardMember;

/**
 * 水卡成员授权有效性的<b>唯一</b>判定口径（CARD-MEMBER）。
 *
 * <p>预检（eligibility）、可用卡列表（usable-list）与取水事务三条链统一复用本类；
 * 仓内不得再出现第二套成员有效性判断——口径漂移会造成「列表可见、下单被拒」或反之的越权缝隙。</p>
 *
 * <p>时间窗语义与卡有效期一致：{@code EFFECTIVE_TIME} 空=立即生效，非空则须 &lt;= now；
 * {@code EXPIRE_TIME} 空=长期有效，非空且 &lt;= now 即失效（与 ws_card 的过期判定同边界）。
 * 任一维不满足即无效，由调用方 fail-closed 拒绝。</p>
 */
public final class CardMemberRule {

    /** 授权状态(1333)：1 生效。 */
    public static final int MEMBER_STATUS_ACTIVE = 1;

    private CardMemberRule() {
    }

    /**
     * 成员授权当前是否有效：记录存在、未逻辑删除、状态生效、且落在授权时间窗内。
     *
     * @param member 成员授权记录（可为 null，可含逻辑删除态——调用方常用跨 DATA_STATUS 查询）
     * @param now    服务端当前时间 yyyyMMddHHmmss
     */
    public static boolean isActive(WsCardMember member, String now) {
        return ObjectUtil.isNotNull(member)
                && ObjectUtil.equals(member.getDataStatus(), 0)
                && ObjectUtil.equals(member.getMemberStatus(), MEMBER_STATUS_ACTIVE)
                && withinWindow(member, now);
    }

    /** 仅时间窗判定（不含状态）：生效时间未到或失效时间已过即不在窗内。 */
    public static boolean withinWindow(WsCardMember member, String now) {
        if (ObjectUtil.isNull(member) || StrUtil.isBlank(now)) {
            return false;
        }
        boolean effective = StrUtil.isBlank(member.getEffectiveTime())
                || member.getEffectiveTime().compareTo(now) <= 0;
        boolean notExpired = StrUtil.isBlank(member.getExpireTime())
                || member.getExpireTime().compareTo(now) > 0;
        return effective && notExpired;
    }
}
