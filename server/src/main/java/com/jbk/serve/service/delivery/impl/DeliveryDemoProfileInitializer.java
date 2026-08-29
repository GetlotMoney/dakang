package com.jbk.serve.service.delivery.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.device.WsDeviceMapper;
import com.jbk.serve.mapper.station.WsStationMapper;
import com.jbk.serve.mapper.user.WsCourierMapper;
import com.jbk.serve.mapper.user.WsUserMapper;
import com.jbk.tool.consts.user.UserEnum;
import com.jbk.tool.data.station.po.WsStation;
import com.jbk.tool.data.user.po.WsCourier;
import com.jbk.tool.data.user.po.WsUser;
import com.jbk.tool.exception.JbkException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 老板测试身份初始化：按业务唯一值解析数据库主键，不依赖某台数据库恰好分配出的 ID。
 *
 * <p>老板首次微信登录建号后，本服务把指定水站归属和配送准入幂等写入服务器数据库；
 * 虚拟配送员同样按手机号解析，并收口到同一水站服务范围。代码与数据库迁移到另一台
 * 服务器后，只需迁移私有环境变量，不需要改源码或手工猜主键。</p>
 */
@Service
@RequiredArgsConstructor
public class DeliveryDemoProfileInitializer {

    private static final long SYSTEM_ACTOR = 1L;

    private final WsUserMapper userMapper;
    private final WsCourierMapper courierMapper;
    private final WsStationMapper stationMapper;
    private final WsDeviceMapper deviceMapper;

    public record DemoContext(Long customerUserId, Long courierUserId, Long courierId, Long stationId) {
    }

    /** 老板尚未登录建号时返回 null，Worker 等待下一轮；其余错配均 fail-closed。 */
    @Transactional(rollbackFor = Exception.class)
    public DemoContext ensureReady(String customerPhone, String courierPhone, String stationCode, String now) {
        if (StrUtil.hasBlank(customerPhone, courierPhone, stationCode)) {
            throw new JbkException("老板测试身份配置不完整");
        }
        WsUser customer = userMapper.selectOne(Wrappers.lambdaQuery(WsUser.class)
                .eq(WsUser::getUserPhone, customerPhone)
                .last("LIMIT 1"));
        if (customer == null) {
            return null;
        }
        WsStation station = stationMapper.selectOne(Wrappers.lambdaQuery(WsStation.class)
                .eq(WsStation::getStationCode, stationCode)
                .last("LIMIT 1"));
        if (station == null) {
            throw new JbkException("老板测试水站不存在：" + stationCode);
        }
        WsCourier virtualCourier = courierMapper.selectOne(Wrappers.lambdaQuery(WsCourier.class)
                .eq(WsCourier::getCourierPhone, courierPhone)
                .orderByDesc(WsCourier::getId)
                .last("LIMIT 1"));
        if (virtualCourier == null) {
            throw new JbkException("老板测试虚拟配送员不存在");
        }
        if (ObjectUtil.equals(customer.getId(), virtualCourier.getUserId())) {
            throw new JbkException("老板测试客户与虚拟配送员不能是同一账号");
        }

        assignStationOwner(station, customer.getId(), now);
        assignStationDevicesOwner(station, customer.getId(), now);
        ensureBossCourier(customer, station, now);
        virtualCourier = ensureVirtualCourier(virtualCourier, station, now);
        return new DemoContext(customer.getId(), virtualCourier.getUserId(), virtualCourier.getId(), station.getId());
    }

    /**
     * 水站与设备必须使用同一机主归属，否则经营概览按水站有营收、设备列表按设备却为空，
     * 新售水单还会继续把分润记给旧设备机主。仅在老板测试初始化器开启时执行。
     */
    private void assignStationDevicesOwner(WsStation station, Long customerUserId, String now) {
        deviceMapper.assignOwnerByStation(station.getId(), customerUserId, SYSTEM_ACTOR, now);
    }

