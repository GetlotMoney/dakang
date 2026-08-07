package com.jbk.serve.service.mini.impl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.jbk.serve.mapper.device.WsDeviceMapper;
import com.jbk.serve.mapper.device.WsDeviceOutletMapper;
import com.jbk.serve.mapper.device.WsQrcodeMapper;
import com.jbk.serve.mapper.station.WsStationMapper;
import com.jbk.serve.service.device.DeviceAvailabilityGuard;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.serve.service.trade.WaterBillingMath;
import com.jbk.tool.consts.mini.MiniRejectCode;
import com.jbk.tool.data.device.po.WsDevice;
import com.jbk.tool.data.device.po.WsDeviceOutlet;
import com.jbk.tool.data.device.po.WsQrcode;
import com.jbk.tool.data.mini.vo.ScanSessionInfo;
import com.jbk.tool.data.mini.vo.ScanSessionVo;
import com.jbk.tool.data.mini.vo.WaterDeviceContextVo;
import com.jbk.tool.data.station.po.WsStation;
import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S2 扫码报价冻结（B02/B03）：价格与水种在扫码那一刻定死，此后确认页展示与下单装配只吃这一份。
 *
 * <p>此前会话只冻结二维码/设备/出水口 ID，确认页读一次当前价、提交时再读一次当前价——
 * 用户确认期间后台调价，页面显示的价与实际扣款的价就不是同一个。</p>
 */
class ScanQuoteFreezeTest {

    private static final long USER_ID = 9L;
    private static final long QRCODE_ID = 11L;
    private static final long STATION_ID = 41L;
    private static final long DEVICE_ID = 21L;
    private static final long OUTLET_ID = 31L;
    private static final long WATER_TYPE_ID = 8L;
    private static final String RAW_CODE = "DK-QR-DEV0001-O1";

    private MiniDeviceServiceImpl service;
    private WsQrcodeMapper qrcodeMapper;
    private WsDeviceMapper deviceMapper;
    private WsDeviceOutletMapper outletMapper;
    private WsStationMapper stationMapper;
    private IWsDomainEventService domainEventService;
    /** 内存 Redis：铸会话后要能按 key 读回，才能验证「读到的就是写进去的那份报价」。 */
    private final Map<String, Object> store = new HashMap<>();

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        service = new MiniDeviceServiceImpl();
        qrcodeMapper = Mockito.mock(WsQrcodeMapper.class);
        deviceMapper = Mockito.mock(WsDeviceMapper.class);
        outletMapper = Mockito.mock(WsDeviceOutletMapper.class);
        stationMapper = Mockito.mock(WsStationMapper.class);
        domainEventService = Mockito.mock(IWsDomainEventService.class);

        RedisTemplate<String, Object> redis = Mockito.mock(RedisTemplate.class);
        ValueOperations<String, Object> ops = Mockito.mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        Mockito.doAnswer(inv -> store.put(inv.getArgument(0), inv.getArgument(1)))
                .when(ops).set(anyString(), any(), anyLong(), any());
        when(ops.get(anyString())).thenAnswer(inv -> store.get(inv.<String>getArgument(0)));

        ReflectionTestUtils.setField(service, "qrcodeMapper", qrcodeMapper);
        ReflectionTestUtils.setField(service, "deviceMapper", deviceMapper);
        ReflectionTestUtils.setField(service, "outletMapper", outletMapper);
        ReflectionTestUtils.setField(service, "stationMapper", stationMapper);
        ReflectionTestUtils.setField(service, "domainEventService", domainEventService);
        ReflectionTestUtils.setField(service, "redis", redis);
        ReflectionTestUtils.setField(service, "availabilityGuard",
                Mockito.mock(DeviceAvailabilityGuard.class));

