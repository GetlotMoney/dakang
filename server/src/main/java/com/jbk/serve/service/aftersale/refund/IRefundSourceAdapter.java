package com.jbk.serve.service.aftersale.refund;

/**
 * 退款来源的<b>受信任服务端适配器</b>（E2E-04 包B，R0-8）。
 *
 * <p>与 {@code IRechargePaySourceAdapter} 同一套设计：REFUND_SOURCE 是退款单的权威来源，
 * 一旦创建不可改变；微信适配器只能写 WECHAT，Refund-Sim 适配器只能写 REFUND_SIM。
 * 前端、普通请求或退款报文自报字段<b>均不得决定来源</b>，因此该值只能由本接口的服务端实现给出，
 * 禁止出现在任何入参 BO 里。</p>
 *
 * <h3>与支付侧的关键差异：本接口多了 {@link #requireOperable()}</h3>
 * <p>支付来源适配器只需回答「我是谁」，因为收款能力由用户在微信侧完成。退款不同——
 * 退款是<b>我方主动发起</b>的出账动作，所以还必须回答「我现在能不能真的发起」。
 * R0-8 要求「真实微信凭据未配置时 fail-closed，禁止回退 Refund-Sim」，
 * 而这道闸必须在<b>任何写库之前</b>生效：若等到写完退款单再发现发不出去，
 * 库里就留下一张永远推进不了、又无法判断是否已经出过款的退款单——
 * 那是对账里最难处理的一种形状。</p>
 *
 * <h3>适配器不产生「退款成功」</h3>
 * <p>{@link #acceptRefund} 只把退款请求交给服务方并拿回受理凭据，
 * 返回 SUCCESS 也不代表钱已退：真正的成功必须由一条独立的退款事实经收件箱、
 * 由 Worker 核验后推进（R0-8）。适配器<b>不得</b>直接改写 {@code ws_refund.REFUND_STATUS}。</p>
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

    /**
     * 断言当前适配器具备发起退款的能力，不具备则抛出。
     *
     * <p>调用方必须在<b>创建退款单之前</b>调用本方法。见类注释：
     * 这道闸的位置本身就是设计的一部分，挪到写库之后即失去意义。</p>
     */
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
