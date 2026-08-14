package com.jbk.serve.service.mini.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.device.WsDeviceMapper;
import com.jbk.serve.mapper.device.WsDeviceOutletMapper;
import com.jbk.serve.mapper.device.WsDeviceTelemetryMapper;
import com.jbk.serve.mapper.station.WsStationMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.user.WsUserMapper;
import com.jbk.serve.service.mini.IMiniOwnerService;
import com.jbk.serve.service.ops.IWorkOrderService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.device.DeviceEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.device.po.WsDevice;
import com.jbk.tool.data.device.po.WsDeviceOutlet;
import com.jbk.tool.data.device.po.WsDeviceTelemetry;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.mini.bo.MiniOwnerBo;
import com.jbk.tool.data.mini.bo.MiniOwnerTransactionBo;
import com.jbk.tool.data.mini.vo.MiniOwnerDeviceVo;
import com.jbk.tool.data.mini.vo.MiniOwnerOverviewVo;
import com.jbk.tool.data.mini.vo.MiniOwnerTransactionVo;
import com.jbk.tool.data.mini.vo.MiniOwnerServiceTraceVo;
import com.jbk.tool.data.mini.vo.MiniOwnerServiceVo;
import com.jbk.tool.data.mini.vo.OutletSummaryVo;
import com.jbk.tool.data.ops.bo.WorkOrderApplyBo;
import com.jbk.tool.data.ops.po.WsDomainEvent;
import com.jbk.tool.data.ops.po.WsWorkOrder;
import com.jbk.tool.data.station.po.WsStation;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.user.po.WsUser;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.utils.DateUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.PhoneMask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 小程序机主域实现（E2E-05 包E）。范围：列表 WHERE OWNER_USER_ID=会话 userId，
 * 详情非本人一律「设备不存在」（存在性不泄露）；申报建单复用 {@link IWorkOrderService#ownerApply}，
 * 与 PC 同源（S14 三端同一工单号）。遥测只回最近一次有效上报，无上报为 null 不伪造数值。
 *
 * @author dakang
 * @since 2026-07-30
 */
@Service
@RequiredArgsConstructor
public class MiniOwnerServiceImpl implements IMiniOwnerService {

    /** 绑号闸：工单联系方式只来自账号手机号，未绑号即无法联系。 */
    private final com.jbk.serve.service.mini.auth.MiniPhoneGate phoneGate;
    private final WsDeviceMapper deviceMapper;
    private final WsStationMapper stationMapper;
    private final WsDeviceOutletMapper outletMapper;
    private final WsDeviceTelemetryMapper telemetryMapper;
    private final WsUserMapper userMapper;
    private final WsOrderMapper orderMapper;
    private final IWorkOrderService workOrderService;
    private final IWsDomainEventService domainEventService;

    @Override
    public List<MiniOwnerDeviceVo> listDevices(Long userId) {
        List<WsDevice> devices = deviceMapper.selectList(Wrappers.lambdaQuery(WsDevice.class)
                .eq(WsDevice::getOwnerUserId, userId)
                .orderByAsc(WsDevice::getId));
        Map<Long, String> stationNames = stationNamesOf(devices);
        return devices.stream()
                .map(device -> toSummary(device, ObjectUtil.isNull(device.getStationId())
                        ? null : stationNames.get(device.getStationId())))
                .collect(Collectors.toList());
    }

    @Override
    public MiniOwnerDeviceVo deviceDetail(String deviceNo, Long userId) {
        WsDevice device = requireOwnedDevice(deviceNo, userId);
        MiniOwnerDeviceVo vo = toSummary(device, ObjectUtil.isNull(device.getStationId())
                ? null : stationNamesOf(List.of(device)).get(device.getStationId()));
        vo.setSimStatus(device.getSimStatus());
        vo.setSimExpireTime(device.getSimExpireTime());
        WsDeviceTelemetry telemetry = telemetryMapper.selectList(Wrappers.lambdaQuery(WsDeviceTelemetry.class)
                        .eq(WsDeviceTelemetry::getDeviceId, device.getId())
                        .orderByDesc(WsDeviceTelemetry::getId)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        if (ObjectUtil.isNotNull(telemetry)) {
            vo.setTds(telemetry.getTdsValue());
            vo.setTemperatureCelsius(telemetry.getWaterTemp());
            vo.setSignalDbm(telemetry.getSignalStrength());
            vo.setReportTime(telemetry.getReportTime());
        }
        List<WsDeviceOutlet> outlets = outletMapper.selectList(Wrappers.lambdaQuery(WsDeviceOutlet.class)
                .eq(WsDeviceOutlet::getDeviceId, device.getId())
                .orderByAsc(WsDeviceOutlet::getOutletNo));
        vo.setOutlets(outlets.stream().map(outlet -> new OutletSummaryVo()
                .setOutletId(outlet.getId())
                .setOutletNo(outlet.getOutletNo())
                .setWaterTypeId(outlet.getWaterTypeId())
                .setWaterTypeName(outlet.getWaterType())
                .setUnitPriceFenPerLiter(parsePriceOrNull(outlet.getOutletPrice()))
                .setAvailable(ObjectUtil.equal(outlet.getOutletStatus(), 1))).collect(Collectors.toList()));
        return vo;
    }

    @Override
    public MiniOwnerServiceVo applyService(MiniOwnerBo bo, Long userId) {
        if (StrUtil.hasBlank(bo.getRequestId(), bo.getDeviceNo(), bo.getServiceType(), bo.getDescription())) {
            throw new JbkException("申报信息不完整");
        }
        // 联系电话只做格式校验即弃：回显永远用账号注册手机号脱敏，防止申报电话成为第二身份源
        if (StrUtil.isNotBlank(bo.getContactPhone()) && !bo.getContactPhone().matches("^1\\d{10}$")) {
            throw new JbkException("联系电话格式不合法");
        }
        // 绑号闸：账号手机号是工单唯一联系方式，未绑号即空联系人；修法是要求绑号，
        // 不是启用申报电话（那会造出第二身份源）
        phoneGate.requirePhoneBound(userId, "机主报修申报");
        WsDevice device = requireOwnedDevice(bo.getDeviceNo(), userId);
        WorkOrderApplyBo apply = new WorkOrderApplyBo();
        apply.setRequestId(bo.getRequestId());
        apply.setDeviceId(device.getId());
        apply.setWorkType(serviceTypeToWorkType(bo.getServiceType()));
        apply.setOrderContent(bo.getDescription());
        apply.setOrderPhotos(bo.getEvidenceRefs());
        Long workOrderId = workOrderService.ownerApply(apply, userId);
        WsWorkOrder order = workOrderService.getById(workOrderId);
        if (ObjectUtil.isNull(order)) {
            throw new JbkException("申报创建异常，请重试");
        }
        return toServiceVo(order, device.getDeviceNo(), maskedPhoneOf(userId), false);
    }

    @Override
    public List<MiniOwnerServiceVo> listServiceRequests(Long userId) {
        List<WsWorkOrder> orders = workOrderService.list(Wrappers.lambdaQuery(WsWorkOrder.class)
                .eq(WsWorkOrder::getApplicantUserId, userId)
                .orderByDesc(WsWorkOrder::getId));
        String maskedPhone = maskedPhoneOf(userId);
        Map<Long, String> deviceNos = deviceNosOf(orders);
        // deviceId 可空且空集分支返回 Map.of()（不可变映射 get(null) 抛 NPE），先判空再取
        return orders.stream()
                .map(order -> toServiceVo(order, ObjectUtil.isNull(order.getDeviceId())
                        ? null : deviceNos.get(order.getDeviceId()), maskedPhone, false))
                .collect(Collectors.toList());
    }

    @Override
    public MiniOwnerServiceVo serviceDetail(String requestId, Long userId) {
        if (StrUtil.isBlank(requestId)) {
            throw new JbkException("申请不存在");
        }
        WsWorkOrder order = workOrderService.getOne(Wrappers.lambdaQuery(WsWorkOrder.class)
                .eq(WsWorkOrder::getRequestId, requestId));
        // 非本人申报按不存在处理（存在性不泄露）
        if (ObjectUtil.isNull(order) || ObjectUtil.notEqual(order.getApplicantUserId(), userId)) {
            throw new JbkException("申请不存在");
        }
        return toServiceVo(order, deviceNoOf(order), maskedPhoneOf(userId), true);
    }

    // ==================== E2E-06 经营数据（订单口径毛额，纯只读） ====================

    /** 归属双轨范围（本文件内经营数据的唯一权威解析结果） */
    private record OwnerScope(java.util.Set<Long> stationIds, java.util.Set<Long> deviceIds,
                              java.util.Map<Long, WsDevice> devicesById) {
        boolean isEmpty() {
            return stationIds.isEmpty() && deviceIds.isEmpty();
        }
    }

    /**
     * 双轨范围解析——概览与明细共用的单一出处（任务书 3.2）。
     * 站轨 = ws_station.OWNER_USER_ID；设备轨 = ws_device.OWNER_USER_ID。
     */
    private OwnerScope resolveScope(Long userId) {
        java.util.Set<Long> stationIds = stationMapper.selectList(Wrappers.lambdaQuery(WsStation.class)
                        .eq(WsStation::getOwnerUserId, userId)).stream()
                .map(WsStation::getId).collect(Collectors.toSet());
        Map<Long, WsDevice> devices = deviceMapper.selectList(Wrappers.lambdaQuery(WsDevice.class)
                        .eq(WsDevice::getOwnerUserId, userId)).stream()
                .collect(Collectors.toMap(WsDevice::getId, java.util.function.Function.identity()));
        return new OwnerScope(stationIds, devices.keySet(), devices);
    }

    /**
     * 订单计入条件——概览与明细共用（任务书 3.1/3.2 冻结口径）：
     * 排除待支付/已取消；充值单显式排除（其 STATION_ID 本为 NULL，显式排除防未来带站）；
     * 双轨 OR：站轨命中或设备轨命中即计入，同单天然只计一次（同一行）。
     */
    private com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<WsOrder> analyticsQuery(
            OwnerScope scope, String periodStart, String periodEnd) {
        // 用字符串列 QueryWrapper 而非 Lambda：概览的聚合列（COUNT/SUM）只有它能 select
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<WsOrder> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        wrapper.ne("ORDER_TYPE", TradeEnum.OrderType.CARD.getValue())
                .notIn("ORDER_STATUS", TradeEnum.OrderStatus.UNPAID.getValue(),
                        TradeEnum.OrderStatus.CANCELLED.getValue());
        // 时间窗可空：明细「全部」档不限时间（两端皆空才算全量）。概览恒传窗，不走这条分支。
        // 若强行给「全部」补近7日缺省，页面标称全部却只回7日——那是虚假展示。
        if (StrUtil.isNotBlank(periodStart) && StrUtil.isNotBlank(periodEnd)) {
            wrapper.between("CREATE_TIME", periodStart, periodEnd);
        } else if (StrUtil.isNotBlank(periodStart)) {
            wrapper.ge("CREATE_TIME", periodStart);
        } else if (StrUtil.isNotBlank(periodEnd)) {
            wrapper.le("CREATE_TIME", periodEnd);
        }
        boolean hasStations = !scope.stationIds().isEmpty();
        boolean hasDevices = !scope.deviceIds().isEmpty();
        if (hasStations && hasDevices) {
            wrapper.and(w -> w.in("STATION_ID", scope.stationIds())
                    .or().in("DEVICE_ID", scope.deviceIds()));
        } else if (hasStations) {
            wrapper.in("STATION_ID", scope.stationIds());
        } else {
            wrapper.in("DEVICE_ID", scope.deviceIds());
        }
        return wrapper;
    }

    /** 近 7 自然日含今日的起点（服务器 Asia/Shanghai 时钟，任务书 3.3）；时点由调用方给定以保证同源。 */
    private String periodStartOf(java.util.Date clock) {
        return DateUtils.timeTransition(DateUtils.addDateDays(clock, -6)).substring(0, 8) + "000000";
    }

    private void requireScope(OwnerScope scope) {
        if (scope.isEmpty()) {
            // 沿 OWNER_SCOPE_DENIED 语义：无归属账号明确拒绝，不返回空数据冒充"没生意"
            throw new JbkException("机主授权范围为空，默认不可访问经营数据");
        }
    }

    @Override
    public MiniOwnerOverviewVo overview(Long userId) {
        OwnerScope scope = resolveScope(userId);
        requireScope(scope);
        // 单次时钟快照：periodStart 与 periodEnd 必须来自同一时点，否则跨零点会出现
        // 「起点已跳到新一天、终点还是昨天」的错位窗口
        java.util.Date clock = new java.util.Date();
        String periodStart = periodStartOf(clock);
        String periodEnd = DateUtils.timeTransition(clock);

        // 设备指标按设备轨；未知运行状态不计故障（fail-closed 展示归 DeviceAvailability，这里只计数）
        int online = 0;
        int fault = 0;
        for (WsDevice device : scope.devicesById().values()) {
            if (ObjectUtil.equal(device.getOnlineStatus(), DeviceEnum.OnlineStatus.ONLINE.getValue())) {
                online++;
            }
            if (ObjectUtil.equal(device.getRunStatus(), DeviceEnum.RunStatus.FAULT.getValue())) {
                fault++;
            }
        }
        // stationCount = 名下站 ∪ 名下设备挂靠站（去重，任务书 3.2）
        java.util.Set<Long> allStations = new java.util.HashSet<>(scope.stationIds());
        scope.devicesById().values().forEach(device -> {
            if (ObjectUtil.isNotNull(device.getStationId())) {
                allStations.add(device.getStationId());
            }
        });

        // 订单聚合：单条 SQL SUM（IFNULL 防空集 NULL）；周期与计入口径同明细共用
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<WsOrder> aggQuery =
                analyticsQuery(scope, periodStart, periodEnd);
        aggQuery.select("COUNT(*) AS cnt", "IFNULL(SUM(ORDER_AMOUNT),0) AS amt", "IFNULL(SUM(ACTUAL_ML),0) AS ml",
                "IFNULL(SUM(CASE WHEN PAY_WAY = " + TradeEnum.PayWay.CARD_ML.getValue()
                        + " THEN IFNULL(ACTUAL_ML,0) ELSE 0 END),0) AS prepaid_ml");
        Map<String, Object> agg = orderMapper.selectMaps(aggQuery).stream()
                .filter(ObjectUtil::isNotNull).findFirst().orElse(Map.of());

        return new MiniOwnerOverviewVo()
                .setPeriodStart(periodStart)
                .setPeriodEnd(periodEnd)
                .setStationCount(allStations.size())
                .setDeviceCount(scope.deviceIds().size())
                .setOnlineCount(online)
                .setOfflineCount(scope.deviceIds().size() - online)
                .setFaultCount(fault)
                .setOrderCount(agg.get("cnt") == null ? 0 : Integer.parseInt(String.valueOf(agg.get("cnt"))))
                .setActualVolumeMl(agg.get("ml") == null ? 0L : Long.parseLong(String.valueOf(agg.get("ml"))))
                .setOrderAmountFen(agg.get("amt") == null ? 0L : Long.parseLong(String.valueOf(agg.get("amt"))))
                .setPrepaidVolumeMl(agg.get("prepaid_ml") == null ? 0L
                        : Long.parseLong(String.valueOf(agg.get("prepaid_ml"))))
                .setEvidenceMode("real");
    }

    @Override
    public PageDataVo<MiniOwnerTransactionVo> transactionPage(MiniOwnerTransactionBo bo, Long userId) {
        OwnerScope scope = resolveScope(userId);
        requireScope(scope);
        long current = ObjectUtil.defaultIfNull(bo.getCurrent(), 1L);
        long size = ObjectUtil.defaultIfNull(bo.getSize(), 20L);
        if (current <= 0 || size <= 0) {
            throw new JbkException("分页参数必须大于 0");
        }
        if (size > 100) {
            throw new JbkException("页大小超出上限 100");
        }
        // current 不设上限时，(current-1)*size 在 Page.offset() 里可 long 溢出为负 LIMIT，
        // 以 SQLException/500 收场而非干净的参数拒绝。10 万页 × 100 条远超任何真实明细量。
        if (current > 100_000) {
            throw new JbkException("页码超出上限");
        }
        // 明细的周期为「筛选条件」而非「统计口径」：不传即不限时间（对应页面「全部」档）。
        // 概览有固定统计周期（近7日）并随响应回显，二者语义不同，不可互相套用缺省值。
        String periodStart = StrUtil.trimToEmpty(bo.getPeriodStart());
        String periodEnd = StrUtil.trimToEmpty(bo.getPeriodEnd());
        if ((StrUtil.isNotBlank(periodStart) && !periodStart.matches("^\\d{14}$"))
                || (StrUtil.isNotBlank(periodEnd) && !periodEnd.matches("^\\d{14}$"))) {
            throw new JbkException("时间格式必须是 yyyyMMddHHmmss");
        }

        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<WsOrder> wrapper =
                analyticsQuery(scope, periodStart, periodEnd);
        if (StrUtil.isNotBlank(bo.getDeviceNo())) {
            // deviceNo 筛选必须命中名下设备（设备轨）；越权与不存在同文案，不泄露存在性
            WsDevice filter = scope.devicesById().values().stream()
                    .filter(device -> bo.getDeviceNo().equals(device.getDeviceNo()))
                    .findFirst().orElse(null);
            if (ObjectUtil.isNull(filter)) {
                throw new JbkException("设备不在当前机主授权范围");
            }
            wrapper.eq("DEVICE_ID", filter.getId());
        }
        wrapper.orderByDesc("CREATE_TIME").orderByDesc("ID");

        Page<WsOrder> page = orderMapper.selectPage(new Page<>(current, size), wrapper);
        java.util.Set<Long> stationIds = page.getRecords().stream().map(WsOrder::getStationId)
                .filter(ObjectUtil::isNotNull).collect(Collectors.toSet());
        Map<Long, String> stationNames = stationIds.isEmpty() ? Map.of()
                : stationMapper.selectBatchIds(stationIds).stream()
                        .collect(Collectors.toMap(WsStation::getId, WsStation::getStationName));
        java.util.Set<Long> deviceIds = page.getRecords().stream().map(WsOrder::getDeviceId)
                .filter(ObjectUtil::isNotNull).collect(Collectors.toSet());
        Map<Long, String> deviceNos = deviceIds.isEmpty() ? Map.of()
                : deviceMapper.selectBatchIds(deviceIds).stream()
                        .collect(Collectors.toMap(WsDevice::getId, WsDevice::getDeviceNo));

        List<MiniOwnerTransactionVo> rows = page.getRecords().stream().map(order -> new MiniOwnerTransactionVo()
                .setOrderNo(order.getOrderNo())
                .setOrderType(order.getOrderType())
                .setOrderStatus(order.getOrderStatus())
                .setStationId(order.getStationId())
                .setStationName(ObjectUtil.isNull(order.getStationId()) ? "未命名水站"
                        : StrUtil.blankToDefault(stationNames.get(order.getStationId()), "未命名水站"))
                .setDeviceNo(ObjectUtil.isNull(order.getDeviceId()) ? null : deviceNos.get(order.getDeviceId()))
                .setActualVolumeMl(order.getActualMl())
                .setOrderAmountFen(order.getOrderAmount())
                .setCreateTime(order.getCreateTime())).collect(Collectors.toList());
        return PageDataVo.getPageData(rows, page.getTotal());
    }

    // ------------------------------------------------------------------
    // 装配
    // ------------------------------------------------------------------

    private WsDevice requireOwnedDevice(String deviceNo, Long userId) {
        if (StrUtil.isBlank(deviceNo)) {
            throw new JbkException("设备不存在");
        }
        WsDevice device = deviceMapper.selectOne(Wrappers.lambdaQuery(WsDevice.class)
                .eq(WsDevice::getDeviceNo, deviceNo));
        if (ObjectUtil.isNull(device) || ObjectUtil.notEqual(device.getOwnerUserId(), userId)) {
            throw new JbkException("设备不存在");
        }
        return device;
    }

    private MiniOwnerDeviceVo toSummary(WsDevice device, String stationName) {
        return new MiniOwnerDeviceVo()
                .setDeviceNo(device.getDeviceNo())
                .setDeviceName(device.getDeviceName())
                .setStationId(device.getStationId())
                .setStationName(StrUtil.blankToDefault(stationName, "未命名水站"))
                .setOnlineStatus(ObjectUtil.equal(device.getOnlineStatus(),
                        DeviceEnum.OnlineStatus.ONLINE.getValue()) ? "ONLINE" : "OFFLINE")
                .setRunStatus(mapRunStatus(device.getRunStatus()))
                .setLastHeartbeat(device.getLastHeartbeat())
                .setLastFaultCode(device.getLastFaultCode());
    }

    /** 契约五值之外（含 null）一律按 FAULT 展示：展示层 fail-closed，引导联系运维而不是伪装空闲。 */
    private String mapRunStatus(Integer runStatus) {
        if (ObjectUtil.equal(runStatus, DeviceEnum.RunStatus.IDLE.getValue())) {
            return "IDLE";
        }
        if (ObjectUtil.equal(runStatus, DeviceEnum.RunStatus.DISPENSING.getValue())) {
            return "DISPENSING";
        }
        if (ObjectUtil.equal(runStatus, DeviceEnum.RunStatus.MAINTAIN.getValue())) {
            return "MAINTENANCE";
        }
        if (ObjectUtil.equal(runStatus, DeviceEnum.RunStatus.LOCKED.getValue())) {
            return "LOCKED";
        }
        if (ObjectUtil.equal(runStatus, DeviceEnum.RunStatus.FAULT.getValue())) {
            return "FAULT";
        }
        return "FAULT";
    }

    private Map<Long, String> stationNamesOf(List<WsDevice> devices) {
        var stationIds = devices.stream().map(WsDevice::getStationId)
                .filter(ObjectUtil::isNotNull).collect(Collectors.toSet());
        if (stationIds.isEmpty()) {
            return Map.of();
        }
        return stationMapper.selectBatchIds(stationIds).stream()
                .collect(Collectors.toMap(WsStation::getId, WsStation::getStationName));
    }

    private Map<Long, String> deviceNosOf(List<WsWorkOrder> orders) {
        var deviceIds = orders.stream().map(WsWorkOrder::getDeviceId)
                .filter(ObjectUtil::isNotNull).collect(Collectors.toSet());
        if (deviceIds.isEmpty()) {
            return Map.of();
        }
        return deviceMapper.selectBatchIds(deviceIds).stream()
                .collect(Collectors.toMap(WsDevice::getId, WsDevice::getDeviceNo));
    }

    private String deviceNoOf(WsWorkOrder order) {
        if (ObjectUtil.isNull(order.getDeviceId())) {
            return null;
        }
        WsDevice device = deviceMapper.selectById(order.getDeviceId());
        return ObjectUtil.isNull(device) ? null : device.getDeviceNo();
    }

    private String maskedPhoneOf(Long userId) {
        WsUser user = userMapper.selectById(userId);
        return ObjectUtil.isNull(user) ? "-" : PhoneMask.mask(user.getUserPhone());
    }

    private int serviceTypeToWorkType(String serviceType) {
        if ("REPAIR".equals(serviceType)) {
            return OpsEnum.WorkOrderType.REPAIR.getValue();
        }
        if ("PART".equals(serviceType)) {
            return OpsEnum.WorkOrderType.PARTS.getValue();
        }
        throw new JbkException("服务类型不合法");
    }

    private String workTypeToServiceType(Integer workType) {
        return ObjectUtil.equal(workType, OpsEnum.WorkOrderType.PARTS.getValue()) ? "PART" : "REPAIR";
    }

    /** 六状态收敛为机主侧四态：待确认→待受理；分配前后到复核都是处理中；关闭→完成；驳回→拒绝。 */
    private String mapServiceStatus(Integer orderStatus) {
        OpsEnum.WorkOrderStatus status = OpsEnum.WorkOrderStatus.getType(orderStatus);
        switch (status) {
            case WAIT_CONFIRM:
                return "PENDING_ACCEPTANCE";
            case CLOSED:
                return "COMPLETED";
            case REJECTED:
                return "REJECTED";
            default:
                return "PROCESSING";
        }
    }

    private MiniOwnerServiceVo toServiceVo(WsWorkOrder order, String deviceNo, String maskedPhone,
                                           boolean withTrace) {
        MiniOwnerServiceVo vo = new MiniOwnerServiceVo()
                .setRequestId(order.getRequestId())
                .setAccountId(order.getApplicantUserId())
                .setWorkOrderNo(order.getOrderNo())
                .setDeviceNo(deviceNo)
                .setServiceType(workTypeToServiceType(order.getWorkType()))
                .setDescription(order.getOrderContent())
                .setEvidenceRefs(parseKeys(order.getOrderPhotos()))
                .setMaskedContactPhone(maskedPhone)
                .setStatus(mapServiceStatus(order.getOrderStatus()))
                .setWorkOrderStatus(order.getOrderStatus())
                .setRejectReason(order.getRejectReason())
                .setFinishResult(order.getFinishResult())
                .setCreateTime(order.getCreateTime())
                .setEvidenceMode("external-snapshot");
        if (withTrace) {
            List<WsDomainEvent> events = domainEventService.list(Wrappers.lambdaQuery(WsDomainEvent.class)
                    .eq(WsDomainEvent::getEventType, OpsEnum.EventType.WORK_ORDER_STATUS.getValue())
                    .eq(WsDomainEvent::getEventKey, order.getOrderNo())
                    .orderByAsc(WsDomainEvent::getId));
            vo.setTrace(events.stream().map(event -> new MiniOwnerServiceTraceVo()
                    .setEventTime(event.getCreateTime())
                    .setActorLabel(actorLabelOf(event.getActorPortal()))
                    .setDetail(payloadText(event.getEventPayload()))).collect(Collectors.toList()));
        }
        return vo;
    }

    /** 机主侧轨迹只区分平台/机主/系统，不暴露内部员工身份 */
    private String actorLabelOf(Integer actorPortal) {
        if (ObjectUtil.equal(actorPortal, OpsEnum.ActorPortal.OWNER.getValue())
                || ObjectUtil.equal(actorPortal, OpsEnum.ActorPortal.USER.getValue())) {
            return "机主";
        }
        if (ObjectUtil.equal(actorPortal, OpsEnum.ActorPortal.SYSTEM.getValue())) {
            return "系统";
        }
        return "平台";
    }

    private String payloadText(String payload) {
        if (StrUtil.isBlank(payload)) {
            return "-";
        }
        try {
            var obj = JSONUtil.parseObj(payload);
            Object newValue = obj.getObj("new", obj.getObj("newValue"));
            Object oldValue = obj.getObj("old", obj.getObj("oldValue"));
            if (ObjectUtil.isNotNull(newValue)) {
                return ObjectUtil.isNull(oldValue) ? String.valueOf(newValue) : oldValue + " → " + newValue;
            }
        } catch (Exception ignore) {
            // 非 JSON 原样返回
        }
        return payload;
    }

    private List<String> parseKeys(String raw) {
        if (StrUtil.isBlank(raw)) {
            return List.of();
        }
        try {
            return JSONUtil.parseArray(raw).toList(String.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private Integer parsePriceOrNull(String price) {
        try {
            return Integer.valueOf(StrUtil.trim(price));
        } catch (Exception e) {
            // 机主详情是只读展示：单价配置异常不阻断页面，字段留空由前端降级
            return null;
        }
    }
}
