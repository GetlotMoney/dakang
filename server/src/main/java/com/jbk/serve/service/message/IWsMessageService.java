package com.jbk.serve.service.message;

import com.baomidou.mybatisplus.extension.service.IService;
import com.jbk.tool.consts.message.MessageEnum;
import com.jbk.tool.data.message.po.WsMessage;

/**
 * 站内消息服务（E2E-03 A6：一期只做站内渠道，不接微信订阅消息）。
 *
 * @author dakang
 * @since 2026-07-23
 */
public interface IWsMessageService extends IService<WsMessage> {

    /**
     * 发送站内消息：与调用方业务动作同事务落库（同生共死），SEND_TIME 与动作时间同源。
     * 写入失败向上抛出，由业务事务整体回滚——消息不是尽力而为的旁路，而是履约证据链的一环。
     *
     * @param userId     收件用户
     * @param domain     消息领域
     * @param title      标题
     * @param content    正文（禁止含明文手机号等敏感信息）
     * @param objectType 关联对象类型（order/task/appeal/...）
     * @param objectId   关联对象业务键
     * @param sendTime   业务动作时间（yyyyMMddHHmmss）
     */
    void sendInApp(Long userId, MessageEnum.MsgDomain domain, String title, String content,
                   String objectType, String objectId, String sendTime);
}
