package com.jbk.serve.service.device.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jbk.serve.mapper.device.WsCommandMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.service.device.IWsCommandService;
import com.jbk.serve.service.ops.IWsAlarmService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.serve.service.trade.ITradeOrderTxService;
import com.jbk.serve.service.trade.WaterBillingMath;
import com.jbk.tool.config.mqtt.MqttConnectionManager;
import com.jbk.tool.consts.device.DeviceEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.device.bo.WsCommandBo;
import com.jbk.tool.data.device.po.WsCommand;
import com.jbk.tool.data.device.po.WsDevice;
import com.jbk.tool.data.device.po.WsDeviceOutlet;
import com.jbk.tool.data.device.vo.WsCommandVo;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import com.jbk.tool.utils.OptionalUtils;
import com.jbk.serve.mapper.device.WsDeviceMapper;
import com.jbk.serve.mapper.device.WsDeviceOutletMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 设备指令服务实现
 * <p>状态机：1待下发 → 2已下发 → 3已回执 → 4成功/5失败/7部分完成；2/3 停留超时由 worker 翻转 6。
 * 每次状态推进都写领域事件（REQ-024：命令/ACK 状态变化必写审计）。</p>
 *
 * @author dakang
 * @since 2026-07-12
 */
@Slf4j
@Service
public class WsCommandServiceImpl extends ServiceImpl<WsCommandMapper, WsCommand> implements IWsCommandService {

    /** 下行协议版本标识（真机适配分支解析用；旧模拟器忽略该字段兼容） */
    private static final String PROTOCOL_VERSION = "0.1";
    /** 已下发无回执超时阈值（秒），协议文档 30s */
    private static final int ACK_TIMEOUT_SECONDS = 30;
    /** 已回执无结果超时阈值（秒），协议文档 120s */
    private static final int RESULT_TIMEOUT_SECONDS = 120;
    /** 待下发滞留超时阈值（秒）：save 后未离开 PENDING 超此秒判中断（N-06 兜底服务重启/崩溃残留） */
    private static final int PENDING_TIMEOUT_SECONDS = 60;
    /** 后台中控允许人工下发的指令类型（出水类 1/2 必须由订单链路触发） */
    private static final Set<Integer> CONSOLE_ALLOWED_TYPES = Set.of(
            DeviceEnum.CmdType.QUERY_STATUS.getValue(),
            DeviceEnum.CmdType.LOCK.getValue(),
            DeviceEnum.CmdType.UNLOCK.getValue(),
            DeviceEnum.CmdType.PARAM_SYNC.getValue(),
            DeviceEnum.CmdType.REBOOT.getValue());

    @Autowired
    private WsDeviceMapper deviceMapper;
    @Autowired
    private WsDeviceOutletMapper outletMapper;
    @Autowired
    private WsOrderMapper wsOrderMapper;
    @Autowired
    private ITradeOrderTxService tradeOrderTxService;
    @Autowired
    private IWsAlarmService alarmService;
    @Autowired
    private IWsDomainEventService domainEventService;
    /** MQTT 为条件装配 Bean（mqtt.enabled），用 ObjectProvider 兼容未启用场景 */
    @Autowired
    private ObjectProvider<MqttConnectionManager> mqttProvider;

    @Override
    public PageDataVo<WsCommandVo> pageData(WsCommandBo commandBo) {
        Page<WsCommand> page = page(new Page<>(commandBo.getCurrent(), commandBo.getSize()),
                Wrappers.lambdaQuery(WsCommand.class)
                        .eq(ObjectUtil.isNotNull(commandBo.getDeviceId()), WsCommand::getDeviceId, commandBo.getDeviceId())
                        .eq(ObjectUtil.isNotNull(commandBo.getCmdStatus()), WsCommand::getCmdStatus, commandBo.getCmdStatus())
                        .like(StrUtil.isNotBlank(commandBo.getCmdNo()), WsCommand::getCmdNo, commandBo.getCmdNo())
                        .orderByDesc(WsCommand::getId));
        List<WsCommandVo> voList = page.getRecords().stream()
                .map(e -> BeanUtil.copyProperties(e, WsCommandVo.class))
                .collect(Collectors.toList());
        fillDeviceInfo(voList);
        return PageDataVo.getPageData(voList, page.getTotal());
    }

