package com.jbk.serve.service.mini.wxship;

/**
 * 微信「交易订单发货管理」出站适配器（WX-ECO S4，upload_shipping_info）。
 * 传输隔离在接口后：本轮禁止真实外呼，测试用假实现验证接线，联调轮补真 HTTP 实现。
 * 实现不得把"调用成功"翻译成任何业务状态——同步结果只写 outbox 终态。
 */
public interface IWechatShippingClient {

    /**
     * 上传发货信息。
     *
     * @param transactionId 微信支付交易号（order_key 定位）
     * @param logisticsType 协议值：1快递 2同城 3虚拟 4自提
     * @param providerCode  承运商编码（快递模式必填；微信 delivery_id 由实现映射）
     * @param waybillNo     运单号（快递模式必填）
     * @param itemDesc      商品描述
     * @param payerOpenid   付款人 openid（协议必填，调用方按 userId 现查）
     */
    void uploadShippingInfo(String transactionId, int logisticsType, String providerCode,
                            String waybillNo, String itemDesc, String payerOpenid);
}
