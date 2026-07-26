package com.jbk.serve.service.trade.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jbk.serve.mapper.trade.RechargeIdentityMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.service.device.IWsCommandService;
import com.jbk.serve.service.mini.recharge.RechargeDetailVerifier;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.trade.bo.AdminOrderBo;
import com.jbk.tool.data.trade.vo.AdminOrderItemVo;
import com.jbk.tool.data.trade.vo.AdminOrderTraceVo;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * UI-TRACE 读侧身份聚合单测：使用人/持卡人两个身份、accessRole 与脱敏必须由服务端算好下发。
 * <p>覆盖成员单（MEMBER + 双身份齐 + 脱敏）、本人单（OWNER）、卡缺失（字段置空不 500）、
 * 异常号码（整体屏蔽不回明文）以及分页路径逐行装饰。</p>
 */
class AdminOrderActorOwnerTraceTest {

    @Test
    void memberOrderTraceCarriesBothIdentitiesMaskedAndMemberRole() {
        Fixture fixture = fixture();
        AdminOrderItemVo order = waterOrder(101L);
        order.setUserId(6L);
        order.setUserName("成员小李");
        order.setUserPhone("13800001111");
        order.setCardId(3L);
        order.setCardNo("VC-3");
        order.setCardOwnerUserId(5L);
        order.setCardOwnerName("主卡钱女士");
        order.setCardOwnerPhoneRaw("13900002222");
        order.setCardBalanceFen(8200L);
        order.setCardBalanceMl(360000L);
        Mockito.when(fixture.orderMapper.selectAdminOrderById(101L)).thenReturn(order);

        AdminOrderTraceVo trace = fixture.service.getOrderTrace(101L);

        AdminOrderItemVo decorated = trace.getOrder();
        assertEquals("MEMBER", decorated.getAccessRole());
        // 两个身份都齐：使用人来自 ws_order.USER_ID，持卡人来自 ws_card.USER_ID。
        assertEquals("成员小李", decorated.getUserName());
        assertEquals("138****1111", decorated.getActorMaskedPhone());
        assertEquals(5L, decorated.getCardOwnerUserId());
        assertEquals("主卡钱女士", decorated.getCardOwnerName());
        assertEquals("139****2222", decorated.getCardOwnerMaskedPhone());
        // 原始持卡人号码必须在服务端清空，不得随响应序列化出接口。
        assertNull(decorated.getCardOwnerPhoneRaw());
        assertEquals(8200L, decorated.getCardBalanceFen());
        assertEquals(360000L, decorated.getCardBalanceMl());
    }

    @Test
    void ownerOrderTraceResolvesOwnerRole() {
        Fixture fixture = fixture();
        AdminOrderItemVo order = waterOrder(102L);
        order.setUserId(5L);
        order.setUserName("钱女士");
        order.setUserPhone("13900002222");
        order.setCardId(3L);
        order.setCardOwnerUserId(5L);
        order.setCardOwnerName("钱女士");
        order.setCardOwnerPhoneRaw("13900002222");
        Mockito.when(fixture.orderMapper.selectAdminOrderById(102L)).thenReturn(order);

        AdminOrderTraceVo trace = fixture.service.getOrderTrace(102L);

        assertEquals("OWNER", trace.getOrder().getAccessRole());
        assertEquals("139****2222", trace.getOrder().getActorMaskedPhone());
        assertEquals("139****2222", trace.getOrder().getCardOwnerMaskedPhone());
        assertNull(trace.getOrder().getCardOwnerPhoneRaw());
    }

    @Test
    void missingCardLeavesOwnerFieldsEmptyWithoutError() {
        Fixture fixture = fixture();
        AdminOrderItemVo order = waterOrder(103L);
        order.setUserId(6L);
        order.setUserName("成员小李");
        order.setUserPhone("13800001111");
        // 卡被物理删除/查不到：LEFT JOIN 各持卡人列为 NULL，服务端必须置空而非抛错。
        order.setCardId(99L);
        order.setCardOwnerUserId(null);
        order.setCardOwnerName(null);
        order.setCardOwnerPhoneRaw(null);
        order.setCardBalanceFen(null);
        order.setCardBalanceMl(null);
        Mockito.when(fixture.orderMapper.selectAdminOrderById(103L)).thenReturn(order);

        AdminOrderTraceVo trace = fixture.service.getOrderTrace(103L);

        AdminOrderItemVo decorated = trace.getOrder();
        // 持卡人不可知时不猜测角色；持卡人脱敏号按"整体屏蔽"口径给空串。
        assertNull(decorated.getAccessRole());
        assertNull(decorated.getCardOwnerUserId());
        assertNull(decorated.getCardOwnerName());
        assertEquals("", decorated.getCardOwnerMaskedPhone());
        assertNull(decorated.getCardBalanceFen());
        assertNull(decorated.getCardBalanceMl());
        assertEquals("138****1111", decorated.getActorMaskedPhone());
    }

