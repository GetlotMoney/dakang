package com.jbk.serve.service.mini.recharge;

/**
 * 首次购卡发卡事务（L2-A，决策 A2/A3/A4）：{@link IRechargeCreditTx} 的姊妹事务。
 *
 * <p>支付成功后同一事务完成：锁 payment → order → 用户行 → 发行锚点核验 → 复查资格
 * → 建零余额零水量虚拟卡 → 原子加权益 → 唯一流水 → 回填 CARD_ID → 订单 2→4 → 事件组收敛。
 * 任一步失败整体回滚：<b>不留卡、不留流水、订单不动</b>（决策 A2）。</p>
 *
 * <p>结果分类直接复用 {@link IRechargeCreditTx.CreditResult}（枚举语义同源）：
 * CREDITED=发卡并首充入账完成；ALREADY=发行锚点已存在且账本核验一致，仅收敛事件；
 * UNRECOVERABLE=钱已收但不能发卡（如复查发现已有卡），订单应推进到 6 转人工；
 * RETRY_CARD_FROZEN 在发卡路径理论上不出现（尚无既有卡可冻结），保留语义以防御性处理。</p>
 */
public interface IRechargeIssueTx {

    /**
     * 执行发卡与首充入账。
     *
     * @param eventId        已由处理者 claim 的 SUCCESS 支付事实 ID
     * @param processingTime 本次处理时刻，仅用于状态判定、结果校验与审计；
     *                       新卡有效期只从权威 paySuccessTime 起算（决策 A1），绝不用本参数
     */
    IRechargeCreditTx.CreditResult issue(Long eventId, String processingTime);

    /**
     * 发卡失败后的独立落痕事务（决策 A2；语义对齐 {@link IRechargeCreditFailureTx}）：
     * 重新锁定 payment → order（购卡单无卡可锁），可恢复失败保持订单 2 并把事实组转待重试；
     * 不可恢复失败以精确前态 CAS 将订单 2→6 并把事实组转待对账；
     * 订单已完成时只做只读核验与事件收敛，绝不降级已完成订单。
     */
    void recordFailure(Long eventId, boolean retryable, String reason, String now);
}
