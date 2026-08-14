package com.jbk.serve.service.mall.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.jbk.serve.service.mall.ILogisticsProviderAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 内部模拟承运商适配器（E2E-09 L1，仅隔离环境启用）。模拟的是承运方不是业务结果：
 * 只按幂等键确定性派生运单号（前缀 + sha256(bizActionKey) 前 16 位，重试恒得同号），
 * 不改任何业务状态——包裹与履约推进恒由验签后的物流事实驱动，与真实承运商链路同形。
 * 开关 {@code mall.logistics-sim.enabled} 缺省与生产恒 false；关闭时 Bean 不注册，
 * Worker 取不到适配器即整批转人工，不会静默降级。
 *
 * @author dakang
 * @since 2026-08-11
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mall.logistics-sim.enabled", havingValue = "true")
public class LogisticsSimAdapter implements ILogisticsProviderAdapter {

    /** 模拟承运商编码：与自营的 SELF 分开，让运单号唯一键在两条链上互不干扰。 */
    public static final String PROVIDER_CODE = "SIM";
    /** 模拟运单号前缀：一眼能看出这不是真实承运商的单号。 */
    static final String WAYBILL_PREFIX = "SIMWB";
    /** 模拟承运方订单号前缀。 */
    static final String ORDER_PREFIX = "SIMPO";
    /** 本模拟只提供一种服务类型：真实服务类型目录属承运商契约，未接入前不发明。 */
    static final String SERVICE_CODE = "SIM_STANDARD";

    @Override
    public String providerCode() {
        return PROVIDER_CODE;
    }

    @Override
    public String providerName() {
        // 名称里写死"内部模拟"：运营在下拉里一眼看出这不是真实承运商，
        // 隔离环境的演示单据也不会被误当成真单据流转出去
        return "内部模拟承运（Logistics-Sim）";
    }

    @Override
    public CreateResult createOrder(String bizActionKey, String request) {
        String digest = DigestUtil.sha256Hex(bizActionKey).substring(0, 16).toUpperCase();
        log.info("Logistics-Sim 受理建单 actionKey={}", bizActionKey);
        return new CreateResult(ORDER_PREFIX + digest, WAYBILL_PREFIX + digest, SERVICE_CODE);
    }
}
