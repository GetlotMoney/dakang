package com.jbk.serve.service.device.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.device.WsCommandMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.service.device.DeviceReportTime;
import com.jbk.serve.service.device.IWsCommandAckTxService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.device.DeviceEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.data.device.po.WsCommand;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * ACK 事务实现：指令状态、订单状态与可靠状态事件同生共死。
 *
 * @author dakang
 * @since 2026-08-03
 */
@Service
@RequiredArgsConstructor
public class WsCommandAckTxServiceImpl implements IWsCommandAckTxService {

    /**
     * accepted 回执下允许「已经推过了」的订单状态白名单（显式枚举，不用数值大小判断）。
     * 3出水中 正是本次的目标态；4/5/6/7/8 是订单已走完或已被其他链路收口。
     * 1待支付、NULL 与任何未登记值都不在内——那说明这条 ACK 打在了不该打的订单上。
     */
    private static final Set<Integer> ACCEPT_IDEMPOTENT_STATUSES = Set.of(
            TradeEnum.OrderStatus.DISPENSING.getValue(),
            TradeEnum.OrderStatus.FINISHED.getValue(),
            TradeEnum.OrderStatus.CANCELLED.getValue(),
            TradeEnum.OrderStatus.ABNORMAL.getValue(),
            TradeEnum.OrderStatus.REFUNDED.getValue(),
            TradeEnum.OrderStatus.PART_REFUNDED.getValue());

    /** 失败回执下允许「已经推过了」的订单状态白名单（终态五项；2/3 仍需本次推进）。 */
    private static final Set<Integer> FAIL_IDEMPOTENT_STATUSES = Set.of(
            TradeEnum.OrderStatus.FINISHED.getValue(),
            TradeEnum.OrderStatus.CANCELLED.getValue(),
            TradeEnum.OrderStatus.ABNORMAL.getValue(),
            TradeEnum.OrderStatus.REFUNDED.getValue(),
            TradeEnum.OrderStatus.PART_REFUNDED.getValue());

    private final WsCommandMapper commandMapper;
    private final WsOrderMapper orderMapper;
    private final IWsDomainEventService domainEventService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WsCommand applyAck(Long reportDeviceId, String cmdNo, String ackCode, String ackTs) {
        // ① 权威当前读并锁定指令行：后续所有判定只认锁内值
        WsCommand locked = commandMapper.selectByCmdNoForUpdate(cmdNo);
        if (ObjectUtil.isNull(locked)) {
            domainEventService.recordByDevice(OpsEnum.EventType.COMMAND_STATUS, cmdNo,
                    null, "收到未知指令号 ACK，忽略");
            return null;
        }
        if (ObjectUtil.notEqual(locked.getDeviceId(), reportDeviceId)) {
            // 错设备 ACK：只审计不改状态（MVP 验收五场景之一）
            domainEventService.recordByDevice(OpsEnum.EventType.COMMAND_STATUS, cmdNo, null,
                    "错设备 ACK：指令目标设备 " + locked.getDeviceId() + "，实际上报设备 " + reportDeviceId + "，忽略");
            return null;
        }
        if (ObjectUtil.notEqual(locked.getCmdStatus(), DeviceEnum.CmdStatus.SENT.getValue())) {
            // 迟到/重复 ACK：指令已回执或已终态，只审计（REQ-041）
            domainEventService.recordByDevice(OpsEnum.EventType.COMMAND_STATUS, cmdNo,
                    DeviceEnum.CmdStatus.getType(locked.getCmdStatus()).getDesc(), "迟到/重复 ACK，状态不变");
            return null;
        }

        // ② 判定：只有非空且忽略大小写等于 accepted 才是受理
        String ackNormalized = StrUtil.trimToEmpty(ackCode);
        boolean accepted = "accepted".equalsIgnoreCase(ackNormalized);
        boolean ackMissing = !accepted && StrUtil.isEmpty(ackNormalized);
        String ackTime = resolveAckTime(cmdNo, ackTs);

        // ③ 出水指令：先把订单共键闭合核完再动任何状态（不符即回滚，指令一并不推进）
        WsOrder order = null;
        boolean dispense = ObjectUtil.equal(locked.getCmdType(), DeviceEnum.CmdType.START_DISPENSE.getValue());
        if (dispense) {
            order = requireDispenseOrder(locked);
        }

        // ④ 指令前态 CAS 推进
        DeviceEnum.CmdStatus target = accepted ? DeviceEnum.CmdStatus.ACKED : DeviceEnum.CmdStatus.FAILED;
        String oldDesc = DeviceEnum.CmdStatus.getType(locked.getCmdStatus()).getDesc();
        WsCommand patch = new WsCommand().setCmdStatus(target.getValue());
        patch.setId(locked.getId());
        if (accepted) {
            patch.setAckTime(ackTime);
        }
        else {
            patch.setFinishTime(ackTime);
            patch.setFailReason(StrUtil.maxLength(
                    ackMissing ? "ACK 缺少 ackCode" : "设备拒绝执行(ackCode=" + ackCode + ")", 490));
        }
        int moved = commandMapper.update(patch, Wrappers.lambdaUpdate(WsCommand.class)
                .eq(WsCommand::getId, locked.getId())
                .eq(WsCommand::getCmdStatus, DeviceEnum.CmdStatus.SENT.getValue()));
        if (moved != 1) {
            // 行已锁定，理论上不可能落空；真落空说明有绕过锁的写入路径，fail-closed 不放行
            throw rejected(cmdNo, "指令状态推进失败（cmdNo=" + cmdNo + "）");
        }
        locked.setCmdStatus(target.getValue());

        // ⑤ 可靠状态事件：与业务同事务，写入失败抛出并连同④⑥一起回滚。
        //    绝不用静默写入——「状态成功、审计缺失」在追溯里等于这次状态变化没发生过。
        //    幂等键取指令号：一条指令从 SENT 出发只会有一次 ACK 推进，回滚重投时事件同被回滚，
        //    不会与自己撞键；真撞键说明是同语义重放，由可靠入口读回核验。
        domainEventService.recordReliableOnceAs(OpsEnum.ActorPortal.DEVICE, locked.getDeviceId(),
                OpsEnum.EventType.COMMAND_STATUS, cmdNo, "CMD_ACK:" + cmdNo, oldDesc, target.getDesc());

        // ⑥ 订单联动（仅出水指令；停止出水等指令按自身语义不联动取水订单）
        if (dispense) {
            linkOrder(locked, order, accepted, ackMissing, ackCode);
        }
        return locked;
    }

