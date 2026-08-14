package com.jbk.serve.service.mall;

/**
 * 商城支付方查单适配（E2E-09 S2）。
 *
 * <p>超时关单必须以支付方的权威答复为依据：本地时间到了只说明"我方认为该催了"，
 * 不说明对方那笔钱没有收。没有可用适配器时，关单链路必须整体停摆而不是自行关单
 * ——凭本地时钟关掉一笔实际已支付的订单，会把用户的钱和货同时弄丢。</p>
 *
 * @author dakang
 * @since 2026-08-08
 */
public interface IMallPayQueryAdapter {

    /** 支付来源标识（与 ws_mall_payment.PAY_SOURCE 同域）。 */
    int paySource();

    /**
     * 查单：返回规范化状态 SUCCESS/NOTPAY/CLOSED；无法判定时返回 null（调用方不得推进）。
     */
    String queryTradeState(String orderNo);
}
