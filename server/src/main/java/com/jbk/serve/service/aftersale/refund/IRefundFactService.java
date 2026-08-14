package com.jbk.serve.service.aftersale.refund;

/**
 * 退款事实收件箱：落库与消费（E2E-04 包B，R0-8）。单向管道：服务方事实 → 收件箱 → Worker 核验
 * → 推进退款单；绕过收件箱直接改 {@code REFUND_STATUS=2} 即重复退款的温床。
 *
 * @author dakang
 * @since 2026-07-29
 */
public interface IRefundFactService {

    /** 一条事实被消费后的结局，供调用方与冒烟脚本判读。 */
    enum Outcome {
        /** 已推进退款单（或确认此前已推进过）。 */
        PROCESSED,
        /** 未抢到认领：别的处理者正在处理或已处理完。 */
        NOT_CLAIMED,
        /** 可重试失败，已排期。 */
        RETRY_SCHEDULED,
        /** 账实不符或事实自相矛盾，已转人工对账。 */
        RECONCILIATION
    }

    /**
     * 事实落收件箱。幂等由 {@code uk_refund_event_source_channel_key} 保证；
     * 重复到达还要比对正文摘要，同键不同正文即拒绝本次到达。
     *
     * @return 事实行 ID
     */
    Long ingest(RefundFact fact, String now);

    /**
     * 消费一条事实：认领 → 关联 → 核验 → 推进退款单 → 落已处理。以 {@link Outcome} 表达结局
     * 而非抛异常——批量消费时异常无法区分「该条有问题」与「整批该中断」。
     */
    Outcome process(Long eventId, String now);
}
