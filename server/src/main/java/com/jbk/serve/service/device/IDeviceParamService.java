package com.jbk.serve.service.device;

import com.jbk.tool.data.device.po.WsDeviceParam;
import com.jbk.tool.data.device.po.WsDeviceParamDef;

import java.util.List;
import java.util.Map;

/**
 * 设备参数（REQ-213-S1）：下发前校验所需的定义、result 成功后的快照回写、以及只读查询。
 *
 * <p>本接口是<b>独立 Bean</b>而不是 WsCommandServiceImpl 的私有方法——回写要自带事务边界，
 * 而 onResult 本身无事务；同类自调用会让 {@code @Transactional} 静默失效（B18/R1 踩过一次）。</p>
 *
 * @author dakang
 * @since 2026-08-04
 */
public interface IDeviceParamService {

    /**
     * 取某指令类型下已登记且启用的参数定义。
     *
     * @return key=paramKey；无登记返回空 Map（空 Map 即「今天的行为」，不得抛异常）
     */
    Map<String, DeviceParamPayload.Definition> enabledDefinitions(int cmdType);

    /**
     * result 成功后回写平台侧参数快照。
     *
     * <p>只接受成功终态；partial 与失败一律不回写——ACK/部分完成都不能证明设备真的应用了参数。
     * 同一 cmdNo 重复进入不得抬升版本（设备补传与消息重投是常态）。</p>
     *
     * @param deviceId   设备ID
     * @param cmdType    指令类型
     * @param cmdId      指令ID（ws_command.ID）。单调守卫的排序基准：只有更大的 ID 才能覆盖既有快照，
     *                   否则断网补传/消息重投带来的旧 result 会把新值覆盖回去
     * @param cmdNo      指令号（溯源用）
     * @param cmdPayload 平台<b>下发</b>的报文原文（不取 result 报文：设备回什么由厂家定，尚未确认）
     * @param syncTime   设备回报成功时间
     * @return 本次报文覆盖的参数键数。<b>该值是幂等的</b>：同一 cmdNo 重复调用返回同一数字，
     *         不代表"又生效了一次"——真正的幂等不变量是数据本身（版本与同步时间不被重复 result 改动）。
     *         这里刻意不拿受影响行数当"是否首次生效"的信号：MySQL 驱动默认是 found-rows 语义，
     *         ON DUPLICATE KEY UPDATE 匹配但零变更时返回 1 而不是 0，据此判定必错；
     *         而 useAffectedRows 是连接级全局开关，翻它会一并改掉资金链
     *         「UPDATE ... WHERE 余量>=X 校验影响行数」的语义，绝不允许。
     */
    int applySyncedParams(Long deviceId, int cmdType, Long cmdId, String cmdNo,
                          String cmdPayload, String syncTime);

    /** 只读：这台设备当前参数是什么（按键名升序，无记录返回空列表而非报错）。 */
    List<WsDeviceParam> currentParams(Long deviceId);

    /** 只读：某指令类型下的全部启用定义（供 PC 表单与运维排查用）。 */
    List<WsDeviceParamDef> definitions(int cmdType);
}
