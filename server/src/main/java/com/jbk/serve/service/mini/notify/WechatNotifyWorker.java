package com.jbk.serve.service.mini.notify;

import cn.hutool.core.util.StrUtil;
import com.jbk.serve.mapper.mini.WsWechatNotifyOutboxMapper;
import com.jbk.serve.mapper.user.WsUserIdentityMapper;
import com.jbk.tool.consts.mini.WechatNotifyEnum;
import com.jbk.tool.data.mini.po.WsWechatNotifyOutbox;
import com.jbk.serve.service.mini.auth.MiniUserIdentitySupport;
import com.jbk.tool.data.user.po.WsUser;
import com.jbk.tool.utils.DateUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 订阅通知发送 Worker：认领 → 外呼 → 按结果分流。刻意无 {@code @Transactional}——
 * 外呼 P99 秒级，放进事务等于把行锁持有到秒级并与业务写路径争锁。
 * 结果分流：成功→PROCESSED；模板未配/无授权/收件人不可用→PROCESSED+SKIP_REASON
 * （重试一万次也不会变，记失败会无限累积重试计数）；瞬时失败→RETRY_WAIT 指数退避；
 * 超上限→NEED_MANUAL（无限重试会把坏消息变成永久后台流量）。
 *
 * @author dakang
 * @since 2026-08-12
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WechatNotifyWorker {

    /** 单轮认领上限：够用且不会让一轮跑太久占住调度线程。 */
    private static final int SCAN_LIMIT = 50;

    /** 租约时长（秒）：需明显长于一次外呼的最坏耗时，否则会出现两个 Worker 同时发同一条。 */
    private static final int LEASE_SECONDS = 120;

    /** 重试上限；超过转人工。 */
    private static final int MAX_RETRY = 5;

    private final WsWechatNotifyOutboxMapper outboxMapper;
    private final WsUserIdentityMapper identityMapper;
    private final WechatNotifyProperties notifyProperties;
    private final WechatSubscribeSender sender;

    /** 关闭时 Worker 不注册；与 Pay-Sim 一类开关同一纪律：默认关，显式开。 */
    /**
     * 与真实 sender <b>同一个权威开关</b>：false/缺省时兜底 sender 在位（一律不发），
     * 此时 Worker 扫描只会把 outbox 行空转成失败，故整体休眠。
     * 绝不另设第二个开关——两个开关必然出现"开了 sender 却没开 Worker"的静默失效。
     */
    @Value("${wechat.notify.http-sender.enabled:false}")
    private boolean enabled;

    @Scheduled(fixedDelayString = "${wechat.notify.scan-interval-ms:30000}")
    public void scanAndSend() {
        if (!enabled) {
            return;
        }
        String now = DateUtils.time();
        List<Long> ids = outboxMapper.scanClaimableIds(now, SCAN_LIMIT);
        for (Long id : ids) {
            try {
                processOne(id);
            }
            catch (Exception e) {
                // 单条失败不能中断整轮：否则队首一条坏消息会让它后面的全部饿死
                log.error("订阅通知处理异常 id={}", id, e);
            }
        }
    }

    /** 处理单条。认领失败（被别人抢走或状态已变）直接返回，不是错误。 */
    public void processOne(Long id) {
        String now = DateUtils.time();
        String leaseUntil = DateUtils.plusSeconds(now, LEASE_SECONDS);
        if (outboxMapper.claimNotify(id, now, leaseUntil) != 1) {
            return;
        }
        WsWechatNotifyOutbox row = outboxMapper.selectById(id);
        if (row == null) {
            return;
        }

        WechatNotifyEnum.EventType eventType = parseEventType(row.getEventType());
        if (eventType == null) {
            // 事件类型认不出：代码与数据不同版本，重试也不会变
            outboxMapper.markNeedManual(id, "未知事件类型：" + row.getEventType(), DateUtils.time());
            return;
        }
        String templateId = notifyProperties.templateOf(eventType);
        if (templateId == null) {
            skip(id, WechatNotifyEnum.SkipReason.TEMPLATE_UNCONFIGURED);
            return;
        }
        String openid = usableOpenidOf(row.getReceiverUserId());
        if (openid == null) {
            skip(id, WechatNotifyEnum.SkipReason.RECEIVER_UNUSABLE);
            return;
        }

        // ↓↓↓ 外呼。此处之前没有任何未提交事务，之后也不持锁。
        WechatSubscribeSender.SendOutcome outcome =
                sender.send(openid, templateId, row.getPayloadSnap());

        String done = DateUtils.time();
        switch (outcome.kind()) {
            case SENT -> outboxMapper.markProcessed(id, null, done);
            case NO_SUBSCRIPTION -> skip(id, WechatNotifyEnum.SkipReason.NO_SUBSCRIPTION);
            case RETRYABLE -> {
                int next = (row.getRetryCount() == null ? 0 : row.getRetryCount()) + 1;
                if (next > MAX_RETRY) {
                    outboxMapper.markNeedManual(id,
                            "重试超过上限：" + StrUtil.maxLength(outcome.reason(), 400), done);
                }
                else {
                    // 指数退避：1/2/4/8/16 分钟。固定间隔在微信侧限流时会把限流延长
                    long backoffSeconds = 60L * (1L << (next - 1));
                    outboxMapper.markRetry(id, DateUtils.plusSeconds(done, backoffSeconds),
                            StrUtil.maxLength(outcome.reason(), 400), done);
                }
            }
            case PERMANENT -> outboxMapper.markNeedManual(id,
                    StrUtil.maxLength(outcome.reason(), 400), done);
            default -> outboxMapper.markNeedManual(id, "未知发送结果", done);
        }
    }

    /** 「已处理但没发出去」：终态，不重试，原因可查。 */
    private void skip(Long id, WechatNotifyEnum.SkipReason reason) {
        outboxMapper.markProcessed(id, reason.name(), DateUtils.time());
    }

    private WechatNotifyEnum.EventType parseEventType(String raw) {
        for (WechatNotifyEnum.EventType type : WechatNotifyEnum.EventType.values()) {
            if (type.name().equals(raw)) {
                return type;
            }
        }
        return null;
    }

    /**
     * 取收件人 openid：账号必须当下仍可用。
     *
     * <p>登记与发送之间可能隔着重试与退避，期间账号可能已被停用或注销。
     * 不复查就会往一个已经不该收到消息的人那里发。</p>
     */
    /**
     * 可发送的 openid；不可用一律返回 null（调用方记 RECEIVER_UNUSABLE，绝不外呼）。
     * 可用性判据复用 {@link MiniUserIdentitySupport#isUsable}——停用/注销/逻辑删除三态
     * 只此一份判据，Worker 不得自己再写一遍（漏一个字段=已封账号仍收到通知）。
     */
    private String usableOpenidOf(Long userId) {
        if (userId == null) {
            return null;
        }
        WsUser user = identityMapper.selectByIdIncludingDeleted(userId);
        if (!MiniUserIdentitySupport.isUsable(user)) {
            return null;
        }
        return StrUtil.blankToDefault(user.getWechatXcxOpenid(), null);
    }
}
