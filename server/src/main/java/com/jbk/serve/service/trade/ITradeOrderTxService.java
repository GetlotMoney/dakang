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
     * 事务内创建取水订单（CARD-SCOPE 固定顺序，预检不是安全边界，这里是最终防线）：
     * ①以服务端铸造的扫码会话为设备/出水口唯一来源 → ②锁内重核 qrcode/station/device/outlet
     * 共键与档案状态 → ③SELECT 水卡 FOR UPDATE → ④使用人=卡主，或（CARD-MEMBER）锁
     * uk_card_member_user 成员关系行并校验授权状态与有效期窗口 →
     * ⑤未删除/状态正常/未过期 → ⑥{@code WaterCardScope.allows(station,device,outlet)} 放行
     * （成员继承主卡范围）→ ⑦成员日限额（仅成员且配置 DAY_LIMIT_ML；靠 ④ 的行锁串行化）→
     * ⑧原子扣减（条件 UPDATE + 影响行数；USER_ID 归属条件=锁内卡主，UPDATE_BY=实际使用人）→
     * ⑨INSERT 订单+流水（USER_ID=实际使用人，扣减作用卡主的卡）。
     * 任一步失败整体回滚：卡余额/水量不变、订单/流水零新增；扫码会话消费与设备指令
     * 由编排层在提交成功后才执行（⑩）。
     *
     * @param order   已装配好业务字段（不含 ID/审计字段）的订单，ORDER_STATUS=2 已支付
     * @param session 服务端铸造的扫码会话（qrcodeId/stationId/deviceId/outletId 共键重核依据）
     * @param now     yyyyMMddHHmmss，用于扣减 SQL 的过期判定与 UPDATE_TIME
     * @return 已持久化（含自增 ID）的订单
     */
    WsOrder createWaterOrder(WsOrder order, ScanSessionInfo session, String now);

    /**
     * 出水指令重入原子闸：条件 UPDATE ws_order SET CMD_ID=? WHERE ID=? AND CMD_ID IS NULL。
     * 返回影响行数：==1 抢闸成功（可 publish）；==0 已有指令占位（重复触发，作废本次不下发）。
     * 保证一个订单物理上不可能产生两条有效出水指令（设备安全铁律4 配套）。
     *
     * <p>CAS 谓词还包含订单类型、已支付状态与「站-设备-出水口」三共键：调用方读单、判定设备到
     * 抢占之间，订单可能被并发转异常/退款/完成，档案也可能迁站或改绑。影响行数不是 1 时
     * 调用方必须作废本次 PENDING 指令且不得 publish。</p>
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
