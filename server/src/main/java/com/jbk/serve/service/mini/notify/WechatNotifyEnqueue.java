package com.jbk.serve.service.mini.notify;

import cn.hutool.json.JSONObject;
import com.jbk.serve.mapper.mini.WsWechatNotifyOutboxMapper;
import com.jbk.tool.consts.mini.WechatNotifyEnum;
import com.jbk.tool.data.mini.po.WsWechatNotifyOutbox;
import com.jbk.tool.utils.DateUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订阅通知登记：业务事务内只写 outbox 表，不产生网络流量。
 *
 * <p>{@link #enqueue} 刻意不加 {@code @Transactional}——通知必须随业务事务一起提交/回滚
 * （否则会出现「订单没成、用户却收到成功通知」）；失败路径用 {@link #enqueueIndependent}
 * （REQUIRES_NEW），两者方向相反、不可互换。payload 由调用方在事务内冻结快照，
 * Worker 不反查（发送时业务状态可能已变）。撞唯一键=已登记，直接返回；其余插入异常不吞：
 * {@code ws_wechat_notify_outbox} 漏建会让八个业务动作整体失败，属部署契约
 * （SchemaParityTest 看守），不许改成静默吞掉。</p>
 *
 * @author dakang
 * @since 2026-08-12
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WechatNotifyEnqueue {

    /** 系统登记：这条记录不是任何员工写的。 */
    private static final long SYSTEM_ACTOR = 0L;

    private final WsWechatNotifyOutboxMapper outboxMapper;

    /**
     * 在当前业务事务内登记一条待发通知。
     *
     * @param eventType  事件类型
     * @param objectType 业务对象类型
     * @param objectNo   业务对象编号（订单号/任务号等）
     * @param receiverUserId 收件人；为 null 直接跳过（没有收件人的通知没有意义）
     * @param payload    模板数据快照；调用方在事务内冻结好，Worker 不再反查
     */
    public void enqueue(WechatNotifyEnum.EventType eventType,
                        WechatNotifyEnum.BizObjectType objectType,
                        String objectNo,
                        Long receiverUserId,
                        JSONObject payload) {
        if (eventType == null || objectType == null || objectNo == null || receiverUserId == null) {
            // 入参不全静默跳过：通知绝不能把成功的业务动作变成失败
            log.warn("订阅通知登记入参不完整，已跳过 event={} objectType={} objectNo={} receiver={}",
                    eventType, objectType, objectNo, receiverUserId);
            return;
        }
        String key = String.join(":", WechatNotifyEnum.NOTIFY_KEY_PREFIX,
                eventType.name(), objectType.name(), objectNo);
        String now = DateUtils.time();
        WsWechatNotifyOutbox row = new WsWechatNotifyOutbox()
                .setEventType(eventType.name())
                .setBizObjectType(objectType.name())
                .setBizObjectNo(objectNo)
                .setBizNotifyKey(key)
                .setReceiverUserId(receiverUserId)
                .setPayloadSnap(payload == null ? null : payload.toString())
                .setProcessingStatus(WechatNotifyEnum.ProcessingStatus.PENDING.getValue())
                .setRetryCount(0);
        row.setCreateBy(SYSTEM_ACTOR);
        row.setCreateTime(now);
        row.setUpdateBy(SYSTEM_ACTOR);
        row.setUpdateTime(now);
        try {
            outboxMapper.insert(row);
        }
        catch (DuplicateKeyException e) {
            // 撞键即已登记：重放/补偿/并发都会走到这里，属正常路径，不是错误
            log.debug("订阅通知已登记，跳过重复入队 key={}", key);
        }
    }

    /**
     * 独立事务（REQUIRES_NEW）登记：专供主事务正在回滚的失败路径（自动补货创单失败），
     * 通知必须在回滚后存活；与 {@link #enqueue} 方向相反、不可互换。
     * 登记失败一律吞掉——失败路径不能被通知变成另一种失败。
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void enqueueIndependent(WechatNotifyEnum.EventType eventType,
                                   WechatNotifyEnum.BizObjectType objectType,
                                   String objectNo,
                                   Long receiverUserId,
                                   JSONObject payload) {
        try {
            enqueue(eventType, objectType, objectNo, receiverUserId, payload);
        }
        catch (Exception e) {
            log.error("失败路径订阅通知登记失败 event={} objectNo={}", eventType, objectNo, e);
        }
    }
}
