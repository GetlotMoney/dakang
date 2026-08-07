package com.jbk.serve.service.aftersale;

import com.jbk.serve.service.delivery.DeliveryRefundSnapshot;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.exception.JbkException;

/**
 * 四元额度与累计封顶的唯一出处（R0-3 / R0-7）：一张订单最多能返多少，只在这里判。
 *
 * <h3>调用方必须遵守的两条隔离级别铁律（不遵守则本类形同虚设）</h3>
 * <p><b>① 必须在 {@code @Transactional(isolation = READ_COMMITTED)} 的事务内调用。</b>
 * MySQL 8 默认 REPEATABLE READ，事务的 read view 在<b>第一条普通 SELECT</b> 时就固定了。
 * 售后执行事务的第一条读通常是「读售后动作行取 cardId/金额」，发生在锁卡之前；此后
 * {@code SUM(REFUND_*) FROM ws_after_sale_action WHERE ORDER_ID=? AND ACTION_STATUS=3}
 * 这条聚合读<b>仍走那份旧快照</b>，看不见并发事务刚提交的返还。攻击序列：同一订单两条合法
 * PENDING（同任务可多次申诉，{@code uk_after_sale_source} 因 appealId 不同而拦不住）→
 * T2 先读动作行建立旧 read view → T1 完整走完锁卡/聚合/返还/标记成功并提交 → T2 在卡行锁上
 * 醒来，聚合读到的 used 仍是 0 → 判定额度充足 → <b>第二次全额返还</b>。
 * 本仓已踩过同型坑并留有权威修法：{@code RechargeIssueTxImpl.issue} 用
 * {@code isolation = READ_COMMITTED} 修复「同用户并发发两张卡」，其类注释同时说明了
 * <b>为什么不改用 FOR SHARE 锁定读</b>——对不存在的行做二级索引锁定读会留 gap 锁，
 * 两笔不相关的并发售后会在相邻索引间隙上互等死锁。降隔离级别零新增锁面，是同一处方。</p>
 *
 * <p><b>② 必须在持有卡行 X 锁之后调用。</b>READ_COMMITTED 只保证「读到最新已提交」，
 * 不保证「读完到写完之间没人插进来」。卡锁把同一张卡的售后执行串行化，聚合读才有意义。
 * 创建期（裁决事务内）的那次调用是给运营的快速反馈，<b>不是</b>权威口径；权威口径只有
 * 持卡锁之后的这一次。</p>
 *
 * <h3>为什么额度必须是四元而不是一个总额</h3>
 * <p>payWay=2 时水费与配送费<b>都从余额扣</b>。若只按混合总额判封顶，连续多次
 * SERVICE_FEE_ONLY 申诉会各自按总额算余量，把水费额度挪去退配送费：20L桶×5、
 * 水费 6500 + 配送费 1000 = 总额 7500，三次 SERVICE_FEE_ONLY 各退 1000 全部通过，
 * 而本单实际只扣过 1000 分配送费。故必须按「水品 / 配送费」两条独立维度分别判定，
 * {@code REFUND_AMOUNT} 只用于 CAS 与流水，封顶一眼都不看它。</p>
 *
 * <p>纯函数，无状态、无注入；全程 checked arithmetic。</p>
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
     * 同订单已成功返还的累计额度。
     *
     * <p>聚合口径必须<b>跨 {@code DATA_STATUS}</b> 读：唯一键不含 {@code DATA_STATUS}，
     * 逻辑删除的动作行仍然代表「钱已经退出去了」，漏掉它就会误判成「没返过」。</p>
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
     * 从订单快照 + 原扣款流水推出四元额度上限。
     *
     * <p><b>R0-3 总额兜底</b>：额度锚点取自快照，但必须与原扣款流水的绝对值<b>逐维精确相等</b>，
     * 不等即 fail-closed 转人工。这里刻意不做 {@code min()} 收敛——两侧不一致意味着快照或流水
     * 至少有一份被改写过，此时按较小值继续返还只是把「账不对」这件事掩盖到下一次对账，
     * 而少退用户的钱同样是事故。相等断言是能给出的最强口径。</p>
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
            // payWay=3：水品走水量、配送费走余额，两维物理隔离，余额侧不含任何水费
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
            // 负额度没有任何合法来源；容忍它等于允许「负的已用额度」凭空放大余量
            return Verdict.reject(dimension + "额度参数非法：已用 " + used
                    + "，本次 " + requested + "，上限 " + cap);
        }
        if (used > cap) {
            // 已用超过上限本身就是账本断裂，不能只拒绝本次，必须让调用方转人工对账
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
