package com.jbk.serve.controller.ops;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.core.util.StrUtil;
import com.jbk.serve.service.delivery.IDeliveryMediaService;
import com.jbk.serve.service.ops.IWorkOrderService;
import com.jbk.tool.annotation.LogOperation;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.annotation.RepeatSubmit;
import com.jbk.tool.consts.delivery.DeliveryEnum;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.mini.bo.MiniDeliveryMediaUploadBo;
import com.jbk.tool.data.ops.bo.WsWorkOrderBo;
import com.jbk.tool.data.ops.vo.WsWorkOrderVo;
import com.jbk.tool.domain.R;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import com.jbk.tool.utils.satoken.StpKit;
import com.jbk.tool.validator.group.IdGroup;
import com.jbk.tool.validator.group.InsertGroup;
import com.jbk.tool.validator.group.PageGroup;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/**
 * 设备运营——运维工单（E2E-05 包C/D）
 * <p>六状态单一状态机（WorkOrderTransitions）：待确认→待分配→处理中→待复核→已关闭，
 * 驳回与复核退回显式落状态。全部动作前态 CAS + 同事务审计。</p>
 *
 * @author dakang
 * @since 2026-07-30
 */
@Tag(name = "WS-设备运营-运维工单")
@Validated
@RestController
@RequestMapping("/device/workorder")
public class WsWorkOrderController {

    /** 上传入口 MIME 白名单（与小程序媒体口一致；最终防线在媒体服务） */
    private static final Set<String> UPLOAD_MIME_WHITELIST = Set.of("image/jpeg", "image/png", "image/webp");
    /** 员工门户（1364）：PC 上传的工单证据以管理端身份登记 */
    private static final int PORTAL_MANAGE = 1;

    @Autowired
    private IWorkOrderService workOrderService;
    @Autowired
    private IDeliveryMediaService mediaService;

    @PostMapping("/page")
    @Operation(summary = "工单分页（状态/类型/来源/设备/处理人筛选）")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)})
    public R<PageDataVo<WsWorkOrderVo>> page(
            @RequestBody @Validated(PageGroup.class) WsWorkOrderBo bo) {
        return R.ok(workOrderService.pageData(bo));
    }

    @PostMapping("/detail")
    @Operation(summary = "工单详情（设备/水站/告警联查 + 完整状态轨迹）")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)})
    public R<WsWorkOrderVo> detail(
            @RequestBody @Validated(IdGroup.class) WsWorkOrderBo bo) {
        return R.ok(workOrderService.getData(bo.getId()));
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/create")
    @Operation(summary = "后台建单（维修/配件/巡检，直接进待分配）")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "device:workorder:handle", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Long> create(@RequestBody @Validated(InsertGroup.class) WsWorkOrderBo bo) {
        return R.ok(workOrderService.createInspection(bo, StpKit.MANAGE.getLoginIdAsLong()));
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/confirm")
    @Operation(summary = "确认（待确认→待分配）")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "device:workorder:handle", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Boolean> confirm(@RequestBody @Validated(IdGroup.class) WsWorkOrderBo bo) {
        workOrderService.confirm(bo.getId(), StpKit.MANAGE.getLoginIdAsLong());
        return R.ok(Boolean.TRUE);
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/reject")
    @Operation(summary = "驳回（待确认→已驳回，原因必填）")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "device:workorder:handle", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Boolean> reject(@RequestBody @Validated(IdGroup.class) WsWorkOrderBo bo) {
        workOrderService.reject(bo.getId(), bo.getRejectReason(), StpKit.MANAGE.getLoginIdAsLong());
        return R.ok(Boolean.TRUE);
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/assign")
    @Operation(summary = "分配（待分配→处理中，处理人必须是有效员工）")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "device:workorder:handle", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Boolean> assign(@RequestBody @Validated(IdGroup.class) WsWorkOrderBo bo) {
        workOrderService.assign(bo.getId(), bo.getAssigneeId(), StpKit.MANAGE.getLoginIdAsLong());
        return R.ok(Boolean.TRUE);
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/submitResult")
    @Operation(summary = "提交处理结果（处理中→待复核，结果必填，证据为受控 mediaKey）")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "device:workorder:handle", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Boolean> submitResult(@RequestBody @Validated(IdGroup.class) WsWorkOrderBo bo) {
        workOrderService.submitResult(bo.getId(), bo.getFinishResult(), bo.getResultPhotos(),
                StpKit.MANAGE.getLoginIdAsLong());
        return R.ok(Boolean.TRUE);
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/reviewPass")
    @Operation(summary = "复核通过（待复核→已关闭；来源告警活动键此刻释放）")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "device:workorder:handle", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Boolean> reviewPass(@RequestBody @Validated(IdGroup.class) WsWorkOrderBo bo) {
        workOrderService.reviewPass(bo.getId(), bo.getReviewRemark(), StpKit.MANAGE.getLoginIdAsLong());
        return R.ok(Boolean.TRUE);
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/reviewReturn")
    @Operation(summary = "复核退回（待复核→处理中，意见必填）")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "device:workorder:handle", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Boolean> reviewReturn(@RequestBody @Validated(IdGroup.class) WsWorkOrderBo bo) {
        workOrderService.reviewReturn(bo.getId(), bo.getReviewRemark(), StpKit.MANAGE.getLoginIdAsLong());
        return R.ok(Boolean.TRUE);
    }

    @LogOperation
    @PostMapping("/media/upload")
    @Operation(summary = "员工登记处理证据（仅工单证据用途；同人同内容幂等返回同键）")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "device:workorder:handle", type = StpKit.DRIVER_MANAGE)}
    )
    public R<String> uploadMedia(@RequestBody @Valid MiniDeliveryMediaUploadBo bo) {
        // PC 员工口只受理工单证据：配送三照/申诉举证属于小程序用户身份域，收窄防误用
        if (DeliveryEnum.MediaPurpose.WORK_ORDER.getValue() != bo.getPurpose()) {
            throw new JbkException("本入口仅受理工单证据用途");
        }
        String mimeType = StrUtil.trimToEmpty(bo.getMimeType()).toLowerCase(Locale.ROOT);
        if (!UPLOAD_MIME_WHITELIST.contains(mimeType)) {
            throw new JbkException("仅支持 JPEG/PNG/WEBP 图片");
        }
        byte[] content;
        try {
            content = Base64.getDecoder().decode(StrUtil.trimToEmpty(bo.getContentBase64()));
        } catch (IllegalArgumentException e) {
            throw new JbkException("图片内容不是合法的 base64");
        }
        String mediaKey = mediaService.registerAs(PORTAL_MANAGE, StpKit.MANAGE.getLoginIdAsLong(),
                DeliveryEnum.MediaPurpose.WORK_ORDER, content, mimeType, DateUtils.time());
        return R.ok(mediaKey);
    }
}
