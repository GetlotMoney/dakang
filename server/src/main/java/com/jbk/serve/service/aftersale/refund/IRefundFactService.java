package com.jbk.serve.service.aftersale.refund;

/**
 * 退款事实收件箱：落库与消费（E2E-04 包B，R0-8）。
 *
 * <p>R0-8 的核心是一条单向管道：<b>服务方事实 → 收件箱 → Worker 核验 → 推进退款单</b>。
 * 任何绕过收件箱直接改 {@code ws_refund.REFUND_STATUS=2} 的路径都是重复退款的温床，
 * 因为「已经推进过」这件事只有收件箱能凭幂等键回答。</p>
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
     * 事实落收件箱。
     *
     * <p>幂等由 {@code uk_refund_event_source_channel_key} 保证：同一事实重复到达多少次，
     * 库里也只有一行。重复到达时还要比对正文摘要——同键不同正文意味着同一个事实号
     * 承载了两份内容，此时返回既有事实但把它转人工，绝不复用。</p>
     *
     * @return 事实行 ID
     */
    Long ingest(RefundFact fact, String now);

    /**
     * 消费一条事实：认领 → 关联 → 核验 → 推进退款单 → 落已处理。
     *
     * <p>本方法自身不抛业务异常来表达结局，而是返回 {@link Outcome}：
     * 事实消费是批量的，用异常表达「这条转人工」会让 Worker 无法区分
     * 「该条有问题」与「整个批次该中断」。</p>
     */
    Outcome process(Long eventId, String now);
}
