package com.jbk.serve.service.mini.recharge;

/**
 * 关单事务（L2 契约 v2 §6.2 第 6 条、§7.1）：{@code payment 1/order 5} 由<b>支付方权威 CLOSED 查单结果</b>驱动。
 *
 * <p>它与事务 A（{@link IRechargePayConfirmTx}）刻意分开：认定付款和确认没付款是两个方向相反的动作，
 * 合成一个方法会让"推进"和"关闭"共用同一段状态判断，改错一处两条路径一起塌。</p>
 *
 * <p><b>这里没有本地超时关单</b>。到点了只代表"可以去问支付方"，不代表可以自己把订单改成 5。
 * 若本地擅自关单，而用户其实在最后一秒付款成功，就会出现"钱进来了、订单已关闭"的资金黑洞。</p>
 */
public interface IRechargePayCloseTx {

    enum Result {
        /** 本次把支付单与订单推进到了 payment 4/order 5。 */
        CLOSED,
        /** 之前已是精确的 payment 4/order 5（重复 CLOSED 查单）。 */
        ALREADY,
        /** 与内部状态冲突（含任何成功态），不得关单，交人工对账。 */
        MISMATCH
    }

    /**
     * @param eventId 已被认领为 2处理中 的 CLOSED 查单事实主键；关单与事实收敛必须同事务
     */
    Result close(Long orderId, Long paymentId, Long eventId, String now);
}
