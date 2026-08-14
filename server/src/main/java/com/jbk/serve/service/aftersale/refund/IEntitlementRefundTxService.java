package com.jbk.serve.service.aftersale.refund;

import com.jbk.tool.data.aftersale.vo.AdminRechargeRefundPreviewVo;

/**
 * 充值/购卡退款的权益批次事务（E2E-04 包D-5，REQ-061）：三个方法对应受理 / 成功结算 / 失败落痕
 * 三个时刻，事务边界各自独立。结算不放在退款事实消费里——事实消费者没有动卡能力（包B 边界），
 * 只在退款单确实成功后委托本服务。
 *
 * @author dakang
 * @since 2026-07-29
 */
public interface IEntitlementRefundTxService {

    /**
     * 只读预览退款依据（不加锁、不写库）：不可退的订单同样返回对象并以 {@code blockReason} 说明。
     *
     * @param orderId 充值订单ID
     * @return 预览对象；{@code refundable=false} 时 {@code blockReason} 必非空
     */
    AdminRechargeRefundPreviewVo preview(Long orderId);

    /**
     * 受理一笔已入账充值/购卡订单的退款：锁定权益批次（阻断处理中继续消费）并登记待执行动作，
     * 此刻不动钱、不向服务方发请求。
     *
     * @param orderId   充值订单ID（{@code ORDER_TYPE=2}、{@code ORDER_STATUS=4已完成}）
     * @param remark    运营受理说明，落计算快照与审计（高风险操作的唯一人工留痕）
     * @param opUserId  运营操作人
     * @param now       yyyyMMddHHmmss
     * @return 售后动作ID（幂等：同一订单重复受理返回既有动作）
     */
    Long prepare(Long orderId, String remark, Long opUserId, String now);

    /**
     * 退款成功后的结算：批次冲正、卡聚合冲减、唯一流水、订单终态与卡处置。
     * 必须幂等：成功事实可能重复投递，结算也可能因基础设施失败被重试。
     *
     * @param refundId 已推进为成功的退款单ID
     * @param now      yyyyMMddHHmmss
     */
    void settleOnRefundSuccess(Long refundId, String now);

    /**
     * 退款失败/关闭后的落痕：动作转需人工对账，批次保持退款锁定不自动解除
     * （任务书 3.4：退款永久失败时不得自动恢复为未退款）。
     *
     * @param refundId 退款单ID
     * @param reason   失败原因（写入 LAST_ERROR）
     * @param now      yyyyMMddHHmmss
     */
    void parkOnRefundFailure(Long refundId, String reason, String now);
}
