package com.jbk.serve.service.aftersale.batch;

import com.jbk.tool.data.aftersale.po.WsCardEntitlementBatch;
import com.jbk.tool.exception.JbkException;

import java.util.Comparator;

/**
 * 权益批次的消费选取次序——<b>单一出处</b>（E2E-04 包D，REQ-061）。
 *
 * <p>任务书原文：「消费优先级固定为：最早到期批次优先，同到期时间按创建时间优先」。</p>
 *
 * <h3>这个次序为什么是资金安全问题而不是偏好</h3>
 * <p>它决定「先扣谁的水」。次序若不确定，同一张卡在两次相同的消费下可能扣不同的批次，
 * 于是「某笔充值还剩多少」变成不可复现的值——而退款折算正是按批次剩余算的。
 * 更直接的危害：把<b>晚到期</b>的批次先扣空，早到期的那批就会带着余额过期作废，
 * 用户白白损失；反过来把正在退款流程里的批次先扣空，退款金额就被消费吃掉了。</p>
 *
 * <h3>永久批次排在最后</h3>
 * <p>{@code EXPIRE_TIME} 为 NULL 表示永久有效（D-213：正规付费卡永久）。
 * 永久权益不会作废，所以它应该<b>最后</b>被消费——先用会过期的，是对用户最有利的顺序，
 * 也避免「永久的用光了、有限期的过期了」这种两头落空。
 * 排序里把 NULL 当作最大值，而不是让它落到默认的最小值。</p>
 *
 * <p>本类只提供比较器，不查库、不写库：SQL 侧的 {@code ORDER BY} 与内存侧的排序
 * 必须给出同一个次序，故把口径写在这里，SQL 的 idx_batch_pick 索引列顺序与之对齐。</p>
 *
 * @author dakang
 * @since 2026-07-29
 */
public final class EntitlementBatchOrder {

    private EntitlementBatchOrder() {
    }

    /**
     * 消费选取次序：最早到期优先；同到期时间按创建时间升序；再同则按 ID 升序。
     *
     * <p>最后那道 ID 兜底不是可有可无的：{@code CREATE_TIME} 只精确到秒，
     * 同一秒内建出的两个批次靠前两级分不出先后，次序就退化成不确定——
     * 而不确定正是本类要消除的东西。</p>
     */
    public static Comparator<WsCardEntitlementBatch> consumeOrder() {
        return Comparator
                .comparing(EntitlementBatchOrder::expireKey)
                .thenComparing(b -> nullToEmpty(b.getCreateTime()))
                .thenComparing(b -> b.getId() == null ? Long.MAX_VALUE : b.getId());
    }

    /**
     * 到期时间的排序键：NULL（永久）映射为一个恒大于任何真实时间串的值。
     *
     * <p>业务时间串是 14 位 yyyyMMddHHmmss，字典序与时间序同构，
     * 故用 15 个 '9' 即可保证永久批次恒排在所有有限期批次之后，且不依赖任何日期上限假设。</p>
     */
    private static String expireKey(WsCardEntitlementBatch batch) {
        String expire = batch == null ? null : batch.getExpireTime();
        return expire == null || expire.isBlank() ? "999999999999999" : expire;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 断言批次可被消费。
     *
     * <p>退款锁定（2）与已退款（3）必须挡在这里：任务书明令「退款审核后必须锁定对应批次，
     * 防止退款处理中继续消费」。少了这道闸，用户在退款受理后还能把这批权益用掉，
     * 退款成功时冲正的就是一个已经被花掉的余额，账面出现负数或凭空补回。</p>
     *
     * <p><b>6不可退是可消费的</b>：它表达「这笔权益退不了款」，不表达「这笔权益花不了」。
     * 历史聚合批次承载存量卡的真实余额，把它判成不可消费，等于让所有存量卡在
     * D-4 上线当天集体停摆。可消费集合与 {@code lockConsumableByCard}、{@code consume}
     * 的 {@code BATCH_STATUS IN (1, 6)} 必须逐值一致——这里放宽而 SQL 没放宽，
     * 表现是「选得出却扣不动」；SQL 放宽而这里没放宽，表现是消费在内存断言处被拒。</p>
     */
    public static void requireConsumable(WsCardEntitlementBatch batch) {
        if (batch == null) {
            throw new JbkException("权益批次不存在，无法消费");
        }
        Integer status = batch.getBatchStatus();
        if (status == null) {
            throw new JbkException("权益批次状态缺失，拒绝消费");
        }
        if (status == BatchStatus.REFUND_LOCKED) {
            throw new JbkException("该权益批次正在退款处理中，已锁定，不可消费");
        }
        if (status != BatchStatus.AVAILABLE && status != BatchStatus.NON_REFUNDABLE) {
            throw new JbkException("权益批次当前状态不可消费：" + status);
        }
    }

    /** 批次状态(1374)。与迁移脚本的字典逐值对齐。 */
    public interface BatchStatus {
        int AVAILABLE = 1;
        int REFUND_LOCKED = 2;
        int REFUNDED = 3;
        int EXHAUSTED = 4;
        int EXPIRED = 5;
        int NON_REFUNDABLE = 6;
    }

    /** 批次来源(1375)。 */
    public interface SourceType {
        int FIRST_PURCHASE = 1;
        int RECHARGE = 2;
        /** 历史聚合权益，无法归属到具体充值订单，不可退款。 */
        int LEGACY = 3;
        /** 运营赠卡（E2E-08，字典 1375 值4）：无支付锚、恒不可退。 */
        int GIFT = 4;
    }
}
