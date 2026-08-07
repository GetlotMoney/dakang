package com.jbk.serve.controller.ops;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjectUtil;
import com.jbk.serve.service.ops.IWorkOrderService;
import com.jbk.serve.service.ops.IWsAlarmService;
import com.jbk.tool.annotation.LogOperation;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.annotation.RepeatSubmit;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.ops.bo.WsAlarmBo;
import com.jbk.tool.data.ops.po.WsAlarm;
import com.jbk.tool.data.ops.po.WsWorkOrder;
import com.jbk.tool.data.ops.vo.WsAlarmVo;
import com.jbk.tool.domain.R;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import com.jbk.tool.utils.OptionalUtils;
import com.jbk.tool.utils.satoken.StpKit;
import com.jbk.tool.validator.group.IdGroup;
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
 * 设备运营——告警中心（E2E-05 包C/D）
 * <p>处置动作：忽略（前态 CAS，仅待处理可忽略）、转工单（uk_wo_alarm 一告警一单）。
 * 自动恢复由心跳/状态上行触发，不提供人工「标记恢复」入口——恢复必须与设备事实同源。</p>
 *
 * @author dakang
 * @since 2026-07-30
 */
@Tag(name = "WS-设备运营-告警中心")
@Validated
@RestController
@RequestMapping("/device/alarm")
public class WsAlarmController {

    @Autowired
    private IWsAlarmService alarmService;
    @Autowired
    private IWorkOrderService workOrderService;

    @PostMapping("/page")
    @Operation(summary = "告警全量分页（类型/状态/等级/设备筛选）")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)})
    public R<PageDataVo<WsAlarmVo>> page(
            @RequestBody @Validated(PageGroup.class) WsAlarmBo bo) {
        return R.ok(alarmService.pageData(bo));
    }

    @PostMapping("/detail")
    @Operation(summary = "告警详情（含关联工单号回看）")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)})
    public R<WsAlarmVo> detail(
            @RequestBody @Validated(IdGroup.class) WsAlarmBo bo) {
        WsAlarm alarm = alarmService.getById(bo.getId());
        OptionalUtils.nullToElseThrow(alarm, "告警不存在");
        WsAlarmVo vo = BeanUtil.copyProperties(alarm, WsAlarmVo.class);
        if (ObjectUtil.isNotNull(alarm.getWorkOrderId())) {
            WsWorkOrder workOrder = workOrderService.getById(alarm.getWorkOrderId());
            if (ObjectUtil.isNotNull(workOrder)) {
                vo.setWorkOrderNo(workOrder.getOrderNo());
            }
        }
        return R.ok(vo);
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/ignore")
    @Operation(summary = "忽略告警（仅待处理；键清空后同型告警可再次触发）")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "device:alarm:handle", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Boolean> ignore(@RequestBody @Validated(IdGroup.class) WsAlarmBo bo) {
        boolean moved = alarmService.ignore(bo.getId(), StpKit.MANAGE.getLoginIdAsLong(), DateUtils.time());
        if (!moved) {
            throw new JbkException("告警已被处置或不在待处理状态");
        }
        return R.ok(Boolean.TRUE);
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/toWorkOrder")
    @Operation(summary = "告警转工单（一个告警最多一单，重复转单返回既有工单ID）")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "device:alarm:handle", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Long> toWorkOrder(@RequestBody @Validated(IdGroup.class) WsAlarmBo bo) {
        return R.ok(workOrderService.createFromAlarm(bo.getId(), StpKit.MANAGE.getLoginIdAsLong()));
    }
}
