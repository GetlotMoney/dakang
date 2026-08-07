package com.jbk.serve.service.device;

import com.jbk.tool.data.device.po.WsCommand;

/**
 * 出水指令准备的事务边界（B20）。
 *
 * <p>为什么必须是一个真实事务而不是「再读一次」：设备、出水口、故障字典、订单、CMD_ID 抢占
 * 分处五条语句，各自自动提交时它们属于五个不同的判定时点。无论最后一次读放得多靠后，
 * 读完到抢占之间仍留着一条缝——迁站、改绑、改水种、订单被并发转异常都能从那里挤进来。
 * 把读与写收进同一个事务、并让抢占的 CAS 复核共键，缝才真正闭合。</p>
 *
 * <p>MQTT 下发严禁进事务：网络 IO 卡住会把设备与订单的行锁一起拖住。本接口只负责在事务内
 * 定稿「发什么、发给谁」，提交成功后由调用方执行 publish。</p>
 *
 * @author dakang
 * @since 2026-08-03
 */
public interface IDispenseDispatchTxService {

    /**
     * 准备结果。
     *
     * @param commandId 有效出水指令ID
     * @param created   true=本次新建并抢占成功（调用方须继续下发）；false=订单此前已绑定指令（幂等命中，不再下发）
     * @param command   新建的指令行（created=false 时为 null）
     * @param deviceNo  事务内当前读到的权威设备编号，publish 目标（created=false 时为 null）
     */
    record Prepared(Long commandId, boolean created, WsCommand command, String deviceNo) {
    }

    /**
     * 事务内完成：锁设备/出水口/故障字典/水站 → 当前读订单 → 核订单类型与状态 → 核站-设备-出水口
     * 三共键 → 核冻结水种 → 判设备可用性 → 落 PENDING 指令 → CMD_ID 精确 CAS 抢占。
     *
     * <p>抢占影响行数不为 1 时整体回滚，刚建的指令一并消失——不留作废垃圾行。</p>
     *
     * @param orderId 已扣款的取水订单ID
     * @return 准备结果
     * @throws com.jbk.tool.exception.JbkException 订单状态/共键/水种/可用性任一不符，或抢占失败
     */
    Prepared prepare(Long orderId);
}