    /**
     * ACK 拒绝的可靠留痕：先独立提交证据，再抛出让业务事务回滚。
     *
     * <p>{@code recordByDevice} 与本事务同生共死，用它记拒绝等于没记——回滚会把证据一起抹掉，
     * 上游只在 {@code ws_device_msg.HANDLE_REMARK} 留一行文本，而那张表没有任何读路径。
     * 拒绝证据必须独立于主事务存活，这也是本仓 {@code IWsDomainEventService} 明写的口径。</p>
     */
    private JbkException rejected(String cmdNo, String reason) {
        domainEventService.recordReliable(OpsEnum.EventType.COMMAND_STATUS, cmdNo, null,
                "ACK 拒绝：" + reason);
        return new JbkException(reason);
    }

    /** 设备时间双存兜底（P0-06）：判定规则唯一实现在 {@link DeviceReportTime}，回退时留痕。 */
    private String resolveAckTime(String cmdNo, String ackTs) {
        String accepted = DeviceReportTime.acceptOrNull(ackTs);
        if (accepted != null) {
            return accepted;
        }
        String server = DateUtils.time();
        domainEventService.recordByDevice(OpsEnum.EventType.COMMAND_STATUS, cmdNo,
                ackTs, "设备时间缺失/非法，回退服务器时间 " + server);
        return server;
    }

    /**
     * 出水指令的订单闭合核验（锁内当前读）。这些共键一条都不能省：
     * 订单必须存在且未删除、必须是取水订单、设备与指令一致、且 {@code CMD_ID} 正指向本指令。
     * 少核 CMD_ID，一条被作废的旧指令的迟到 ACK 就能把订单推进到出水中。
     */
    private WsOrder requireDispenseOrder(WsCommand command) {
        if (ObjectUtil.isNull(command.getOrderId())) {
            throw rejected(command.getCmdNo(), "出水指令缺少关联订单，无法收敛");
        }
        WsOrder order = orderMapper.selectByIdForUpdate(command.getOrderId());
        if (ObjectUtil.isNull(order)) {
            throw rejected(command.getCmdNo(), "关联订单不存在或已删除（orderId=" + command.getOrderId() + "）");
        }
        if (ObjectUtil.notEqual(order.getOrderType(), TradeEnum.OrderType.WATER.getValue())) {
            throw rejected(command.getCmdNo(), "关联订单不是取水订单（orderId=" + order.getId()
                    + "，orderType=" + order.getOrderType() + "）");
        }
        if (ObjectUtil.notEqual(order.getDeviceId(), command.getDeviceId())) {
            throw rejected(command.getCmdNo(), "订单与指令设备共键错位（orderId=" + order.getId() + "）");
        }
        if (ObjectUtil.notEqual(order.getCmdId(), command.getId())) {
            throw rejected(command.getCmdNo(), "订单未绑定本指令（orderId=" + order.getId()
                    + "，orderCmdId=" + order.getCmdId() + "，cmdId=" + command.getId() + "）");
        }
        return order;
    }

