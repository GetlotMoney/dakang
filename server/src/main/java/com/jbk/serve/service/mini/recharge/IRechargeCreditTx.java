package com.jbk.serve.service.mini.recharge;

/**
 * 充值权益入账事务（L2 契约 v2 §6.4 事务 B）：
 * 加余额、加水量、有限卡续期与恢复卡状态、写唯一流水、订单转 4，全部同事务原子完成。
 */
public interface IRechargeCreditTx {

    /**
     * 入账结果分类。<b>「不能入账」不是只有一种</b>——冻结是可恢复的，注销/换主/范围变化是不可恢复的，
     * 两者的后续处理完全不同（前者订单保持 2 等重试，后者订单推进到 6 转人工）。
     * 早期实现把它们一律抛成同一个异常，等于把一张只是临时冻结的卡直接判成异常订单。
     */
    enum Outcome {
        /** 本次真正完成入账，订单已转 4。 */
        CREDITED,
        /** 订单已完成且锁内账本核验一致；本次只收敛事件，不重复写权益。 */
        ALREADY,
        /** 可恢复：卡冻结中。订单保持 2，等待下次重试，不写任何权益。 */
        RETRY_CARD_FROZEN,
        /** 不可恢复：注销/删除/换主/范围变化/算得的新有效期已过期等。订单应推进到 6 转人工。 */
        UNRECOVERABLE
    }

    /** @param reason 仅在非 CREDITED 时有值，用于落痕与人工排查 */
    record CreditResult(Outcome outcome, String reason) {
        public static CreditResult credited() {
            return new CreditResult(Outcome.CREDITED, null);
        }

        public static CreditResult retry(String reason) {
            return new CreditResult(Outcome.RETRY_CARD_FROZEN, reason);
        }

        public static CreditResult already() {
            return new CreditResult(Outcome.ALREADY, null);
        }

        public static CreditResult unrecoverable(String reason) {
            return new CreditResult(Outcome.UNRECOVERABLE, reason);
        }
    }

    /**
     * 执行权益入账。
     *
     * <p>方法在锁内区分正常入账与 order=4 的只读幂等核验；唯一键冲突只会触发回滚，
     * 不能单独作为“此前已正确入账”的证据。</p>
     *
     * @param eventId 已由 Worker claim 的 SUCCESS 支付事实 ID
     * @param processingTime 本次处理时刻，仅用于状态判定、结果校验与审计
     */
    CreditResult credit(Long eventId, String processingTime);
}
