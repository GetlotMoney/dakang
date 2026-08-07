package com.jbk.serve.device;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.device.WsDeviceMsgMapper;
import com.jbk.serve.mapper.device.WsDeviceTelemetryMapper;
import com.jbk.serve.mapper.device.WsFaultDictMapper;
import com.jbk.serve.service.device.IWsCommandService;
import com.jbk.serve.service.device.IWsDeviceService;
import com.jbk.serve.service.ops.IWsAlarmService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.ApiEnum;
import com.jbk.tool.consts.device.DeviceEnum;
import com.jbk.tool.consts.device.DeviceTopics;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.device.po.WsDevice;
import com.jbk.tool.data.device.po.WsDeviceMsg;
import com.jbk.tool.data.device.po.WsDeviceTelemetry;
import com.jbk.tool.data.device.po.WsFaultDict;
import com.jbk.tool.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 设备上行消息处理实现（MQTT 闭环核心）
 * <p>安全铁律 3：带 msgId 的消息（status/ack/result/replay）先按 uk_msg_id 落库去重，
 * 重复消息仅记录审计事件，不得重复推进状态；心跳与遥测属于 QoS0 周期数据，
 * 无 msgId 时按实时数据处理。</p>
 * <p>replay 补传的载荷保留原始 msgId。系统根据字段识别消息类型，并复用对应处理链；
 * 唯一约束保证断网补传不会重复计量（REQ-041）。</p>
 *
 * @author dakang
 * @since 2026-07-12
 */
@Slf4j
@Component
public class DeviceUplinkHandlerImpl implements DeviceUplinkHandler {

    /** msgId 处理失败最大重试次数（P0-07），达到后标 [FINAL] 不再重投 */
    private static final int MAX_MSG_RETRY = 3;
    /** HANDLE_REMARK 重试计数前缀匹配（锚定行首，防原因文本误判） */
    private static final Pattern RETRY_PATTERN = Pattern.compile("^\\[RETRY:(\\d+)]");

    @Autowired
    private IWsDeviceService deviceService;
    @Autowired
    private IWsCommandService commandService;
    @Autowired
    private IWsAlarmService alarmService;
    @Autowired
    private IWsDomainEventService domainEventService;
    @Autowired
    private WsDeviceMsgMapper deviceMsgMapper;
    @Autowired
    private WsDeviceTelemetryMapper telemetryMapper;
    @Autowired
    private WsFaultDictMapper faultDictMapper;

    @Override
    public void onUplink(String deviceNo, String kind, String payload) {
        if (!JSONUtil.isTypeJSON(payload)) {
            log.warn("设备上行报文非法 JSON，丢弃：device={} kind={} payload={}", deviceNo, kind, payload);
            return;
        }
        WsDevice device = deviceService.getOne(Wrappers.lambdaQuery(WsDevice.class)
                .eq(WsDevice::getDeviceNo, deviceNo));
        if (ObjectUtil.isNull(device)) {
            // 未建档设备缺少业务归属，仅记录审计事件，不执行自动注册或业务处理。
            domainEventService.recordByDevice(OpsEnum.EventType.DEVICE_STATUS, deviceNo,
                    null, "收到未建档设备上行消息（kind=" + kind + "），已忽略");
            return;
        }
        JSONObject json = JSONUtil.parseObj(payload);
        switch (kind) {
            case DeviceTopics.KIND_HEARTBEAT -> onHeartbeat(device);
            case DeviceTopics.KIND_STATUS -> withDedupe(device, json, payload, kind,
                    DeviceEnum.MsgType.STATUS, () -> handleStatus(device, json));
            case DeviceTopics.KIND_TELEMETRY -> onTelemetry(device, json);
            case DeviceTopics.KIND_ACK -> withDedupe(device, json, payload, kind,
                    DeviceEnum.MsgType.ACK, () -> handleAck(device, json));
            case DeviceTopics.KIND_RESULT -> withDedupe(device, json, payload, kind,
                    DeviceEnum.MsgType.RESULT, () -> handleResult(device, json));
            case DeviceTopics.KIND_REPLAY -> onReplay(device, json, payload);
            default -> log.warn("未知上行消息类型：device={} kind={}", deviceNo, kind);
        }
    }

