package com.jbk.serve.service.aftersale.batch;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 权益批次测试夹具（E2E-04 包D-4）：给裸 INSERT 的卡补历史聚合批次（SOURCE_TYPE=3、
 * BATCH_STATUS=6不可退、与回填脚本逐列一致），否则构造的是生产不存在的断裂账本。
 * 需要可退批次的用例必须自己按充值语义建，不得复用本夹具。
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
