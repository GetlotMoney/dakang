package com.jbk.serve.mapper.aftersale;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.aftersale.po.WsCardEntitlementBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 权益批次 Mapper（E2E-04 包D，REQ-061）。SQL 内不含 SQL 注释（Druid WallFilter comment-not-allow）。
 * 刻意不提供「解除退款锁定」CAS：任务书 3.4 明令退款永久失败不得自动恢复可消费（双花窗口），人工解锁需连同审批与互斥另行设计。
 */
@Mapper
public interface WsCardEntitlementBatchMapper extends BaseMapper<WsCardEntitlementBatch> {

    String COLUMNS = "ID AS id, CARD_ID AS cardId, USER_ID AS userId, SOURCE_TYPE AS sourceType, "
            + "ORDER_ID AS orderId, ORDER_NO AS orderNo, PAYMENT_ID AS paymentId, PACKAGE_ID AS packageId, "
            + "PACKAGE_SNAP AS packageSnap, PAY_AMOUNT_FEN AS payAmountFen, GRANT_AMOUNT_FEN AS grantAmountFen, "
            + "GRANT_BONUS_FEN AS grantBonusFen, GRANT_WATER_ML AS grantWaterMl, "
            + "REMAIN_AMOUNT_FEN AS remainAmountFen, REMAIN_WATER_ML AS remainWaterMl, "
            + "EXPIRE_TIME AS expireTime, SCOPE_JSON AS scopeJson, BATCH_STATUS AS batchStatus, "
            + "REFUND_LOCKED_BY AS refundLockedBy, REFUND_LOCK_TIME AS refundLockTime, "
            + "REFUNDED_AMOUNT_FEN AS refundedAmountFen, VERSION AS version, DATA_STATUS AS dataStatus, "
            + "CREATE_TIME AS createTime";

    /**
     * 按消费次序锁定该卡的可消费批次。FOR UPDATE：分摊是读-判-写，不锁则并发消费各自认为够扣、合起来超发。
     * ORDER BY 必须与 {@code EntitlementBatchOrder.consumeOrder()} 同口径（否则退款折算的批次剩余不可复现）；永久批次（EXPIRE_TIME IS NULL）排最后。
     * 6不可退 也可消费：它只是退不了款（历史聚合批次承载存量真实余额），退款侧收口在 {@link #lockForRefund}（只认 1可用），两侧口径刻意不同。
     * 剩余全零的批次不进结果集；其增长路径（{@link #restoreAllocated}/{@link #growLegacy}）按 ID 锁定后 CAS，不依赖本范围锁。
     */
    @Select("SELECT " + COLUMNS + " FROM ws_card_entitlement_batch "
            + "WHERE CARD_ID = #{cardId} AND DATA_STATUS = 0 AND BATCH_STATUS IN (1, 6) "
            + "AND (REMAIN_AMOUNT_FEN > 0 OR REMAIN_WATER_ML > 0) "
            + "ORDER BY (EXPIRE_TIME IS NULL), EXPIRE_TIME, CREATE_TIME, ID FOR UPDATE")
    List<WsCardEntitlementBatch> lockConsumableByCard(@Param("cardId") Long cardId);

    @Select("SELECT " + COLUMNS + " FROM ws_card_entitlement_batch WHERE ORDER_ID = #{orderId}")
    WsCardEntitlementBatch selectByOrderId(@Param("orderId") Long orderId);

    @Select("SELECT " + COLUMNS + " FROM ws_card_entitlement_batch WHERE ID = #{id} FOR UPDATE")
    WsCardEntitlementBatch lockById(@Param("id") Long id);

    /**
     * 消费扣减 CAS：WHERE 带 REMAIN_* >= 扣减量与 VERSION，0 行即余额不足或并发改动，必须整体回滚（铁律①）。
     * BATCH_STATUS IN (1,6) 与 {@link #lockConsumableByCard} 同集合；2退款锁定必须挡在这里，否则退款成功冲正的是已被花掉的余额。
     */
    @Update("UPDATE ws_card_entitlement_batch "
            + "SET REMAIN_AMOUNT_FEN = REMAIN_AMOUNT_FEN - #{amountFen}, "
            + "REMAIN_WATER_ML = REMAIN_WATER_ML - #{waterMl}, VERSION = VERSION + 1, "
            + "UPDATE_BY = #{opUserId}, UPDATE_TIME = #{now} "
            + "WHERE ID = #{id} AND VERSION = #{expectedVersion} AND DATA_STATUS = 0 "
            + "AND BATCH_STATUS IN (1, 6) "
            + "AND REMAIN_AMOUNT_FEN >= #{amountFen} AND REMAIN_WATER_ML >= #{waterMl}")
    int consume(@Param("id") Long id,
                @Param("expectedVersion") Integer expectedVersion,
                @Param("amountFen") Long amountFen,
                @Param("waterMl") Long waterMl,
                @Param("opUserId") Long opUserId,
                @Param("now") String now);