        when(qrcodeMapper.selectOne(any(Wrapper.class))).thenReturn(new WsQrcode()
                .setQrcodeContent(RAW_CODE).setQrcodeType(1).setQrcodeStatus(1)
                .setDeviceId(DEVICE_ID).setOutletId(OUTLET_ID));
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device());
        when(stationMapper.selectById(STATION_ID)).thenReturn(station());
        givenOutletPrice("20");
    }

    private WsDevice device() {
        WsDevice device = new WsDevice().setDeviceNo("DK-DEV-0021").setDeviceName("测试机")
                .setStationId(STATION_ID).setOnlineStatus(1).setRunStatus(1);
        device.setId(DEVICE_ID);
        return device;
    }

    private WsStation station() {
        WsStation station = new WsStation().setStationName("光谷站").setStationStatus(1);
        station.setId(STATION_ID);
        return station;
    }

    private void givenOutletPrice(String price) {
        givenOutlet(WATER_TYPE_ID, price);
    }

    private void givenOutlet(Long waterTypeId, String price) {
        WsDeviceOutlet outlet = new WsDeviceOutlet().setDeviceId(DEVICE_ID).setOutletNo(1)
                .setWaterTypeId(waterTypeId).setWaterType("纯净水").setOutletPrice(price).setOutletStatus(1);
        outlet.setId(OUTLET_ID);
        when(outletMapper.selectById(OUTLET_ID)).thenReturn(outlet);
    }

    private JSONObject storedSession(String scanSessionId) {
        Object raw = store.get("mini:scan:" + scanSessionId);
        assertNotNull(raw, "会话必须已铸造");
        return JSONUtil.parseObj(raw.toString());
    }

    // ==================== 1. 铸会话即冻结报价 ====================

    @Test
    void resolveScanFreezesWaterTypePriceAndQuotedAt() {
        ScanSessionVo scanned = service.resolveScan(RAW_CODE, USER_ID);

        JSONObject session = storedSession(scanned.getScanSessionId());
        assertEquals(WATER_TYPE_ID, session.getLong("waterTypeId"));
        assertEquals(20, session.getInt("unitPriceFenPerLiter"));
        assertEquals(14, String.valueOf(session.getStr("quotedAt")).length(), "quotedAt 为 yyyyMMddHHmmss");
    }

    // ==================== 2. 非法单价不铸会话 ====================

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"-1", "1.5", "abc", " 20 x", "100001", "0020"})
    void illegalOutletPriceDoesNotMintSession(String price) {
        givenOutletPrice(price);

        assertThrows(JbkException.class, () -> service.resolveScan(RAW_CODE, USER_ID));

        assertTrue(store.isEmpty(), "非法报价一律不铸会话，绝不返回部分结果");
    }

    @Test
    void missingWaterTypeDoesNotMintSession() {
        givenOutlet(null, "20");

        assertThrows(JbkException.class, () -> service.resolveScan(RAW_CODE, USER_ID));

        assertTrue(store.isEmpty());
    }

    // ==================== 3. 旧会话缺报价字段 fail-closed ====================

    @Test
    void legacySessionWithoutQuoteIsTreatedAsExpired() {
        // S2 之前铸造的会话：绝不回退去读当前价，否则「页面一个价、扣款另一个价」原样复活
        store.put("mini:scan:legacy", JSONUtil.createObj()
                .set("userId", USER_ID).set("qrcodeId", QRCODE_ID).set("stationId", STATION_ID)
                .set("deviceId", DEVICE_ID).set("deviceNo", "DK-DEV-0021").set("outletId", OUTLET_ID)
                .toString());

        JbkException denied = assertThrows(JbkException.class,
                () -> service.loadScanSession("legacy", USER_ID));

        assertEquals(MiniRejectCode.SCAN_SESSION_EXPIRED, denied.getCode());
        verify(outletMapper, never()).selectById(OUTLET_ID);
    }

    // ==================== 4. 上下文返回冻结报价 ====================

    @Test
    void waterContextReturnsFrozenQuoteNotCurrentPrice() {
        ScanSessionVo scanned = service.resolveScan(RAW_CODE, USER_ID);

        WaterDeviceContextVo context = service.getWaterContext(scanned.getScanSessionId(), USER_ID);

        assertEquals(20, context.getOutlet().getUnitPriceFenPerLiter());
        assertEquals(WATER_TYPE_ID, context.getOutlet().getWaterTypeId());
        assertNotNull(context.getQuotedAt(), "报价时间须透出，页面据此提示有效期");
        // 冻结值必须与会话里的那一份逐字相同——不能是「又读了一次当前档案恰好相等」
        JSONObject session = storedSession(scanned.getScanSessionId());
        assertEquals(session.getInt("unitPriceFenPerLiter"), context.getOutlet().getUnitPriceFenPerLiter());
    }

    // ==================== 5/6. 报价漂移 → SCAN_QUOTE_CHANGED ====================

    @Test
    void priceChangedAfterScanRejectsContextWithQuoteChanged() {
        ScanSessionVo scanned = service.resolveScan(RAW_CODE, USER_ID);
        givenOutletPrice("30");

        JbkException denied = assertThrows(JbkException.class,
                () -> service.getWaterContext(scanned.getScanSessionId(), USER_ID));

        assertEquals(MiniRejectCode.SCAN_QUOTE_CHANGED, denied.getCode());
    }

    @Test
    void waterTypeChangedAfterScanRejectsContextWithQuoteChanged() {
        ScanSessionVo scanned = service.resolveScan(RAW_CODE, USER_ID);
        givenOutlet(WATER_TYPE_ID + 1, "20");

        JbkException denied = assertThrows(JbkException.class,
                () -> service.getWaterContext(scanned.getScanSessionId(), USER_ID));

        assertEquals(MiniRejectCode.SCAN_QUOTE_CHANGED, denied.getCode());
    }

    // ==================== 会话数据完整透出 ====================

    @Test
    void loadScanSessionExposesFrozenQuoteForDownstream() {
        ScanSessionVo scanned = service.resolveScan(RAW_CODE, USER_ID);

        ScanSessionInfo info = service.loadScanSession(scanned.getScanSessionId(), USER_ID);

        assertEquals(scanned.getScanSessionId(), info.getScanSessionId());
        assertEquals(WATER_TYPE_ID, info.getWaterTypeId());
        assertEquals(20, info.getUnitPriceFenPerLiter());
        assertNotNull(info.getQuotedAt());
    }

    // ==================== 单价解析唯一实现的边界 ====================

    @Test
    void outletPriceParsingIsStrictAndShared() {
        assertEquals(20, WaterBillingMath.requireOutletPrice("20"));
        assertEquals(20, WaterBillingMath.requireOutletPrice(" 20 "), "首尾空白容忍，内部非法一律拒");
        assertEquals(0, WaterBillingMath.requireOutletPrice("0"));
        for (String illegal : new String[]{null, "", "-1", "1.5", "0020", "1e2", "20元", "2 0"}) {
            assertThrows(JbkException.class, () -> WaterBillingMath.requireOutletPrice(illegal),
                    "必须拒绝：" + illegal);
        }
    }

    // 报价冻结不改变扫码解析既有的共键 fail-closed
    @Test
    void quoteFreezeDoesNotWeakenCoKeyChecks() {
        WsDeviceOutlet foreign = new WsDeviceOutlet().setDeviceId(DEVICE_ID + 5).setOutletNo(1)
                .setWaterTypeId(WATER_TYPE_ID).setOutletPrice("20").setOutletStatus(1);
        foreign.setId(OUTLET_ID);
        when(outletMapper.selectById(OUTLET_ID)).thenReturn(foreign);

        assertThrows(JbkException.class, () -> service.resolveScan(RAW_CODE, USER_ID));

        assertTrue(store.isEmpty());
        ArgumentCaptor<Object> evidence = ArgumentCaptor.forClass(Object.class);
        verify(domainEventService).recordByDevice(any(), anyString(), any(), evidence.capture());
        assertTrue(String.valueOf(evidence.getValue()).contains("共键错位"));
    }
}
