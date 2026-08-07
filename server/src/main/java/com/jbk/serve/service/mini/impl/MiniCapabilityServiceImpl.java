package com.jbk.serve.service.mini.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.device.WsDeviceMapper;
import com.jbk.serve.mapper.station.WsStationMapper;
import com.jbk.serve.mapper.user.WsCourierMapper;
import com.jbk.tool.data.device.po.WsDevice;
import com.jbk.tool.data.station.po.WsStation;
import com.jbk.serve.service.mini.IMiniCapabilityService;
import com.jbk.tool.consts.user.UserEnum;
import com.jbk.tool.data.user.po.WsCourier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 小程序账号能力投影实现。
 *
 * <p>COURIER_WORK 的取行口径与 CourierAccess 一致（同 userId 最新记录 + 启用态），
 * 保证「首页显示配送入口」与「接口真的放行」判定同源；范围为空集的启用配送员仍会
 * 在具体接口被 CourierAccess 拒绝——投影不做范围判定，绝不比接口更宽松地放行动作。</p>
 *
 * @author dakang
 * @since 2026-07-24
 */
@Service
@RequiredArgsConstructor
public class MiniCapabilityServiceImpl implements IMiniCapabilityService {

    private final WsCourierMapper courierMapper;
    private final WsDeviceMapper deviceMapper;
    private final WsStationMapper stationMapper;

    @Override
    public List<String> capabilitiesOf(Long userId) {
        List<String> capabilities = new ArrayList<>();
        capabilities.add("USER_BASE");
        // 配送准入查看/申请是基础投影（miniapp 蓝图：所有账号可查准入状态）
        capabilities.add("COURIER_APPLY");
        if (ObjectUtil.isNull(userId) || userId <= 0) {
            return capabilities;
        }
        WsCourier courier = courierMapper.selectOne(Wrappers.lambdaQuery(WsCourier.class)
                .eq(WsCourier::getUserId, userId)
                .orderByDesc(WsCourier::getId)
                .last("LIMIT 1"));
        if (ObjectUtil.isNotNull(courier)
                && ObjectUtil.equal(courier.getCourierStatus(), UserEnum.CourierStatus.ENABLED.getValue())) {
            capabilities.add("COURIER_WORK");
        }
        // 机主能力（E2E-05 包E）：以名下归属行判定（ws_device/ws_station OWNER_USER_ID），
        // 与 /mini/owner/* 的数据范围同源——投影只决定入口显隐，逐设备归属仍由接口逐条校验
        boolean ownsDevice = deviceMapper.exists(Wrappers.lambdaQuery(WsDevice.class)
                .eq(WsDevice::getOwnerUserId, userId));
        boolean ownsStation = stationMapper.exists(Wrappers.lambdaQuery(WsStation.class)
                .eq(WsStation::getOwnerUserId, userId));
        if (ownsDevice || ownsStation) {
            capabilities.add("OWNER_VIEW");
            capabilities.add("OWNER_SERVICE");
        }
        return capabilities;
    }

    @Override
    public com.jbk.tool.data.mini.vo.MiniOwnerScopeVo ownerScopeOf(Long userId) {
        if (ObjectUtil.isNull(userId) || userId <= 0) {
            return null;
        }
        java.util.List<String> stationIds = stationMapper.selectList(Wrappers.lambdaQuery(WsStation.class)
                        .eq(WsStation::getOwnerUserId, userId)).stream()
                .map(station -> String.valueOf(station.getId()))
                .collect(java.util.stream.Collectors.toList());
        java.util.List<String> deviceNos = deviceMapper.selectList(Wrappers.lambdaQuery(WsDevice.class)
                        .eq(WsDevice::getOwnerUserId, userId)).stream()
                .map(WsDevice::getDeviceNo)
                .collect(java.util.stream.Collectors.toList());
        if (stationIds.isEmpty() && deviceNos.isEmpty()) {
            return null;
        }
        return new com.jbk.tool.data.mini.vo.MiniOwnerScopeVo()
                .setStationIds(stationIds)
                .setDeviceNos(deviceNos);
    }
}
