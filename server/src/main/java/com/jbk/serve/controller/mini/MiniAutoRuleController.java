package com.jbk.serve.controller.mini;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.jbk.serve.service.delivery.IMiniAutoRuleService;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.data.mini.vo.MiniAutoRuleVo;
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
 * 自动补货规则用户自助管理（S2）。
 *
 * <p>主体恒取会话（KH_USER），不接受 userId 入参；ruleId 只是操作对象，
 * 归属校验在 Service 的三键 CAS 内完成（他人规则与不存在规则同报「规则不存在」）。</p>
 *
 * @author dakang
 * @since 2026-08-07
 */
@RestController
@RequestMapping("/mini/delivery/autoRule")
@Tag(name = "小程序-自动补货规则")
public class MiniAutoRuleController {

    @Autowired
    private IMiniAutoRuleService autoRuleService;

    @PostMapping("/list")
    @Operation(summary = "本人规则列表")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<List<MiniAutoRuleVo>> list() {
        return R.ok(autoRuleService.listMine(StpKit.KH_USER.getLoginIdAsLong()));
    }

    @PostMapping("/pause")
    @Operation(summary = "暂停规则")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<Boolean> pause(@RequestBody Map<String, Object> body) {
        autoRuleService.pause(StpKit.KH_USER.getLoginIdAsLong(), ruleIdOf(body));
        return R.ok(true);
    }

    @PostMapping("/resume")
    @Operation(summary = "恢复规则")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<Boolean> resume(@RequestBody Map<String, Object> body) {
        autoRuleService.resume(StpKit.KH_USER.getLoginIdAsLong(), ruleIdOf(body));
        return R.ok(true);
    }

    @PostMapping("/cancel")
    @Operation(summary = "取消规则（不可恢复）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<Boolean> cancel(@RequestBody Map<String, Object> body) {
        autoRuleService.cancel(StpKit.KH_USER.getLoginIdAsLong(), ruleIdOf(body));
        return R.ok(true);
    }

    private static Long ruleIdOf(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("ruleId");
        if (raw == null) {
            throw new JbkException("规则不存在");
        }
        try {
            long id = Long.parseLong(String.valueOf(raw));
            if (id <= 0) {
                throw new JbkException("规则不存在");
            }
            return id;
        }
        catch (NumberFormatException e) {
            throw new JbkException("规则不存在");
        }
    }
}
