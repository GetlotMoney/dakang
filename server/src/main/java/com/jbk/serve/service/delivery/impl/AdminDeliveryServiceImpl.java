package com.jbk.serve.service.delivery.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jbk.serve.mapper.delivery.WsDeliveryAppealMapper;
import com.jbk.serve.mapper.delivery.WsDeliveryExceptionMapper;
import com.jbk.serve.mapper.delivery.WsDeliveryMediaMapper;
import com.jbk.serve.mapper.delivery.WsDeliveryTaskMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.mapper.user.WsCourierMapper;
import com.jbk.serve.service.delivery.IAdminDeliveryService;
import com.jbk.serve.service.delivery.IDeliveryAppealTxService;
import com.jbk.serve.service.message.IWsMessageService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.delivery.DeliveryEnum;
import com.jbk.tool.consts.message.MessageEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.delivery.bo.AdminDeliveryAppealBo;
import com.jbk.tool.data.delivery.bo.AdminDeliveryTaskBo;
import com.jbk.tool.data.delivery.bo.DeliveryAppealDecideBo;
import com.jbk.tool.data.delivery.po.WsDeliveryAppeal;
import com.jbk.tool.data.delivery.po.WsDeliveryException;
import com.jbk.tool.data.delivery.po.WsDeliveryMedia;
import com.jbk.tool.data.delivery.po.WsDeliveryTask;
import com.jbk.tool.data.delivery.vo.AdminAppealTraceVo;
import com.jbk.tool.data.delivery.vo.AdminCourierEvidenceVo;
import com.jbk.tool.data.delivery.vo.AdminDeliveryAppealEvidenceVo;
import com.jbk.tool.data.delivery.vo.AdminDeliveryAppealItemVo;
import com.jbk.tool.data.delivery.vo.AdminDeliveryExceptionVo;
import com.jbk.tool.data.delivery.vo.AdminDeliveryNotificationVo;
import com.jbk.tool.data.delivery.vo.AdminDeliveryOrderTraceVo;
import com.jbk.tool.data.delivery.vo.AdminDeliveryPaymentVo;
import com.jbk.tool.data.delivery.vo.AdminDeliveryTaskDetailVo;
import com.jbk.tool.data.delivery.vo.AdminDeliveryTaskItemVo;
import com.jbk.tool.data.delivery.vo.AdminDeliveryTimelineNodeVo;
import com.jbk.tool.data.delivery.vo.AdminDeliveryTraceVo;
import com.jbk.tool.data.delivery.vo.AdminMediaRefVo;
import com.jbk.tool.data.delivery.vo.AdminSignPhotoMetaVo;
import com.jbk.tool.data.message.po.WsMessage;
import com.jbk.tool.data.ops.po.WsDomainEvent;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsWalletFlow;
import com.jbk.tool.data.trade.vo.AdminAuditEventVo;
import com.jbk.tool.data.trade.vo.AdminOrderItemVo;
import com.jbk.tool.data.user.po.WsCourier;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import com.jbk.tool.utils.PhoneMask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PC 管理端配送域服务实现（E2E-03 包C）：把包A 落库结果投影成 PC 契约 Vo、把裁决透传给包A
 * 裁决事务；状态机/金额/幂等/窗口规则不在本层重复实现。追溯核验 fail-closed：
 * 履约链与资金链分层独立核验，任一不一致只呈现数据异常，不把断链数据拼成履约证据。
 *
 * @author dakang
 * @since 2026-07-24
 */
@Service
@RequiredArgsConstructor
public class AdminDeliveryServiceImpl implements IAdminDeliveryService {

    private final WsDeliveryTaskMapper taskMapper;
    private final WsDeliveryAppealMapper appealMapper;
    private final WsDeliveryExceptionMapper exceptionMapper;
    private final WsDeliveryMediaMapper mediaMapper;
    private final WsOrderMapper orderMapper;
    private final WsWalletFlowMapper walletFlowMapper;
    private final WsCourierMapper courierMapper;
    private final IWsMessageService messageService;
    private final IWsDomainEventService domainEventService;
    private final IDeliveryAppealTxService appealTxService;

    /** 订单类型(1340)：3=水配送。 */
    private static final int ORDER_TYPE_DELIVERY = 3;
    /** 支付方式(1346)：2=水卡余额（全余额）。 */
    private static final int PAY_WAY_CARD_BALANCE = 2;
    /** 支付方式(1346)：3=水卡水量（D-214 混合结算：水费扣水量、配送费扣余额）。 */
    private static final int PAY_WAY_CARD_ML = 3;
    /** 流水类型(1344)：7=配送扣减。 */
    private static final int FLOW_TYPE_DELIVERY_CONSUME = 7;
    /** 消息领域(1312)：3=配送。 */
    private static final int MSG_DOMAIN_DELIVERY = MessageEnum.MsgDomain.DELIVERY.getValue();

    // ==================== C1 管理端查询 ====================

    @Override
    public PageDataVo<AdminDeliveryTaskItemVo> pageTasks(AdminDeliveryTaskBo bo) {
        Page<AdminDeliveryTaskItemVo> page = new Page<>(bo.getCurrent(), bo.getSize());
        IPage<AdminDeliveryTaskItemVo> result = taskMapper.pageAdminTasks(page, bo);
        result.getRecords().forEach(this::decorateTaskItem);
        return PageDataVo.getPageData(result.getRecords(), result.getTotal());
    }

    @Override
    public AdminDeliveryTaskDetailVo taskDetail(Long taskId) {
        AdminDeliveryTaskItemVo item = taskMapper.selectAdminTaskById(taskId);
        if (ObjectUtil.isNull(item)) {
            throw new JbkException("配送任务不存在");
        }
        decorateTaskItem(item);
        WsDeliveryTask task = taskMapper.selectById(taskId);
        WsOrder order = ObjectUtil.isNull(task) ? null : orderMapper.selectById(task.getOrderId());
        return assembleTaskDetail(item, order, task);
    }

    /**
     * 申诉列表（D-215）：分页单位是案件（TASK_ID），聚合在 SQL 层完成（前端聚合会被分页边界
     * 切残案件）；本层只做脱敏与原因中文名映射。
     */
    @Override
    public PageDataVo<AdminDeliveryAppealItemVo> pageAppeals(AdminDeliveryAppealBo bo) {
        Page<AdminDeliveryAppealItemVo> page = new Page<>(bo.getCurrent(), bo.getSize());
        IPage<AdminDeliveryAppealItemVo> result = appealMapper.pageAdminAppeals(page, bo);
        result.getRecords().forEach(this::decorateAppealItem);
        return PageDataVo.getPageData(result.getRecords(), result.getTotal());
    }

