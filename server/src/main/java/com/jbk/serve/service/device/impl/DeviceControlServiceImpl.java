package com.jbk.serve.service.device.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jbk.serve.mapper.device.WsCommandBatchMapper;
import com.jbk.serve.mapper.device.WsCommandMapper;
import com.jbk.serve.mapper.device.WsDeviceMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.service.device.IDeviceControlService;
import com.jbk.serve.service.device.IWsCommandService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.config.system.redis.utils.RedisUtils;
import com.jbk.tool.consts.device.DeviceEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.device.bo.WsCommandBatchBo;
import com.jbk.tool.data.device.po.WsCommand;
import com.jbk.tool.data.device.po.WsCommandBatch;
import com.jbk.tool.data.device.po.WsDevice;
import com.jbk.tool.data.device.vo.DeviceControlPreviewVo;
import com.jbk.tool.data.device.vo.WsCommandBatchVo;
import com.jbk.tool.data.device.vo.WsCommandVo;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import com.jbk.tool.utils.OptionalUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import com.jbk.serve.service.device.DeviceCommandRisk;
import com.jbk.tool.utils.satoken.StpKit;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 高风险设备控制实现（E2E-05 包B）。confirm 不重新解析目标：运营确认的就是 preview 冻结的
 * 快照，窗口期消失的个别目标在展开时按失败计入聚合。崩溃窗口：展开中途崩溃时批次以
 * 「已展开子指令 + 已直记失败」收敛，未展开目标零下发——宁可少发绝不多发。
 *
 * @author dakang
 * @since 2026-07-30
 */
@Slf4j
@Service
public class DeviceControlServiceImpl implements IDeviceControlService {

    /** 操作凭据 Redis 键前缀 */
    private static final String TICKET_KEY_PREFIX = "device:ctl:ticket:";
    /** 凭据时效（秒）：默认 120——足够运营核对目标数量，不够囤票跨班次使用；验收环境可调短以验证过期拒绝 */
    @org.springframework.beans.factory.annotation.Value("${dakang.device.control-ticket-ttl-seconds:120}")
    private long ticketTtlSeconds;
    /** 批量允许的指令类型：3查询 4锁机 5解锁 6参数同步 7重启 8价格同步（出水类 1 永不允许） */
    private static final Set<Integer> BATCH_ALLOWED_TYPES = Set.of(
            DeviceEnum.CmdType.QUERY_STATUS.getValue(),
            DeviceEnum.CmdType.LOCK.getValue(),
            DeviceEnum.CmdType.UNLOCK.getValue(),
            DeviceEnum.CmdType.PARAM_SYNC.getValue(),
            DeviceEnum.CmdType.REBOOT.getValue(),
            DeviceEnum.CmdType.PRICE_SYNC.getValue());
    /** 必须携带合法 JSON 参数的指令类型 */
    private static final Set<Integer> JSON_PAYLOAD_TYPES = Set.of(
            DeviceEnum.CmdType.PARAM_SYNC.getValue(),
            DeviceEnum.CmdType.PRICE_SYNC.getValue());

    @Autowired
    private com.jbk.serve.service.device.IDeviceParamService deviceParamService;
    @Autowired
    private WsDeviceMapper deviceMapper;
    @Autowired
    private WsOrderMapper wsOrderMapper;
    @Autowired
    private WsCommandMapper commandMapper;
    @Autowired
    private WsCommandBatchMapper batchMapper;
    @Autowired
    private IWsCommandService commandService;
    @Autowired
    private IWsDomainEventService domainEventService;
    @Resource(name = "redisTemplate1")
    private RedisTemplate<String, Object> redis;

    /**
     * 冻结凭据里的指令若属加闸档，要求当前会话处于二级认证安全期。
     *
     * <p>判据取「冻结值 ∪ 现算值」的并集而不是只信冻结值：本次改造之前签发的 ticket
     * 里没有 requireSafe 字段，只信冻结值会让部署瞬间在途的那批凭据全部免检。
     * fail-closed 的代价只是多一次口令，反过来的代价是一个免检窗口。</p>
     */
    private void requireSafeIfGated(JSONObject frozen) {
        Integer cmdType = frozen.getInt("cmdType");
        if (cmdType == null) {
            return;
        }
        boolean need = frozen.getBool("requireSafe", false)
                || DeviceCommandRisk.requireSafe(cmdType, frozen.getInt("scopeType"));
        if (need) {
            // 抛 Sa-Token 原生异常，由 GlobalExceptionHandler 统一映射成 1440，
            // 前端据此就地弹口令框并原样重放——不自造第二种「需要二次认证」的表达
            StpKit.MANAGE.checkSafe();
        }
    }

