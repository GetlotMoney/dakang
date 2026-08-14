package com.jbk.serve.service.aftersale.impl;

import com.jbk.tool.data.aftersale.po.WsRefund;
import com.jbk.serve.service.aftersale.refund.RefundAcceptance;
import com.jbk.serve.service.aftersale.refund.IRefundSourceAdapter;
import com.jbk.serve.service.aftersale.refund.IEntitlementRefundTxService;
import com.jbk.serve.service.aftersale.refund.IRefundRequestTxService;
import com.jbk.serve.service.aftersale.IResendFulfillmentTxService;
import com.jbk.serve.mapper.aftersale.WsRefundMapper;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jbk.serve.mapper.aftersale.WsAfterSaleActionMapper;
import com.jbk.serve.service.aftersale.AfterSaleStrategy;
import com.jbk.serve.service.aftersale.AfterSaleTransitions;
import com.jbk.serve.service.aftersale.IAdminAfterSaleService;
import com.jbk.serve.service.aftersale.IAfterSaleActionTxService;
import com.jbk.serve.service.aftersale.IWaterAbnormalReconcileTxService;
import com.jbk.tool.consts.aftersale.AfterSaleEnum;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.aftersale.bo.AdminAfterSaleActionBo;
import com.jbk.tool.data.aftersale.bo.AfterSaleExecuteBo;
import com.jbk.tool.data.aftersale.po.WsAfterSaleAction;
import com.jbk.tool.data.aftersale.vo.AdminAfterSaleActionItemVo;
import com.jbk.tool.data.aftersale.vo.AdminRechargeRefundPreviewVo;
import com.jbk.tool.data.aftersale.vo.AdminWaterAbnormalPreviewVo;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import com.jbk.tool.utils.PhoneMask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;