    @Override
    public AdminDeliveryAppealEvidenceVo appealEvidence(Long appealId) {
        AdminDeliveryAppealItemVo item = appealMapper.selectAdminAppealById(appealId);
        if (ObjectUtil.isNull(item)) {
            throw new JbkException("申诉不存在");
        }
        decorateAppealItem(item);
        AdminDeliveryAppealEvidenceVo vo = new AdminDeliveryAppealEvidenceVo();
        vo.setAppeal(item);

        WsDeliveryAppeal appeal = appealMapper.selectById(appealId);
        WsDeliveryTask task = ObjectUtil.isNull(appeal) ? null : taskMapper.selectById(appeal.getTaskId());
        WsOrder order = ObjectUtil.isNull(appeal) ? null : orderMapper.selectById(appeal.getOrderId());
        // 共键核验与包A 裁决前置同口径：订单-任务关联 + 申诉三方一致；任一断链只给原因，
        // 不下发任务详情/媒体元数据/举证内容（申诉主体本身是被查记录，保留其自述字段）
        String linkReason = ObjectUtil.isNull(appeal) ? "申诉主体读取失败"
                : firstNonBlank(deliveryLinkMismatch(order, task), appealRowMismatch(order, task, appeal));
        if (StrUtil.isNotBlank(linkReason)) {
            vo.setLinkStatus("mismatch");
            vo.setLinkReason(linkReason);
            return vo;
        }
        vo.setLinkStatus("ok");
        // D-215：共键核验通过后才下发同任务全部申诉；每条同样过 decorateAppealItem，不走第二套投影
        List<AdminDeliveryAppealItemVo> history = appealMapper.selectAdminAppealsByTaskId(appeal.getTaskId());
        history.forEach(this::decorateAppealItem);
        vo.setAppealHistory(history);
        WsCourier courier = ObjectUtil.isNull(task.getCourierId()) ? null
                : courierMapper.selectById(task.getCourierId());
        Long courierUserId = ObjectUtil.isNull(courier) ? null : courier.getUserId();
        vo.setAppealPhotos(mediaRefs(parseStringArray(appeal.getAppealPhotos()), task.getId(),
                DeliveryEnum.MediaPurpose.APPEAL_EVIDENCE.getValue(), appeal.getUserId()));
        vo.setCourierEvidences(parseCourierEvidences(appeal.getCourierEvidences(), task.getId(), courierUserId));
        vo.setTask(taskDetail(task.getId()));
        return vo;
    }

    @Override
    public boolean decideAppeal(DeliveryAppealDecideBo bo, Long adminUserId) {
        if (ObjectUtil.isNull(adminUserId) || adminUserId <= 0) {
            throw new JbkException("后台会话身份非法");
        }
        // 全部裁决规则（三态白名单/并发唯一/共键栅栏/消息与审计）由包A 裁决事务收口，本层零复制
        return appealTxService.decideAppeal(bo, adminUserId, DateUtils.time());
    }

    // ==================== C2 订单追溯聚合 ====================

    @Override
    public AdminDeliveryOrderTraceVo buildOrderTrace(AdminOrderItemVo adminOrder) {
        AdminDeliveryOrderTraceVo result = new AdminDeliveryOrderTraceVo();
        // 用 PO 重读订单主体：共键核验必须建立在带逻辑删除语义的原始行上，
        // 与包A 各事务读取同一数据源，不拿列表投影当校验输入
        WsOrder order = orderMapper.selectById(adminOrder.getId());
        WsDeliveryTask task = ObjectUtil.isNull(order) ? null
                : taskMapper.selectOne(Wrappers.lambdaQuery(WsDeliveryTask.class)
                        .eq(WsDeliveryTask::getOrderId, order.getId()));
        List<WsDeliveryAppeal> appeals = ObjectUtil.isNull(order) ? List.of()
                : appealMapper.selectList(Wrappers.lambdaQuery(WsDeliveryAppeal.class)
                        .eq(WsDeliveryAppeal::getOrderId, order.getId())
                        .orderByAsc(WsDeliveryAppeal::getId));

        result.setDelivery(buildDeliveryBlock(adminOrder, order, task, appeals));
        result.setAppeals(buildAppealRows(order, task, appeals));
        result.setAuditEvents(buildAuditEvents(adminOrder.getOrderNo(),
                ObjectUtil.isNull(task) ? null : task.getTaskNo()));
        return result;
    }

