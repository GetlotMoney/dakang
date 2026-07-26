package com.jbk.serve.service.ops;

import com.baomidou.mybatisplus.extension.service.IService;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.ops.bo.WsAlarmBo;
import com.jbk.tool.data.ops.po.WsAlarm;
import com.jbk.tool.data.ops.vo.WsAlarmVo;

/**
 * 设备告警服务（一期：产生 + 自动恢复 + 只读查询；工单闭环为商业一期）
 *
 * @author dakang
 * @since 2026-07-12
 */
public interface IWsAlarmService extends IService<WsAlarm> {

    /** 按设备分页查询告警（设备详情页只读提醒） */
    PageDataVo<WsAlarmVo> pageByDevice(WsAlarmBo alarmBo);

    /**
     * 产生告警（幂等：同设备同类型存在待处理告警时不重复产生）
     *
     * @param deviceId  设备ID
     * @param type      告警类型
     * @param level     告警等级（复用故障等级 1304：1提示 2一般 3严重）
     * @param content   告警内容
     * @param sourceRef 来源引用（故障码/指令号，可空）
     */
    void raise(Long deviceId, OpsEnum.AlarmType type, int level, String content, String sourceRef);

    /** 自动恢复指定类型的待处理告警（如设备心跳恢复时恢复离线告警），返回是否有告警被恢复 */
    boolean autoRecover(Long deviceId, OpsEnum.AlarmType type);
}
