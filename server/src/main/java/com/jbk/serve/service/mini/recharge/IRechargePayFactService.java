package com.jbk.serve.service.mini.recharge;

/**
 * 支付事实处理器（L2-T）：把一条已落库的支付事实推进到终态。
 *
 * <p>输入只有事实主键——处理器不接受任何调用方传来的金额、订单号或状态，
 * 全部从库里重读。这样无论事实是 Pay-Sim 造的还是将来真实微信回调落的，
 * 走的都是同一条路径、同一套校验。</p>
 */
public interface IRechargePayFactService {

    /**
     * @param code    CREDITED 本次入账完成 / ALREADY 之前已完成 / SKIPPED 事实已被他人认领
     *                / RECONCILIATION 已转人工对账 / MISMATCH 关键字段错位
     * @param message 面向运维的可读原因
     */
    record Outcome(String code, String message) {
        public boolean success() {
            return "CREDITED".equals(code) || "ALREADY".equals(code);
        }
    }

    Outcome process(Long eventId);
}