    private AdminDeliveryTraceVo buildDeliveryBlock(AdminOrderItemVo adminOrder, WsOrder order,
                                                    WsDeliveryTask task, List<WsDeliveryAppeal> appeals) {
        AdminDeliveryTraceVo block = new AdminDeliveryTraceVo();
        if (ObjectUtil.isNotNull(task)) {
            block.setTaskId(task.getId());
            block.setTaskNo(task.getTaskNo());
            block.setTaskStatus(task.getTaskStatus());
        }
        if (ObjectUtil.isNull(order)) {
            block.setLinkStatus("mismatch");
            block.setLinkReason("订单主体不存在或已删除，配送证据无法核验");
            return block;
        }
        long pendingByTask = ObjectUtil.isNull(task) ? 0 : appeals.stream()
                .filter(a -> ObjectUtil.equal(a.getTaskId(), task.getId())
                        && ObjectUtil.equal(a.getAppealStatus(), DeliveryEnum.AppealStatus.PENDING.getValue()))
                .count();
        // 先共键后状态：状态矩阵以共键成立为前提（缺任务/缺订单时不进入状态核验）
        String reason = deliveryLinkMismatch(order, task);
        if (StrUtil.isBlank(reason)) {
            reason = deliveryStateMismatch(order, task, pendingByTask);
        }
        if (StrUtil.isNotBlank(reason)) {
            // fail-closed：履约链断裂只保留任务标识与原因，配送员/费用/时间线/三照/消息全部不下发
            block.setLinkStatus("mismatch");
            block.setLinkReason(reason);
            return block;
        }
        block.setLinkStatus("ok");
        WsCourier courier = ObjectUtil.isNull(task.getCourierId()) ? null
                : courierMapper.selectById(task.getCourierId());
        block.setUserName(adminOrder.getUserName());
        // 下单人原值在 AdminOrderServiceImpl.decorateActorAndOwner 已脱敏并清空，这里直接取脱敏结果，
        // 不再对原值列做第二次脱敏——脱敏点只留一个，避免两处规则随时间漂移。
        block.setUserMaskedPhone(adminOrder.getActorMaskedPhone());
        if (ObjectUtil.isNotNull(courier)) {
            block.setCourierName(courier.getCourierName());
            block.setCourierMaskedPhone(PhoneMask.mask(courier.getCourierPhone()));
        }
        block.setStationName(adminOrder.getStationName());
        block.setWaterTypeName(task.getWaterType());
        block.setContainerSpec(task.getContainerSpec());
        block.setDeliveryCount(task.getDeliveryCount());
        block.setActualDeliveryCount(task.getActualDeliveryCount());
        block.setPlanReturnCount(task.getPlanReturnCount());
        block.setActualReturnCount(task.getActualReturnCount());
        block.setWaterAmountFen(task.getWaterAmount());
        block.setDeliveryFeeFen(task.getDeliveryFee());
        block.setTotalAmountFen(sumFen(task.getWaterAmount(), task.getDeliveryFee()));
        // D-214：payWay=3 补下发水量抵扣（快照 waterMl），页面据此把 0 元水费如实呈现为水量抵扣
        block.setPayWay(order.getPayWay());
        if (ObjectUtil.equal(order.getPayWay(), PAY_WAY_CARD_ML)) {
            block.setDeductWaterMl(snapWaterMl(order));
        }
        block.setReceiveAddress(task.getReceiveAddress());
        block.setReceiveMaskedPhone(PhoneMask.mask(task.getReceivePhone()));
        block.setScheduledTime(blankToNull(task.getScheduledTime()));
        block.setAppealDeadline(blankToNull(task.getAppealDeadline()));
        block.setLocationStatus(task.getLocationStatus());
        block.setTimeline(buildTimeline(order, task,
                ObjectUtil.isNull(courier) ? null : courier.getCourierName()));
        block.setSignPhotos(signPhotoMetas(task,
                ObjectUtil.isNull(courier) ? null : courier.getUserId()));
        block.setPayment(buildPayment(order));
        block.setNotifications(listNotifications(order.getOrderNo(), appeals));
        return block;
    }

    /** 资金链核验（独立于履约链）：mismatch 只下发原因，不提供流水正向证据。 */
    private AdminDeliveryPaymentVo buildPayment(WsOrder order) {
        AdminDeliveryPaymentVo payment = new AdminDeliveryPaymentVo();
        String bizKey = "DELIVERY:" + order.getOrderNo();
        WsWalletFlow flow = walletFlowMapper.selectOne(Wrappers.lambdaQuery(WsWalletFlow.class)
                .eq(WsWalletFlow::getBizIdempotencyKey, bizKey));
        String reason = deliveryFlowMismatch(order, flow);
        if (StrUtil.isNotBlank(reason)) {
            payment.setFlowStatus("mismatch");
            payment.setFlowReason(reason);
            return payment;
        }
        return payment.setFlowStatus("ok")
                .setFlowId(flow.getId())
                .setBizKey(bizKey)
                .setAmountChangeFen(flow.getAmountChange())
                .setAmountAfterFen(flow.getAmountAfter())
                .setMlChange(flow.getMlChange())
                .setMlAfter(flow.getMlAfter())
                .setTime(flow.getCreateTime())
                .setRemark(flow.getFlowRemark());
    }

    private List<AdminAppealTraceVo> buildAppealRows(WsOrder order, WsDeliveryTask task,
                                                     List<WsDeliveryAppeal> appeals) {
        List<AdminAppealTraceVo> rows = new ArrayList<>(appeals.size());
        for (WsDeliveryAppeal appeal : appeals) {
            AdminAppealTraceVo row = new AdminAppealTraceVo().setAppealId(appeal.getId());
            String reason = firstNonBlank(deliveryLinkMismatch(order, task),
                    appealRowMismatch(order, task, appeal));
            if (StrUtil.isNotBlank(reason)) {
                // 逐行 fail-closed：断链申诉只留ID与原因，不把理由/裁决当有效内容展示
                rows.add(row.setLinkStatus("mismatch").setLinkReason(reason));
                continue;
            }
            rows.add(row.setLinkStatus("ok")
                    .setAppealStatus(appeal.getAppealStatus())
                    .setAppealReason(appeal.getAppealReason())
                    .setAppealReasonLabel(appealReasonLabel(appeal.getAppealReason()))
                    .setAppealDesc(appeal.getAppealDesc())
                    .setReceivedCount(appeal.getReceivedCount())
                    .setHandleTime(blankToNull(appeal.getHandleTime()))
                    .setHandleResult(blankToNull(appeal.getHandleResult()))
                    .setCreateTime(appeal.getCreateTime()));
        }
        return rows;
    }

    /**
     * 审计事件聚合（订单号+任务号双键）。审计是事实记录而非派生证据，共键异常时依然完整下发。
     */
    private List<AdminAuditEventVo> buildAuditEvents(String orderNo, String taskNo) {
        List<String> keys = new ArrayList<>();
        if (StrUtil.isNotBlank(orderNo)) {
            keys.add(orderNo);
        }
        if (StrUtil.isNotBlank(taskNo)) {
            keys.add(taskNo);
        }
        if (keys.isEmpty()) {
            return List.of();
        }
        List<WsDomainEvent> events = domainEventService.list(Wrappers.lambdaQuery(WsDomainEvent.class)
                .in(WsDomainEvent::getEventKey, keys)
                .orderByAsc(WsDomainEvent::getId));
        List<AdminAuditEventVo> rows = new ArrayList<>(events.size());
        for (WsDomainEvent event : events) {
            rows.add(new AdminAuditEventVo()
                    .setId(event.getId())
                    .setEventType(event.getEventType())
                    .setEventTypeLabel(eventTypeLabel(event.getEventType()))
                    .setEventKey(event.getEventKey())
                    .setActorLabel(actorLabel(event))
                    .setDetail(eventDetail(event.getEventPayload()))
                    .setTime(event.getCreateTime()));
        }
        return rows;
    }