    /** 订单联动：状态分类全部走显式白名单，不做数值大小比较。 */
    private void linkOrder(WsCommand command, WsOrder order, boolean accepted,
                           boolean ackMissing, String ackCode) {
        Integer status = order.getOrderStatus();
        if (ObjectUtil.isNull(status)) {
            throw rejected(command.getCmdNo(), "关联订单状态为空，无法判定（orderId=" + order.getId() + "）");
        }
        if (accepted) {
            if (ObjectUtil.equal(status, TradeEnum.OrderStatus.PAID.getValue())) {
                requireOrderMoved(command.getCmdNo(), order, Wrappers.lambdaUpdate(WsOrder.class)
                        .set(WsOrder::getOrderStatus, TradeEnum.OrderStatus.DISPENSING.getValue())
                        .set(WsOrder::getUpdateTime, DateUtils.time())
                        .eq(WsOrder::getId, order.getId())
                        .eq(WsOrder::getOrderStatus, TradeEnum.OrderStatus.PAID.getValue()));
                recordOrderTransition(command, order, TradeEnum.OrderStatus.PAID.getDesc(),
                        TradeEnum.OrderStatus.DISPENSING.getDesc());
                return;
            }
            requireWhitelisted(command, order, status, ACCEPT_IDEMPOTENT_STATUSES);
            return;
        }
        if (ObjectUtil.equal(status, TradeEnum.OrderStatus.PAID.getValue())
                || ObjectUtil.equal(status, TradeEnum.OrderStatus.DISPENSING.getValue())) {
            String reason = ackMissing
                    ? "设备回执缺少 ackCode，无法确认已受理，待补偿处理"
                    : "设备拒绝出水(ackCode=" + ackCode + ")";
            requireOrderMoved(command.getCmdNo(), order, Wrappers.lambdaUpdate(WsOrder.class)
                    .set(WsOrder::getOrderStatus, TradeEnum.OrderStatus.ABNORMAL.getValue())
                    .set(WsOrder::getCancelReason, StrUtil.maxLength(reason, 490))
                    .set(WsOrder::getUpdateTime, DateUtils.time())
                    .eq(WsOrder::getId, order.getId())
                    .in(WsOrder::getOrderStatus, TradeEnum.OrderStatus.PAID.getValue(),
                            TradeEnum.OrderStatus.DISPENSING.getValue()));
            recordOrderTransition(command, order, String.valueOf(status),
                    TradeEnum.OrderStatus.ABNORMAL.getDesc());
            return;
        }
        requireWhitelisted(command, order, status, FAIL_IDEMPOTENT_STATUSES);
    }

    /**
     * 订单状态推进的可靠事件：与指令事件同事务、写失败即整体回滚。
     *
     * <p>ACK 是这两次订单状态变化的唯一驱动方，只给指令留痕、订单不留痕，事后就只能靠
     * {@code UPDATE_TIME} 反推「这单什么时候、因为什么进的出水中/异常待补偿」。
     * 事件键用 {@code orderNo}，与全仓 ORDER_STATUS 事件的检索口径一致。</p>
     */
    private void recordOrderTransition(WsCommand command, WsOrder order, String from, String to) {
        domainEventService.recordReliableOnceAs(OpsEnum.ActorPortal.DEVICE, command.getDeviceId(),
                OpsEnum.EventType.ORDER_STATUS, order.getOrderNo(),
                "ACK_ORDER:" + command.getCmdNo(), from, to);
    }

    /** 订单行已被本事务锁定，条件 UPDATE 不可能被并发抢走；影响行数不是 1 即数据异常，回滚。 */
    private void requireOrderMoved(String cmdNo, WsOrder order,
                                   com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<WsOrder> update) {
        if (orderMapper.update(null, update) != 1) {
            throw rejected(cmdNo, "订单联动失败，锁内条件更新影响 0 行（orderId=" + order.getId() + "）");
        }
    }

    /** 不在白名单内的订单状态一律 fail-closed；在白名单内的按幂等命中留审计。 */
    private void requireWhitelisted(WsCommand command, WsOrder order, Integer status, Set<Integer> whitelist) {
        if (!whitelist.contains(status)) {
            throw rejected(command.getCmdNo(), "关联订单状态不在合法集合内（orderId=" + order.getId()
                    + "，orderStatus=" + status + "）");
        }
        // 幂等命中属「未发生状态变化」的忽略类审计，用 orderNo 作键与订单轨迹检索口径一致
        domainEventService.recordByDevice(OpsEnum.EventType.ORDER_STATUS, order.getOrderNo(), null,
                "订单已处于状态 " + status + "，ACK 按幂等不重复推进（cmdNo=" + command.getCmdNo() + "）");
    }
}