    // ==================== 心跳（在线判定，REQ-028） ====================

    private void onHeartbeat(WsDevice device) {
        String now = DateUtils.time();
        // 心跳时间无条件刷新——它是离线扫描的判据，晚到的心跳也是真实心跳
        deviceService.update(Wrappers.lambdaUpdate(WsDevice.class)
                .eq(WsDevice::getId, device.getId())
                .set(WsDevice::getLastHeartbeat, now));
        // 转在线单独一条 CAS：WHERE 带前态，affected==1 的那一次心跳才是「恢复事件的赢家」。
        // 此前无前态的写法与离线扫描互相覆盖——扫描判离线、心跳同时到达，两边都无条件写，
        // 最终状态取决于谁后提交；且 wasOffline 取自事务外读到的旧行，会重复触发恢复事件。
        // 恢复事件、离线告警恢复、ONLINE 状态由同一次 CAS 胜出方触发，三者天然同源。
        int recovered = deviceService.getBaseMapper().update(null, Wrappers.lambdaUpdate(WsDevice.class)
                .set(WsDevice::getOnlineStatus, DeviceEnum.OnlineStatus.ONLINE.getValue())
                .set(WsDevice::getUpdateTime, now)
                .eq(WsDevice::getId, device.getId())
                .ne(WsDevice::getOnlineStatus, DeviceEnum.OnlineStatus.ONLINE.getValue()));
        if (recovered == 1) {
            String oldDesc = DeviceEnum.OnlineStatus.getType(device.getOnlineStatus()).getDesc();
            domainEventService.recordByDevice(OpsEnum.EventType.DEVICE_STATUS, device.getDeviceNo(),
                    oldDesc, DeviceEnum.OnlineStatus.ONLINE.getDesc());
            // 心跳恢复只恢复离线告警（任务书 3.6）：故障/滤芯告警各有自己的恢复来源
            alarmService.autoRecover(device.getId(), OpsEnum.AlarmType.DEVICE_OFFLINE, null);
        }
    }

    // ==================== 运行状态/故障（REQ-029/036/037） ====================

