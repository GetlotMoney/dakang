package com.jbk.tool.data.mall.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 已注册承运商（E2E-09 L1）。
 *
 * <p>只暴露编码与展示名。运营在 PC 下拉里看到什么，取决于后端实际装配了哪些适配器：
 * 关掉 Logistics-Sim 开关，列表就是空的，创建运单入口随之不可用——
 * 而不是让人选出一个没有适配器的编码，建出一张永远发不出去的运单。</p>
 *
 * @author dakang
 * @since 2026-08-11
 */
@Data
@Accessors(chain = true)
@Schema(description = "已注册承运商")
public class MallLogisticsProviderVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "承运商编码：与 ws_mall_shipment.PROVIDER_CODE 同值")
    private String providerCode;

    @Schema(description = "承运商展示名")
    private String providerName;
}
