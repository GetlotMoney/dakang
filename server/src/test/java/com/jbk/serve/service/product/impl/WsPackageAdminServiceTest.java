package com.jbk.serve.service.product.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jbk.serve.mapper.product.WsPackageMapper;
import com.jbk.tool.data.product.bo.WsPackageBo;
import com.jbk.tool.data.product.po.WsPackage;
import com.jbk.tool.data.product.vo.WsPackageVo;
import com.jbk.tool.exception.JbkException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PC 套餐管理接线测试：只验证「后台入口正确接到 RechargeLimits / WaterCardScope 唯一规则」
 * 与上下架 CAS 影响行数闸，不重复测两套规则自身（各有专属测试）。
 */
class WsPackageAdminServiceTest {

    private WsPackageMapper mapper;
    private WsPackageServiceImpl service;

    /** 纯单测无 Spring/Mapper 注册，需手动初始化 MP TableInfo，否则 LambdaWrapper 无 lambda 缓存。 */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), WsPackage.class);
    }

    @BeforeEach
    void setup() {
        mapper = Mockito.mock(WsPackageMapper.class);
        service = new WsPackageServiceImpl(mapper);
    }

    /** 合法水量套餐 Bo：100 元 500 升，限定 1 号站。 */
    private WsPackageBo validBo() {
        WsPackageBo bo = new WsPackageBo();
        bo.setPackageName("100元500升卡");
        bo.setPayAmount(10000L);
        bo.setWaterMl(500000L);
        bo.setBonusAmount(0L);
        bo.setExpireDays(null); // D-213：可售套餐一律永久
        bo.setPackageStatus(1);
        bo.setScopeJson("{\"scopeType\":\"specified\",\"stationIds\":[\"1\"]}");
        return bo;
    }

    private WsPackage existing(long id, int status) {
        WsPackage po = new WsPackage();
        po.setId(id);
        po.setPackageName("既有套餐");
        po.setPayAmount(10000L);
        po.setWaterMl(500000L);
        po.setBonusAmount(0L);
        po.setUnitPriceSnap("20.00");
        po.setPackageStatus(status);
        return po;
    }

    // 1) 非法范围拒绝：specified 但三维全空，WaterCardScope 拒绝且零写入
    @Test
    void addRejectsInvalidScopeWithoutInsert() {
        WsPackageBo bo = validBo();
        bo.setScopeJson("{\"scopeType\":\"specified\",\"stationIds\":[]}");
        assertThrows(JbkException.class, () -> service.saveData(bo));
        verify(mapper, never()).insert(any(WsPackage.class));
    }

    // 2) 越界金额拒绝：超 RechargeLimits 上限（接线到唯一数值规则），零写入
    @Test
    void addRejectsOverLimitPayAmount() {
        WsPackageBo bo = validBo();
        bo.setPayAmount(1_000_001L);
        assertThrows(JbkException.class, () -> service.saveData(bo));
        verify(mapper, never()).insert(any(WsPackage.class));
    }

    // 2b) D-213 管理端守卫：付费套餐带有效期，新增与修改一律拒绝且零写入。
    //     这是审计驳回 P1-1 点名的缺口——此前 PC 能建出售价>0 且带 expireDays 的套餐并正常上架，
    //     小程序判为可购，用户买完即持有一张与预付卡合规底线冲突的有限期付费卡
    @Test
    void paidPackageWithExpiryRejectedOnSaveAndUpdate() {
        WsPackageBo add = validBo();
        add.setExpireDays(365);
        JbkException saveEx = assertThrows(JbkException.class, () -> service.saveData(add));
        assertTrue(saveEx.getMessage().contains("付费套餐不得设置有效期"), "实际=" + saveEx.getMessage());
        verify(mapper, never()).insert(any(WsPackage.class));

        WsPackageBo upd = validBo();
        upd.setId(1L);
        upd.setExpireDays(365);
        when(mapper.selectById(1L)).thenReturn(existing(1L, 1));
        JbkException updEx = assertThrows(JbkException.class, () -> service.updateData(upd));
        assertTrue(updEx.getMessage().contains("付费套餐不得设置有效期"), "实际=" + updEx.getMessage());
        verify(mapper, never()).update(any(), any());
    }

    // 3) 合法范围通过：落库为规范化指纹（IDs 升序字符串、无展示名），单价快照服务端派生
    @Test
    void addStoresCanonicalScopeAndDerivedUnitPrice() {
        WsPackageBo bo = validBo();
        // 前端可能传历史整数 ID 与乱序；服务端必须统一为规范化字符串升序
        bo.setScopeJson("{\"scopeType\":\"specified\",\"stationIds\":[2,1]}");
        when(mapper.insert(any(WsPackage.class))).thenAnswer(inv -> {
            inv.getArgument(0, WsPackage.class).setId(9L);
            return 1;
        });

        Long id = service.saveData(bo);

        assertEquals(9L, id);
        ArgumentCaptor<WsPackage> cap = ArgumentCaptor.forClass(WsPackage.class);
        verify(mapper).insert(cap.capture());
        WsPackage saved = cap.getValue();
        assertEquals("{\"scopeType\":\"specified\",\"stationIds\":[\"1\",\"2\"],\"deviceIds\":[],\"outletIds\":[]}",
                saved.getScopeJson());
        assertEquals("20.00", saved.getUnitPriceSnap(), "单价快照必须由服务端按 售价÷水量 派生");
        assertEquals(1, saved.getPackageStatus());
    }

    // 4) 空范围=未配置：存 NULL 不存空串（L2-A6 语义交由读取方判定，不在写入时伪造 all）
    @Test
    void addStoresBlankScopeAsNull() {
        WsPackageBo bo = validBo();
        bo.setWaterMl(0L);
        bo.setBonusAmount(500L);
        bo.setScopeJson("   ");
        when(mapper.insert(any(WsPackage.class))).thenAnswer(inv -> {
            inv.getArgument(0, WsPackage.class).setId(10L);
            return 1;
        });

        service.saveData(bo);

        ArgumentCaptor<WsPackage> cap = ArgumentCaptor.forClass(WsPackage.class);
        verify(mapper).insert(cap.capture());
        assertNull(cap.getValue().getScopeJson());
        assertEquals("0", cap.getValue().getUnitPriceSnap(), "纯金额套餐单价快照固定 0");
    }

    // 5) 修改：不存在拒绝；存在时可空列（有效期/范围/备注）经 wrapper 显式 set，状态列不参与修改
    @Test
    void updateRejectsMissingAndClearsNullableColumnsExplicitly() {
        WsPackageBo missing = validBo();
        missing.setId(404L);
        when(mapper.selectById(404L)).thenReturn(null);
        assertThrows(JbkException.class, () -> service.updateData(missing));

        WsPackageBo bo = validBo();
        bo.setId(1L);
        bo.setExpireDays(null); // 可空列显式清空动线保留（存量有限期数据改永久时要用）
        bo.setScopeJson(null);
        when(mapper.selectById(1L)).thenReturn(existing(1L, 1));
        when(mapper.update(any(), any())).thenReturn(1);

        assertTrue(service.updateData(bo));

        ArgumentCaptor<WsPackage> entityCap = ArgumentCaptor.forClass(WsPackage.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<WsPackage>> wrapperCap = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(entityCap.capture(), wrapperCap.capture());
        assertNull(entityCap.getValue().getPackageStatus(), "修改不得顺带改状态，状态变更只走 /shelf CAS");
        String sqlSet = ((LambdaUpdateWrapper<WsPackage>) wrapperCap.getValue()).getSqlSet();
        assertTrue(sqlSet.contains("EXPIRE_DAYS"), "可空列必须显式 set 才能清空，实际 SET=" + sqlSet);
        assertTrue(sqlSet.contains("SCOPE_JSON"), "可空列必须显式 set 才能清空，实际 SET=" + sqlSet);
    }

    // 6) 上下架 CAS：前置状态进 WHERE；影响行数=1 成功，=0 视为并发变化必须报错
    @Test
    void shelfCasChecksAffectedRows() {
        WsPackageBo bo = new WsPackageBo();
        bo.setId(1L);
        bo.setTargetStatus(2);
        when(mapper.selectById(1L)).thenReturn(existing(1L, 1));
        when(mapper.update(any(), any())).thenReturn(1);

        assertTrue(service.shelfData(bo));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<WsPackage>> cap = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(any(), cap.capture());
        LambdaUpdateWrapper<WsPackage> w = (LambdaUpdateWrapper<WsPackage>) cap.getValue();
        assertTrue(w.getSqlSegment().contains("PACKAGE_STATUS"),
                "CAS 必须把前置状态写进 WHERE，实际=" + w.getSqlSegment());
        assertTrue(w.getParamNameValuePairs().containsValue(1), "下架的前置状态必须是 1 在售");

        // 并发下第二次点击：前置状态已不匹配，影响行数 0 → 明确报错，不静默覆盖
        when(mapper.update(any(), any())).thenReturn(0);
        assertThrows(JbkException.class, () -> service.shelfData(bo));
    }

    // 7) 上下架目标状态只允许 1/2；非法值零写入
    @Test
    void shelfRejectsIllegalTargetStatus() {
        WsPackageBo bo = new WsPackageBo();
        bo.setId(1L);
        bo.setTargetStatus(3);
        assertThrows(JbkException.class, () -> service.shelfData(bo));
        verify(mapper, never()).update(any(), any());
    }

    // 8) 列表：范围经唯一解析器下发规范化结果；非法配置降级 scopeValid=false 而不是炸整页
    @Test
    void pageExposesNormalizedScopeAndDowngradesInvalid() {
        WsPackage valid = existing(1L, 1);
        valid.setScopeJson("{\"scopeType\":\"specified\",\"stationIds\":[\"1\"],\"deviceIds\":[\"3\"]}");
        WsPackage invalid = existing(2L, 1);
        invalid.setScopeJson("{\"scopeType\":\"specified\"}");
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<WsPackage> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20);
        page.setRecords(List.of(valid, invalid));
        page.setTotal(2);
        when(mapper.selectPage(any(), any())).thenReturn(page);

        WsPackageBo query = new WsPackageBo();
        query.setCurrent(1L);
        query.setSize(20L);
        List<WsPackageVo> list = service.pageData(query).getList();

        assertEquals(Boolean.TRUE, list.get(0).getScopeValid());
        assertEquals("specified", list.get(0).getScopeType());
        assertEquals(List.of("1"), list.get(0).getStationIds());
        assertEquals(List.of("3"), list.get(0).getDeviceIds());
        assertEquals(Boolean.FALSE, list.get(1).getScopeValid());
        assertNull(list.get(1).getScopeType());
        assertFalse(list.get(1).getScopeSummary().isEmpty());
    }
}
