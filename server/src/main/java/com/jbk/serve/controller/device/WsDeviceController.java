package com.jbk.serve.controller.device;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jbk.serve.service.device.IWsDeviceService;
import com.jbk.serve.service.ops.IWsAlarmService;
import com.jbk.tool.annotation.LogOperation;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.annotation.RepeatSubmit;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.device.bo.WsDeviceBo;
import com.jbk.tool.data.device.vo.WsDeviceTelemetryVo;
import com.jbk.tool.data.device.vo.WsDeviceVo;
import com.jbk.tool.data.device.vo.WsQrcodeVo;
import com.jbk.tool.data.ops.bo.WsAlarmBo;
import com.jbk.tool.data.ops.vo.WsAlarmVo;
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

import java.util.List;

/**
 * 设备中控——设备档案（后台）
 * <p>状态字段（在线/运行/心跳/故障码/信号）由设备上行链路维护，本控制器仅承载档案；
 * 二维码/遥测/告警为详情页只读数据（完整管理为商业一期）。</p>
 *
 * @author dakang
 * @since 2026-07-12
 */
@Tag(name = "WS-设备中控-设备档案")
@Validated
@RestController
@RequestMapping("/device/device")
public class WsDeviceController {

    @Autowired
    private IWsDeviceService deviceService;
    @Autowired
    private IWsAlarmService alarmService;

    @PostMapping("/page")
    @Operation(summary = "分页查询")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)})
    public R<PageDataVo<WsDeviceVo>> page(
            @RequestBody @Validated(PageGroup.class) WsDeviceBo bo) {
        return R.ok(deviceService.pageData(bo));
    }

    @PostMapping("/detail")
    @Operation(summary = "详情")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)})
    public R<WsDeviceVo> detail(
            @RequestBody @Validated(IdGroup.class) WsDeviceBo bo) {
        return R.ok(deviceService.getData(bo.getId()));
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/add")
    @Operation(summary = "新增（初始未激活，首个心跳激活）")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "device:device:add", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Long> add(
            @RequestBody @Validated(InsertGroup.class) WsDeviceBo bo) {
        return R.ok(deviceService.saveData(bo));
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/update")
    @Operation(summary = "修改（设备编号不可改）")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "device:device:update", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Boolean> update(
            @RequestBody @Validated({IdGroup.class, InsertGroup.class}) WsDeviceBo bo) {
        return R.ok(deviceService.updateData(bo));
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/delete")
    @Operation(summary = "删除（绑定二维码禁删）")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "device:device:delete", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Boolean> delete(
            @RequestBody @Validated(IdGroup.class) WsDeviceBo bo) {
        return R.ok(deviceService.deleteData(bo.getId()));
    }

    @PostMapping("/qrcodeList")
    @Operation(summary = "设备二维码列表（一期只读）")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)})
    public R<List<WsQrcodeVo>> qrcodeList(
            @RequestBody @Validated(IdGroup.class) WsDeviceBo bo) {
        return R.ok(deviceService.qrcodeList(bo.getId()));
    }

    @PostMapping("/latestTelemetry")
    @Operation(summary = "最近遥测（TDS/滤芯/信号）")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)})
    public R<WsDeviceTelemetryVo> latestTelemetry(
            @RequestBody @Validated(IdGroup.class) WsDeviceBo bo) {
        return R.ok(deviceService.latestTelemetry(bo.getId()));
    }

    @PostMapping("/alarmPage")
    @Operation(summary = "设备告警分页（一期只读提醒）")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)})
    public R<PageDataVo<WsAlarmVo>> alarmPage(
            @RequestBody @Validated(PageGroup.class) WsAlarmBo bo) {
        return R.ok(alarmService.pageByDevice(bo));
    }
}
