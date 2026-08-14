package com.jbk.serve.service.aftersale;

/**
 * 补送履约事务（E2E-04 包C）。补送走「零金额子订单 + 新任务」而非复活原任务：
 * {@code uk_dtask_order} 一单一任务（任务书明令不得移除）。
 *
 * <p>三道独立防重复生成闸（刻意冗余）：① 子订单号由 {@code RESEND:APPEAL:<appealId>} 确定性派生
 * 撞 {@code uk_order_no}；② 动作回填 {@code RESULT_ORDER_ID IS NULL} 的 CAS；③ 新订单上的
 * {@code uk_dtask_order}。补送不动钱：不扣水卡、不写 {@code DELIVERY:<orderNo>} 扣款流水——
 * 补送是履约补偿而非二次交易。</p>
 *
 * @author dakang
 * @since 2026-07-29
 */
public interface IResendFulfillmentTxService {

    /**
     * 为一条「补送」售后动作生成子订单与任务。
     *
     * @param afterSaleId 售后动作 ID（ACTION_TYPE 必须为 4补送）
     * @param opUserId    操作人（PC 员工 ID）
     * @param now         业务时间(yyyyMMddHHmmss)
     * @return 补送子订单 ID
     */
    Long generate(Long afterSaleId, Long opUserId, String now);

    /**
     * 补送任务签收成功后把对应售后动作推成 3已完成。由签收事务在<b>同事务内</b>调用
     * （任务书明令不得提前显示补送完成），共键由 CAS 的 {@code RESULT_TASK_ID} 谓词保证。
     *
     * @param taskId 刚签收成功的补送任务 ID
     * @return 是否确实推进了某条补送动作；非补送任务恒返回 false，不是错误
     */
    boolean completeOnSigned(Long taskId, Long opUserId, String now);
}
