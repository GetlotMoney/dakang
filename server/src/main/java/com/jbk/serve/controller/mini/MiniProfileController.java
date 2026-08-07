package com.jbk.serve.controller.mini;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.jbk.serve.service.mini.IMiniProfileService;
import com.jbk.serve.service.mini.profile.ProfileAvatarStore;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.data.mini.bo.MiniProfileUpdateBo;
import com.jbk.tool.data.mini.vo.MiniAccountContextVo;
import com.jbk.tool.domain.R;
import com.jbk.tool.utils.satoken.StpKit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import java.time.Duration;

/**
 * 小程序自助资料（昵称/头像）。
 *
 * <p>更新走会话授权（铁律6：不收前端 userId）。出图端点是全仓「统一 POST+JSON」惯例的
 * 显式例外：小程序 {@code image} 标签只能发不带自定义头的 GET，会话头带不上去，
 * 访问控制改由不可猜测的内容寻址文件名承担（AV+30位十六进制，格式白名单硬校验）。
 * 键内容寻址 ⇒ 同名必同内容，响应可标记为不可变长缓存。</p>
 *
 * @author dakang
 * @since 2026-08-02
 */
@Tag(name = "MINI-资料")
@Validated
@RestController
@RequestMapping("/mini/profile")
@RequiredArgsConstructor
public class MiniProfileController {

    private final IMiniProfileService miniProfileService;

    @PostMapping("/update")
    @Operation(summary = "更新本人昵称/头像（返回刷新后的账号上下文，不换发会话）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniAccountContextVo> update(@RequestBody @Valid MiniProfileUpdateBo bo) {
        Long userId = StpKit.KH_USER.getLoginIdAsLong();
        return R.ok(miniProfileService.updateProfile(userId, bo));
    }

    @GetMapping("/avatar/{fileName}")
    @Operation(summary = "读取头像（免会话 GET；受控文件名即访问凭据）")
    public ResponseEntity<byte[]> avatar(@PathVariable("fileName") String fileName) {
        // 格式白名单在 Store 内再验一次；这里先拦掉明显非法值，非法/缺失一律裸 404（image 标签消费方，无 R 包装）
        if (fileName == null || !ProfileAvatarStore.SAFE_FILE.matcher(fileName).matches()) {
            return ResponseEntity.notFound().build();
        }
        byte[] content = miniProfileService.loadAvatar(fileName);
        if (content == null) {
            return ResponseEntity.notFound().build();
        }
        MediaType type = fileName.endsWith(".png") ? MediaType.IMAGE_PNG
                : fileName.endsWith(".webp") ? MediaType.parseMediaType("image/webp")
                : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).immutable())
                .body(content);
    }
}
