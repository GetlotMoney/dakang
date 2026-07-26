package com.jbk.serve.service.delivery.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.user.WsCourierMapper;
import com.jbk.serve.service.delivery.CourierScope;
import com.jbk.tool.consts.user.UserEnum;
import com.jbk.tool.data.user.po.WsCourier;
import com.jbk.tool.exception.JbkException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 配送员会话准入解析（E2E-03 规则8；铁律6：范围在 Service 层按登录人强制解析，
 * 绝不采信前端传入的配送员ID或范围）。
 *
 * @author dakang
 * @since 2026-07-23
 */
@Component
public class CourierAccess {

    @Autowired
    private WsCourierMapper courierMapper;

    /** 解析后的配送员上下文：准入记录 + 服务范围（已保证非空集）。 */
    public record EnabledCourier(WsCourier courier, Set<Long> stationIds) {

        public boolean allowsStation(Long stationId) {
            return stationId != null && stationIds.contains(stationId);
        }
    }

    /**
     * 按会话用户解析启用配送员。未准入/待审核/停用/驳回统一拒绝（不泄露具体准入阶段），
     * 范围空集也拒绝——未配置范围默认不可接单（Mock COURIER_SCOPE_DENIED 口径）。
     */
    public EnabledCourier requireEnabledCourier(Long actorUserId) {
        if (actorUserId == null || actorUserId <= 0) {
            throw new JbkException("会话用户非法");
        }
        WsCourier courier = courierMapper.selectOne(Wrappers.lambdaQuery(WsCourier.class)
                .eq(WsCourier::getUserId, actorUserId)
                .orderByDesc(WsCourier::getId)
                .last("LIMIT 1"));
        if (ObjectUtil.isNull(courier)
                || ObjectUtil.notEqual(courier.getCourierStatus(), UserEnum.CourierStatus.ENABLED.getValue())) {
            throw new JbkException("当前账号不具备配送接单资格");
        }
        Set<Long> stationIds = CourierScope.parseStationIds(courier.getStationIds());
        if (stationIds.isEmpty()) {
            throw new JbkException("配送范围未配置，默认不可接单");
        }
        return new EnabledCourier(courier, stationIds);
    }
}