    /**
     * 返还回补 CAS（退差/配送取消/申诉补偿共用）。必须回批次而非只加卡：不变式「SUM(批次剩余)==卡余额」是分摊成立的前提，破了会「有余额却下不了单」。
     * REMAIN_* + 回补量 <= GRANT_* 防越界：越界说明回补量不来自本批次，0 行整体回滚，绝不凭空造出可退款额度。
     * 已退款(3)/退款锁定(2)不接受回补：0 行按账本断裂转人工，不得静默跳过。
     */
    @Update("UPDATE ws_card_entitlement_batch "
            + "SET REMAIN_AMOUNT_FEN = REMAIN_AMOUNT_FEN + #{amountFen}, "
            + "REMAIN_WATER_ML = REMAIN_WATER_ML + #{waterMl}, VERSION = VERSION + 1, "
            + "UPDATE_BY = #{opUserId}, UPDATE_TIME = #{now} "
            + "WHERE ID = #{id} AND VERSION = #{expectedVersion} AND DATA_STATUS = 0 "
            + "AND BATCH_STATUS IN (1, 6) "
            + "AND REMAIN_AMOUNT_FEN + #{amountFen} <= GRANT_AMOUNT_FEN "
            + "AND REMAIN_WATER_ML + #{waterMl} <= GRANT_WATER_ML")
    int restoreAllocated(@Param("id") Long id,
                         @Param("expectedVersion") Integer expectedVersion,
                         @Param("amountFen") Long amountFen,
                         @Param("waterMl") Long waterMl,
                         @Param("opUserId") Long opUserId,
                         @Param("now") String now);

    /**
     * 历史聚合批次扩容 CAS：D-4 上线前的消费无分摊记录，其返还并入 SOURCE_TYPE=3 的「不可退」桶以恢复卡批次不变式；
     * BATCH_STATUS=6 + PAY_AMOUNT_FEN=0 保证它永不变成可退款基准。
     * WHERE 三条身份谓词（SOURCE_TYPE=3、BATCH_STATUS=6、PAY_AMOUNT_FEN=0）缺一不可，否则本方法成为凭空加发放量的后门。
     */
    @Update("UPDATE ws_card_entitlement_batch "
            + "SET GRANT_AMOUNT_FEN = GRANT_AMOUNT_FEN + #{amountFen}, "
            + "GRANT_WATER_ML = GRANT_WATER_ML + #{waterMl}, "
            + "REMAIN_AMOUNT_FEN = REMAIN_AMOUNT_FEN + #{amountFen}, "
            + "REMAIN_WATER_ML = REMAIN_WATER_ML + #{waterMl}, VERSION = VERSION + 1, "
            + "UPDATE_BY = #{opUserId}, UPDATE_TIME = #{now} "
            + "WHERE ID = #{id} AND VERSION = #{expectedVersion} AND DATA_STATUS = 0 "
            + "AND SOURCE_TYPE = 3 AND BATCH_STATUS = 6 AND PAY_AMOUNT_FEN = 0")
    int growLegacy(@Param("id") Long id,
                   @Param("expectedVersion") Integer expectedVersion,
                   @Param("amountFen") Long amountFen,
                   @Param("waterMl") Long waterMl,
                   @Param("opUserId") Long opUserId,
                   @Param("now") String now);

    /** 锁定该卡的历史聚合批次（每卡至多一条）。FOR UPDATE 让并发返还排队，而不是 CAS 输家整事务回滚。 */
    @Select("SELECT " + COLUMNS + " FROM ws_card_entitlement_batch "
            + "WHERE CARD_ID = #{cardId} AND DATA_STATUS = 0 AND SOURCE_TYPE = 3 "
            + "ORDER BY ID LIMIT 1 FOR UPDATE")
    WsCardEntitlementBatch lockLegacyByCard(@Param("cardId") Long cardId);

