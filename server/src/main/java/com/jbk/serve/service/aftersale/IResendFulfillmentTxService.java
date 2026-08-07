package com.jbk.serve.service.aftersale;

/**
 * 补送履约事务（E2E-04 包C）。
 *
 * <h3>为什么补送必须走「新订单 + 新任务」而不是复活原任务</h3>
 * <p>{@code ws_delivery_task} 上有 {@code uk_dtask_order (ORDER_ID)}——一单一任务，
 * 任务书明令不得移除它。原订单已经有一条走完的任务（已签收），
 * 想在同一订单上再挂一条补送任务就必须删掉那把唯一键，
 * 而那把键正是「一个配送订单不会凭空多出第二条履约记录」的物理保证。
 * 因此补送生成一张<b>零金额子订单</b>，它自带一条新任务，唯一键原封不动。</p>
 *
 * <h3>三道各自独立的防重复生成闸</h3>
 * <ol>
 *   <li>子订单号由 {@code RESEND:APPEAL:<appealId>} 确定性派生 → 重复生成撞 {@code uk_order_no}；</li>
 *   <li>售后动作回填 {@code RESULT_ORDER_ID IS NULL} 的 CAS → 第二次影响 0 行；</li>
 *   <li>新订单上的 {@code uk_dtask_order} → 一单至多一条任务。</li>
 * </ol>
 * <p>任何一道单独失效都不会导致重复补送，这是刻意的冗余：补送重复意味着白送一趟水。</p>
 *
 * <h3>补送不动钱</h3>
 * <p>子订单 {@code ORDER_AMOUNT=0}、水费与配送费快照均为 0，
 * <b>不扣水卡、不写 {@code DELIVERY:<orderNo>} 扣款流水</b>。
 * 用户已经为原单付过钱，补送是履约补偿而非二次交易；写一条扣款流水会让账本
 * 多出一笔用户根本没付的消费。</p>
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
     * 补送任务签收成功后，把对应的售后动作推成 3已完成。
     *
     * <p>由签收事务在<b>同事务内</b>调用：只有补送真正回签，售后才算完成。
     * 任务书明令「签收失败、重复签收或共键错位不得提前显示补送完成」——
     * 同事务保证签收回滚时这一步一起回滚，共键由 CAS 的
     * {@code RESULT_TASK_ID = <本次签收的任务>} 谓词保证。</p>
     *
     * @param taskId 刚签收成功的补送任务 ID
     * @return 是否确实推进了某条补送动作；非补送任务恒返回 false，不是错误
     */
    boolean completeOnSigned(Long taskId, Long opUserId, String now);
}
