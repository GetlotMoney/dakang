package com.jbk.serve.controller.settlement;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jbk.serve.mapper.settlement.WsSplitConfigMapper;
import com.jbk.serve.mapper.settlement.WsReconcileDiffMapper;
import com.jbk.serve.service.settlement.IIncomeService;
import com.jbk.serve.service.settlement.IReconcileService;
import com.jbk.serve.service.settlement.ISplitService;
import com.jbk.tool.consts.settlement.SettlementEnum;
import com.jbk.tool.annotation.LogOperation;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.annotation.RepeatSubmit;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.settlement.bo.FinanceQueryBo;
import com.jbk.tool.data.settlement.bo.WithdrawBo;
import com.jbk.tool.data.settlement.po.WsReconcileDiff;
import com.jbk.tool.data.settlement.po.WsReconcileTask;
import com.jbk.tool.data.settlement.po.WsSplitConfig;
import com.jbk.tool.data.settlement.po.WsSplitRecord;
import com.jbk.tool.domain.R;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import com.jbk.tool.utils.satoken.StpKit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PC 财务面（E2E-08 包E）：分账明细 / 日对账 / 比例配置，全部挂 /finance 前缀
 * （nginx 与 vite 双处代理已同步登记，前缀漏配=E2E-07 的 405 同型事故）。
 * <p>读端仅登录门槛（与既有 ops 只读面同惯例）；对账触发与配置变更为高风险写，
 * 三件套（LogOperation+RepeatSubmit+权限码）对齐 changeStatus 先例。</p>
 *
 * @author dakang
 * @since 2026-07-31
 */
@Tag(name = "WS-财务分账与对账")
@Validated
@RestController
@RequestMapping("/finance")
public class WsFinanceController {

    @Autowired
    private ISplitService splitService;
    @Autowired
    private IReconcileService reconcileService;
    @Autowired
    private WsSplitConfigMapper configMapper;
    @Autowired
    private com.jbk.serve.service.settlement.ISplitConfigService splitConfigService;
    @Autowired
    private WsReconcileDiffMapper diffMapper;
    @Autowired
    private IIncomeService incomeService;

