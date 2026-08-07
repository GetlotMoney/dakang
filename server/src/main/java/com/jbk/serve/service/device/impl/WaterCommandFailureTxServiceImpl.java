package com.jbk.serve.service.device.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.device.WsCommandMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.service.device.IWaterCommandFailureTxService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.device.DeviceEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.data.device.po.WsCommand;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.exception.JbkException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/** 开始出水指令失败、订单异常收敛与可靠事件的统一事务实现。 */
@Service
@RequiredArgsConstructor
public class WaterCommandFailureTxServiceImpl implements IWaterCommandFailureTxService {

    private static final Set<Integer> ORDER_TERMINAL_STATUSES = Set.of(
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
    public boolean converge(String cmdNo,
                            Integer expectedCommandStatus,
                            DeviceEnum.CmdStatus targetCommandStatus,
                            String finishTime,
                            String commandReason,
                            String orderReason,
                            String scene) {
        validateRequest(cmdNo, expectedCommandStatus, targetCommandStatus, finishTime, scene);

        // 固定锁序与 ACK 事务一致：指令 → 订单。所有共键在任何写入前基于锁内当前值核验。
        WsCommand command = commandMapper.selectByCmdNoForUpdate(cmdNo);
        if (ObjectUtil.isNull(command)) {
            throw new JbkException("开始出水指令不存在或已删除");
        }
        if (ObjectUtil.notEqual(command.getCmdStatus(), expectedCommandStatus)) {
            return false;
        }
        if (ObjectUtil.notEqual(command.getCmdType(), DeviceEnum.CmdType.START_DISPENSE.getValue())
                || ObjectUtil.isNull(command.getOrderId())) {
            throw new JbkException("失败收敛仅适用于已关联订单的开始出水指令");
        }
        WsOrder order = requireLinkedWaterOrder(command);

        WsCommand commandPatch = new WsCommand()
                .setId(command.getId())
                .setCmdStatus(targetCommandStatus.getValue())
                .setFinishTime(finishTime)
                .setFailReason(StrUtil.maxLength(StrUtil.blankToDefault(commandReason, "设备指令执行失败"), 490));
        int commandMoved = commandMapper.update(commandPatch, Wrappers.lambdaUpdate(WsCommand.class)
                .eq(WsCommand::getId, command.getId())
                .eq(WsCommand::getCmdNo, command.getCmdNo())
                .eq(WsCommand::getDeviceId, command.getDeviceId())
                .eq(WsCommand::getOrderId, command.getOrderId())
                .eq(WsCommand::getCmdType, DeviceEnum.CmdType.START_DISPENSE.getValue())
                .eq(WsCommand::getCmdStatus, expectedCommandStatus));
        if (commandMoved != 1) {
            throw new JbkException("开始出水指令终态推进失败");
        }

        Integer orderOldStatus = order.getOrderStatus();
        boolean orderMoved = isOrderActive(orderOldStatus);
        if (orderMoved) {
            int affected = orderMapper.update(null, Wrappers.lambdaUpdate(WsOrder.class)
                    .set(WsOrder::getOrderStatus, TradeEnum.OrderStatus.ABNORMAL.getValue())
                    .set(WsOrder::getCancelReason,
                            StrUtil.maxLength(StrUtil.blankToDefault(orderReason, "出水指令失败，待补偿处理"), 490))
                    .set(WsOrder::getUpdateTime, finishTime)
                    .eq(WsOrder::getId, order.getId())
                    .eq(WsOrder::getOrderNo, order.getOrderNo())
                    .eq(WsOrder::getOrderType, TradeEnum.OrderType.WATER.getValue())
                    .eq(WsOrder::getDeviceId, command.getDeviceId())
                    .eq(WsOrder::getCmdId, command.getId())
                    .eq(WsOrder::getOrderStatus, orderOldStatus));
            if (affected != 1) {
                throw new JbkException("开始出水指令失败后的订单收敛影响行数异常");
            }
        }

        domainEventService.recordReliableOnceAs(OpsEnum.ActorPortal.SYSTEM, null,
                OpsEnum.EventType.COMMAND_STATUS, command.getCmdNo(),
                "CMD_FAIL:" + command.getId(), commandStatusDesc(expectedCommandStatus),
                targetCommandStatus.getDesc());
        domainEventService.recordReliableOnceAs(OpsEnum.ActorPortal.SYSTEM, null,
                OpsEnum.EventType.ORDER_STATUS, order.getOrderNo(),
                "WATER_FALLBACK:" + command.getId(), orderStatusDesc(orderOldStatus),
                orderMoved
                        ? TradeEnum.OrderStatus.ABNORMAL.getDesc() + "（" + scene + "）"
                        : "兜底未改单：订单已处于" + orderStatusDesc(orderOldStatus) + "（" + scene + "）");
        return true;
    }

    private WsOrder requireLinkedWaterOrder(WsCommand command) {
        WsOrder order = orderMapper.selectByIdForUpdate(command.getOrderId());
        if (ObjectUtil.isNull(order)) {
            throw new JbkException("开始出水指令关联订单不存在或已删除");
        }
        if (StrUtil.isBlank(order.getOrderNo())
                || ObjectUtil.notEqual(order.getOrderType(), TradeEnum.OrderType.WATER.getValue())
                || ObjectUtil.notEqual(order.getDeviceId(), command.getDeviceId())
                || ObjectUtil.notEqual(order.getCmdId(), command.getId())) {
            throw new JbkException("开始出水指令与订单共键不一致");
        }
        if (!isOrderActive(order.getOrderStatus()) && !ORDER_TERMINAL_STATUSES.contains(order.getOrderStatus())) {
            throw new JbkException("开始出水指令关联订单状态不在合法集合内");
        }
        return order;
    }

    private void validateRequest(String cmdNo,
                                 Integer expectedCommandStatus,
                                 DeviceEnum.CmdStatus targetCommandStatus,
                                 String finishTime,
                                 String scene) {
        if (StrUtil.isBlank(cmdNo) || ObjectUtil.isNull(expectedCommandStatus)
                || ObjectUtil.isNull(targetCommandStatus) || StrUtil.isBlank(finishTime)
                || StrUtil.isBlank(scene)) {
            throw new JbkException("开始出水指令失败收敛参数不完整");
        }
        boolean dispatchFailed = ObjectUtil.equal(expectedCommandStatus, DeviceEnum.CmdStatus.SENT.getValue())
                && targetCommandStatus == DeviceEnum.CmdStatus.FAILED;
        boolean pendingInterrupted = ObjectUtil.equal(expectedCommandStatus, DeviceEnum.CmdStatus.PENDING.getValue())
                && targetCommandStatus == DeviceEnum.CmdStatus.FAILED;
        boolean timedOut = (ObjectUtil.equal(expectedCommandStatus, DeviceEnum.CmdStatus.SENT.getValue())
                || ObjectUtil.equal(expectedCommandStatus, DeviceEnum.CmdStatus.ACKED.getValue()))
                && targetCommandStatus == DeviceEnum.CmdStatus.TIMEOUT;
        if (!dispatchFailed && !pendingInterrupted && !timedOut) {
            throw new JbkException("开始出水指令失败收敛状态迁移不合法");
        }
    }

    private static boolean isOrderActive(Integer status) {
        return ObjectUtil.equal(status, TradeEnum.OrderStatus.PAID.getValue())
                || ObjectUtil.equal(status, TradeEnum.OrderStatus.DISPENSING.getValue());
    }

    private static String commandStatusDesc(Integer status) {
        return DeviceEnum.CmdStatus.getType(status).getDesc();
    }

    private static String orderStatusDesc(Integer status) {
        for (TradeEnum.OrderStatus item : TradeEnum.OrderStatus.values()) {
            if (ObjectUtil.equal(item.getValue(), status)) {
                return item.getDesc();
            }
        }
        throw new JbkException("订单状态不存在");
    }
}
