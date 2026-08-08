package com.jbk.serve.controller.mini;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.jbk.serve.service.minientry.IMiniEntryConfigService;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.domain.R;
import com.jbk.tool.utils.satoken.StpKit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 小程序入口配置只读（S6）：只下发已发布且启用的行；草稿与已撤回对端上不可见。
 * 内部路由的能力守卫仍由前端路由合同执行——本接口不构成授权，配置不覆盖
 * Tabbar 与账号能力权限。
 *
 * @author dakang
 * @since 2026-08-07
 */
@Tag(name = "MINI-入口配置")
@RestController
@RequestMapping("/mini/entry")
public class MiniEntryController {

    @Autowired
    private IMiniEntryConfigService entryConfigService;

    @PostMapping("/list")
    @Operation(summary = "已发布入口与内容位（异常时端上回放最后一次成功配置、从未成功则为空，核心页由固定 Tabbar 保证）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<List<Map<String, Object>>> list() {
        return R.ok(entryConfigService.listPublished().stream()
                .map(row -> Map.<String, Object>of(
                        "entryKey", row.getEntryKey(),
                        "entryType", row.getEntryType(),
                        "entryName", row.getEntryName(),
                        "sortNo", row.getSortNo(),
                        "jumpType", row.getJumpType() == null ? 0 : row.getJumpType(),
                        "routeId", row.getRouteId() == null ? "" : row.getRouteId(),
                        "externalUrl", row.getExternalUrl() == null ? "" : row.getExternalUrl(),
                        "contentText", row.getContentText() == null ? "" : row.getContentText()))
                .collect(Collectors.toList()));
    }
}
