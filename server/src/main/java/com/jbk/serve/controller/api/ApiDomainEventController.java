package com.jbk.serve.controller.api;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.ops.bo.WsDomainEventBo;
import com.jbk.tool.data.ops.po.WsDomainEvent;
import com.jbk.tool.domain.R;
import com.jbk.tool.utils.satoken.StpKit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 领域事件白名单只读面（E2E-07 包B / REQ-083）：为 n8n 等外部编排的订阅语义预演。
 * <p>只暴露 WHITELIST_FLAG=是 的事件；无任何写端点——消费翻转（CONSUMED_FLAG）明确归二期，
 * 真实 n8n webhook 对接属外部能力闭环，不在本期。</p>
 *
 * @author dakang
 * @since 2026-07-31
 */
@Tag(name = "API-领域事件白名单")
@Validated
@RestController
@RequestMapping("/api/domainEvent")
public class ApiDomainEventController {

    @Autowired
    private IWsDomainEventService domainEventService;

    @PostMapping("/whitelistPage")
    @Operation(summary = "白名单事件只读分页（非白名单类型筛选直接拒绝）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) })
    public R<PageDataVo<WsDomainEvent>> whitelistPage(@RequestBody @Valid WsDomainEventBo bo) {
        return R.ok(domainEventService.pageWhitelist(bo.getCurrent(), bo.getSize(), bo.getEventType()));
    }
}
