package com.jbk.tool.consts.mini;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 微信交易订单发货管理同步（WX-ECO S4）。处理状态沿用字典 1408
 * （{@link WechatNotifyEnum.ProcessingStatus}），不新铸口径。
 */
public final class WechatShippingEnum {

    /** 幂等键前缀：WXSHIP:&lt;orderNo&gt;:&lt;direction&gt;:&lt;seq&gt;。 */
    public static final String SYNC_KEY_PREFIX = "WXSHIP";

    private WechatShippingEnum() {
    }

    /** 微信 upload_shipping_info 的 logistics_type 值域，取值即协议值。 */
    @Getter
    @AllArgsConstructor
    public enum LogisticsType {
        EXPRESS(1, "实体快递"),
        SAME_CITY(2, "同城配送"),
        VIRTUAL(3, "虚拟商品"),
        SELF_PICKUP(4, "用户自提");

        private final int value;
        private final String desc;
    }

    /** 已处理但未同步的原因；与同步成功必须可区分。 */
    @Getter
    @AllArgsConstructor
    public enum SkipReason {
        /** 非微信收款单（Pay-Sim）：留痕不外呼，模拟单绝不进真实微信发货台账。 */
        NOT_WECHAT_PAY("NOT_WECHAT_PAY"),
        /** 出站适配器未接（fail-closed 兜底生效期）。 */
        CLIENT_UNCONFIGURED("CLIENT_UNCONFIGURED");

        private final String code;
    }
}
