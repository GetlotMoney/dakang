package com.jbk.serve.service.message.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jbk.serve.mapper.message.WsMessageMapper;
import com.jbk.serve.service.message.IWsMessageService;
import com.jbk.tool.consts.message.MessageEnum;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.message.po.WsMessage;
import com.jbk.tool.data.mini.bo.MiniMessageBo;
import com.jbk.tool.data.mini.vo.MiniMessageVo;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 站内消息服务实现。不开独立事务：加入调用方事务，保证「动作成功才有消息、
 * 动作回滚消息同灭」（E2E-03 规则13 消息与动作同源的一半；另一半是 sendTime 入参）。
 * <p>E2E-07 读端一律以会话 userId 过滤（铁律6）；越权与不存在同文案（存在性不泄露）。</p>
 *
 * @author dakang
 * @since 2026-07-23
 */
@Service
public class WsMessageServiceImpl extends ServiceImpl<WsMessageMapper, WsMessage> implements IWsMessageService {

    /** 越权与不存在同文案（对齐 miniapp mock 冻结文案，存在性不泄露）。 */
    private static final String NOT_FOUND = "消息不存在或无权访问";
    /** 列表摘要长度：正文截断派生，供列表行展示（正文 ≤500 也随行下发，两者语义不同不互替）。 */
    private static final int SUMMARY_LENGTH = 60;

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

    @Override
    public PageDataVo<MiniMessageVo> pageForUser(MiniMessageBo bo, Long userId) {
        long current = ObjectUtil.defaultIfNull(bo.getCurrent(), 1L);
        long size = ObjectUtil.defaultIfNull(bo.getSize(), 20L);
        validatePaging(current, size);
        Integer domain = bo.getMsgDomain();
        if (ObjectUtil.isNotNull(domain) && domainUnknown(domain)) {
            throw new JbkException("消息领域不合法");
        }
        // MySQL DESC 排序 NULL 天然排尾，恰与 mock 冻结口径（无发送时间排最后）一致；
        // ID 兜底次序保证同秒消息分页稳定不漂移
        Page<WsMessage> page = page(new Page<>(current, size), Wrappers.lambdaQuery(WsMessage.class)
                .eq(WsMessage::getUserId, userId)
                .eq(ObjectUtil.isNotNull(domain), WsMessage::getMsgDomain, domain)
                .orderByDesc(WsMessage::getSendTime)
                .orderByDesc(WsMessage::getId));
        List<MiniMessageVo> rows = page.getRecords().stream().map(this::toVo).toList();
        return PageDataVo.getPageData(rows, page.getTotal());
    }

    @Override
    public MiniMessageVo detailForUser(Long messageId, Long userId) {
        return toVo(ownMessage(messageId, userId));
    }

    @Override
    public PageDataVo<WsMessage> pageRecords(com.jbk.tool.data.message.bo.WsMessageRecordBo bo) {
        long current = ObjectUtil.defaultIfNull(bo.getCurrent(), 1L);
        long size = ObjectUtil.defaultIfNull(bo.getSize(), 20L);
        validatePaging(current, size);
        Page<WsMessage> page = page(new Page<>(current, size), Wrappers.lambdaQuery(WsMessage.class)
                .eq(ObjectUtil.isNotNull(bo.getUserId()), WsMessage::getUserId, bo.getUserId())
                .eq(ObjectUtil.isNotNull(bo.getMsgDomain()), WsMessage::getMsgDomain, bo.getMsgDomain())
                .eq(ObjectUtil.isNotNull(bo.getSendStatus()), WsMessage::getSendStatus, bo.getSendStatus())
                .orderByDesc(WsMessage::getSendTime)
                .orderByDesc(WsMessage::getId));
        return PageDataVo.getPageData(page.getRecords(), page.getTotal());
    }

    @Override
    public void markRead(Long messageId, Long userId) {
        WsMessage message = ownMessage(messageId, userId);
        if (ObjectUtil.equal(message.getReadFlag(), 1)) {
            // 幂等：重复标记零变化仍成功（C02 点击即标，用户回访同一条不该报错）
            return;
        }
        // 已读时刻是用户动作而非原业务动作：UPDATE_TIME 用当下时钟，不沿用 sendTime
        boolean updated = update(Wrappers.lambdaUpdate(WsMessage.class)
                .eq(WsMessage::getId, messageId)
                .eq(WsMessage::getUserId, userId)
                .eq(WsMessage::getReadFlag, 0)
                .set(WsMessage::getReadFlag, 1)
                .set(WsMessage::getUpdateBy, userId)
                .set(WsMessage::getUpdateTime, DateUtils.time()));
        // 并发下另一端已标过：条件不中但目标态已达成，同样视为成功
        if (!updated && ObjectUtil.notEqual(ownMessage(messageId, userId).getReadFlag(), 1)) {
            throw new JbkException("消息已读标记失败");
        }
    }

