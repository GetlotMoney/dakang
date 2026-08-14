package com.jbk.serve.service.mall;

/**
 * 承运商适配器（E2E-09 L1）：厂商差异只落在这里，平台侧表/状态机/三端只认
 * {@code ws_mall_shipment} 的四个通用列。只被 Worker 调用——实现可能发起外部请求，
 * 绝不允许在数据库事务内被调：业务事务只写 outbox，Worker 领取后才调这里。
 *
 * @author dakang
 * @since 2026-08-11
 */
public interface ILogisticsProviderAdapter {

    /** 承运商编码：与 {@code ws_mall_shipment.PROVIDER_CODE} 同值。 */
    String providerCode();

    /** 承运商展示名：三端下拉与包裹卡片用它，不要在前端按编码硬编码中文。 */
    String providerName();

    /**
     * 创建运单。
     *
     * @param request 登记时冻结的请求快照（JSON），Worker 原样透传，不回读当前业务态
     * @return 承运方回执；实现必须保证同一 bizActionKey 重复调用返回同一运单号
     */
    CreateResult createOrder(String bizActionKey, String request);

    /** 承运方回执。 */
    record CreateResult(String providerOrderNo, String waybillNo, String serviceCode) {
    }
}
