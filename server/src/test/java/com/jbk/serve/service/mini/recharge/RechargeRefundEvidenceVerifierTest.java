package com.jbk.serve.service.mini.recharge;

import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.tool.consts.aftersale.AfterSaleEnum;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.data.mini.vo.MiniAfterSaleProgressVo;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class RechargeRefundEvidenceVerifierTest {

    private static final String FINISH_TIME = "20260730134330";

    private WsOrderMapper orderMapper;
    private RechargeRefundEvidenceVerifier verifier;
    private WsOrder order;
    private MiniAfterSaleProgressVo progress;

    @BeforeEach
    void setup() {
        orderMapper = Mockito.mock(WsOrderMapper.class);
        verifier = new RechargeRefundEvidenceVerifier(orderMapper);
        order = new WsOrder()
                .setId(71L)
                .setUserId(9L)
                .setOrderStatus(TradeEnum.OrderStatus.REFUNDED.getValue())
                .setFinishTime(FINISH_TIME);
        progress = new MiniAfterSaleProgressVo()
                .setAfterSaleNo("AS-REFUND-71")
                .setSourceType(AfterSaleEnum.SourceType.RECHARGE_REFUND.getValue())
                .setActionType(AfterSaleEnum.ActionType.GATEWAY_REFUND.getValue())
                .setActionStatus(AfterSaleEnum.ActionStatus.SUCCESS.getValue())
                .setRefundProductFen(5000L)
                .setRefundServiceFen(0L)
                .setRefundProductMl(500000L)
                .setRefundAmount(5000L)
                .setRefundSource(2)
                .setFinishTime(FINISH_TIME);
        when(orderMapper.selectLatestMiniAfterSaleProgress(71L, 9L)).thenReturn(progress);
    }

    @Test
    void acceptsCompleteRefundAndPartialRefundEvidence() {
        assertDoesNotThrow(() -> verifier.requireIfRefunded(order));
        order.setOrderStatus(TradeEnum.OrderStatus.PART_REFUNDED.getValue());
        assertDoesNotThrow(() -> verifier.requireIfRefunded(order));
    }

    @Test
    void rejectsMissingOrWronglyLinkedAfterSaleEvidence() {
        when(orderMapper.selectLatestMiniAfterSaleProgress(71L, 9L)).thenReturn(null);
        assertThrows(JbkException.class, () -> verifier.requireIfRefunded(order));

        when(orderMapper.selectLatestMiniAfterSaleProgress(71L, 9L)).thenReturn(progress);
        progress.setSourceType(AfterSaleEnum.SourceType.DELIVERY_APPEAL.getValue());
        JbkException wrongSource = assertThrows(JbkException.class, () -> verifier.requireIfRefunded(order));
        assertTrue(wrongSource.getMessage().contains("来源"));
    }

    @Test
    void rejectsAmountOrTerminalTimeMismatch() {
        progress.setRefundAmount(4999L);
        assertThrows(JbkException.class, () -> verifier.requireIfRefunded(order));

        progress.setRefundAmount(5000L).setFinishTime("20260730134331");
        assertThrows(JbkException.class, () -> verifier.requireIfRefunded(order));
    }

    @Test
    void ignoresNonRefundOrderStatuses() {
        order.setOrderStatus(TradeEnum.OrderStatus.FINISHED.getValue());
        assertDoesNotThrow(() -> verifier.requireIfRefunded(order));
        Mockito.verifyNoInteractions(orderMapper);
    }
}