    private void assignStationOwner(WsStation station, Long customerUserId, String now) {
        if (ObjectUtil.equals(station.getOwnerUserId(), customerUserId)) {
            return;
        }
        int updated = stationMapper.update(null, Wrappers.lambdaUpdate(WsStation.class)
                .set(WsStation::getOwnerUserId, customerUserId)
                .set(WsStation::getUpdateBy, SYSTEM_ACTOR)
                .set(WsStation::getUpdateTime, now)
                .eq(WsStation::getId, station.getId()));
        if (updated != 1) {
            throw new JbkException("老板测试水站归属初始化失败");
        }
    }

    private void ensureBossCourier(WsUser customer, WsStation station, String now) {
        // 与自助申请同锁锚：避免登录刷新与初始化器并发时插出两条最新准入记录。
        courierMapper.lockUserRow(customer.getId());
        WsCourier existing = courierMapper.selectOne(Wrappers.lambdaQuery(WsCourier.class)
                .eq(WsCourier::getUserId, customer.getId())
                .orderByDesc(WsCourier::getId)
                .last("LIMIT 1"));
        String stationIds = String.valueOf(station.getId());
        if (existing == null) {
            WsCourier created = new WsCourier()
                    .setUserId(customer.getId())
                    .setCourierName(StrUtil.blankToDefault(customer.getUserName(), "老板测试配送员"))
                    .setCourierPhone(customer.getUserPhone())
                    .setStationIds(stationIds)
                    .setServiceRegion(station.getStationRegion())
                    .setCourierStatus(UserEnum.CourierStatus.ENABLED.getValue())
                    .setAuditRemark("老板测试环境自动启用；正式环境不开启此初始化器");
            created.setDataStatus(0);
            created.setCreateBy(SYSTEM_ACTOR);
            created.setCreateTime(now);
            created.setUpdateBy(SYSTEM_ACTOR);
            created.setUpdateTime(now);
            if (courierMapper.insert(created) != 1) {
                throw new JbkException("老板测试配送能力初始化失败");
            }
            return;
        }
        int updated = courierMapper.update(null, Wrappers.lambdaUpdate(WsCourier.class)
                .set(WsCourier::getCourierName, StrUtil.blankToDefault(customer.getUserName(), "老板测试配送员"))
                .set(WsCourier::getCourierPhone, customer.getUserPhone())
                .set(WsCourier::getStationIds, stationIds)
                .set(WsCourier::getServiceRegion, station.getStationRegion())
                .set(WsCourier::getCourierStatus, UserEnum.CourierStatus.ENABLED.getValue())
                .set(WsCourier::getAuditRemark, "老板测试环境自动启用；正式环境不开启此初始化器")
                .set(WsCourier::getUpdateBy, SYSTEM_ACTOR)
                .set(WsCourier::getUpdateTime, now)
                .eq(WsCourier::getId, existing.getId()));
        if (updated != 1) {
            throw new JbkException("老板测试配送能力更新失败");
        }
    }

    private WsCourier ensureVirtualCourier(WsCourier courier, WsStation station, String now) {
        String stationIds = String.valueOf(station.getId());
        boolean alreadyReady = ObjectUtil.equals(courier.getCourierStatus(), UserEnum.CourierStatus.ENABLED.getValue())
                && ObjectUtil.equals(courier.getStationIds(), stationIds);
        if (!alreadyReady) {
            int updated = courierMapper.update(null, Wrappers.lambdaUpdate(WsCourier.class)
                    .set(WsCourier::getStationIds, stationIds)
                    .set(WsCourier::getServiceRegion, station.getStationRegion())
                    .set(WsCourier::getCourierStatus, UserEnum.CourierStatus.ENABLED.getValue())
                    .set(WsCourier::getAuditRemark, "老板测试虚拟配送员；正式环境不开启")
                    .set(WsCourier::getUpdateBy, SYSTEM_ACTOR)
                    .set(WsCourier::getUpdateTime, now)
                    .eq(WsCourier::getId, courier.getId()));
            if (updated != 1) {
                throw new JbkException("老板测试虚拟配送员初始化失败");
            }
            courier = courierMapper.selectById(courier.getId());
        }
        return courier;
    }
}
