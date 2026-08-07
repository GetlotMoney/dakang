package com.jbk.serve.controller.mini;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.jbk.serve.service.mini.IMiniCardService;
import com.jbk.serve.service.mini.IMiniGiftMergeService;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.data.mini.bo.MiniCardDetailBo;
import com.jbk.tool.data.mini.bo.MiniCardMemberRevokeBo;
import com.jbk.tool.data.mini.bo.MiniCardMemberSaveBo;
import com.jbk.tool.data.mini.bo.MiniCardMergeBo;
import com.jbk.tool.data.mini.vo.MiniCardDetailVo;
import com.jbk.tool.data.mini.vo.MiniCardMemberVo;
import com.jbk.tool.data.mini.vo.MiniCardMergeVo;
import com.jbk.tool.data.mini.vo.MiniCardSummaryVo;
import com.jbk.tool.data.mini.vo.MiniUsableCardVo;
import com.jbk.tool.domain.R;
import com.jbk.tool.utils.satoken.StpKit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import java.util.List;

/**
 * 小程序水卡（L1f 扫码取水链：本人主卡余额真实展示，修“真扣款却显陈旧 mock 余额”的资金误导）。
 * <p>经 {@link MySaCheckOr} 强制 KH_USER 登录；主卡范围一律取会话登录人，禁收前端 userId（铁律6）。</p>
 *
 * @author dakang
 * @since 2026-07-19
 */
@Tag(name = "MINI-水卡")
@RestController
@RequestMapping("/mini/card")
@RequiredArgsConstructor
public class MiniCardController {

    private final IMiniCardService miniCardService;
    private final IMiniGiftMergeService miniGiftMergeService;

    @PostMapping("/primary")
    @Operation(summary = "查询本人主水卡摘要（无卡返 null）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniCardSummaryVo> primary() {
        Long userId = StpKit.KH_USER.getLoginIdAsLong();
        return R.ok(miniCardService.getPrimaryCard(userId));
    }

    @PostMapping("/detail")
    @Operation(summary = "查询本人指定水卡详情（L2-READ；非本人卡一律拒绝）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniCardDetailVo> detail(@RequestBody @Valid MiniCardDetailBo bo) {
        // 铁律6：userId 只从会话取，绝不接受前端传入。
        Long userId = StpKit.KH_USER.getLoginIdAsLong();
        return R.ok(miniCardService.getCardDetail(bo.getCardId(), userId));
    }

    @PostMapping("/usable-list")
    @Operation(summary = "查询可用水卡列表（本人持卡 OWNER + 有效成员授权卡 MEMBER，含能力位）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<List<MiniUsableCardVo>> usableList() {
        Long userId = StpKit.KH_USER.getLoginIdAsLong();
        return R.ok(miniCardService.listUsableCards(userId));
    }

    @PostMapping("/member/save")
    @Operation(summary = "保存成员授权（新增/编辑；卡主专属，手机号只匹配已注册用户）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniCardMemberVo> saveMember(@RequestBody @Valid MiniCardMemberSaveBo bo) {
        // 铁律6：卡主身份只从会话取；卡归属在 Service 层 WHERE 内强制。
        Long userId = StpKit.KH_USER.getLoginIdAsLong();
        return R.ok(miniCardService.saveMember(bo, userId));
    }

    @PostMapping("/merge")
    @Operation(summary = "赠卡合并入正式水卡（D-415；有效期内转移权益，已过期作废清理，均注销赠卡）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniCardMergeVo> merge(@RequestBody @Valid MiniCardMergeBo bo) {
        // 铁律6：userId 只从会话取；目标正式水卡由服务端锁内定位，前端不可指定。
        Long userId = StpKit.KH_USER.getLoginIdAsLong();
        return R.ok(miniGiftMergeService.merge(bo.getCardId(), userId));
    }

    @PostMapping("/member/revoke")
    @Operation(summary = "撤销成员授权（卡主专属；条件状态更新并校验影响行数）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniCardMemberVo> revokeMember(@RequestBody @Valid MiniCardMemberRevokeBo bo) {
        Long userId = StpKit.KH_USER.getLoginIdAsLong();
        return R.ok(miniCardService.revokeMember(bo, userId));
    }
}
