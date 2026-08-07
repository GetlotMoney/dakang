package com.jbk.serve.service.settlement.impl;

import com.jbk.serve.service.settlement.IGiftCardService;
import com.jbk.serve.service.settlement.RegisterGiftProperties;
import com.jbk.tool.data.settlement.bo.GiftIssueBo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-418「0 元注册送」发放判定（挂点行为由 MiniAuthServiceTest 的 newlyCreated 语义看守）。
 *
 * <p>四条不变式：开关关零行为；参数非法零行为（绝不发零权益/非法效期卡）；
 * 开启且合法时请求号由 userId 确定性派生（幂等锚）；发放异常不外抛（注册不受影响）。</p>
 */
class RegisterGiftServiceTest {

    private RegisterGiftProperties props;
    private IGiftCardService giftCardService;
    private RegisterGiftServiceImpl service;

    @BeforeEach
    void setup() {
        props = new RegisterGiftProperties();
        giftCardService = Mockito.mock(IGiftCardService.class);
        service = new RegisterGiftServiceImpl(props, giftCardService);
    }

    @Test
    void disabledSwitchDoesNothing() {
        props.setEnabled(false);
        props.setAmountFen(1000);
        props.setExpireDays(30);
        service.grantIfEnabled(9L);
        verify(giftCardService, never()).issue(any(), anyLong());
    }

    @Test
    void invalidParamsRefuseToIssueEvenWhenEnabled() {
        props.setEnabled(true);
        // 两额皆非正
        props.setAmountFen(0);
        props.setWaterMl(0);
        props.setExpireDays(30);
        service.grantIfEnabled(9L);
        // 天数越界
        props.setAmountFen(1000);
        props.setExpireDays(0);
        service.grantIfEnabled(9L);
        props.setExpireDays(3651);
        service.grantIfEnabled(9L);
        verify(giftCardService, never()).issue(any(), anyLong());
    }

    @Test
    void enabledIssuesWithDeterministicRequestIdAndSystemOperator() {
        props.setEnabled(true);
        props.setAmountFen(1000);
        props.setWaterMl(50000);
        props.setExpireDays(30);
        service.grantIfEnabled(9L);

        ArgumentCaptor<GiftIssueBo> bo = ArgumentCaptor.forClass(GiftIssueBo.class);
        ArgumentCaptor<Long> operator = ArgumentCaptor.forClass(Long.class);
        verify(giftCardService).issue(bo.capture(), operator.capture());
        assertEquals(UUID.nameUUIDFromBytes("REG-GIFT:9".getBytes(StandardCharsets.UTF_8)).toString(),
                bo.getValue().getRequestId(), "请求号由 userId 确定性派生：重复触发恒同卡号锚，终身至多一张");
        assertEquals(9L, bo.getValue().getUserId());
        assertEquals(1000L, bo.getValue().getGrantFen());
        assertEquals(50000L, bo.getValue().getGrantMl());
        assertEquals(30, bo.getValue().getExpireDays());
        assertEquals(0L, operator.getValue(), "系统发放用 0 哨兵操作人");
    }

    @Test
    void activeTransactionOnCallStackSkipsGrant() {
        props.setEnabled(true);
        props.setAmountFen(1000);
        props.setExpireDays(30);
        org.springframework.transaction.support.TransactionSynchronizationManager
                .setActualTransactionActive(true);
        try {
            service.grantIfEnabled(9L);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .setActualTransactionActive(false);
        }
        verify(giftCardService, never()).issue(any(), anyLong());
    }

    @Test
    void issueFailureNeverPropagatesToRegistration() {
        props.setEnabled(true);
        props.setAmountFen(1000);
        props.setExpireDays(30);
        when(giftCardService.issue(any(), anyLong())).thenThrow(new RuntimeException("库故障"));
        assertDoesNotThrow(() -> service.grantIfEnabled(9L), "发放失败绝不阻断注册主链");
    }
}
