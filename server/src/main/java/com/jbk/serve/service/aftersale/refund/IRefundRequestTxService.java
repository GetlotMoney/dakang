package com.jbk.serve.service.aftersale.refund;

/**
 * 外部退款请求的<b>事务边界</b>（E2E-04 包B）。
 *
 * <h3>为什么拆成两个事务，而不是一个方法从头做到尾</h3>
 * <p>中间夹着一次<b>对外调用</b>（{@link IRefundSourceAdapter#acceptRefund}）。
 * 把它放进同一个事务，意味着 ws_order / ws_payment 的行锁要一直持有到支付机构回包为止——
 * 对方超时 30 秒，这些行就锁死 30 秒，同一订单的任何其它资金动作全部排队。
 * 更糟的是事务超时回滚时，请求可能<b>已经发出去了</b>，而本地退款单被回滚掉，
 * 于是「钱已经在退，我们却没有任何记录」。</p>
 *
 * <p>因此顺序固定为：{@link #createPending} 落库并提交 → 事务外调用服务方 →
 * {@link #fillAcceptance} 回填。任一步中断，本地都留着一张 1退款中 的退款单，
 * 其 {@code REFUND_NO} 是确定性派生的，重发时服务方按 out_refund_no 幂等，不会重复出账。</p>
 *
 * <p>「退款成功」不在本接口发生——它必须由一条独立的退款事实经收件箱、
 * 由 Worker 核验后推进（R0-8）。任何在这里直接写 REFUND_STATUS=2 的改动都违反该纪律。</p>
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
     * 过准入闸并创建 1退款中 的本地退款单。
     *
     * <p>幂等：同一售后动作重复调用会撞 {@code uk_refund_after_sale} / {@code uk_refund_no}，
     * 由数据库唯一键收敛（铁律②）；本方法在插入前也会先读一次既有单直接返回，
     * 那只是省一次异常，<b>不是</b>幂等的依据。</p>
     *
     * @param afterSaleId 售后动作 ID
     * @param opUserId    操作人（PC 员工 ID）
     * @param now         业务时间(yyyyMMddHHmmss)，由调用方统一给出
     * @return 退款单 ID
     */
    Long createPending(Long afterSaleId, Long opUserId, String now);

    /**
     * 回填服务方受理凭据。
     *
     * <p>CAS 前态含 {@code PROVIDER_REFUND_ID IS NULL}：重复受理不得覆盖既有服务方单号，
     * 否则事实回来时无从判断哪个号才是本单的，而两个号在服务方那边意味着两笔退款。
     * 影响 0 行不抛异常——那通常意味着另一个并发处理者已经回填过，属正常竞争结果。</p>
     */
    void fillAcceptance(Long refundId, String providerRefundId, Long opUserId, String now);
}