    /** 退款锁定：1可用 → 2退款锁定。只允许从 1可用 出发，否则「已退款」可被拖回处理中、同一笔退两次。 */
    @Update("UPDATE ws_card_entitlement_batch SET BATCH_STATUS = 2, REFUND_LOCKED_BY = #{afterSaleId}, "
            + "REFUND_LOCK_TIME = #{now}, VERSION = VERSION + 1, UPDATE_BY = #{opUserId}, UPDATE_TIME = #{now} "
            + "WHERE ID = #{id} AND VERSION = #{expectedVersion} AND DATA_STATUS = 0 AND BATCH_STATUS = 1")
    int lockForRefund(@Param("id") Long id,
                      @Param("expectedVersion") Integer expectedVersion,
                      @Param("afterSaleId") Long afterSaleId,
                      @Param("opUserId") Long opUserId,
                      @Param("now") String now);

    /** 退款成功冲正：2退款锁定 → 3已退款，剩余清零并累计已退金额。REFUND_LOCKED_BY 进 WHERE：只有锁定它的那笔售后能冲正。 */
    @Update("UPDATE ws_card_entitlement_batch SET BATCH_STATUS = 3, "
            + "REFUNDED_AMOUNT_FEN = REFUNDED_AMOUNT_FEN + #{refundedFen}, "
            + "REMAIN_AMOUNT_FEN = 0, REMAIN_WATER_ML = 0, VERSION = VERSION + 1, "
            + "UPDATE_BY = #{opUserId}, UPDATE_TIME = #{now} "
            + "WHERE ID = #{id} AND VERSION = #{expectedVersion} AND DATA_STATUS = 0 "
            + "AND BATCH_STATUS = 2 AND REFUND_LOCKED_BY = #{afterSaleId}")
    int settleRefunded(@Param("id") Long id,
                       @Param("expectedVersion") Integer expectedVersion,
                       @Param("afterSaleId") Long afterSaleId,
                       @Param("refundedFen") Long refundedFen,
                       @Param("opUserId") Long opUserId,
                       @Param("now") String now);

    /**
     * 按 ID 序锁定该卡全部正剩余批次——不限状态（审计 R2 P0-2）。与 {@link #lockConsumableByCard} 分工：那边「能花哪些」，这边「账上还有哪些」。
     * 到期清算与赠卡转正的账本等式「SUM(全部正剩余)==卡余额」必须对全集成立，只看可消费子集会把状态 2 的正余额静默永久化。
     * ORDER BY ID 恒定锁序防死锁；需要消费次序时调用方内存重排。
     */
    @Select("SELECT " + COLUMNS + " FROM ws_card_entitlement_batch "
            + "WHERE CARD_ID = #{cardId} AND DATA_STATUS = 0 "
            + "AND (REMAIN_AMOUNT_FEN > 0 OR REMAIN_WATER_ML > 0) "
            + "ORDER BY ID FOR UPDATE")
    List<WsCardEntitlementBatch> lockAllPositiveRemainByCard(@Param("cardId") Long cardId);

    /**
     * 批次到期作废 CAS（审计 P0-1）：置5并清零剩余。只能由 {@code EntitlementLedger.settleExpired} 在持卡行锁内调用——
     * 全仓唯一到期清算入口，单独调用会写出「批次清了、卡没减」的断裂账本；行锁在手仍 0 行=数据不自洽，必须抛出回滚。
     */
    @Update("UPDATE ws_card_entitlement_batch SET BATCH_STATUS = 5, "
            + "REMAIN_AMOUNT_FEN = 0, REMAIN_WATER_ML = 0, "
            + "UPDATE_BY = #{opUserId}, UPDATE_TIME = #{now}, VERSION = VERSION + 1 "
            + "WHERE ID = #{id} AND VERSION = #{expectedVersion} AND DATA_STATUS = 0 "
            + "AND BATCH_STATUS IN (1, 6) "
            + "AND (REMAIN_AMOUNT_FEN > 0 OR REMAIN_WATER_ML > 0)")
    int expireBatch(@Param("id") Long id,
                    @Param("expectedVersion") Integer expectedVersion,
                    @Param("opUserId") Long opUserId,
                    @Param("now") String now);

    /** 该卡的批次剩余合计，用于与卡聚合值对账。 */
    @Select("SELECT IFNULL(SUM(REMAIN_AMOUNT_FEN), 0) FROM ws_card_entitlement_batch "
            + "WHERE CARD_ID = #{cardId} AND DATA_STATUS = 0")
    long sumRemainFenByCard(@Param("cardId") Long cardId);

    @Select("SELECT IFNULL(SUM(REMAIN_WATER_ML), 0) FROM ws_card_entitlement_batch "
            + "WHERE CARD_ID = #{cardId} AND DATA_STATUS = 0")
    long sumRemainMlByCard(@Param("cardId") Long cardId);
}