/**
 * PC 管理端售后服务实现（E2E-04 包A）：读模型投影 + 三步执行编排。
 *
 * <p>本类没有 {@code @Transactional}，这是编排正确性的前提：三个事务边界必须各自独立提交
 * （理由见 {@link IAfterSaleActionTxService}），外层事务会让隔离级别与认领独立性同时失效。
 * 判定逻辑一律外借（铁律⑤）：本层不解析快照、不算金额、不判额度。</p>
 *
 * @author dakang
 * @since 2026-07-29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAfterSaleServiceImpl implements IAdminAfterSaleService {

    /** 基础设施类失败的重试间隔（秒），与充值域失败落痕同口径（60 秒）。 */
    private static final long RETRY_DELAY_SECONDS = 60L;

    private final WsAfterSaleActionMapper actionMapper;
    private final IAfterSaleActionTxService actionTxService;
    private final IWaterAbnormalReconcileTxService waterReconcileTxService;
    private final WsRefundMapper refundMapper;
    private final IRefundRequestTxService refundRequestTxService;
    private final IRefundSourceAdapter refundSourceAdapter;
    private final IResendFulfillmentTxService resendFulfillmentTxService;
    /** 包D-5：已入账充值退款的受理（锁批次 + 登记动作）。结算不在本层，见该接口注释。 */
    private final IEntitlementRefundTxService entitlementRefundTxService;

    // ==================== 读模型 ====================

    @Override
    public PageDataVo<AdminAfterSaleActionItemVo> pageActions(AdminAfterSaleActionBo bo) {
        Page<AdminAfterSaleActionItemVo> page = new Page<>(bo.getCurrent(), bo.getSize());
        IPage<AdminAfterSaleActionItemVo> result = actionMapper.pageAdminActions(page, bo);
        result.getRecords().forEach(this::decorate);
        return PageDataVo.getPageData(result.getRecords(), result.getTotal());
    }

    @Override
    public AdminAfterSaleActionItemVo detail(Long id) {
        requireId(id, "售后动作ID");
        AdminAfterSaleActionItemVo item = actionMapper.selectAdminActionById(id);
        if (ObjectUtil.isNull(item)) {
            throw new JbkException("售后动作不存在");
        }
        decorate(item);
        return item;
    }

    /**
     * 列表/详情共用的投影装饰：手机号统一 {@link PhoneMask} 脱敏并清空原值
     * （原值清空是第二道闸，Vo 上的 {@code @JsonIgnore} 是第三道）。
     */
    private void decorate(AdminAfterSaleActionItemVo item) {
        item.setUserMaskedPhone(PhoneMask.mask(item.getUserPhoneRaw()));
        item.setUserPhoneRaw(null);
    }

    // ==================== 执行编排 ====================

    /**
     * PC 点「执行」：认领 → 执行 → 失败落痕。三步不共享事务也不重试：
     * 认领落空一律停手，自行重试会重新打开同一笔返还被推进两次的窗口。
     */
    @Override
    public void execute(AfterSaleExecuteBo bo, Long adminUserId) {
        requireOperator(adminUserId);
        requireId(bo.getId(), "售后动作ID");

        // 绕过 @TableLogic 读：逻辑删除行必须能读到并显式拒绝，否则唯一键被占而行查不到、无从归因
        WsAfterSaleAction action = actionMapper.selectByIdIncludingDeleted(bo.getId());
        if (ObjectUtil.isNull(action)) {
            throw new JbkException("售后动作不存在");
        }
        if (ObjectUtil.notEqual(action.getDataStatus(), 0)) {
            throw new JbkException("售后动作已被逻辑删除，拒绝执行");
        }
        if (!StrUtil.equals(action.getAfterSaleNo(), bo.getAfterSaleNo())) {
            // 共键复核：ID 与售后号指向不同行，说明页面数据已过期，此刻执行的将是另一笔返还
            throw new JbkException("售后号与动作ID不匹配，页面数据可能已过期，请刷新后重试");
        }
        if (StrUtil.isNotBlank(action.getStrategyCode())
                && !AfterSaleStrategy.refundsAssets(AfterSaleStrategy.requireStrategy(action.getStrategyCode()))) {
            // RESEND/REJECT 不消耗额度、不进资金执行入口；放行会让入口错用被记成「需人工对账」的资金事故
            throw new JbkException("该策略不产生资金返还，不走售后执行入口");
        }
        // 状态预检只为把「为什么点不动」讲清楚，结论不作数：权威判定是 claimIndependent 的 CAS，
        // 预检与 CAS 共用 AfterSaleTransitions 同一份矩阵
        int processing = AfterSaleEnum.ActionStatus.PROCESSING.getValue();
        if (!AfterSaleTransitions.allowed(action.getActionStatus(), processing)) {
            throw new JbkException("当前状态不可执行："
                    + AfterSaleEnum.ActionStatus.getByValue(action.getActionStatus()).getDesc());
        }

        // 一次点击一套时钟：认领、资金、流水、审计与落痕同源，避免同一次执行留下两个时刻
        String now = DateUtils.time();
        if (!actionTxService.claimIndependent(action.getId(), action.getVersion(), adminUserId, now)) {
            throw new JbkException("该售后动作已被他人认领或状态已变，请刷新后重试");
        }

        try {
            actionTxService.executeInTx(action.getId(), adminUserId, now);
        } catch (RuntimeException failure) {
            // 铁律④后半句：钱没动，终局证据独立提交；落痕失败不得掩盖原始错因，原样重抛
            markTerminal(action, failure, adminUserId, now);
            throw failure;
        }
        log.info("PC 售后执行完成：afterSaleNo={} actionId={} 操作人={}",
                action.getAfterSaleNo(), action.getId(), adminUserId);
    }

    /**
     * 失败分流：{@link TransientDataAccessException}（重试可能成功）进 4可重试，
     * 业务性失败重试结论不变、一律 5需人工对账。expectedVersion 传的是认领前旧值，
     * markTerminalIndependent 会在事务内重读现状再 CAS，本层不做版本推算。
     */
    private void markTerminal(WsAfterSaleAction action, RuntimeException failure,
                              Long adminUserId, String now) {
        boolean retryable = failure instanceof TransientDataAccessException;
        int toStatus = retryable
                ? AfterSaleEnum.ActionStatus.RETRY_WAIT.getValue()
                : AfterSaleEnum.ActionStatus.RECONCILIATION_REQUIRED.getValue();
        try {
            actionTxService.markTerminalIndependent(action.getId(), action.getVersion(), toStatus,
                    retryable ? DateUtils.plusSeconds(now, RETRY_DELAY_SECONDS) : null,
                    failure.getMessage(), adminUserId, now);
        } catch (RuntimeException recordFailed) {
            // 落痕失败绝不能顶替原始错因抛给页面；错因由外层重抛，落痕失败只进日志
            log.error("售后执行失败后的终局落痕未成功：actionId={} afterSaleNo={} 原始错因={}",
                    action.getId(), action.getAfterSaleNo(), failure.getMessage(), recordFailed);
        }
    }

    // ==================== 取水异常核账 ====================

    @Override
    public AdminWaterAbnormalPreviewVo previewWaterAbnormal(Long orderId) {
        // 不拦非法 orderId：预览对不可核订单同样返回对象并用 blockReason 说明，抛异常运营只见红条
        return waterReconcileTxService.preview(orderId);
    }

    @Override
    public void confirmWaterAbnormal(Long orderId, String remark, Long adminUserId) {
        requireOperator(adminUserId);
        // 核账说明、订单可核性、来源判别、目标终态全部在事务内判定，本层零复制
        waterReconcileTxService.confirm(orderId, remark, adminUserId, DateUtils.time());
    }


    private void requireId(Long id, String label) {
        if (ObjectUtil.isNull(id) || id <= 0) {
            throw new JbkException(label + "非法");
        }
    }

    /** PC 入口的操作人必须是真实后台会话员工：本层不接受 0（那是无会话 Worker 的口径）。 */
    private void requireOperator(Long adminUserId) {
        if (ObjectUtil.isNull(adminUserId) || adminUserId <= 0) {
            throw new JbkException("后台会话身份非法");
        }
    }

    @Override
    public Long requestRechargeRefund(Long orderId, String remark, Long adminUserId) {
        requireOperator(adminUserId);
        requireId(orderId, "充值订单ID");
        // 能力检查必须早于 prepare：prepare 会锁批次，通道未配置才失败会让权益在无退款单下永久不可消费
        refundSourceAdapter.requireOperable();
        String now = DateUtils.time();
        // ① 受理：锁批次 + 登记动作，金额按冻结公式算定后不再重算；独立事务先提交
        Long actionId = entitlementRefundTxService.prepare(orderId, remark, adminUserId, now);
        // ②③ 「先建本地单、再事务外发请求、最后回填凭据」只允许一份实现，与未入账路径共用
        return submitRefund(actionId, adminUserId, now);
    }

    @Override
    public Long requestUnsettledRechargeRefund(Long orderId, String remark, Long adminUserId) {
        requireOperator(adminUserId);
        requireId(orderId, "充值订单ID");
        // 能力闸必须早于动作登记；事务服务内部会再次核验来源与支付单共键。
        refundSourceAdapter.requireOperable();
        String now = DateUtils.time();
        Long refundId = refundRequestTxService.createUnsettledRechargePending(
                orderId, remark, adminUserId, now);
        return acceptPendingRefund(refundId, adminUserId, now);
    }

    @Override
    public Long requestRefund(AfterSaleExecuteBo bo, Long adminUserId) {
        requireOperator(adminUserId);
        requireId(bo.getId(), "售后动作ID");
        WsAfterSaleAction action = requireActiveAction(bo);
        if (ObjectUtil.equals(action.getSourceType(), AfterSaleEnum.SourceType.RECHARGE_REFUND.getValue())) {
            // 充值退款会先锁权益批次并冻结折算结果，只能经专用入口完成该前置事务。
            throw new JbkException("充值退款必须走专用入口并通过独立财务审核权限");
        }
        return submitRefund(bo.getId(), adminUserId, DateUtils.time());
    }

    /** 建本地退款单 → 事务外发起 → 回填受理凭据。两条退款路径共用这一份编排。 */
    private Long submitRefund(Long actionId, Long adminUserId, String now) {
        // ① 建本地退款单并提交。准入闸与累计封顶都在这一步内，全部 fail-closed。
        Long refundId = refundRequestTxService.createPending(actionId, adminUserId, now);

        return acceptPendingRefund(refundId, adminUserId, now);
    }

    /** 本地事务已经提交后才允许调用退款来源；成功只回填受理凭据，不推进退款成功。 */
    private Long acceptPendingRefund(Long refundId, Long adminUserId, String now) {
        // ② 事务外调用服务方：进事务会让行锁持有到回包，且超时回滚时请求可能已发出
        WsRefund refund = refundMapper.selectById(refundId);
        if (ObjectUtil.isNull(refund)) {
            throw new JbkException("退款单创建后读取失败");
        }
        if (StrUtil.isNotBlank(refund.getProviderRefundId())) {
            // 已受理过：重复点击或重试，直接返回，绝不再向服务方发一次
            return refundId;
        }
        RefundAcceptance acceptance = refundSourceAdapter.acceptRefund(
                refund.getRefundNo(), refund.getOrderNo(), refund.getRefundAmount(), refund.getCurrency());

        // ③ 回填受理凭据。CAS 带 PROVIDER_REFUND_ID IS NULL，重复受理不覆盖既有号。
        refundRequestTxService.fillAcceptance(refundId, acceptance.providerRefundId(), adminUserId, now);
        return refundId;
    }

    @Override
    public AdminRechargeRefundPreviewVo previewRechargeRefund(Long orderId) {
        // 不拦非法 orderId：不可退订单同样返回对象并用 blockReason 说明，与取水核账预览同口径
        return entitlementRefundTxService.preview(orderId);
    }

    @Override
    public Long generateResend(AfterSaleExecuteBo bo, Long adminUserId) {
        requireOperator(adminUserId);
        requireId(bo.getId(), "售后动作ID");
        requireActiveAction(bo);
        return resendFulfillmentTxService.generate(bo.getId(), adminUserId, DateUtils.time());
    }

    /** 执行类入口统一使用 ID + 售后号复核，避免旧页面把动作落到另一笔记录上。 */
    private WsAfterSaleAction requireActiveAction(AfterSaleExecuteBo bo) {
        WsAfterSaleAction action = actionMapper.selectByIdIncludingDeleted(bo.getId());
        if (ObjectUtil.isNull(action) || ObjectUtil.notEqual(action.getDataStatus(), 0)) {
            throw new JbkException("售后动作不存在或已删除");
        }
        if (!StrUtil.equals(action.getAfterSaleNo(), bo.getAfterSaleNo())) {
            throw new JbkException("售后号与动作ID不匹配，页面数据可能已过期，请刷新后重试");
        }
        return action;
    }
}