    private List<AdminDeliveryNotificationVo> listNotifications(String orderNo, List<WsDeliveryAppeal> appeals) {
        List<String> appealIds = appeals.stream().map(a -> String.valueOf(a.getId())).toList();
        List<WsMessage> messages = messageService.list(Wrappers.lambdaQuery(WsMessage.class)
                .eq(WsMessage::getMsgDomain, MSG_DOMAIN_DELIVERY)
                .and(w -> {
                    w.nested(n -> n.eq(WsMessage::getObjectType, "order").eq(WsMessage::getObjectId, orderNo));
                    if (!appealIds.isEmpty()) {
                        w.or(n -> n.eq(WsMessage::getObjectType, "appeal").in(WsMessage::getObjectId, appealIds));
                    }
                })
                .orderByAsc(WsMessage::getId));
        List<AdminDeliveryNotificationVo> rows = new ArrayList<>(messages.size());
        for (WsMessage message : messages) {
            rows.add(new AdminDeliveryNotificationVo()
                    .setId(message.getId())
                    .setTitle(message.getMsgTitle())
                    .setContent(message.getMsgContent())
                    .setSendStatus(message.getSendStatus())
                    .setSendTime(message.getSendTime())
                    .setObjectType(message.getObjectType())
                    .setObjectId(message.getObjectId()));
        }
        return rows;
    }

    // ==================== 详情装配 ====================

    private AdminDeliveryTaskDetailVo assembleTaskDetail(AdminDeliveryTaskItemVo item,
                                                         WsOrder order, WsDeliveryTask task) {
        long pendingByTask = ObjectUtil.isNull(task) ? 0
                : appealMapper.selectCount(Wrappers.lambdaQuery(WsDeliveryAppeal.class)
                        .eq(WsDeliveryAppeal::getTaskId, task.getId())
                        .eq(WsDeliveryAppeal::getAppealStatus, DeliveryEnum.AppealStatus.PENDING.getValue()));
        // 先共键后状态：状态矩阵以共键成立为前提（缺任务/缺订单时不进入状态核验）
        String reason;
        if (ObjectUtil.isNull(task)) {
            reason = "配送任务主体读取失败";
        } else {
            reason = deliveryLinkMismatch(order, task);
            if (StrUtil.isBlank(reason)) {
                reason = deliveryStateMismatch(order, task, pendingByTask);
            }
        }
        if (StrUtil.isNotBlank(reason)) {
            // fail-closed：只保留任务/订单标识与状态，其余正向证据（配送员/费用/地址/时间线/三照）全部不下发
            AdminDeliveryTaskDetailVo broken = new AdminDeliveryTaskDetailVo();
            broken.setTaskId(item.getTaskId());
            broken.setTaskNo(item.getTaskNo());
            broken.setOrderId(item.getOrderId());
            broken.setOrderNo(item.getOrderNo());
            broken.setOrderStatus(item.getOrderStatus());
            broken.setTaskStatus(item.getTaskStatus());
            broken.setLinkStatus("mismatch");
            broken.setLinkReason(reason);
            return broken;
        }
        AdminDeliveryTaskDetailVo detail = new AdminDeliveryTaskDetailVo();
        BeanUtil.copyProperties(item, detail);
        detail.setLinkStatus("ok");
        WsCourier courier = ObjectUtil.isNull(task.getCourierId()) ? null
                : courierMapper.selectById(task.getCourierId());
        detail.setTimeline(buildTimeline(order, task, item.getCourierName()));
        detail.setSignPhotos(signPhotoMetas(task, ObjectUtil.isNull(courier) ? null : courier.getUserId()));
        detail.setExceptions(listExceptions(task.getId()));
        return detail;
    }

    private List<AdminDeliveryExceptionVo> listExceptions(Long taskId) {
        List<WsDeliveryException> records = exceptionMapper.selectList(
                Wrappers.lambdaQuery(WsDeliveryException.class)
                        .eq(WsDeliveryException::getTaskId, taskId)
                        .orderByAsc(WsDeliveryException::getId));
        List<AdminDeliveryExceptionVo> rows = new ArrayList<>(records.size());
        for (WsDeliveryException record : records) {
            Integer reason = record.getExceptionReason();
            rows.add(new AdminDeliveryExceptionVo()
                    .setExceptionId(record.getId())
                    .setReason(reason)
                    .setReasonLabel(exceptionReasonLabel(reason))
                    .setDescription(record.getExceptionDesc())
                    .setEvidenceRefs(mediaRefs(parseStringArray(record.getEvidenceRefs()), taskId,
                            DeliveryEnum.MediaPurpose.EXCEPTION_EVIDENCE.getValue(), null))
                    .setCreateTime(record.getCreateTime()));
        }
        return rows;
    }

    /** 履约时间线：节点时间全部取任务/订单落库值，done 由服务端判定，前端不推导。 */
    private List<AdminDeliveryTimelineNodeVo> buildTimeline(
            WsOrder order, WsDeliveryTask task, String courierName) {
        List<AdminDeliveryTimelineNodeVo> nodes = new ArrayList<>(5);
        nodes.add(timelineNode("created", "下单", order.getCreateTime(), null));
        nodes.add(timelineNode("accept", "接单", task.getAcceptTime(), blankToNull(courierName)));
        nodes.add(timelineNode("depart", "已离站", task.getDepartTime(), null));
        nodes.add(timelineNode("arrive", "已送达", task.getArriveTime(), null));
        String signDetail = ObjectUtil.isNull(task.getActualDeliveryCount()) ? null
                : "实际配送 " + task.getActualDeliveryCount() + " 桶 / 回收 "
                        + ObjectUtil.defaultIfNull(task.getActualReturnCount(), 0) + " 桶";
        nodes.add(timelineNode("sign", "三照签收", task.getSignTime(), signDetail));
        return nodes;
    }

    private AdminDeliveryTimelineNodeVo timelineNode(
            String node, String label, String time, String detail) {
        return new AdminDeliveryTimelineNodeVo()
                .setNode(node)
                .setNodeLabel(label)
                .setTime(blankToNull(time))
                .setDetail(detail)
                .setDone(StrUtil.isNotBlank(time));
    }

    // ==================== 媒体元数据 ====================

    /** 签收三照元数据：type/时间/GPS 来自签收事务 JSON，媒体核验来自 ws_delivery_media。 */
    private List<AdminSignPhotoMetaVo> signPhotoMetas(WsDeliveryTask task, Long courierUserId) {
        List<SignPhotoDraft> drafts = parseSignPhotos(task.getSignPhotos());
        if (ObjectUtil.isNull(drafts) || drafts.isEmpty()) {
            return List.of();
        }
        Map<String, WsDeliveryMedia> mediaByKey = loadMedia(
                drafts.stream().map(d -> d.mediaKey).toList());
        List<AdminSignPhotoMetaVo> photos = new ArrayList<>(drafts.size());
        for (SignPhotoDraft draft : drafts) {
            AdminSignPhotoMetaVo photo = new AdminSignPhotoMetaVo()
                    .setType(draft.type)
                    .setTypeLabel(signPhotoTypeLabel(draft.type))
                    .setTime(draft.time)
                    .setLatitude(draft.lat)
                    .setLongitude(draft.lng);
            fillMediaRef(photo, draft.mediaKey, mediaByKey.get(draft.mediaKey), task.getId(),
                    DeliveryEnum.MediaPurpose.SIGN_PHOTO.getValue(), courierUserId);
            photos.add(photo);
        }
        return photos;
    }

