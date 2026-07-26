package com.jbk.serve.service.ops.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import cn.hutool.core.util.ObjectUtil;
import com.jbk.serve.mapper.ops.WsAlarmMapper;
import com.jbk.serve.service.ops.IWsAlarmService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.ops.bo.WsAlarmBo;
import com.jbk.tool.data.ops.po.WsAlarm;
import com.jbk.tool.data.ops.vo.WsAlarmVo;
import com.jbk.tool.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Override
    public PageDataVo<WsAlarmVo> pageByDevice(WsAlarmBo alarmBo) {
        Page<WsAlarm> page = page(new Page<>(alarmBo.getCurrent(), alarmBo.getSize()),
                Wrappers.lambdaQuery(WsAlarm.class)
                        .eq(WsAlarm::getDeviceId, alarmBo.getDeviceId())
                        .eq(ObjectUtil.isNotNull(alarmBo.getAlarmStatus()), WsAlarm::getAlarmStatus, alarmBo.getAlarmStatus())
                        .orderByDesc(WsAlarm::getId));
        List<WsAlarmVo> voList = page.getRecords().stream()
                .map(e -> BeanUtil.copyProperties(e, WsAlarmVo.class))
                .collect(Collectors.toList());
        return PageDataVo.getPageData(voList, page.getTotal());
    }

    @Override
    public void raise(Long deviceId, OpsEnum.AlarmType type, int level, String content, String sourceRef) {
        // 幂等：同设备同类型已有待处理告警则不重复产生（避免 worker 周期扫描刷屏）
        long pending = count(Wrappers.lambdaQuery(WsAlarm.class)
                .eq(WsAlarm::getDeviceId, deviceId)
                .eq(WsAlarm::getAlarmType, type.getValue())
                .eq(WsAlarm::getAlarmStatus, OpsEnum.AlarmStatus.PENDING.getValue()));
        if (pending > 0) {
            return;
        }
        WsAlarm alarm = new WsAlarm()
                .setDeviceId(deviceId)
                .setAlarmType(type.getValue())
                .setAlarmLevel(level)
                // 内容截断防超列宽（varchar(500)，故障建议拼接可能超长）
                .setAlarmContent(StrUtil.brief(content, 500))
                .setSourceRef(sourceRef)
                .setAlarmStatus(OpsEnum.AlarmStatus.PENDING.getValue());
        save(alarm);
        domainEventService.record(OpsEnum.EventType.ALARM_CREATED, "ALARM-" + alarm.getId(), null, content);
    }

    @Override
    public boolean autoRecover(Long deviceId, OpsEnum.AlarmType type) {
        List<WsAlarm> pendingList = list(Wrappers.lambdaQuery(WsAlarm.class)
                .eq(WsAlarm::getDeviceId, deviceId)
                .eq(WsAlarm::getAlarmType, type.getValue())
                .eq(WsAlarm::getAlarmStatus, OpsEnum.AlarmStatus.PENDING.getValue()));
        if (pendingList.isEmpty()) {
            return false;
        }
        String now = DateUtils.time();
        pendingList.forEach(alarm -> {
            alarm.setAlarmStatus(OpsEnum.AlarmStatus.AUTO_RECOVERED.getValue());
            alarm.setRecoverTime(now);
        });
        updateBatchById(pendingList);
        return true;
    }
}
