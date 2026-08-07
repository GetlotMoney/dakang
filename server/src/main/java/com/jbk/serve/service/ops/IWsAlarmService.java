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

    /** 按设备分页查询告警（设备详情页只读提醒；deviceId 必填，服务端校验） */
    PageDataVo<WsAlarmVo> pageByDevice(WsAlarmBo alarmBo);

    /** 告警中心全量分页（PC，MANAGE 会话；类型/状态/等级/设备可选筛选） */
    PageDataVo<WsAlarmVo> pageData(WsAlarmBo alarmBo);

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

    /**
     * 忽略一条活动告警：状态转 3已忽略并原子清空活动键（同一条 UPDATE，带前态 CAS）。
     * 键清空后同型告警可再次触发——忽略表达的是「这次不管了」，不是「永远别再报」。
     *
     * @return 影响行数为 1 才为 true；0 表示已被并发处置或不在活动态
     */
    boolean ignore(Long alarmId, Long operatorId, String now);

    /**
     * 告警转工单后的回链：回填 WORK_ORDER_ID 并转 2已转工单。
     * 活动键<b>保留</b>（任务书 3.2：转单后活动键保留到告警恢复或工单正式关闭）——
     * 工单处理期间同型故障不该再刷第二条告警。
     */
    boolean linkWorkOrder(Long alarmId, Long workOrderId, Long operatorId, String now);

    /** 自动恢复指定类型的待处理告警（如设备心跳恢复时恢复离线告警），返回是否有告警被恢复 */
    /**
     * 自动恢复：按活动键精确恢复（状态转 4自动恢复 + 清空活动键，同一条 UPDATE）。
     *
     * <p>{@code sourceRef} 决定恢复哪一条：离线告警传 null（键无来源段），故障告警必须传
     * 当前恢复的故障码——任务书 3.6「无故障状态只能恢复与当前故障匹配的故障告警」。
     * 已转工单(2)的告警同样可自动恢复（设备自愈），但其工单绝不因此关闭，仍须人工复核。</p>
     */
    boolean autoRecover(Long deviceId, OpsEnum.AlarmType type, String sourceRef);

    /**
     * 工单正式关闭时释放告警活动键（任务书 3.2：活动键保留到告警恢复<b>或工单关闭</b>）。
     * 只清键不改状态——告警的业务终态仍是「已转工单」，释放后同型故障可再触发新告警。
     * 键可能已被自动恢复清空，条件 UPDATE 天然幂等。
     */
    void releaseActiveKey(Long alarmId);
}
