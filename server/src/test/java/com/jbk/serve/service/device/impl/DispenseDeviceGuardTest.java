package com.jbk.serve.service.device.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jbk.serve.mapper.device.WsCommandMapper;
import com.jbk.serve.mapper.device.WsDeviceMapper;
import com.jbk.serve.mapper.device.WsDeviceOutletMapper;
import com.jbk.serve.mapper.device.WsFaultDictMapper;
import com.jbk.serve.service.device.DeviceAvailabilityGuard;
import com.jbk.serve.service.device.IDispenseDispatchTxService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.config.mqtt.MqttConnectionManager;
import com.jbk.tool.consts.ApiEnum;
import com.jbk.tool.consts.device.DeviceEnum;
import com.jbk.tool.data.device.po.WsCommand;
import com.jbk.tool.data.device.po.WsDevice;
import com.jbk.tool.data.device.po.WsDeviceOutlet;
import com.jbk.tool.data.device.po.WsFaultDict;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 设备可用性加载器（B20）与出水下发编排的单测。
 *
 * <p>本类钉两件事：</p>
 * <ol>
 *   <li>{@link DeviceAvailabilityGuard} 把档案与故障字典读齐后的判定结论——含
 *       同一故障码多条有效配置时必须 fail-closed（{@code FAULT_CODE} 只是普通索引，
 *       库层挡不住重复录入，取第一条会把配置污染静默压平）；</li>
 *   <li>{@code sendDispenseForOrder} 与准备事务的边界：幂等命中不下发；
 *       新建指令时 publish 必须发生在准备事务返回<b>之后</b>（MQTT 不得进事务）。</li>
 * </ol>
 * 事务内的共键、水种与抢占核验由 {@code DispenseDispatchTxDbTest} 用真库钉。
 */
class DispenseDeviceGuardTest {

    private WsDeviceMapper deviceMapper;
    private WsDeviceOutletMapper outletMapper;
    private WsFaultDictMapper faultDictMapper;
    private DeviceAvailabilityGuard guard;

