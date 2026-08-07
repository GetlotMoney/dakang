package com.jbk.serve.service.aftersale.batch;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 权益批次测试夹具（E2E-04 包D-4）——<b>只在真库测试里给「直接 INSERT 出来的卡」补批次</b>。
 *
 * <h3>为什么每个真库测试都要用它</h3>
 * <p>D-4 之后取水/配送的扣减在同事务里要把额度摊到批次上，凑不出额度就整笔回滚。
 * 生产里的卡永远带批次：购卡与充值入账同事务建批次（包D-3），存量卡由回填脚本建历史聚合批次，
 * 全新环境由 {@code 02-ws-business.sql} 的种子建。而测试里的卡是一条裸 INSERT，
 * 不补批次就等于构造了一个生产中不存在的断裂账本，跑出来的红是夹具的问题而不是被测代码的问题。</p>
 *
 * <p>形态与回填脚本逐列一致：{@code SOURCE_TYPE=3历史聚合}、{@code BATCH_STATUS=6不可退}、
 * {@code PAY_AMOUNT_FEN=0}、{@code ORDER_ID} 为 NULL。刻意用「不可退」形态而不是伪造一个可退批次：
 * 需要可退批次的用例（包D-5 退款）必须自己按充值语义建，不能顺手复用这里的夹具。</p>
 */
public final class EntitlementFixture {

    private EntitlementFixture() {
    }

    /**
     * 给指定卡补一条历史聚合批次，剩余额度恰等于卡的聚合值。
     *
     * <p>先删后插：测试用例常在同一个方法里反复重置同一张卡的余额，
     * 只插不删会留下多条批次，「每卡至多一条历史聚合批次」的口径就破了。</p>
     */
    public static void seedLegacyBatch(JdbcTemplate jdbc, long cardId, long userId, long fen, long ml) {
        jdbc.update("DELETE FROM ws_card_entitlement_batch WHERE CARD_ID=?", cardId);
        jdbc.update("INSERT INTO ws_card_entitlement_batch(DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,"
                        + "UPDATE_TIME,CARD_ID,USER_ID,SOURCE_TYPE,PAY_AMOUNT_FEN,GRANT_AMOUNT_FEN,"
                        + "GRANT_BONUS_FEN,GRANT_WATER_ML,REMAIN_AMOUNT_FEN,REMAIN_WATER_ML,"
                        + "BATCH_STATUS,REFUNDED_AMOUNT_FEN,VERSION) "
                        + "VALUES(0,1,'20260729000000',1,'20260729000000',?,?,3,0,?,0,?,?,?,6,0,1)",
                cardId, userId, fen, ml, fen, ml);
    }

    /** 该卡的批次剩余合计——与卡聚合值比对，是 D-4 不变式的断言口径。 */
    public static long sumRemainFen(JdbcTemplate jdbc, long cardId) {
        Long sum = jdbc.queryForObject("SELECT IFNULL(SUM(REMAIN_AMOUNT_FEN),0) FROM ws_card_entitlement_batch "
                + "WHERE CARD_ID=? AND DATA_STATUS=0", Long.class, cardId);
        return sum == null ? 0L : sum;
    }

    /** 同上，水量维度。 */
    public static long sumRemainMl(JdbcTemplate jdbc, long cardId) {
        Long sum = jdbc.queryForObject("SELECT IFNULL(SUM(REMAIN_WATER_ML),0) FROM ws_card_entitlement_batch "
                + "WHERE CARD_ID=? AND DATA_STATUS=0", Long.class, cardId);
        return sum == null ? 0L : sum;
    }
}
