package com.jbk.serve.service.device.impl;

import com.jbk.serve.mapper.device.WsCommandBatchMapper;
import com.jbk.serve.mapper.device.WsCommandMapper;
import com.jbk.serve.mapper.device.WsDeviceMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.service.device.DeviceParamPayload;
import com.jbk.serve.service.device.IDeviceParamService;
import com.jbk.serve.service.device.IWsCommandService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.device.DeviceEnum;
import com.jbk.tool.data.device.bo.WsCommandBatchBo;
import com.jbk.tool.data.device.po.WsDevice;
import com.jbk.tool.data.device.vo.DeviceControlPreviewVo;
import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R2-P1-2 批量确认必须按<b>最新</b>参数定义复验的行为测试。
 *
 * <p>缺陷形状：{@code preview} 校验后把 payload 冻结进 ticket，{@code confirm} 取出后直接
 * 建批次并 {@code sendAsBatchChild}。预览到确认之间若运营收紧了参数定义，
 * 旧 ticket 仍能把此刻已不合规的值发到设备上。</p>
 *
 * <p>本类刻意<b>不做源码断言</b>——{@code contains("DeviceParamPayload.require")} 那种写法
 * 只能证明字符串在文件里，证明不了它在写操作之前执行、也证明不了拒绝路径零副作用。
 * 这里用真实调用 + Mockito 交互验证：拒绝时批次、子指令、领域事件、下发四者必须全零。</p>
 */
class DeviceControlConfirmRevalidateTest {

    private static final long DEVICE_ID = 21L;
    private static final long OPERATOR = 7L;
    private static final int PARAM_SYNC = DeviceEnum.CmdType.PARAM_SYNC.getValue();
    private static final int REBOOT = DeviceEnum.CmdType.REBOOT.getValue();

    private DeviceControlServiceImpl service;
    private WsCommandBatchMapper batchMapper;
    private WsCommandMapper commandMapper;
    private WsDeviceMapper deviceMapper;
    private IWsCommandService commandService;
    private IWsDomainEventService domainEventService;
    private IDeviceParamService deviceParamService;

    /** 进程内 Redis 替身：ticket 的 SET / GETDEL 语义必须真实，否则 confirm 的前置闸测不了。 */
    private final Map<String, String> redisStore = new HashMap<>();

    @BeforeEach
    void setUp() {
        service = new DeviceControlServiceImpl();
        batchMapper = Mockito.mock(WsCommandBatchMapper.class);
        commandMapper = Mockito.mock(WsCommandMapper.class);
        deviceMapper = Mockito.mock(WsDeviceMapper.class);
        commandService = Mockito.mock(IWsCommandService.class);
        domainEventService = Mockito.mock(IWsDomainEventService.class);
        deviceParamService = Mockito.mock(IDeviceParamService.class);

        // Redis 替身只需覆盖生产真正用到的两个操作：set(key,value,ttl) 与 getAndDelete(key)。
        // GETDEL 语义必须真实——ticket 一次性领取是 confirm 的前置闸，用普通 get 替代会让
        // 「拒绝后 ticket 已作废」这条断言变成假的。
        @SuppressWarnings("unchecked")
        RedisTemplate<String, String> redis = Mockito.mock(RedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        Mockito.doAnswer(inv -> {
            redisStore.put(inv.getArgument(0), String.valueOf((Object) inv.getArgument(1)));
            return null;
        }).when(ops).set(anyString(), anyString(), anyLong(), any(java.util.concurrent.TimeUnit.class));
        Mockito.doAnswer(inv -> {
            redisStore.put(inv.getArgument(0), String.valueOf((Object) inv.getArgument(1)));
            return null;
        }).when(ops).set(anyString(), anyString());
        when(ops.getAndDelete(anyString()))
                .thenAnswer(inv -> redisStore.remove(inv.<String>getArgument(0)));

        ReflectionTestUtils.setField(service, "batchMapper", batchMapper);
        ReflectionTestUtils.setField(service, "commandMapper", commandMapper);
        ReflectionTestUtils.setField(service, "deviceMapper", deviceMapper);
        ReflectionTestUtils.setField(service, "commandService", commandService);
        ReflectionTestUtils.setField(service, "domainEventService", domainEventService);
        ReflectionTestUtils.setField(service, "deviceParamService", deviceParamService);
        ReflectionTestUtils.setField(service, "wsOrderMapper", Mockito.mock(WsOrderMapper.class));
        ReflectionTestUtils.setField(service, "redis", redis);
        ReflectionTestUtils.setField(service, "ticketTtlSeconds", 300L);

        when(deviceMapper.selectList(any())).thenReturn(List.of(device()));
        when(deviceMapper.selectBatchIds(any())).thenReturn(List.of(device()));
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device());
        // 默认：注册表为空 —— 未登记键放行（保持 E2E-05 已验收的既有能力）
        when(deviceParamService.enabledDefinitions(anyInt())).thenReturn(Map.of());
    }

    private WsDevice device() {
        return new WsDevice().setId(DEVICE_ID).setDeviceNo("DK-DEV-0021").setStationId(41L);
    }

    private WsCommandBatchBo bo(int cmdType, String payload) {
        WsCommandBatchBo bo = new WsCommandBatchBo();
        bo.setCmdType(cmdType);
        bo.setCmdPayload(payload);
        bo.setScopeType(1);
        bo.setDeviceIds(List.of(DEVICE_ID));
        return bo;
    }

