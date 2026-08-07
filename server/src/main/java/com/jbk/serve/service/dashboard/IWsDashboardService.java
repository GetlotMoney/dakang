package com.jbk.serve.service.dashboard;

import com.jbk.tool.data.dashboard.vo.DashboardOverviewVo;

/**
 * 运营总览聚合（只读）。口径固化在 {@link DashboardOverviewVo} 类注释，改口径先改注释。
 *
 * @author dakang
 * @since 2026-08-02
 */
public interface IWsDashboardService {

    /** 拉取总览快照：今日指标 + 24h 指令三态 + 待办计数 + 近 7 日趋势（含今日，缺日补零）。 */
    DashboardOverviewVo overview();
}
