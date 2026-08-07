package com.jbk.serve.device;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.service.device.IWsCommandService;
import com.jbk.serve.service.device.IWsDeviceService;
import com.jbk.serve.service.device.DeviceAvailability;
import com.jbk.serve.service.ops.IWsAlarmService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.device.DeviceEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.device.po.WsDevice;
import com.jbk.tool.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * 设备监控定时任务（MVP 地基组件：超时判定由后端 worker 负责，REQ-033/028）
 * <p>单实例部署，无需分布式锁；扩容时用 Redisson 加锁改造。</p>
 *
 * @author dakang
 * @since 2026-07-12
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "dakang.device.monitor-enabled", havingValue = "true", matchIfMissing = true)
public class DeviceMonitorTask {

    /** 心跳周期 30s × 3 周期无心跳判离线（协议文档口径） */
    private static final int OFFLINE_THRESHOLD_SECONDS = 90;

    @Autowired
    private IWsCommandService commandService;
    @Autowired
    private IWsDeviceService deviceService;
    @Autowired
    private IWsAlarmService alarmService;
    @Autowired
    private IWsDomainEventService domainEventService;

    /** 指令超时扫描：已下发 30s 无回执 / 已回执 120s 无结果 → 超时 + 告警 */
    @Scheduled(fixedDelay = 15000, initialDelay = 20000)
    public void commandTimeoutScan() {
        try {
            int count = commandService.scanTimeout();
            if (count > 0) {
                log.info("指令超时扫描：本次转为超时状态 {} 条", count);
            }
        } catch (Exception e) {
            log.error("指令超时扫描异常", e);
        }
    }

    /** 设备离线判定：在线设备心跳超过阈值 → 离线 + 告警（离线设备扫码前会被阻断下单） */
    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void deviceOfflineScan() {
        try {
            String deadline = DateUtils.timeTransition(
                    DateUtils.addDateSeconds(new Date(), -OFFLINE_THRESHOLD_SECONDS));
            // varchar(14) 纯数字等长，字典序即时间序；心跳为空的在线设备一并判离线
            List<WsDevice> staleList = deviceService.list(Wrappers.lambdaQuery(WsDevice.class)
                    .eq(WsDevice::getOnlineStatus, DeviceEnum.OnlineStatus.ONLINE.getValue())
                    .and(w -> w.lt(WsDevice::getLastHeartbeat, deadline)
                            .or().isNull(WsDevice::getLastHeartbeat)));
            int flipped = 0;
            for (WsDevice device : staleList) {
                // CAS：ONLINE→OFFLINE 且心跳仍陈旧才算数（捞取与更新之间可能有心跳到达并转在线，
                // 无前态写会把刚活过来的设备再压回离线）。只有 affected==1 的赢家才产事件与告警——
                // 多实例并发扫描或扫描与心跳竞争时，恰一个胜出方触发，离线告警不重复。
                int moved = deviceService.getBaseMapper().update(null, Wrappers.lambdaUpdate(WsDevice.class)
                        .set(WsDevice::getOnlineStatus, DeviceEnum.OnlineStatus.OFFLINE.getValue())
                        .set(WsDevice::getUpdateTime, DateUtils.time())
                        .eq(WsDevice::getId, device.getId())
                        .eq(WsDevice::getOnlineStatus, DeviceEnum.OnlineStatus.ONLINE.getValue())
                        .and(w -> w.lt(WsDevice::getLastHeartbeat, deadline)
                                .or().isNull(WsDevice::getLastHeartbeat)));
                if (moved != 1) {
                    continue;
                }
                flipped++;
                domainEventService.record(OpsEnum.EventType.DEVICE_STATUS, device.getDeviceNo(),
                        DeviceEnum.OnlineStatus.ONLINE.getDesc(), DeviceEnum.OnlineStatus.OFFLINE.getDesc());
                alarmService.raise(device.getId(), OpsEnum.AlarmType.DEVICE_OFFLINE, 2,
                        "设备心跳超过 " + OFFLINE_THRESHOLD_SECONDS + " 秒，判定离线", null);
            }
            if (flipped > 0) {
                log.info("离线判定扫描：本次转为离线状态 {} 台", flipped);
            }
        } catch (Exception e) {
            log.error("设备离线判定异常", e);
        }
    }

    /**
     * SIM 档案异常扫描：明确未激活/欠费/停用、已到期或非法到期时间形成一条活动告警；
     * 运营修正档案后自动恢复。真实运营商查询未接入，本扫描只消费平台档案事实。
     */
    @Scheduled(fixedDelay = 60000, initialDelay = 45000)
    public void simAbnormalScan() {
        try {
            String now = DateUtils.time();
            for (WsDevice device : deviceService.list()) {
                String reason = DeviceAvailability.simBlockReason(device, now);
                if (reason == null) {
                    alarmService.autoRecover(device.getId(), OpsEnum.AlarmType.SIM_ABNORMAL, null);
                    continue;
                }
                alarmService.raise(device.getId(), OpsEnum.AlarmType.SIM_ABNORMAL, 2,
                        reason, null);
            }
        } catch (Exception e) {
            log.error("SIM异常扫描失败", e);
        }
    }
}
