package com.jbk.serve.service.identity.impl;

import com.jbk.serve.mapper.device.WsDeviceMapper;
import com.jbk.serve.mapper.device.WsDeviceOutletMapper;
import com.jbk.serve.mapper.device.WsQrcodeMapper;
import com.jbk.serve.mapper.identity.WsDemoControlMapper;
import com.jbk.serve.mapper.identity.WsIdentityApplicationMapper;
import com.jbk.serve.mapper.identity.WsIdentityAuditMapper;
import com.jbk.serve.mapper.identity.WsIdentityProfileMapper;
import com.jbk.serve.mapper.identity.WsInviteCodeMapper;
import com.jbk.serve.mapper.identity.WsPublicLeadMapper;
import com.jbk.serve.mapper.identity.WsWithdrawOrderMapper;
import com.jbk.serve.mapper.product.WsWaterTypeMapper;
import com.jbk.serve.mapper.settlement.WsOwnerAttributionMapper;
import com.jbk.serve.mapper.settlement.WsOwnerReferrerMapper;
import com.jbk.serve.mapper.station.WsStationMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.user.WsCourierMapper;
import com.jbk.serve.mapper.user.WsUserMapper;
import com.jbk.serve.service.settlement.IIncomeService;
import com.jbk.tool.data.identity.po.WsIdentityProfile;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class MiniIdentityServiceImplTest {

    @Test
    void noRegionalProfileKeepsOwnerLeadUnassignedInsteadOfCrashing() {
        MiniIdentityServiceImpl service = new MiniIdentityServiceImpl(
                mock(WsIdentityApplicationMapper.class), mock(WsIdentityProfileMapper.class),
                mock(WsIdentityAuditMapper.class), mock(WsInviteCodeMapper.class),
                mock(WsPublicLeadMapper.class), mock(WsWithdrawOrderMapper.class),
                mock(WsDemoControlMapper.class), mock(WsUserMapper.class),
                mock(WsCourierMapper.class), mock(WsStationMapper.class),
                mock(WsDeviceMapper.class), mock(WsDeviceOutletMapper.class),
                mock(WsQrcodeMapper.class), mock(WsWaterTypeMapper.class),
                mock(WsOwnerReferrerMapper.class), mock(WsOwnerAttributionMapper.class),
                mock(WsOrderMapper.class), mock(IIncomeService.class));

        WsIdentityProfile selected = ReflectionTestUtils.invokeMethod(
                service, "leastLoadedProfile", List.<WsIdentityProfile>of());

        assertNull(selected, "冷启动没有区域代理时应进入公域待分配，而不是让机主申请返回 500");
    }
}
