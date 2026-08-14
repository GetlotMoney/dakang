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
 *
 * <p>安全边界：本入口只受理「只读 / 单台可逆」两档，类型由 {@code DeviceCommandRisk}
 * 按 {@code DeviceEnum.CmdType} 的档位派生（不是手写白名单）；出水类由订单链路触发。
 * 恒加闸档（紧急停机、价格同步）与范围 ≥ 水站的批量操作，其二次验证在
 * {@code /device/batch/confirm} 执行。口径以 decisions.md **D-423** 为准，此处不复述规则。</p>
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
    @Operation(summary = "下发指令（只读/单台可逆档，受理类型按 D-423 档位派生）")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "device:command:send", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Long> send(
            @RequestBody @Validated(InsertGroup.class) WsCommandBo bo) {
        return R.ok(commandService.send(bo));
    }
}
