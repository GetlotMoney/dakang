package com.jbk.serve.controller.device;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jbk.serve.service.device.IDeviceControlService;
import com.jbk.tool.annotation.LogOperation;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.annotation.RepeatSubmit;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.device.bo.WsCommandBatchBo;
import com.jbk.tool.data.device.vo.DeviceControlPreviewVo;
import com.jbk.tool.data.device.vo.WsCommandBatchVo;
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
 * 设备中控——批量控制与紧急停止（E2E-05 包B，任务书 3.4/3.5）
 * <p>两步确认：preview 返回目标数量/摘要/一次性凭据，confirm 以 Redis GETDEL 领取凭据后
 * 才建批次下发。安全边界：出水类指令仅紧急停止（cmdType=2、单设备、锚定活动订单）可经
 * 本入口；批量类型限 3查询/4锁机/5解锁/6参数同步/7重启/8价格同步。</p>
 *
 * @author dakang
 * @since 2026-07-30
 */
@Tag(name = "WS-设备中控-批量控制")
@Validated
@RestController
@RequestMapping("/device/batch")
public class WsCommandBatchController {

    @Autowired
    private IDeviceControlService deviceControlService;

    @LogOperation
    @PostMapping("/preview")
    @Operation(summary = "第一步预览：服务端解析目标，返回数量/摘要/一次性操作凭据")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "device:batch:execute", type = StpKit.DRIVER_MANAGE)}
    )
    public R<DeviceControlPreviewVo> preview(
            @RequestBody @Validated(InsertGroup.class) WsCommandBatchBo bo) {
        return R.ok(deviceControlService.preview(bo, StpKit.MANAGE.getLoginIdAsLong()));
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/confirm")
    @Operation(summary = "第二步确认：GETDEL 领取凭据，建批次并逐设备展开子指令")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "device:batch:execute", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Long> confirm(@RequestBody WsCommandBatchBo bo) {
        return R.ok(deviceControlService.confirm(bo, StpKit.MANAGE.getLoginIdAsLong()));
    }

    @PostMapping("/page")
    @Operation(summary = "批次分页")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)})
    public R<PageDataVo<WsCommandBatchVo>> page(
            @RequestBody @Validated(PageGroup.class) WsCommandBatchBo bo) {
        return R.ok(deviceControlService.pageData(bo));
    }

    @PostMapping("/detail")
    @Operation(summary = "批次详情（含子指令明细）")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)})
    public R<WsCommandBatchVo> detail(
            @RequestBody @Validated(IdGroup.class) WsCommandBatchBo bo) {
        return R.ok(deviceControlService.getData(bo.getId()));
    }
}
