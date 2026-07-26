package com.jbk.serve.service.trade.impl;

import com.jbk.serve.mapper.trade.RechargeIdentityMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.service.device.IWsCommandService;
import com.jbk.serve.service.mini.recharge.RechargeDetailVerifier;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.vo.AdminOrderItemVo;
import com.jbk.tool.data.trade.vo.AdminOrderTraceVo;
import com.jbk.tool.data.trade.vo.MiniRechargeDetailVo;
import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 管理端充值追溯只消费统一证据校验器；失败时不得回落到旧单维流水。 */
class AdminOrderRechargeTraceTest {

    @Test
    void verifiedPurchaseReturnsStructuredEvidenceWithoutLegacyFlowProjection() {
        Fixture fixture = fixture();
        MiniRechargeDetailVo detail = new MiniRechargeDetailVo()
                .setSnapshotValid(true)
                .setPurchaseMode("FIRST_CARD")
                .setCardId(3L)
                .setCardNo("VC-3")
                .setCardType(1)
                .setIssueOrderId(68L)
                .setScopeDescription("限定范围：水站 1 个");
        Mockito.when(fixture.verifier.verify(fixture.persisted)).thenReturn(detail);

        AdminOrderTraceVo trace = fixture.service.getOrderTrace(68L);

        assertEquals("ok", trace.getRecharge().getLinkStatus());
        assertEquals(detail, trace.getRecharge().getDetail());
        assertTrue(trace.getFlows().isEmpty());
    }

    @Test
    void verifierFailureReturnsMismatchAndNoPositiveEvidence() {
        Fixture fixture = fixture();
        Mockito.when(fixture.verifier.verify(fixture.persisted))
                .thenThrow(new JbkException("首次购卡流水 AFTER 与零初始权益不一致"));

        AdminOrderTraceVo trace = fixture.service.getOrderTrace(68L);

        assertEquals("mismatch", trace.getRecharge().getLinkStatus());
        assertTrue(trace.getRecharge().getLinkReason().contains("AFTER"));
        assertNull(trace.getRecharge().getDetail());
        assertTrue(trace.getFlows().isEmpty());
    }

    @Test
    void twoReadOrderMismatchFailsClosedBeforeEvidenceProjection() {
        Fixture fixture = fixture();
        fixture.persisted.setCardId(99L);

        AdminOrderTraceVo trace = fixture.service.getOrderTrace(68L);

        assertEquals("mismatch", trace.getRecharge().getLinkStatus());
        assertNotNull(trace.getRecharge().getLinkReason());
        assertNull(trace.getRecharge().getDetail());
        Mockito.verifyNoInteractions(fixture.verifier);
    }

    private Fixture fixture() {
        WsOrderMapper orderMapper = Mockito.mock(WsOrderMapper.class);
        WsWalletFlowMapper flowMapper = Mockito.mock(WsWalletFlowMapper.class);
        IWsCommandService commandService = Mockito.mock(IWsCommandService.class);
        RechargeIdentityMapper identityMapper = Mockito.mock(RechargeIdentityMapper.class);
        RechargeDetailVerifier verifier = Mockito.mock(RechargeDetailVerifier.class);

        AdminOrderItemVo admin = new AdminOrderItemVo();
        admin.setId(68L);
        admin.setOrderNo("RC-FIRST-68");
        admin.setOrderType(2);
        admin.setUserId(5L);
        admin.setCardId(3L);
        admin.setOrderAmount(10000L);
        admin.setPayWay(1);
        admin.setOrderStatus(4);
        WsOrder persisted = new WsOrder()
                .setId(68L)
                .setOrderNo("RC-FIRST-68")
                .setOrderType(2)
                .setUserId(5L)
                .setCardId(3L)
                .setOrderAmount(10000L)
                .setPayWay(1)
                .setOrderStatus(4);
        persisted.setDataStatus(0);

        Mockito.when(orderMapper.selectAdminOrderById(68L)).thenReturn(admin);
        Mockito.when(identityMapper.selectOrdersByOrderNoIncludingDeleted("RC-FIRST-68"))
                .thenReturn(List.of(persisted));
        AdminOrderServiceImpl service = new AdminOrderServiceImpl(
                orderMapper, flowMapper, commandService, identityMapper, verifier,
                Mockito.mock(com.jbk.serve.service.delivery.IAdminDeliveryService.class));
        return new Fixture(service, verifier, persisted);
    }

    private record Fixture(AdminOrderServiceImpl service, RechargeDetailVerifier verifier,
                           WsOrder persisted) {
    }
}
