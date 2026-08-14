package com.jbk.serve.service.aftersale;

import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.aftersale.bo.AdminAfterSaleActionBo;
import com.jbk.tool.data.aftersale.bo.AfterSaleExecuteBo;
import com.jbk.tool.data.aftersale.vo.AdminAfterSaleActionItemVo;
import com.jbk.tool.data.aftersale.vo.AdminRechargeRefundPreviewVo;
import com.jbk.tool.data.aftersale.vo.AdminWaterAbnormalPreviewVo;

/**
 * PC 管理端售后服务（E2E-04 包A）：读模型投影（含手机号脱敏）+ 执行编排，<b>本层不持有任何事务</b>。
 *
 * <p>{@link #execute} 绝不加 {@code @Transactional}：外层事务会让隔离级别静默沿用 RR
 * （validateExistingTransaction 默认 false）并把认领卷进主事务——两条已知 P0 一起复活且零报错。</p>
 *
 * @author dakang
 * @since 2026-07-29
 */
public interface IAdminAfterSaleService {

    /** 售后台账分页（三条来源共用一张表）。手机号经 {@code PhoneMask} 脱敏，明文不出接口。 */
    PageDataVo<AdminAfterSaleActionItemVo> pageActions(AdminAfterSaleActionBo bo);

    /** 售后台账详情（与分页同一列集，按售后动作ID单查）。 */
    AdminAfterSaleActionItemVo detail(Long id);

    /**
     * PC 执行一笔待执行/到点可重试的售后返还：认领 → 执行 → 失败落痕。
     * 失败时资金整体回滚、终局证据独立提交，原始错因原样抛回页面。
     *
     * @param bo          定位键（id + 售后号共键复核）
     * @param adminUserId 当前后台会话员工ID（落 UPDATE_BY / 审计归属）
     */
    void execute(AfterSaleExecuteBo bo, Long adminUserId);

    /** 取水异常核账预览（只读）。预览结论不是确认的授权：确认会在事务内用同一份规则重判。 */
    AdminWaterAbnormalPreviewVo previewWaterAbnormal(Long orderId);

    /**
     * 运营确认取水异常核账，推进订单终态。<b>零资金写入</b>（执行方不注入任何加钱能力）。
     *
     * @param remark      运营核账说明，落审计与台账计算快照
     * @param adminUserId 当前后台会话员工ID（落 APPROVE_BY / UPDATE_BY）
     */
    void confirmWaterAbnormal(Long orderId, String remark, Long adminUserId);

    /**
     * 对一条「机构退款」售后动作发起外部退款（E2E-04 包B）：建本地退款单 → 事务外调服务方 →
     * 回填受理凭据。本方法不判定退款是否成功——成功只能由退款事实经收件箱推进（R0-8）。
     *
     * @return 退款单 ID
     */
    Long requestRefund(AfterSaleExecuteBo bo, Long adminUserId);

    /**
     * 对一条「补送」售后动作生成零金额子订单与新任务（E2E-04 包C）。
     * 生成后动作仍是待执行：只有补送任务真正签收才转已完成。
     *
     * @return 补送子订单 ID
     */
    Long generateResend(AfterSaleExecuteBo bo, Long adminUserId);

    /**
     * 受理并发起一笔<b>已入账</b>充值/购卡订单的退款（E2E-04 包D-5，REQ-061）：受理（锁批次+登记动作）
     * → 建本地退款单 → 向服务方发起，三步各自独立事务。金额一律由服务端按冻结公式折算，
     * 请求体只能提供订单ID；权益冲正与卡处置在退款事实成功后才发生（R0-8）。
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
