package com.jbk.serve.service.mini.recharge;

import com.jbk.tool.exception.JbkException;

/**
 * 充值权益换算（L2 契约 §2.3）：把订单快照换算成「这次该给卡加多少余额、多少水量」。
 *
 * <p><b>取值只来自订单创建时冻结的 PACKAGE_SNAP，绝不来自支付报文或前端。</b>
 * 支付方只能告诉我们「这笔钱收到了」，收到多少权益是下单那一刻就定死的——
 * 否则改一下回调报文就能凭空充水。</p>
 *
 * <p>两类套餐互斥：<br>
 * · 水量套餐（waterMl &gt; 0）：加水量 = waterMl，加余额 = bonusAmount（售价本身已换成水，不再进余额）<br>
 * · 纯金额套餐（waterMl == 0）：加水量 = 0，加余额 = payAmount + bonusAmount<br>
 * 这条规则与 {@link RechargeLimits#validatePackage} 里「两个 credit 不得同时为 0」的判据同源，
 * 改动其一必须同步另一处（{@code RechargeCreditTest} 钉死了二者一致）。</p>
 */
public record RechargeCredit(long amountFen, long ml) {

    /** 从已解析的订单快照换算权益。 */
    public static RechargeCredit of(RechargeSnapshot.Parsed snap) {
        if (snap == null) {
            throw new JbkException("订单快照缺失，拒绝入账");
        }
        RechargeLimits.validateSnapshotValues(snap.packageName(), snap.payAmount(), snap.waterMl(),
                snap.bonusAmount(), snap.unitPriceSnap(), snap.expireDays());
        return of(snap.payAmount(), snap.waterMl(), snap.bonusAmount());
    }

    /** 换算主体，便于与 {@link RechargeLimits} 的同源规则做一致性测试。 */
    public static RechargeCredit of(long payAmount, long waterMl, long bonusAmount) {
        if (payAmount < 0 || waterMl < 0 || bonusAmount < 0) {
            throw new JbkException("订单快照权益为负，拒绝入账");
        }
        long ml = waterMl > 0 ? waterMl : 0L;
        long amount;
        try {
            amount = waterMl > 0 ? bonusAmount : Math.addExact(payAmount, bonusAmount);
        } catch (ArithmeticException e) {
            throw new JbkException("订单快照权益溢出，拒绝入账");
        }
        if (ml == 0L && amount == 0L) {
            // 空权益订单不该被创建；真出现了说明快照有问题，宁可转人工也不写一条零变动流水
            throw new JbkException("订单权益为空，拒绝入账");
        }
        return new RechargeCredit(amount, ml);
    }
}
