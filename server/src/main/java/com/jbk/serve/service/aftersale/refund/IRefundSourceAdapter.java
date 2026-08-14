package com.jbk.serve.service.aftersale.refund;

/**
 * 退款来源的<b>受信任服务端适配器</b>（E2E-04 包B，R0-8）。REFUND_SOURCE 只能由服务端实现给出，
 * 禁止出现在任何入参 BO 里。退款是我方主动出账，故多一道 {@link #requireOperable()}，
 * 且必须在任何写库之前生效——写完退款单再发现发不出去，会留下一张无法判断是否已出款的死单。
 * 适配器不产生「退款成功」、不得直接改写 {@code ws_refund.REFUND_STATUS}（R0-8）。
 *
 * @author dakang
 * @since 2026-07-29
 */
public interface IRefundSourceAdapter {

    /** 退款来源：1 微信。 */
    int WECHAT = 1;

    /** 退款来源：2 Refund-Sim（仅隔离测试环境）。 */
    int REFUND_SIM = 2;

    /** 当前生效的退款来源常量，写入 {@code ws_refund.REFUND_SOURCE}。 */
    int currentSource();

    /** 断言当前适配器具备发起退款的能力；调用方必须在创建退款单之前调用（见类注释）。 */
    void requireOperable();

    /**
     * 向支付机构发起退款请求，返回受理凭据。
     *
     * @param refundNo    商户退款单号（out_refund_no），全局唯一
     * @param orderNo     商户订单号，供服务方定位原交易
     * @param amountFen   本次退款金额（分），必须为正
     * @param currency    币种，当前只支持 CNY
     * @return 服务方受理凭据；<b>不代表退款已成功</b>
     */
    RefundAcceptance acceptRefund(String refundNo, String orderNo, long amountFen, String currency);
}
