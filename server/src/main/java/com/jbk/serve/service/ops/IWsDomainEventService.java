package com.jbk.serve.service.ops;

import com.baomidou.mybatisplus.extension.service.IService;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.ops.po.WsDomainEvent;

/**
 * 领域事件服务（状态级审计统一入口）
 * <p>铁律：订单/命令/ACK/配送/工单等状态变化必须经由本服务落事件表（REQ-024/051），
 * 操作者身份上下文自动从当前会话推断，系统触发写 SYSTEM。</p>
 *
 * @author dakang
 * @since 2026-07-12
 */
public interface IWsDomainEventService extends IService<WsDomainEvent> {

    /**
     * 可订阅事件类型白名单（E2E-07 包B / REQ-083）：外部编排只开放业务状态变化族，
     * 资金类与设备指令内部态不开放。置位在事件落库时单点发生（见 impl.buildEvent），
     * 历史存量事件不回刷。
     */
    java.util.Set<Integer> WHITELIST_EVENT_TYPES = java.util.Set.of(
            OpsEnum.EventType.ORDER_STATUS.getValue(),
            OpsEnum.EventType.DEVICE_STATUS.getValue(),
            OpsEnum.EventType.ALARM_CREATED.getValue(),
            OpsEnum.EventType.WORK_ORDER_STATUS.getValue(),
            OpsEnum.EventType.DELIVERY_NODE.getValue(),
            OpsEnum.EventType.ALARM_RECOVERED.getValue());

    /**
     * 白名单事件只读分页（管理端；为 n8n 订阅预演，消费翻转 CONSUMED_FLAG 明确归二期）。
     *
     * @param current   页码（≥1）
     * @param size      页大小（1~100）
     * @param eventType 可选类型筛选（必须在白名单内，否则拒绝——不泄露非白名单事件存在性）
     */
    com.jbk.tool.data.PageDataVo<WsDomainEvent> pageWhitelist(Long current, Long size, Integer eventType);

    /**
     * 记录状态变化事件（身份上下文自动取自当前登录会话；无会话=系统触发）
     *
     * @param eventType 事件类型
     * @param eventKey  业务键（订单号/设备编号/指令号/工单号）
     * @param oldValue  旧值（对象将序列化为 JSON，可为 null）
     * @param newValue  新值（对象将序列化为 JSON）
     */
    void record(OpsEnum.EventType eventType, String eventKey, Object oldValue, Object newValue);

    /**
     * 记录必须独立提交的可靠异常证据（REQUIRES_NEW）；写入失败必须抛出，禁止静默吞掉。
     */
    void recordReliable(OpsEnum.EventType eventType, String eventKey, Object oldValue, Object newValue);

    /**
     * 成功状态审计（REQUIRED 同事务、无幂等键）：业务回滚时审计一并消失，写入失败抛出。
     * 适用于可重复发生的成功状态流转；拒绝/失败证据走 REQUIRES_NEW 的 {@link #recordReliable}。
     */
    void recordReliableInTx(OpsEnum.EventType eventType, String eventKey, Object oldValue, Object newValue);

    /**
     * 正向状态可靠事件：业务幂等键全库最多一条，REQUIRED 与业务同事务。命中
     * {@code uk_domain_event_biz_key} 不盲信：读回既有行核验语义一致（time 除外）才幂等返回，
     * 读不回或不一致 fail-closed 抛出；拒绝/失败证据禁走本方法，改用 {@link #recordReliableOnceIndependent}。
     *
     * @param bizIdempotencyKey 稳定业务幂等键（max64），由调用方按既定业务键派生
     */
    void recordReliableOnce(OpsEnum.EventType eventType, String eventKey, String bizIdempotencyKey,
                            Object oldValue, Object newValue);

    /**
     * 拒绝/失败证据：业务幂等键全库最多一条，REQUIRES_NEW 独立提交——主事务回滚正是拒绝的
     * 业务结果，证据必须存活（与 {@link #recordReliableOnce} 的分界即 E2E-03 复审边界）。
     * 撞键按「已留过痕」幂等处理、不读回核验（payload 含时间戳，重试天然不同报文）；
     * 非撞键的写入失败必须抛出。
     */
    void recordReliableOnceIndependent(OpsEnum.EventType eventType, String eventKey, String bizIdempotencyKey,
                                       Object oldValue, Object newValue);

    /**
     * 记录设备触发的事件（心跳/回执等设备上行场景，portal=设备）
     */
    void recordByDevice(OpsEnum.EventType eventType, String eventKey, Object oldValue, Object newValue);

    /**
     * 以显式操作端口记录展示型事件（E2E-03 验收 P1-3）：多能力账号会话推断只能落 USER，
     * portal/actorId 须由受信任的领域服务按能力投影传入，禁止客户端自报；写入失败不阻断主业务。
     *
     * @param actorPortal 操作端口（领域服务据能力校验结论传入，非客户端自报）
     * @param actorId     操作者用户 ID（与会话 userId 同源，显式传入以便无会话环境可测）
     */
    void recordAs(OpsEnum.ActorPortal actorPortal, Long actorId, OpsEnum.EventType eventType,
                  String eventKey, Object oldValue, Object newValue);

    /**
     * 语义同 {@link #recordReliableOnce}、身份同 {@link #recordAs}（E2E-03 P1-3）；
     * 履约节点等关键状态变化必须在业务事务内调用。
     */
    void recordReliableOnceAs(OpsEnum.ActorPortal actorPortal, Long actorId, OpsEnum.EventType eventType,
                              String eventKey, String bizIdempotencyKey, Object oldValue, Object newValue);
}
