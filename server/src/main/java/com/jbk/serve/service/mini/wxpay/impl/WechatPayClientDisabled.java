package com.jbk.serve.service.mini.wxpay.impl;

import com.jbk.serve.service.mini.wxpay.IWechatPayClient;
import com.jbk.tool.exception.JbkException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 微信支付出站适配器的 fail-closed 兜底实现（WX-ECO S3）：每个方法明确拒绝，
 * 绝不返回可被误读为"微信侧成功"的值。门控用显式属性互斥而非
 * {@code @ConditionalOnMissingBean}——后者在组件扫描 Bean 上求值依赖注册顺序
 * （WechatPaySourceAdapter 已踩过弃用）；真实 HTTP 适配器以 havingValue="true" 注册。
 *
 * @author dakang
 * @since 2026-08-13
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "wechat.pay.http-client.enabled", havingValue = "false", matchIfMissing = true)
public class WechatPayClientDisabled implements IWechatPayClient {

    @Override
    public String jsapiPrepay(String outTradeNo, long amountFen, String description,
                              String payerOpenid, String expireRfc3339) {
        throw refuse("下单", outTradeNo);
    }

    @Override
    public String queryByOutTradeNo(String outTradeNo) {
        throw refuse("查单", outTradeNo);
    }

    @Override
    public void closeByOutTradeNo(String outTradeNo) {
        throw refuse("关单", outTradeNo);
    }

    @Override
    public String applyRefund(String outRefundNo, String outTradeNo,
                              long refundFen, long totalFen, String reason) {
        throw refuse("退款申请", outRefundNo);
    }

    @Override
    public String queryRefund(String outRefundNo) {
        throw refuse("退款查询", outRefundNo);
    }

    @Override
    public byte[] downloadTradeBill(String billDateIso) {
        throw refuse("账单下载", billDateIso);
    }

    private JbkException refuse(String action, String no) {
        log.warn("微信支付未接入，拒绝{}：{}", action, no);
        // 对用户说人话；对运维，上面的日志带动作与单号
        return new JbkException("微信支付暂未开通，请使用其他方式或联系客服");
    }
}
