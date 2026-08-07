package com.jbk.serve.service.ops.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import cn.hutool.core.util.ObjectUtil;
import com.jbk.serve.mapper.ops.WsAlarmMapper;
import com.jbk.serve.service.ops.AlarmDedupeKey;
import com.jbk.serve.service.ops.IWsAlarmService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.ops.bo.WsAlarmBo;
import com.jbk.tool.data.ops.po.WsAlarm;
import com.jbk.tool.data.ops.vo.WsAlarmVo;
import com.jbk.tool.utils.DateUtils;
import com.jbk.tool.utils.OptionalUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 设备告警服务实现
 *
 * @author dakang
 * @since 2026-07-12
 */
@Slf4j
@Service
public class WsAlarmServiceImpl extends ServiceImpl<WsAlarmMapper, WsAlarm> implements IWsAlarmService {

    @Autowired
    private IWsDomainEventService domainEventService;
    @Autowired
    private com.jbk.serve.mapper.device.WsDeviceMapper deviceMapper;

    @Override
    public PageDataVo<WsAlarmVo> pageByDevice(WsAlarmBo alarmBo) {
        // deviceId 必填校验从 Bo 分组下移到这里：同一 Bo 还服务告警中心全量分页
        OptionalUtils.nullToElseThrow(alarmBo.getDeviceId(), "设备信息不为空");
        return pageData(alarmBo);
    }

    @Override
    public PageDataVo<WsAlarmVo> pageData(WsAlarmBo alarmBo) {
        Page<WsAlarm> page = page(new Page<>(alarmBo.getCurrent(), alarmBo.getSize()),
                Wrappers.lambdaQuery(WsAlarm.class)
                        .eq(ObjectUtil.isNotNull(alarmBo.getDeviceId()), WsAlarm::getDeviceId, alarmBo.getDeviceId())
                        .eq(ObjectUtil.isNotNull(alarmBo.getAlarmStatus()), WsAlarm::getAlarmStatus, alarmBo.getAlarmStatus())
                        .eq(ObjectUtil.isNotNull(alarmBo.getAlarmType()), WsAlarm::getAlarmType, alarmBo.getAlarmType())
                        .eq(ObjectUtil.isNotNull(alarmBo.getAlarmLevel()), WsAlarm::getAlarmLevel, alarmBo.getAlarmLevel())
                        .orderByDesc(WsAlarm::getId));
        List<WsAlarmVo> voList = page.getRecords().stream()
                .map(e -> BeanUtil.copyProperties(e, WsAlarmVo.class))
                .collect(Collectors.toList());
        fillDeviceNo(voList);
        return PageDataVo.getPageData(voList, page.getTotal());
    }

    /** 联查设备编号（告警中心列表展示；缺档不阻断，字段留空） */
    private void fillDeviceNo(List<WsAlarmVo> voList) {
        if (voList.isEmpty()) {
            return;
        }
        java.util.Set<Long> deviceIds = voList.stream().map(WsAlarmVo::getDeviceId)
                .filter(ObjectUtil::isNotNull).collect(Collectors.toSet());
        if (deviceIds.isEmpty()) {
            return;
        }
        java.util.Map<Long, String> noById = deviceMapper.selectBatchIds(deviceIds).stream()
                .collect(Collectors.toMap(com.jbk.tool.data.device.po.WsDevice::getId,
                        com.jbk.tool.data.device.po.WsDevice::getDeviceNo));
        voList.forEach(vo -> vo.setDeviceNo(noById.get(vo.getDeviceId())));
    }

    @Override
    public void raise(Long deviceId, OpsEnum.AlarmType type, int level, String content, String sourceRef) {
        // 幂等由 uk_alarm_active_dedupe 承担（铁律②：不做应用层预查重）。此前的
        // 「先 COUNT 再 INSERT」在离线扫描/心跳/指令超时并发命中同一设备时必然穿透，
        // 会插出重复 PENDING 告警——现在两条并发 raise 里输的一方撞键，按幂等静默返回。
        WsAlarm alarm = new WsAlarm()
                .setDeviceId(deviceId)
                .setAlarmType(type.getValue())
                .setAlarmLevel(level)
                // 内容截断防超列宽（varchar(500)，故障建议拼接可能超长）
                .setAlarmContent(StrUtil.brief(content, 500))
                .setSourceRef(sourceRef)
                .setAlarmStatus(OpsEnum.AlarmStatus.PENDING.getValue())
                .setActiveDedupeKey(AlarmDedupeKey.of(type, deviceId, sourceRef));
        try {
            save(alarm);
        } catch (DuplicateKeyException duplicated) {
            // 同键活动告警已在（含已转工单的：任务书 3.2 转单后活动键保留），本次触发不重复入库。
            // 不读回不更新——告警内容以首报为准，周期扫描的后续触发只是同一事实的重复观测
            return;
        }
        domainEventService.record(OpsEnum.EventType.ALARM_CREATED, "ALARM-" + alarm.getId(), null, content);
    }

