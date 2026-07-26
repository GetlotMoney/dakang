package com.jbk.serve.controller.mini;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.jbk.serve.service.mini.IMiniDeliveryService;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.data.delivery.bo.DeliveryAppealEvidenceBo;
import com.jbk.tool.data.delivery.bo.DeliveryExceptionReportBo;
import com.jbk.tool.data.delivery.bo.DeliverySignBo;
import com.jbk.tool.data.mini.bo.MiniDeliveryAcceptBo;
import com.jbk.tool.data.mini.bo.MiniDeliveryAdvanceBo;
import com.jbk.tool.data.mini.bo.MiniDeliveryTaskNoBo;
import com.jbk.tool.data.mini.bo.MiniDeliveryTaskQueryBo;
import com.jbk.tool.data.mini.vo.MiniDeliveryAppealVo;
import com.jbk.tool.data.mini.vo.MiniDeliveryExceptionVo;
import com.jbk.tool.data.mini.vo.MiniDeliveryTaskVo;
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

import java.util.List;

/**
 * 小程序配送任务（E2E-03 包B / D01·D03·D04·D05：可接列表、本人任务、接单、离站、
 * 送达、三照签收、异常上报与申诉举证）。
 *
 * <p>Controller 只做鉴权 + 会话取 userId + 参数透传（铁律6）；配送员归属、服务范围、
 * 自配送禁止、状态机与版本校验全部由包A Service 层的条件 UPDATE 裁决（铁律7），
 * 此处不重复判定也不复制状态机。</p>
 *
 * @author dakang
 * @since 2026-07-24
 */
@Tag(name = "MINI-配送任务")
@Validated
@RestController
@RequestMapping("/mini/delivery/task")
public class MiniDeliveryTaskController {

    @Autowired
    private IMiniDeliveryService miniDeliveryService;

    @PostMapping("/page")
    @Operation(summary = "任务列表：available 可接池 / active 本人进行中 / history 本人终态")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<List<MiniDeliveryTaskVo>> page(@RequestBody @Valid MiniDeliveryTaskQueryBo bo) {
        Long userId = StpKit.KH_USER.getLoginIdAsLong();
        return R.ok(miniDeliveryService.pageTasks(bo.getView(), userId));
    }

    @PostMapping("/detail")
    @Operation(summary = "任务详情（本人任务任意态；未分配任务须在范围内且订单可履约）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniDeliveryTaskVo> detail(@RequestBody @Valid MiniDeliveryTaskNoBo bo) {
        Long userId = StpKit.KH_USER.getLoginIdAsLong();
        return R.ok(miniDeliveryService.taskDetail(bo.getTaskNo(), userId));
    }

    @PostMapping("/accept")
    @Operation(summary = "接单（CAS 并发唯一；再次强制校验范围与禁自配送）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniDeliveryTaskVo> accept(@RequestBody @Valid MiniDeliveryAcceptBo bo) {
        Long userId = StpKit.KH_USER.getLoginIdAsLong();
        return R.ok(miniDeliveryService.acceptTask(bo.getTaskNo(), bo.getExpectedVersion(), userId));
    }

    @PostMapping("/advance")
    @Operation(summary = "推进任务：3离站 / 4送达（条件 UPDATE，非法跳转按影响行数拒绝）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniDeliveryTaskVo> advance(@RequestBody @Valid MiniDeliveryAdvanceBo bo) {
        Long userId = StpKit.KH_USER.getLoginIdAsLong();
        return R.ok(miniDeliveryService.advanceTask(bo.getTaskNo(), bo.getTargetStatus(),
                bo.getExpectedVersion(), userId));
    }

    @PostMapping("/sign")
    @Operation(summary = "三照签收（三照缺一不可；签收即订单完成，时间服务端生成）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniDeliveryTaskVo> sign(@RequestBody @Valid DeliverySignBo bo) {
        Long userId = StpKit.KH_USER.getLoginIdAsLong();
        return R.ok(miniDeliveryService.signTask(bo, userId));
    }

    @PostMapping("/exception/report")
    @Operation(summary = "配送异常上报（限任务归属配送员；不推进状态）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniDeliveryExceptionVo> reportException(@RequestBody @Valid DeliveryExceptionReportBo bo) {
        Long userId = StpKit.KH_USER.getLoginIdAsLong();
        return R.ok(miniDeliveryService.reportException(bo, userId));
    }

    @PostMapping("/exception/list")
    @Operation(summary = "本人任务异常记录（未分配/他人任务 fail-closed）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<List<MiniDeliveryExceptionVo>> listExceptions(@RequestBody @Valid MiniDeliveryTaskNoBo bo) {
        Long userId = StpKit.KH_USER.getLoginIdAsLong();
        return R.ok(miniDeliveryService.listTaskExceptions(bo.getTaskNo(), userId));
    }

    @PostMapping("/appeal/detail")
    @Operation(summary = "本人任务关联申诉（配送员只读；无申诉返回空）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniDeliveryAppealVo> appealDetail(@RequestBody @Valid MiniDeliveryTaskNoBo bo) {
        Long userId = StpKit.KH_USER.getLoginIdAsLong();
        return R.ok(miniDeliveryService.getTaskAppeal(bo.getTaskNo(), userId));
    }

    @PostMapping("/appeal/evidence")
    @Operation(summary = "配送员追加申诉举证（限任务归属配送员且任务申诉中）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniDeliveryAppealVo> appendEvidence(@RequestBody @Valid DeliveryAppealEvidenceBo bo) {
        Long userId = StpKit.KH_USER.getLoginIdAsLong();
        return R.ok(miniDeliveryService.appendAppealEvidence(bo, userId));
    }
}
