package com.jbk.serve.service.message.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jbk.serve.mapper.message.WsMessageMapper;
import com.jbk.serve.service.message.IWsMessageService;
import com.jbk.tool.consts.message.MessageEnum;
import com.jbk.tool.data.message.po.WsMessage;
import com.jbk.tool.exception.JbkException;
import org.springframework.stereotype.Service;

/**
 * 站内消息服务实现。不开独立事务：加入调用方事务，保证「动作成功才有消息、
 * 动作回滚消息同灭」（E2E-03 规则13 消息与动作同源的一半；另一半是 sendTime 入参）。
 *
 * @author dakang
 * @since 2026-07-23
 */
@Service
public class WsMessageServiceImpl extends ServiceImpl<WsMessageMapper, WsMessage> implements IWsMessageService {

    @Override
    public void sendInApp(Long userId, MessageEnum.MsgDomain domain, String title, String content,
                          String objectType, String objectId, String sendTime) {
        if (userId == null || domain == null || title == null || title.isBlank()) {
            throw new JbkException("站内消息参数不完整");
        }
        WsMessage message = new WsMessage()
                .setUserId(userId)
                .setMsgDomain(domain.getValue())
                .setMsgTitle(title)
                .setMsgContent(content == null ? "" : content)
                .setMsgChannel(MessageEnum.MsgChannel.IN_APP.getValue())
                .setSendStatus(MessageEnum.SendStatus.DELIVERED.getValue())
                .setSendTime(sendTime)
                .setReadFlag(0)
                .setObjectType(objectType)
                .setObjectId(objectId);
        // 消息时间与业务动作同源：显式写创建/更新时间，不交给落库时刻的系统时钟
        message.setCreateTime(sendTime);
        message.setUpdateTime(sendTime);
        if (!save(message)) {
            throw new JbkException("站内消息写入失败");
        }
    }
}
