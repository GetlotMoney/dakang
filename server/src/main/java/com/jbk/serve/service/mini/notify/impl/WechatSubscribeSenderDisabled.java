package com.jbk.serve.service.mini.notify.impl;

import com.jbk.serve.service.mini.notify.WechatSubscribeSender;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 订阅消息发送的 fail-closed 兜底实现：真实适配器未注册时生效，一律不发。
 * 有它，未接入时通知链表现为"登记了但发不出去且说明原因"，而不是让整个应用启动失败。
 * 返回 PERMANENT 而非 RETRYABLE（未接入不会因重试改变，判可重试会堆满假故障）；
 * 绝不返回 SENT（任务书：不得把代码候选表述为真实微信闭环）。
 *
 * @author dakang
 * @since 2026-08-12
 */
@Service
@ConditionalOnProperty(name = "wechat.notify.http-sender.enabled", havingValue = "false", matchIfMissing = true)
public class WechatSubscribeSenderDisabled implements WechatSubscribeSender {

    @Override
    public SendOutcome send(String openid, String templateId, String payloadJson) {
        return SendOutcome.permanent("微信订阅消息适配器未接入（待商户主体授权与模板申请完成）");
    }
}
