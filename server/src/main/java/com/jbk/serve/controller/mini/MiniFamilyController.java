package com.jbk.serve.controller.mini;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.jbk.serve.service.mini.IMiniFamilyService;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.data.mini.bo.MiniAddressSaveBo;
import com.jbk.tool.data.mini.bo.MiniFamilySaveBo;
import com.jbk.tool.data.mini.vo.MiniAddressVo;
import com.jbk.tool.data.mini.vo.MiniFamilyProfileVo;
import com.jbk.tool.domain.R;
import com.jbk.tool.utils.satoken.StpKit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import java.util.List;
import java.util.Map;

/**
 * 小程序家庭资料与配送地址（2026-08-02 链路落地；此前仅前端本机存储）。
 * <p>全部会话授权（铁律6：不收前端 userId）；地址电话对外恒 PhoneMask 脱敏，
 * 编辑动线要求重填完整号码，原号绝不回流。</p>
 *
 * @author dakang
 * @since 2026-08-02
 */
@Tag(name = "MINI-家庭与地址")
@Validated
@RestController
@RequestMapping("/mini/family")
@RequiredArgsConstructor
public class MiniFamilyController {

    private final IMiniFamilyService familyService;

    private static Long userId() {
        return StpKit.KH_USER.getLoginIdAsLong();
    }

    @PostMapping("/address/list")
    @Operation(summary = "本人地址簿（默认在前，电话脱敏）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<List<MiniAddressVo>> listAddresses() {
        return R.ok(familyService.listAddresses(userId()));
    }

    @PostMapping("/address/get")
    @Operation(summary = "地址详情（编辑回显；电话脱敏，改号须重填）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniAddressVo> getAddress(@RequestBody Map<String, Long> body) {
        return R.ok(familyService.getAddress(userId(), body.get("addressId")));
    }

    @PostMapping("/address/save")
    @Operation(summary = "新增/编辑地址（默认位事务互斥；上限 20 条）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniAddressVo> saveAddress(@RequestBody @Valid MiniAddressSaveBo bo) {
        return R.ok(familyService.saveAddress(userId(), bo));
    }

    @PostMapping("/address/delete")
    @Operation(summary = "删除地址（逻辑删除；归属校验 fail-closed）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<Void> deleteAddress(@RequestBody Map<String, Long> body) {
        familyService.deleteAddress(userId(), body.get("addressId"));
        return R.ok(null);
    }

    @PostMapping("/profile/get")
    @Operation(summary = "家庭资料（未建档返回 null）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniFamilyProfileVo> getProfile() {
        return R.ok(familyService.getFamilyProfile(userId()));
    }

    @PostMapping("/profile/save")
    @Operation(summary = "保存家庭资料（首存必须同意隐私说明，同意时间记服务端时间）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniFamilyProfileVo> saveProfile(@RequestBody @Valid MiniFamilySaveBo bo) {
        return R.ok(familyService.saveFamilyProfile(userId(), bo));
    }

    @PostMapping("/profile/delete")
    @Operation(summary = "删除家庭资料（逻辑删除）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<Void> deleteProfile() {
        familyService.deleteFamilyProfile(userId());
        return R.ok(null);
    }
}