    @Override
    public WsCommandVo getData(Long id) {
        WsCommand command = getById(id);
        OptionalUtils.nullToElseThrow(command, "指令不存在");
        WsCommandVo vo = BeanUtil.copyProperties(command, WsCommandVo.class);
        fillDeviceInfo(CollUtil.newArrayList(vo));
        return vo;
    }

    @Override
    public Long send(WsCommandBo commandBo) {
        if (!CONSOLE_ALLOWED_TYPES.contains(commandBo.getCmdType())) {
            throw new JbkException("出水类指令必须由订单链路触发，禁止人工下发");
        }
        WsDevice device = deviceMapper.selectById(commandBo.getDeviceId());
        OptionalUtils.nullToElseThrow(device, "目标设备不存在");
        // 参数同步必须携带合法 JSON 报文
        if (DeviceEnum.CmdType.PARAM_SYNC.getValue() == commandBo.getCmdType()) {
            if (StrUtil.isBlank(commandBo.getCmdPayload()) || !JSONUtil.isTypeJSON(commandBo.getCmdPayload())) {
                throw new JbkException("参数同步指令必须携带合法 JSON 报文");
            }
        }

        WsCommand command = new WsCommand()
                .setCmdNo(generateCmdNo())
                .setDeviceId(device.getId())
                .setCmdType(commandBo.getCmdType())
                .setCmdPayload(StrUtil.blankToDefault(commandBo.getCmdPayload(), "{}"))
                .setCmdStatus(DeviceEnum.CmdStatus.PENDING.getValue())
                .setRetryCount(0);
        save(command);
        domainEventService.record(OpsEnum.EventType.COMMAND_STATUS, command.getCmdNo(),
                null, DeviceEnum.CmdStatus.PENDING.getDesc());
        doSend(command, device);
        return command.getId();
    }

    @Override
    public Long sendDispenseForOrder(Long orderId) {
        WsOrder order = wsOrderMapper.selectById(orderId);
        OptionalUtils.nullToElseThrow(order, "订单不存在");
        if (ObjectUtil.notEqual(order.getOrderType(), TradeEnum.OrderType.WATER.getValue())) {
            throw new JbkException("非取水订单不下发出水指令");
        }
        if (ObjectUtil.isNull(order.getDeviceId()) || ObjectUtil.isNull(order.getOutletId())) {
            throw new JbkException("订单缺少设备或出水口，无法下发出水指令");
        }
        // 重入快速幂等：订单已绑定指令直接返回既有（afterCommit 重发/重启重放不产生新指令）
        if (ObjectUtil.isNotNull(order.getCmdId())) {
            return order.getCmdId();
        }
        // 仅已支付订单允许下发出水（防未支付/异常态触发出水）
        if (ObjectUtil.notEqual(order.getOrderStatus(), TradeEnum.OrderStatus.PAID.getValue())) {
            throw new JbkException("订单状态不支持下发出水指令");
        }
        WsDevice device = deviceMapper.selectById(order.getDeviceId());
        OptionalUtils.nullToElseThrow(device, "目标设备不存在");
        WsDeviceOutlet outlet = outletMapper.selectById(order.getOutletId());
        OptionalUtils.nullToElseThrow(outlet, "出水口不存在");

        JSONObject payload = new JSONObject();
        payload.set("outletNo", outlet.getOutletNo());
        payload.set("waterTypeId", outlet.getWaterTypeId());
        payload.set("waterType", outlet.getWaterType());
        payload.set("planMl", order.getPlanMl());
        payload.set("orderNo", order.getOrderNo());

        WsCommand command = new WsCommand()
                .setCmdNo(generateCmdNo())
                .setDeviceId(device.getId())
                .setOrderId(orderId)
                .setCmdType(DeviceEnum.CmdType.START_DISPENSE.getValue())
                .setCmdPayload(payload.toString())
                .setCmdStatus(DeviceEnum.CmdStatus.PENDING.getValue())
                .setRetryCount(0);
        save(command);
        domainEventService.record(OpsEnum.EventType.COMMAND_STATUS, command.getCmdNo(),
                null, DeviceEnum.CmdStatus.PENDING.getDesc());

        // 重入原子闸：抢占订单 CMD_ID，赢者才 publish（物理保证单订单单有效出水指令，铁律4 配套）
        int won = tradeOrderTxService.claimCommandSlot(orderId, command.getId());
        if (won != 1) {
            // 并发/重复触发输方：作废刚建的 PENDING 指令，绝不 publish；返回既有指令ID
            update(Wrappers.lambdaUpdate(WsCommand.class)
                    .set(WsCommand::getCmdStatus, DeviceEnum.CmdStatus.FAILED.getValue())
                    .set(WsCommand::getFinishTime, DateUtils.time())
                    .set(WsCommand::getFailReason, "订单已有出水指令，重复触发作废")
                    .eq(WsCommand::getId, command.getId())
                    .eq(WsCommand::getCmdStatus, DeviceEnum.CmdStatus.PENDING.getValue()));
            domainEventService.record(OpsEnum.EventType.COMMAND_STATUS, command.getCmdNo(),
                    DeviceEnum.CmdStatus.PENDING.getDesc(), "重复触发作废（订单已有出水指令）");
            WsOrder fresh = wsOrderMapper.selectById(orderId);
            return ObjectUtil.isNull(fresh) ? null : fresh.getCmdId();
        }
        doSend(command, device);
        return command.getId();
    }

