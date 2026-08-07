package com.jbk.serve.controller.message;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.jbk.serve.service.message.IWsMessageService;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.message.bo.WsMessageRecordBo;
import com.jbk.tool.data.message.po.WsMessage;
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
 * 管理端消息记录（E2E-07 包D / REQ-087「后台记录」）：全域站内消息只读查询，
 * 供运营核对触达事实与发送状态。无写端点——消息产生只走各业务链的同事务写入点。
 *
 * @author dakang
 * @since 2026-07-31
 */
@Tag(name = "WS-消息记录")
@Validated
@RestController
@RequestMapping("/message")
public class WsMessageController {

    @Autowired
    private IWsMessageService messageService;

    @PostMapping("/page")
    @Operation(summary = "消息记录分页（按用户/领域/发送状态筛选，只读）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) })
    public R<PageDataVo<WsMessage>> page(@RequestBody @Valid WsMessageRecordBo bo) {
        return R.ok(messageService.pageRecords(bo));
    }
}
