package com.jbk.serve.service.mini.recharge;

/** 事务 B 回滚或分类失败后的独立严格落痕事务。 */
public interface IRechargeCreditFailureTx {

    /**
     * @param retryable 仅卡冻结等明确可恢复错误为 true；其余进入人工对账
     */
    void record(Long eventId, boolean retryable, String reason, String now);
}
