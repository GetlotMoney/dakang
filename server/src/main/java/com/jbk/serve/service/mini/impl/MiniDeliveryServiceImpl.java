package com.jbk.serve.service.mini.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.delivery.WsDeliveryAppealMapper;
import com.jbk.serve.mapper.delivery.WsDeliveryTaskMapper;
import com.jbk.serve.mapper.station.WsStationMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.user.WsCourierMapper;
import com.jbk.serve.service.delivery.CourierScope;
import com.jbk.serve.service.delivery.DeliveryLinkGuard;
import com.jbk.serve.service.delivery.IDeliveryAppealTxService;
import com.jbk.serve.service.delivery.IDeliveryMediaService;
import com.jbk.serve.service.delivery.IDeliveryOrderService;
import com.jbk.serve.service.delivery.IDeliveryTaskTxService;
import com.jbk.serve.service.delivery.impl.CourierAccess;
import com.jbk.serve.service.mini.IMiniDeliveryService;
import com.jbk.serve.service.mini.IMiniOrderService;
import com.jbk.tool.consts.delivery.DeliveryEnum;
import com.jbk.tool.consts.message.MessageEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.consts.user.UserEnum;
import com.jbk.tool.data.delivery.bo.DeliveryAppealCreateBo;
import com.jbk.tool.data.delivery.bo.DeliveryAppealEvidenceBo;
import com.jbk.serve.service.mini.IMiniFamilyService;
import com.jbk.tool.data.delivery.bo.DeliveryCreateBo;
import com.jbk.tool.data.user.po.WsUserAddress;
import com.jbk.tool.data.delivery.bo.DeliveryExceptionReportBo;
import com.jbk.tool.data.delivery.bo.DeliverySignBo;
import com.jbk.tool.data.delivery.po.WsDeliveryAppeal;
import com.jbk.tool.data.delivery.po.WsDeliveryException;
import com.jbk.tool.data.delivery.po.WsDeliveryTask;
import com.jbk.tool.data.mini.bo.MiniDeliveryMediaUploadBo;
import com.jbk.tool.data.mini.vo.MiniCourierAdmissionVo;
import com.jbk.tool.data.mini.vo.MiniDeliveryAppealVo;
import com.jbk.tool.data.mini.vo.MiniDeliveryCreateVo;
import com.jbk.tool.data.mini.vo.MiniDeliveryExceptionVo;
import com.jbk.tool.data.mini.vo.MiniDeliveryMediaVo;
import com.jbk.tool.data.mini.vo.MiniDeliveryTaskVo;
import com.jbk.tool.data.station.po.WsStation;
import com.jbk.tool.data.trade.bo.MiniOrderDetailBo;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.user.po.WsCourier;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import com.jbk.tool.utils.PhoneMask;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 小程序配送域读模型实现（E2E-03 包B）。
 *
 * <p>只做两件事：把包A 服务的 Po 结果投影成 miniapp 契约形状的 Vo；把会话 userId 透传给
 * 包A 服务。任何状态转换、金额、幂等、窗口、范围判定都不在这里重复实现——
 * 这里若出现第二份判定，就会与包A 的条件 UPDATE 漂移，形成"读到的规则"与
 * "写入时执行的规则"两套口径。</p>
 *
 * @author dakang
 * @since 2026-07-24
 */
@Service
public class MiniDeliveryServiceImpl implements IMiniDeliveryService {

    @Autowired
    private com.jbk.serve.mapper.aftersale.WsAfterSaleActionMapper afterSaleActionMapper;


    /** 任务列表视图（接口入参形态，非落库状态，不注册字典）。 */
    private static final String VIEW_AVAILABLE = "available";
    private static final String VIEW_ACTIVE = "active";
    private static final String VIEW_HISTORY = "history";

    /** 进行中视图状态集（与 Mock 契约 D01 同口径：含申诉中）。 */
    private static final Set<Integer> ACTIVE_STATUSES = Set.of(
            DeliveryEnum.TaskStatus.ACCEPTED.getValue(),
            DeliveryEnum.TaskStatus.DELIVERING.getValue(),
            DeliveryEnum.TaskStatus.ARRIVED.getValue(),
            DeliveryEnum.TaskStatus.APPEALING.getValue());

    /** 终态视图状态集：已签收 / 已取消。 */
    private static final Set<Integer> HISTORY_STATUSES = Set.of(
            DeliveryEnum.TaskStatus.SIGNED.getValue(),
            DeliveryEnum.TaskStatus.CANCELLED.getValue());

