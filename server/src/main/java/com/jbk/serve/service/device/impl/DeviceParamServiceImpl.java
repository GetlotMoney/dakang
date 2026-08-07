package com.jbk.serve.service.device.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.device.WsDeviceParamDefMapper;
import com.jbk.serve.mapper.device.WsDeviceParamMapper;
import com.jbk.serve.service.device.DeviceParamPayload;
import com.jbk.serve.service.device.IDeviceParamService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.device.DeviceEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.device.po.WsDeviceParam;
import com.jbk.tool.data.device.po.WsDeviceParamDef;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备参数服务实现（REQ-213-S1）。
 *
 * @author dakang
 * @since 2026-08-04
 */
@Slf4j
@Service
public class DeviceParamServiceImpl implements IDeviceParamService {

    /** 只有这两类指令承载参数；其余指令的 payload 不是参数，不得写进快照。 */
    private static final List<Integer> PARAM_BEARING_TYPES = List.of(
            DeviceEnum.CmdType.PARAM_SYNC.getValue(),
            DeviceEnum.CmdType.PRICE_SYNC.getValue());

    /** 设备上报驱动的写入，归属记为系统。 */
    private static final long SYSTEM_ACTOR = 0L;

    @Autowired
    private WsDeviceParamMapper paramMapper;
    @Autowired
    private WsDeviceParamDefMapper paramDefMapper;
    @Autowired
    private IWsDomainEventService domainEventService;

    public static boolean isParamBearing(Integer cmdType) {
        return ObjectUtil.isNotNull(cmdType) && PARAM_BEARING_TYPES.contains(cmdType);
    }

    @Override
    public Map<String, DeviceParamPayload.Definition> enabledDefinitions(int cmdType) {
        Map<String, DeviceParamPayload.Definition> defs = new LinkedHashMap<>();
        for (WsDeviceParamDef row : paramDefMapper.selectList(Wrappers.lambdaQuery(WsDeviceParamDef.class)
                .eq(WsDeviceParamDef::getCmdType, cmdType)
                .eq(WsDeviceParamDef::getDefStatus, WsDeviceParamDef.STATUS_ENABLED)
                .orderByAsc(WsDeviceParamDef::getParamKey))) {
            defs.put(row.getParamKey(), new DeviceParamPayload.Definition(
                    row.getParamKey(), row.getValueType(), row.getValueUnit(),
                    row.getValueMin(), row.getValueMax(), row.getValueEnum()));
        }
        return defs;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int applySyncedParams(Long deviceId, int cmdType, Long cmdId, String cmdNo,
                                 String cmdPayload, String syncTime) {
        if (!isParamBearing(cmdType)) {
            // 非参数类指令的 payload 不是参数（出水单的 planMl/orderNo 若被写进参数快照就是污染）
            return 0;
        }
        if (ObjectUtil.isNull(deviceId) || ObjectUtil.isNull(cmdId) || StrUtil.isBlank(cmdNo)) {
            // cmdId 是单调守卫的唯一排序基准，缺了它就退化成「按到达顺序覆盖」
            throw new JbkException("回写设备参数缺少设备、指令ID或指令号");
        }
        DeviceParamPayload.Checked checked;
        try {
            // 回写前重新校验一次下发报文：下发时通过不等于此刻仍合法（定义可能在此期间被收紧），
            // 而快照是「平台认为设备现在是什么」，写进去的必须是当下仍合规的值。
            checked = DeviceParamPayload.require(cmdPayload, enabledDefinitions(cmdType));
        }
        catch (JbkException invalid) {
            // 不抛给调用方：指令终态已经落定，这里抛只会让设备反复重投 result 而无法收敛。
            // 但绝不静默——落可靠留痕，运维能查到「这台设备的参数快照为何没跟上」。
            domainEventService.recordReliableOnceIndependent(OpsEnum.EventType.DEVICE_STATUS, cmdNo,
                    "PARAM_SNAPSHOT_REJECT:" + cmdNo, null,
                    "设备参数快照回写被拒（下发报文此刻已不合规）：" + invalid.getMsg());
            return 0;
        }
        String now = DateUtils.time();
        String effectiveSyncTime = StrUtil.isBlank(syncTime) ? now : syncTime;
        int applied = 0;
        // 按键名升序逐键回写：加锁顺序必须全局固定。若按报文里的 JSON 顺序写，
        // {"x":1,"y":2} 与 {"y":2,"x":1} 两条 result 并发就会各自反向持锁，构成 ABBA 死锁
        //（与 B20 固定全局锁序同一条理由）。
        for (String key : new java.util.TreeSet<>(checked.values().keySet())) {
            if (checked.unregisteredKeys().contains(key)) {
                // 未登记键**允许下发但不得进入快照**（R2-P1-1）。
                // 依据：厂家协议要求固件忽略不认识的键；而平台目前不解析逐键 result，
                // 拿不到「设备到底应用了哪些键」的事实。把未登记键写进快照，等于用
                // 「我们发过」冒充「设备当前生效」——一个被固件默默丢弃的键会在平台上
                // 显示成生效值，运维据此排障必然走错方向。
                // 它仍完整保留在 ws_command.CMD_PAYLOAD 与指令追溯里，证据不丢。
                // V-12.3 明确逐键 result 协议后再决定是否依据设备回报写入，本轮不发明报文格式。
                continue;
            }
            if (upsertOne(deviceId, key, checked.values().get(key), cmdNo, cmdId,
                    effectiveSyncTime, 1, now)) {
                applied++;
            }
        }
        return applied;
    }

    /**
     * 单键回写：单语句 upsert，受影响行数即结论。
     *
     * <p>不用「先 UPDATE 再兜底 INSERT」：那种两段式在两条 result 并发命中同一 (设备,键) 时
     * 会双双走到 INSERT 互等插入意向锁，实测抛 DeadlockLoserDataAccessException，
     * 而死锁会连带回滚整个回写事务——同一报文里其它键的值也一起丢。</p>
     *
     * <p>返回值不用来判断"是否首次生效"：驱动默认 found-rows 语义下，匹配但零变更同样返回 1。
     * 幂等由 SQL 里的 IF 守卫在数据层保证——重复 result 不动版本、不动同步时间。</p>
     *
     * @return 该键是否被本次报文覆盖
     */
    private boolean upsertOne(Long deviceId, String key, String value, String cmdNo, Long cmdId,
                              String syncTime, int registered, String now) {
        return paramMapper.upsertSnapshot(
                deviceId, key, value, cmdNo, cmdId, syncTime, registered, SYSTEM_ACTOR, now) > 0;
    }

    @Override
    public List<WsDeviceParam> currentParams(Long deviceId) {
        if (ObjectUtil.isNull(deviceId)) {
            return List.of();
        }
        return paramMapper.selectList(Wrappers.lambdaQuery(WsDeviceParam.class)
                .eq(WsDeviceParam::getDeviceId, deviceId)
                .eq(WsDeviceParam::getDataStatus, 0)
                .orderByAsc(WsDeviceParam::getParamKey));
    }

    @Override
    public List<WsDeviceParamDef> definitions(int cmdType) {
        return paramDefMapper.selectList(Wrappers.lambdaQuery(WsDeviceParamDef.class)
                .eq(WsDeviceParamDef::getCmdType, cmdType)
                .eq(WsDeviceParamDef::getDefStatus, WsDeviceParamDef.STATUS_ENABLED)
                .orderByAsc(WsDeviceParamDef::getParamKey));
    }
}
