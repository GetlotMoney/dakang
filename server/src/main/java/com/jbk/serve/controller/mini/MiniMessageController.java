package com.jbk.serve.controller.mini;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.jbk.serve.service.message.IWsMessageService;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.mini.bo.MiniMessageBo;
import com.jbk.tool.data.mini.vo.MiniMessageVo;
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
 * 小程序消息中心（E2E-07 包B）：本人消息分页/详情/已读。
 * <p>收件范围一律以 KH_USER 会话 userId 过滤（铁律6，前端 userId 不收）；
 * 越权与不存在同文案。路径对齐 miniapp message.ts 冻结契约。</p>
 *
 * @author dakang
 * @since 2026-07-31
 */
@Tag(name = "MINI-消息中心")
@Validated
@RestController
@RequestMapping("/mini/message")
public class MiniMessageController {

    @Autowired
    private IWsMessageService messageService;

    @PostMapping("/page")
    @Operation(summary = "本人消息分页（可选领域筛选；发送时间倒序，无发送时间排尾）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<PageDataVo<MiniMessageVo>> page(@RequestBody @Valid MiniMessageBo bo) {
        return R.ok(messageService.pageForUser(bo, StpKit.KH_USER.getLoginIdAsLong()));
    }

    @PostMapping("/detail")
    @Operation(summary = "本人消息详情（非本人按不存在处理）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniMessageVo> detail(@RequestBody @Valid MiniMessageBo bo) {
        return R.ok(messageService.detailForUser(bo.getMessageId(), StpKit.KH_USER.getLoginIdAsLong()));
    }

    @PostMapping("/read")
    @Operation(summary = "标记已读（幂等；重复标记零变化仍成功）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<Boolean> read(@RequestBody @Valid MiniMessageBo bo) {
        messageService.markRead(bo.getMessageId(), StpKit.KH_USER.getLoginIdAsLong());
        return R.ok(Boolean.TRUE);
    }
}
