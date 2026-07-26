package com.jbk.serve.service.mini.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jbk.serve.mapper.device.WsCommandMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.data.device.po.WsCommand;
import com.jbk.tool.data.trade.po.WsOrder;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 出水指令下发异常后的兜底（链路一 S0-C）：
 * 异常不得被完全吞掉且库中无任何痕迹——无指令行时把已支付订单转「6 异常待补偿」，
 * 已有指令/已绑定 CMD_ID 时交既有指令超时联动，不重复改单。
 */
class MiniOrderDispatchFallbackTest {

    private WsOrderMapper wsOrderMapper;
    private WsCommandMapper commandMapper;
    private IWsDomainEventService domainEventService;
    private MiniOrderServiceImpl service;

    private static final Long ORDER_ID = 7L;
    private static final String ORDER_NO = "WO20260721ABCDEF";

    static {
        // 纯单测无 MP mapper 扫描；lambda 条件构造器需要 TableInfo 才能解析列名，此处显式初始化。
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, WsOrder.class);
        TableInfoHelper.initTableInfo(assistant, WsCommand.class);
    }

    @BeforeEach
    void setup() {
        wsOrderMapper = Mockito.mock(WsOrderMapper.class);
        commandMapper = Mockito.mock(WsCommandMapper.class);
        domainEventService = Mockito.mock(IWsDomainEventService.class);
        service = new MiniOrderServiceImpl();
        ReflectionTestUtils.setField(service, "wsOrderMapper", wsOrderMapper);
        ReflectionTestUtils.setField(service, "commandMapper", commandMapper);
        ReflectionTestUtils.setField(service, "domainEventService", domainEventService);
    }

    private WsOrder paidOrder(Long cmdId) {
        return new WsOrder()
                .setId(ORDER_ID)
                .setOrderNo(ORDER_NO)
                .setOrderType(TradeEnum.OrderType.WATER.getValue())
                .setOrderStatus(TradeEnum.OrderStatus.PAID.getValue())
                .setCmdId(cmdId);
    }

    // 指令落库前失败（无 CMD_ID、无指令行）→ 订单转异常待补偿并记录领域事件。
    // 断言可证伪：捕获真实 UpdateWrapper，校验 SET 目标态为 ABNORMAL(6)、WHERE 含 ORDER_STATUS=PAID 与 CMD_ID IS NULL。
    @Test
    void marksOrderAbnormalWithCasPredicates() {
        when(wsOrderMapper.selectById(ORDER_ID)).thenReturn(paidOrder(null));
        when(commandMapper.selectCount(any())).thenReturn(0L);
        when(wsOrderMapper.update(isNull(), any())).thenReturn(1);

        service.markAbnormalWhenDispatchLeftNoTrace(ORDER_ID, ORDER_NO, new RuntimeException("出水口不存在"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<WsOrder>> captor =
                ArgumentCaptor.forClass((Class<LambdaUpdateWrapper<WsOrder>>) (Class<?>) LambdaUpdateWrapper.class);
        verify(wsOrderMapper, times(1)).update(isNull(), captor.capture());

        LambdaUpdateWrapper<WsOrder> wrapper = captor.getValue();
        Map<String, Object> params = wrapper.getParamNameValuePairs();
        String sqlSet = renderParams(wrapper.getSqlSet(), params);
        String where = wrapper.getTargetSql().replace(" ", "");
        Collection<Object> values = params.values();

        // SET：目标状态必须是 6 异常待补偿（改成 5 或删掉本行，此断言即变红）
        assertTrue(sqlSet.contains("ORDER_STATUS=" + TradeEnum.OrderStatus.ABNORMAL.getValue()),
                "SET 必须把订单置为 ABNORMAL(6)，实际 SET=" + sqlSet);
        // WHERE：CAS 三谓词缺一不可（删掉任一 eq/isNull，对应断言即变红）
        assertTrue(where.contains("ID=?"), "WHERE 必须锁定订单 ID，实际=" + where);
        assertTrue(where.contains("ORDER_STATUS=?"), "WHERE 必须含 ORDER_STATUS 的 CAS 谓词，实际=" + where);
        assertTrue(where.contains("CMD_IDISNULL"), "WHERE 必须含 CMD_ID IS NULL 的 CAS 谓词，实际=" + where);
        // WHERE 绑定值必须是本订单与 PAID(2)，而非任意值
        assertTrue(values.contains(ORDER_ID), "WHERE 绑定值必须含订单 ID，实际=" + values);
        assertTrue(values.contains(TradeEnum.OrderStatus.PAID.getValue()),
                "WHERE 绑定值必须含 PAID(2)，实际=" + values);

        verify(domainEventService, times(1))
                .record(eq(OpsEnum.EventType.ORDER_STATUS), eq(ORDER_NO), any(), anyString());
    }

    /** 把 #{ew.paramNameValuePairs.MPGENVALn} 占位符替换为实际值，便于对 SQL 片段做可证伪断言。 */
    private String renderParams(String sql, Map<String, Object> params) {
        String out = String.valueOf(sql);
        for (Map.Entry<String, Object> e : params.entrySet()) {
            out = out.replace("#{ew.paramNameValuePairs." + e.getKey() + "}", String.valueOf(e.getValue()));
        }
        return out;
    }

    // 已绑定 CMD_ID → 既有指令联动负责，不重复改单
    @Test
    void doesNotTouchOrderWhenCommandAlreadyBound() {
        when(wsOrderMapper.selectById(ORDER_ID)).thenReturn(paidOrder(99L));

        service.markAbnormalWhenDispatchLeftNoTrace(ORDER_ID, ORDER_NO, new RuntimeException("publish 失败"));

        verify(wsOrderMapper, never()).update(any(), any());
        verify(domainEventService, never()).record(any(), anyString(), any(), any());
    }

    // 指令行已存在（PENDING/FAILED 均可被超时兜底扫到）→ 不重复改单
    @Test
    void doesNotTouchOrderWhenCommandRowExists() {
        when(wsOrderMapper.selectById(ORDER_ID)).thenReturn(paidOrder(null));
        when(commandMapper.selectCount(any())).thenReturn(1L);

        service.markAbnormalWhenDispatchLeftNoTrace(ORDER_ID, ORDER_NO, new RuntimeException("claim 输方"));

        verify(wsOrderMapper, never()).update(any(), any());
    }

    // CAS 未命中（订单已被并发推进）→ 不记事件，不抛异常
    @Test
    void staysSilentWhenCasMisses() {
        when(wsOrderMapper.selectById(ORDER_ID)).thenReturn(paidOrder(null));
        when(commandMapper.selectCount(any())).thenReturn(0L);
        when(wsOrderMapper.update(isNull(), any())).thenReturn(0);

        service.markAbnormalWhenDispatchLeftNoTrace(ORDER_ID, ORDER_NO, new RuntimeException("x"));

        verify(domainEventService, never()).record(any(), anyString(), any(), any());
    }

    // 兜底自身异常不得外抛，避免影响下单响应
    @Test
    void neverThrowsEvenWhenFallbackItselfFails() {
        when(wsOrderMapper.selectById(ORDER_ID)).thenThrow(new RuntimeException("DB down"));

        service.markAbnormalWhenDispatchLeftNoTrace(ORDER_ID, ORDER_NO, new RuntimeException("x"));
    }
}