    @Override
    public DeviceControlPreviewVo preview(WsCommandBatchBo bo, Long operatorId) {
        boolean emergencyStop = DeviceEnum.CmdType.STOP_DISPENSE.getValue() == bo.getCmdType();
        if (!emergencyStop && !BATCH_ALLOWED_TYPES.contains(bo.getCmdType())) {
            throw new JbkException("出水类指令必须由订单链路触发，批量控制不受理");
        }
        String payload = StrUtil.blankToDefault(bo.getCmdPayload(), "{}");
        // 参数类指令走与单发完全相同的校验实现（REQ-213）：结构校验恒生效，已登记键额外校验类型与值域
        requireParamPayloadAgainstLatestDefinitions(bo.getCmdType(), bo.getCmdPayload());

        List<WsDevice> targets = resolveTargets(bo, emergencyStop);
        Long orderId = null;
        String activeOrderNo = null;
        if (emergencyStop) {
            // 紧急停止必须锚定服务端确认的活动出水链（任务书 3.4）：设备 + 出水中订单 + 出水口齐备
            WsOrder active = findActiveDispensingOrder(targets.get(0).getId());
            OptionalUtils.nullToElseThrow(active, "该设备当前不存在活动出水链，拒绝紧急停止");
            orderId = active.getId();
            activeOrderNo = active.getOrderNo();
            JSONObject stopPayload = new JSONObject();
            stopPayload.set("orderNo", active.getOrderNo());
            stopPayload.set("reason", "EMERGENCY_STOP");
            payload = stopPayload.toString();
        }

        List<Long> targetIds = targets.stream().map(WsDevice::getId).sorted().collect(Collectors.toList());
        String targetDigest = DigestUtil.sha256Hex(targetIds.stream()
                .map(String::valueOf).collect(Collectors.joining(",")));
        String paramDigest = DigestUtil.sha256Hex(bo.getCmdType() + "|" + payload);

        String ticket = "DCT" + IdUtil.fastSimpleUUID();
        JSONObject frozen = new JSONObject();
        frozen.set("operatorId", operatorId);
        frozen.set("cmdType", bo.getCmdType());
        frozen.set("scopeType", bo.getScopeType());
        frozen.set("stationId", bo.getStationId());
        frozen.set("cmdPayload", payload);
        frozen.set("targetDigest", targetDigest);
        frozen.set("paramDigest", paramDigest);
        frozen.set("deviceIds", targetIds);
        frozen.set("orderId", orderId);
        // 档位判定只在预览这一处发生，结果连同摘要一起冻进凭据；确认段只复验冻结值，
        // 不重新分档——否则两处判据一旦漂移，确认放行的就不是预览时说好的那件事
        boolean requireSafe = DeviceCommandRisk.requireSafe(bo.getCmdType(), bo.getScopeType());
        frozen.set("requireSafe", requireSafe);
        RedisUtils.set(redis, TICKET_KEY_PREFIX + ticket, frozen.toString(), ticketTtlSeconds);

        return new DeviceControlPreviewVo()
                .setOperationTicket(ticket)
                .setTicketTtlSeconds((int) ticketTtlSeconds)
                .setCmdType(bo.getCmdType())
                .setCmdTypeDesc(DeviceEnum.CmdType.getType(bo.getCmdType()).getDesc())
                .setScopeType(bo.getScopeType())
                .setTargetCount(targets.size())
                .setDeviceNos(targets.stream().map(WsDevice::getDeviceNo).sorted()
                        .limit(20).collect(Collectors.toList()))
                .setTargetDigest(targetDigest)
                .setParamDigest(paramDigest)
                .setActiveOrderNo(activeOrderNo)
                // 预览本身不拦：预览的价值是让运营先看见「我以为 37 台、实际 41 台」，
                // 把口令挡在这个信息前面是本末倒置
                .setRequireSafe(requireSafe);
    }

