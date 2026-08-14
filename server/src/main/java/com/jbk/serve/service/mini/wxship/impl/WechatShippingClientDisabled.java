package com.jbk.serve.service.mini.wxship.impl;

import com.jbk.serve.service.mini.wxship.IWechatShippingClient;
import com.jbk.tool.exception.JbkException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 发货同步出站的 fail-closed 兜底：恒拒绝，绝不返回可被误读为"已同步"的结果。
 * 属性互斥门控（@ConditionalOnMissingBean 在扫描 Bean 上不可靠，仓内已落档弃用）；
 * 联调轮真实实现以 havingValue="true" 注册。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "wechat.shipping.http-client.enabled",
        havingValue = "false", matchIfMissing = true)
public class WechatShippingClientDisabled implements IWechatShippingClient {

    @Override
    public void uploadShippingInfo(String transactionId, int logisticsType, String providerCode,
                                   String waybillNo, String itemDesc, String payerOpenid) {
        log.warn("微信发货同步未接入，拒绝上传 transactionId={}", transactionId);
        throw new JbkException("微信发货同步未接入");
    }
}
