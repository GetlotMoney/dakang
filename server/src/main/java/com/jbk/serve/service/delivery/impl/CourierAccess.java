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

    /** 绑号闸：履约主体必须可联系。 */
    @Autowired
    private com.jbk.serve.service.mini.auth.MiniPhoneGate phoneGate;
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
        // 绑号闸：配送员是要对真实用户上门的履约主体，下单人会拿到「谁来送」这条信息，
        // 出问题时平台必须能立刻联系上他。这里是一期水配送全部动作
        //（接单/推进/签收/异常上报/申诉举证）的唯一准入口，挂一处即全覆盖。
        phoneGate.requirePhoneBound(actorUserId, "配送员履约");
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