    private List<AdminMediaRefVo> mediaRefs(List<String> keys, Long taskId, int purpose, Long ownerUserId) {
        if (ObjectUtil.isNull(keys) || keys.isEmpty()) {
            return List.of();
        }
        Map<String, WsDeliveryMedia> mediaByKey = loadMedia(keys);
        List<AdminMediaRefVo> refs = new ArrayList<>(keys.size());
        for (String key : keys) {
            AdminMediaRefVo ref = new AdminMediaRefVo();
            fillMediaRef(ref, key, mediaByKey.get(key), taskId, purpose, ownerUserId);
            refs.add(ref);
        }
        return refs;
    }

    private Map<String, WsDeliveryMedia> loadMedia(List<String> keys) {
        Set<String> distinct = new HashSet<>(keys);
        if (distinct.isEmpty()) {
            return new HashMap<>();
        }
        List<WsDeliveryMedia> rows = mediaMapper.selectList(Wrappers.lambdaQuery(WsDeliveryMedia.class)
                .in(WsDeliveryMedia::getMediaKey, distinct));
        Map<String, WsDeliveryMedia> byKey = new HashMap<>();
        rows.forEach(row -> byKey.putIfAbsent(row.getMediaKey(), row));
        return byKey;
    }

    /**
     * 单个媒体引用核验（纯投影层校验，不产生副作用）：
     * 登记行缺失=missing；归属/用途/任务绑定不符=invalid；仅核验通过才下发正向元数据。
     */
    private void fillMediaRef(AdminMediaRefVo ref, String key, WsDeliveryMedia media,
                              Long taskId, int purpose, Long expectedOwnerUserId) {
        ref.setMediaKey(key);
        if (ObjectUtil.isNull(media)) {
            ref.setMediaStatus("missing");
            ref.setMediaReason("受控媒体登记记录缺失，引用键无法核验");
            return;
        }
        if (ObjectUtil.notEqual(media.getMediaPurpose(), purpose)) {
            ref.setMediaStatus("invalid");
            ref.setMediaReason("媒体用途与业务场景不符");
            return;
        }
        if (ObjectUtil.isNull(media.getBoundTaskId()) || ObjectUtil.notEqual(media.getBoundTaskId(), taskId)) {
            ref.setMediaStatus("invalid");
            ref.setMediaReason("媒体未绑定当前任务，存在跨任务复用风险");
            return;
        }
        if (ObjectUtil.isNotNull(expectedOwnerUserId)
                && ObjectUtil.notEqual(media.getOwnerUserId(), expectedOwnerUserId)) {
            ref.setMediaStatus("invalid");
            ref.setMediaReason("媒体登记人与举证人不一致");
            return;
        }
        ref.setMediaStatus("ok");
        ref.setMimeType(media.getMimeType());
        ref.setSizeBytes(media.getSizeBytes());
        ref.setUploadTime(media.getCreateTime());
    }

    // ==================== 共键/状态/资金 核验（纯函数，供单测直接驱动） ====================

    /**
     * 履约链共键核验（对齐包A DeliveryLinkGuard 语义 + 水站快照一致）。
     *
     * @return null 表示一致；否则返回不一致原因。
     */
    static String deliveryLinkMismatch(WsOrder order, WsDeliveryTask task) {
        if (ObjectUtil.isNull(task)) {
            return "配送订单未关联任何配送任务（一单一任务，履约证据缺失）";
        }
        if (ObjectUtil.isNull(order) || ObjectUtil.notEqual(order.getDataStatus(), 0)) {
            return "任务关联的订单不存在或已删除，不能作为履约证据";
        }
        Integer orderType = order.getOrderType();
        if (ObjectUtil.isNull(orderType) || orderType != ORDER_TYPE_DELIVERY) {
            return "任务关联订单类型（" + orderType + "）不是水配送订单";
        }
        if (ObjectUtil.isNull(task.getOrderId()) || !task.getOrderId().equals(order.getId())) {
            return "任务外键指向订单（" + task.getOrderId() + "）与当前订单（" + order.getId() + "）不一致";
        }
        if (ObjectUtil.isNull(order.getUserId()) || ObjectUtil.notEqual(order.getUserId(), task.getUserId())) {
            return "订单归属用户与任务收货用户不一致";
        }
        if (ObjectUtil.notEqual(order.getStationId(), task.getStationId())) {
            return "订单水站与任务水站快照不一致";
        }
        return null;
    }

