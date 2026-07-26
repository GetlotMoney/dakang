package com.jbk.serve.controller.device;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jbk.serve.service.device.IWsDeviceOutletService;
import com.jbk.tool.annotation.LogOperation;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.annotation.RepeatSubmit;
import com.jbk.tool.data.device.bo.WsDeviceOutletBo;
import com.jbk.tool.data.device.vo.WsDeviceOutletVo;
import com.jbk.tool.domain.R;
import com.jbk.tool.utils.satoken.StpKit;
import com.jbk.tool.validator.group.IdGroup;
import com.jbk.tool.validator.group.InsertGroup;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 设备中控——出水口（设备详情页内管理）
 * <p>权限说明：出水口是设备档案的组成部分，写操作复用 device:device:update 功能点，
 * 不单设权限（沿用设备编辑权限，最终菜单见 03-demo-baseline.sql）。</p>
 *
 * @author dakang
 * @since 2026-07-12
 */
@Tag(name = "WS-设备中控-出水口")
@Validated
@RestController
@RequestMapping("/device/outlet")
public class WsDeviceOutletController {

    @Autowired
    private IWsDeviceOutletService outletService;

    @PostMapping("/listByDevice")
    @Operation(summary = "按设备查询出水口")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)})
    public R<List<WsDeviceOutletVo>> listByDevice(
            @RequestBody @Validated(InsertGroup.class) DeviceIdBo bo) {
        return R.ok(outletService.listByDevice(bo.getDeviceId()));
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/add")
    @Operation(summary = "新增出水口")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "device:device:update", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Long> add(
            @RequestBody @Validated(InsertGroup.class) WsDeviceOutletBo bo) {
        return R.ok(outletService.saveData(bo));
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/update")
    @Operation(summary = "修改出水口")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "device:device:update", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Boolean> update(
            @RequestBody @Validated({IdGroup.class, InsertGroup.class}) WsDeviceOutletBo bo) {
        return R.ok(outletService.updateData(bo));
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/delete")
    @Operation(summary = "删除出水口")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "device:device:update", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Boolean> delete(
            @RequestBody @Validated(IdGroup.class) WsDeviceOutletBo bo) {
        return R.ok(outletService.deleteData(bo.getId()));
    }

    /** listByDevice 入参（仅设备ID） */
    public static class DeviceIdBo {
        @NotNull(groups = InsertGroup.class, message = "设备信息不为空")
        private Long deviceId;

        public Long getDeviceId() {
            return deviceId;
        }

        public void setDeviceId(Long deviceId) {
            this.deviceId = deviceId;
        }
    }
}