    private static final long DEVICE_ID = 21L;
    private static final long OUTLET_ID = 31L;
    private static final long STATION_ID = 41L;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), WsCommand.class);
    }

    @BeforeEach
    void setUp() {
        deviceMapper = mock(WsDeviceMapper.class);
        outletMapper = mock(WsDeviceOutletMapper.class);
        faultDictMapper = mock(WsFaultDictMapper.class);
        guard = new DeviceAvailabilityGuard(deviceMapper, outletMapper, faultDictMapper);
        when(faultDictMapper.selectByCodeForShare(anyString())).thenReturn(List.of());
        when(outletMapper.selectById(OUTLET_ID)).thenReturn(outlet(1));
    }

    private WsDevice device(int onlineStatus, int runStatus, String faultCode) {
        return new WsDevice()
                .setId(DEVICE_ID)
                .setStationId(STATION_ID)
                .setDeviceNo("DK-DEV-0021")
                .setOnlineStatus(onlineStatus)
                .setRunStatus(runStatus)
                .setLastFaultCode(faultCode);
    }

    private WsDeviceOutlet outlet(int outletStatus) {
        return new WsDeviceOutlet()
                .setId(OUTLET_ID).setDeviceId(DEVICE_ID).setOutletNo(1)
                .setWaterTypeId(8L).setWaterType("纯净水").setOutletStatus(outletStatus);
    }

    private WsFaultDict fault(String code, int blockFlag) {
        return new WsFaultDict().setFaultCode(code).setFaultName("故障" + code)
                .setFaultLevel(3).setBlockOrderFlag(blockFlag);
    }

    private DeviceAvailabilityGuard.CheckedDeviceContext judgeIdleWithFaults(String code, List<WsFaultDict> rows) {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(
                device(DeviceEnum.OnlineStatus.ONLINE.getValue(), DeviceEnum.RunStatus.FAULT.getValue(), code));
        when(faultDictMapper.selectByCodeForShare(code)).thenReturn(rows);
        return guard.loadCurrent(DEVICE_ID, OUTLET_ID);
    }

    // ==================== 可用性判定 ====================

    @Test
    void offlineDeviceIsUnavailable() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(
                device(DeviceEnum.OnlineStatus.OFFLINE.getValue(), DeviceEnum.RunStatus.IDLE.getValue(), null));

        assertFalse(guard.loadCurrent(DEVICE_ID, OUTLET_ID).available());
    }

    @Test
    void maintainingDeviceIsUnavailable() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(
                device(DeviceEnum.OnlineStatus.ONLINE.getValue(), DeviceEnum.RunStatus.MAINTAIN.getValue(), null));

        assertFalse(guard.loadCurrent(DEVICE_ID, OUTLET_ID).available());
    }

    @Test
    void disabledOutletIsUnavailable() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(
                device(DeviceEnum.OnlineStatus.ONLINE.getValue(), DeviceEnum.RunStatus.IDLE.getValue(), null));
        when(outletMapper.selectById(OUTLET_ID)).thenReturn(outlet(2));

        assertFalse(guard.loadCurrent(DEVICE_ID, OUTLET_ID).available());
    }

    @Test
    void missingOutletIsUnavailableRatherThanDeviceOnlyJudgement() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(
                device(DeviceEnum.OnlineStatus.ONLINE.getValue(), DeviceEnum.RunStatus.IDLE.getValue(), null));
        when(outletMapper.selectById(OUTLET_ID)).thenReturn(null);

        DeviceAvailabilityGuard.CheckedDeviceContext checked = guard.loadCurrent(DEVICE_ID, OUTLET_ID);

        assertTrue(checked.archiveMissing());
        assertFalse(checked.available());
    }

    @Test
    void blockingFaultIsUnavailable() {
        assertFalse(judgeIdleWithFaults("E001", List.of(fault("E001", ApiEnum.Flag.YES.value()))).available());
    }

    @Test
    void unregisteredFaultCodeFailsClosed() {
        assertFalse(judgeIdleWithFaults("E999", List.of()).available());
    }

    @Test
    void registeredNonBlockingFaultStaysAvailable() {
        assertTrue(judgeIdleWithFaults("E100", List.of(fault("E100", ApiEnum.Flag.NO.value()))).available());
    }

    // ==================== P1-4：故障码重复配置 ====================

    // 一条阻断、一条不阻断：没有唯一答案，必须阻断，绝不任选其一
    @Test
    void conflictingFaultDictRowsFailClosed() {
        DeviceAvailabilityGuard.CheckedDeviceContext checked = judgeIdleWithFaults("E001",
                List.of(fault("E001", ApiEnum.Flag.NO.value()), fault("E001", ApiEnum.Flag.YES.value())));

        assertFalse(checked.available());
        assertEquals("FAULT_DICT_CONFLICT", checked.verdict().code());
        assertTrue(checked.reason().contains("配置冲突"), "拒因必须点明冲突，实际=" + checked.reason());
        assertNull(checked.fault(), "冲突时不得挑一条当作生效配置");
    }

    // 两条内容相同也是配置污染：库里就不该有第二条，同样阻断
    @Test
    void duplicateIdenticalFaultDictRowsAlsoFailClosed() {
        DeviceAvailabilityGuard.CheckedDeviceContext checked = judgeIdleWithFaults("E100",
                List.of(fault("E100", ApiEnum.Flag.NO.value()), fault("E100", ApiEnum.Flag.NO.value())));

        assertFalse(checked.available());
        assertEquals("FAULT_DICT_CONFLICT", checked.verdict().code());
    }

    // 调用方已批量读好字典时（PC 列表页避免 N+1），0/1/多行的处理仍由 Guard 决定，
    // 调用方不得先做去重或排序——那样重复配置在到达判定之前就被压平了
    @Test
    void judgeWithPreloadedFaultsKeepsConflictFailClosed() {
        WsDevice device = device(DeviceEnum.OnlineStatus.ONLINE.getValue(),
                DeviceEnum.RunStatus.FAULT.getValue(), "E001");

        DeviceAvailabilityGuard.CheckedDeviceContext conflict = guard.judgeWithFaults(device, outlet(1),
                List.of(fault("E001", ApiEnum.Flag.NO.value()), fault("E001", ApiEnum.Flag.YES.value())));
        assertFalse(conflict.available());
        assertEquals("FAULT_DICT_CONFLICT", conflict.verdict().code());

        DeviceAvailabilityGuard.CheckedDeviceContext single = guard.judgeWithFaults(device, outlet(1),
                List.of(fault("E001", ApiEnum.Flag.NO.value())));
        assertTrue(single.available(), "单条不阻断配置仍须放行，冲突判定不得误伤");
    }

    // 设备一个出水口都没有时：设备本身有问题要先报设备级原因，报「出水口缺失」会把运维引错方向
    @Test
    void outletlessDeviceStillReportsDeviceLevelReasonFirst() {
        WsDevice offline = device(DeviceEnum.OnlineStatus.OFFLINE.getValue(),
                DeviceEnum.RunStatus.IDLE.getValue(), null);

        DeviceAvailabilityGuard.CheckedDeviceContext checked =
                guard.judgeWithFaults(offline, null, List.of());

        assertFalse(checked.available());
        assertEquals("DEVICE_OFFLINE", checked.verdict().code(), "设备离线优先于出水口缺失");
    }

    @Test
    void outletlessHealthyDeviceReportsNoAvailableOutlet() {
        WsDevice healthy = device(DeviceEnum.OnlineStatus.ONLINE.getValue(),
                DeviceEnum.RunStatus.IDLE.getValue(), null);

        DeviceAvailabilityGuard.CheckedDeviceContext checked =
                guard.judgeWithFaults(healthy, null, List.of());

        assertFalse(checked.available());
        assertEquals("NO_AVAILABLE_OUTLET", checked.verdict().code());
    }

    // ==================== 下发编排：publish 必须在准备事务之后 ====================

    @SuppressWarnings("unchecked")
    private WsCommandServiceImpl commandServiceWith(IDispenseDispatchTxService prepTx,
                                                    MqttConnectionManager mqtt, WsCommandMapper commandMapper) {
        ObjectProvider<MqttConnectionManager> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mqtt);
        WsCommandServiceImpl service = new WsCommandServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", commandMapper);
        ReflectionTestUtils.setField(service, "dispatchTxService", prepTx);
        ReflectionTestUtils.setField(service, "domainEventService", mock(IWsDomainEventService.class));
        ReflectionTestUtils.setField(service, "mqttProvider", provider);
        return service;
    }

    @Test
    void publishHappensOnlyAfterPreparationTransactionReturns() {
        IDispenseDispatchTxService prepTx = mock(IDispenseDispatchTxService.class);
        MqttConnectionManager mqtt = mock(MqttConnectionManager.class);
        WsCommandMapper commandMapper = mock(WsCommandMapper.class);
        when(commandMapper.update(any(WsCommand.class), any())).thenReturn(1);
        WsCommand prepared = new WsCommand()
                .setCmdNo("CMD-PREP-1").setDeviceId(DEVICE_ID).setOrderId(77L)
                .setCmdType(DeviceEnum.CmdType.START_DISPENSE.getValue())
                .setCmdPayload("{\"outletNo\":1}")
                .setCmdStatus(DeviceEnum.CmdStatus.PENDING.getValue());
        prepared.setId(900L);
        when(prepTx.prepare(77L)).thenReturn(
                new IDispenseDispatchTxService.Prepared(900L, true, prepared, "DK-DEV-0021"));

        WsCommandServiceImpl service = commandServiceWith(prepTx, mqtt, commandMapper);
        assertEquals(900L, service.sendDispenseForOrder(77L));

        // 顺序即契约：准备事务先返回（提交完成），随后才 publish；反过来就是把网络 IO 关进事务
        var order = inOrder(prepTx, mqtt);
        order.verify(prepTx).prepare(77L);
        order.verify(mqtt).publishCommand(eq("DK-DEV-0021"), anyString());
    }

    @Test
    void idempotentHitReturnsExistingCommandWithoutPublish() {
        IDispenseDispatchTxService prepTx = mock(IDispenseDispatchTxService.class);
        MqttConnectionManager mqtt = mock(MqttConnectionManager.class);
        WsCommandMapper commandMapper = mock(WsCommandMapper.class);
        when(prepTx.prepare(77L)).thenReturn(
                new IDispenseDispatchTxService.Prepared(4321L, false, null, null));

        WsCommandServiceImpl service = commandServiceWith(prepTx, mqtt, commandMapper);
        assertEquals(4321L, service.sendDispenseForOrder(77L));

        verify(mqtt, never()).publishCommand(anyString(), anyString());
        verify(commandMapper, never()).insert(any(WsCommand.class));
    }
}