    private void handleStatus(WsDevice device, JSONObject json) {
        Integer runStatus = json.getInt("runStatus");
        // 非法状态值通过异常终止处理，并将消息状态记录为处理失败。
        DeviceEnum.RunStatus target = DeviceEnum.RunStatus.getType(runStatus);
        String statusTime = requireDeviceTime(json.getStr("ts"));
        String reportedFaultCode = StrUtil.trim(json.getStr("faultCode"));
        boolean faultState = target == DeviceEnum.RunStatus.FAULT;
        if (!faultState && StrUtil.isNotBlank(reportedFaultCode)) {
            // 运行态与故障码必须表达同一事实，否则 DeviceAvailability 会把“空闲+故障码”误判为可用。
            throw new IllegalArgumentException("非故障运行状态不得携带故障码");
        }
        // 设备明确上报故障却未给出厂商码时，仍以 UNKNOWN 形成阻断投影和高风险告警，不能静默放行。
        String faultCode = faultState
                ? StrUtil.blankToDefault(reportedFaultCode, "UNKNOWN")
                : null;

        int updated = deviceService.getBaseMapper().update(null, Wrappers.lambdaUpdate(WsDevice.class)
                .eq(WsDevice::getId, device.getId())
                .set(WsDevice::getRunStatus, runStatus)
                .set(WsDevice::getLastFaultCode, faultCode)
                .set(WsDevice::getLastStatusDeviceTime, statusTime)
                // 状态补传必须服从设备时间。旧报文只保留原始消息证据，不得回滚当前运行投影。
                .and(w -> w.isNull(WsDevice::getLastStatusDeviceTime)
                        .or().le(WsDevice::getLastStatusDeviceTime, statusTime)));
        if (updated == 0) {
            domainEventService.recordByDevice(OpsEnum.EventType.DEVICE_STATUS, device.getDeviceNo(),
                    null, "忽略早于当前设备状态的补传消息（deviceTime=" + statusTime + "）");
            return;
        }

        if (ObjectUtil.notEqual(device.getRunStatus(), runStatus)) {
            String oldDesc = DeviceEnum.RunStatus.getType(device.getRunStatus()).getDesc();
            domainEventService.recordByDevice(OpsEnum.EventType.DEVICE_STATUS, device.getDeviceNo(),
                    oldDesc, target.getDesc());
        }
        if (StrUtil.isNotBlank(device.getLastFaultCode())
                && !StrUtil.equals(device.getLastFaultCode(), faultCode)) {
            // 恢复或切换到另一故障码时释放旧活动键，否则历史故障会永久挂在告警中心。
            alarmService.autoRecover(device.getId(), OpsEnum.AlarmType.FAULT_CODE, device.getLastFaultCode());
        }

        // 根据故障字典判定告警级别与下单阻断规则；取水链路读取同一字典生成用户提示。
        if (StrUtil.isNotBlank(faultCode)) {
            // 读全部有效行而不是 LIMIT 1：同码多条时任选一条，会把「一条严重阻断、一条提示不阻断」
            // 静默压成不告警。0 行=未登记按高风险；多行=配置冲突同样按最高风险 fail-closed。
            java.util.List<WsFaultDict> faults = faultDictMapper.selectByCodeForShare(faultCode);
            boolean unknown = faults.isEmpty();
            boolean conflict = faults.size() > 1;
            WsFaultDict fault = unknown || conflict ? null : faults.get(0);
            // 未登记故障码按未知高风险 fail-closed（任务书 3.6）：level=3 产告警并由
            // 可用性策略阻断取水。兜底成 2 会让厂商新增故障码在字典跟上之前静默放行取水。
            int level = ObjectUtil.isNotNull(fault) ? fault.getFaultLevel() : 3;
            String faultName = ObjectUtil.isNotNull(fault) ? fault.getFaultName()
                    : (conflict ? "故障码字典存在 " + faults.size() + " 条冲突配置（按高风险处理）"
                            : "未登记故障码（按高风险处理）");
            boolean blockOrder = ObjectUtil.isNull(fault)
                    || ApiEnum.Flag.YES.value() == fault.getBlockOrderFlag();
            if (level >= 3 || blockOrder) {
                alarmService.raise(device.getId(), OpsEnum.AlarmType.FAULT_CODE, level,
                        "设备上报故障 " + faultCode + "（" + faultName + "）"
                                + (blockOrder ? "，该故障阻断下单" : ""), faultCode);
            }
        }
    }

    // ==================== 遥测（REQ-038/039 骨架） ====================

