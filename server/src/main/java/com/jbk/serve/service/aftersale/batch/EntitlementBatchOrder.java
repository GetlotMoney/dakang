package com.jbk.serve.service.aftersale.batch;

import com.jbk.tool.data.aftersale.po.WsCardEntitlementBatch;
import com.jbk.tool.exception.JbkException;

import java.util.Comparator;

/**
 * 权益批次的消费选取次序——<b>单一出处</b>（E2E-04 包D，REQ-061）。
 * 任务书原文：「消费优先级固定为：最早到期批次优先，同到期时间按创建时间优先」。
 *
 * <p>次序是资金安全问题：不确定的次序让「某笔充值还剩多少」不可复现，而退款折算正按批次剩余算。
 * 永久批次（EXPIRE_TIME=NULL，D-213）排最后：先用会过期的对用户最有利，排序把 NULL 当最大值。
 * 只提供比较器不查库；SQL 的 ORDER BY 与 idx_batch_pick 索引列顺序与之对齐。</p>
 *
 * @author dakang
 * @since 2026-07-29
 */
public final class EntitlementBatchOrder {

    private EntitlementBatchOrder() {
    }

    /**
     * 消费选取次序：最早到期优先；同到期按创建时间升序；再同按 ID 升序兜底
     * （CREATE_TIME 只精确到秒，缺 ID 兜底则同秒批次次序不确定）。
     */
    public static Comparator<WsCardEntitlementBatch> consumeOrder() {
        return Comparator
                .comparing(EntitlementBatchOrder::expireKey)
                .thenComparing(b -> nullToEmpty(b.getCreateTime()))
                .thenComparing(b -> b.getId() == null ? Long.MAX_VALUE : b.getId());
    }

    /**
     * 到期时间排序键：NULL（永久）映射为 15 个 '9'——14 位 yyyyMMddHHmmss 字典序与时间序同构，
     * 永久批次恒排最后且不依赖日期上限假设。
     */
    private static String expireKey(WsCardEntitlementBatch batch) {
        String expire = batch == null ? null : batch.getExpireTime();
        return expire == null || expire.isBlank() ? "999999999999999" : expire;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 断言批次可被消费。退款锁定（2）与已退款（3）必须挡住（任务书明令：退款审核后锁定批次，
     * 防止处理中继续消费）。6不可退是<b>可消费</b>的——它只表达退不了款，判成不可消费会让
     * 存量卡集体停摆；可消费集合必须与 {@code lockConsumableByCard}/{@code consume} 的
     * {@code BATCH_STATUS IN (1, 6)} 逐值一致。
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
