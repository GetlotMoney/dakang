package com.jbk.serve.service.mall.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.mall.WsMallCourierScopeMapper;
import com.jbk.serve.mapper.mall.WsMallFulfillmentMapper;
import com.jbk.serve.mapper.mall.WsMallFulfillmentTraceMapper;
import com.jbk.serve.mapper.mall.WsMallOrderMapper;
import com.jbk.serve.mapper.mall.WsMallWarehouseMapper;
import com.jbk.serve.mapper.mall.WsMallWarehouseOperatorMapper;
import com.jbk.serve.mapper.user.WsCourierMapper;
import com.jbk.serve.service.message.IWsMessageService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.user.UserEnum;
import com.jbk.tool.consts.mall.MallEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.mall.po.WsMallFulfillment;
import com.jbk.tool.data.mall.po.WsMallFulfillmentTrace;
import com.jbk.tool.data.mall.po.WsMallOrder;
import com.jbk.tool.data.mall.po.WsMallWarehouse;
import com.jbk.tool.data.mall.vo.MallFulfillTraceVo;
import com.jbk.tool.data.mall.vo.MallFulfillVo;
import com.jbk.tool.data.user.po.WsCourier;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.PhoneMask;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 商城履约状态机原语（E2E-09 L1）：渠道无关的共享底座——共键校验、状态 CAS、轨迹、审计、
 * Vo 组装对自营与第三方逐字相同。本类不开事务，恒在调用方事务里运行
 * （单独提交任一步都会留下半截状态）。
 *
 * @author dakang
 * @since 2026-08-11
 */
@Component
public class MallFulfillCore {

    /** 履约轨迹幂等键前缀。 */
    static final String TRACE_KEY_PREFIX = "MFT:";
    /** 履约审计幂等键前缀。 */
    static final String AUDIT_KEY_PREFIX = "MALL_FULFILL:";
    /** 系统操作者。 */
    static final long SYSTEM_OPERATOR = 0L;

    /** 绑号闸：商城自配送履约主体必须可联系。 */
    @Autowired
    private com.jbk.serve.service.mini.auth.MiniPhoneGate phoneGate;
    @Autowired
    WsMallFulfillmentMapper fulfillMapper;

    @Autowired
    WsMallFulfillmentTraceMapper traceMapper;

    @Autowired
    WsMallCourierScopeMapper scopeMapper;

    @Autowired
    WsMallOrderMapper orderMapper;

    @Autowired
    WsMallWarehouseMapper warehouseMapper;

    @Autowired
    WsMallWarehouseOperatorMapper whOperatorMapper;

    @Autowired
    WsCourierMapper courierMapper;

    @Autowired
    IWsMessageService messageService;

    @Autowired
    IWsDomainEventService domainEventService;

    @Autowired
    MallExchangeSettlement exchangeSettlement;

    WsMallOrder requirePayableOrder(String orderNo) {
        WsMallOrder order = orderMapper.selectByOrderNoIncludingDeleted(orderNo);
        if (ObjectUtil.isNull(order) || !ObjectUtil.equal(order.getDataStatus(), 0)) {
            throw new JbkException("订单不存在");
        }
        if (!ObjectUtil.equal(order.getOrderStatus(), MallEnum.OrderStatus.PAID.getValue())) {
            throw new JbkException("订单未支付或已推进，不生成履约任务");
        }
        return order;
    }

    WsMallFulfillment requireTask(String orderNo) {
        WsMallFulfillment task = fulfillMapper.selectByOrderNoIncludingDeleted(orderNo);
        if (ObjectUtil.isNull(task)) {
            throw new JbkException("履约任务不存在");
        }
        requireAlive(task);
        return task;
    }

    void requireAlive(WsMallFulfillment task) {
        if (!ObjectUtil.equal(task.getDataStatus(), 0)) {
            throw new JbkException("履约任务已删除，请人工核查");
        }
    }

