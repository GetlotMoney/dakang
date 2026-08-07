package com.jbk.serve.service.device;

import com.jbk.tool.data.device.po.WsCommand;

/**
 * ACK 回执的事务边界（B18）。
 *
 * <p>单独成一个 Bean 是必需的而不是风格选择：ACK 处理入口在 {@code WsCommandServiceImpl}，
 * 同类内部自调用不经过 Spring 代理，{@code @Transactional} 会静默失效，指令与订单又会退回
 * 各自独立提交。指令状态、关联订单状态与可靠状态事件必须同生共死——只推指令不推订单会留下
 * 「command=FAILED、order=PAID」这种无人认领的悬挂态，超时扫描也够不着它；只改状态不落审计
 * 则让这次状态变化在追溯里彻底消失。</p>
 *
 * <p>本接口<b>不接收</b> {@code WsCommand} 入参：调用方手里的对象是入口处读到的旧快照且可变，
 * 拿它当 orderId/deviceId/cmdType 的权威来源等于把核验建在沙子上。事务内按 cmdNo 自行
 * 当前读并锁定指令行，全部判定基于锁内值。</p>
 *
 * @author dakang
 * @since 2026-08-03
 */
public interface IWsCommandAckTxService {

    /**
     * 在同一事务内落 ACK 的三件事：指令前态 CAS 推进、关联取水订单联动、可靠状态事件。
     *
     * <p>成功回执的唯一形态是非空且忽略大小写等于 {@code accepted}；缺失、空白与其余取值
     * （rejected/busy/failed 等）一律按失败收口，不设兼容开关。</p>
     *
     * <p>出水指令（cmdType=1）额外闭合订单共键：订单存在且未删除、类型为取水、
     * 设备与指令一致、{@code CMD_ID} 指向本指令、订单状态属于显式白名单。任一不符 fail-closed 回滚。
     * 停止出水等其他指令类型按自身语义只推进指令状态，不联动取水订单。</p>
     *
     * @param reportDeviceId 上报设备ID（错设备 ACK 不改任何状态）
     * @param cmdNo          平台指令号
     * @param ackCode        设备回执码（可为 null/空白）
     * @param ackTs          设备回执时间；缺失或非法回退服务器时间并留痕
     * @return 已推进状态的指令行（供批次聚合使用）；未推进（未知指令/错设备/前态不符）返回 null
     */
    WsCommand applyAck(Long reportDeviceId, String cmdNo, String ackCode, String ackTs);
}