    /**
     * 状态-时间矩阵核验：只接受当前配送状态机（1→2→3→4→5⇄7，1→6）能产生的组合；
     * 时间单调、状态必备时间、订单-任务耦合、总额恒等式、三照齐全、申诉锁步逐项核对，
     * 任一不满足即整块 mismatch。6已取消是 E2E-04 包A 起的合法终态，放宽仅限取消这一格。
     */
    static String deliveryStateMismatch(WsOrder order, WsDeliveryTask task, long pendingAppealCount) {
        Integer status = task.getTaskStatus();
        if (ObjectUtil.isNull(status)) {
            return "任务状态为空，无法核验履约阶段";
        }
        boolean known = status == 1 || status == 2 || status == 3 || status == 4
                || status == 5 || status == 6 || status == 7;
        if (!known) {
            return "任务状态（" + status + "）不属于当前配送状态机可产生的状态";
        }
        if (ObjectUtil.isNull(task.getVersion()) || task.getVersion() < 1) {
            return "任务乐观锁版本非法（VERSION=" + task.getVersion() + "）";
        }
        // 总额恒等式（规则2）：订单总额 = 水费 + 配送费，快照落库后不再重算
        Long water = task.getWaterAmount();
        Long fee = task.getDeliveryFee();
        Long amount = order.getOrderAmount();
        if (ObjectUtil.isNull(water) || water < 0 || ObjectUtil.isNull(fee) || fee < 0) {
            return "任务缺少水费/配送费快照或数值非法，无法核验计价";
        }
        if (ObjectUtil.isNull(amount) || amount <= 0 || amount != water + fee) {
            return "订单总额（" + amount + "）与水费+配送费快照（" + water + "+" + fee + "）不满足恒等式";
        }
        // 状态必备时间/字段：缺了对应节点时间就不可能处于该状态
        boolean hasAccept = StrUtil.isNotBlank(task.getAcceptTime());
        boolean hasDepart = StrUtil.isNotBlank(task.getDepartTime());
        boolean hasArrive = StrUtil.isNotBlank(task.getArriveTime());
        boolean hasSign = StrUtil.isNotBlank(task.getSignTime());
        boolean hasCourier = ObjectUtil.isNotNull(task.getCourierId());
        switch (status) {
            case 1:
                if (hasCourier || hasAccept || hasDepart || hasArrive || hasSign) {
                    return "待接单任务不得携带配送员或任何履约节点时间";
                }
                break;
            case 2:
                if (!hasCourier || !hasAccept || hasDepart || hasArrive || hasSign) {
                    return "已接单任务必须且仅具备配送员与接单时间";
                }
                break;
            case 3:
                if (!hasCourier || !hasAccept || !hasDepart || hasArrive || hasSign) {
                    return "配送中任务必须具备接单/离站时间且未产生送达/签收时间";
                }
                break;
            case 4:
                if (!hasCourier || !hasAccept || !hasDepart || !hasArrive || hasSign) {
                    return "已送达任务必须具备接单/离站/送达时间且未产生签收时间";
                }
                break;
            case 6:
                // 6已取消（E2E-04 包A）：取消只发生在 1待接单，履约维度必须与待接单完全一致——
                // 带履约痕迹的「取消」意味着履约中途改库，不能当合法取消放行
                if (hasCourier || hasAccept || hasDepart || hasArrive || hasSign) {
                    return "已取消任务不得携带配送员或任何履约节点时间";
                }
                if (StrUtil.isNotBlank(task.getSignPhotos())) {
                    return "已取消任务不得携带签收三照";
                }
                if (ObjectUtil.isNotNull(task.getActualDeliveryCount())
                        || ObjectUtil.isNotNull(task.getActualReturnCount())) {
                    return "已取消任务不得携带实际配送/回收数量";
                }
                break;
            default:
                // 5已签收 / 7申诉中：完整履约证据缺一不可
                if (!hasCourier || !hasAccept || !hasDepart || !hasArrive || !hasSign) {
                    return "已签收/申诉中任务必须具备完整履约节点时间";
                }
                if (StrUtil.isBlank(task.getAppealDeadline())) {
                    return "已签收任务缺少申诉截止时间";
                }
                Integer location = task.getLocationStatus();
                if (ObjectUtil.isNull(location) || (location != 1 && location != 2)) {
                    return "已签收任务定位记录状态非法（LOCATION_STATUS=" + location + "）";
                }
                if (ObjectUtil.isNull(task.getActualDeliveryCount()) || task.getActualDeliveryCount() < 0
                        || ObjectUtil.isNull(task.getActualReturnCount()) || task.getActualReturnCount() < 0) {
                    return "已签收任务缺少实际配送/回收数量";
                }
                String photoIssue = signPhotoSetMismatch(task.getSignPhotos());
                if (StrUtil.isNotBlank(photoIssue)) {
                    return photoIssue;
                }
                break;
        }
        // 时间单调（统一逻辑时钟）：下单 ≤ 接单 ≤ 离站 ≤ 送达 ≤ 签收
        String previous = order.getCreateTime();
        for (String current : new String[]{task.getAcceptTime(), task.getDepartTime(),
                task.getArriveTime(), task.getSignTime()}) {
            if (StrUtil.isBlank(current)) {
                break;
            }
            if (StrUtil.isNotBlank(previous) && current.compareTo(previous) < 0) {
                return "履约节点时间倒序（" + current + " 早于 " + previous + "），时间证据不成立";
            }
            previous = current;
        }
        // 订单-任务耦合三段：签收「任务签收↔订单完成」、取消「任务取消↔订单退款」同事务推进，
        // 脱钩即异常
        Integer orderStatus = order.getOrderStatus();
        boolean signedSide = status == 5 || status == 7;
        boolean cancelled = status == 6;
        int expectedOrderStatus = signedSide ? TradeEnum.OrderStatus.FINISHED.getValue()
                : cancelled ? TradeEnum.OrderStatus.REFUNDED.getValue()
                : TradeEnum.OrderStatus.PAID.getValue();
        if (!ObjectUtil.equal(orderStatus, expectedOrderStatus)) {
            return "订单状态（" + orderStatus + "）与任务状态（" + status + "）不属于当前配送状态机可产生的组合";
        }
        // 申诉锁步：申诉创建 5→7 与裁决 7→5 均与申诉状态同事务变更
        if (status == 7 && pendingAppealCount != 1) {
            return "申诉中任务必须恰有一条待处理申诉（当前 " + pendingAppealCount + " 条）";
        }
        if (status != 7 && pendingAppealCount != 0) {
            return "任务不在申诉中却存在待处理申诉，状态与申诉记录脱钩";
        }
        return null;
    }

    /** 签收三照集合核验：JSON 可解析、恰 3 张、类型覆盖 {1,2,3}、键与时间齐备。 */
    static String signPhotoSetMismatch(String signPhotosJson) {
        List<SignPhotoDraft> drafts = parseSignPhotos(signPhotosJson);
        if (ObjectUtil.isNull(drafts)) {
            return "签收三照数据损坏，无法作为签收证据";
        }
        if (drafts.size() != 3) {
            return "签收三照数量（" + drafts.size() + "）不足，门牌/水品/摆放缺一不可";
        }
        Set<Integer> types = new HashSet<>();
        for (SignPhotoDraft draft : drafts) {
            if (ObjectUtil.isNull(draft.type) || StrUtil.isBlank(draft.mediaKey)
                    || StrUtil.isBlank(draft.time) || !types.add(draft.type)) {
                return "签收三照存在类型重复、缺媒体键或缺时间，证据不完整";
            }
        }
        if (!types.containsAll(DeliveryEnum.SIGN_PHOTO_TYPES)) {
            return "签收三照类型未覆盖门牌/水品/摆放";
        }
        return null;
    }