    /** 收紧定义：limitMl 必须是 100~20000 的整数。 */
    private void tightenDefinition() {
        when(deviceParamService.enabledDefinitions(PARAM_SYNC)).thenReturn(Map.of(
                "limitMl", new DeviceParamPayload.Definition(
                        "limitMl", DeviceParamPayload.TYPE_INT, "毫升", "100", "20000", null)));
    }

    private void assertZeroSideEffects() {
        verify(batchMapper, never()).insert(Mockito.<com.jbk.tool.data.device.po.WsCommandBatch>any());
        verify(commandService, never()).sendAsBatchChild(any(), any(), anyInt(), anyString(), any());
        verify(domainEventService, never()).record(any(), anyString(), any(), any());
        verify(commandMapper, never()).insert(Mockito.<com.jbk.tool.data.device.po.WsCommand>any());
    }

    // ==================== 核心：预览通过 → 定义收紧 → 确认必须拒绝 ====================

    @Test
    void confirmRejectsWhenDefinitionTightenedAfterPreviewAndLeavesZeroSideEffects() {
        // 预览时注册表为空，未登记键 limitMl 放行（证明既有能力没被打断）
        DeviceControlPreviewVo preview = service.preview(bo(PARAM_SYNC, "{\"limitMl\":99999}"), OPERATOR);
        assertNotNull(preview.getOperationTicket());

        // 预览之后、确认之前：运营补登记了 limitMl 且上限 20000
        tightenDefinition();

        WsCommandBatchBo confirmBo = bo(PARAM_SYNC, null);
        confirmBo.setOperationTicket(preview.getOperationTicket());
        confirmBo.setTargetDigest(preview.getTargetDigest());
        confirmBo.setParamDigest(preview.getParamDigest());

        JbkException rejected = assertThrows(JbkException.class,
                () -> service.confirm(confirmBo, OPERATOR));
        assertTrue(rejected.getMsg().contains("上限"), "拒因必须是值域，实际=" + rejected.getMsg());

        // 四类写操作全零：批次、子指令、领域事件、指令行
        assertZeroSideEffects();
    }

    @Test
    void confirmProceedsWhenDefinitionUnchanged() {
        DeviceControlPreviewVo preview = service.preview(bo(PARAM_SYNC, "{\"limitMl\":5000}"), OPERATOR);

        WsCommandBatchBo confirmBo = bo(PARAM_SYNC, null);
        confirmBo.setOperationTicket(preview.getOperationTicket());
        confirmBo.setTargetDigest(preview.getTargetDigest());
        confirmBo.setParamDigest(preview.getParamDigest());

        Mockito.doAnswer(inv -> {
            inv.<com.jbk.tool.data.device.po.WsCommandBatch>getArgument(0).setId(900L);
            return 1;
        }).when(batchMapper).insert(Mockito.<com.jbk.tool.data.device.po.WsCommandBatch>any());

        Long batchId = service.confirm(confirmBo, OPERATOR);

        assertEquals(900L, batchId);
        verify(batchMapper).insert(Mockito.<com.jbk.tool.data.device.po.WsCommandBatch>any());
        verify(commandService).sendAsBatchChild(any(), any(), anyInt(), anyString(), any());
    }

    @Test
    void confirmStillRejectsWhenValueViolatesNewlyRegisteredDefinition() {
        // 预览时值就已越界但未登记 → 放行；确认时登记生效 → 必须拒
        DeviceControlPreviewVo preview = service.preview(bo(PARAM_SYNC, "{\"limitMl\":1}"), OPERATOR);
        tightenDefinition();

        WsCommandBatchBo confirmBo = bo(PARAM_SYNC, null);
        confirmBo.setOperationTicket(preview.getOperationTicket());
        confirmBo.setTargetDigest(preview.getTargetDigest());
        confirmBo.setParamDigest(preview.getParamDigest());

        assertThrows(JbkException.class, () -> service.confirm(confirmBo, OPERATOR));
        assertZeroSideEffects();
    }

    // ==================== 非参数类批次行为不变 ====================

    @Test
    void nonParamBearingBatchIsUnaffectedByDefinitions() {
        tightenDefinition();
        DeviceControlPreviewVo preview = service.preview(bo(REBOOT, null), OPERATOR);

        WsCommandBatchBo confirmBo = bo(REBOOT, null);
        confirmBo.setOperationTicket(preview.getOperationTicket());
        confirmBo.setTargetDigest(preview.getTargetDigest());
        confirmBo.setParamDigest(preview.getParamDigest());

        Mockito.doAnswer(inv -> {
            inv.<com.jbk.tool.data.device.po.WsCommandBatch>getArgument(0).setId(901L);
            return 1;
        }).when(batchMapper).insert(Mockito.<com.jbk.tool.data.device.po.WsCommandBatch>any());

        assertEquals(901L, service.confirm(confirmBo, OPERATOR));
        // 重启指令不承载参数，绝不该去查参数定义
        verify(deviceParamService, never()).enabledDefinitions(REBOOT);
    }

    // ==================== 复验必须读「当前」定义，不得复用预览时的快照 ====================

    @Test
    void confirmReReadsDefinitionsRatherThanReusingPreviewSnapshot() {
        service.preview(bo(PARAM_SYNC, "{\"limitMl\":5000}"), OPERATOR);
        // preview 调过一次；confirm 必须再调一次，否则「按最新定义复验」无从谈起
        verify(deviceParamService).enabledDefinitions(PARAM_SYNC);
    }
}
