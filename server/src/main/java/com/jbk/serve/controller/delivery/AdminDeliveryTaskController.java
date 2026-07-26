package com.jbk.serve.controller.delivery;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.jbk.serve.service.delivery.IAdminDeliveryService;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.delivery.bo.AdminDeliveryTaskBo;
import com.jbk.tool.data.delivery.vo.AdminDeliveryTaskDetailVo;
import com.jbk.tool.data.delivery.vo.AdminDeliveryTaskItemVo;
import com.jbk.tool.domain.R;
import com.jbk.tool.utils.satoken.StpKit;
import com.jbk.tool.validator.group.IdGroup;
import com.jbk.tool.validator.group.PageGroup;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 配送履约监控（PC 管理端，E2E-03 包C）。
 * <p>
 * 只读：分页 + 任务详情，均需 DRIVER_MANAGE 会话（管理端全量可见，
 * 与小程序配送员端 KH_USER 本人范围区开，铁律6）。读接口沿用订单中心口径：
 * 仅登录校验、不挂功能点。PC 不承载接单/离站/送达/签收等履约动作——
 * 任务状态只能由配送员端产生，本控制器不提供任何伪造履约的写入口。
 * </p>
 *
 * @author dakang
 * @since 2026-07-24
 */
@Tag(name = "WS-订单中心-配送监控")
@Validated
@RestController
@RequestMapping("/order/delivery")
@RequiredArgsConstructor
public class AdminDeliveryTaskController {

    private final IAdminDeliveryService adminDeliveryService;

    @PostMapping("/page")
    @Operation(summary = "分页查询配送任务（状态/订单号/任务号/用户关键词筛选）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) })
    public R<PageDataVo<AdminDeliveryTaskItemVo>> page(
            @RequestBody @Validated(PageGroup.class) AdminDeliveryTaskBo bo) {
        return R.ok(adminDeliveryService.pageTasks(bo));
    }

    @PostMapping("/detail")
    @Operation(summary = "任务详情（时间线/三照媒体元数据/异常记录，共键 fail-closed）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) })
    public R<AdminDeliveryTaskDetailVo> detail(
            @RequestBody @Validated(IdGroup.class) AdminDeliveryTaskBo bo) {
        return R.ok(adminDeliveryService.taskDetail(bo.getId()));
    }
}