    @PostMapping("/split/page")
    @Operation(summary = "分账明细分页（按订单/收款方/状态筛选，只读）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) })
    public R<PageDataVo<WsSplitRecord>> splitPage(@RequestBody @Valid FinanceQueryBo bo) {
        Page<WsSplitRecord> page = splitService.page(new Page<>(bo.pageOrDefault(), bo.sizeOrDefault()),
                Wrappers.lambdaQuery(WsSplitRecord.class)
                        .eq(ObjectUtil.isNotNull(bo.getOrderId()), WsSplitRecord::getOrderId, bo.getOrderId())
                        .eq(ObjectUtil.isNotNull(bo.getReceiverType()), WsSplitRecord::getReceiverType, bo.getReceiverType())
                        .eq(ObjectUtil.isNotNull(bo.getSplitStatus()), WsSplitRecord::getSplitStatus, bo.getSplitStatus())
                        .orderByDesc(WsSplitRecord::getId));
        return R.ok(PageDataVo.getPageData(page.getRecords(), page.getTotal()));
    }

    @PostMapping("/reconcile/taskPage")
    @Operation(summary = "日对账批任务分页（只读）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) })
    public R<PageDataVo<WsReconcileTask>> reconcileTaskPage(@RequestBody @Valid FinanceQueryBo bo) {
        Page<WsReconcileTask> page = reconcileService.page(new Page<>(bo.pageOrDefault(), bo.sizeOrDefault()),
                Wrappers.lambdaQuery(WsReconcileTask.class)
                        .eq(StrUtil.isNotBlank(bo.getBizDate()), WsReconcileTask::getBizDate, bo.getBizDate())
                        .orderByDesc(WsReconcileTask::getBizDate));
        return R.ok(PageDataVo.getPageData(page.getRecords(), page.getTotal()));
    }

    @PostMapping("/reconcile/diffPage")
    @Operation(summary = "对账差异台账分页（按账期/维度/分类筛选，只读）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) })
    public R<PageDataVo<WsReconcileDiff>> reconcileDiffPage(@RequestBody @Valid FinanceQueryBo bo) {
        Page<WsReconcileDiff> page = new Page<>(bo.pageOrDefault(), bo.sizeOrDefault());
        page = diffMapper.selectPage(page, Wrappers.lambdaQuery(WsReconcileDiff.class)
                .eq(StrUtil.isNotBlank(bo.getBizDate()), WsReconcileDiff::getBizDate, bo.getBizDate())
                .eq(ObjectUtil.isNotNull(bo.getDiffType()), WsReconcileDiff::getDiffType, bo.getDiffType())
                .orderByDesc(WsReconcileDiff::getId));
        return R.ok(PageDataVo.getPageData(page.getRecords(), page.getTotal()));
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/reconcile/run")
    @Operation(summary = "手动触发指定账期对账（高风险：重算整批替换差异台账）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) }, permission = {
            @SaCheckPermission(value = "finance:reconcile:run", type = StpKit.DRIVER_MANAGE) })
    public R<WsReconcileTask> reconcileRun(@RequestBody @Valid FinanceQueryBo bo) {
        if (StrUtil.isBlank(bo.getBizDate())) {
            throw new JbkException("账期日不能为空");
        }
        return R.ok(reconcileService.runFor(bo.getBizDate()));
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/withdraw/reject")
    @Operation(summary = "提现驳回解冻（高风险；审核通过=真实出金属外部能力，本期不提供）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) }, permission = {
            @SaCheckPermission(value = "finance:withdraw:audit", type = StpKit.DRIVER_MANAGE) })
    public R<Boolean> withdrawReject(@RequestBody @Valid WithdrawBo bo) {
        if (bo.getUserId() == null) {
            throw new JbkException("收益人不能为空");
        }
        incomeService.rejectWithdraw(bo.getUserId(), bo.getAmountFen(), bo.getRequestId(),
                StpKit.MANAGE.getLoginIdAsLong());
        return R.ok(Boolean.TRUE);
    }

    @PostMapping("/config/page")
    @Operation(summary = "分账比例配置分页（含历史版本，只读）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) })
    public R<PageDataVo<WsSplitConfig>> configPage(@RequestBody @Valid FinanceQueryBo bo) {
        Page<WsSplitConfig> page = new Page<>(bo.pageOrDefault(), bo.sizeOrDefault());
        page = configMapper.selectPage(page, Wrappers.lambdaQuery(WsSplitConfig.class)
                .eq(ObjectUtil.isNotNull(bo.getProductLine()), WsSplitConfig::getProductLine, bo.getProductLine())
                .orderByDesc(WsSplitConfig::getEffectTime).orderByAsc(WsSplitConfig::getReceiverType));
        return R.ok(PageDataVo.getPageData(page.getRecords(), page.getTotal()));
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/config/create")
    @Operation(summary = "新增比例生效版本（高风险：影响此后所有新单分账；历史按快照不追溯）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) }, permission = {
            @SaCheckPermission(value = "finance:config:edit", type = StpKit.DRIVER_MANAGE) })
    public R<Long> configCreate(@RequestBody @Valid FinanceQueryBo bo) {
        if (ObjectUtil.hasNull(bo.getProductLine(), bo.getReceiverType(), bo.getSplitRate())) {
            throw new JbkException("商品线/收款方/比例均不能为空");
        }
        if (bo.getSplitRate() < 0 || bo.getSplitRate() > 10_000) {
            throw new JbkException("比例必须在 0~10000（万分比）");
        }
        // 生效时间缺省=当下：新版本只影响此后新单；不允许写过去时点（伪造历史快照语义）
        String now = DateUtils.time();
        String effect = StrUtil.blankToDefault(bo.getEffectTime(), now);
        if (!effect.matches("^\\d{14}$") || effect.compareTo(now) < 0) {
            throw new JbkException("生效时间必须是不早于当前的 yyyyMMddHHmmss");
        }
        // R1 P1-3：校验+写入整体下沉 Service——商品线锁锚事务内完成（跨实例串行化）。
        // Controller 无事务，留在这里的「校验后 INSERT」正是被驳回的并发超配窗口。
        return R.ok(splitConfigService.createVersion(bo, effect));
    }

}
