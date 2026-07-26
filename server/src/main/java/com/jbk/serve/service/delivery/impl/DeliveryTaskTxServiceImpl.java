package com.jbk.serve.service.delivery.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.delivery.WsDeliveryExceptionMapper;
import com.jbk.serve.mapper.delivery.WsDeliveryTaskMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.service.delivery.DeliveryClock;
import com.jbk.serve.service.delivery.DeliveryLinkGuard;
import com.jbk.serve.service.delivery.DeliveryPricing;
import com.jbk.serve.service.delivery.DeliveryTransitions;
import com.jbk.serve.service.delivery.IDeliveryMediaService;
import com.jbk.serve.service.delivery.IDeliveryTaskTxService;
import com.jbk.serve.service.message.IWsMessageService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.delivery.DeliveryEnum;
import com.jbk.tool.consts.message.MessageEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.data.delivery.bo.DeliveryExceptionReportBo;
import com.jbk.tool.data.delivery.bo.DeliverySignBo;
import com.jbk.tool.data.delivery.po.WsDeliveryException;
import com.jbk.tool.data.delivery.po.WsDeliveryTask;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.exception.JbkException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 配送任务状态机实现（E2E-03 A3）。
 *
 * <p>并发唯一与非法跳转都由「条件 UPDATE 影响行数」裁决：所有前置校验只决定拒因文案，
 * 真正的安全边界是 WHERE 里的 状态+版本+归属 条件——预检不是安全边界（取水域同款设计）。
 * 拒绝路径零副作用由「校验全部通过之后才产生任何写入 + 整体事务回滚」保证。</p>
 *
 * @author dakang
 * @since 2026-07-23
 */
@Service
public class DeliveryTaskTxServiceImpl implements IDeliveryTaskTxService {

    @Autowired
    private WsDeliveryTaskMapper taskMapper;
    @Autowired
    private WsOrderMapper orderMapper;
    @Autowired
    private WsDeliveryExceptionMapper exceptionMapper;
    @Autowired
    private CourierAccess courierAccess;
    @Autowired
    private IDeliveryMediaService mediaService;
    @Autowired
    private IWsMessageService messageService;
    @Autowired
    private IWsDomainEventService domainEventService;