    /**
     * 上传类型白名单：只收微信端会实际产出的图片格式。包A register 的 image/* 是最终防线，
     * 这里再收窄一层是入口策略——svg 之类"合法 image/* 却可携带脚本"的类型不放进来。
     */
    private static final Set<String> UPLOAD_MIME_WHITELIST = Set.of("image/jpeg", "image/png", "image/webp");

    @Autowired
    private IDeliveryOrderService deliveryOrderService;
    @Autowired
    private IDeliveryTaskTxService taskTxService;
    @Autowired
    private IDeliveryAppealTxService appealTxService;
    @Autowired
    private IDeliveryMediaService mediaService;
    @Autowired
    private IMiniOrderService miniOrderService;
    @Autowired
    private CourierAccess courierAccess;
    @Autowired
    private WsDeliveryTaskMapper taskMapper;
    @Autowired
    private WsDeliveryAppealMapper appealMapper;
    @Autowired
    private WsOrderMapper orderMapper;
    @Autowired
    private WsStationMapper stationMapper;
    @Autowired
    private WsCourierMapper courierMapper;
    @Autowired
    private com.jbk.serve.mapper.user.WsUserMapper userMapper;
    @org.springframework.beans.factory.annotation.Value("${demo-simulation.enabled:false}")
    private boolean demoSimulationEnabled;
    @Autowired
    private IMiniFamilyService familyService;

    // ==================== 用户侧 ====================

    @Override
    public MiniDeliveryCreateVo createOrder(DeliveryCreateBo bo, Long userId) {
        // 地址簿引用优先：服务端按归属解引用取地址与号码写入任务快照（号码不经前端回流，铁律6）。
        // 快照语义不变——任务仍存文本，后续改地址簿不影响已下单。
        if (ObjectUtil.isNotNull(bo.getAddressId())) {
            WsUserAddress address = familyService.requireOwnAddress(userId, bo.getAddressId());
            bo.setReceiveAddress(StrUtil.trim(address.getRegion()) + ' ' + StrUtil.trim(address.getAddressDetail()));
            bo.setReceivePhone(address.getContactPhone());
        }
        IDeliveryOrderService.CreatedDelivery created = deliveryOrderService.createDeliveryOrder(bo, userId);
        // 订单区块复用 mini 订单读模型（同一 OrderDetailVo 契约），不为配送另拼订单结构
        MiniOrderDetailBo detailBo = new MiniOrderDetailBo().setOrderNo(created.order().getOrderNo());
        return new MiniDeliveryCreateVo()
                .setOrder(miniOrderService.getMyOrderDetail(detailBo, userId))
                .setTask(toTaskVo(created.task(), created.order(), null));
    }

    @Override
    public MiniDeliveryTaskVo getMyDeliveryTask(String orderNo, Long userId) {
        WsOrder order = orderMapper.selectOne(Wrappers.lambdaQuery(WsOrder.class)
                .eq(WsOrder::getOrderNo, StrUtil.trim(orderNo)));
        // 归属先行（铁律6）：非本人订单按不存在拒绝，不泄露他人订单存在性
        if (ObjectUtil.isNull(order) || ObjectUtil.notEqual(order.getUserId(), userId)) {
            throw new JbkException("订单不存在或无权访问");
        }
        if (ObjectUtil.notEqual(order.getOrderType(), TradeEnum.OrderType.DELIVERY.getValue())) {
            // 非配送单没有配送任务：与 Mock getMyDeliveryTask 同口径返回空
            return null;
        }
        WsDeliveryTask task = taskMapper.selectOne(Wrappers.lambdaQuery(WsDeliveryTask.class)
                .eq(WsDeliveryTask::getOrderId, order.getId()));
        if (ObjectUtil.isNull(task)) {
            return null;
        }
        // 共键栅栏：任务-订单错位 fail-closed，绝不把错挂的任务证据当成本单证据展示
        DeliveryLinkGuard.requireLinked(order, task);
        return toTaskVo(task, order, null);
    }

    @Override
    public MiniDeliveryAppealVo getMyAppeal(String appealId, Long userId) {
        long id = decimalId(appealId, "appealId");
        WsDeliveryAppeal appeal = appealMapper.selectById(id);
        // 归属先行（铁律6）：非本人申诉与不存在同一口径拒绝，不泄露他人申诉存在性
        if (ObjectUtil.isNull(appeal) || ObjectUtil.notEqual(appeal.getUserId(), userId)) {
            throw new JbkException("申诉不存在或无权访问");
        }
        return toAppealVo(appeal, null, null);
    }

