package com.jbk.serve.controller.mini;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.jbk.serve.service.mini.IMiniOwnerService;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.annotation.RepeatSubmit;
import com.jbk.tool.data.mini.bo.MiniOwnerBo;
import com.jbk.tool.data.mini.vo.MiniOwnerDeviceVo;
import com.jbk.tool.data.mini.vo.MiniOwnerServiceVo;
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
 * 小程序机主域（E2E-05 包E）：本人设备列表/详情、维修与配件申报、本人申请列表与轨迹。
 * <p>数据范围一律以 KH_USER 会话 userId 对 OWNER_USER_ID 过滤（铁律6，前端 userId 不收）；
 * 申报证据经 /mini/delivery/media/upload（purpose=4 工单证据）先登记换受控 mediaKey。</p>
 *
 * @author dakang
 * @since 2026-07-30
 */
@Tag(name = "MINI-机主设备与服务")
@Validated
@RestController
@RequestMapping("/mini/owner")
public class MiniOwnerController {

    @Autowired
    private IMiniOwnerService miniOwnerService;
    @Autowired
    private com.jbk.serve.service.settlement.IIncomeService incomeService;

    @PostMapping("/device/list")
    @Operation(summary = "本人设备列表（OWNER_USER_ID 服务端过滤）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<List<MiniOwnerDeviceVo>> deviceList() {
        return R.ok(miniOwnerService.listDevices(StpKit.KH_USER.getLoginIdAsLong()));
    }

    @PostMapping("/device/detail")
    @Operation(summary = "本人设备详情（在线/运行/故障/遥测摘要；非本人按不存在处理）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniOwnerDeviceVo> deviceDetail(@RequestBody @Valid MiniOwnerBo bo) {
        return R.ok(miniOwnerService.deviceDetail(bo.getDeviceNo(), StpKit.KH_USER.getLoginIdAsLong()));
    }

    @RepeatSubmit
    @PostMapping("/service/create")
    @Operation(summary = "维修/配件申报（requestId 幂等，落真实工单，入口待确认）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniOwnerServiceVo> serviceCreate(@RequestBody @Valid MiniOwnerBo bo) {
        return R.ok(miniOwnerService.applyService(bo, StpKit.KH_USER.getLoginIdAsLong()));
    }

    @PostMapping("/service/list")
    @Operation(summary = "本人服务申请列表（APPLICANT_USER_ID 过滤）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<List<MiniOwnerServiceVo>> serviceList() {
        return R.ok(miniOwnerService.listServiceRequests(StpKit.KH_USER.getLoginIdAsLong()));
    }

    @PostMapping("/overview")
    @Operation(summary = "经营概览（订单口径毛额，近7自然日；双轨归属去重，无归属拒绝）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<com.jbk.tool.data.mini.vo.MiniOwnerOverviewVo> overview() {
        return R.ok(miniOwnerService.overview(StpKit.KH_USER.getLoginIdAsLong()));
    }

    @PostMapping("/transaction/page")
    @Operation(summary = "交易快照分页（毛额口径，退款状态如实；不含用户身份字段）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<com.jbk.tool.data.PageDataVo<com.jbk.tool.data.mini.vo.MiniOwnerTransactionVo>> transactionPage(
            @RequestBody @Valid com.jbk.tool.data.mini.bo.MiniOwnerTransactionBo bo) {
        return R.ok(miniOwnerService.transactionPage(bo, StpKit.KH_USER.getLoginIdAsLong()));
    }

    @PostMapping("/service/detail")
    @Operation(summary = "本人服务申请详情（含处理轨迹；非本人按不存在处理）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniOwnerServiceVo> serviceDetail(@RequestBody @Valid MiniOwnerBo bo) {
        return R.ok(miniOwnerService.serviceDetail(bo.getRequestId(), StpKit.KH_USER.getLoginIdAsLong()));
    }

    @com.jbk.tool.annotation.RepeatSubmit
    @PostMapping("/wallet/withdraw")
    @Operation(summary = "提现申请骨架（余额转冻结，后台审核；本期不做真实出金）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<Boolean> walletWithdraw(@RequestBody @Valid com.jbk.tool.data.settlement.bo.WithdrawBo bo) {
        incomeService.applyWithdraw(StpKit.KH_USER.getLoginIdAsLong(), bo.getAmountFen(), bo.getRequestId());
        return R.ok(Boolean.TRUE);
    }

    @PostMapping("/wallet")
    @Operation(summary = "收益钱包（E2E-08：分润净额账务口径，与经营毛额并存；铁律6 会话过滤）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<com.jbk.tool.data.mini.vo.MiniWalletVo> wallet() {
        return R.ok(incomeService.walletFor(StpKit.KH_USER.getLoginIdAsLong()));
    }
}