    /**
     * 任务—订单关联校验器（唯一出处）：所有动作与角色的读取出口都必须过这里；
     * 除共键外逐字核收货四要素与区县码——冻结快照被改写而无人校验即成无痕换地址。
     */
    WsMallOrder requireLinkedOrder(WsMallFulfillment task) {
        WsMallOrder order = orderMapper.selectByOrderNoIncludingDeleted(task.getOrderNo());
        if (ObjectUtil.isNull(order) || !ObjectUtil.equal(order.getDataStatus(), 0)) {
            throw new JbkException("订单不存在或已删除");
        }
        boolean linked = ObjectUtil.equal(order.getId(), task.getOrderId())
                && ObjectUtil.equal(order.getOrderNo(), task.getOrderNo())
                && ObjectUtil.equal(order.getUserId(), task.getUserId())
                && ObjectUtil.equal(order.getWarehouseId(), task.getWarehouseId())
                && ObjectUtil.equal(order.getReceiverName(), task.getReceiverName())
                && ObjectUtil.equal(order.getReceiverPhone(), task.getReceiverPhone())
                && ObjectUtil.equal(order.getReceiverRegion(), task.getReceiverRegion())
                && ObjectUtil.equal(order.getReceiverAddress(), task.getReceiverAddress())
                && ObjectUtil.equal(order.getReceiverDistrictCode(), task.getReceiverDistrictCode());
        if (!linked) {
            throw new JbkException("履约任务与订单共键或收货快照不一致，请人工核查");
        }
        return order;
    }

    void requireFulfillingOrder(WsMallFulfillment task) {
        WsMallOrder order = requireLinkedOrder(task);
        if (!ObjectUtil.equal(order.getOrderStatus(), MallEnum.OrderStatus.FULFILLING.getValue())) {
            throw new JbkException("订单不处于履约中状态，动作已拒绝");
        }
    }

    WsMallFulfillment requireOwnCourierTask(Long courierUserId, String orderNo) {
        WsCourier courier = requireEnabledCourierByUser(courierUserId);
        WsMallFulfillment task = requireTask(orderNo);
        requireAssignedCourierEvidence(task);
        if (!ObjectUtil.equal(task.getCourierId(), courier.getId())) {
            // 非本人任务按不存在处理：收货地址、电话与签收证据一律不下发
            throw new JbkException("履约任务不存在");
        }
        return task;
    }

    /**
     * 配送归属结构化证据（唯一出处）：任务 COURIER_ID 必须与分配轨迹 SUBJECT_ID 共键。
     * COURIER_ID 是可变字段，只比当前值会让「改指向另一配送员」直接通过本人任务校验；
     * 证据缺失/删除/重复/错位一律拒绝，不降级也不补轨迹把证据「修好」。
     */
    void requireAssignedCourierEvidence(WsMallFulfillment task) {
        int status = task.getFulfillStatus() == null ? 0 : task.getFulfillStatus();
        boolean assignedStage = status >= MallEnum.FulfillStatus.PENDING_FETCH.getValue();
        if (!assignedStage) {
            if (ObjectUtil.isNotNull(task.getCourierId())) {
                throw new JbkException("履约任务分配证据异常（未到分配阶段却已有配送员），请人工核查");
            }
            return;
        }
        if (ObjectUtil.isNull(task.getCourierId())) {
            throw new JbkException("履约任务分配证据异常（已达分配阶段却无配送员），请人工核查");
        }
        List<WsMallFulfillmentTrace> evidence = traceMapper.selectLiveByNode(
                task.getOrderNo(), MallEnum.FulfillStatus.PENDING_FETCH.getValue());
        if (evidence.size() != 1) {
            throw new JbkException("履约任务分配证据缺失或重复，请人工核查");
        }
        WsMallFulfillmentTrace assigned = evidence.get(0);
        if (!ObjectUtil.equal(assigned.getFulfillId(), task.getId())
                || !ObjectUtil.equal(assigned.getOrderNo(), task.getOrderNo())
                || !ObjectUtil.equal(assigned.getTraceNode(),
                        MallEnum.FulfillStatus.PENDING_FETCH.getValue())
                || ObjectUtil.isNull(assigned.getSubjectId())
                || !ObjectUtil.equal(assigned.getSubjectId(), task.getCourierId())) {
            throw new JbkException("履约任务分配证据与任务不一致，请人工核查");
        }
    }

    /**
     * 仓库归属：操作员必须绑定本单前置仓——缺这一条，任何已登录管理端账号都能操作别的仓的货。
     */
    void requireWarehouseOperator(Long operatorId, WsMallFulfillment task) {
        if (ObjectUtil.isNull(operatorId)
                || whOperatorMapper.countActiveScope(task.getWarehouseId(), operatorId) <= 0) {
            // 跨仓一律按不存在处理，不泄露别的仓有没有这一单
            throw new JbkException("履约任务不存在");
        }
    }