    /**
     * 资金链核验（DELIVERY:orderNo 幂等键流水）：
     * 配送资金链两种形态（D-214）——payWay=2 全余额（水量变动必须为 0）；
     * payWay=3 混合结算（金额变动=-配送费即-订单总额，水量变动=-创单快照 waterMl）。
     * 其余支付方式一律按证据缺失呈现。
     */
    static String deliveryFlowMismatch(WsOrder order, WsWalletFlow flow) {
        Integer payWay = order.getPayWay();
        if (ObjectUtil.isNull(payWay) || (payWay != PAY_WAY_CARD_BALANCE && payWay != PAY_WAY_CARD_ML)) {
            return "当前配送资金链仅支持水卡余额/水量支付，该订单支付方式（" + payWay + "）无法核验扣款流水";
        }
        if (ObjectUtil.isNull(order.getCardId())) {
            return "配送订单缺少扣款水卡（CARD_ID 为空），扣款证据不完整";
        }
        if (ObjectUtil.isNull(flow)) {
            return "未找到业务幂等键 DELIVERY:" + order.getOrderNo() + " 的扣款流水，资金证据缺失";
        }
        if (ObjectUtil.isNull(flow.getOrderId()) || !flow.getOrderId().equals(order.getId())) {
            return "扣款流水归属订单（" + flow.getOrderId() + "）与当前订单（" + order.getId() + "）不一致";
        }
        if (ObjectUtil.notEqual(flow.getCardId(), order.getCardId())) {
            return "扣款流水水卡与订单扣款卡不一致";
        }
        if (ObjectUtil.notEqual(flow.getUserId(), order.getUserId())) {
            return "扣款流水用户与订单用户不一致";
        }
        if (ObjectUtil.isNull(flow.getFlowType()) || flow.getFlowType() != FLOW_TYPE_DELIVERY_CONSUME) {
            return "扣款流水类型（" + flow.getFlowType() + "）不是配送扣减";
        }
        Long amountChange = flow.getAmountChange();
        Long orderAmount = order.getOrderAmount();
        if (ObjectUtil.isNull(amountChange) || ObjectUtil.isNull(orderAmount)
                || amountChange != -orderAmount) {
            return "扣款流水金额（" + amountChange + "）与订单总额（" + orderAmount + "）不一致";
        }
        if (payWay == PAY_WAY_CARD_BALANCE) {
            if (ObjectUtil.isNotNull(flow.getMlChange()) && flow.getMlChange() != 0L) {
                return "余额支付的配送扣款不得变动水量（ML_CHANGE=" + flow.getMlChange() + "）";
            }
            return null;
        }
        // payWay=3：水量变动必须恰等于创单冻结快照的 -waterMl（快照即扣减依据，两者不一致即证据断裂）
        Long snapWaterMl = snapWaterMl(order);
        if (ObjectUtil.isNull(snapWaterMl) || snapWaterMl <= 0) {
            return "水量抵扣订单缺少创单快照 waterMl，无法核验水量扣减";
        }
        Long mlChange = flow.getMlChange();
        if (ObjectUtil.isNull(mlChange) || mlChange != -snapWaterMl) {
            return "扣款流水水量变动（" + mlChange + "）与快照抵扣水量（" + snapWaterMl + "）不一致";
        }
        return null;
    }

    /** 创单冻结快照的 waterMl（D-214）；快照缺失/损坏返回空，由调用方按证据缺失处理。 */
    static Long snapWaterMl(WsOrder order) {
        if (StrUtil.isBlank(order.getPackageSnap())) {
            return null;
        }
        try {
            return JSONUtil.parseObj(order.getPackageSnap()).getLong("waterMl");
        } catch (Exception malformed) {
            return null;
        }
    }

    /**
     * 申诉行共键核验：申诉的任务/用户与已核验的任务-订单一致，
     * 状态只接受当前申诉状态机产出（1待处理 / 2成立待补偿 / 3不成立驳回 / 5补送待执行；
     * 4撤销无产生路径），裁决态必须携带处理时间与结果，待处理不得预写处理字段。
     */
    static String appealRowMismatch(WsOrder order, WsDeliveryTask task, WsDeliveryAppeal appeal) {
        if (ObjectUtil.isNull(task)) {
            return "配送任务缺失，申诉关联无法核验";
        }
        if (ObjectUtil.isNull(order) || ObjectUtil.notEqual(order.getDataStatus(), 0)) {
            return "申诉关联订单不存在或已删除";
        }
        if (ObjectUtil.isNull(appeal.getTaskId()) || !appeal.getTaskId().equals(task.getId())) {
            return "申诉指向任务（" + appeal.getTaskId() + "）与本单任务（" + task.getId() + "）不一致";
        }
        if (ObjectUtil.isNull(appeal.getOrderId()) || !appeal.getOrderId().equals(order.getId())) {
            return "申诉指向订单与当前订单不一致";
        }
        if (ObjectUtil.notEqual(appeal.getUserId(), order.getUserId())) {
            return "申诉用户与订单归属用户不一致";
        }
        Integer status = appeal.getAppealStatus();
        boolean known = ObjectUtil.equal(status, DeliveryEnum.AppealStatus.PENDING.getValue())
                || ObjectUtil.equal(status, DeliveryEnum.AppealStatus.COMPENSATE_PENDING.getValue())
                || ObjectUtil.equal(status, DeliveryEnum.AppealStatus.REJECTED.getValue())
                || ObjectUtil.equal(status, DeliveryEnum.AppealStatus.RESEND_PENDING.getValue());
        if (!known) {
            return "申诉状态（" + status + "）不属于当前申诉状态机可产生的状态";
        }
        boolean decided = ObjectUtil.notEqual(status, DeliveryEnum.AppealStatus.PENDING.getValue());
        boolean hasHandle = StrUtil.isNotBlank(appeal.getHandleTime())
                && StrUtil.isNotBlank(appeal.getHandleResult())
                && ObjectUtil.isNotNull(appeal.getHandleBy());
        if (decided && !hasHandle) {
            return "裁决态申诉缺少处理人/处理时间/处理结果";
        }
        if (!decided && (StrUtil.isNotBlank(appeal.getHandleTime())
                || StrUtil.isNotBlank(appeal.getHandleResult())
                || ObjectUtil.isNotNull(appeal.getHandleBy()))) {
            return "待处理申诉不得预写处理人/处理时间/处理结果";
        }
        return null;
    }

    // ==================== 投影辅助 ====================

