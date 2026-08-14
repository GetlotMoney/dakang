package com.jbk.serve.service.mall.impl;

import cn.hutool.json.JSONUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.mall.WsMallCourierScopeMapper;
import com.jbk.serve.mapper.mall.WsMallFulfillmentMapper;
import com.jbk.serve.mapper.user.WsCourierMapper;
import com.jbk.serve.service.mall.IMallSelfDeliveryService;
import com.jbk.serve.service.mini.notify.WechatNotifyEnqueue;
import com.jbk.serve.service.mall.IMallShipmentService;
import com.jbk.serve.service.message.IWsMessageService;
import com.jbk.tool.consts.mall.MallEnum;
import com.jbk.tool.consts.mini.WechatNotifyEnum;
import com.jbk.tool.consts.mini.WechatShippingEnum;
import com.jbk.tool.consts.message.MessageEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.consts.user.UserEnum;
import com.jbk.tool.data.mall.bo.MallFulfillActionBo;
import com.jbk.tool.data.mall.bo.MallFulfillAssignBo;
import com.jbk.tool.data.mall.po.WsMallFulfillment;
import com.jbk.tool.data.mall.vo.MallCourierCandidateVo;
import com.jbk.tool.data.mall.vo.MallFulfillVo;
import com.jbk.tool.data.user.po.WsCourier;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import com.jbk.tool.utils.PhoneMask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 自营配送渠道服务实现（E2E-09 L1）。只处理自营任务：每个配送端出口先过
 * {@link #requireSelfMode}，第三方任务对配送员完全不可见，直调也拒绝。
 * 分配动作同事务 CAS 0→1 冻结渠道，与第三方建单的 0→2 互斥，冻结失败整事务回滚。
 * 状态机原语复用 {@link MallFulfillmentServiceImpl}（渠道无关，复制第二份必然漂移），
 * 本类只承载自营特有判据与动作。
 *
 * @author dakang
 * @since 2026-08-11
 */
@Slf4j
@Service
public class MallSelfDeliveryServiceImpl implements IMallSelfDeliveryService {

    /** 绑号闸：被派单的配送员必须可联系，否则整单会卡死在取货前。 */
    /** 订阅通知登记：与站内信同事务——本事务回滚意味着那件事没发生，通知必须一起消失。 */
    @Autowired
    private WechatNotifyEnqueue notifyEnqueue;
    @Autowired
    private com.jbk.serve.service.mini.wxship.WechatShippingEnqueue shippingEnqueue;
    @Autowired
    private com.jbk.serve.service.mini.auth.MiniPhoneGate phoneGate;
    @Autowired
    private MallFulfillCore core;
    @Autowired
    private WsMallFulfillmentMapper fulfillMapper;
    @Autowired
    private WsMallCourierScopeMapper scopeMapper;
    @Autowired
    private WsCourierMapper courierMapper;
    @Autowired
    private IWsMessageService messageService;
    @Autowired
    private IMallShipmentService shipmentService;

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public MallFulfillVo assign(Long operatorId, MallFulfillAssignBo bo) {
        WsMallFulfillment task = core.requireTask(bo.getOrderNo());
        core.requireWarehouseOperator(operatorId, task);
        core.requireFulfillingOrder(task);
        WsCourier courier = core.requireAssignableCourier(task, bo.getCourierId());
        // 绑号闸挂在**分配**这一步，而不是只挂在配送员自己取货那一步：
        // 派单成功后订单就归到这个配送员头上了，若他没绑手机号，取货时才撞 627——
        // 那时任务已被占、订单已付款，而平台既联系不上他、也没有改派入口，整单永久卡死。
        // 拦在派单前，运营还能当场换一个人。
        phoneGate.requirePhoneBound(courier.getUserId(), "商城分配自营配送员");
        String now = DateUtils.time();

        // 渠道冻结先于一切：影响 0 行只有一种解释——这单已被另一条链接走
        freezeSelfMode(task, operatorId, now);
        // 自营也建包裹：三端展示、售后与对账都按包裹口径读，两条链不能只有一条有出库物
        shipmentService.ensureShipment(task, MallEnum.ShipmentDirection.FORWARD.getValue(), 1,
                null, MallShipmentGate.SELF_PROVIDER, operatorId, now);

        int moved = fulfillMapper.casAssign(task.getId(), courier.getId(),
                MallEnum.FulfillStatus.PENDING_ASSIGN.getValue(),
                MallEnum.FulfillStatus.PENDING_FETCH.getValue(),
                task.getVersion(), operatorId, now);
        if (moved != 1) {
            // 0 行只有两种解释：状态已被推进，或已经有人分配过——两者都不能覆盖
            throw new JbkException("任务已被分配或状态已变化，请刷新后重试");
        }
        task.setCourierId(courier.getId())
                .setFulfillStatus(MallEnum.FulfillStatus.PENDING_FETCH.getValue())
                .setVersion(task.getVersion() + 1);
        // SUBJECT_ID 是配送归属的唯一结构化证据：ACTOR_ID 记「谁分配的」（仓库操作员），
        // SUBJECT_ID 记「分给谁」。任务 COURIER_ID 是可变字段，单独比它证明不了归属
        core.writeTrace(task, MallEnum.FulfillStatus.PENDING_FETCH, MallEnum.ActorType.WAREHOUSE,
                operatorId, courier.getId(), now, "已分配配送员 " + courier.getCourierName());
        messageService.sendInApp(task.getUserId(), MessageEnum.MsgDomain.MALL,
                "商城订单已分配配送员",
                "您的商城订单 " + task.getOrderNo() + " 已分配配送员，正在准备配送。",
                "mallOrder", task.getOrderNo(), now);
        core.writeAudit(task, MallEnum.FulfillStatus.PENDING_FETCH, OpsEnum.ActorPortal.MANAGE,
                operatorId, "分配配送员 " + courier.getId());
        return core.toVoChecked(core.requireTask(task.getOrderNo()), true);
    }

    @Override
    public List<MallCourierCandidateVo> courierCandidates(Long operatorId, String orderNo) {
        WsMallFulfillment task = core.requireTask(orderNo);
        core.requireWarehouseOperator(operatorId, task);
        core.requireLinkedOrder(task);
        requireAssignableChannel(task);
        List<Long> ids = scopeMapper.selectAssignableCourierIds(task.getWarehouseId(),
                UserEnum.CourierStatus.ENABLED.getValue());
        List<MallCourierCandidateVo> list = new ArrayList<>();
        for (Long id : ids) {
            WsCourier courier = courierMapper.selectById(id);
            if (ObjectUtil.isNull(courier)
                    || ObjectUtil.equal(courier.getUserId(), task.getUserId())) {
                // 下单人自己不出现在候选里；服务端分配时还会再拒一次
                continue;
            }
            list.add(new MallCourierCandidateVo()
                    .setCourierId(courier.getId())
                    .setCourierName(courier.getCourierName())
                    .setCourierPhone(PhoneMask.mask(courier.getCourierPhone())));
        }
        return list;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public MallFulfillVo fetch(Long courierUserId, MallFulfillActionBo bo) {
        WsMallFulfillment task = requireOwnSelfTask(courierUserId, bo.getOrderNo());
        core.requireFulfillingOrder(task);
        String now = DateUtils.time();
        core.advance(task, MallEnum.FulfillStatus.PENDING_FETCH, MallEnum.FulfillStatus.DELIVERING,
                "FETCH_TIME", courierUserId, now);
        advanceShipment(task, MallEnum.ShipmentStatus.PICKED_UP, "PICKUP_TIME", courierUserId, now);
        core.writeTrace(task, MallEnum.FulfillStatus.DELIVERING, MallEnum.ActorType.COURIER,
                courierUserId, now, "配送员已取货，开始配送");
        messageService.sendInApp(task.getUserId(), MessageEnum.MsgDomain.MALL,
                "商城订单配送中",
                "您的商城订单 " + task.getOrderNo() + " 已由配送员取货，正在配送途中。",
                "mallOrder", task.getOrderNo(), now);
        notifyEnqueue.enqueue(WechatNotifyEnum.EventType.MALL_SHIPPED,
                WechatNotifyEnum.BizObjectType.MALL_ORDER, task.getOrderNo(), task.getUserId(),
                JSONUtil.createObj().set("time", now));
        // WX-ECO S4：微信收款单发货后须向微信同步发货信息（资金结算前提）；
        // Pay-Sim 单由登记服务分流落 SKIP。自营=同城配送模式，无运单号。
        forwardShipmentId(task).ifPresent(shipmentId -> shippingEnqueue.enqueue(
                task.getOrderId(), task.getOrderNo(), task.getUserId(), shipmentId,
                MallEnum.ShipmentDirection.FORWARD.getValue(), 1,
                WechatShippingEnum.LogisticsType.SAME_CITY,
                MallShipmentGate.SELF_PROVIDER, null, "商城订单商品"));
        // 配送员与用户共用 KH_USER 会话，身份必须显式声明，绝不让事件服务按会话去猜
        core.writeAudit(task, MallEnum.FulfillStatus.DELIVERING, OpsEnum.ActorPortal.COURIER,
                courierUserId, "配送员取货，开始配送");
        return detailForCourier(courierUserId, task.getOrderNo());
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public MallFulfillVo arrive(Long courierUserId, MallFulfillActionBo bo) {
        WsMallFulfillment task = requireOwnSelfTask(courierUserId, bo.getOrderNo());
        core.requireFulfillingOrder(task);
        String now = DateUtils.time();
        core.advance(task, MallEnum.FulfillStatus.DELIVERING, MallEnum.FulfillStatus.ARRIVED,
                "ARRIVE_TIME", courierUserId, now);
        advanceShipment(task, MallEnum.ShipmentStatus.DELIVERED, "DELIVER_TIME", courierUserId, now);
        core.writeTrace(task, MallEnum.FulfillStatus.ARRIVED, MallEnum.ActorType.COURIER,
                courierUserId, now, "配送员已送达，待用户确认");
        messageService.sendInApp(task.getUserId(), MessageEnum.MsgDomain.MALL,
                "商城订单已送达",
                "您的商城订单 " + task.getOrderNo() + " 已送达，请及时确认收货。",
                "mallOrder", task.getOrderNo(), now);
        core.writeAudit(task, MallEnum.FulfillStatus.ARRIVED, OpsEnum.ActorPortal.COURIER,
                courierUserId, "配送员送达，待用户确认收货");
        return detailForCourier(courierUserId, task.getOrderNo());
    }

    @Override
    public List<MallFulfillVo> listForCourier(Long courierUserId) {
        WsCourier courier = core.requireEnabledCourierByUser(courierUserId);
        List<WsMallFulfillment> tasks = fulfillMapper.selectList(
                Wrappers.lambdaQuery(WsMallFulfillment.class)
                        .eq(WsMallFulfillment::getCourierId, courier.getId())
                        // 第三方任务对配送端必须完全不可见：过滤下沉到 SQL，
                        // 而不是查出来再在内存里挑——内存过滤漏一处就是一次越权展示
                        .eq(WsMallFulfillment::getFulfillMode,
                                MallEnum.FulfillMode.SELF_DELIVERY.getValue())
                        .orderByDesc(WsMallFulfillment::getId));
        List<MallFulfillVo> list = new ArrayList<>();
        for (WsMallFulfillment task : tasks) {
            // 列表同样过证据：被改指向的任务不得因为"列表只是只读"就漏出收货信息
            core.requireAssignedCourierEvidence(task);
            list.add(core.toVoChecked(task, false));
        }
        return list;
    }

    @Override
    public MallFulfillVo detailForCourier(Long courierUserId, String orderNo) {
        WsMallFulfillment task = requireOwnSelfTask(courierUserId, orderNo);
        return core.toVoChecked(task, true);
    }

    /**
     * 冻结渠道为自营。
     *
     * <p>已经是自营（重复分配同一单）不算失败——`ensureTask` 之后运营可能连点两次；
     * 已经是第三方或冻结失败一律拒绝。</p>
     */
    private void freezeSelfMode(WsMallFulfillment task, Long operatorId, String now) {
        if (ObjectUtil.equal(task.getFulfillMode(),
                MallEnum.FulfillMode.SELF_DELIVERY.getValue())) {
            return;
        }
        if (ObjectUtil.equal(task.getFulfillMode(),
                MallEnum.FulfillMode.THIRD_PARTY.getValue())) {
            throw new JbkException("该订单已按第三方物流发运，不能再分配自营配送员");
        }
        int frozen = fulfillMapper.casFreezeMode(task.getId(),
                MallEnum.FulfillMode.SELF_DELIVERY.getValue(), operatorId, now);
        if (frozen != 1) {
            throw new JbkException("承运渠道已被占用（可能已创建第三方运单），请刷新后重试");
        }
        task.setFulfillMode(MallEnum.FulfillMode.SELF_DELIVERY.getValue());
        // 冻结这一步也 VERSION+1。不同步内存副本，后面那句状态 CAS 就会拿着旧版本号撞空，
        // 表现成"任务已被分配"——而实际上刚被自己改的。行锁在本事务手上，故 +1 精确
        task.setVersion(task.getVersion() + 1);
    }

    /**
     * 配送端唯一入口：**渠道闸先于一切**，再判本人归属。
     *
     * <p>顺序不能反。core.requireOwnCourierTask 内部先跑分配证据校验，而第三方单在
     * 「待承运方揽收」阶段恰好是「已达分配阶段却无配送员」——于是配送员拿一个第三方单号
     * 直查，收到的是「请人工核查」而不是「不存在」。这句话本身就泄露了两件事：
     * 这个单号存在，且它已经走到了分配之后。把渠道闸提到最前，第三方单在配送端
     * 恒等于不存在，任何阶段都问不出别的答案。</p>
     */
    private WsMallFulfillment requireOwnSelfTask(Long courierUserId, String orderNo) {
        requireSelfMode(core.requireTask(orderNo));
        WsMallFulfillment task = core.requireOwnCourierTask(courierUserId, orderNo);
        requireSelfMode(task);
        return task;
    }

    /** 配送端出口守卫：非自营任务一律按不存在处理，不泄露"存在但不归你管"。 */
    private void requireSelfMode(WsMallFulfillment task) {
        if (!ObjectUtil.equal(task.getFulfillMode(),
                MallEnum.FulfillMode.SELF_DELIVERY.getValue())) {
            throw new JbkException("配送任务不存在");
        }
    }

    /** 分配前的渠道资格：已冻结为第三方的任务不再提供自营候选。 */
    private void requireAssignableChannel(WsMallFulfillment task) {
        if (ObjectUtil.equal(task.getFulfillMode(),
                MallEnum.FulfillMode.THIRD_PARTY.getValue())) {
            throw new JbkException("该订单已按第三方物流发运，无自营配送员可选");
        }
    }

    /** 自营包裹随配送员动作同步推进：两条链的包裹口径必须一致，否则对账只能靠人脑翻译。 */
    private java.util.Optional<Long> forwardShipmentId(WsMallFulfillment task) {
        return shipmentService.listByOrderNo(task.getOrderNo()).stream()
                .filter(vo -> ObjectUtil.equal(vo.getDirection(),
                        MallEnum.ShipmentDirection.FORWARD.getValue()))
                .findFirst()
                .map(vo -> Long.valueOf(vo.getShipmentId()));
    }

    private void advanceShipment(WsMallFulfillment task, MallEnum.ShipmentStatus to,
                                 String timeColumn, Long operator, String now) {
        shipmentService.listByOrderNo(task.getOrderNo()).stream()
                .filter(vo -> ObjectUtil.equal(vo.getDirection(),
                        MallEnum.ShipmentDirection.FORWARD.getValue()))
                .findFirst()
                .ifPresent(vo -> {
                    // 自营链是单线程人工动作（配送员点取货/送达），不存在并发事实抢占，
                    // 因此 LOST 在这里等价于数据异常；但仍不静默吞掉——它意味着有人
                    // 在同一个包裹上并发写，那是需要人看的信号。
                    IMallShipmentService.Advance moved = shipmentService.advanceById(
                            Long.valueOf(vo.getShipmentId()), to.getValue(), timeColumn,
                            operator, now);
                    if (moved == IMallShipmentService.Advance.LOST) {
                        log.warn("自营包裹推进被并发抢占 orderNo={} to={}", task.getOrderNo(), to);
                    }
                });
    }
}
