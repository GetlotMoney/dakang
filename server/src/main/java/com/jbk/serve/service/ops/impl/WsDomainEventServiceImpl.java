package com.jbk.serve.service.ops.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jbk.serve.mapper.ops.WsDomainEventMapper;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.ApiEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.ops.po.WsDomainEvent;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import com.jbk.tool.utils.satoken.StpKit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 领域事件服务实现
 *
 * @author dakang
 * @since 2026-07-12
 */
@Slf4j
@Service
public class WsDomainEventServiceImpl extends ServiceImpl<WsDomainEventMapper, WsDomainEvent> implements IWsDomainEventService {

    /**
     * 审计关键性分界（E2E-03 验收 P1-3）：
     * 关键履约/裁决等「状态变化」审计必须走可靠路径，写入失败向调用方抛出，绝不静默——
     * {@link #recordReliableOnce}/{@link #recordReliableOnceAs} 与业务写入同一事务
     * （业务回滚审计必须一并消失，杜绝「业务失败、审计称成功」的幽灵记录），
     * 撞业务幂等键时读回既有行核验语义一致性（见 {@link #saveReliableOnce}）；
     * 拒绝/失败证据必须独立于主事务存活（REQUIRES_NEW）：带幂等键走
     * {@link #recordReliableOnceIndependent}（撞键按已留痕处理），无幂等键走 {@link #recordReliable}。
     * 本方法与 {@link #recordAs} 仅限「非关键展示型」留痕（幂等核验备注、举证登记等），
     * 写入失败只记错误日志不阻断主业务（{@link #saveQuietly}）。
     */
    @Override
    public void record(OpsEnum.EventType eventType, String eventKey, Object oldValue, Object newValue) {
        WsDomainEvent event = buildEvent(eventType, eventKey, oldValue, newValue);
        fillActorFromSession(event);
        saveQuietly(event);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordReliableInTx(OpsEnum.EventType eventType, String eventKey, Object oldValue, Object newValue) {
        // REQUIRED：与业务写入同生共死——业务回滚审计随之消失（无幽灵），写入失败抛出共同回滚
        WsDomainEvent event = buildEvent(eventType, eventKey, oldValue, newValue);
        fillActorFromSession(event);
        if (!save(event)) {
            throw new com.jbk.tool.exception.JbkException("成功状态审计写入失败，业务一并回滚");
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordReliable(OpsEnum.EventType eventType, String eventKey, Object oldValue, Object newValue) {
        WsDomainEvent event = buildEvent(eventType, eventKey, oldValue, newValue);
        fillActorFromSession(event);
        if (!save(event)) {
            throw new IllegalStateException("可靠领域事件写入失败");
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void recordReliableOnce(OpsEnum.EventType eventType, String eventKey, String bizIdempotencyKey,
                                   Object oldValue, Object newValue) {
        WsDomainEvent event = buildEvent(eventType, eventKey, oldValue, newValue);
        event.setBizIdempotencyKey(bizIdempotencyKey);
        fillActorFromSession(event);
        saveReliableOnce(event, bizIdempotencyKey);
    }

    /**
     * 拒绝/失败证据 + 幂等键（P1-C 范围拒绝等）：REQUIRES_NEW 独立提交，主事务回滚后证据仍存活。
     * 撞键不读回核验：同一请求跨层（编排层预检拒 + 事务内复检拒）与跨次重试共用同一键、
     * payload 携带各自判定时间戳，读回比对必然不一致；证据不驱动业务走向，先到的一条即有效留痕。
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordReliableOnceIndependent(OpsEnum.EventType eventType, String eventKey,
                                              String bizIdempotencyKey, Object oldValue, Object newValue) {
        WsDomainEvent event = buildEvent(eventType, eventKey, oldValue, newValue);
        event.setBizIdempotencyKey(bizIdempotencyKey);
        fillActorFromSession(event);
        try {
            if (!save(event)) {
                throw new IllegalStateException("可靠领域事件写入失败");
            }
        } catch (DuplicateKeyException already) {
            // 独立事务内除本条 INSERT 无其他写入，语句级失败后按空事务提交即可
            log.info("拒绝证据幂等命中，不重复落痕 bizKey={} eventKey={}", bizIdempotencyKey, event.getEventKey());
        }
    }

    @Override
    public void recordByDevice(OpsEnum.EventType eventType, String eventKey, Object oldValue, Object newValue) {
        WsDomainEvent event = buildEvent(eventType, eventKey, oldValue, newValue);
        event.setActorPortal(OpsEnum.ActorPortal.DEVICE.getValue());
        event.setActorRole(OpsEnum.ActorPortal.DEVICE.getDesc());
        saveQuietly(event);
    }

    /** 展示型 + 显式端口（P1-3）：身份由受信任领域服务传入，不走会话推断；失败 quietly。 */
    @Override
    public void recordAs(OpsEnum.ActorPortal actorPortal, Long actorId, OpsEnum.EventType eventType,
                         String eventKey, Object oldValue, Object newValue) {
        WsDomainEvent event = buildEvent(eventType, eventKey, oldValue, newValue);
        fillActorExplicit(event, actorPortal, actorId);
        saveQuietly(event);
    }

    /** 关键状态变化 + 显式端口（P1-3）：与业务同事务 + 幂等键，写入失败必须抛出。 */
    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void recordReliableOnceAs(OpsEnum.ActorPortal actorPortal, Long actorId, OpsEnum.EventType eventType,
                                     String eventKey, String bizIdempotencyKey, Object oldValue, Object newValue) {
        WsDomainEvent event = buildEvent(eventType, eventKey, oldValue, newValue);
        event.setBizIdempotencyKey(bizIdempotencyKey);
        fillActorExplicit(event, actorPortal, actorId);
        saveReliableOnce(event, bizIdempotencyKey);
    }

    /**
     * 可靠落痕共用体：写入失败抛出（fail-closed）；撞业务幂等键不等于幂等成功——
     * 必须读回既有行核验语义一致（同 type/key/actor/portal 且 payload 的 old/new 相同），
     * 否则键可能被伪造事件抢占，盲信撞键会让攻击者用一条假审计顶掉真实履约记录。
     * DuplicateKeyException 必须在本方法内消化：让它穿出 @Transactional 代理边界，
     * 共享的业务事务会被标记 rollback-only，幂等命中反而变成整单失败。
     */
    private void saveReliableOnce(WsDomainEvent event, String bizIdempotencyKey) {
        try {
            if (!save(event)) {
                throw new IllegalStateException("可靠领域事件写入失败");
            }
        } catch (DuplicateKeyException already) {
            verifyOccupiedKeySameSemantics(event, bizIdempotencyKey);
        }
    }

    /** 撞键读回核验：行不在或语义不一致一律 fail-closed（业务整体回滚），一致才算幂等命中。 */
    private void verifyOccupiedKeySameSemantics(WsDomainEvent attempted, String bizIdempotencyKey) {
        WsDomainEvent existing = getOne(Wrappers.lambdaQuery(WsDomainEvent.class)
                .eq(WsDomainEvent::getBizIdempotencyKey, bizIdempotencyKey), false);
        if (ObjectUtil.isNull(existing)) {
            // 撞键却读不回行（并发写入未提交或行被外力清除）：无从核验，宁可回滚业务
            throw new JbkException("审计幂等键被占用且无法读回核验：" + bizIdempotencyKey);
        }
        boolean consistent = ObjectUtil.equal(existing.getEventType(), attempted.getEventType())
                && ObjectUtil.equal(existing.getEventKey(), attempted.getEventKey())
                && ObjectUtil.equal(existing.getActorId(), attempted.getActorId())
                && ObjectUtil.equal(existing.getActorPortal(), attempted.getActorPortal())
                && payloadSemanticsMatch(existing.getEventPayload(), attempted.getEventPayload());
        if (!consistent) {
            throw new JbkException("审计幂等键被占用且语义不一致，拒绝落痕：" + bizIdempotencyKey);
        }
        log.info("可靠领域事件幂等命中且语义一致，不重复落痕 bizKey={} eventKey={}",
                bizIdempotencyKey, attempted.getEventKey());
    }

    /** payload 只比 old/new 语义值：time 是每次落痕时刻，合法重放必然不同，不参与比较。 */
    private boolean payloadSemanticsMatch(String existingPayload, String attemptedPayload) {
        try {
            JSONObject existing = JSONUtil.parseObj(existingPayload);
            JSONObject attempted = JSONUtil.parseObj(attemptedPayload);
            return ObjectUtil.equal(semanticText(existing.get("old")), semanticText(attempted.get("old")))
                    && ObjectUtil.equal(semanticText(existing.get("new")), semanticText(attempted.get("new")));
        } catch (Exception unparseable) {
            // 既有 payload 解析不了就证明不了同义，按不一致处理
            return false;
        }
    }

    private String semanticText(Object value) {
        return ObjectUtil.isNull(value) ? null : String.valueOf(value);
    }

    /** 显式身份填充（P1-3）：portal/role/actorId 全部采信领域服务结论，杜绝配送员动作被记成 USER。 */
    private void fillActorExplicit(WsDomainEvent event, OpsEnum.ActorPortal actorPortal, Long actorId) {
        event.setActorId(actorId);
        event.setActorPortal(actorPortal.getValue());
        event.setActorRole(actorPortal.getDesc());
    }

    private WsDomainEvent buildEvent(OpsEnum.EventType eventType, String eventKey, Object oldValue, Object newValue) {
        JSONObject payload = new JSONObject();
        payload.set("old", toJsonValue(oldValue));
        payload.set("new", toJsonValue(newValue));
        payload.set("time", DateUtils.time());
        WsDomainEvent event = new WsDomainEvent();
        event.setEventType(eventType.getValue());
        event.setEventKey(eventKey);
        event.setEventPayload(payload.toString());
        // 白名单置位单一来源（E2E-07 / REQ-083）：可订阅类型落库即标记；
        // 消费翻转 CONSUMED_FLAG 明确归二期，本期白名单只供只读订阅查询
        event.setWhitelistFlag(WHITELIST_EVENT_TYPES.contains(eventType.getValue())
                ? ApiEnum.Flag.YES.value() : ApiEnum.Flag.NO.value());
        event.setConsumedFlag(ApiEnum.Flag.NO.value());
        return event;
    }

    @Override
    public com.jbk.tool.data.PageDataVo<WsDomainEvent> pageWhitelist(Long current, Long size, Integer eventType) {
        long page = ObjectUtil.defaultIfNull(current, 1L);
        long pageSize = ObjectUtil.defaultIfNull(size, 20L);
        if (page <= 0 || pageSize <= 0 || pageSize > 100 || page > 100_000) {
            throw new JbkException("分页参数不合法");
        }
        // 类型筛选必须在白名单内：非白名单类型直接拒绝，不泄露其事件是否存在
        if (ObjectUtil.isNotNull(eventType) && !WHITELIST_EVENT_TYPES.contains(eventType)) {
            throw new JbkException("事件类型不在可订阅白名单内");
        }
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<WsDomainEvent> result =
                page(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, pageSize),
                        Wrappers.lambdaQuery(WsDomainEvent.class)
                                .eq(WsDomainEvent::getWhitelistFlag, ApiEnum.Flag.YES.value())
                                .eq(ObjectUtil.isNotNull(eventType), WsDomainEvent::getEventType, eventType)
                                .orderByDesc(WsDomainEvent::getId));
        return com.jbk.tool.data.PageDataVo.getPageData(result.getRecords(), result.getTotal());
    }

    private Object toJsonValue(Object value) {
        if (ObjectUtil.isNull(value)) {
            return null;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return JSONUtil.parse(JSONUtil.toJsonStr(value));
    }

    /**
     * 从登录会话推断账号来源。
     * 当前通用接口只能可靠区分后台、C 端用户和设备；机主/配送等能力动作必须改走
     * {@link #recordAs}/{@link #recordReliableOnceAs} 由受信任的领域服务显式传入 actor portal
     * （P1-3 已落地配送域），禁止依据客户端自报的身份请求头写审计归属。
     */
    private void fillActorFromSession(WsDomainEvent event) {
        try {
            if (StpKit.MANAGE.isLogin()) {
                event.setActorId(StpKit.MANAGE.getLoginIdAsLong());
                event.setActorPortal(OpsEnum.ActorPortal.MANAGE.getValue());
                Object name = StpKit.MANAGE.getExtra(StpKit.EXTRA_NAME);
                event.setActorRole(ObjectUtil.isNotNull(name) ? String.valueOf(name) : OpsEnum.ActorPortal.MANAGE.getDesc());
                return;
            }
            if (StpKit.KH_USER.isLogin()) {
                event.setActorId(StpKit.KH_USER.getLoginIdAsLong());
                // 同一小程序账号默认记录为用户端；具体业务能力应由领域动作显式覆盖。
                event.setActorPortal(OpsEnum.ActorPortal.USER.getValue());
                event.setActorRole(OpsEnum.ActorPortal.USER.getDesc());
                return;
            }
        } catch (Exception e) {
            log.warn("领域事件身份上下文推断失败，按系统触发记录：{}", e.getMessage());
        }
        event.setActorPortal(OpsEnum.ActorPortal.SYSTEM.getValue());
        event.setActorRole(OpsEnum.ActorPortal.SYSTEM.getDesc());
    }

    /** 仅限非关键展示型审计：写入失败不阻断主业务但留错误日志；关键状态变化禁止走本路径（见 record 注释）。 */
    private void saveQuietly(WsDomainEvent event) {
        try {
            save(event);
        } catch (Exception e) {
            log.error("领域事件写入失败 eventType={} eventKey={}", event.getEventType(), event.getEventKey(), e);
        }
    }
}
