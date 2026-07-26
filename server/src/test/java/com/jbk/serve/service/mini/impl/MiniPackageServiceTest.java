package com.jbk.serve.service.mini.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jbk.serve.mapper.product.WsPackageMapper;
import com.jbk.tool.data.mini.vo.MiniPackageVo;
import com.jbk.tool.data.product.po.WsPackage;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L2-READ 套餐只读：只出在售、不下发范围配置、空列表不返回 null。
 */
class MiniPackageServiceTest {

    private WsPackageMapper mapper;
    private MiniPackageServiceImpl service;

    /** 纯单测无 Spring/Mapper 注册，需手动初始化 MP TableInfo，否则 LambdaWrapper 生成 SQL 时无 lambda 缓存。 */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), WsPackage.class);
    }

    @BeforeEach
    void setup() {
        mapper = Mockito.mock(WsPackageMapper.class);
        service = new MiniPackageServiceImpl(mapper);
    }

    private WsPackage pkg(Long id, String name, long fen, long ml) {
        WsPackage p = new WsPackage();
        p.setId(id);
        p.setPackageName(name);
        p.setPayAmount(fen);
        p.setWaterMl(ml);
        p.setBonusAmount(0L);
        p.setUnitPriceSnap("20.00");
        p.setScopeJson("{\"scopeType\":\"specified\",\"stationIds\":[1]}");
        p.setPackageStatus(1);
        return p;
    }

    // 1) 查询条件必须限定在售状态（下架套餐不得出现在小程序）
    @Test
    void onlyOnSalePackagesAreQueried() {
        when(mapper.selectList(any())).thenReturn(List.of());
        service.listOnSale();

        ArgumentCaptor<Wrapper<WsPackage>> cap = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(cap.capture());
        LambdaQueryWrapper<WsPackage> w = (LambdaQueryWrapper<WsPackage>) cap.getValue();
        assertTrue(w.getSqlSegment().contains("PACKAGE_STATUS"),
                "必须按 PACKAGE_STATUS 过滤，实际=" + w.getSqlSegment());
        assertTrue(w.getParamNameValuePairs().containsValue(1), "必须限定为 1 在售");
    }

    // 2) 空结果返回空列表而不是 null
    @Test
    void emptyResultIsEmptyListNotNull() {
        when(mapper.selectList(any())).thenReturn(List.of());
        List<MiniPackageVo> list = service.listOnSale();
        assertNotNull(list);
        assertTrue(list.isEmpty());
    }

    // 3) 字段映射正确（售价映射为契约字段 payAmountFen）
    @Test
    void fieldsMappedToContract() {
        when(mapper.selectList(any())).thenReturn(List.of(pkg(3L, "季卡 100L", 9900L, 100000L)));
        MiniPackageVo vo = service.listOnSale().get(0);
        assertEquals(3L, vo.getPackageId());
        assertEquals("季卡 100L", vo.getPackageName());
        assertEquals(9900L, vo.getPayAmountFen());
        assertEquals(100000L, vo.getWaterMl());
        assertEquals("20.00", vo.getUnitPriceSnap());
        assertTrue(vo.getPurchasable());
    }

    // 4) 可购结论三态：合法非空范围=true，空范围/非法范围=false
    @Test
    void purchasableReflectsStrictScopeNormalization() {
        WsPackage valid = pkg(1L, "合法范围", 1000L, 10000L);
        WsPackage blank = pkg(2L, "空范围", 2000L, 20000L);
        blank.setScopeJson(null);
        WsPackage invalid = pkg(3L, "非法范围", 3000L, 30000L);
        invalid.setScopeJson("{\"scopeType\":\"specified\",\"stationIds\":[]}");
        when(mapper.selectList(any())).thenReturn(List.of(valid, blank, invalid));

        List<MiniPackageVo> result = service.listOnSale();

        assertTrue(result.get(0).getPurchasable());
        assertFalse(result.get(1).getPurchasable());
        assertFalse(result.get(2).getPurchasable());
    }

    // 5) 范围配置绝不下发给前端（范围校验是服务端职责）
    @Test
    void scopeJsonIsNeverExposed() throws Exception {
        when(mapper.selectList(any())).thenReturn(List.of(pkg(3L, "季卡", 9900L, 100000L)));
        String json = new ObjectMapper().writeValueAsString(service.listOnSale());
        assertFalse(json.toLowerCase().contains("scope"), "套餐响应不得含范围配置：" + json);
        assertFalse(json.contains("stationIds"), "套餐响应不得含范围白名单：" + json);
    }
}
