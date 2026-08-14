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
import com.jbk.serve.service.device.DeviceReportTime;
import com.jbk.serve.service.device.IDispenseDispatchTxService;
import com.jbk.serve.service.device.IWaterCommandFailureTxService;
import com.jbk.serve.service.device.IWsCommandAckTxService;
import com.jbk.serve.service.device.IWsCommandService;
import com.jbk.serve.service.ops.IWsAlarmService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.serve.service.trade.ITradeOrderTxService;
import com.jbk.serve.service.trade.WaterBillingMath;
import com.jbk.tool.config.mqtt.MqttConnectionManager;
import com.jbk.serve.service.device.DeviceCommandRisk;
import com.jbk.tool.consts.device.DeviceEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.device.bo.WsCommandBo;
import com.jbk.tool.data.device.po.WsCommand;
import com.jbk.tool.data.device.po.WsCommandBatch;
import com.jbk.tool.data.device.po.WsDevice;
import com.jbk.tool.data.device.vo.WsCommandVo;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import com.jbk.tool.utils.OptionalUtils;
import com.jbk.serve.mapper.device.WsDeviceMapper;
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
    /** 批次展开中断兜底：覆盖 PENDING/SENT/ACKED 最长正常终态窗口后再对账，避免误判慢设备。 */
    private static final int BATCH_RECONCILE_SECONDS = 180;
    /**
     * 后台单发入口受理的指令类型：由 {@link DeviceCommandRisk} 按档位派生（D-423）——
     * 恒加闸档（紧急停机、价格同步）在结构上进不来，不再靠手写集合互相对齐。
     */
    private static final Set<Integer> CONSOLE_ALLOWED_TYPES = DeviceCommandRisk.consoleAllowedTypes();

    @Autowired
    private WsDeviceMapper deviceMapper;
    @Autowired
    private com.jbk.serve.mapper.device.WsCommandBatchMapper commandBatchMapper;
    @Autowired
    private ITradeOrderTxService tradeOrderTxService;
    @Autowired
    private IWsAlarmService alarmService;
    @Autowired
    private IWsDomainEventService domainEventService;
    /** 出水指令准备事务（B20）：档案锁定、核验、建指令与抢占同事务，publish 留在事务外。 */
    @Autowired
    private IDispenseDispatchTxService dispatchTxService;
    /** REQ-213 参数快照：独立 Bean，回写自带事务边界（onResult 本身无事务）。 */
    @Autowired
    private com.jbk.serve.service.device.IDeviceParamService deviceParamService;
    /** ACK 事务边界（B18）：独立 Bean，避免同类自调用导致 @Transactional 失效。 */
    @Autowired
    private IWsCommandAckTxService ackTxService;
    /** 开始出水失败/超时收敛事务：指令、订单和可靠事件同生共死。 */
    @Autowired
    private IWaterCommandFailureTxService waterCommandFailureTxService;
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
        // 参数类指令的报文校验唯一实现在 DeviceParamPayload，与批量路径共用（REQ-213）——
        // 任何一处放松，运营就能从那一处把畸形报文发到设备上
        if (DeviceParamServiceImpl.isParamBearing(commandBo.getCmdType())) {
            com.jbk.serve.service.device.DeviceParamPayload.require(commandBo.getCmdPayload(),
                    deviceParamService.enabledDefinitions(commandBo.getCmdType()));
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
        doSend(command, device.getDeviceNo());
        return command.getId();
    }

    @Override
    public Long sendDispenseForOrder(Long orderId) {
        // 档案锁定、核验、建指令与 CMD_ID 抢占全收进一个事务（B20）；MQTT 是网络 IO 留在事务外，
        // 异常由编排层终态闸把已扣款订单收敛到「6 异常待补偿」
        IDispenseDispatchTxService.Prepared prepared = dispatchTxService.prepare(orderId);
        if (!prepared.created()) {
            return prepared.commandId();
        }
        doSend(prepared.command(), prepared.deviceNo());
        return prepared.commandId();
    }

    /**
     * 统一下发：先持久化 1待下发→2已下发，再发布下行报文。
     *
     * <p>状态必须先落库：设备可能在 publish 返回前立即回 ACK；若仍是 PENDING，ACK 会被当作
     * 非法前态丢弃并最终误判超时。发布失败则从 SENT 精确转 FAILED，仍保留完整尝试证据。</p>
     */
    private void doSend(WsCommand command, String deviceNo) {
        if (!transit(command, DeviceEnum.CmdStatus.SENT, cmd -> cmd.setSentTime(DateUtils.time()))) {
            return;
        }
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
            mqtt.publishCommand(deviceNo, downPayload.toString());
        } catch (Exception e) {
            log.error("指令下发失败 cmdNo={} device={}", command.getCmdNo(), deviceNo, e);
            if (linksWaterOrder(command)) {
                String finishTime = DateUtils.time();
                String reason = StrUtil.maxLength(
                        "下发失败：" + StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()), 490);
                boolean moved = waterCommandFailureTxService.converge(command.getCmdNo(),
                        DeviceEnum.CmdStatus.SENT.getValue(), DeviceEnum.CmdStatus.FAILED,
                        finishTime, reason, "出水指令下发失败，待补偿处理", "下发失败");
                if (moved) {
                    command.setCmdStatus(DeviceEnum.CmdStatus.FAILED.getValue())
                            .setFinishTime(finishTime)
                            .setFailReason(reason);
                    accumulateBatchIfTerminal(command);
                }
                return;
            }
            transit(command, DeviceEnum.CmdStatus.FAILED, cmd -> {
                cmd.setFinishTime(DateUtils.time());
                cmd.setFailReason(StrUtil.maxLength(
                        "下发失败：" + StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()), 490));
            });
        }
    }

    @Override
    public boolean onAck(Long deviceId, String cmdNo, String ackTs, String ackCode) {
        // 指令锁定与重读、判定、订单联动、可靠事件全在 ACK 事务内完成；本方法不预读指令——
        // 预读对象是可变旧快照，不能当权威来源
        WsCommand moved = ackTxService.applyAck(deviceId, cmdNo, ackCode, ackTs);
        if (ObjectUtil.isNull(moved)) {
            return false;
        }
        // 批量子指令终态回写聚合：ACK 拒绝是批次的失败腿之一（accepted 落 ACKED 非终态不计）
        accumulateBatchIfTerminal(moved);
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
        // 实际水量与资金结算契约只属于出水指令（cmdType=1）：紧急停止（cmdType=2）同带 ORDER_ID
        // 但不承载出水量，订单终局由原出水指令 result 或 120s 结果超时收口
        boolean dispenseSettlement = ObjectUtil.equal(command.getCmdType(),
                DeviceEnum.CmdType.START_DISPENSE.getValue())
                && ObjectUtil.isNotNull(command.getOrderId());
        Long actualMl = null;
        if (dispenseSettlement) {
            try {
                actualMl = parseActualMl(resultPayload);
            } catch (JbkException e) {
                domainEventService.recordReliable(OpsEnum.EventType.ORDER_STATUS, cmdNo, null,
                        "设备结果实际水量非法，拒绝写入指令终态和资金结算：" + e.getMsg());
                throw e;
            }
        }
        // SENT/ACKED 均可收执行结果（设备可能不报 ACK 直接报 result）；已终态不再推进但仍尝试结算——
        // 防「指令已 SUCCESS 但结算失败」后重投漏结算，结算以订单终态为幂等闸（L1d）
        boolean canTransit = DeviceEnum.CmdStatus.SENT.getValue() == command.getCmdStatus()
                || DeviceEnum.CmdStatus.ACKED.getValue() == command.getCmdStatus();
        boolean transited = false;
        if (canTransit) {
            // partial 由 result 报文显式声明：success=true 但只完成一部分，不是成功——
            // 落 7部分完成，批量聚合与 PC 展示按「未全成」处理（任务书 S8/S11）
            boolean partial = success && isPartialResult(resultPayload);
            DeviceEnum.CmdStatus target = partial ? DeviceEnum.CmdStatus.PARTIAL
                    : success ? DeviceEnum.CmdStatus.SUCCESS : DeviceEnum.CmdStatus.FAILED;
            // 指令终态并原样保存 RESULT_PAYLOAD（含实际水量），FINISH_TIME 存设备 finishTs（P0-06）
            transited = transitByDevice(command, target, cmd -> {
                cmd.setFinishTime(deviceTimeOrServer(command, finishTs));
                cmd.setResultPayload(resultPayload);
                if (!success) {
                    cmd.setFailReason(StrUtil.maxLength(StrUtil.blankToDefault(failReason, "设备回报执行失败"), 490));
                }
            });
        } else {
            domainEventService.recordByDevice(OpsEnum.EventType.COMMAND_STATUS, cmdNo,
                    DeviceEnum.CmdStatus.getType(command.getCmdStatus()).getDesc(), "迟到执行结果，指令状态不变（仍尝试结算）");
        }

        // REQ-213 参数快照回写：只认 SUCCESS（partial/失败回写会显示「已同步」而设备没改）；
        // 值取下发的 CMD_PAYLOAD 不取 RESULT_PAYLOAD（设备回什么由厂家定，V-12.3 未答复）；
        // transited 是必要条件——迟到 result 不得回写，数据层另有 SOURCE_CMD_ID 单调守卫兜底
        if (transited && success && DeviceParamServiceImpl.isParamBearing(command.getCmdType())
                && !isPartialResult(resultPayload)) {
            try {
                deviceParamService.applySyncedParams(command.getDeviceId(), command.getCmdType(),
                        command.getId(), cmdNo, command.getCmdPayload(),
                        deviceTimeOrServer(command, finishTs));
            }
            catch (RuntimeException snapshotFailure) {
                // 快照是运维可见性不是资金：写失败不回滚指令终态、不让设备重投，但必须留痕
                log.error("设备参数快照回写失败 cmdNo={} deviceId={}", cmdNo, command.getDeviceId(), snapshotFailure);
                domainEventService.recordByDevice(OpsEnum.EventType.DEVICE_STATUS, cmdNo, null,
                        "设备参数快照回写失败（指令终态不受影响）：" + snapshotFailure.getMessage());
            }
        }

        // L1d 结算：出水单按 v1.3 规则回写实际水量+订单终态+差额/退款补偿（原子，幂等闸=订单终态）
        if (dispenseSettlement) {
            boolean settled = tradeOrderTxService.settleWaterOrder(command.getOrderId(), success, actualMl);
            if (settled && !success) {
                // 缺口A：success=false 但已按实际量结算 → 订单转 6异常待核 + 出水异常告警
                alarmService.raise(command.getDeviceId(), OpsEnum.AlarmType.DISPENSE_ABNORMAL, 2,
                        "出水结果异常待核：订单 " + command.getOrderId() + " actualMl=" + actualMl, cmdNo);
            }
        }
        return transited;
    }

    /** result 报文显式携带 partial=true 才算部分完成；缺失/非布尔一律按非 partial 处理。 */
    private boolean isPartialResult(String resultPayload) {
        if (StrUtil.isBlank(resultPayload) || !JSONUtil.isTypeJSON(resultPayload)) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(JSONUtil.parseObj(resultPayload).getBool("partial"));
        } catch (Exception malformed) {
            return false;
        }
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

        // varchar(14) 纯数字等长，字典序即时间序。三组超时基准：PENDING 以 CREATE_TIME（N-06 崩溃残留）、
        // SENT 以 SENT_TIME、ACKED 以 UPDATE_TIME（M3/R2：ACK_TIME 存设备时间不可信；依赖不变量
        // 「ACKED 态期间无其它 UPDATE」，将来加 ACKED 期间进度更新须重评）
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
            boolean waterCommand = linksWaterOrder(command);
            if (waterCommand) {
                String scene = pending ? "下发中断"
                        : DeviceEnum.CmdStatus.SENT.getValue() == oldStatus ? "ACK超时" : "结果超时";
                String orderReason = "出水指令" + (pending ? "下发中断" : "超时")
                        + "（" + failReason + "），待补偿处理";
                try {
                    if (!waterCommandFailureTxService.converge(command.getCmdNo(), oldStatus, target,
                            now, failReason, orderReason, scene)) {
                        continue;
                    }
                } catch (RuntimeException failure) {
                    // 事务失败时指令仍保持可扫描前态；本轮跳过，下一轮继续重试，不阻断其它指令。
                    log.error("开始出水指令超时收敛失败，保留活动态等待重试 cmdNo={}", command.getCmdNo(), failure);
                    continue;
                }
            } else {
                // 非出水指令不关联资金订单，维持原有单表 CAS 状态机。
                int affected = getBaseMapper().update(null, Wrappers.lambdaUpdate(WsCommand.class)
                        .set(WsCommand::getCmdStatus, target.getValue())
                        .set(WsCommand::getFinishTime, now)
                        .set(WsCommand::getFailReason, failReason)
                        .eq(WsCommand::getId, command.getId())
                        .eq(WsCommand::getCmdStatus, oldStatus));
                if (affected == 0) {
                    continue;
                }
                domainEventService.record(OpsEnum.EventType.COMMAND_STATUS,
                        command.getCmdNo(), oldDesc, target.getDesc());
            }
            flipped++;
            // 批量子指令超时同样回写聚合：本路径直连 mapper 不经 conditionalTransit，
            // 漏掉这一笔批次会永远停在处理中（E2E-05 S11 的超时腿）
            command.setCmdStatus(target.getValue());
            accumulateBatchIfTerminal(command);
            alarmService.raise(command.getDeviceId(), OpsEnum.AlarmType.COMMAND_TIMEOUT,
                    2, "指令 " + command.getCmdNo() + "（" + failReason + "）", command.getCmdNo());
        }
        reconcileInterruptedBatches(now);
        return flipped;
    }

    /**
     * 对账超过完整指令窗口仍在处理中的批次。已有子指令必须全部进入终态后才允许收敛，
     * 未展开目标按失败计入；这样进程在批量展开中途退出也不会留下永久“处理中”。
     */
    private void reconcileInterruptedBatches(String now) {
        String deadline = DateUtils.timeTransition(
                DateUtils.addDateSeconds(new Date(), -BATCH_RECONCILE_SECONDS));
        List<WsCommandBatch> stale = commandBatchMapper.selectList(
                Wrappers.lambdaQuery(WsCommandBatch.class)
                        .eq(WsCommandBatch::getBatchStatus, 1)
                        .lt(WsCommandBatch::getCreateTime, deadline));
        int reconciled = 0;
        for (WsCommandBatch batch : stale) {
            reconciled += commandBatchMapper.reconcileInterrupted(batch.getId(), now);
        }
        if (reconciled > 0) {
            log.warn("批量指令中断对账：本次收敛 {} 个陈旧批次", reconciled);
        }
    }

    @Override
    public Long sendAsBatchChild(Long batchId, Long deviceId, int cmdType, String cmdPayload, Long orderId) {
        // R2-P1-2 纵深防御：这是批量链路真正建指令并 publish 的入口，新增调用方若忘了复验，
        // 参数会从这里直接落到设备上；校验唯一实现仍是 DeviceParamPayload
        if (DeviceParamServiceImpl.isParamBearing(cmdType)) {
            com.jbk.serve.service.device.DeviceParamPayload.require(
                    cmdPayload, deviceParamService.enabledDefinitions(cmdType));
        }
        WsDevice device = deviceMapper.selectById(deviceId);
        OptionalUtils.nullToElseThrow(device, "目标设备不存在");
        WsCommand command = new WsCommand()
                .setCmdNo(generateCmdNo())
                .setDeviceId(device.getId())
                .setOrderId(orderId)
                .setCmdType(cmdType)
                .setCmdPayload(StrUtil.blankToDefault(cmdPayload, "{}"))
                .setCmdStatus(DeviceEnum.CmdStatus.PENDING.getValue())
                .setBatchId(batchId)
                .setRetryCount(0);
        // 撞 uk_cmd_batch_device 即同批次同设备重复展开：批量任务重放的幂等由唯一键收敛，
        // 调用方捕获后跳过该设备而不是让整批失败（铁律②：不做应用层预查重）
        save(command);
        domainEventService.record(OpsEnum.EventType.COMMAND_STATUS, command.getCmdNo(),
                null, DeviceEnum.CmdStatus.PENDING.getDesc() + "（批量 " + batchId + "）");
        doSend(command, device.getDeviceNo());
        return command.getId();
    }

    /**
     * 只有「开始出水」失败才代表取水没做成；紧急停止（cmdType=2）同带 ORDER_ID，但它失败
     * 只说明停不下来，订单终局仍由原出水指令 result 或结果超时收口。
     */
    private boolean linksWaterOrder(WsCommand command) {
        return ObjectUtil.isNotNull(command.getOrderId())
                && ObjectUtil.equal(command.getCmdType(), DeviceEnum.CmdType.START_DISPENSE.getValue());
    }

    /** 平台指令号：CMD + 时间戳 + 6 位随机数；唯一性由 uk_cmd_no 约束保证。 */
    private String generateCmdNo() {
        return "CMD" + DateUtils.time() + RandomUtil.randomNumbers(6);
    }

    /** 状态推进 + 领域事件（后台会话触发）；N-05 条件 UPDATE 防并发覆盖终态 */
    private boolean transit(WsCommand command, DeviceEnum.CmdStatus target,
                            java.util.function.Consumer<WsCommand> filler) {
        int oldStatus = command.getCmdStatus();
        String oldDesc = DeviceEnum.CmdStatus.getType(oldStatus).getDesc();
        command.setCmdStatus(target.getValue());
        filler.accept(command);
        if (conditionalTransit(command, oldStatus) == 0) {
            domainEventService.record(OpsEnum.EventType.COMMAND_STATUS, command.getCmdNo(), oldDesc,
                    "并发跳过：库态已非「" + oldDesc + "」，不覆盖");
            return false;
        }
        domainEventService.record(OpsEnum.EventType.COMMAND_STATUS, command.getCmdNo(), oldDesc, target.getDesc());
        return true;
    }

    /** 状态推进 + 领域事件（设备上行触发，portal=设备）；N-05 条件 UPDATE 防并发覆盖终态 */
    private boolean transitByDevice(WsCommand command, DeviceEnum.CmdStatus target,
                                    java.util.function.Consumer<WsCommand> filler) {
        int oldStatus = command.getCmdStatus();
        String oldDesc = DeviceEnum.CmdStatus.getType(oldStatus).getDesc();
        command.setCmdStatus(target.getValue());
        filler.accept(command);
        if (conditionalTransit(command, oldStatus) == 0) {
            domainEventService.recordByDevice(OpsEnum.EventType.COMMAND_STATUS, command.getCmdNo(), oldDesc,
                    "并发跳过：库态已非「" + oldDesc + "」，不覆盖");
            return false;
        }
        domainEventService.recordByDevice(OpsEnum.EventType.COMMAND_STATUS, command.getCmdNo(), oldDesc, target.getDesc());
        return true;
    }

    /**
     * 条件 UPDATE 推进指令状态（N-05）：仅当库中仍为 oldStatus 才落库，SET 用 command 的非空字段。
     * affected==0 表示已被并发/超时改写，调用方仅审计不覆盖。
     */
    private int conditionalTransit(WsCommand command, int oldStatus) {
        // 用 Mapper 取影响行数（ServiceImpl.update 返回 boolean，无法判并发抢占）
        int affected = getBaseMapper().update(command, Wrappers.lambdaUpdate(WsCommand.class)
                .eq(WsCommand::getId, command.getId())
                .eq(WsCommand::getCmdStatus, oldStatus));
        if (affected == 1) {
            accumulateBatchIfTerminal(command);
        }
        return affected;
    }

    /**
     * 批量子指令终态回写聚合（E2E-05）：全部终态路径都经 conditionalTransit 胜出方之后这一个内核，
     * CAS 保证每条子指令只进终态一次，聚合天然只触发一次。PARTIAL 计入失败列——
     * 部分完成不是成功（任务书 S8/S11）。
     */
    private void accumulateBatchIfTerminal(WsCommand command) {
        if (ObjectUtil.isNull(command.getBatchId())) {
            return;
        }
        int status = command.getCmdStatus();
        int success = DeviceEnum.CmdStatus.SUCCESS.getValue() == status ? 1 : 0;
        int timeout = DeviceEnum.CmdStatus.TIMEOUT.getValue() == status ? 1 : 0;
        int fail = (DeviceEnum.CmdStatus.FAILED.getValue() == status
                || DeviceEnum.CmdStatus.PARTIAL.getValue() == status) ? 1 : 0;
        if (success + fail + timeout == 0) {
            return;
        }
        String now = DateUtils.time();
        commandBatchMapper.accumulateChildTerminal(command.getBatchId(), success, fail, timeout, now);
        // 计数满则置聚合终态；前态 CAS 保证并发的最后两条子指令只有一个赢家置状态
        commandBatchMapper.settleIfComplete(command.getBatchId(), now);
    }

    /**
     * 设备时间双存兜底（P0-06）：设备上报时间为合法 14 位 yyyyMMddHHmmss 则采用，否则回退服务器时间并记审计。
     */
    private String deviceTimeOrServer(WsCommand command, String deviceTs) {
        String accepted = DeviceReportTime.acceptOrNull(deviceTs);
        if (accepted != null) {
            return accepted;
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