    WsCourier requireEnabledCourierByUser(Long courierUserId) {
        // 绑号闸：配送员要按订单地址上门。这条链不走 CourierAccess 只走本方法，
        // 只在 CourierAccess 挂闸会整条漏掉（四个锚点里最易漏的一处）
        phoneGate.requirePhoneBound(courierUserId, "商城配送履约");
        WsCourier courier = courierMapper.selectOne(Wrappers.lambdaQuery(WsCourier.class)
                .eq(WsCourier::getUserId, courierUserId)
                .eq(WsCourier::getDataStatus, 0)
                .last("LIMIT 1"));
        if (ObjectUtil.isNull(courier)) {
            throw new JbkException("配送员身份不存在");
        }
        if (!ObjectUtil.equal(courier.getCourierStatus(),
                UserEnum.CourierStatus.ENABLED.getValue())) {
            throw new JbkException("配送员未准入或已停用");
        }
        return courier;
    }

    /**
     * 分配资格：准入启用 + 未删除 + 覆盖本单前置仓 + 不是下单人本人；
     * 四条都在服务层重判，候选列表是给人看的不是授权。
     */
    WsCourier requireAssignableCourier(WsMallFulfillment task, Long courierId) {
        WsCourier courier = courierMapper.selectById(courierId);
        if (ObjectUtil.isNull(courier) || !ObjectUtil.equal(courier.getDataStatus(), 0)) {
            throw new JbkException("配送员不存在");
        }
        if (!ObjectUtil.equal(courier.getCourierStatus(),
                UserEnum.CourierStatus.ENABLED.getValue())) {
            throw new JbkException("配送员未准入或已停用，不能分配");
        }
        if (ObjectUtil.equal(courier.getUserId(), task.getUserId())) {
            throw new JbkException("不能把订单分配给下单本人配送");
        }
        if (scopeMapper.countActiveScope(task.getWarehouseId(), courier.getId()) <= 0) {
            throw new JbkException("该配送员不在本前置仓的服务范围内");
        }
        return courier;
    }

    MallFulfillVo toVoChecked(WsMallFulfillment task, boolean withTimeline) {
        WsMallOrder order = requireLinkedOrder(task);
        return toVo(task, order, withTimeline);
    }

    /**
     * 组装（order 必须是刚过共键校验的同一张订单）；换货补发标记只从 SOURCE_AFTER_SALE_ID
     * 推出，不按金额为零猜。
     */
    MallFulfillVo toVo(WsMallFulfillment task, WsMallOrder order, boolean withTimeline) {
        MallFulfillVo vo = new MallFulfillVo()
                .setExchangeReshipment(ObjectUtil.isNotNull(order.getSourceAfterSaleId()))
                .setOrderNo(task.getOrderNo())
                .setFulfillStatus(task.getFulfillStatus())
                .setFulfillStatusName(statusName(task.getFulfillStatus()))
                .setFulfillMode(task.getFulfillMode())
                .setFulfillModeName(modeName(task.getFulfillMode()))
                .setWarehouseId(task.getWarehouseId())
                .setCourierId(task.getCourierId())
                .setReceiverName(task.getReceiverName())
                .setReceiverPhone(PhoneMask.mask(task.getReceiverPhone()))
                .setReceiverRegion(task.getReceiverRegion())
                .setReceiverAddress(task.getReceiverAddress())
                .setPickTime(task.getPickTime())
                .setPackTime(task.getPackTime())
                .setAssignTime(task.getAssignTime())
                .setFetchTime(task.getFetchTime())
                .setArriveTime(task.getArriveTime())
                .setSignTime(task.getSignTime())
                .setSignMethod(task.getSignMethod())
                .setSignRemark(task.getSignRemark());
        WsMallWarehouse warehouse = warehouseMapper.selectById(task.getWarehouseId());
        if (ObjectUtil.isNotNull(warehouse)) {
            vo.setWarehouseName(warehouse.getWarehouseName());
        }
        if (ObjectUtil.isNotNull(task.getCourierId())) {
            WsCourier courier = courierMapper.selectById(task.getCourierId());
            if (ObjectUtil.isNotNull(courier)) {
                vo.setCourierName(courier.getCourierName())
                        .setCourierPhone(PhoneMask.mask(courier.getCourierPhone()));
            }
        }
        if (withTimeline) {
            List<MallFulfillTraceVo> timeline = new ArrayList<>();
            for (WsMallFulfillmentTrace trace : traceMapper.selectTimeline(task.getOrderNo())) {
                timeline.add(new MallFulfillTraceVo()
                        .setTraceNode(trace.getTraceNode())
                        .setTraceNodeName(statusName(trace.getTraceNode()))
                        .setActorType(trace.getActorType())
                        .setActorTypeName(actorName(trace.getActorType()))
                        .setTraceTime(trace.getTraceTime())
                        .setTraceText(trace.getTraceText()));
            }
            vo.setTimeline(timeline);
        }
        return vo;
    }

