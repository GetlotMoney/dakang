package com.jbk.serve.service.aftersale.refund;

import com.jbk.tool.data.aftersale.vo.AdminRechargeRefundPreviewVo;

/**
 * 充值/购卡退款的权益批次事务（E2E-04 包D-5，REQ-061）。
 *
 * <h3>三个方法对应退款的三个时刻，事务边界各自独立</h3>
 * <ol>
 *   <li>{@link #prepare}：<b>受理</b>。锁批次（阻断处理中继续消费）+ 登记待执行的机构退款动作。
 *       此刻一分钱都不动，也不向服务方发请求——发请求由编排层在事务外做（包B 既有口径）。</li>
 *   <li>{@link #settleOnRefundSuccess}：<b>成功结算</b>。退款事实已经把 ws_refund 推成成功，
 *       此时才做批次冲正、卡聚合冲减、唯一流水、订单终态与卡处置。
 *       它必须幂等：同一笔退款的成功事实可能被重复投递，结算也可能因基础设施失败被重试。</li>
 *   <li>{@link #parkOnRefundFailure}：<b>失败落痕</b>。动作转需人工对账，
 *       而<b>批次锁定不自动解除</b>——任务书 3.4 明令「退款永久失败时不得自动恢复为未退款」。</li>
 * </ol>
 *
 * <p><b>为什么结算不能放在退款事实消费里</b>：{@code RefundFactServiceImpl} 的依赖清单里
 * 只有事实与退款单两个 Mapper，它<b>没有动卡的能力</b>，这是包B 刻意留下的边界。
 * 结算需要写卡、批次、流水与订单，故必须是一个独立的、能被单独审的服务；
 * 事实消费者只负责在退款单确实成功之后调用它。</p>
 *
 * @author dakang
 * @since 2026-07-29
 */
public interface IEntitlementRefundTxService {

    /**
     * 只读预览退款依据：退多少钱、从卡上冲减多少权益、这张卡会不会被注销。
     *
     * <p>不加锁、不写库、不抛业务异常——对不可退的订单同样返回对象并以
     * {@code blockReason} 说明原因。抛异常会让运营只看到一个红条，
     * 而「为什么不能退」（历史聚合权益 / 已退过 / 已用尽 / 账本断裂）各自需要不同的人工动作。</p>
     *
     * @param orderId 充值订单ID
     * @return 预览对象；{@code refundable=false} 时 {@code blockReason} 必非空
     */
    AdminRechargeRefundPreviewVo preview(Long orderId);

    /**
     * 受理一笔已入账充值/购卡订单的退款：锁定权益批次并登记待执行的机构退款动作。
     *
     * @param orderId   充值订单ID（{@code ORDER_TYPE=2}、{@code ORDER_STATUS=4已完成}）
     * @param remark    运营受理说明，落计算快照与审计（高风险操作的唯一人工留痕）
     * @param opUserId  运营操作人
     * @param now       yyyyMMddHHmmss
     * @return 售后动作ID（幂等：同一订单重复受理返回既有动作）
     */
    Long prepare(Long orderId, String remark, Long opUserId, String now);

    /**
     * 退款成功后的结算。幂等：动作已完成时直接返回。
     *
     * @param refundId 已推进为成功的退款单ID
     * @param now      yyyyMMddHHmmss
     */
    void settleOnRefundSuccess(Long refundId, String now);

    /**
     * 退款失败/关闭后的落痕：动作转需人工对账，批次保持退款锁定不自动解除。
     *
     * @param refundId 退款单ID
     * @param reason   失败原因（写入 LAST_ERROR）
     * @param now      yyyyMMddHHmmss
     */
    void parkOnRefundFailure(Long refundId, String reason, String now);
}