    private void onTelemetry(WsDevice device, JSONObject json) {
        // 逐字段范围校验（任务书 5.3）：非法值置 NULL 而不是整条丢弃——一个坏字段
        // 不该拖垮其余合法读数；置 NULL 后页面按「暂无数据」降级展示，绝不伪造数值。
        // 上界防的是溢出/错单位（ppm 报成 ppb、温度报成毫度），负值只有信号强度合法。
        WsDeviceTelemetry telemetry = new WsDeviceTelemetry()
                .setDeviceId(device.getId())
                .setTdsValue(sanitizeRange(json.getInt("tds"), 0, 10000))
                .setRawTdsValue(sanitizeRange(json.getInt("rawTds"), 0, 10000))
                .setWaterTemp(sanitizeRange(json.getInt("waterTemp"), -30, 100))
                .setSignalStrength(sanitizeRange(json.getInt("signal"), -140, 0))
                .setReportTime(StrUtil.blankToDefault(json.getStr("ts"), DateUtils.time()));
        JSONArray filterLife = json.getJSONArray("filterLife");
        if (ObjectUtil.isNotNull(filterLife)) {
            telemetry.setFilterLifeJson(filterLife.toString());
            // 滤芯状态为超期（status=3）时产生提示级告警；告警服务负责幂等处理（REQ-039）。
            boolean expired = filterLife.stream()
                    .map(item -> (JSONObject) item)
                    .anyMatch(f -> ObjectUtil.equal(f.getInt("status"), 3));
            if (expired) {
                alarmService.raise(device.getId(), OpsEnum.AlarmType.FILTER_EXPIRE, 1,
                        "设备滤芯已超期，请安排换芯", null);
            } else if (!filterLife.isEmpty()) {
                // 只有设备明确上报了至少一个滤芯且全部正常，才足以恢复此前的超期告警。
                alarmService.autoRecover(device.getId(), OpsEnum.AlarmType.FILTER_EXPIRE, null);
            }
        }
        telemetryMapper.insert(telemetry);
        deviceService.update(Wrappers.lambdaUpdate(WsDevice.class)
                .eq(WsDevice::getId, device.getId())
                .set(ObjectUtil.isNotNull(telemetry.getSignalStrength()),
                        WsDevice::getSignalStrength, telemetry.getSignalStrength()));
        // TDS 告警阈值待甲方确认；当前阶段仅持久化遥测数据（REQ-038）。
    }

    /** 遥测数值域校验：越界即 NULL（页面降级为"暂无数据"），绝不让脏值污染最新读数。 */
    private Integer sanitizeRange(Integer value, int min, int max) {
        if (value == null || value < min || value > max) {
            return null;
        }
        return value;
    }

    /** 状态设备时间是乱序保护锚点；缺失或非法时拒绝处理，不能用服务器时间伪造设备先后顺序。 */
    private String requireDeviceTime(String value) {
        if (StrUtil.length(value) != 14) {
            throw new IllegalArgumentException("状态报文 ts 必须为 yyyyMMddHHmmss");
        }
        try {
            LocalDateTime parsed = LocalDateTime.parse(value, DateUtils.COMPACT_FORMATTER);
            if (!value.equals(parsed.format(DateUtils.COMPACT_FORMATTER))) {
                throw new DateTimeParseException("日期发生宽松归一化", value, 0);
            }
            return value;
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("状态报文 ts 必须为合法 yyyyMMddHHmmss", e);
        }
    }

    // ==================== 指令回执 / 执行结果（REQ-032/033/034） ====================

    private void handleAck(WsDevice device, JSONObject json) {
        String cmdNo = json.getStr("cmdNo");
        if (StrUtil.isBlank(cmdNo)) {
            throw new IllegalArgumentException("ack 报文缺少 cmdNo");
        }
        // ackTs 设备回执时间（P0-06 时间双存）；ackCode 回执结果码——缺省不再兼容为 accepted，
        // 判定口径见 WsCommandServiceImpl#onAck（缺 ackCode 一律按失败收口）
        commandService.onAck(device.getId(), cmdNo, json.getStr("ackTs"), json.getStr("ackCode"));
    }

    private void handleResult(WsDevice device, JSONObject json) {
        String cmdNo = json.getStr("cmdNo");
        if (StrUtil.isBlank(cmdNo)) {
            throw new IllegalArgumentException("result 报文缺少 cmdNo");
        }
        boolean success = Boolean.TRUE.equals(json.getBool("success"));
        // finishTs 设备完成时间（P0-06 时间双存）
        commandService.onResult(device.getId(), cmdNo, success, json.toString(),
                json.getStr("reason"), json.getStr("finishTs"));
    }

    // ==================== 断网补传（REQ-041） ====================

