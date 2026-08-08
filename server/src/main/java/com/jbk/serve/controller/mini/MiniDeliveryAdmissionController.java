package com.jbk.serve.controller.mini;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.jbk.serve.service.mini.IMiniDeliveryService;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.data.mini.vo.MiniCourierAdmissionVo;
import com.jbk.tool.domain.R;
import com.jbk.tool.utils.satoken.StpKit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 小程序配送准入状态（E2E-03 包B / D02·U03，只读）。
 * <p>准入建档与审核是 PC 人工链路（B09 已接真）；小程序端申请提交不在包B 范围，
 * 前端对应写路径保持显式 pending，绝不伪造申请成功。</p>
 *
 * @author dakang
 * @since 2026-07-24
 */
@Tag(name = "MINI-配送准入")
@Validated
@RestController
@RequestMapping("/mini/delivery/admission")
public class MiniDeliveryAdmissionController {

    @Autowired
    private IMiniDeliveryService miniDeliveryService;

    @PostMapping("/detail")
    @Operation(summary = "本人配送准入状态（无记录返回 status=0 未提交）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniCourierAdmissionVo> detail() {
        Long userId = StpKit.KH_USER.getLoginIdAsLong();
        return R.ok(miniDeliveryService.getAdmission(userId));
    }

    @PostMapping("/submit")
    @Operation(summary = "自助提交准入申请（0 未提交/4 已驳回可提交；驳回复用原记录）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniCourierAdmissionVo> submit(
            @org.springframework.web.bind.annotation.RequestBody
            @org.springframework.validation.annotation.Validated
            com.jbk.tool.data.mini.bo.MiniAdmissionSubmitBo bo) {
        // 主体恒取会话：手机号只是联系信息，不构成替他人申请的依据（S3 边界）
        Long userId = StpKit.KH_USER.getLoginIdAsLong();
        return R.ok(miniDeliveryService.submitAdmission(bo, userId));
    }
}
