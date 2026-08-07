package com.jbk.serve.controller.dashboard;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.jbk.serve.service.dashboard.IWsDashboardService;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.data.dashboard.vo.DashboardOverviewVo;
import com.jbk.tool.domain.R;
import com.jbk.tool.utils.satoken.StpKit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营总览（PC 5+1 之一）：真实库聚合快照，取代前端演示数据源。
 * <p>只读、无入参、管理端登录即可见；不挂操作日志（与字典读同理：
 * 每次进首页都会拉，记日志只会重演噪音淹没审计的事故）。</p>
 *
 * @author dakang
 * @since 2026-08-02
 */
@Tag(name = "WS-运营总览")
@RestController
@RequestMapping("/ws/dashboard")
@RequiredArgsConstructor
public class WsDashboardController {

    private final IWsDashboardService dashboardService;

    @PostMapping("/overview")
    @Operation(summary = "总览聚合快照（今日指标/24h指令三态/待办计数/近7日趋势）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) })
    public R<DashboardOverviewVo> overview() {
        return R.ok(dashboardService.overview());
    }
}