    private void onReplay(WsDevice device, JSONObject json, String rawPayload) {
        // 消息类型识别规则：cmdNo+success 为执行结果，cmdNo 为回执，runStatus 为状态上报。
        if (json.containsKey("cmdNo") && json.containsKey("success")) {
            withDedupe(device, json, rawPayload, DeviceTopics.KIND_REPLAY,
                    DeviceEnum.MsgType.REPLAY, () -> handleResult(device, json));
        } else if (json.containsKey("cmdNo")) {
            withDedupe(device, json, rawPayload, DeviceTopics.KIND_REPLAY,
                    DeviceEnum.MsgType.REPLAY, () -> handleAck(device, json));
        } else if (json.containsKey("runStatus")) {
            withDedupe(device, json, rawPayload, DeviceTopics.KIND_REPLAY,
                    DeviceEnum.MsgType.REPLAY, () -> handleStatus(device, json));
        } else {
            log.warn("补传消息无法推断类型，丢弃：device={} payload={}", device.getDeviceNo(), rawPayload);
        }
    }

    // ==================== msgId 幂等处理 ====================

    /**
     * 带 msgId 的消息先写入消息表，再执行业务并回写处理状态；uk_msg_id 负责并发去重。
     * 重复消息（包括断网补传）不重复入库或执行业务，仅记录审计事件。
     */
    private void withDedupe(WsDevice device, JSONObject json, String rawPayload, String topicKind,
                            DeviceEnum.MsgType msgType, Runnable handler) {
        String msgId = json.getStr("msgId");
        if (StrUtil.isBlank(msgId)) {
            log.warn("上行消息缺少 msgId，丢弃：device={} kind={}", device.getDeviceNo(), topicKind);
            return;
        }
        // P0-06：DEVICE_TIME 按消息类型取设备上报时间——ack 用 ackTs、result 用 finishTs、其余用 ts；
        // 合并取首个非空以兼容 replay（补传 ack/result 载荷分别带 ackTs/finishTs）。
        String deviceTime = StrUtil.firstNonBlank(json.getStr("ackTs"), json.getStr("finishTs"), json.getStr("ts"));
        WsDeviceMsg msg = new WsDeviceMsg()
                .setMsgId(msgId)
                .setDeviceId(device.getId())
                .setMsgType(msgType.getValue())
                .setMsgTopic("up/" + device.getDeviceNo() + "/" + topicKind)
                .setMsgPayload(rawPayload)
                .setDeviceTime(deviceTime)
                .setHandleStatus(DeviceEnum.MsgHandleStatus.PENDING.getValue());
        try {
            deviceMsgMapper.insert(msg);
        } catch (DuplicateKeyException e) {
            // P0-07：msgId 重复不再一律丢弃——对上次处理失败(FAILED 且未 FINAL)的消息走重试通道，
            // 已成功/已去重/处理中的一律审计丢弃。断网补传/QoS1 重投的失败消息因此可被重新处理。
            retryOnDuplicate(device, msgId, topicKind, handler);
            return;
        }
        try {
            handler.run();
            msg.setHandleStatus(DeviceEnum.MsgHandleStatus.HANDLED.getValue());
            deviceMsgMapper.updateById(msg);
        } catch (Exception e) {
            log.error("上行消息处理失败：device={} msgId={}", device.getDeviceNo(), msgId, e);
            // 首次失败不加 [RETRY]/[FINAL] 前缀，remark=原因；后续同 msgId 重投由 retryOnDuplicate 递增计数
            msg.setHandleStatus(DeviceEnum.MsgHandleStatus.FAILED.getValue());
            msg.setHandleRemark(StrUtil.maxLength(e.getMessage(), 490));
            deviceMsgMapper.updateById(msg);
        }
    }