    @Override
    public boolean markRetrySending(Long messageId) {
        return update(Wrappers.lambdaUpdate(WsMessage.class)
                .eq(WsMessage::getId, messageId)
                .eq(WsMessage::getSendStatus, MessageEnum.SendStatus.FAILED.getValue())
                .set(WsMessage::getSendStatus, MessageEnum.SendStatus.SENDING.getValue())
                .set(WsMessage::getUpdateTime, DateUtils.time()));
    }

    @Override
    public boolean completeRetry(Long messageId, boolean success) {
        String now = DateUtils.time();
        return update(Wrappers.lambdaUpdate(WsMessage.class)
                .eq(WsMessage::getId, messageId)
                .eq(WsMessage::getSendStatus, MessageEnum.SendStatus.SENDING.getValue())
                .set(WsMessage::getSendStatus, success
                        ? MessageEnum.SendStatus.DELIVERED.getValue()
                        : MessageEnum.SendStatus.FAILED.getValue())
                // 成功送达时发送时间取送达时刻；退回失败态不动 SEND_TIME
                .set(success, WsMessage::getSendTime, now)
                .set(WsMessage::getUpdateTime, now));
    }

    @Override
    public boolean degradeToInApp(Long messageId) {
        String now = DateUtils.time();
        return update(Wrappers.lambdaUpdate(WsMessage.class)
                .eq(WsMessage::getId, messageId)
                .eq(WsMessage::getSendStatus, MessageEnum.SendStatus.FAILED.getValue())
                .set(WsMessage::getMsgChannel, MessageEnum.MsgChannel.IN_APP.getValue())
                .set(WsMessage::getSendStatus, MessageEnum.SendStatus.DELIVERED.getValue())
                // 降级即此刻站内可见：SEND_TIME 同源于降级动作时刻
                .set(WsMessage::getSendTime, now)
                .set(WsMessage::getUpdateTime, now));
    }

    /**
     * 分页守卫（本类两个分页面共用，报错文案单一来源）：
     * size 上限 100；current 封顶防 (current-1)*size 溢出为负 LIMIT（E2E-06 同口径）。
     */
    private void validatePaging(long current, long size) {
        if (current <= 0 || size <= 0) {
            throw new JbkException("分页参数必须大于 0");
        }
        if (size > 100) {
            throw new JbkException("页大小超出上限 100");
        }
        if (current > 100_000) {
            throw new JbkException("页码超出上限");
        }
    }

    /** 本人消息读取：ID+USER_ID 双条件，越权与不存在同文案。 */
    private WsMessage ownMessage(Long messageId, Long userId) {
        if (ObjectUtil.isNull(messageId)) {
            throw new JbkException(NOT_FOUND);
        }
        WsMessage message = getById(messageId);
        if (ObjectUtil.isNull(message) || ObjectUtil.notEqual(message.getUserId(), userId)) {
            throw new JbkException(NOT_FOUND);
        }
        return message;
    }

    private MiniMessageVo toVo(WsMessage message) {
        return new MiniMessageVo()
                .setMessageId(message.getId())
                .setMsgDomain(message.getMsgDomain())
                .setMsgTitle(message.getMsgTitle())
                .setSummary(StrUtil.brief(message.getMsgContent(), SUMMARY_LENGTH))
                .setMsgContent(message.getMsgContent())
                .setMsgChannel(message.getMsgChannel())
                .setSendStatus(message.getSendStatus())
                .setSendTime(message.getSendTime())
                .setReadFlag(message.getReadFlag())
                .setObjectType(message.getObjectType())
                .setObjectId(message.getObjectId());
    }

    private boolean domainUnknown(int value) {
        for (MessageEnum.MsgDomain item : MessageEnum.MsgDomain.values()) {
            if (item.getValue() == value) {
                return false;
            }
        }
        return true;
    }
}
