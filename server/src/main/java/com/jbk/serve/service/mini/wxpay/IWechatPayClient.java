package com.jbk.serve.service.mini.wxpay;

/**
 * 微信支付 APIv3 出站调用适配器（WX-ECO S3）：传输隔离在接口后，本轮禁真实外呼，
 * 测试用假实现，联调轮补真 HTTP 实现。返回值永远是微信侧原话——禁止把"调用成功"
 * 翻译成"支付成功"（任务书明令），查单结果落库走与回调相同的事实收件箱（QUERY 通道）。
 * 金额一律为分（long）。本轮仅 {@link #jsapiPrepay} 有调用方，查单/关单/退款/账单为占位，
 * 接线属 S4/联调轮（退款出站需同步改造 {@code WechatRefundSourceAdapter}）。
 *
 * @author dakang
 * @since 2026-08-13
 */
public interface IWechatPayClient {

    /**
     * JSAPI 下单，换取 prepay_id。
     *
     * @param outTradeNo  商户订单号（本系统订单号）
     * @param amountFen   订单金额（分）；由服务端支付单给出，绝不接受前端金额
     * @param description 商品描述（微信收银台展示）
     * @param payerOpenid 付款人 openid（JSAPI 必填）
     * @param expireRfc3339 支付截止时间（RFC3339），与内部付款窗同源；null 表示不传
     */
    String jsapiPrepay(String outTradeNo, long amountFen, String description,
                       String payerOpenid, String expireRfc3339);

    /**
     * 商户订单号查单。返回原始应答 JSON 字符串——由调用方按事实收件箱口径解析落库，
     * 不在适配器里做任何状态翻译。
     */
    String queryByOutTradeNo(String outTradeNo);

    /** 关单。幂等：微信侧已关/已支付时按其应答处理，适配器不吞错。 */
    void closeByOutTradeNo(String outTradeNo);

    /**
     * 申请退款。
     *
     * @param outRefundNo 商户退款单号（本系统售后/退款单号）
     * @param outTradeNo  原商户订单号
     * @param refundFen   本次退款金额（分）
     * @param totalFen    原订单金额（分）
     * @param reason      退款原因（用户可见）
     * @return 微信原始应答 JSON
     */
    String applyRefund(String outRefundNo, String outTradeNo, long refundFen, long totalFen, String reason);

    /** 商户退款单号查退款。返回微信原始应答 JSON。 */
    String queryRefund(String outRefundNo);

    /**
     * 申请交易账单下载地址并拉取账单原文（对账用）。
     *
     * @param billDateIso 账单日期 yyyy-MM-dd
     * @return 账单原文字节（GZIP 已解开）
     */
    byte[] downloadTradeBill(String billDateIso);
}
