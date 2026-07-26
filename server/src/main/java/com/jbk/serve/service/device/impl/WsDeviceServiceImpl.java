package com.jbk.serve.service.device.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jbk.serve.mapper.device.WsDeviceMapper;
import com.jbk.serve.mapper.device.WsDeviceTelemetryMapper;
import com.jbk.serve.mapper.device.WsQrcodeMapper;
import com.jbk.serve.service.device.IWsDeviceOutletService;
import com.jbk.serve.service.device.IWsDeviceService;
import com.jbk.serve.service.user.IWsUserService;
import com.jbk.serve.service.station.IWsStationService;
import com.jbk.tool.consts.device.DeviceEnum;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.device.bo.WsDeviceBo;
import com.jbk.tool.data.device.po.WsDevice;
import com.jbk.tool.data.device.po.WsDeviceOutlet;
import com.jbk.tool.data.device.po.WsDeviceTelemetry;
import com.jbk.tool.data.device.po.WsQrcode;
import com.jbk.tool.data.device.vo.WsDeviceTelemetryVo;
import com.jbk.tool.data.device.vo.WsDeviceVo;
import com.jbk.tool.data.device.vo.WsQrcodeVo;
import com.jbk.tool.data.user.po.WsUser;
import com.jbk.tool.data.station.po.WsStation;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.OptionalUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 设备服务实现
 * <p>档案编辑边界：在线状态/运行状态/心跳/故障码/信号由设备上行链路维护，
 * 后台仅可编辑档案字段；新建设备初始为 未激活+空闲，收到首个心跳后转在线。</p>
 *
 * @author dakang
 * @since 2026-07-12
 */
@Service
public class WsDeviceServiceImpl extends ServiceImpl<WsDeviceMapper, WsDevice> implements IWsDeviceService {

    @Autowired
    private IWsStationService stationService;
    @Autowired
    private IWsUserService wsUserService;
    @Autowired
    private IWsDeviceOutletService outletService;
    @Autowired
    private WsQrcodeMapper qrcodeMapper;
    @Autowired
    private WsDeviceTelemetryMapper telemetryMapper;

    @Override
    public PageDataVo<WsDeviceVo> pageData(WsDeviceBo deviceBo) {
        Page<WsDevice> page = page(new Page<>(deviceBo.getCurrent(), deviceBo.getSize()),
                Wrappers.lambdaQuery(WsDevice.class)
                        .like(StrUtil.isNotBlank(deviceBo.getDeviceNo()), WsDevice::getDeviceNo, deviceBo.getDeviceNo())
                        .like(StrUtil.isNotBlank(deviceBo.getDeviceName()), WsDevice::getDeviceName, deviceBo.getDeviceName())
                        .eq(ObjectUtil.isNotNull(deviceBo.getStationId()), WsDevice::getStationId, deviceBo.getStationId())
                        .eq(ObjectUtil.isNotNull(deviceBo.getOnlineStatus()), WsDevice::getOnlineStatus, deviceBo.getOnlineStatus())
                        .eq(ObjectUtil.isNotNull(deviceBo.getRunStatus()), WsDevice::getRunStatus, deviceBo.getRunStatus())
                        .orderByDesc(WsDevice::getId));
        List<WsDeviceVo> voList = page.getRecords().stream()
                .map(e -> BeanUtil.copyProperties(e, WsDeviceVo.class))
                .collect(Collectors.toList());
        fillDerived(voList);
        return PageDataVo.getPageData(voList, page.getTotal());
    }

    @Override
    public WsDeviceVo getData(Long id) {
        WsDevice device = getById(id);
        OptionalUtils.nullToElseThrow(device, "设备不存在");
        WsDeviceVo vo = BeanUtil.copyProperties(device, WsDeviceVo.class);
        fillDerived(CollUtil.newArrayList(vo));
        return vo;
    }

    @Override
    public Long saveData(WsDeviceBo deviceBo) {
        // 服务层查重用于返回明确错误；uk_device_no 唯一约束处理并发写入冲突。
        long cnt = count(Wrappers.lambdaQuery(WsDevice.class)
                .eq(WsDevice::getDeviceNo, deviceBo.getDeviceNo()));
        OptionalUtils.gtZeroElseThrow(cnt, "设备编号已存在");
        checkStation(deviceBo.getStationId());
        checkOwnerUser(deviceBo.getOwnerUserId());
        WsDevice device = BeanUtil.copyProperties(deviceBo, WsDevice.class);
        // 新建设备初始状态为未激活、空闲；首次有效心跳负责激活，后台不提供人工修改入口。
        device.setOnlineStatus(DeviceEnum.OnlineStatus.INACTIVE.getValue());
        device.setRunStatus(DeviceEnum.RunStatus.IDLE.getValue());
        save(device);
        return device.getId();
    }

    @Override
    public Boolean updateData(WsDeviceBo deviceBo) {
        WsDevice exist = getById(deviceBo.getId());
        OptionalUtils.nullToElseThrow(exist, "设备不存在");
        // 设备编号是 MQTT 身份与订单归属的根，建档后禁止修改（防指令错发，REQ-025）
        if (StrUtil.isNotBlank(deviceBo.getDeviceNo())
                && ObjectUtil.notEqual(deviceBo.getDeviceNo(), exist.getDeviceNo())) {
            throw new JbkException("设备编号建档后不可修改（如换机请走商业一期迁移流程）");
        }
        checkStation(deviceBo.getStationId());
        checkOwnerUser(deviceBo.getOwnerUserId());
        WsDevice device = BeanUtil.copyProperties(deviceBo, WsDevice.class);
        device.setDeviceNo(null);
        // Bo 的在线/运行状态仅是列表筛选字段，严禁经编辑写回（状态只能由设备上行链路维护）
        device.setOnlineStatus(null);
        device.setRunStatus(null);
        // 机主可解绑：显式覆盖（null 不会被 MP 更新，走 UpdateWrapper 置空）
        if (ObjectUtil.isNull(deviceBo.getOwnerUserId())) {
            update(Wrappers.lambdaUpdate(WsDevice.class)
                    .eq(WsDevice::getId, deviceBo.getId())
                    .set(WsDevice::getOwnerUserId, null));
        }
        updateById(device);
        return Boolean.TRUE;
    }

