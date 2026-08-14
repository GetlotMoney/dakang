package com.jbk.serve.service.aftersale.refund;

/**
 * 外部退款请求的<b>事务边界</b>（E2E-04 包B）。拆成两个事务是因为中间夹着对外调用：
 * 放进同一事务会让行锁持有到回包，且超时回滚时请求可能已发出——「钱在退而我们没有记录」。
 * 顺序固定：{@link #createPending} 提交 → 事务外调服务方 → {@link #fillAcceptance} 回填；
 * REFUND_NO 确定性派生，重发时服务方按 out_refund_no 幂等。
 * 「退款成功」不在本接口发生，必须由退款事实经收件箱推进（R0-8）。
 *
 * @author dakang
 * @since 2026-07-29
 */
public interface IRefundRequestTxService {

    /**
     * 锁定异常充值订单与原支付证据，在同一 REQUIRES_NEW 事务内登记稳定售后动作和本地退款单。
     * 请求只给订单ID与说明；金额恒由 {@code RefundEligibility} 按原支付单全额算定。
     */
    Long createUnsettledRechargePending(Long orderId, String remark, Long opUserId, String now);

    /**
     * 过准入闸并创建 1退款中 的本地退款单。幂等由 {@code uk_refund_after_sale}/{@code uk_refund_no}
     * 收敛（铁律②）；插入前的既有单读回只是省一次异常，不是幂等依据。
     *
     * @param afterSaleId 售后动作 ID
     * @param opUserId    操作人（PC 员工 ID）
     * @param now         业务时间(yyyyMMddHHmmss)，由调用方统一给出
     * @return 退款单 ID
     */
    Long createPending(Long afterSaleId, Long opUserId, String now);

    /**
     * 回填服务方受理凭据。CAS 前态含 {@code PROVIDER_REFUND_ID IS NULL}：重复受理不得覆盖既有号
     * （两个号在服务方即两笔退款）；影响 0 行不抛——通常是并发处理者已回填，属正常竞争。
     */
    void fillAcceptance(Long refundId, String providerRefundId, Long opUserId, String now);
}