    /**
     * P0-07 重试通道：msgId 重复到达时按既有处理状态分流。
     * FAILED 且未达 [FINAL] → 条件抢占 FAILED→PENDING（并发安全）后重投 handler，[RETRY:n] 计数编码进 HANDLE_REMARK；
     * 已处理/已去重/处理中 → 仅审计丢弃。ws_device_msg 无 RETRY_COUNT 列，计数编码入备注，不加列。
     */
    private void retryOnDuplicate(WsDevice device, String msgId, String topicKind, Runnable handler) {
        WsDeviceMsg existing = deviceMsgMapper.selectOne(
                Wrappers.lambdaQuery(WsDeviceMsg.class).eq(WsDeviceMsg::getMsgId, msgId));
        if (ObjectUtil.isNull(existing)) {
            domainEventService.recordByDevice(OpsEnum.EventType.DEVICE_STATUS, device.getDeviceNo(),
                    null, "重复消息丢弃 msgId=" + msgId + "（并发中，未查到落库行）");
            return;
        }
        int status = existing.getHandleStatus();
        if (DeviceEnum.MsgHandleStatus.FAILED.getValue() != status) {
            // 已处理/已去重/处理中：不重复执行业务，仅审计（铁律3 不重复计量）
            domainEventService.recordByDevice(OpsEnum.EventType.DEVICE_STATUS, device.getDeviceNo(),
                    null, "重复消息丢弃 msgId=" + msgId + "（kind=" + topicKind + "，状态="
                            + DeviceEnum.MsgHandleStatus.getType(status).getDesc() + "）");
            return;
        }
        String remark = StrUtil.blankToDefault(existing.getHandleRemark(), "");
        if (remark.startsWith("[FINAL]")) {
            domainEventService.recordByDevice(OpsEnum.EventType.DEVICE_STATUS, device.getDeviceNo(),
                    null, "重复消息丢弃 msgId=" + msgId + "（已达最大重试 FINAL）");
            return;
        }
        // 条件抢占 FAILED→PENDING，affected==1 才重投，避免并发重复处理
        int claimed = deviceMsgMapper.update(null, Wrappers.lambdaUpdate(WsDeviceMsg.class)
                .set(WsDeviceMsg::getHandleStatus, DeviceEnum.MsgHandleStatus.PENDING.getValue())
                .eq(WsDeviceMsg::getId, existing.getId())
                .eq(WsDeviceMsg::getHandleStatus, DeviceEnum.MsgHandleStatus.FAILED.getValue()));
        if (claimed == 0) {
            domainEventService.recordByDevice(OpsEnum.EventType.DEVICE_STATUS, device.getDeviceNo(),
                    null, "msgId=" + msgId + " 并发抢占重试失败，跳过");
            return;
        }
        int next = parseRetryCount(remark) + 1;
        try {
            handler.run();
            deviceMsgMapper.update(null, Wrappers.lambdaUpdate(WsDeviceMsg.class)
                    .set(WsDeviceMsg::getHandleStatus, DeviceEnum.MsgHandleStatus.HANDLED.getValue())
                    .set(WsDeviceMsg::getHandleRemark, "[RETRY:" + next + "] 重投成功")
                    .eq(WsDeviceMsg::getId, existing.getId())
                    .eq(WsDeviceMsg::getHandleStatus, DeviceEnum.MsgHandleStatus.PENDING.getValue()));
        } catch (Exception e) {
            log.error("上行消息重投失败：device={} msgId={} retry={}", device.getDeviceNo(), msgId, next, e);
            boolean fin = next >= MAX_MSG_RETRY;
            String prefix = fin ? "[FINAL]" : "[RETRY:" + next + "]";
            deviceMsgMapper.update(null, Wrappers.lambdaUpdate(WsDeviceMsg.class)
                    .set(WsDeviceMsg::getHandleStatus, DeviceEnum.MsgHandleStatus.FAILED.getValue())
                    .set(WsDeviceMsg::getHandleRemark, StrUtil.maxLength(prefix + " " + e.getMessage(), 490))
                    .eq(WsDeviceMsg::getId, existing.getId())
                    .eq(WsDeviceMsg::getHandleStatus, DeviceEnum.MsgHandleStatus.PENDING.getValue()));
        }
    }

    /** 从 HANDLE_REMARK 解析已重试次数：锚定前缀 [RETRY:n]，无则 0（防失败原因文本内出现类似字样误判） */
    private int parseRetryCount(String remark) {
        Matcher matcher = RETRY_PATTERN.matcher(remark);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }
}
