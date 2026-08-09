package com.jbk.serve.controller.delivery;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.jbk.serve.service.delivery.IMiniAutoRuleService;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.delivery.vo.AdminAutoRuleVo;
import com.jbk.tool.domain.R;
import com.jbk.tool.utils.satoken.StpKit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 自动补货规则后台只读查询（S2 R1）：Controller→Service→VO 分层——
 * 不直接注入 Mapper、不下发原始 Po（RULE_KEY 创建幂等键与明文电话不出接口）。
 * 启停与取消是用户自助动作，后台不代操作；执行结果穿透走订单中心。
 *
 * @author dakang
 * @since 2026-08-07
 */
@RestController
@RequestMapping("/order/autoRule")
@Tag(name = "自动补货规则查询")
public class AdminAutoRuleController {

    @Autowired
    private IMiniAutoRuleService autoRuleService;

    @PostMapping("/page")
    @Operation(summary = "规则分页（只读；电话脱敏）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) })
    public R<PageDataVo<AdminAutoRuleVo>> page(@RequestBody Map<String, Object> body) {
        long current = parseLong(body.get("current"), 1L);
        long size = parseLong(body.get("size"), 20L);
        Long userId = blank(body.get("userId")) ? null : parseLong(body.get("userId"), 0L);
        Integer status = blank(body.get("ruleStatus")) ? null : (int) parseLong(body.get("ruleStatus"), 0L);
        return R.ok(autoRuleService.pageForAdmin(userId, status, current, size));
    }

    private static boolean blank(Object raw) {
        return raw == null || String.valueOf(raw).isBlank();
    }

    private static long parseLong(Object raw, long fallback) {
        try {
            return Long.parseLong(String.valueOf(raw));
        }
        catch (Exception e) {
            return fallback;
        }
    }
}
