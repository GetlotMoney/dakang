package com.jbk.serve.service.aftersale;

import com.jbk.serve.service.delivery.DeliveryRefundSnapshot;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.exception.JbkException;

/**
 * 四元额度与累计封顶的唯一出处（R0-3 / R0-7）：一张订单最多能返多少，只在这里判。
 *
 * <p>调用方两条铁律，不遵守则本类形同虚设：
 * ① 必须在 {@code isolation = READ_COMMITTED} 事务内调用——MySQL 默认 RR 下 read view 在锁卡前的
 * 首条 SELECT 就固定，锁卡后的已用额度聚合仍读旧快照，看不见并发已提交的返还 → 第二次全额返还
 * （权威修法与「为什么不用 FOR SHARE（gap 锁互等死锁）」见 {@code RechargeIssueTxImpl.issue}）；
 * ② 必须在持有卡行 X 锁之后调用——卡锁把同卡售后串行化，聚合读才有意义；
 * 创建期（裁决事务内）那次调用只是给运营的快速反馈，权威口径只有持卡锁后的这一次。</p>
 *
 * <p>额度必须四元而非总额：payWay=2 时水费与配送费都从余额扣，按混合总额判会让连续
 * SERVICE_FEE_ONLY 把水费额度挪去退配送费；{@code REFUND_AMOUNT} 只用于 CAS 与流水，封顶不看它。
 * 纯函数、无注入，全程 checked arithmetic。</p>
 */
public final class AfterSaleQuota {

    private AfterSaleQuota() {
    }

    /**
     * 四元额度上限。三个维度互不通兑（R0-1）。
     *
     * @param capProductFen 水品权益金额上限（分）：payWay=2 锚定快照 waterAmountFen；payWay=3 恒 0
     * @param capServiceFen 配送费上限（分）：两种支付方式均锚定快照 deliveryFeeFen
     * @param capProductMl  水品权益水量上限（毫升）：payWay=3 锚定快照 waterMl；payWay=2 恒 0
     */
    public record Caps(long capProductFen, long capServiceFen, long capProductMl) {
    }

    /**
     * 同订单已成功返还的累计额度。聚合必须<b>跨 {@code DATA_STATUS}</b> 读：
     * 逻辑删除的动作行仍代表「钱已经退出去了」，漏掉即误判成「没返过」。
     */
    public record Used(long usedProductFen, long usedServiceFen, long usedProductMl) {

        public static final Used NONE = new Used(0L, 0L, 0L);
    }

    /** 封顶判定结论：{@code allowed=false} 时 {@code reason} 是可直接回给运营的精确原因。 */
    public record Verdict(boolean allowed, String reason) {

        private static final Verdict PASS = new Verdict(true, null);

        public static Verdict pass() {
            return PASS;
        }

        public static Verdict reject(String reason) {
            return new Verdict(false, reason);
        }
    }

    /**
     * 从订单快照 + 原扣款流水推出四元额度上限。R0-3 总额兜底：锚点取快照，但必须与原扣款流水
     * 绝对值逐维精确相等，不等即 fail-closed 转人工——刻意不做 {@code min()} 收敛，
     * 按较小值继续只会掩盖账不对。
     *
     * @param flowAmountChange 原扣款流水的 {@code AMOUNT_CHANGE}（扣款为负）
     * @param flowMlChange     原扣款流水的 {@code ML_CHANGE}（扣款为负）
     */
    public static Caps caps(DeliveryRefundSnapshot.Parsed snap, long flowAmountChange, long flowMlChange) {
        if (snap == null) {
            throw new JbkException("配送订单快照缺失，无法确定返还额度上限");
        }
        long amountDeducted = deductedAbs(flowAmountChange, "扣款流水金额");
        long mlDeducted = deductedAbs(flowMlChange, "扣款流水水量");
        long capProductFen;
        long capProductMl;
        if (snap.payWay() == TradeEnum.PayWay.CARD_ML.getValue()) {
            // payWay=3：水品走水量、配送费走余额，余额侧不含任何水费
            capProductFen = 0L;
            capProductMl = snap.waterMl();
        } else {
            capProductFen = snap.waterAmountFen();
            capProductMl = 0L;
        }
        long capServiceFen = snap.deliveryFeeFen();
        long expectedAmount = Math.addExact(capProductFen, capServiceFen);
        if (amountDeducted != expectedAmount) {
            throw new JbkException("原扣款流水金额(" + amountDeducted + ")与快照水费+配送费("
                    + expectedAmount + ")不一致，账实不符，拒绝返还");
        }
        if (mlDeducted != capProductMl) {
            throw new JbkException("原扣款流水水量(" + mlDeducted + ")与快照抵扣水量("
                    + capProductMl + ")不一致，账实不符，拒绝返还");
        }
        return new Caps(capProductFen, capServiceFen, capProductMl);
    }