    @Override
    public MiniDeliveryAppealVo createAppeal(DeliveryAppealCreateBo bo, Long userId) {
        WsDeliveryAppeal appeal = appealTxService.createAppeal(bo, userId, DateUtils.time());
        return toAppealVo(appeal, StrUtil.trim(bo.getOrderNo()), StrUtil.trim(bo.getTaskNo()));
    }

    // ==================== 配送员侧 ====================

    @Override
    public List<MiniDeliveryTaskVo> pageTasks(String view, Long userId) {
        String normalized = StrUtil.trim(view);
        List<WsDeliveryTask> tasks;
        if (VIEW_AVAILABLE.equals(normalized)) {
            // 范围/自配送/预约到点/订单可履约全部压在包A SQL（与接单校验同源）
            tasks = taskTxService.listAvailableTasks(userId, DateUtils.time());
        } else if (VIEW_ACTIVE.equals(normalized) || VIEW_HISTORY.equals(normalized)) {
            // 本人任务：配送员身份由会话解析（铁律6/7），未启用/无范围一律拒绝
            CourierAccess.EnabledCourier courier = courierAccess.requireEnabledCourier(userId);
            Set<Integer> statuses = VIEW_ACTIVE.equals(normalized) ? ACTIVE_STATUSES : HISTORY_STATUSES;
            tasks = taskMapper.selectList(Wrappers.lambdaQuery(WsDeliveryTask.class)
                    .eq(WsDeliveryTask::getCourierId, courier.courier().getId())
                    .in(WsDeliveryTask::getTaskStatus, statuses)
                    .orderByDesc(WsDeliveryTask::getCreateTime)
                    .orderByDesc(WsDeliveryTask::getId));
        } else {
            throw new JbkException("任务视图不合法");
        }
        return toTaskVos(tasks);
    }

    @Override
    public MiniDeliveryTaskVo taskDetail(String taskNo, Long userId) {
        CourierAccess.EnabledCourier courier = courierAccess.requireEnabledCourier(userId);
        WsDeliveryTask task = requireTaskByNo(taskNo);
        boolean isMine = ObjectUtil.isNotNull(task.getCourierId())
                && ObjectUtil.equal(task.getCourierId(), courier.courier().getId());
        if (!isMine) {
            // 未分配任务的可见性（Mock assertCourierCanView 同口径）：
            // 待接单 + 无人认领 + 非本人下单 + 在服务范围内，缺一即拒
            boolean canViewAvailable = ObjectUtil.equal(task.getTaskStatus(), DeliveryEnum.TaskStatus.PENDING.getValue())
                    && ObjectUtil.isNull(task.getCourierId())
                    && ObjectUtil.notEqual(task.getUserId(), userId)
                    && courier.allowsStation(task.getStationId());
            if (!canViewAvailable) {
                throw new JbkException("无权查看该配送任务");
            }
            // 深链防护：未分配任务只有关联订单可履约才允许查看，
            // 阻断孤儿/未支付/终态/错位任务经 URL 直达泄露收货地址（Mock 第三轮口径）
            DeliveryLinkGuard.requireFulfillable(orderMapper.selectById(task.getOrderId()), task);
        }
        return toTaskVo(task, null, null);
    }

    @Override
    public MiniDeliveryTaskVo acceptTask(String taskNo, Integer expectedVersion, Long userId) {
        WsDeliveryTask task = taskTxService.acceptTask(StrUtil.trim(taskNo), expectedVersion, userId, DateUtils.time());
        return toTaskVo(task, null, null);
    }

    @Override
    public MiniDeliveryTaskVo advanceTask(String taskNo, int targetStatus, Integer expectedVersion, Long userId) {
        WsDeliveryTask task = taskTxService.advanceTask(StrUtil.trim(taskNo), targetStatus,
                expectedVersion, userId, DateUtils.time());
        return toTaskVo(task, null, null);
    }

    @Override
    public MiniDeliveryTaskVo signTask(DeliverySignBo bo, Long userId) {
        WsDeliveryTask task = taskTxService.signTask(bo, userId, DateUtils.time());
        return toTaskVo(task, null, null);
    }