    @Override
    public Boolean deleteData(Long id) {
        WsDevice device = getById(id);
        OptionalUtils.nullToElseThrow(device, "设备不存在");
        // 设备是订单/指令追溯的根：已产生指令或绑定二维码的设备禁删
        // TODO 订单模块接入后追加"存在订单禁删"校验
        long qrCnt = qrcodeMapper.selectCount(Wrappers.lambdaQuery(WsQrcode.class)
                .eq(WsQrcode::getDeviceId, id));
        if (qrCnt > 0) {
            throw new JbkException("该设备绑定了 " + qrCnt + " 个二维码，请先解绑");
        }
        // 出水口随设备一并删除（物理部件无独立生命周期）
        outletService.remove(Wrappers.lambdaQuery(WsDeviceOutlet.class)
                .eq(WsDeviceOutlet::getDeviceId, id));
        return removeById(id);
    }

    @Override
    public List<WsQrcodeVo> qrcodeList(Long deviceId) {
        List<WsQrcode> list = qrcodeMapper.selectList(Wrappers.lambdaQuery(WsQrcode.class)
                .eq(WsQrcode::getDeviceId, deviceId)
                .orderByAsc(WsQrcode::getId));
        if (CollUtil.isEmpty(list)) {
            return List.of();
        }
        // 出水口编号派生
        Map<Long, Integer> outletNoMap = outletService.listByDevice(deviceId).stream()
                .collect(Collectors.toMap(o -> o.getId(), o -> o.getOutletNo()));
        return list.stream().map(e -> {
            WsQrcodeVo vo = BeanUtil.copyProperties(e, WsQrcodeVo.class);
            if (ObjectUtil.isNotNull(e.getOutletId())) {
                vo.setOutletNo(outletNoMap.get(e.getOutletId()));
            }
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    public WsDeviceTelemetryVo latestTelemetry(Long deviceId) {
        Page<WsDeviceTelemetry> page = telemetryMapper.selectPage(new Page<>(1, 1),
                Wrappers.lambdaQuery(WsDeviceTelemetry.class)
                        .eq(WsDeviceTelemetry::getDeviceId, deviceId)
                        .orderByDesc(WsDeviceTelemetry::getId));
        if (CollUtil.isEmpty(page.getRecords())) {
            return null;
        }
        return BeanUtil.copyProperties(page.getRecords().get(0), WsDeviceTelemetryVo.class);
    }

    private void checkStation(Long stationId) {
        if (ObjectUtil.isNull(stationId)) {
            return;
        }
        WsStation station = stationService.getById(stationId);
        OptionalUtils.nullToElseThrow(station, "所属水站不存在");
    }

    private void checkOwnerUser(Long ownerUserId) {
        if (ObjectUtil.isNull(ownerUserId)) {
            return;
        }
        WsUser user = wsUserService.getById(ownerUserId);
        OptionalUtils.nullToElseThrow(user, "机主用户不存在");
    }

    /** 填充派生字段：水站名、机主名、出水口数 */
    private void fillDerived(List<WsDeviceVo> voList) {
        if (CollUtil.isEmpty(voList)) {
            return;
        }
        List<Long> stationIdList = voList.stream()
                .map(WsDeviceVo::getStationId).filter(ObjectUtil::isNotNull).distinct().collect(Collectors.toList());
        if (CollUtil.isNotEmpty(stationIdList)) {
            Map<Long, WsStation> stationMap = stationService.listByIds(stationIdList).stream()
                    .collect(Collectors.toMap(WsStation::getId, Function.identity()));
            voList.forEach(vo -> {
                WsStation station = stationMap.get(vo.getStationId());
                if (ObjectUtil.isNotNull(station)) {
                    vo.setStationName(station.getStationName());
                }
            });
        }
        List<Long> ownerIdList = voList.stream()
                .map(WsDeviceVo::getOwnerUserId).filter(ObjectUtil::isNotNull).distinct().collect(Collectors.toList());
        if (CollUtil.isNotEmpty(ownerIdList)) {
            Map<Long, WsUser> userMap = wsUserService.listByIds(ownerIdList).stream()
                    .collect(Collectors.toMap(WsUser::getId, Function.identity()));
            voList.forEach(vo -> {
                WsUser user = userMap.get(vo.getOwnerUserId());
                if (ObjectUtil.isNotNull(user)) {
                    vo.setOwnerUserName(user.getUserName());
                }
            });
        }
        // 出水口数
        List<Long> deviceIdList = voList.stream().map(WsDeviceVo::getId).collect(Collectors.toList());
        Map<Long, Long> outletCountMap = outletService.list(Wrappers.lambdaQuery(WsDeviceOutlet.class)
                        .in(WsDeviceOutlet::getDeviceId, deviceIdList)).stream()
                .collect(Collectors.groupingBy(WsDeviceOutlet::getDeviceId, Collectors.counting()));
        voList.forEach(vo -> vo.setOutletCount(outletCountMap.getOrDefault(vo.getId(), 0L)));
    }
}
