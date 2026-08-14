package com.jbk.serve.service.trade;

import com.jbk.tool.data.mini.vo.ScanSessionInfo;
import com.jbk.tool.data.trade.po.WsOrder;

/**
 * 订单资金事务服务：把「原子扣卡 → AFTER 快照 → 落订单 → 落流水」封装在单个 @Transactional 边界内，
 * 与编排逻辑（幂等、复验、Redis、会话消费）分离，便于资金核心代码集中审查与保证回滚一致性。
 *
 * @author dakang
 * @since 2026-07-19
 */
public interface ITradeOrderTxService {

    /**
     * 事务内创建取水订单（CARD-SCOPE 固定顺序 ①~⑨，预检不是安全边界，这里是最终防线）：
     * 会话共键重核 → 锁卡 → 归属/成员授权 → 状态 → 范围 → 日限额 → 原子扣减 → 订单+流水。
     * 任一步失败整体回滚零副作用；扫码会话消费与设备指令由编排层在提交成功后才执行（⑩）。
     *
     * @param order   已装配好业务字段（不含 ID/审计字段）的订单，ORDER_STATUS=2 已支付
     * @param session 服务端铸造的扫码会话（qrcodeId/stationId/deviceId/outletId 共键重核依据）
     * @param now     yyyyMMddHHmmss，用于扣减 SQL 的过期判定与 UPDATE_TIME
     * @return 已持久化（含自增 ID）的订单
     */
    WsOrder createWaterOrder(WsOrder order, ScanSessionInfo session, String now);

    /**
     * 出水指令重入原子闸：条件 UPDATE，==1 抢闸成功可 publish，==0 已有指令占位作废本次不下发
     * （一单物理上不可能两条有效出水指令，铁律4 配套）。CAS 谓词含订单类型、已支付态与三共键；
     * 影响行数不是 1 时调用方必须作废本次 PENDING 指令且不得 publish。
     *
     * @param orderId   订单ID
     * @param commandId 待关联的指令ID
     * @param stationId 判定所依据的当前水站ID（必须仍等于订单落库值）
     * @param deviceId  判定所依据的当前设备ID
     * @param outletId  判定所依据的当前出水口ID
     * @return 影响行数（0 或 1）
     */
    int claimCommandSlot(Long orderId, Long commandId, Long stationId, Long deviceId, Long outletId);

    /**
     * 出水结果结算（L1d，铁律1）：事务内 条件推进订单终态 → 回写 ACTUAL_ML → 差额/退款补偿入账（原子 UPDATE+流水）。
     * v1.3 结算规则：success 且 actualMl≥planMl→4已完成（不退）；success 且 0&lt;actualMl&lt;planMl→按实际量结算退差→4；
     * success 且 actualMl=0→全额退→7已退款；success=false（含 actualMl&gt;0/=0）→按实际量退差/全退→6异常待核。
     * 取整：payWay2 应扣 ceil(actualMl*单价/1000)，退差=预扣-应扣；payWay3 退未出水量。
     * 幂等：订单终态条件 UPDATE 为闸，affected==0（已结算）直接跳过不补偿；同事务任一失败全回滚。
     *
     * @param orderId  订单ID
     * @param success  设备执行是否成功
     * @param actualMl 实际出水量（毫升），必须为非负整数；缺失或非法值时拒绝结算
     * @return 是否发生了结算推进（true=本次完成结算，false=已结算/不可结算跳过）
     */
    boolean settleWaterOrder(Long orderId, boolean success, Long actualMl);
}