    @Override
    public Long confirm(WsCommandBatchBo bo, Long operatorId) {
        if (StrUtil.hasBlank(bo.getOperationTicket(), bo.getTargetDigest(), bo.getParamDigest())) {
            throw new JbkException("确认信息不完整，请重新预览");
        }
        String ticketKey = TICKET_KEY_PREFIX + bo.getOperationTicket();
        // 二次验证必须在 GETDEL **之前**判（D-423）：闸放在领取之后的话，一次未认证的确认
        // 就把凭据销毁了，运营被迫重走预览——那正好是二次验证最该避免的「假故障」。
        // 这里先窥视不销毁，闸过了再原子领取。
        Object peek = RedisUtils.get(redis, ticketKey);
        if (ObjectUtil.isNotNull(peek)) {
            requireSafeIfGated(JSONUtil.parseObj(peek.toString()));
        }
        // GETDEL 原子领取：同一 ticket 并发/重复确认只有一个赢家，其余拿到 null（任务书 3.5）
        Object raw = RedisUtils.getDel(redis, ticketKey);
        if (ObjectUtil.isNull(raw)) {
            throw new JbkException("操作凭据不存在、已过期或已使用，请重新预览");
        }
        JSONObject frozen = JSONUtil.parseObj(raw.toString());
        if (ObjectUtil.notEqual(frozen.getLong("operatorId"), operatorId)) {
            // 换人拒绝。ticket 已被 GETDEL 销毁——冒领即作废，原预览人也须重新预览
            throw new JbkException("确认人与预览人不一致，凭据已作废，请重新预览");
        }
        if (ObjectUtil.notEqual(frozen.getStr("targetDigest"), bo.getTargetDigest())
                || ObjectUtil.notEqual(frozen.getStr("paramDigest"), bo.getParamDigest())) {
            throw new JbkException("目标或参数与预览时不一致，凭据已作废，请重新预览");
        }

        int cmdType = frozen.getInt("cmdType");
        String payload = frozen.getStr("cmdPayload");
        Long orderId = frozen.getLong("orderId");
        List<Long> deviceIds = frozen.getJSONArray("deviceIds").toList(Long.class);
        if (CollUtil.isEmpty(deviceIds)) {
            throw new JbkException("凭据内目标集合为空，请重新预览");
        }
        if (ObjectUtil.isNotNull(orderId)) {
            // 紧急停止复验：窗口期内订单已离开出水中（自然完成/已被停止），不再重复下发
            WsOrder fresh = wsOrderMapper.selectById(orderId);
            if (ObjectUtil.isNull(fresh)
                    || ObjectUtil.notEqual(fresh.getOrderStatus(), TradeEnum.OrderStatus.DISPENSING.getValue())) {
                throw new JbkException("活动出水链已结束，无需紧急停止");
            }
        }

        // R2-P1-2：按**当前**参数定义复验，位置必须早于批次落库、子指令创建、领域事件与 MQTT publish。
        // ticket 冻结的是预览那一刻的 payload；预览到确认之间定义可能被收紧（运营刚补登记了值域），
        // 只信 ticket 等于让一张旧凭据把此刻已不合规的值发到设备上。
        // ticket 已被 GETDEL 消费，这里拒绝后用户必须重新预览——这正是期望行为。
        requireParamPayloadAgainstLatestDefinitions(cmdType, payload);

        String now = DateUtils.time();
        Map<Long, String> deviceNoById = deviceMapper.selectBatchIds(deviceIds).stream()
                .collect(Collectors.toMap(WsDevice::getId, WsDevice::getDeviceNo));
        JSONObject snapshot = new JSONObject();
        snapshot.set("deviceIds", deviceIds);
        snapshot.set("deviceNos", deviceIds.stream().map(deviceNoById::get)
                .filter(ObjectUtil::isNotNull).collect(Collectors.toList()));
        snapshot.set("stationId", frozen.getLong("stationId"));
        snapshot.set("orderId", orderId);
        snapshot.set("operatorId", operatorId);

        WsCommandBatch batch = new WsCommandBatch()
                .setBatchNo(generateBatchNo())
                .setScopeType(frozen.getInt("scopeType"))
                .setScopeSnapshot(snapshot.toString())
                .setCmdType(cmdType)
                .setCmdPayload(payload)
                .setParamDigest(frozen.getStr("paramDigest"))
                .setTotalCount(deviceIds.size())
                .setSuccessCount(0)
                .setFailCount(0)
                .setTimeoutCount(0)
                .setBatchStatus(1)
                .setVersion(0);
        batchMapper.insert(batch);
        domainEventService.record(OpsEnum.EventType.COMMAND_STATUS, batch.getBatchNo(),
                null, "批量任务创建（" + DeviceEnum.CmdType.getType(cmdType).getDesc()
                        + "，目标 " + deviceIds.size() + " 台）");

        for (Long deviceId : deviceIds) {
            try {
                commandService.sendAsBatchChild(batch.getId(), deviceId, cmdType, payload, orderId);
            } catch (DuplicateKeyException e) {
                // uk_cmd_batch_device：同批次同设备已展开过（重放/并发），跳过即幂等
                log.info("批次 {} 设备 {} 子指令已存在，跳过重复展开", batch.getBatchNo(), deviceId);
            } catch (JbkException e) {
                // 档案在预览→确认窗口内消失等：该设备直接按失败计入聚合，批次不因此卡在处理中
                log.warn("批次 {} 设备 {} 展开失败：{}", batch.getBatchNo(), deviceId, e.getMessage());
                batchMapper.accumulateChildTerminal(batch.getId(), 0, 1, 0, DateUtils.time());
                batchMapper.settleIfComplete(batch.getId(), DateUtils.time());
            }
        }
        return batch.getId();
    }

