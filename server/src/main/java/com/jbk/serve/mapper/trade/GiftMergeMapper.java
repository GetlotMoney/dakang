package com.jbk.serve.mapper.trade;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 赠卡合并入正式水卡的资金 SQL（D-415）。合并=同一事务：赠卡清零作废 + 主卡原子加 + 批次整体改挂主卡（时效由批次层承载，主卡有效期不动）。
 * 已自然过期的赠卡不走本 Mapper（批次作废统一经 EntitlementLedger.settleExpired，审计 P0-1）。
 * 全部 CAS，任一影响行数≠预期即整事务回滚；不变式「SUM(批次剩余)==卡余额」须对两张卡同时成立。SQL 内不写注释（Druid WallFilter）。
 *
 * @author dakang
 * @since 2026-08-07
 */
@Mapper
public interface GiftMergeMapper {

    /**
     * 主卡原子入账（合并转入）。与 {@code RechargeCreditMapper.creditFiniteCard} 的差别刻意：不改 EXPIRE_TIME/PACKAGE_*（转入时效在批次层）；
     * CARD_STATUS=1 收紧于售后返还白名单，冻结/过期主卡不接收合并。
     *
     * @return 影响行数（必须为 1）
     */
    @Update("UPDATE ws_card SET BALANCE_AMOUNT = BALANCE_AMOUNT + #{amountFen}, "
            + "BALANCE_ML = BALANCE_ML + #{ml}, UPDATE_BY = #{opUserId}, UPDATE_TIME = #{now} "
            + "WHERE ID = #{cardId} AND USER_ID = #{opUserId} AND DATA_STATUS = 0 "
            + "AND CARD_STATUS = 1 "
            + "AND BALANCE_AMOUNT = #{oldAmount} AND BALANCE_ML = #{oldMl} "
            + "AND EXPIRE_TIME <=> #{oldExpireTime}")
    int mergeCreditPaidCard(@Param("cardId") Long cardId,
                            @Param("amountFen") Long amountFen,
                            @Param("ml") Long ml,
                            @Param("opUserId") Long opUserId,
                            @Param("now") String now,
                            @Param("oldAmount") Long oldAmount,
                            @Param("oldMl") Long oldMl,
                            @Param("oldExpireTime") String oldExpireTime);

    /**
     * 赠卡清零并作废（合并转出侧）。WHERE 的赠卡形态谓词（与 CardEligibility#isGiftCard 同构）是库层防线：调用方判错也碰不到付费卡；
     * 状态白名单 (1,3) 与转正入口同口径，已注销(4)由调用方走幂等分支。
     *
     * @return 影响行数（必须为 1）
     */
    @Update("UPDATE ws_card SET BALANCE_AMOUNT = 0, BALANCE_ML = 0, CARD_STATUS = 4, "
            + "UPDATE_BY = #{opUserId}, UPDATE_TIME = #{now} "
            + "WHERE ID = #{cardId} AND USER_ID = #{opUserId} AND DATA_STATUS = 0 "
            + "AND CARD_STATUS IN (1, 3) "
            + "AND " + com.jbk.serve.service.mini.card.CardEligibility.SQL_GIFT + " "
            + "AND BALANCE_AMOUNT = #{oldAmount} AND BALANCE_ML = #{oldMl} "
            + "AND EXPIRE_TIME = #{oldExpireTime}")
    int cancelGiftCardForMerge(@Param("cardId") Long cardId,
                               @Param("opUserId") Long opUserId,
                               @Param("now") String now,
                               @Param("oldAmount") Long oldAmount,
                               @Param("oldMl") Long oldMl,
                               @Param("oldExpireTime") String oldExpireTime);

    /**
     * 赠卡剩余批次整体改挂主卡（原 EXPIRE_TIME/SCOPE_JSON/状态原样搬家）。选「改挂」而非「关旧建新」：保持发放快照与批次连续性，不给批次写入开第三个入口。
     * 剩余为 0 的批次留在赠卡作历史；状态集 (1,6) 与 {@code lockConsumableByCard} 逐值一致，影响行数与锁内清点数不等即回滚。
     *
     * @return 影响行数（必须等于锁内清点的剩余>0 批次数）
     */
    @Update("UPDATE ws_card_entitlement_batch SET CARD_ID = #{toCardId}, "
            + "UPDATE_BY = #{opUserId}, UPDATE_TIME = #{now}, VERSION = VERSION + 1 "
            + "WHERE CARD_ID = #{fromCardId} AND USER_ID = #{opUserId} AND DATA_STATUS = 0 "
            + "AND BATCH_STATUS IN (1, 6) "
            + "AND (REMAIN_AMOUNT_FEN > 0 OR REMAIN_WATER_ML > 0)")
    int migrateBatchesToCard(@Param("fromCardId") Long fromCardId,
                             @Param("toCardId") Long toCardId,
                             @Param("opUserId") Long opUserId,
                             @Param("now") String now);

}