    @Override
    public MiniDeliveryExceptionVo reportException(DeliveryExceptionReportBo bo, Long userId) {
        WsDeliveryException record = taskTxService.reportException(bo, userId, DateUtils.time());
        return toExceptionVo(record, StrUtil.trim(bo.getTaskNo()));
    }

    @Override
    public List<MiniDeliveryExceptionVo> listTaskExceptions(String taskNo, Long userId) {
        String normalized = StrUtil.trim(taskNo);
        return appealTxService.listTaskExceptionsForCourier(normalized, userId).stream()
                .map(item -> toExceptionVo(item, normalized))
                .collect(Collectors.toList());
    }

    @Override
    public MiniDeliveryAppealVo getTaskAppeal(String taskNo, Long userId) {
        String normalized = StrUtil.trim(taskNo);
        WsDeliveryAppeal appeal = appealTxService.getTaskAppealForCourier(normalized, userId);
        if (ObjectUtil.isNull(appeal)) {
            return null;
        }
        return toAppealVo(appeal, orderNoOf(appeal.getOrderId()), normalized);
    }

    @Override
    public MiniDeliveryAppealVo appendAppealEvidence(DeliveryAppealEvidenceBo bo, Long userId) {
        WsDeliveryAppeal appeal = appealTxService.appendCourierEvidence(bo, userId, DateUtils.time());
        return toAppealVo(appeal, orderNoOf(appeal.getOrderId()), StrUtil.trim(bo.getTaskNo()));
    }

