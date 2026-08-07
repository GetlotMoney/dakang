package com.jbk.serve.service.aftersale;

import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.aftersale.bo.AdminAfterSaleActionBo;
import com.jbk.tool.data.aftersale.bo.AfterSaleExecuteBo;
import com.jbk.tool.data.aftersale.vo.AdminAfterSaleActionItemVo;
import com.jbk.tool.data.aftersale.vo.AdminRechargeRefundPreviewVo;
import com.jbk.tool.data.aftersale.vo.AdminWaterAbnormalPreviewVo;

/**
 * PC 管理端售后服务（E2E-04 包A）：读模型 + 编排，<b>本层不持有任何事务</b>。
 *
 * <p>资金写入、状态 CAS、封顶判定、幂等与审计全部由包A 的两个事务服务收口
 * （{@link IAfterSaleActionTxService} 卡内返还内核、{@link IWaterAbnormalReconcileTxService} 零资金核账）。
 * 本层只做三件事：把台账行投影成 PC 契约（含手机号脱敏）、把 PC 动作透传给事务层、
 * 在执行失败时按既有失败落痕入口留下终局证据。</p>
 *
 * <p><b>{@link #execute} 绝不加 {@code @Transactional}</b>：认领与失败落痕是
 * {@code REQUIRES_NEW} 独立事务、资金写入是 {@code REQUIRES_NEW + READ_COMMITTED}，
 * 三者必须各自独立提交。本层一旦开启外层事务，Spring 默认 {@code validateExistingTransaction=false}
 * 会让隔离级别静默沿用外层（MySQL 默认 RR），而认领若被卷进主事务，资金失败回滚会把
 * ACTION_STATUS 复原成待执行、失败证据的 CAS 恒 0 行——两条已知 P0 一起复活，且零报错。</p>
 *
 * @author dakang
 * @since 2026-07-29
 */
public interface IAdminAfterSaleService {

    /**
     * 售后台账分页（三条来源共用一张表，按来源/类型/状态/关键字筛选）。
     * <p>手机号在本层统一走 {@code PhoneMask} 脱敏后下发，明文不出接口。</p>
     */
    PageDataVo<AdminAfterSaleActionItemVo> pageActions(AdminAfterSaleActionBo bo);

    /** 售后台账详情（与分页同一列集，按售后动作ID单查）。 */
    AdminAfterSaleActionItemVo detail(Long id);

    /**
     * PC 执行一笔待执行/到点可重试的售后返还：认领 → 执行 → 失败落痕，三步事务边界由事务层保证。
     *
     * <p>成功即钱已入卡、流水已唯一落库、动作已终态；失败时资金整体回滚，终局证据独立提交，
     * 原始错因原样抛回页面（不吞异常、不改写成"执行成功但请稍后查看"）。</p>
     *
     * @param bo          定位键（id + 售后号共键复核）
     * @param adminUserId 当前后台会话员工ID（落 UPDATE_BY / 审计归属）
     */
    void execute(AfterSaleExecuteBo bo, Long adminUserId);

    /**
     * 取水异常核账预览（只读，不写任何状态与证据）。
     * <p>预览结论不是确认的授权：确认会在事务内用同一份规则重新判定。</p>
     */
    AdminWaterAbnormalPreviewVo previewWaterAbnormal(Long orderId);

    /**
     * 运营确认取水异常核账，推进订单终态。<b>零资金写入</b>（执行方不注入任何加钱能力）。
     *
     * @param remark      运营核账说明，落审计与台账计算快照
     * @param adminUserId 当前后台会话员工ID（落 APPROVE_BY / UPDATE_BY）
     */
    void confirmWaterAbnormal(Long orderId, String remark, Long adminUserId);

    /**
     * 对一条「机构退款」售后动作发起外部退款（E2E-04 包B）。
     *
     * <p>三步编排：建本地退款单（事务）→ 事务外调用服务方 → 回填受理凭据（事务）。
     * 中间那次对外调用刻意不在事务里，理由见 {@code IRefundRequestTxService} 的类注释。
     * 本方法<b>不返回也不判定退款是否成功</b>——成功只能由退款事实经收件箱推进（R0-8）。</p>
     *
     * @return 退款单 ID
     */
    Long requestRefund(AfterSaleExecuteBo bo, Long adminUserId);

    /**
     * 对一条「补送」售后动作生成零金额子订单与新任务（E2E-04 包C）。
     *
     * <p>生成后售后动作仍是<b>待执行</b>：只有补送任务真正三照签收，才会转已完成。</p>
     *
     * @return 补送子订单 ID
     */
    Long generateResend(AfterSaleExecuteBo bo, Long adminUserId);

    /**
     * 受理并发起一笔<b>已入账</b>充值/购卡订单的退款（E2E-04 包D-5，REQ-061）。
     *
     * <p>一次调用走完三步，每步各自独立事务：受理（锁权益批次 + 登记待执行动作）→ 建本地退款单
     * → 向服务方发起。金额一律由服务端按冻结公式折算，请求体<b>只能提供订单ID</b>。</p>
     *
     * <p>退款是否成功不由本方法决定：服务方事实进收件箱后由 Worker 推进，
     * 权益冲正与卡处置在那时才发生（R0-8）。</p>
     *
     * @param orderId    充值订单ID
     * @param adminUserId 运营操作人
     * @return 退款单ID
     */
    Long requestRechargeRefund(Long orderId, String remark, Long adminUserId);

    /**
     * 受理已付款未入账异常充值单的全额原路退款。动作与本地退款单原子登记，外部受理在事务外执行。
     */
    Long requestUnsettledRechargeRefund(Long orderId, String remark, Long adminUserId);

    /**
     * 充值退款依据预览（只读）。对不可退的订单同样返回对象并以 blockReason 说明原因，
     * 页面据此禁用按钮——「为什么不能退」的四类原因各自对应不同的人工动作。
     */
    AdminRechargeRefundPreviewVo previewRechargeRefund(Long orderId);
}
