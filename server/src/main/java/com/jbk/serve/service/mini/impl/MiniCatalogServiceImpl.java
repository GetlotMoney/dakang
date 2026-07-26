package com.jbk.serve.service.mini.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.device.WsDeviceMapper;
import com.jbk.serve.mapper.device.WsDeviceOutletMapper;
import com.jbk.serve.mapper.product.WsWaterTypeMapper;
import com.jbk.serve.mapper.station.WsStationMapper;
import com.jbk.serve.service.mini.IMiniCatalogService;
import com.jbk.tool.data.device.po.WsDevice;
import com.jbk.tool.data.device.po.WsDeviceOutlet;
import com.jbk.tool.data.mini.vo.MiniCatalogVo;
import com.jbk.tool.data.product.po.WsWaterType;
import com.jbk.tool.data.station.po.WsStation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 小程序目录读模型实现（只读，无副作用）。
 *
 * <p>字段口径对齐 miniapp catalog.ts：水种 8 条为甲方未确认前的占位（placeholder 恒 true，
 * 确认后仅改数据不改结构）；水站状态只有营业/停用两态（STATION_STATUS 10 字典），
 * distanceMeters 未接定位能力恒为空——不造伪距离。</p>
 *
 * @author dakang
 * @since 2026-07-24
 */
@Service
public class MiniCatalogServiceImpl implements IMiniCatalogService {

    @Autowired
    private WsWaterTypeMapper waterTypeMapper;
    @Autowired
    private WsStationMapper stationMapper;
    @Autowired
    private WsDeviceMapper deviceMapper;
    @Autowired
    private WsDeviceOutletMapper outletMapper;

    @Override
    public List<MiniCatalogVo.MiniWaterTypeVo> listWaterTypes() {
        return waterTypeMapper.selectList(Wrappers.lambdaQuery(WsWaterType.class)
                        .orderByAsc(WsWaterType::getWaterSort)
                        .orderByAsc(WsWaterType::getId)).stream()
                .map(item -> new MiniCatalogVo.MiniWaterTypeVo()
                        .setId(item.getId())
                        .setName(item.getWaterName())
                        .setEnabled(ObjectUtil.equal(item.getWaterStatus(), 1))
                        // 8 种水最终定义待甲方确认（AGENTS 一期待确认项）；确认前一律占位
                        .setPlaceholder(true))
                .collect(Collectors.toList());
    }

    @Override
    public List<MiniCatalogVo.MiniStationVo> listStations() {
        List<WsStation> stations = stationMapper.selectList(Wrappers.lambdaQuery(WsStation.class)
                .orderByAsc(WsStation::getId));
        return stations.stream().map(this::toStationVo).collect(Collectors.toList());
    }

    private MiniCatalogVo.MiniStationVo toStationVo(WsStation station) {
        List<WsDevice> devices = deviceMapper.selectList(Wrappers.lambdaQuery(WsDevice.class)
                .eq(WsDevice::getStationId, station.getId()));
        long onlineCount = devices.stream()
                // 在线状态(1300)：1在线；2离线/3未激活不计
                .filter(device -> ObjectUtil.equal(device.getOnlineStatus(), 1))
                .count();
        List<Long> deviceIds = devices.stream().map(WsDevice::getId).collect(Collectors.toList());
        long outletCount = deviceIds.isEmpty() ? 0 : outletMapper.selectCount(
                Wrappers.lambdaQuery(WsDeviceOutlet.class)
                        .in(WsDeviceOutlet::getDeviceId, deviceIds)
                        .eq(WsDeviceOutlet::getOutletStatus, 1));
        return new MiniCatalogVo.MiniStationVo()
                .setId(station.getId())
                .setStationName(station.getStationName())
                .setAddress(StrUtil.emptyIfNull(station.getStationRegion())
                        + StrUtil.emptyIfNull(station.getStationAddress()))
                .setDistanceMeters(null)
                .setAvailableOutletCount((int) outletCount)
                .setOnlineDeviceCount((int) onlineCount)
                // 一期水站只有 1正常/2禁用 两态：营业外一律 CLOSED（不虚构维护中状态）
                .setStatus(ObjectUtil.equal(station.getStationStatus(), 1) ? "OPEN" : "CLOSED");
    }
}
