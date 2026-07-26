package com.jbk.serve.controller.device;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jbk.serve.service.device.IWsCommandService;
import com.jbk.tool.annotation.LogOperation;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.annotation.RepeatSubmit;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.device.bo.WsCommandBo;
import com.jbk.tool.data.device.vo.WsCommandVo;
import com.jbk.tool.domain.R;
import com.jbk.tool.utils.satoken.StpKit;
import com.jbk.tool.validator.group.IdGroup;
import com.jbk.tool.validator.group.InsertGroup;
import com.jbk.tool.validator.group.PageGroup;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备中控——指令（下发 + 记录查询）
 * <p>安全边界：仅允许 查询状态/锁机/解锁/参数同步/重启；出水类指令由订单链路触发。
 * 锁机等高风险操作的二次验证（openSafe）属于商业一期范围；一期采用功能点权限与操作日志控制。</p>
 *
 * @author dakang
 * @since 2026-07-12
 */
@Tag(name = "WS-设备中控-指令")
@Validated
@RestController
@RequestMapping("/device/command")
public class WsCommandController {

    @Autowired
    private IWsCommandService commandService;

    @PostMapping("/page")
    @Operation(summary = "指令记录分页")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)})
    public R<PageDataVo<WsCommandVo>> page(
            @RequestBody @Validated(PageGroup.class) WsCommandBo bo) {
        return R.ok(commandService.pageData(bo));
    }

    @PostMapping("/detail")
    @Operation(summary = "指令详情（含下发/结果报文）")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)})
    public R<WsCommandVo> detail(
            @RequestBody @Validated(IdGroup.class) WsCommandBo bo) {
        return R.ok(commandService.getData(bo.getId()));
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/send")
    @Operation(summary = "下发指令（查询/锁机/解锁/参数同步/重启）")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "device:command:send", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Long> send(
            @RequestBody @Validated(InsertGroup.class) WsCommandBo bo) {
        return R.ok(commandService.send(bo));
    }
}