    static String statusName(Integer value) {
        for (MallEnum.FulfillStatus status : MallEnum.FulfillStatus.values()) {
            if (ObjectUtil.equal(status.getValue(), value)) {
                return status.getDesc();
            }
        }
        return null;
    }

    /** 渠道名：未知值返回 null 而不是编造中文，让三端一眼看出是数据异常而非新渠道。 */
    static String modeName(Integer value) {
        for (MallEnum.FulfillMode mode : MallEnum.FulfillMode.values()) {
            if (ObjectUtil.equal(mode.getValue(), value)) {
                return mode.getDesc();
            }
        }
        return null;
    }

    static String actorName(Integer value) {
        for (MallEnum.ActorType actor : MallEnum.ActorType.values()) {
            if (ObjectUtil.equal(actor.getValue(), value)) {
                return actor.getDesc();
            }
        }
        return null;
    }

    void advance(WsMallFulfillment task, MallEnum.FulfillStatus from,
                         MallEnum.FulfillStatus to, String timeColumn, Long operator, String now) {
        int moved = fulfillMapper.casStatus(task.getId(), from.getValue(), to.getValue(),
                task.getVersion(), timeColumn, operator, now);
        if (moved != 1) {
            throw new JbkException("履约状态已变化（当前不是" + from.getDesc() + "），请刷新后重试");
        }
        task.setFulfillStatus(to.getValue()).setVersion(task.getVersion() + 1);
    }

    /**
     * 写轨迹——撞键即证据冲突，整事务回滚：只在状态 CAS 命中后调用且命中者唯一，
     * 「合法的重复」不存在，键被占用只能是伪造轨迹或真轨迹被逻辑删除悬空。
     */
    void writeTrace(WsMallFulfillment task, MallEnum.FulfillStatus node,
                            MallEnum.ActorType actorType, Long actorId, String now, String text) {
        writeTrace(task, node, actorType, actorId, null, now, text);
    }

    void writeTrace(WsMallFulfillment task, MallEnum.FulfillStatus node,
                            MallEnum.ActorType actorType, Long actorId, Long subjectId,
                            String now, String text) {
        String key = TRACE_KEY_PREFIX + task.getOrderNo() + ":" + node.getValue();
        if (ObjectUtil.isNotNull(traceMapper.selectByKeyIncludingDeleted(key))) {
            throw new JbkException("履约轨迹证据冲突（节点已被占用），动作已中止，请人工核查");
        }
        WsMallFulfillmentTrace trace = new WsMallFulfillmentTrace()
                .setFulfillId(task.getId())
                .setOrderNo(task.getOrderNo())
                .setTraceNode(node.getValue())
                .setActorType(actorType.getValue())
                .setActorId(actorId == null ? SYSTEM_OPERATOR : actorId)
                .setSubjectId(subjectId)
                .setTraceTime(now)
                .setTraceText(text)
                .setBizIdempotencyKey(key);
        trace.setCreateTime(now);
        try {
            traceMapper.insert(trace);
        }
        catch (DuplicateKeyException race) {
            throw new JbkException("履约轨迹证据冲突（并发写入），动作已中止，请人工核查");
        }
    }

    void writeAudit(WsMallFulfillment task, MallEnum.FulfillStatus node,
                            OpsEnum.ActorPortal portal, Long actorId, String detail) {
        domainEventService.recordReliableOnceAs(portal,
                actorId == null ? SYSTEM_OPERATOR : actorId, OpsEnum.EventType.MALL,
                "MALLORDER:" + task.getOrderNo(),
                AUDIT_KEY_PREFIX + task.getOrderNo() + ":" + node.getValue(),
                node.getDesc(), detail);
    }

    /**
     * 物流事件节点审计（L1）：与履约节点审计共用可靠事件底座，键前缀分开；身份显式传入——
     * Worker 无会话上下文，按会话推断会记成上一次登录的人。
     */
    void recordLogisticsAudit(String bizKey, String eventKey, OpsEnum.ActorPortal portal,
                              Long actorId, String title, String detail) {
        domainEventService.recordReliableOnceAs(portal, actorId, OpsEnum.EventType.MALL,
                eventKey, bizKey, title, detail);
    }

}
