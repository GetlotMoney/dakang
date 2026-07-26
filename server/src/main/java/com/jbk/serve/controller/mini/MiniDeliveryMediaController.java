package com.jbk.serve.controller.mini;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.jbk.serve.service.mini.IMiniDeliveryService;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.data.mini.bo.MiniDeliveryMediaUploadBo;
import com.jbk.tool.data.mini.vo.MiniDeliveryMediaVo;
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

/**
 * 小程序配送受控媒体上传（E2E-03 A5 / 包B）。
 * <p>统一 POST+JSON 工程惯例，内容走 base64；登记只换受控 mediaKey，
 * 三照/举证提交时由包A 在事务内原子占用并校验归属/用途/未复用。</p>
 *
 * @author dakang
 * @since 2026-07-24
 */
@Tag(name = "MINI-配送媒体")
@Validated
@RestController
@RequestMapping("/mini/delivery/media")
public class MiniDeliveryMediaController {

    @Autowired
    private IMiniDeliveryService miniDeliveryService;

    @PostMapping("/upload")
    @Operation(summary = "登记受控媒体（JPEG/PNG/WEBP，≤10MB；同人同内容同用途幂等返回同键）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniDeliveryMediaVo> upload(@RequestBody @Valid MiniDeliveryMediaUploadBo bo) {
        Long userId = StpKit.KH_USER.getLoginIdAsLong();
        return R.ok(miniDeliveryService.uploadMedia(bo, userId));
    }
}
