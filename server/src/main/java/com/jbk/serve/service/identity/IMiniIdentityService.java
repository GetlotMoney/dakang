package com.jbk.serve.service.identity;

import com.jbk.tool.data.identity.bo.*;
import com.jbk.tool.data.identity.vo.*;
import com.jbk.tool.data.settlement.bo.WithdrawBo;

public interface IMiniIdentityService {
    MiniIdentityOverviewVo overview(Long userId);
    MiniIdentityOverviewVo apply(Long userId, MiniIdentityApplyBo bo);
    MiniIdentityDashboardVo channelDashboard(Long userId);
    MiniIdentityDashboardVo regionDashboard(Long userId);
    MiniIdentityDashboardVo confirmPublicLead(Long userId, MiniPublicLeadBo bo);
    MiniDemoControlVo demoControl(Long userId);
    MiniDemoControlVo updateDemoControl(Long userId, MiniDemoControlBo bo);
    String simulateWithdraw(Long userId, WithdrawBo bo);
}