    /**
     * 参数类指令报文校验的唯一入口（preview 与 confirm 共用）。
     *
     * <p>规则本身不在这里——业务规则的唯一实现是 {@link com.jbk.serve.service.device.DeviceParamPayload}，
     * 本方法只负责「取当前定义 + 调它」。preview 与 confirm 各写一份判断，就会重演本改动
     * 一开始要消灭的那个问题：两处宽严一致纯属巧合。</p>
     *
     * <p>每次都<b>重新读取</b>定义而不是缓存：定义随时可能被运营收紧，
     * 拿旧定义复验等于没复验。</p>
     */
    private void requireParamPayloadAgainstLatestDefinitions(Integer cmdType, String cmdPayload) {
        if (ObjectUtil.isNull(cmdType) || !JSON_PAYLOAD_TYPES.contains(cmdType)) {
            return;
        }
        com.jbk.serve.service.device.DeviceParamPayload.require(
                cmdPayload, deviceParamService.enabledDefinitions(cmdType));
    }

    @Override
    public PageDataVo<WsCommandBatchVo> pageData(WsCommandBatchBo bo) {
        Page<WsCommandBatch> page = batchMapper.selectPage(new Page<>(bo.getCurrent(), bo.getSize()),
                Wrappers.lambdaQuery(WsCommandBatch.class)
                        .like(StrUtil.isNotBlank(bo.getBatchNo()), WsCommandBatch::getBatchNo, bo.getBatchNo())
                        .eq(ObjectUtil.isNotNull(bo.getBatchStatus()), WsCommandBatch::getBatchStatus, bo.getBatchStatus())
                        .eq(ObjectUtil.isNotNull(bo.getFilterCmdType()), WsCommandBatch::getCmdType, bo.getFilterCmdType())
                        .orderByDesc(WsCommandBatch::getId));
        List<WsCommandBatchVo> voList = page.getRecords().stream()
                .map(e -> BeanUtil.copyProperties(e, WsCommandBatchVo.class))
                .collect(Collectors.toList());
        return PageDataVo.getPageData(voList, page.getTotal());
    }

