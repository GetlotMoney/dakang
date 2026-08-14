package com.jbk.serve.service.mall;

/**
 * 商城模拟退款（Refund-Sim，E2E-09 S4）。
 *
 * <p>只在 {@code mall.refund-sim.enabled=true} 时注册；dev/prod 两环境均显式 false。
 * 与 {@code mini.pay-sim} 和 {@code mall.pay-sim} 三个开关完全独立——收款与退款是两条
 * 不同方向的资金链，开一条不等于该开另一条。</p>
 *
 * <p>它只做一件事：产生一条渠道退款事实。订单、库存与售后状态一律由退款推进段在锁内
 * 重读共键后决定，模拟器碰不到它们。</p>
 *
 * @author dakang
 * @since 2026-08-10
 */
public interface IMallRefundSimService {

    /** 对指定售后单发起模拟退款；重复调用复用同一条事实。 */
    void refund(Long operatorId, String afterSaleNo);
}