    @Override
    public MiniCourierAdmissionVo getAdmission(Long userId) {
        // 与 CourierAccess 同一取行口径（同 userId 取最新准入记录），但这里是只读状态投影：
        // 未准入不是错误，返回 status=0 供 D02/U03 呈现"未提交"
        WsCourier courier = courierMapper.selectOne(Wrappers.lambdaQuery(WsCourier.class)
                .eq(WsCourier::getUserId, userId)
                .orderByDesc(WsCourier::getId)
                .last("LIMIT 1"));
        MiniCourierAdmissionVo vo = new MiniCourierAdmissionVo()
                .setAccountId(userId)
                .setUserId(userId);
        if (ObjectUtil.isNull(courier)) {
            return vo.setStatus(0).setRequestedStationIds(List.of());
        }
        boolean rejected = ObjectUtil.equal(courier.getCourierStatus(), UserEnum.CourierStatus.REJECTED.getValue());
        return vo.setStatus(courier.getCourierStatus())
                .setApplicantName(courier.getCourierName())
                // 脱敏唯一实现：PhoneMask；非 11 位一律整体屏蔽，绝不回落明文
                .setMaskedPhone(PhoneMask.mask(courier.getCourierPhone()))
                .setRequestedStationIds(new ArrayList<>(CourierScope.parseStationIds(courier.getStationIds())))
                .setRequestedRegion(blankToNull(courier.getServiceRegion()))
                .setSubmittedTime(courier.getCreateTime())
                .setRejectReason(rejected ? blankToNull(courier.getAuditRemark()) : null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.jbk.serve.service.message.IWsMessageService messageService;
    @org.springframework.beans.factory.annotation.Autowired
    private com.jbk.serve.service.ops.IWsDomainEventService domainEventService;

    /**
     * 自助准入申请（S3）。并发裁决点=本人 ws_user 行锁：ws_courier 无 USER_ID 唯一键
     * （历史允许多条记录），「读最新→判分支→写入」窗口靠行锁串行化——后到者等锁后
     * 重读，命中先到者刚写入的待审核记录即被拒绝，恒至多一条有效申请。
     */
    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class,
            isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public MiniCourierAdmissionVo submitAdmission(com.jbk.tool.data.mini.bo.MiniAdmissionSubmitBo bo, Long userId) {
        com.jbk.tool.data.user.po.WsUser applicant = userMapper.selectById(userId);
        if (ObjectUtil.isNull(applicant) || cn.hutool.core.util.StrUtil.isBlank(applicant.getUserPhone())) {
            throw new JbkException("申请配送员前请先绑定手机号");
        }
        if (!ObjectUtil.equal(applicant.getUserPhone(), bo.getPhone())) {
            throw new JbkException("申请手机号必须与当前账号已绑定手机号一致");
        }
        List<Long> stationIds = bo.getRequestedStationIds() == null ? List.of()
                : bo.getRequestedStationIds().stream().filter(ObjectUtil::isNotNull).distinct().toList();
        String region = StrUtil.trimToNull(bo.getRequestedRegion());
        if (stationIds.isEmpty() && region == null) {
            throw new JbkException("申请服务区域或水站至少填写一项");
        }
        if (stationIds.size() > 20) {
            throw new JbkException("申请水站数量过多，请精简后提交");
        }
        if (!stationIds.isEmpty()) {
            // 水站必须真实存在且正常营业：停业/不存在的站进服务范围会让审核通过后接不到任何单
            List<WsStation> stations = stationMapper.selectBatchIds(stationIds);
            boolean allLegal = stations.size() == stationIds.size()
                    && stations.stream().allMatch(s -> ObjectUtil.equal(s.getStationStatus(), 1));
            if (!allLegal) {
                throw new JbkException("申请的水站不存在或已停业，请重新选择");
            }
        }
        if (ObjectUtil.isNull(courierMapper.lockUserRow(userId))) {
            throw new JbkException("登录状态异常");
        }
        WsCourier existing = courierMapper.selectOne(Wrappers.lambdaQuery(WsCourier.class)
                .eq(WsCourier::getUserId, userId)
                .orderByDesc(WsCourier::getId)
                .last("LIMIT 1"));
        String now = DateUtils.time();
        String stationIdsText = stationIds.isEmpty() ? null
                : stationIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        int targetStatus = demoSimulationEnabled ? UserEnum.CourierStatus.ENABLED.getValue()
                : UserEnum.CourierStatus.PENDING.getValue();
        Long recordId;
        if (ObjectUtil.isNull(existing)) {
            WsCourier fresh = new WsCourier()
                    .setUserId(userId)
                    .setCourierName(bo.getApplicantName().trim())
                    .setCourierPhone(bo.getPhone())
                    .setStationIds(stationIdsText)
                    .setServiceRegion(region)
                    .setCourierStatus(targetStatus);
            courierMapper.insert(fresh);
            recordId = fresh.getId();
        }
        else if (ObjectUtil.equal(existing.getCourierStatus(), UserEnum.CourierStatus.REJECTED.getValue())) {
            // 驳回后重新提交：复用原记录（保留历史审核备注为证据），精确前态 CAS 防并发覆盖
            int updated = courierMapper.update(null, Wrappers.lambdaUpdate(WsCourier.class)
                    .eq(WsCourier::getId, existing.getId())
                    .eq(WsCourier::getCourierStatus, UserEnum.CourierStatus.REJECTED.getValue())
                    .set(WsCourier::getCourierName, bo.getApplicantName().trim())
                    .set(WsCourier::getCourierPhone, bo.getPhone())
                    .set(WsCourier::getStationIds, stationIdsText)
                    .set(WsCourier::getServiceRegion, region)
                    .set(WsCourier::getCourierStatus, targetStatus));
            if (updated != 1) {
                throw new JbkException("申请状态已变化，请刷新后重试");
            }
            recordId = existing.getId();
        }
        else if (ObjectUtil.equal(existing.getCourierStatus(), UserEnum.CourierStatus.PENDING.getValue())) {
            if (!demoSimulationEnabled) {
                throw new JbkException("申请正在审核中，请耐心等待结果");
            }
            int updated = courierMapper.update(null, Wrappers.lambdaUpdate(WsCourier.class)
                    .eq(WsCourier::getId, existing.getId())
                    .eq(WsCourier::getCourierStatus, UserEnum.CourierStatus.PENDING.getValue())
                    .set(WsCourier::getCourierName, bo.getApplicantName().trim())
                    .set(WsCourier::getCourierPhone, bo.getPhone())
                    .set(WsCourier::getStationIds, stationIdsText)
                    .set(WsCourier::getServiceRegion, region)
                    .set(WsCourier::getCourierStatus, targetStatus));
            if (updated != 1) throw new JbkException("申请状态已变化，请刷新后重试");
            recordId = existing.getId();
        }
        else if (ObjectUtil.equal(existing.getCourierStatus(), UserEnum.CourierStatus.ENABLED.getValue())) {
            throw new JbkException("您已具备配送能力，无需重复申请");
        }
        else {
            throw new JbkException("配送能力已停用，请联系运营处理");
        }
        // 留痕：领域事件与提交写入同事务（REQUIRED）——插入/复用记录回滚时审计随之
        // 消失，无幽灵；首次申请与每次驳回重提各成一行（无幂等键，互不撞键）。
        // 站内消息同事务：发送失败=提交整体回滚（用户重试即可，绝不留半套事实）
        domainEventService.recordReliableInTx(OpsEnum.EventType.DELIVERY_NODE,
                "ADMISSION:" + userId, ObjectUtil.isNull(existing) ? null : existing.getCourierStatus(),
                targetStatus);
        messageService.sendInApp(userId, MessageEnum.MsgDomain.DELIVERY,
                demoSimulationEnabled ? "配送准入已通过" : "配送准入申请已提交",
                demoSimulationEnabled ? "演示环境已按准入条件自动审核，可以进入任务中心" : "申请已进入审核，结果将另行通知",
                "admission",
                String.valueOf(recordId), now);
        return getAdmission(userId);
    }

    // ==================== 媒体 ====================

    @Override
    public MiniDeliveryMediaVo uploadMedia(MiniDeliveryMediaUploadBo bo, Long userId) {
        DeliveryEnum.MediaPurpose purpose = mediaPurposeOf(bo.getPurpose());
        String mimeType = StrUtil.trimToEmpty(bo.getMimeType()).toLowerCase(Locale.ROOT);
        // 入口白名单先于解码：类型对不上就不必花费 10MB 级的 base64 解码
        if (!UPLOAD_MIME_WHITELIST.contains(mimeType)) {
            throw new JbkException("仅支持 JPEG/PNG/WEBP 图片");
        }
        byte[] content;
        try {
            content = Base64.getDecoder().decode(StrUtil.trimToEmpty(bo.getContentBase64()));
        } catch (IllegalArgumentException e) {
            throw new JbkException("图片内容不是合法的 base64");
        }
        // 大小界与内容寻址幂等由包A register 收口（≤10MB、同人同内容同用途返回同键）
        String mediaKey = mediaService.register(userId, purpose, content, mimeType, DateUtils.time());
        return new MiniDeliveryMediaVo().setMediaKey(mediaKey);
    }

    // ==================== Vo 装配 ====================

    private List<MiniDeliveryTaskVo> toTaskVos(List<WsDeliveryTask> tasks) {
        if (ObjectUtil.isNull(tasks) || tasks.isEmpty()) {
            return List.of();
        }
        Map<Long, String> stationNames = loadStationNames(tasks);
        Map<Long, WsOrder> orders = loadOrders(tasks);
        return tasks.stream()
                .map(task -> toTaskVo(task, orders.get(task.getOrderId()), stationNames.get(task.getStationId())))
                .collect(Collectors.toList());
    }

    /**
     * Po → 契约 Vo 投影。order/stationName 允许调用方传入批量预取值；
     * 单条场景传 null 时按需回查（orderNo 与 payWay 同出订单行，D-214 展示分流依据）。
     * 电话只出脱敏值（PhoneMask 唯一实现）。
     */
    private MiniDeliveryTaskVo toTaskVo(WsDeliveryTask task, WsOrder order, String stationName) {
        long water = ObjectUtil.defaultIfNull(task.getWaterAmount(), 0L);
        long fee = ObjectUtil.defaultIfNull(task.getDeliveryFee(), 0L);
        WsOrder linked = ObjectUtil.isNull(order) ? orderOf(task.getOrderId()) : order;
        return new MiniDeliveryTaskVo()
                .setIsResend(resolveIsResend(task.getId()))
                .setTaskId(task.getId())
                .setTaskNo(task.getTaskNo())
                .setOrderId(task.getOrderId())
                .setOrderNo(ObjectUtil.isNull(linked) ? null : linked.getOrderNo())
                .setUserId(task.getUserId())
                .setCourierId(task.getCourierId())
                .setStationId(task.getStationId())
                .setStationName(ObjectUtil.isNull(stationName) ? stationNameOf(task.getStationId()) : stationName)
                .setWaterTypeId(task.getWaterTypeId())
                .setWaterTypeName(task.getWaterType())
                .setContainerSpec(task.getContainerSpec())
                .setPlannedDeliveryCount(task.getDeliveryCount())
                .setActualDeliveryCount(task.getActualDeliveryCount())
                .setPlannedReturnCount(task.getPlanReturnCount())
                .setActualReturnCount(task.getActualReturnCount())
                .setReceiveAddress(task.getReceiveAddress())
                .setMaskedPhone(PhoneMask.mask(task.getReceivePhone()))
                .setPriceSnapshot(new MiniDeliveryTaskVo.MiniDeliveryPriceVo()
                        .setWaterAmountFen(water)
                        .setDeliveryFeeFen(fee)
                        // 总额=两者之和（规则2）；快照落库后不再重算价目
                        .setTotalAmountFen(water + fee))
                .setPayWay(ObjectUtil.isNull(linked) ? null : linked.getPayWay())
                .setTaskStatus(task.getTaskStatus())
                .setVersion(task.getVersion())
                .setScheduledTime(task.getScheduledTime())
                .setAcceptTime(task.getAcceptTime())
                .setDepartTime(task.getDepartTime())
                .setArriveTime(task.getArriveTime())
                .setSignTime(task.getSignTime())
                .setAppealDeadline(task.getAppealDeadline())
                .setLocationStatus(task.getLocationStatus())
                .setSignPhotos(parseSignPhotos(task.getSignPhotos()));
    }

    /** 签收三照 JSON（包A signTask 落库形态：{type, mediaKey, time, lat, lng}）→ Vo。 */
    private List<MiniDeliveryTaskVo.MiniSignPhotoVo> parseSignPhotos(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {
            JSONArray array = JSONUtil.parseArray(json);
            List<MiniDeliveryTaskVo.MiniSignPhotoVo> photos = new ArrayList<>(array.size());
            for (Object item : array) {
                JSONObject obj = (JSONObject) item;
                photos.add(new MiniDeliveryTaskVo.MiniSignPhotoVo()
                        .setType(obj.getInt("type"))
                        .setMediaKey(obj.getStr("mediaKey"))
                        .setTime(obj.getStr("time"))
                        .setLatitude(obj.getDouble("lat"))
                        .setLongitude(obj.getDouble("lng")));
            }
            return photos;
        } catch (Exception malformed) {
            // 三照 JSON 由签收事务落库，损坏意味着数据被外力破坏：宁可不展示，也不拼残缺证据
            return List.of();
        }
    }

    private MiniDeliveryExceptionVo toExceptionVo(WsDeliveryException record, String taskNo) {
        return new MiniDeliveryExceptionVo()
                .setExceptionId(record.getId())
                .setTaskNo(taskNo)
                .setCourierId(record.getCourierId())
                // 1354 整数 → 契约字符码的唯一映射点（枚举名即契约码）
                .setReason(DeliveryEnum.ExceptionReason.getType(record.getExceptionReason()).name())
                .setDescription(record.getExceptionDesc())
                .setEvidenceRefs(parseStringArray(record.getEvidenceRefs()))
                .setCreateTime(record.getCreateTime());
    }

    private MiniDeliveryAppealVo toAppealVo(WsDeliveryAppeal appeal, String orderNo, String taskNo) {
        boolean decided = ObjectUtil.notEqual(appeal.getAppealStatus(),
                DeliveryEnum.AppealStatus.PENDING.getValue());
        return new MiniDeliveryAppealVo()
                .setAppealId(appeal.getId())
                .setOrderNo(ObjectUtil.isNull(orderNo) ? orderNoOf(appeal.getOrderId()) : orderNo)
                .setTaskNo(ObjectUtil.isNull(taskNo) ? taskNoOf(appeal.getTaskId()) : taskNo)
                .setUserId(appeal.getUserId())
                .setAppealStatus(appeal.getAppealStatus())
                .setReason(appeal.getAppealReason())
                .setDescription(appeal.getAppealDesc())
                .setReceivedCount(appeal.getReceivedCount())
                .setEvidenceRefs(parseStringArray(appeal.getAppealPhotos()))
                .setCreateTime(appeal.getCreateTime())
                .setDecisionSummary(decided ? blankToNull(appeal.getHandleResult()) : null)
                .setCourierEvidences(parseCourierEvidences(appeal.getCourierEvidences()));
    }

    private List<MiniDeliveryAppealVo.MiniCourierEvidenceVo> parseCourierEvidences(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {
            JSONArray array = JSONUtil.parseArray(json);
            List<MiniDeliveryAppealVo.MiniCourierEvidenceVo> evidences = new ArrayList<>(array.size());
            for (Object item : array) {
                JSONObject obj = (JSONObject) item;
                List<String> refs = ObjectUtil.isNull(obj.getJSONArray("evidenceRefs"))
                        ? List.of()
                        : obj.getJSONArray("evidenceRefs").toList(String.class);
                evidences.add(new MiniDeliveryAppealVo.MiniCourierEvidenceVo()
                        .setDescription(obj.getStr("description"))
                        .setEvidenceRefs(refs)
                        .setTime(obj.getStr("time")));
            }
            return evidences;
        } catch (Exception malformed) {
            return List.of();
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

    // ==================== 关联档案读取 ====================

    private WsDeliveryTask requireTaskByNo(String taskNo) {
        if (StrUtil.isBlank(taskNo)) {
            throw new JbkException("配送任务不存在");
        }
        WsDeliveryTask task = taskMapper.selectOne(Wrappers.lambdaQuery(WsDeliveryTask.class)
                .eq(WsDeliveryTask::getTaskNo, taskNo.trim()));
        if (ObjectUtil.isNull(task)) {
            throw new JbkException("配送任务不存在");
        }
        return task;
    }

    private String orderNoOf(Long orderId) {
        WsOrder order = orderOf(orderId);
        return ObjectUtil.isNull(order) ? null : order.getOrderNo();
    }

    private WsOrder orderOf(Long orderId) {
        return ObjectUtil.isNull(orderId) ? null : orderMapper.selectById(orderId);
    }

    private String taskNoOf(Long taskId) {
        if (ObjectUtil.isNull(taskId)) {
            return null;
        }
        WsDeliveryTask task = taskMapper.selectById(taskId);
        return ObjectUtil.isNull(task) ? null : task.getTaskNo();
    }

    private String stationNameOf(Long stationId) {
        if (ObjectUtil.isNull(stationId)) {
            return null;
        }
        WsStation station = stationMapper.selectById(stationId);
        return ObjectUtil.isNull(station) ? null : station.getStationName();
    }

    private Map<Long, String> loadStationNames(List<WsDeliveryTask> tasks) {
        List<Long> stationIds = tasks.stream()
                .map(WsDeliveryTask::getStationId)
                .filter(ObjectUtil::isNotNull)
                .distinct()
                .collect(Collectors.toList());
        if (stationIds.isEmpty()) {
            return new HashMap<>();
        }
        return stationMapper.selectBatchIds(stationIds).stream()
                .collect(Collectors.toMap(WsStation::getId, WsStation::getStationName, (a, b) -> a));
    }

    private Map<Long, WsOrder> loadOrders(List<WsDeliveryTask> tasks) {
        List<Long> orderIds = tasks.stream()
                .map(WsDeliveryTask::getOrderId)
                .filter(ObjectUtil::isNotNull)
                .distinct()
                .collect(Collectors.toList());
        if (orderIds.isEmpty()) {
            return new HashMap<>();
        }
        return orderMapper.selectBatchIds(orderIds).stream()
                .collect(Collectors.toMap(WsOrder::getId, order -> order, (a, b) -> a));
    }

    private DeliveryEnum.MediaPurpose mediaPurposeOf(Integer purpose) {
        for (DeliveryEnum.MediaPurpose item : DeliveryEnum.MediaPurpose.values()) {
            if (ObjectUtil.equal(item.getValue(), purpose)) {
                return item;
            }
        }
        throw new JbkException("媒体用途不合法");
    }

    /** 空白串归一化为 null（Vo NON_NULL 序列化下不给前端发空串占位字段）。 */
    private String blankToNull(String value) {
        return StrUtil.isBlank(value) ? null : value;
    }

    private long decimalId(String raw, String field) {
        if (raw == null || !raw.trim().matches("^[1-9]\\d{0,17}$")) {
            throw new JbkException(field + " 必须是正十进制字符串");
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new JbkException(field + " 越界");
        }
    }

    /**
     * 补送标识：由 ws_after_sale_action.RESULT_TASK_ID 反查，刻意不按「金额为 0」推断
     * （会把赠送活动单误标成补送）。查询异常返回 false 不抛出——纯展示标识不该拖垮任务详情。
     */
    private Boolean resolveIsResend(Long taskId) {
        if (afterSaleActionMapper == null || taskId == null) {
            return false;
        }
        try {
            return afterSaleActionMapper.exists(com.baomidou.mybatisplus.core.toolkit.Wrappers
                    .lambdaQuery(com.jbk.tool.data.aftersale.po.WsAfterSaleAction.class)
                    .eq(com.jbk.tool.data.aftersale.po.WsAfterSaleAction::getResultTaskId, taskId));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
