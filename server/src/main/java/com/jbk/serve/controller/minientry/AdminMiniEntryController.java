package com.jbk.serve.controller.minientry;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jbk.serve.service.minientry.IMiniEntryConfigService;
import com.jbk.tool.annotation.LogOperation;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.annotation.RepeatSubmit;
import com.jbk.tool.data.minientry.po.WsMiniEntryConfig;
import com.jbk.tool.domain.R;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.satoken.StpKit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 小程序入口配置管理（S6，PC）。发布/撤回/修改全部经 @LogOperation 审计
 * （操作人+时间+请求参数即修改后内容；修改前内容以列表快照与 VERSION 链追溯）。
 *
 * @author dakang
 * @since 2026-08-07
 */
@RestController
@RequestMapping("/api/miniEntry")
@Tag(name = "小程序入口配置")
public class AdminMiniEntryController {

    @Autowired
    private IMiniEntryConfigService entryConfigService;

    @PostMapping("/list")
    @Operation(summary = "全量配置列表（含草稿/已撤回）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) })
    public R<List<WsMiniEntryConfig>> list() {
        return R.ok(entryConfigService.listForAdmin());
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/save")
    @Operation(summary = "保存草稿（新建或修改；已发布须先撤回）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) }, permission = {
            @SaCheckPermission(value = "system:minientry:edit", type = StpKit.DRIVER_MANAGE) })
    public R<Long> save(@RequestBody WsMiniEntryConfig bo) {
        Integer expectedVersion = bo.getVersion();
        return R.ok(entryConfigService.saveDraft(bo, expectedVersion));
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/publish")
    @Operation(summary = "发布（小程序即刻可见）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) }, permission = {
            @SaCheckPermission(value = "system:minientry:edit", type = StpKit.DRIVER_MANAGE) })
    public R<Boolean> publish(@RequestBody Map<String, Object> body) {
        entryConfigService.publish(longOf(body, "id"), intOf(body, "version"));
        return R.ok(true);
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/retract")
    @Operation(summary = "撤回（小程序即刻隐藏）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) }, permission = {
            @SaCheckPermission(value = "system:minientry:edit", type = StpKit.DRIVER_MANAGE) })
    public R<Boolean> retract(@RequestBody Map<String, Object> body) {
        entryConfigService.retract(longOf(body, "id"), intOf(body, "version"));
        return R.ok(true);
    }

    private static Long longOf(Map<String, Object> body, String key) {
        try {
            return Long.parseLong(String.valueOf(body.get(key)));
        }
        catch (Exception e) {
            throw new JbkException("配置标识缺失，请刷新后重试");
        }
    }

    private static Integer intOf(Map<String, Object> body, String key) {
        try {
            return Integer.parseInt(String.valueOf(body.get(key)));
        }
        catch (Exception e) {
            throw new JbkException("配置版本缺失，请刷新后重试");
        }
    }
}