    /**
     * 单维度封顶判定的原子实现——三个维度都走这一个函数，判定逻辑物理上只有一份。
     *
     * @param used      该维度已成功返还的累计额度
     * @param requested 本次申请的该维度额度
     * @param cap       该维度的额度上限
     * @param dimension 维度名，用于拼出运营能看懂的拒绝原因
     */
    public static Verdict check(long used, long requested, long cap, String dimension) {
        if (used < 0 || requested < 0 || cap < 0) {
            // 负额度无合法来源；容忍它等于允许「负的已用额度」凭空放大余量
            return Verdict.reject(dimension + "额度参数非法：已用 " + used
                    + "，本次 " + requested + "，上限 " + cap);
        }
        if (used > cap) {
            // 已用超过上限即账本断裂，必须转人工对账而非只拒绝本次
            return Verdict.reject(dimension + "已返还累计(" + used + ")已超过上限(" + cap
                    + ")，账本断裂，需人工对账");
        }
        long remain;
        long after;
        try {
            remain = Math.subtractExact(cap, used);
            after = Math.addExact(used, requested);
        } catch (ArithmeticException overflow) {
            return Verdict.reject(dimension + "额度计算溢出，拒绝返还");
        }
        if (after > cap) {
            return Verdict.reject(dimension + "超出可返还额度：本次 " + requested
                    + "，剩余 " + remain + "（上限 " + cap + "，已返 " + used + "）");
        }
        return Verdict.pass();
    }

    /**
     * 三个维度分别封顶（R0-1：任一维度不足都不允许拿另一维度的余量顶上）。
     * 返回首个不通过的维度原因；全部通过返回 {@link Verdict#pass()}。
     */
    private static Verdict check(Caps caps, Used used, AfterSaleStrategy.Refund requested) {
        if (caps == null || used == null || requested == null) {
            throw new JbkException("售后额度判定入参缺失");
        }
        Verdict product = check(used.usedProductFen(), requested.productFen(),
                caps.capProductFen(), "水品金额");
        if (!product.allowed()) {
            return product;
        }
        Verdict service = check(used.usedServiceFen(), requested.serviceFen(),
                caps.capServiceFen(), "配送费");
        if (!service.allowed()) {
            return service;
        }
        return check(used.usedProductMl(), requested.productMl(), caps.capProductMl(), "水品水量");
    }

    /**
     * 写路径入口：不通过即抛，绝不返回一个"删减过的"额度。
     * 与 {@link #check(Caps, Used, AfterSaleStrategy.Refund)} 委托同一份判定，不存在第二套口径。
     */
    public static void requireWithinCap(Caps caps, Used used, AfterSaleStrategy.Refund requested) {
        Verdict verdict = check(caps, used, requested);
        if (!verdict.allowed()) {
            throw new JbkException(verdict.reason());
        }
    }

    /** 扣款流水的绝对值。扣款必须为负或零；正值说明取错了流水（那是一条入账），fail-closed。 */
    private static long deductedAbs(long change, String label) {
        if (change > 0) {
            throw new JbkException(label + "为正数(" + change + ")，不是扣款流水，拒绝作为返还基准");
        }
        try {
            return Math.negateExact(change);
        } catch (ArithmeticException overflow) {
            throw new JbkException(label + "越界，拒绝作为返还基准");
        }
    }
}