    @Override
    public boolean autoRecover(Long deviceId, OpsEnum.AlarmType type, String sourceRef) {
        // 按活动键精确恢复：状态与键清空必须同一条 UPDATE——分两步的话，第一步后并发 raise
        // 撞不到键（已清）但状态还没落终态，会出现两条"活动"告警。
        // 前态只认 1待处理 / 2已转工单（活动态集合）；已转工单的告警设备自愈时同样恢复，
        // 但其工单绝不因此关闭（任务书 3.6），工单收口只能走人工复核。
        String key = AlarmDedupeKey.of(type, deviceId, StrUtil.blankToDefault(sourceRef, null));
        int moved = getBaseMapper().update(null, Wrappers.lambdaUpdate(WsAlarm.class)
                .set(WsAlarm::getAlarmStatus, OpsEnum.AlarmStatus.AUTO_RECOVERED.getValue())
                .set(WsAlarm::getRecoverTime, DateUtils.time())
                .set(WsAlarm::getActiveDedupeKey, null)
                .set(WsAlarm::getUpdateTime, DateUtils.time())
                .eq(WsAlarm::getActiveDedupeKey, key)
                .in(WsAlarm::getAlarmStatus, OpsEnum.AlarmStatus.PENDING.getValue(),
                        OpsEnum.AlarmStatus.TO_WORK_ORDER.getValue()));
        if (moved == 1) {
            domainEventService.record(OpsEnum.EventType.ALARM_RECOVERED, key, null,
                    "告警自动恢复：" + type.getDesc());
        }
        return moved == 1;
    }

    @Override
    public boolean ignore(Long alarmId, Long operatorId, String now) {
        // 忽略只受理 1待处理：已转工单的告警不能被"忽略"绕开工单闭环，
        // 终态告警忽略没有意义。状态、处置人、键清空同一条 CAS。
        int moved = getBaseMapper().update(null, Wrappers.lambdaUpdate(WsAlarm.class)
                .set(WsAlarm::getAlarmStatus, OpsEnum.AlarmStatus.IGNORED.getValue())
                .set(WsAlarm::getActiveDedupeKey, null)
                .set(WsAlarm::getHandleBy, operatorId)
                .set(WsAlarm::getHandleTime, now)
                .set(WsAlarm::getUpdateBy, operatorId)
                .set(WsAlarm::getUpdateTime, now)
                .eq(WsAlarm::getId, alarmId)
                .eq(WsAlarm::getAlarmStatus, OpsEnum.AlarmStatus.PENDING.getValue()));
        if (moved == 1) {
            domainEventService.record(OpsEnum.EventType.ALARM_RECOVERED, "ALARM-" + alarmId,
                    OpsEnum.AlarmStatus.PENDING.getDesc(), "运营忽略告警");
        }
        return moved == 1;
    }

    @Override
    public boolean linkWorkOrder(Long alarmId, Long workOrderId, Long operatorId, String now) {
        // 1待处理 → 2已转工单；活动键保留（工单处理期间同型故障不再刷第二条告警）。
        // WORK_ORDER_ID 只在本方法写入，且前态钉死 1——重复转单在工单侧被 uk_wo_alarm 挡，
        // 这里被前态挡，两层互为印证。
        int moved = getBaseMapper().update(null, Wrappers.lambdaUpdate(WsAlarm.class)
                .set(WsAlarm::getAlarmStatus, OpsEnum.AlarmStatus.TO_WORK_ORDER.getValue())
                .set(WsAlarm::getWorkOrderId, workOrderId)
                .set(WsAlarm::getHandleBy, operatorId)
                .set(WsAlarm::getHandleTime, now)
                .set(WsAlarm::getUpdateBy, operatorId)
                .set(WsAlarm::getUpdateTime, now)
                .eq(WsAlarm::getId, alarmId)
                .eq(WsAlarm::getAlarmStatus, OpsEnum.AlarmStatus.PENDING.getValue()));
        if (moved == 1) {
            domainEventService.record(OpsEnum.EventType.ALARM_RECOVERED, "ALARM-" + alarmId,
                    OpsEnum.AlarmStatus.PENDING.getDesc(), "告警转工单：WO-" + workOrderId);
        }
        return moved == 1;
    }

    @Override
    public void releaseActiveKey(Long alarmId) {
        // 只清键不改状态：告警终态仍是 2已转工单，释放后同型故障可重新触发新告警。
        // 键已被自动恢复清空时影响行数为 0——幂等，无需区分
        int released = getBaseMapper().update(null, Wrappers.lambdaUpdate(WsAlarm.class)
                .set(WsAlarm::getActiveDedupeKey, null)
                .eq(WsAlarm::getId, alarmId)
                .isNotNull(WsAlarm::getActiveDedupeKey));
        if (released == 1) {
            domainEventService.record(OpsEnum.EventType.ALARM_RECOVERED, "ALARM-" + alarmId,
                    null, "工单关闭，释放告警活动键");
        }
    }
}
