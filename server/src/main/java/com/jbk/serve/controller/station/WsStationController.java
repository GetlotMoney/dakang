package com.jbk.serve.controller.station;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jbk.serve.service.station.IWsStationService;
import com.jbk.tool.annotation.LogOperation;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.annotation.RepeatSubmit;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.station.bo.WsStationBo;
import com.jbk.tool.data.station.vo.WsStationVo;
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
 * 水站管理（后台）
 *
 * @author dakang
 * @since 2026-07-12
 */
@Tag(name = "WS-水站管理")
@Validated
@RestController
@RequestMapping("/station/station")
public class WsStationController {

    @Autowired
    private IWsStationService stationService;

    @PostMapping("/page")
    @Operation(summary = "分页查询")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)})
    public R<PageDataVo<WsStationVo>> page(
            @RequestBody @Validated(PageGroup.class) WsStationBo bo) {
        return R.ok(stationService.pageData(bo));
    }

    @PostMapping("/detail")
    @Operation(summary = "详情")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)})
    public R<WsStationVo> detail(
            @RequestBody @Validated(IdGroup.class) WsStationBo bo) {
        return R.ok(stationService.getData(bo.getId()));
    }

    @PostMapping("/list")
    @Operation(summary = "全部正常水站（下拉）")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)})
    public R<List<WsStationVo>> list() {
        return R.ok(stationService.listData());
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/add")
    @Operation(summary = "新增")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "station:station:add", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Long> add(
            @RequestBody @Validated(InsertGroup.class) WsStationBo bo) {
        return R.ok(stationService.saveData(bo));
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/update")
    @Operation(summary = "修改")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "station:station:update", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Boolean> update(
            @RequestBody @Validated({IdGroup.class, InsertGroup.class}) WsStationBo bo) {
        return R.ok(stationService.updateData(bo));
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/delete")
    @Operation(summary = "删除")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "station:station:delete", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Boolean> delete(
            @RequestBody @Validated(IdGroup.class) WsStationBo bo) {
        return R.ok(stationService.deleteData(bo.getId()));
    }
}