    @Override
    public WsCommandBatchVo getData(Long id) {
        WsCommandBatch batch = batchMapper.selectById(id);
        OptionalUtils.nullToElseThrow(batch, "批次不存在");
        WsCommandBatchVo vo = BeanUtil.copyProperties(batch, WsCommandBatchVo.class);
        List<WsCommand> children = commandMapper.selectList(Wrappers.lambdaQuery(WsCommand.class)
                .eq(WsCommand::getBatchId, id)
                .orderByAsc(WsCommand::getId));
        List<WsCommandVo> childVos = children.stream()
                .map(e -> BeanUtil.copyProperties(e, WsCommandVo.class))
                .collect(Collectors.toList());
        fillDeviceNo(childVos);
        vo.setCommands(childVos);
        return vo;
    }

    /** 服务端解析目标集合：指定设备逐台核验、指定水站按档案查、全部设备全量查——绝不信前端集合。 */
    private List<WsDevice> resolveTargets(WsCommandBatchBo bo, boolean emergencyStop) {
        Integer scopeType = bo.getScopeType();
        List<WsDevice> targets;
        if (ObjectUtil.equal(scopeType, 1)) {
            if (CollUtil.isEmpty(bo.getDeviceIds())) {
                throw new JbkException("指定设备模式必须提供设备集合");
            }
            List<Long> distinct = bo.getDeviceIds().stream().distinct().collect(Collectors.toList());
            targets = deviceMapper.selectBatchIds(distinct);
            if (targets.size() != distinct.size()) {
                // 任一目标不存在即整单拒绝：批量控制的目标必须全部可核验，不做「有多少发多少」
                throw new JbkException("存在无法核验的目标设备，已拒绝（提交 " + distinct.size()
                        + " 台，可核验 " + targets.size() + " 台）");
            }
        } else if (ObjectUtil.equal(scopeType, 2)) {
            OptionalUtils.nullToElseThrow(bo.getStationId(), "指定水站模式必须提供水站ID");
            targets = deviceMapper.selectList(Wrappers.lambdaQuery(WsDevice.class)
                    .eq(WsDevice::getStationId, bo.getStationId()));
        } else if (ObjectUtil.equal(scopeType, 3)) {
            targets = deviceMapper.selectList(Wrappers.lambdaQuery(WsDevice.class));
        } else {
            throw new JbkException("未知范围类型，已拒绝");
        }
        if (CollUtil.isEmpty(targets)) {
            throw new JbkException("目标集合为空，无可下发设备");
        }
        if (emergencyStop && (ObjectUtil.notEqual(scopeType, 1) || targets.size() != 1)) {
            throw new JbkException("紧急停止只允许指定单台设备");
        }
        return targets;
    }

    /** 查设备当前活动出水订单：出水中 + 设备/出水口关联齐备；多条取最新（正常业务下至多一条）。 */
    private WsOrder findActiveDispensingOrder(Long deviceId) {
        List<WsOrder> list = wsOrderMapper.selectList(Wrappers.lambdaQuery(WsOrder.class)
                .eq(WsOrder::getDeviceId, deviceId)
                .eq(WsOrder::getOrderStatus, TradeEnum.OrderStatus.DISPENSING.getValue())
                .isNotNull(WsOrder::getOutletId)
                .orderByDesc(WsOrder::getId)
                .last("LIMIT 1"));
        return CollUtil.isEmpty(list) ? null : list.get(0);
    }

    private void fillDeviceNo(List<WsCommandVo> voList) {
        if (CollUtil.isEmpty(voList)) {
            return;
        }
        Set<Long> deviceIds = voList.stream().map(WsCommandVo::getDeviceId)
                .filter(ObjectUtil::isNotNull).collect(Collectors.toSet());
        if (CollUtil.isEmpty(deviceIds)) {
            return;
        }
        Map<Long, WsDevice> byId = deviceMapper.selectBatchIds(deviceIds).stream()
                .collect(Collectors.toMap(WsDevice::getId, Function.identity()));
        voList.forEach(vo -> {
            WsDevice device = byId.get(vo.getDeviceId());
            if (ObjectUtil.isNotNull(device)) {
                vo.setDeviceNo(device.getDeviceNo());
            }
        });
    }

    /** 批次号：DCB + 时间戳 + 6 位随机数；唯一性由 uk_batch_no 约束保证。 */
    private String generateBatchNo() {
        return "DCB" + DateUtils.time() + cn.hutool.core.util.RandomUtil.randomNumbers(6);
    }
}
