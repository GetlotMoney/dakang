package com.jbk.serve.service.mall;

import com.jbk.tool.data.mall.po.WsMallRefundFact;

/**
 * 商城退款事实服务（事务A，E2E-09 S4）。
 *
 * <p>事实只留证，不直接改订单、库存或售后状态——推进由事务B 在锁内重读后决定。
 * 与支付事实同一结构：事实丢了补不回来，推进可以重试。</p>
 *
 * @author dakang
 * @since 2026-08-10
 */
public interface IMallRefundFactService {

    /** 落退款事实（同键重放复用原行并逐字核对正文）。 */
    WsMallRefundFact recordFact(Integer refundSource, Integer factChannel, String providerEventKey,
                               String refundNo, String orderNo, String refundState,
                               String refundTransactionId, Long refundAmountFen,
                               String refundSuccessTime, int verifyMethod, String rawBody);

    /** 按事实键读取。 */
    WsMallRefundFact findFact(Integer refundSource, Integer factChannel, String providerEventKey);

    /** 认领并推进一条事实（claim → apply → 落终态）。 */
    IMallPayApplyTx.Outcome process(Long refundFactId);
}
