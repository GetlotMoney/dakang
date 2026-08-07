package com.jbk.serve.service.device.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jbk.serve.mapper.device.WsCommandBatchMapper;
import com.jbk.serve.mapper.device.WsCommandMapper;
import com.jbk.serve.service.device.IDeviceParamService;
import com.jbk.serve.service.device.IWaterCommandFailureTxService;
import com.jbk.serve.service.ops.IWsAlarmService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.serve.service.trade.ITradeOrderTxService;
import com.jbk.tool.consts.device.DeviceEnum;
import com.jbk.tool.data.device.po.WsCommand;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REQ-213-R1：{@code onResult} 里参数快照回写门的行为测试。
 *
 * <p>这道门有四个条件（transited / success / 参数类指令 / 非 partial），此前<b>一条都没有测试触达</b>——
 * 把任意一个删掉全仓照样绿。而 partial 与 transited 恰恰是本改动自己声明的核心风险点：
 * 「据此回写会让平台显示已同步而设备其实没改，比不写更糟」。只有注释没有守卫，等于没有。</p>
 *
 * <p>本类只钉「什么时候该调、什么时候绝不能调」，回写自身的数据语义在
 * {@link DeviceParamSnapshotDbTest} 用真库钉。</p>
 */
class DeviceParamWriteBackGateTest {

    private static final long DEVICE_ID = 21L;
    private static final long CMD_ID = 1001L;
    private static final String CMD_NO = "CMD-P1";
    private static final String PAYLOAD = "{\"limitMl\":5000}";
    private static final String FINISH = "20260804120000";