    @Test
    void abnormalPhoneLengthMasksToEmptyNeverPlaintext() {
        Fixture fixture = fixture();
        AdminOrderItemVo order = waterOrder(104L);
        order.setUserId(6L);
        order.setUserPhone("123");
        order.setCardId(3L);
        order.setCardOwnerUserId(5L);
        order.setCardOwnerPhoneRaw("+86-139-0000");
        Mockito.when(fixture.orderMapper.selectAdminOrderById(104L)).thenReturn(order);

        AdminOrderTraceVo trace = fixture.service.getOrderTrace(104L);

        // 长度异常一律整体屏蔽为空串，绝不把非常规号码原文透出。
        assertEquals("", trace.getOrder().getActorMaskedPhone());
        assertEquals("", trace.getOrder().getCardOwnerMaskedPhone());
        assertNull(trace.getOrder().getCardOwnerPhoneRaw());
        assertEquals("MEMBER", trace.getOrder().getAccessRole());
    }

    @Test
    void pageOrdersDecoratesEveryRecord() {
        Fixture fixture = fixture();
        AdminOrderItemVo member = waterOrder(201L);
        member.setUserId(6L);
        member.setUserPhone("13800001111");
        member.setCardOwnerUserId(5L);
        member.setCardOwnerPhoneRaw("13900002222");
        AdminOrderItemVo owner = waterOrder(202L);
        owner.setUserId(5L);
        owner.setUserPhone("13900002222");
        owner.setCardOwnerUserId(5L);
        owner.setCardOwnerPhoneRaw("13900002222");
        Page<AdminOrderItemVo> page = new Page<>(1, 10);
        page.setRecords(new ArrayList<>(List.of(member, owner)));
        page.setTotal(2);
        Mockito.when(fixture.orderMapper.pageAdminOrders(Mockito.any(), Mockito.any())).thenReturn(page);
        AdminOrderBo bo = new AdminOrderBo();
        bo.setCurrent(1L);
        bo.setSize(10L);

        PageDataVo<AdminOrderItemVo> result = fixture.service.pageOrders(bo);

        assertEquals(2, result.getList().size());
        AdminOrderItemVo first = result.getList().get(0);
        AdminOrderItemVo second = result.getList().get(1);
        assertEquals("MEMBER", first.getAccessRole());
        assertEquals("138****1111", first.getActorMaskedPhone());
        assertEquals("OWNER", second.getAccessRole());
        assertEquals("139****2222", second.getCardOwnerMaskedPhone());
        assertNull(first.getCardOwnerPhoneRaw());
        assertNull(second.getCardOwnerPhoneRaw());
    }

    /** 取水单基底：orderStatus=2（已支付未下发），CMD_ID 为空即不触发指令共键校验分支。 */
    private AdminOrderItemVo waterOrder(Long id) {
        AdminOrderItemVo order = new AdminOrderItemVo();
        order.setId(id);
        order.setOrderNo("WO-" + id);
        order.setOrderType(1);
        order.setOrderStatus(2);
        order.setOrderAmount(200L);
        order.setPayWay(3);
        return order;
    }

    private Fixture fixture() {
        WsOrderMapper orderMapper = Mockito.mock(WsOrderMapper.class);
        WsWalletFlowMapper flowMapper = Mockito.mock(WsWalletFlowMapper.class);
        IWsCommandService commandService = Mockito.mock(IWsCommandService.class);
        RechargeIdentityMapper identityMapper = Mockito.mock(RechargeIdentityMapper.class);
        RechargeDetailVerifier verifier = Mockito.mock(RechargeDetailVerifier.class);
        Mockito.when(flowMapper.selectList(Mockito.any())).thenReturn(List.of());
        AdminOrderServiceImpl service = new AdminOrderServiceImpl(
                orderMapper, flowMapper, commandService, identityMapper, verifier,
                Mockito.mock(com.jbk.serve.service.delivery.IAdminDeliveryService.class));
        return new Fixture(service, orderMapper);
    }

    private record Fixture(AdminOrderServiceImpl service, WsOrderMapper orderMapper) {
    }
}
