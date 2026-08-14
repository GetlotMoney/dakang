package com.jbk.serve.service.mall;

import com.jbk.tool.data.mall.po.WsMallPaymentFact;

/**
 * 商城支付事实收件箱服务（E2E-09 S2）。
 *
 * <p>两段式的编排者：{@link #recordFact} 是事务A（事实落库 + 支付单置成功），
 * {@link #process} 是事务B 的驱动（认领 → 推进 → 落终态）。两者必须分开调用，
 * 中间隔一次提交——否则推进失败会把事实一并回滚。</p>
 *
 * @author dakang
 * @since 2026-08-08
 */
public interface IMallPayFactService {

    /**
     * 事务A：落支付事实并（成功事实时）把支付单置成功。
     * 同一幂等三元组重复到达时复用原行，不新增、不改写既有事实。
     */
    WsMallPaymentFact recordFact(Integer paySource, Integer factChannel, String providerEventKey,
                                 String orderNo, String tradeState, String transactionId,
                                 Long payAmountFen, String paySuccessTime, int verifyMethod,
                                 String rawBody);

    /**
     * 按幂等三元组查事实；不存在返回 null。
     *
     * <p>调用方在重复触发同一外部事件时先查后建：同一笔支付只有一个成功时间，
     * 重复调用不该各自生成一份"现在"，否则重放核验会把自己判成内容不一致。</p>
     */
    WsMallPaymentFact findFact(Integer paySource, Integer factChannel, String providerEventKey);

    /**
     * 事务B 驱动：CAS 认领 → 调用推进段 → 按结果落终态（已处理/待重试/需对账）。
     * 并发或重复调用安全：认领失败即按当前状态返回，不重复推进。
     */
    IMallPayApplyTx.Outcome process(Long factId);
}
