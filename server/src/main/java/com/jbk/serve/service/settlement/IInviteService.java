package com.jbk.serve.service.settlement;

/**
 * 邀请归因服务（E2E-08 包D / 甲方确认点③的邀请码路径）。
 *
 * <p>口径（任务书二.9 冻结）：本人邀请码确定性派生+唯一键兜底；补绑一次性
 * （REFERRER_USER_ID IS NULL 前态 CAS）、防自邀、域事件审计；绑定不回溯——
 * 只影响绑定之后的新订单快照。字段语义中性（与推送码是否合一属外部确认项）。</p>
 *
 * @author dakang
 * @since 2026-07-31
 */
public interface IInviteService {

    /** 取本人邀请码：无则惰性生成并落库（确定性派生，唯一键冲突时加盐重派生）。 */
    String myInviteCode(Long userId);

    /**
     * 补绑推荐人：按码定位推荐人 → 防自邀 → REFERRER_USER_ID IS NULL 前态 CAS 一次性绑定
     * → 域事件审计（同事务）。码不存在与已绑定均明确拒绝。
     */
    void bindReferrer(Long userId, String inviteCode);

    /** 下单归因快照：会话用户当前的推荐人（无绑定=NULL）；四个创单点统一走这里取值。 */
    Long referrerSnapshotOf(Long userId);
}