    /**
     * 统一下发：构造下行报文（{cmdNo,cmdType,payload,ts,ver}）→ publish → 1待下发→2已下发；
     * Broker 未启用/未连接/发布异常 → 1→5失败（发后必有终态，不留死状态）。出水指令失败时联动订单转异常待补偿。
     */
    private void doSend(WsCommand command, WsDevice device) {
        try {
            MqttConnectionManager mqtt = mqttProvider.getIfAvailable();
            if (mqtt == null) {
                throw new IllegalStateException("MQTT 未启用（mqtt.enabled=false）");
            }
            JSONObject downPayload = new JSONObject();
            downPayload.set("cmdNo", command.getCmdNo());
            downPayload.set("cmdType", command.getCmdType());
            downPayload.set("payload", JSONUtil.parse(command.getCmdPayload()));
            downPayload.set("ts", DateUtils.time());
            downPayload.set("ver", PROTOCOL_VERSION);
            mqtt.publishCommand(device.getDeviceNo(), downPayload.toString());
            transit(command, DeviceEnum.CmdStatus.SENT, cmd -> cmd.setSentTime(DateUtils.time()));
        } catch (Exception e) {
            log.error("指令下发失败 cmdNo={} device={}", command.getCmdNo(), device.getDeviceNo(), e);
            transit(command, DeviceEnum.CmdStatus.FAILED, cmd -> {
                cmd.setFinishTime(DateUtils.time());
                cmd.setFailReason(StrUtil.maxLength(
                        "下发失败：" + StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()), 490));
            });
            // 出水指令下发失败：订单条件 UPDATE 2→6 异常待补偿（不回滚已扣款，补偿归 L1d）
            if (ObjectUtil.isNotNull(command.getOrderId())) {
                markOrderAbnormal(command.getOrderId(), "出水指令下发失败，待补偿处理");
            }
        }
    }

    /** 出水链异常 → 订单 2已支付/3出水中 → 6异常待补偿（条件 UPDATE，幂等；已扣款不回滚，补偿归 L1d） */
    private void markOrderAbnormal(Long orderId, String reason) {
        wsOrderMapper.update(null, Wrappers.lambdaUpdate(WsOrder.class)
                .set(WsOrder::getOrderStatus, TradeEnum.OrderStatus.ABNORMAL.getValue())
                .set(WsOrder::getCancelReason, StrUtil.maxLength(reason, 490))
                .set(WsOrder::getUpdateTime, DateUtils.time())
                .eq(WsOrder::getId, orderId)
                .in(WsOrder::getOrderStatus, TradeEnum.OrderStatus.PAID.getValue(),
                        TradeEnum.OrderStatus.DISPENSING.getValue()));
    }

    @Override
    public boolean onAck(Long deviceId, String cmdNo, String ackTs, String ackCode) {
        WsCommand command = getOne(Wrappers.lambdaQuery(WsCommand.class).eq(WsCommand::getCmdNo, cmdNo));
        if (ObjectUtil.isNull(command)) {
            domainEventService.recordByDevice(OpsEnum.EventType.COMMAND_STATUS, cmdNo, null, "收到未知指令号 ACK，忽略");
            return false;
        }
        if (ObjectUtil.notEqual(command.getDeviceId(), deviceId)) {
            // 错设备 ACK：MVP 验收五场景之一，只审计不改状态
            domainEventService.recordByDevice(OpsEnum.EventType.COMMAND_STATUS, cmdNo,
                    null, "错设备 ACK：指令目标设备 " + command.getDeviceId() + "，实际上报设备 " + deviceId + "，忽略");
            return false;
        }
        if (DeviceEnum.CmdStatus.SENT.getValue() != command.getCmdStatus()) {
            // 迟到/重复 ACK：指令已回执或已终态，只审计（REQ-041）
            domainEventService.recordByDevice(OpsEnum.EventType.COMMAND_STATUS, cmdNo,
                    DeviceEnum.CmdStatus.getType(command.getCmdStatus()).getDesc(), "迟到/重复 ACK，状态不变");
            return false;
        }
        // ackCode 非 accepted（rejected/busy/failed）→ 设备拒绝执行：cmd 2→5 失败，出水单联动转异常待补偿（缺省 accepted 兼容旧设备/模拟器）
        if (StrUtil.isNotBlank(ackCode) && !"accepted".equalsIgnoreCase(ackCode)) {
            transitByDevice(command, DeviceEnum.CmdStatus.FAILED, cmd -> {
                cmd.setFinishTime(deviceTimeOrServer(command, ackTs));
                cmd.setFailReason(StrUtil.maxLength("设备拒绝执行(ackCode=" + ackCode + ")", 490));
            });
            if (ObjectUtil.isNotNull(command.getOrderId())) {
                markOrderAbnormal(command.getOrderId(), "设备拒绝出水(ackCode=" + ackCode + ")");
            }
            return true;
        }
        // 正常回执：2→3已回执；ACK_TIME 存设备 ackTs（P0-06，非法/缺失回退服务器时间+审计）
        transitByDevice(command, DeviceEnum.CmdStatus.ACKED, cmd -> cmd.setAckTime(deviceTimeOrServer(command, ackTs)));
        // 出水单联动：2已支付→3出水中（条件 UPDATE，幂等；非 2 态不覆盖仅审计）
        if (ObjectUtil.isNotNull(command.getOrderId())) {
            int moved = tradeOrderTxService.onCommandAcked(command.getOrderId());
            if (moved == 0) {
                domainEventService.recordByDevice(OpsEnum.EventType.ORDER_STATUS, cmdNo,
                        null, "订单非已支付态，ACK 不推进出水中（orderId=" + command.getOrderId() + "）");
            }
        }
        return true;
    }

    @Override
    public boolean onResult(Long deviceId, String cmdNo, boolean success, String resultPayload, String failReason, String finishTs) {
        WsCommand command = getOne(Wrappers.lambdaQuery(WsCommand.class).eq(WsCommand::getCmdNo, cmdNo));
        if (ObjectUtil.isNull(command)) {
            domainEventService.recordByDevice(OpsEnum.EventType.COMMAND_STATUS, cmdNo, null, "收到未知指令号执行结果，忽略");
            return false;
        }
        if (ObjectUtil.notEqual(command.getDeviceId(), deviceId)) {
            domainEventService.recordByDevice(OpsEnum.EventType.COMMAND_STATUS, cmdNo,
                    null, "错设备执行结果：指令目标设备 " + command.getDeviceId() + "，实际上报设备 " + deviceId + "，忽略");
            return false;
        }
        Long actualMl = null;
        if (ObjectUtil.isNotNull(command.getOrderId())) {
            try {
                actualMl = parseActualMl(resultPayload);
            } catch (JbkException e) {
                domainEventService.recordReliable(OpsEnum.EventType.ORDER_STATUS, cmdNo, null,
                        "设备结果实际水量非法，拒绝写入指令终态和资金结算：" + e.getMsg());
                throw e;
            }
        }
        // SENT 与 ACKED 均可接收执行结果，以兼容设备未上报 ACK 而直接上报 result 的情况。
        // 已进入终态的指令不再推进状态，但仍尝试结算（幂等）：防止「指令已 SUCCESS 但结算事务失败」后
        // P0-07 同 msgId 重投时因指令终态而漏结算——结算以订单终态为幂等闸，重复调用安全（L1d）。
        boolean canTransit = DeviceEnum.CmdStatus.SENT.getValue() == command.getCmdStatus()
                || DeviceEnum.CmdStatus.ACKED.getValue() == command.getCmdStatus();
        boolean transited = false;
        if (canTransit) {
            DeviceEnum.CmdStatus target = success ? DeviceEnum.CmdStatus.SUCCESS : DeviceEnum.CmdStatus.FAILED;
            // 指令终态并原样保存 RESULT_PAYLOAD（含实际水量），FINISH_TIME 存设备 finishTs（P0-06）
            transitByDevice(command, target, cmd -> {
                cmd.setFinishTime(deviceTimeOrServer(command, finishTs));
                cmd.setResultPayload(resultPayload);
                if (!success) {
                    cmd.setFailReason(StrUtil.maxLength(StrUtil.blankToDefault(failReason, "设备回报执行失败"), 490));
                }
            });
            transited = true;
        } else {
            domainEventService.recordByDevice(OpsEnum.EventType.COMMAND_STATUS, cmdNo,
                    DeviceEnum.CmdStatus.getType(command.getCmdStatus()).getDesc(), "迟到执行结果，指令状态不变（仍尝试结算）");
        }

        // L1d 结算：出水单按 v1.3 规则回写实际水量+订单终态+差额/退款补偿（原子，幂等闸=订单终态）
        if (ObjectUtil.isNotNull(command.getOrderId())) {
            boolean settled = tradeOrderTxService.settleWaterOrder(command.getOrderId(), success, actualMl);
            if (settled && !success) {
                // 缺口A：success=false 但已按实际量结算 → 订单转 6异常待核 + 出水异常告警
                alarmService.raise(command.getDeviceId(), OpsEnum.AlarmType.DISPENSE_ABNORMAL, 2,
                        "出水结果异常待核：订单 " + command.getOrderId() + " actualMl=" + actualMl, cmdNo);
            }
        }
        return transited;
    }

    /** 从 result 报文严格解析实际出水量；只有显式合法整数 0 才代表零出水。 */
    private Long parseActualMl(String resultPayload) {
        if (StrUtil.isBlank(resultPayload) || !JSONUtil.isTypeJSON(resultPayload)) {
            throw new JbkException("设备结果缺少合法 JSON 载荷");
        }
        Object raw;
        try {
            raw = JSONUtil.parseObj(resultPayload).get("actualMl");
        } catch (Exception e) {
            throw new JbkException("设备结果不是合法 JSON 对象");
        }
        if (raw == null) {
            throw new JbkException("设备结果缺少 actualMl");
        }
        try {
            BigDecimal decimal;
            if (raw instanceof Number) {
                decimal = new BigDecimal(raw.toString());
            } else if (raw instanceof String && ((String) raw).matches("^(?:0|[1-9]\\d*)$")) {
                decimal = new BigDecimal((String) raw);
            } else {
                throw new ArithmeticException("actualMl 不是规范整数");
            }
            if (decimal.stripTrailingZeros().scale() > 0) {
                throw new ArithmeticException("actualMl 包含小数");
            }
            return WaterBillingMath.requireNonNegativeMl(decimal.longValueExact(), "实际水量");
        } catch (ArithmeticException e) {
            throw new JbkException("actualMl 非法或超出 Long 范围");
        }
    }

    @Override
    public int scanTimeout() {
        String now = DateUtils.time();
        String ackDeadline = DateUtils.timeTransition(
                DateUtils.addDateSeconds(new Date(), -ACK_TIMEOUT_SECONDS));
        String resultDeadline = DateUtils.timeTransition(
                DateUtils.addDateSeconds(new Date(), -RESULT_TIMEOUT_SECONDS));
        String pendingDeadline = DateUtils.timeTransition(
                DateUtils.addDateSeconds(new Date(), -PENDING_TIMEOUT_SECONDS));

        // varchar(14) 纯数字等长，字典序即时间序。三组超时：
        // - PENDING 超时以 CREATE_TIME 为基准（PENDING 无 SENT_TIME；N-06：save 后未 publish 崩溃残留兜底）；
        // - SENT 超时以 SENT_TIME（服务器下发时间）为基准；
        // - ACKED→result 超时以 UPDATE_TIME 为基准（M3/R2：ACK_TIME 已改存设备上报时间不可信，改用推进 ACKED 时
        //   刷新的服务器 UPDATE_TIME，依赖不变量「ACKED 态期间无其它 UPDATE」，将来加 ACKED 期间进度更新须重评）。
        List<WsCommand> timeoutList = list(Wrappers.lambdaQuery(WsCommand.class)
                .and(w -> w
                        .and(p -> p.eq(WsCommand::getCmdStatus, DeviceEnum.CmdStatus.PENDING.getValue())
                                .lt(WsCommand::getCreateTime, pendingDeadline))
                        .or(a -> a.eq(WsCommand::getCmdStatus, DeviceEnum.CmdStatus.SENT.getValue())
                                .lt(WsCommand::getSentTime, ackDeadline))
                        .or(b -> b.eq(WsCommand::getCmdStatus, DeviceEnum.CmdStatus.ACKED.getValue())
                                .lt(WsCommand::getUpdateTime, resultDeadline))));
        int flipped = 0;
        for (WsCommand command : timeoutList) {
            int oldStatus = command.getCmdStatus();
            String oldDesc = DeviceEnum.CmdStatus.getType(oldStatus).getDesc();
            boolean pending = DeviceEnum.CmdStatus.PENDING.getValue() == oldStatus;
            // PENDING 中断 → 5失败（下发未完成）；SENT/ACKED 无回执/无结果 → 6超时
            DeviceEnum.CmdStatus target = pending ? DeviceEnum.CmdStatus.FAILED : DeviceEnum.CmdStatus.TIMEOUT;
            String failReason;
            if (pending) {
                failReason = "下发中断（服务重启/崩溃），超 " + PENDING_TIMEOUT_SECONDS + " 秒未离开待下发";
            } else if (DeviceEnum.CmdStatus.SENT.getValue() == oldStatus) {
                failReason = "下发后 " + ACK_TIMEOUT_SECONDS + " 秒未收到设备回执";
            } else {
                failReason = "回执后 " + RESULT_TIMEOUT_SECONDS + " 秒未收到执行结果";
            }
            // N-05 条件 UPDATE：仅当库中仍为原态才翻终态，避免与并发到达的 ack/result 抢覆盖
            int affected = getBaseMapper().update(null, Wrappers.lambdaUpdate(WsCommand.class)
                    .set(WsCommand::getCmdStatus, target.getValue())
                    .set(WsCommand::getFinishTime, now)
                    .set(WsCommand::getFailReason, failReason)
                    .eq(WsCommand::getId, command.getId())
                    .eq(WsCommand::getCmdStatus, oldStatus));
            if (affected == 0) {
                continue;
            }
            flipped++;
            domainEventService.record(OpsEnum.EventType.COMMAND_STATUS, command.getCmdNo(), oldDesc, target.getDesc());
            alarmService.raise(command.getDeviceId(), OpsEnum.AlarmType.COMMAND_TIMEOUT,
                    2, "指令 " + command.getCmdNo() + "（" + failReason + "）", command.getCmdNo());
            // 出水指令中断/超时：订单联动转异常待补偿（已扣款不回滚，补偿归 L1d）
            if (ObjectUtil.isNotNull(command.getOrderId())) {
                markOrderAbnormal(command.getOrderId(), "出水指令" + (pending ? "下发中断" : "超时")
                        + "（" + failReason + "），待补偿处理");
            }
        }
        return flipped;
    }

    /** 平台指令号：CMD + 时间戳 + 6 位随机数；唯一性由 uk_cmd_no 约束保证。 */
    private String generateCmdNo() {
        return "CMD" + DateUtils.time() + RandomUtil.randomNumbers(6);
    }

    /** 状态推进 + 领域事件（后台会话触发）；N-05 条件 UPDATE 防并发覆盖终态 */
    private void transit(WsCommand command, DeviceEnum.CmdStatus target, java.util.function.Consumer<WsCommand> filler) {
        int oldStatus = command.getCmdStatus();
        String oldDesc = DeviceEnum.CmdStatus.getType(oldStatus).getDesc();
        command.setCmdStatus(target.getValue());
        filler.accept(command);
        if (conditionalTransit(command, oldStatus) == 0) {
            domainEventService.record(OpsEnum.EventType.COMMAND_STATUS, command.getCmdNo(), oldDesc,
                    "并发跳过：库态已非「" + oldDesc + "」，不覆盖");
            return;
        }
        domainEventService.record(OpsEnum.EventType.COMMAND_STATUS, command.getCmdNo(), oldDesc, target.getDesc());
    }

    /** 状态推进 + 领域事件（设备上行触发，portal=设备）；N-05 条件 UPDATE 防并发覆盖终态 */
    private void transitByDevice(WsCommand command, DeviceEnum.CmdStatus target, java.util.function.Consumer<WsCommand> filler) {
        int oldStatus = command.getCmdStatus();
        String oldDesc = DeviceEnum.CmdStatus.getType(oldStatus).getDesc();
        command.setCmdStatus(target.getValue());
        filler.accept(command);
        if (conditionalTransit(command, oldStatus) == 0) {
            domainEventService.recordByDevice(OpsEnum.EventType.COMMAND_STATUS, command.getCmdNo(), oldDesc,
                    "并发跳过：库态已非「" + oldDesc + "」，不覆盖");
            return;
        }
        domainEventService.recordByDevice(OpsEnum.EventType.COMMAND_STATUS, command.getCmdNo(), oldDesc, target.getDesc());
    }

    /**
     * 条件 UPDATE 推进指令状态（N-05）：仅当库中仍为 oldStatus 才落库，SET 用 command 的非空字段。
     * affected==0 表示已被并发/超时改写，调用方仅审计不覆盖。
     */
    private int conditionalTransit(WsCommand command, int oldStatus) {
        // 用 Mapper 取影响行数（ServiceImpl.update 返回 boolean，无法判并发抢占）
        return getBaseMapper().update(command, Wrappers.lambdaUpdate(WsCommand.class)
                .eq(WsCommand::getId, command.getId())
                .eq(WsCommand::getCmdStatus, oldStatus));
    }

    /**
     * 设备时间双存兜底（P0-06）：设备上报时间为合法 14 位 yyyyMMddHHmmss 则采用，否则回退服务器时间并记审计。
     */
    private String deviceTimeOrServer(WsCommand command, String deviceTs) {
        if (StrUtil.isNotBlank(deviceTs) && deviceTs.length() == 14 && StrUtil.isNumeric(deviceTs)) {
            return deviceTs;
        }
        String server = DateUtils.time();
        domainEventService.recordByDevice(OpsEnum.EventType.COMMAND_STATUS, command.getCmdNo(),
                deviceTs, "设备时间缺失/非法，回退服务器时间 " + server);
        return server;
    }

    /** 填充设备编号/名称派生字段 */
    private void fillDeviceInfo(List<WsCommandVo> voList) {
        if (CollUtil.isEmpty(voList)) {
            return;
        }
        List<Long> deviceIdList = voList.stream()
                .map(WsCommandVo::getDeviceId).filter(ObjectUtil::isNotNull).distinct().collect(Collectors.toList());
        if (CollUtil.isEmpty(deviceIdList)) {
            return;
        }
        Map<Long, WsDevice> deviceMap = deviceMapper.selectBatchIds(deviceIdList).stream()
                .collect(Collectors.toMap(WsDevice::getId, Function.identity()));
        voList.forEach(vo -> {
            WsDevice device = deviceMap.get(vo.getDeviceId());
            if (ObjectUtil.isNotNull(device)) {
                vo.setDeviceNo(device.getDeviceNo());
                vo.setDeviceName(device.getDeviceName());
            }
        });
    }
}