    private WsCommandMapper commandMapper;
    private com.jbk.serve.mapper.device.WsDeviceMapper deviceMapper;
    private IDeviceParamService deviceParamService;
    private WsCommandServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        assistant.setCurrentNamespace(WsCommandMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, WsCommand.class);
    }

    @BeforeEach
    void setUp() {
        commandMapper = mock(WsCommandMapper.class);
        deviceParamService = mock(IDeviceParamService.class);
        service = new WsCommandServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", commandMapper);
        ReflectionTestUtils.setField(service, "deviceParamService", deviceParamService);
        ReflectionTestUtils.setField(service, "domainEventService", mock(IWsDomainEventService.class));
        ReflectionTestUtils.setField(service, "tradeOrderTxService", mock(ITradeOrderTxService.class));
        ReflectionTestUtils.setField(service, "alarmService", mock(IWsAlarmService.class));
        ReflectionTestUtils.setField(service, "commandBatchMapper", mock(WsCommandBatchMapper.class));
        ReflectionTestUtils.setField(service, "waterCommandFailureTxService",
                mock(IWaterCommandFailureTxService.class));
        deviceMapper = mock(com.jbk.serve.mapper.device.WsDeviceMapper.class);
        ReflectionTestUtils.setField(service, "deviceMapper", deviceMapper);
        ReflectionTestUtils.setField(service, "mqttProvider", mock(org.springframework.beans.factory.ObjectProvider.class));
        when(deviceMapper.selectById(DEVICE_ID))
                .thenReturn(new com.jbk.tool.data.device.po.WsDevice()
                        .setId(DEVICE_ID).setDeviceNo("DK-DEV-0021"));
        when(deviceParamService.enabledDefinitions(anyInt())).thenReturn(java.util.Map.of());
    }

    /** 造一条处于给定状态的指令，并让状态推进的 UPDATE 按 transited 期望返回影响行数。 */
    private void givenCommand(int cmdType, int cmdStatus, boolean transitSucceeds) {
        WsCommand command = new WsCommand()
                .setId(CMD_ID)
                .setCmdNo(CMD_NO)
                .setDeviceId(DEVICE_ID)
                .setCmdType(cmdType)
                .setCmdPayload(PAYLOAD)
                .setCmdStatus(cmdStatus);
        when(commandMapper.selectOne(any(Wrapper.class), eq(true))).thenReturn(command);
        when(commandMapper.update(any(WsCommand.class), any(Wrapper.class)))
                .thenReturn(transitSucceeds ? 1 : 0);
    }

    private void verifyWroteBack() {
        verify(deviceParamService).applySyncedParams(eq(DEVICE_ID), anyInt(), eq(CMD_ID),
                eq(CMD_NO), eq(PAYLOAD), anyString());
    }

    private void verifyNeverWroteBack() {
        verify(deviceParamService, never())
                .applySyncedParams(anyLong(), anyInt(), anyLong(), anyString(), anyString(), anyString());
    }

    // ==================== 该调的唯一情形 ====================

    @Test
    void paramSyncSuccessFromSentWritesBack() {
        givenCommand(DeviceEnum.CmdType.PARAM_SYNC.getValue(),
                DeviceEnum.CmdStatus.SENT.getValue(), true);

        service.onResult(DEVICE_ID, CMD_NO, true, "{}", null, FINISH);

        verifyWroteBack();
    }

    @Test
    void priceSyncSuccessFromAckedWritesBack() {
        givenCommand(DeviceEnum.CmdType.PRICE_SYNC.getValue(),
                DeviceEnum.CmdStatus.ACKED.getValue(), true);

        service.onResult(DEVICE_ID, CMD_NO, true, "{}", null, FINISH);

        verifyWroteBack();
    }

    // ==================== 四个条件逐个否决 ====================

    @Test
    void failedResultNeverWritesBack() {
        givenCommand(DeviceEnum.CmdType.PARAM_SYNC.getValue(),
                DeviceEnum.CmdStatus.SENT.getValue(), true);

        service.onResult(DEVICE_ID, CMD_NO, false, "{}", "设备拒绝", FINISH);

        verifyNeverWroteBack();
    }

    @Test
    void partialResultNeverWritesBack() {
        // partial 表示只生效了一部分，具体哪部分设备没说。
        // 据此把整份报文写成「已同步」，平台会显示设备处于一个它并不处于的状态。
        givenCommand(DeviceEnum.CmdType.PARAM_SYNC.getValue(),
                DeviceEnum.CmdStatus.SENT.getValue(), true);

        service.onResult(DEVICE_ID, CMD_NO, true, "{\"partial\":true}", null, FINISH);

        verifyNeverWroteBack();
    }

    @Test
    void nonParamBearingCommandNeverWritesBack() {
        // 出水指令的 payload 是 {outletNo, planMl, orderNo}，写进参数快照就是污染
        givenCommand(DeviceEnum.CmdType.QUERY_STATUS.getValue(),
                DeviceEnum.CmdStatus.SENT.getValue(), true);

        service.onResult(DEVICE_ID, CMD_NO, true, "{}", null, FINISH);

        verifyNeverWroteBack();
    }

    @Test
    void lateResultOnTerminalCommandNeverWritesBack() {
        // 指令已停在 6超时：断网补传把它的 result 送上来时，指令本身正确地不再推进，
        // 快照同样不该被这条更早的指令改写。数据层的单调守卫是第二道，这里是第一道。
        givenCommand(DeviceEnum.CmdType.PARAM_SYNC.getValue(),
                DeviceEnum.CmdStatus.TIMEOUT.getValue(), false);

        service.onResult(DEVICE_ID, CMD_NO, true, "{}", null, FINISH);

        verifyNeverWroteBack();
    }

    @Test
    void transitionLosingRaceNeverWritesBack() {
        // 状态推进的条件 UPDATE 影响 0 行（并发输方）：本次 result 没有驱动指令进入终态，
        // 就不能声称参数已生效。
        givenCommand(DeviceEnum.CmdType.PARAM_SYNC.getValue(),
                DeviceEnum.CmdStatus.SENT.getValue(), false);

        service.onResult(DEVICE_ID, CMD_NO, true, "{}", null, FINISH);

        verifyNeverWroteBack();
    }

    // ==================== 两条下发路径必须共用同一校验实现 ====================

    /**
     * 本改动的立论是「单发与批量此前各判各的 isTypeJSON，宽严一致纯属巧合，
     * 任何一处放松就能从那一处把畸形报文发到设备上」。落地后必须有守卫盯着这两个调用点，
     * 否则下一次重构把其中一处改回去，测试套件不会给出任何信号——那立论就白立了。
     *
     * <p>这里做源级断言：没有 SFC/容器装置能同时驱动两条真实路径，而调用点的存在性
     * 恰恰是可以静态判定的。断言锚定到可执行构造，不用会命中注释的裸关键字。</p>
     */
    @Test
    void bothDispatchPathsDelegateToTheSingleValidator() throws Exception {
        java.nio.file.Path base = java.nio.file.Path.of(
                "src/main/java/com/jbk/serve/service/device/impl");
        String single = java.nio.file.Files.readString(base.resolve("WsCommandServiceImpl.java"));
        String batch = java.nio.file.Files.readString(base.resolve("DeviceControlServiceImpl.java"));

        for (String src : new String[]{single, batch}) {
            org.junit.jupiter.api.Assertions.assertTrue(
                    src.contains("DeviceParamPayload.require("),
                    "下发路径必须调用唯一校验实现 DeviceParamPayload.require");
            org.junit.jupiter.api.Assertions.assertTrue(
                    src.contains("enabledDefinitions("),
                    "必须带上已登记定义，否则登记校验形同虚设");
        }
        // 参数类指令不得再用裸 isTypeJSON 当校验：那正是被替换掉的宽松实现
        org.junit.jupiter.api.Assertions.assertFalse(
                single.contains("!JSONUtil.isTypeJSON(commandBo.getCmdPayload())"),
                "单发路径不得退回裸 isTypeJSON");
        org.junit.jupiter.api.Assertions.assertFalse(
                batch.contains("!JSONUtil.isTypeJSON(bo.getCmdPayload())"),
                "批量路径不得退回裸 isTypeJSON");
    }

    // ==================== 回写失败不得影响指令终态 ====================

    @Test
    void writeBackFailureIsContainedAndDoesNotBreakCommandResult() {
        givenCommand(DeviceEnum.CmdType.PARAM_SYNC.getValue(),
                DeviceEnum.CmdStatus.SENT.getValue(), true);
        when(deviceParamService.applySyncedParams(anyLong(), anyInt(), anyLong(),
                anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("快照表不可用"));

        // 快照是运维可见性不是资金：写失败不得回滚已落定的指令终态，也不得让设备反复重投
        org.junit.jupiter.api.Assertions.assertTrue(
                service.onResult(DEVICE_ID, CMD_NO, true, "{}", null, FINISH),
                "回写失败不得改变 onResult 的返回值（指令终态已落定）");
    }

    // ==================== R2-P1-2：真正执行下发的入口必须自带 fail-closed ====================

    /**
     * {@code sendAsBatchChild} 是批量链路上真正建指令并 publish 的地方。
     *
     * <p>上游 confirm 已按最新定义复验过，这里是纵深防御：任何未来新增的调用方
     * （补发、重试、运维脚本）若忘了复验，参数会从这里直接落到设备上。
     * 没有这条测试，把该闸删掉全仓照样绿——实测过，正是这个结果。</p>
     */
    @Test
    void batchChildRejectsParamPayloadViolatingCurrentDefinition() {
        when(deviceParamService.enabledDefinitions(DeviceEnum.CmdType.PARAM_SYNC.getValue()))
                .thenReturn(java.util.Map.of("limitMl",
                        new com.jbk.serve.service.device.DeviceParamPayload.Definition(
                                "limitMl", com.jbk.serve.service.device.DeviceParamPayload.TYPE_INT,
                                "毫升", "100", "20000", null)));

        org.junit.jupiter.api.Assertions.assertThrows(com.jbk.tool.exception.JbkException.class,
                () -> service.sendAsBatchChild(900L, DEVICE_ID,
                        DeviceEnum.CmdType.PARAM_SYNC.getValue(), "{\"limitMl\":99999}", null));

        // 拒绝必须发生在建指令之前：一行都不能落，设备档案也不必去查
        verify(commandMapper, never()).insert(any(WsCommand.class));
        verify(deviceMapper, never()).selectById(anyLong());
    }

    @Test
    void batchChildAcceptsPayloadThatSatisfiesCurrentDefinition() {
        when(deviceParamService.enabledDefinitions(DeviceEnum.CmdType.PARAM_SYNC.getValue()))
                .thenReturn(java.util.Map.of("limitMl",
                        new com.jbk.serve.service.device.DeviceParamPayload.Definition(
                                "limitMl", com.jbk.serve.service.device.DeviceParamPayload.TYPE_INT,
                                "毫升", "100", "20000", null)));
        when(commandMapper.insert(any(WsCommand.class))).thenReturn(1);

        service.sendAsBatchChild(900L, DEVICE_ID,
                DeviceEnum.CmdType.PARAM_SYNC.getValue(), "{\"limitMl\":5000}", null);

        verify(commandMapper).insert(any(WsCommand.class));
    }

    @Test
    void batchChildDoesNotConsultDefinitionsForNonParamCommands() {
        when(commandMapper.insert(any(WsCommand.class))).thenReturn(1);

        service.sendAsBatchChild(900L, DEVICE_ID,
                DeviceEnum.CmdType.REBOOT.getValue(), "{}", null);

        verify(deviceParamService, never()).enabledDefinitions(DeviceEnum.CmdType.REBOOT.getValue());
        verify(commandMapper).insert(any(WsCommand.class));
    }
}
