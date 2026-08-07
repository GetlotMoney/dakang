package com.jbk.serve.service.ops.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jbk.serve.mapper.api.ApiEmployeeMapper;
import com.jbk.serve.mapper.device.WsDeviceMapper;
import com.jbk.serve.mapper.ops.WsWorkOrderMapper;
import com.jbk.serve.mapper.station.WsStationMapper;
import com.jbk.serve.service.delivery.IDeliveryMediaService;
import com.jbk.serve.service.message.IWsMessageService;
import com.jbk.serve.service.message.MessageTemplates;
import com.jbk.serve.service.ops.IWorkOrderService;
import com.jbk.serve.service.ops.IWsAlarmService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.serve.service.ops.WorkOrderTransitions;
import com.jbk.tool.consts.ApiEnum;
import com.jbk.tool.consts.delivery.DeliveryEnum;
import com.jbk.tool.consts.message.MessageEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.api.po.ApiEmployee;
import com.jbk.tool.data.device.po.WsDevice;
import com.jbk.tool.data.ops.bo.WorkOrderApplyBo;
import com.jbk.tool.data.ops.bo.WsWorkOrderBo;
import com.jbk.tool.data.ops.po.WsAlarm;
import com.jbk.tool.data.ops.po.WsDomainEvent;
import com.jbk.tool.data.ops.po.WsWorkOrder;
import com.jbk.tool.data.ops.vo.WorkOrderTraceVo;
import com.jbk.tool.data.ops.vo.WsWorkOrderVo;
import com.jbk.tool.data.station.po.WsStation;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import com.jbk.tool.utils.OptionalUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 统一工单领域服务实现（E2E-05 包C）。
 *
 * <h3>迁移内核</h3>
 * <p>{@link #transit} 是唯一落库路径：先经 {@link WorkOrderTransitions} 校验动作合法性
 * （fail-closed），再以「前态 + VERSION」双条件 CAS 落库——两个管理员同时点分配，
 * 输方影响行数为 0，收到明确拒绝而不是静默覆盖。审计与业务同事务
 * （recordReliableOnceAs），业务回滚审计一并消失。</p>
 *
 * <h3>幂等边界</h3>
 * <p>建单幂等靠唯一键（uk_wo_alarm / uk_wo_request）撞键读回；动作幂等靠前态 CAS——
 * 重复确认第二次会因前态不符被拒绝（明确拒绝，任务书包C：重复动作必须幂等或明确拒绝）。</p>
 *
 * @author dakang
 * @since 2026-07-30
 */
@Slf4j
@Service
public class WorkOrderServiceImpl extends ServiceImpl<WsWorkOrderMapper, WsWorkOrder> implements IWorkOrderService {

    @Autowired
    private WsDeviceMapper deviceMapper;
    @Autowired
    private WsStationMapper stationMapper;
    @Autowired
    private ApiEmployeeMapper employeeMapper;
    @Autowired
    private IWsAlarmService alarmService;
    @Autowired
    private IWsDomainEventService domainEventService;
    @Autowired
    private IDeliveryMediaService mediaService;
    @Autowired
    private IWsMessageService messageService;

    /** 员工门户（1364）：媒体登记身份域，工单处理证据由员工登记 */
    private static final int PORTAL_MANAGE = 1;
    /** 用户门户（1364）：机主申报证据由机主（小程序用户）登记 */
    private static final int PORTAL_USER = 2;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createFromAlarm(Long alarmId, Long operatorId) {
        WsAlarm alarm = alarmService.getById(alarmId);
        OptionalUtils.nullToElseThrow(alarm, "告警不存在");
        if (ObjectUtil.notEqual(alarm.getAlarmStatus(), OpsEnum.AlarmStatus.PENDING.getValue())) {
            // 已转过单的告警直接回既有工单（幂等）；忽略/已恢复的告警不再受理转单
            if (ObjectUtil.equal(alarm.getAlarmStatus(), OpsEnum.AlarmStatus.TO_WORK_ORDER.getValue())
                    && ObjectUtil.isNotNull(alarm.getWorkOrderId())) {
                return alarm.getWorkOrderId();
            }
            throw new JbkException("告警当前状态不支持转工单");
        }
        WsWorkOrder order = new WsWorkOrder()
                .setOrderNo(generateOrderNo())
                .setWorkType(OpsEnum.WorkOrderType.REPAIR.getValue())
                .setDeviceId(alarm.getDeviceId())
                .setSourceType(OpsEnum.WorkOrderSource.FROM_ALARM.getValue())
                .setAlarmId(alarmId)
                .setOrderTitle(StrUtil.brief("告警转单：" + alarm.getAlarmContent(), 100))
                .setOrderContent(alarm.getAlarmContent())
                .setOrderStatus(WorkOrderTransitions.entryStatus(
                        OpsEnum.WorkOrderSource.FROM_ALARM.getValue()).getValue())
                .setVersion(1);
        try {
            save(order);
        } catch (DuplicateKeyException e) {
            // uk_wo_alarm：并发转单输方读回赢方工单（一个告警最多一个工单，任务书 3.3）
            WsWorkOrder existing = getOne(Wrappers.lambdaQuery(WsWorkOrder.class)
                    .eq(WsWorkOrder::getAlarmId, alarmId));
            OptionalUtils.nullToElseThrow(existing, "工单创建冲突，请重试");
            return existing.getId();
        }
        // 回链告警（1→2已转工单，活动键保留到告警恢复或工单关闭）；回链失败=告警已被并发处置，
        // 整体回滚让调用方重试——不允许「工单已建、告警仍待处理」的半状态
        if (!alarmService.linkWorkOrder(alarmId, order.getId(), operatorId, DateUtils.time())) {
            throw new JbkException("告警已被并发处置，转单终止");
        }
        auditTransit(OpsEnum.ActorPortal.MANAGE, operatorId, order.getOrderNo(), "CREATE_FROM_ALARM",
                null, "告警" + alarmId + "转单，入口" + statusDesc(order.getOrderStatus()));
        return order.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createInspection(WsWorkOrderBo bo, Long operatorId) {
        OpsEnum.WorkOrderType type = OpsEnum.WorkOrderType.getType(bo.getWorkType());
        if (ObjectUtil.isNotNull(bo.getDeviceId())) {
            WsDevice device = deviceMapper.selectById(bo.getDeviceId());
            OptionalUtils.nullToElseThrow(device, "目标设备不存在");
        } else if (type == OpsEnum.WorkOrderType.INSPECTION) {
            throw new JbkException("巡检工单必须指定设备");
        }
        WsWorkOrder order = new WsWorkOrder()
                .setOrderNo(generateOrderNo())
                .setWorkType(type.getValue())
                .setDeviceId(bo.getDeviceId())
                .setSourceType(OpsEnum.WorkOrderSource.CONSOLE.getValue())
                .setOrderTitle(bo.getOrderTitle())
                .setOrderContent(bo.getOrderContent())
                .setOrderStatus(WorkOrderTransitions.entryStatus(
                        OpsEnum.WorkOrderSource.CONSOLE.getValue()).getValue())
                .setVersion(1);
        save(order);
        auditTransit(OpsEnum.ActorPortal.MANAGE, operatorId, order.getOrderNo(), "CREATE_CONSOLE",
                null, type.getDesc() + "工单创建，入口" + statusDesc(order.getOrderStatus()));
        return order.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long ownerApply(WorkOrderApplyBo bo, Long userId) {
        if (ObjectUtil.notEqual(bo.getWorkType(), OpsEnum.WorkOrderType.REPAIR.getValue())
                && ObjectUtil.notEqual(bo.getWorkType(), OpsEnum.WorkOrderType.PARTS.getValue())) {
            throw new JbkException("机主只能申报维修或配件");
        }
        // 归属强校验：设备必须在本人名下（OWNER_USER_ID），不信前端任何归属声明（铁律6）
        WsDevice device = deviceMapper.selectById(bo.getDeviceId());
        if (ObjectUtil.isNull(device) || ObjectUtil.notEqual(device.getOwnerUserId(), userId)) {
            throw new JbkException("设备不存在或不在您的名下");
        }
        WsWorkOrder order = new WsWorkOrder()
                .setOrderNo(generateOrderNo())
                .setWorkType(bo.getWorkType())
                .setDeviceId(device.getId())
                .setSourceType(OpsEnum.WorkOrderSource.OWNER_APPLY.getValue())
                .setApplicantUserId(userId)
                .setRequestId(bo.getRequestId())
                .setOrderTitle(StrUtil.brief(OpsEnum.WorkOrderType.getType(bo.getWorkType()).getDesc()
                        + "申报：" + device.getDeviceNo(), 100))
                .setOrderContent(bo.getOrderContent())
                .setOrderPhotos(CollUtil.isEmpty(bo.getOrderPhotos()) ? null
                        : JSONUtil.toJsonStr(bo.getOrderPhotos()))
                .setOrderStatus(WorkOrderTransitions.entryStatus(
                        OpsEnum.WorkOrderSource.OWNER_APPLY.getValue()).getValue())
                .setVersion(1);
        try {
            save(order);
        } catch (DuplicateKeyException e) {
            // uk_wo_request：只有同一申报的网络重试可以返回原工单。除身份外必须核验全部
            // 业务参数；否则复用 requestId 改设备、类型或内容会被静默解释成旧请求。
            WsWorkOrder existing = getOne(Wrappers.lambdaQuery(WsWorkOrder.class)
                    .eq(WsWorkOrder::getRequestId, bo.getRequestId()));
            if (ObjectUtil.isNull(existing) || ObjectUtil.notEqual(existing.getApplicantUserId(), userId)) {
                throw new JbkException("申报请求标识冲突，请重新发起");
            }
            String requestedPhotos = CollUtil.isEmpty(bo.getOrderPhotos())
                    ? null : JSONUtil.toJsonStr(bo.getOrderPhotos());
            if (ObjectUtil.notEqual(existing.getDeviceId(), bo.getDeviceId())
                    || ObjectUtil.notEqual(existing.getWorkType(), bo.getWorkType())
                    || !StrUtil.equals(existing.getOrderContent(), bo.getOrderContent())
                    || !StrUtil.equals(existing.getOrderPhotos(), requestedPhotos)) {
                throw new JbkException("同一 requestId 不可更换申报参数");
            }
            return existing.getId();
        }
        // 证据以机主身份原子绑定（未登记/非本人/用途不符/已被占用整体失败回滚）
        if (CollUtil.isNotEmpty(bo.getOrderPhotos())) {
            mediaService.claimForTaskAs(PORTAL_USER, bo.getOrderPhotos(), order.getId(), userId,
                    DeliveryEnum.MediaPurpose.WORK_ORDER, "申报证据校验未通过，请重新上传");
        }
        auditTransit(OpsEnum.ActorPortal.OWNER, userId, order.getOrderNo(), "OWNER_APPLY",
                null, "机主申报，入口" + statusDesc(order.getOrderStatus()));
        return order.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirm(Long id, Long operatorId) {
        // 动作时钟单次快照：消息 SEND_TIME 与本动作同源（E2E-03 规则13），不许各处独立取钟跨秒漂移
        String now = DateUtils.time();
        WsWorkOrder order = transit(id, WorkOrderTransitions.Action.CONFIRM, operatorId, null);
        notifyApplicant(order, MessageTemplates.workOrderConfirmed(order.getOrderNo(), order.getOrderTitle()), now);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, String reason, Long operatorId) {
        if (StrUtil.isBlank(reason)) {
            throw new JbkException("驳回必须填写原因");
        }
        String now = DateUtils.time();
        WsWorkOrder order = transit(id, WorkOrderTransitions.Action.REJECT, operatorId,
                o -> o.setRejectReason(reason));
        notifyApplicant(order, MessageTemplates.workOrderRejected(order.getOrderNo(), order.getOrderTitle(), reason), now);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assign(Long id, Long assigneeId, Long operatorId) {
        OptionalUtils.nullToElseThrow(assigneeId, "处理人不为空");
        ApiEmployee assignee = employeeMapper.selectById(assigneeId);
        if (ObjectUtil.isNull(assignee)
                || ObjectUtil.equal(assignee.getDisabledFlag(), ApiEnum.Flag.YES.value())) {
            throw new JbkException("处理人不是有效员工");
        }
        transit(id, WorkOrderTransitions.Action.ASSIGN, operatorId,
                order -> order.setAssigneeId(assigneeId).setAssignTime(DateUtils.time()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitResult(Long id, String result, List<String> resultPhotos, Long operatorId) {
        if (StrUtil.isBlank(result)) {
            throw new JbkException("处理结果不为空");
        }
        // 证据先绑定后迁移：claim 失败整体回滚，不会出现「已待复核但证据没绑上」
        if (CollUtil.isNotEmpty(resultPhotos)) {
            mediaService.claimForTaskAs(PORTAL_MANAGE, resultPhotos, id, operatorId,
                    DeliveryEnum.MediaPurpose.WORK_ORDER, "处理证据校验未通过，请重新上传");
        }
        transit(id, WorkOrderTransitions.Action.SUBMIT_RESULT, operatorId,
                order -> order.setFinishTime(DateUtils.time())
                        .setFinishResult(StrUtil.brief(result, 500))
                        .setResultPhotos(CollUtil.isEmpty(resultPhotos) ? null
                                : JSONUtil.toJsonStr(resultPhotos)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reviewPass(Long id, String remark, Long operatorId) {
        // 复核/关闭/消息三个时间必须同一次取钟：跨秒（尤其跨日）时按天统计触达与关闭才不会错位
        String now = DateUtils.time();
        WsWorkOrder order = transit(id, WorkOrderTransitions.Action.REVIEW_PASS, operatorId,
                o -> o.setReviewBy(operatorId).setReviewTime(now)
                        .setReviewRemark(StrUtil.blankToDefault(remark, null))
                        .setCloseTime(now));
        // 任务书 3.2：告警活动键保留到工单正式关闭。此刻释放——同型故障此后可再触发新告警。
        // 告警可能已自动恢复（键已清空），条件 UPDATE 天然幂等
        if (ObjectUtil.isNotNull(order.getAlarmId())) {
            alarmService.releaseActiveKey(order.getAlarmId());
        }
        notifyApplicant(order, MessageTemplates.workOrderClosed(order.getOrderNo(), order.getOrderTitle()), now);
    }

    /**
     * 申报人消息回执（E2E-07 包B，口径3/4）：只发给机主申报来源（APPLICANT_USER_ID 非空），
     * 告警转入/PC 巡检来源无申报人、静默跳过——告警侧受众是运维员工，触达面即 PC 告警中心。
     * 与迁移同事务（迁移回滚消息同灭）；sendTime 由动作方法单次取钟传入（时间同源）；
     * OBJECT_ID 优先申报凭据 REQUEST_ID（C03 按它回跳 O05 详情），
     * 兜底工单号（极端旧数据缺 REQUEST_ID 时消息仍可读，仅回跳降级为「不存在」提示）。
     */
    private void notifyApplicant(WsWorkOrder order, MessageTemplates.Payload payload, String sendTime) {
        if (ObjectUtil.isNull(order.getApplicantUserId())) {
            return;
        }
        messageService.sendInApp(order.getApplicantUserId(), MessageEnum.MsgDomain.OWNER,
                payload.title(), payload.content(), "service",
                StrUtil.blankToDefault(order.getRequestId(), order.getOrderNo()), sendTime);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reviewReturn(Long id, String remark, Long operatorId) {
        if (StrUtil.isBlank(remark)) {
            throw new JbkException("复核退回必须填写意见");
        }
        transit(id, WorkOrderTransitions.Action.REVIEW_RETURN, operatorId,
                order -> order.setReviewBy(operatorId).setReviewTime(DateUtils.time())
                        .setReviewRemark(StrUtil.brief(remark, 500)));
    }

    @Override
    public PageDataVo<WsWorkOrderVo> pageData(WsWorkOrderBo bo) {
        Page<WsWorkOrder> page = page(new Page<>(bo.getCurrent(), bo.getSize()), buildQuery(bo, null));
        return toPageVo(page);
    }

    @Override
    public WsWorkOrderVo getData(Long id) {
        WsWorkOrder order = getById(id);
        OptionalUtils.nullToElseThrow(order, "工单不存在");
        return buildDetail(order);
    }

    @Override
    public PageDataVo<WsWorkOrderVo> pageByApplicant(WsWorkOrderBo bo, Long userId) {
        Page<WsWorkOrder> page = page(new Page<>(bo.getCurrent(), bo.getSize()), buildQuery(bo, userId));
        return toPageVo(page);
    }

    @Override
    public WsWorkOrderVo getByApplicant(Long id, Long userId) {
        WsWorkOrder order = getById(id);
        // 非本人申报按不存在处理：不区分「没有这张工单」与「有但不是你的」（不泄露存在性）
        if (ObjectUtil.isNull(order) || ObjectUtil.notEqual(order.getApplicantUserId(), userId)) {
            throw new JbkException("工单不存在");
        }
        return buildDetail(order);
    }

    /**
     * 迁移内核（唯一落库路径）：状态机校验 → 前态+VERSION CAS → 审计同事务。
     * 返回迁移前读到的工单行（filler 已应用），供调用方做关联收尾。
     */
    private WsWorkOrder transit(Long id, WorkOrderTransitions.Action action, Long operatorId,
                                java.util.function.Consumer<WsWorkOrder> filler) {
        WsWorkOrder order = getById(id);
        OptionalUtils.nullToElseThrow(order, "工单不存在");
        int fromStatus = order.getOrderStatus();
        int fromVersion = order.getVersion();
        OpsEnum.WorkOrderStatus target = WorkOrderTransitions.target(action, fromStatus);
        order.setOrderStatus(target.getValue());
        order.setVersion(fromVersion + 1);
        if (filler != null) {
            filler.accept(order);
        }
        int affected = getBaseMapper().update(order, Wrappers.lambdaUpdate(WsWorkOrder.class)
                .eq(WsWorkOrder::getId, id)
                .eq(WsWorkOrder::getOrderStatus, fromStatus)
                .eq(WsWorkOrder::getVersion, fromVersion));
        if (affected != 1) {
            // 并发输方：明确拒绝（任务书包C），不静默覆盖
            throw new JbkException("工单已被并发处置，请刷新后重试");
        }
        // 幂等键掺 fromVersion：复核退回后二次提交结果是合法的重复动作（不同 occurrence），
        // 仅靠动作名会撞键 fail-closed；同一事务重试则同键幂等
        auditTransit(OpsEnum.ActorPortal.MANAGE, operatorId, order.getOrderNo(),
                action.name() + ":" + fromVersion,
                statusDesc(fromStatus), action.desc() + "→" + target.getDesc());
        return order;
    }

    /** 关键状态审计（同事务 + 业务幂等键，写失败抛出让业务回滚——fail-closed）。 */
    private void auditTransit(OpsEnum.ActorPortal portal, Long actorId, String orderNo,
                              String actionKey, Object oldValue, Object newValue) {
        domainEventService.recordReliableOnceAs(portal, actorId, OpsEnum.EventType.WORK_ORDER_STATUS,
                orderNo, StrUtil.brief("WO:" + orderNo + ":" + actionKey, 64), oldValue, newValue);
    }

    private com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WsWorkOrder> buildQuery(
            WsWorkOrderBo bo, Long applicantScope) {
        return Wrappers.lambdaQuery(WsWorkOrder.class)
                .eq(ObjectUtil.isNotNull(applicantScope), WsWorkOrder::getApplicantUserId, applicantScope)
                .like(StrUtil.isNotBlank(bo.getOrderNo()), WsWorkOrder::getOrderNo, bo.getOrderNo())
                .eq(ObjectUtil.isNotNull(bo.getOrderStatus()), WsWorkOrder::getOrderStatus, bo.getOrderStatus())
                .eq(ObjectUtil.isNotNull(bo.getFilterWorkType()), WsWorkOrder::getWorkType, bo.getFilterWorkType())
                .eq(ObjectUtil.isNotNull(bo.getSourceType()), WsWorkOrder::getSourceType, bo.getSourceType())
                .eq(ObjectUtil.isNotNull(bo.getFilterDeviceId()), WsWorkOrder::getDeviceId, bo.getFilterDeviceId())
                .eq(ObjectUtil.isNotNull(bo.getFilterAssigneeId()), WsWorkOrder::getAssigneeId, bo.getFilterAssigneeId())
                .orderByDesc(WsWorkOrder::getId);
    }

    private PageDataVo<WsWorkOrderVo> toPageVo(Page<WsWorkOrder> page) {
        List<WsWorkOrderVo> voList = page.getRecords().stream()
                .map(e -> BeanUtil.copyProperties(e, WsWorkOrderVo.class))
                .collect(Collectors.toList());
        fillRefs(voList);
        return PageDataVo.getPageData(voList, page.getTotal());
    }

    private WsWorkOrderVo buildDetail(WsWorkOrder order) {
        WsWorkOrderVo vo = BeanUtil.copyProperties(order, WsWorkOrderVo.class);
        fillRefs(CollUtil.newArrayList(vo));
        if (ObjectUtil.isNotNull(order.getAlarmId())) {
            WsAlarm alarm = alarmService.getById(order.getAlarmId());
            if (ObjectUtil.isNotNull(alarm)) {
                vo.setAlarmContent(alarm.getAlarmContent());
            }
        }
        // 完整状态轨迹：按工单号取领域事件正序（任务书包C：关闭后查询完整轨迹）
        List<WsDomainEvent> events = domainEventService.list(Wrappers.lambdaQuery(WsDomainEvent.class)
                .eq(WsDomainEvent::getEventType, OpsEnum.EventType.WORK_ORDER_STATUS.getValue())
                .eq(WsDomainEvent::getEventKey, order.getOrderNo())
                .orderByAsc(WsDomainEvent::getId));
        vo.setTrace(events.stream().map(e -> new WorkOrderTraceVo()
                .setEventTime(e.getCreateTime())
                .setActorPortal(e.getActorPortal())
                .setActorId(e.getActorId())
                .setPayload(e.getEventPayload())).collect(Collectors.toList()));
        return vo;
    }

    /** 联查设备编号/水站名/处理人名（列表与详情共用；缺档不阻断查询，字段留空） */
    private void fillRefs(List<WsWorkOrderVo> voList) {
        if (CollUtil.isEmpty(voList)) {
            return;
        }
        var deviceIds = voList.stream().map(WsWorkOrderVo::getDeviceId)
                .filter(ObjectUtil::isNotNull).collect(Collectors.toSet());
        var deviceById = deviceIds.isEmpty() ? java.util.Map.<Long, WsDevice>of()
                : deviceMapper.selectBatchIds(deviceIds).stream()
                        .collect(Collectors.toMap(WsDevice::getId, java.util.function.Function.identity()));
        var stationIds = deviceById.values().stream().map(WsDevice::getStationId)
                .filter(ObjectUtil::isNotNull).collect(Collectors.toSet());
        var stationById = stationIds.isEmpty() ? java.util.Map.<Long, WsStation>of()
                : stationMapper.selectBatchIds(stationIds).stream()
                        .collect(Collectors.toMap(WsStation::getId, java.util.function.Function.identity()));
        var employeeIds = voList.stream().map(WsWorkOrderVo::getAssigneeId)
                .filter(ObjectUtil::isNotNull).collect(Collectors.toSet());
        var employeeById = employeeIds.isEmpty() ? java.util.Map.<Long, ApiEmployee>of()
                : employeeMapper.selectBatchIds(employeeIds).stream()
                        .collect(Collectors.toMap(ApiEmployee::getId, java.util.function.Function.identity()));
        // 联查 id 可空（非设备类工单无 DEVICE_ID、未分配工单无 ASSIGNEE_ID），且上面的空集分支返回
        // Map.of()——不可变映射 get(null) 直接抛 NPE。必须先判空再取，否则「列表里没有任何工单
        // 带处理人」时整页接口 500（自动化场景先分配后查询，恰好绕过这条路径）。
        voList.forEach(vo -> {
            WsDevice device = ObjectUtil.isNull(vo.getDeviceId()) ? null : deviceById.get(vo.getDeviceId());
            if (ObjectUtil.isNotNull(device)) {
                vo.setDeviceNo(device.getDeviceNo());
                WsStation station = ObjectUtil.isNull(device.getStationId())
                        ? null : stationById.get(device.getStationId());
                if (ObjectUtil.isNotNull(station)) {
                    vo.setStationName(station.getStationName());
                }
            }
            ApiEmployee assignee = ObjectUtil.isNull(vo.getAssigneeId())
                    ? null : employeeById.get(vo.getAssigneeId());
            if (ObjectUtil.isNotNull(assignee)) {
                vo.setAssigneeName(assignee.getEmployeeName());
            }
        });
    }

    private String statusDesc(int status) {
        return OpsEnum.WorkOrderStatus.getType(status).getDesc();
    }

    /** 工单号：WO + 时间戳 + 6 位随机数；唯一性由 uk_wo_no 约束保证。 */
    private String generateOrderNo() {
        return "WO" + DateUtils.time() + RandomUtil.randomNumbers(6);
    }
}
