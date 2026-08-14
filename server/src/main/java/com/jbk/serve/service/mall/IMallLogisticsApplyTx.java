package com.jbk.serve.service.mall;

/**
 * 物流事实推进段（事务B，E2E-09 L1）。
 *
 * <p>独立事务：本段失败不得回滚已落库的外部事实。事实是证据，推进是内部动作——
 * 前者丢了补不回来，后者失败可以重试。</p>
 *
 * @author dakang
 * @since 2026-08-11
 */
public interface IMallLogisticsApplyTx {

    /** 在锁内重读全部共键、重新核验后推进包裹与履约状态。 */
    IMallPayApplyTx.Outcome apply(Long eventId);
}
