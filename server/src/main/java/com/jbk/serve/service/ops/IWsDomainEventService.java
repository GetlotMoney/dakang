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
     * 可订阅事件类型白名单（E2E-07 包B / REQ-083）：n8n 等外部编排只允许订阅
     * 业务状态变化族——订单/设备/告警/工单/配送节点/告警恢复。
     * 资金类（支付/分账/售后）与设备指令内部态明确不开放：资金事实归 E2E-08 对账链，
     * 指令细节属设备安全面。置位发生在事件落库时（单一来源，见 impl.buildEvent）；
     * 历史存量事件不回刷——白名单语义自本链启用起生效。
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
     * 记录必须独立提交的可靠异常证据。
     *
     * <p>实现使用 REQUIRES_NEW；写入失败必须向调用方抛出，禁止被主事务回滚或静默吞掉。</p>
     */
    void recordReliable(OpsEnum.EventType eventType, String eventKey, Object oldValue, Object newValue);

    /**
     * 记录按业务幂等键全库最多一条、且与业务写入同一事务的<b>正向状态</b>可靠领域事件
     * （配送创单、申诉登记/裁决等）。
     *
     * <p>实现使用 REQUIRED：关键状态变化必须由业务事务内调用，审计与业务同生共死——
     * 业务回滚时审计一并消失，不留「业务失败、审计称成功」的幽灵记录。命中
     * {@code uk_domain_event_biz_key} 唯一键不盲信为已落痕：读回既有行核验语义一致
     * （type/key/actor/portal 与 payload 的 old/new，time 除外），一致才幂等返回；
     * 行读不回或语义不一致必须抛出（fail-closed，业务整体回滚）；其余写入失败同样必须
     * 向调用方抛出，禁止静默吞掉。拒绝/失败证据禁走本方法——改用
     * {@link #recordReliableOnceIndependent}。</p>
     *
     * @param bizIdempotencyKey 稳定业务幂等键（max64），由调用方按既定业务键派生
     */
    void recordReliableOnce(OpsEnum.EventType eventType, String eventKey, String bizIdempotencyKey,
                            Object oldValue, Object newValue);

    /**
     * 记录按业务幂等键全库最多一条、且<b>独立于业务事务</b>提交的拒绝/失败证据
     * （P1-C 范围拒绝审计等）。
     *
     * <p>实现使用 REQUIRES_NEW：拒绝证据记录的是「判定发生过」，主事务随后回滚正是拒绝的
     * 业务结果，证据必须存活，否则拒绝就成了无痕事件（与 {@link #recordReliableOnce} 的
     * 分界即 E2E-03 复审边界：正向状态审计同事务，REQUIRES_NEW 只用于独立的拒绝/失败证据）。
     * 证据 payload 含判定时间戳，同一请求跨层/跨次重试天然产生不同报文，因此撞
     * {@code uk_domain_event_biz_key} 唯一键按「已留过痕」幂等处理、不做读回核验——
     * 证据不驱动业务走向，先到的一条即有效留痕；非撞键的写入失败必须向调用方抛出。</p>
     */
    void recordReliableOnceIndependent(OpsEnum.EventType eventType, String eventKey, String bizIdempotencyKey,
                                       Object oldValue, Object newValue);

    /**
     * 记录设备触发的事件（心跳/回执等设备上行场景，portal=设备）
     */
    void recordByDevice(OpsEnum.EventType eventType, String eventKey, Object oldValue, Object newValue);

    /**
     * 以显式操作端口记录展示型事件（E2E-03 验收 P1-3）。
     *
     * <p>同一小程序账号叠加多种能力时，会话推断只能落 USER；配送员/机主等业务动作必须由
     * 受信任的领域服务按能力投影显式传入 portal 与 actorId（如配送动作记 COURIER），
     * 禁止依据客户端自报身份。与 {@link #record} 同为非关键审计：写入失败不阻断主业务。</p>
     *
     * @param actorPortal 操作端口（领域服务据能力校验结论传入，非客户端自报）
     * @param actorId     操作者用户 ID（与会话 userId 同源，由领域服务显式传入以便无会话环境可测）
     */
    void recordAs(OpsEnum.ActorPortal actorPortal, Long actorId, OpsEnum.EventType eventType,
                  String eventKey, Object oldValue, Object newValue);

    /**
     * 以显式操作端口记录关键状态变化审计（E2E-03 验收 P1-3）。
     *
     * <p>语义同 {@link #recordReliableOnce}（与业务同事务 + 业务幂等键，撞键读回核验，
     * 写入失败必须抛出），身份同 {@link #recordAs}（显式端口，不走会话推断）。
     * 履约节点等关键状态变化必须在业务事务内调用本方法。</p>
     */
    void recordReliableOnceAs(OpsEnum.ActorPortal actorPortal, Long actorId, OpsEnum.EventType eventType,
                              String eventKey, String bizIdempotencyKey, Object oldValue, Object newValue);
}