    @Override
    public List<WsDeliveryTask> listAvailableTasks(Long actorUserId, String now) {
        CourierAccess.EnabledCourier courier = courierAccess.requireEnabledCourier(actorUserId);
        DeliveryClock.requireTime(now, "当前时间");
        // 范围/自配送/订单可履约全部压在 SQL（与接单校验同源，见 Mapper XML 注释）
        return taskMapper.selectAvailableTasks(actorUserId, new ArrayList<>(courier.stationIds()), now);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WsDeliveryTask acceptTask(String taskNo, Integer expectedVersion, Long actorUserId, String now) {
        CourierAccess.EnabledCourier courier = courierAccess.requireEnabledCourier(actorUserId);
        DeliveryClock.requireTime(now, "当前时间");
        WsDeliveryTask task = requireTask(taskNo);
        requireVersion(task, expectedVersion);
        if (!courier.allowsStation(task.getStationId())) {
            throw new JbkException("任务不在当前配送范围");
        }
        // 铁律7：接单动作再次强制校验自配送（不依赖列表过滤）
        if (ObjectUtil.equal(task.getUserId(), actorUserId)) {
            throw new JbkException("同一账号不能配送自己的订单");
        }
        if (ObjectUtil.notEqual(task.getTaskStatus(), DeliveryEnum.TaskStatus.PENDING.getValue())
                || ObjectUtil.isNotNull(task.getCourierId())) {
            throw new JbkException("任务已被领取或状态不可接单");
        }
        // 规则19：预约单到点才可接（查询时间语义，不建调度器）
        if (StrUtil.isNotBlank(task.getScheduledTime()) && task.getScheduledTime().compareTo(now) > 0) {
            throw new JbkException("预约配送时间未到，暂不可接单");
        }
        WsOrder order = DeliveryLinkGuard.requireFulfillable(orderMapper.selectById(task.getOrderId()), task);

        String acceptTime = DeliveryClock.floor(now, order.getCreateTime());
        // 规则9：CAS 并发唯一——状态/无人认领/版本/非本人下单全部压进 WHERE，恰一赢家
        int affected = taskMapper.update(null, Wrappers.lambdaUpdate(WsDeliveryTask.class)
                .set(WsDeliveryTask::getCourierId, courier.courier().getId())
                .set(WsDeliveryTask::getTaskStatus, DeliveryEnum.TaskStatus.ACCEPTED.getValue())
                .set(WsDeliveryTask::getVersion, expectedVersion + 1)
                .set(WsDeliveryTask::getAcceptTime, acceptTime)
                .set(WsDeliveryTask::getUpdateTime, acceptTime)
                .eq(WsDeliveryTask::getId, task.getId())
                .eq(WsDeliveryTask::getTaskStatus, DeliveryEnum.TaskStatus.PENDING.getValue())
                .eq(WsDeliveryTask::getVersion, expectedVersion)
                .isNull(WsDeliveryTask::getCourierId)
                .ne(WsDeliveryTask::getUserId, actorUserId));
        if (affected != 1) {
            throw new JbkException("任务已被领取或状态不可接单");
        }
        messageService.sendInApp(task.getUserId(), MessageEnum.MsgDomain.DELIVERY, "配送员已接单",
                "订单 " + order.getOrderNo() + " 已由配送员接单，备货后将从水站出发。",
                "order", order.getOrderNo(), acceptTime);
        recordNode(task.getTaskNo(), order.getOrderNo(), "accept", acceptTime, actorUserId,
                DeliveryEnum.TaskStatus.PENDING, DeliveryEnum.TaskStatus.ACCEPTED);
        return reload(task.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WsDeliveryTask advanceTask(String taskNo, int targetStatus, Integer expectedVersion,
                                      Long actorUserId, String now) {
        CourierAccess.EnabledCourier courier = courierAccess.requireEnabledCourier(actorUserId);
        DeliveryClock.requireTime(now, "当前时间");
        int expectedCurrent = DeliveryTransitions.requireAdvanceSource(targetStatus);
        WsDeliveryTask task = requireTask(taskNo);
        requireVersion(task, expectedVersion);
        if (ObjectUtil.notEqual(task.getCourierId(), courier.courier().getId())) {
            throw new JbkException("只能推进本人已接任务");
        }
        if (ObjectUtil.notEqual(task.getTaskStatus(), expectedCurrent)) {
            throw new JbkException("配送任务状态不允许该操作");
        }
        WsOrder order = DeliveryLinkGuard.requireFulfillable(orderMapper.selectById(task.getOrderId()), task);

        boolean depart = targetStatus == DeliveryEnum.TaskStatus.DELIVERING.getValue();
        String actionTime = DeliveryClock.floor(now, depart ? task.getAcceptTime() : task.getDepartTime());
        int affected = taskMapper.update(null, Wrappers.lambdaUpdate(WsDeliveryTask.class)
                .set(WsDeliveryTask::getTaskStatus, targetStatus)
                .set(WsDeliveryTask::getVersion, expectedVersion + 1)
                .set(depart, WsDeliveryTask::getDepartTime, actionTime)
                .set(!depart, WsDeliveryTask::getArriveTime, actionTime)
                .set(WsDeliveryTask::getUpdateTime, actionTime)
                .eq(WsDeliveryTask::getId, task.getId())
                .eq(WsDeliveryTask::getTaskStatus, expectedCurrent)
                .eq(WsDeliveryTask::getVersion, expectedVersion)
                .eq(WsDeliveryTask::getCourierId, courier.courier().getId()));
        if (affected != 1) {
            throw new JbkException("任务状态已变化，请刷新后重试");
        }
        if (depart) {
            messageService.sendInApp(task.getUserId(), MessageEnum.MsgDomain.DELIVERY, "水已离开水站",
                    "订单 " + order.getOrderNo() + " 已由配送员取水离站，正在配送途中。",
                    "order", order.getOrderNo(), actionTime);
        } else {
            messageService.sendInApp(task.getUserId(), MessageEnum.MsgDomain.DELIVERY, "已送达，等待签收确认",
                    "订单 " + order.getOrderNo() + " 已送达收货地址，等待三照签收。",
                    "order", order.getOrderNo(), actionTime);
        }
        recordNode(task.getTaskNo(), order.getOrderNo(), depart ? "depart" : "arrive", actionTime,
                actorUserId, DeliveryEnum.TaskStatus.values()[expectedCurrent - 1],
                depart ? DeliveryEnum.TaskStatus.DELIVERING : DeliveryEnum.TaskStatus.ARRIVED);
        return reload(task.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WsDeliveryTask signTask(DeliverySignBo bo, Long actorUserId, String now) {
        CourierAccess.EnabledCourier courier = courierAccess.requireEnabledCourier(actorUserId);
        DeliveryClock.requireTime(now, "当前时间");
        WsDeliveryTask task = requireTask(bo.getTaskNo());
        requireVersion(task, bo.getExpectedVersion());
        if (ObjectUtil.notEqual(task.getCourierId(), courier.courier().getId())
                || ObjectUtil.notEqual(task.getTaskStatus(), DeliveryEnum.TaskStatus.ARRIVED.getValue())) {
            throw new JbkException("当前任务不能签收");
        }
        // 规则12：三照缺一不可（类型恰为 1门牌/2水品/3摆放 各一）
        requireThreePhotos(bo.getPhotos());
        // 结构化数量（服务端界校验，不信 Bo 注解这一道）
        int actualDelivery = DeliveryPricing.requireDeliveryCount(bo.getActualDeliveryCount());
        int actualReturn = DeliveryPricing.requireReturnCount(bo.getActualReturnCount());
        // 定位声明不得超出证据：声明「已记录」时三照必须都携带合法坐标
        int locationStatus = requireLocationEvidence(bo);
        WsOrder order = DeliveryLinkGuard.requireFulfillable(orderMapper.selectById(task.getOrderId()), task);

        // 规则13：签收时间服务端生成，三照/任务/订单完成/消息/审计全部同源本值
        String signTime = DeliveryClock.floor(now, task.getArriveTime());
        String appealDeadline = DeliveryClock.plusHours(signTime, 24);

        // 受控媒体绑定（A5）：非本人/用途不符/已被其他任务占用/重复键一律拒绝
        List<String> mediaKeys = bo.getPhotos().stream().map(DeliverySignBo.SignPhotoBo::getMediaKey).toList();
        mediaService.claimForTask(mediaKeys, task.getId(), actorUserId,
                DeliveryEnum.MediaPurpose.SIGN_PHOTO, "签收三照媒体无效或已被占用");

        String photosJson = buildPhotosJson(bo.getPhotos(), signTime);
        int affected = taskMapper.update(null, Wrappers.lambdaUpdate(WsDeliveryTask.class)
                .set(WsDeliveryTask::getTaskStatus, DeliveryEnum.TaskStatus.SIGNED.getValue())
                .set(WsDeliveryTask::getVersion, bo.getExpectedVersion() + 1)
                .set(WsDeliveryTask::getActualDeliveryCount, actualDelivery)
                .set(WsDeliveryTask::getActualReturnCount, actualReturn)
                .set(WsDeliveryTask::getSignTime, signTime)
                .set(WsDeliveryTask::getSignPhotos, photosJson)
                .set(WsDeliveryTask::getLocationStatus, locationStatus)
                .set(WsDeliveryTask::getAppealDeadline, appealDeadline)
                .set(WsDeliveryTask::getUpdateTime, signTime)
                .eq(WsDeliveryTask::getId, task.getId())
                .eq(WsDeliveryTask::getTaskStatus, DeliveryEnum.TaskStatus.ARRIVED.getValue())
                .eq(WsDeliveryTask::getVersion, bo.getExpectedVersion())
                .eq(WsDeliveryTask::getCourierId, courier.courier().getId()));
        if (affected != 1) {
            throw new JbkException("任务状态已变化，请刷新后重试");
        }
        // 规则14：签收即订单完成。订单必须恰好从 2→4 推进一次；0 行说明订单被并发动过，
        // 整体回滚（任务签收一并撤销），绝不允许「任务已签收、订单不完成」的脱钩态。
        int orderMoved = orderMapper.update(null, Wrappers.lambdaUpdate(WsOrder.class)
                .set(WsOrder::getOrderStatus, TradeEnum.OrderStatus.FINISHED.getValue())
                .set(WsOrder::getFinishTime, signTime)
                .set(WsOrder::getUpdateTime, signTime)
                .eq(WsOrder::getId, order.getId())
                .eq(WsOrder::getOrderStatus, TradeEnum.OrderStatus.PAID.getValue()));
        if (orderMoved != 1) {
            throw new JbkException("订单状态已变化，签收失败");
        }
        messageService.sendInApp(task.getUserId(), MessageEnum.MsgDomain.DELIVERY, "订单已签收",
                "订单 " + order.getOrderNo() + " 已完成三照签收；如有异议，可在签收后 24 小时内发起申诉。",
                "order", order.getOrderNo(), signTime);
        recordNode(task.getTaskNo(), order.getOrderNo(), "sign", signTime, actorUserId,
                DeliveryEnum.TaskStatus.ARRIVED, DeliveryEnum.TaskStatus.SIGNED);
        return reload(task.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WsDeliveryException reportException(DeliveryExceptionReportBo bo, Long actorUserId, String now) {
        CourierAccess.EnabledCourier courier = courierAccess.requireEnabledCourier(actorUserId);
        DeliveryClock.requireTime(now, "当前时间");
        DeliveryEnum.ExceptionReason reason = DeliveryEnum.ExceptionReason.getType(bo.getReason());
        WsDeliveryTask task = requireTask(bo.getTaskNo());
        requireVersion(task, bo.getExpectedVersion());
        boolean inFulfillment = ObjectUtil.equal(task.getTaskStatus(), DeliveryEnum.TaskStatus.ACCEPTED.getValue())
                || ObjectUtil.equal(task.getTaskStatus(), DeliveryEnum.TaskStatus.DELIVERING.getValue())
                || ObjectUtil.equal(task.getTaskStatus(), DeliveryEnum.TaskStatus.ARRIVED.getValue());
        if (ObjectUtil.notEqual(task.getCourierId(), courier.courier().getId()) || !inFulfillment) {
            throw new JbkException("当前任务不能上报配送异常");
        }
        if (StrUtil.isBlank(bo.getDescription())) {
            throw new JbkException("异常说明不能为空");
        }
        // 全部校验（含订单关联与可履约）通过之后才允许任何写入；失败零副作用（Mock 第三轮口径）
        WsOrder order = DeliveryLinkGuard.requireFulfillable(orderMapper.selectById(task.getOrderId()), task);

        // 异常时间不早于任务最后已发生节点（统一逻辑时钟）
        String exceptionTime = DeliveryClock.floor(now, order.getCreateTime(),
                task.getAcceptTime(), task.getDepartTime(), task.getArriveTime());
        if (ObjectUtil.isNotNull(bo.getEvidenceRefs()) && !bo.getEvidenceRefs().isEmpty()) {
            mediaService.claimForTask(bo.getEvidenceRefs(), task.getId(), actorUserId,
                    DeliveryEnum.MediaPurpose.EXCEPTION_EVIDENCE, "异常举证媒体无效或已被占用");
        }
        WsDeliveryException record = new WsDeliveryException()
                .setTaskId(task.getId())
                .setCourierId(courier.courier().getId())
                .setExceptionReason(reason.getValue())
                .setExceptionDesc(bo.getDescription().trim())
                .setEvidenceRefs(ObjectUtil.isNull(bo.getEvidenceRefs()) ? null
                        : JSONUtil.toJsonStr(bo.getEvidenceRefs()));
        record.setCreateTime(exceptionTime);
        record.setUpdateTime(exceptionTime);
        exceptionMapper.insert(record);
        // 异常不推进状态、不加版本（Mock 口径）：异常行本身是权威证据（同事务），
        // 审计属展示型留痕走 quietly；操作者是配送员，portal 显式记 COURIER（P1-3）
        domainEventService.recordAs(OpsEnum.ActorPortal.COURIER, actorUserId,
                OpsEnum.EventType.DELIVERY_NODE, task.getTaskNo(), null,
                "配送异常已登记：" + reason.getDesc() + "：" + record.getExceptionDesc()
                        + "（orderNo=" + order.getOrderNo() + "，time=" + exceptionTime + "）");
        return record;
    }

    // ==================== 内部支撑 ====================

    private WsDeliveryTask requireTask(String taskNo) {
        if (StrUtil.isBlank(taskNo)) {
            throw new JbkException("配送任务不存在");
        }
        WsDeliveryTask task = taskMapper.selectOne(Wrappers.lambdaQuery(WsDeliveryTask.class)
                .eq(WsDeliveryTask::getTaskNo, taskNo));
        if (ObjectUtil.isNull(task)) {
            throw new JbkException("配送任务不存在");
        }
        return task;
    }

    private void requireVersion(WsDeliveryTask task, Integer expectedVersion) {
        if (ObjectUtil.isNull(expectedVersion) || ObjectUtil.notEqual(task.getVersion(), expectedVersion)) {
            throw new JbkException("任务状态已变化，请刷新后重试");
        }
    }

    private WsDeliveryTask reload(Long taskId) {
        WsDeliveryTask task = taskMapper.selectById(taskId);
        if (ObjectUtil.isNull(task)) {
            throw new JbkException("配送任务数据异常");
        }
        return task;
    }

    /** 三照齐全校验：恰3张、类型两两不同且覆盖 {1,2,3}。 */
    private void requireThreePhotos(List<DeliverySignBo.SignPhotoBo> photos) {
        if (ObjectUtil.isNull(photos) || photos.size() != DeliveryEnum.SIGN_PHOTO_TYPES.size()) {
            throw new JbkException("门牌、水品、摆放三照缺一不可");
        }
        Set<Integer> types = new HashSet<>();
        for (DeliverySignBo.SignPhotoBo photo : photos) {
            if (ObjectUtil.isNull(photo) || ObjectUtil.isNull(photo.getType())
                    || StrUtil.isBlank(photo.getMediaKey()) || !types.add(photo.getType())) {
                throw new JbkException("门牌、水品、摆放三照缺一不可");
            }
        }
        if (!types.containsAll(DeliveryEnum.SIGN_PHOTO_TYPES)) {
            throw new JbkException("门牌、水品、摆放三照缺一不可");
        }
    }

    /**
     * 定位声明一致性（Mock LOCATION_EVIDENCE_INVALID 口径的后端形态）：
     * 缺省=未记录；声明「已记录」时三照必须逐张携带值域合法的坐标——
     * 契约不信任调用方单方声明，防「文案宣称超出实际记录」。
     */
    private int requireLocationEvidence(DeliverySignBo bo) {
        Integer claimed = bo.getLocationStatus();
        if (ObjectUtil.isNull(claimed)) {
            return DeliveryEnum.LocationStatus.UNRECORDED.getValue();
        }
        if (ObjectUtil.equal(claimed, DeliveryEnum.LocationStatus.UNRECORDED.getValue())) {
            return DeliveryEnum.LocationStatus.UNRECORDED.getValue();
        }
        if (ObjectUtil.notEqual(claimed, DeliveryEnum.LocationStatus.RECORDED.getValue())) {
            throw new JbkException("定位记录状态不合法");
        }
        for (DeliverySignBo.SignPhotoBo photo : bo.getPhotos()) {
            Double lat = photo.getLatitude();
            Double lng = photo.getLongitude();
            boolean valid = ObjectUtil.isNotNull(lat) && ObjectUtil.isNotNull(lng)
                    && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180;
            if (!valid) {
                throw new JbkException("定位记录声明与三照坐标不符：三照须携带合法坐标");
            }
        }
        return DeliveryEnum.LocationStatus.RECORDED.getValue();
    }

    /** 权威照片时间由签收动作统一写入，不采信页面草稿时间（Mock 第五轮口径）。 */
    private String buildPhotosJson(List<DeliverySignBo.SignPhotoBo> photos, String signTime) {
        JSONArray array = JSONUtil.createArray();
        for (DeliverySignBo.SignPhotoBo photo : photos) {
            JSONObject item = JSONUtil.createObj()
                    .set("type", photo.getType())
                    .set("mediaKey", photo.getMediaKey())
                    .set("time", signTime);
            if (ObjectUtil.isNotNull(photo.getLatitude()) && ObjectUtil.isNotNull(photo.getLongitude())) {
                item.set("lat", photo.getLatitude());
                item.set("lng", photo.getLongitude());
            }
            array.add(item);
        }
        return array.toString();
    }

    /**
     * 履约节点审计（E2E-03 验收 P1-3）：接单/离站/送达/签收是关键状态变化，走可靠路径
     * （与业务动作同一事务 + 业务幂等键：写入失败抛出令动作整体回滚，撞键读回核验语义，
     * 绝不静默丢审计、也不留先于业务提交的幽灵审计）；
     * 每个节点在任务生命周期内至多发生一次，幂等键 DNODE_<节点>:<任务号> 天然唯一。
     * 操作者是配送员：portal 由领域层按能力校验结论显式记 COURIER，不走会话推断（推断只会得到 USER）。
     * 时间与节点动作同源（规则13）。
     */
    private void recordNode(String taskNo, String orderNo, String node, String actionTime,
                            Long courierUserId, DeliveryEnum.TaskStatus from, DeliveryEnum.TaskStatus to) {
        domainEventService.recordReliableOnceAs(OpsEnum.ActorPortal.COURIER, courierUserId,
                OpsEnum.EventType.DELIVERY_NODE, taskNo,
                "DNODE_" + node.toUpperCase() + ":" + taskNo,
                from.getValue() + ":" + from.getDesc(),
                to.getValue() + ":" + to.getDesc() + "（node=" + node + "，orderNo=" + orderNo
                        + "，time=" + actionTime + "）");
    }
}
