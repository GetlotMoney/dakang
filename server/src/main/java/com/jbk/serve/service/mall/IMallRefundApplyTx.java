package com.jbk.serve.service.mall;

/**
 * 商城退款推进段（事务B，E2E-09 S4）。
 *
 * <p>结果词汇直接复用 {@link IMallPayApplyTx.Outcome}：退款与支付的推进结论是同一组
 * （已推进 / 已处理过 / 需人工 / 待重试），再定义一套同义枚举只会让两边各自漂移。</p>
 *
 * @author dakang
 * @since 2026-08-10
 */
public interface IMallRefundApplyTx {

    /**
     * 按退款事实主键推进：锁内重读全部事实，再次核验共键、金额、币种、交易号与时间。
     *
     * <p>入参只有主键——事务A 与事务B 之间隔着一次提交，任何从上一段带过来的对象都可能
     * 已经过期。</p>
     */
    IMallPayApplyTx.Outcome apply(Long refundFactId);
}