    /** 列表项装饰：三个手机号统一 PhoneMask 脱敏并清空原值；总额=水费+配送费（只求和不重算价目）。 */
    private void decorateTaskItem(AdminDeliveryTaskItemVo item) {
        item.setUserMaskedPhone(PhoneMask.mask(item.getUserPhoneRaw()));
        item.setUserPhoneRaw(null);
        item.setReceiveMaskedPhone(PhoneMask.mask(item.getReceivePhoneRaw()));
        item.setReceivePhoneRaw(null);
        item.setCourierMaskedPhone(ObjectUtil.isNull(item.getCourierId()) ? null
                : PhoneMask.mask(item.getCourierPhoneRaw()));
        item.setCourierPhoneRaw(null);
        item.setTotalAmountFen(sumFen(item.getWaterAmountFen(), item.getDeliveryFeeFen()));
    }

    private void decorateAppealItem(AdminDeliveryAppealItemVo item) {
        item.setUserMaskedPhone(PhoneMask.mask(item.getUserPhoneRaw()));
        item.setUserPhoneRaw(null);
        item.setAppealReasonLabel(appealReasonLabel(item.getAppealReason()));
    }

    /** 任一侧缺失即返回空，不用 0 冒充金额快照。 */
    static Long sumFen(Long waterAmountFen, Long deliveryFeeFen) {
        if (ObjectUtil.isNull(waterAmountFen) || ObjectUtil.isNull(deliveryFeeFen)) {
            return null;
        }
        return waterAmountFen + deliveryFeeFen;
    }

    /** 申诉原因码 → 中文名（与小程序 U09 文案同口径）；未登记码原样透出不猜测。 */
    static String appealReasonLabel(String reason) {
        if (StrUtil.isBlank(reason)) {
            return null;
        }
        switch (reason) {
            case "QUANTITY":
                return "数量不符";
            case "QUALITY":
                return "水质问题";
            case "DAMAGE":
                return "货品破损";
            case "PLACEMENT":
                return "摆放问题";
            case "OTHER":
                return "其他";
            default:
                return reason;
        }
    }

    private String exceptionReasonLabel(Integer reason) {
        if (ObjectUtil.isNull(reason)) {
            return null;
        }
        for (DeliveryEnum.ExceptionReason item : DeliveryEnum.ExceptionReason.values()) {
            if (item.getValue() == reason) {
                return item.getDesc();
            }
        }
        // 字典外的脏值原样透出数字，不猜测语义
        return String.valueOf(reason);
    }

    private String signPhotoTypeLabel(Integer type) {
        if (ObjectUtil.isNull(type)) {
            return null;
        }
        return type == 1 ? "门牌照" : type == 2 ? "水品照" : type == 3 ? "摆放照" : String.valueOf(type);
    }

    private String eventTypeLabel(Integer eventType) {
        if (ObjectUtil.isNotNull(eventType)) {
            for (OpsEnum.EventType item : OpsEnum.EventType.values()) {
                if (item.getValue() == eventType) {
                    return item.getDesc();
                }
            }
        }
        return "领域事件";
    }

    /** 操作者标签：优先事件落库的角色/姓名快照，缺失时回落端口名称。 */
    private String actorLabel(WsDomainEvent event) {
        if (StrUtil.isNotBlank(event.getActorRole())) {
            return event.getActorRole();
        }
        Integer portal = event.getActorPortal();
        if (ObjectUtil.isNotNull(portal)) {
            for (OpsEnum.ActorPortal item : OpsEnum.ActorPortal.values()) {
                if (item.getValue() == portal) {
                    return item.getDesc();
                }
            }
        }
        return "系统";
    }

    /** 事件快照 {old,new} → 「旧值 → 新值」描述；载荷损坏时原样透出，不丢审计信息。 */
    private String eventDetail(String payload) {
        if (StrUtil.isBlank(payload)) {
            return null;
        }
        try {
            JSONObject json = JSONUtil.parseObj(payload);
            String oldValue = json.getStr("old");
            String newValue = json.getStr("new");
            if (StrUtil.isBlank(oldValue)) {
                return newValue;
            }
            return oldValue + " → " + newValue;
        } catch (Exception malformed) {
            return payload;
        }
    }

    // ==================== JSON 解析 ====================

    /** 签收三照 JSON 草稿（签收事务落库形态：{type, mediaKey, time, lat, lng}）。 */
    static final class SignPhotoDraft {
        final Integer type;
        final String mediaKey;
        final String time;
        final Double lat;
        final Double lng;

        SignPhotoDraft(Integer type, String mediaKey, String time, Double lat, Double lng) {
            this.type = type;
            this.mediaKey = mediaKey;
            this.time = time;
            this.lat = lat;
            this.lng = lng;
        }
    }

    /** 解析失败返回 null（区别于空数组）：损坏 JSON 属证据破坏，由状态矩阵按 mismatch 呈现。 */
    static List<SignPhotoDraft> parseSignPhotos(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {
            JSONArray array = JSONUtil.parseArray(json);
            List<SignPhotoDraft> drafts = new ArrayList<>(array.size());
            for (Object item : array) {
                JSONObject obj = (JSONObject) item;
                drafts.add(new SignPhotoDraft(obj.getInt("type"), obj.getStr("mediaKey"),
                        obj.getStr("time"), obj.getDouble("lat"), obj.getDouble("lng")));
            }
            return drafts;
        } catch (Exception malformed) {
            return null;
        }
    }

    private List<String> parseStringArray(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {
            return JSONUtil.parseArray(json).toList(String.class);
        } catch (Exception malformed) {
            return List.of();
        }
    }

    /** 配送员举证 JSON → Vo；损坏时返回空列表（举证是附加证据，不因损坏拦断申诉主体展示）。 */
    private List<AdminCourierEvidenceVo> parseCourierEvidences(String json, Long taskId, Long courierUserId) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {
            JSONArray array = JSONUtil.parseArray(json);
            List<AdminCourierEvidenceVo> evidences = new ArrayList<>(array.size());
            for (Object item : array) {
                JSONObject obj = (JSONObject) item;
                List<String> refs = ObjectUtil.isNull(obj.getJSONArray("evidenceRefs"))
                        ? List.of()
                        : obj.getJSONArray("evidenceRefs").toList(String.class);
                evidences.add(new AdminCourierEvidenceVo()
                        .setDescription(obj.getStr("description"))
                        .setTime(obj.getStr("time"))
                        .setEvidenceRefs(mediaRefs(refs, taskId,
                                DeliveryEnum.MediaPurpose.APPEAL_EVIDENCE.getValue(), courierUserId)));
            }
            return evidences;
        } catch (Exception malformed) {
            return List.of();
        }
    }

    private static String firstNonBlank(String first, String second) {
        return StrUtil.isNotBlank(first) ? first : second;
    }

    private static String blankToNull(String value) {
        return StrUtil.isBlank(value) ? null : value;
    }
}
