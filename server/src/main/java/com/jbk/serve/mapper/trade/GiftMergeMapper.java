package com.jbk.serve.mapper.trade;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 赠卡合并入正式水卡的资金 SQL（D-415，2026-08-06 甲方决议）。
 *
 * <p>合并 = 同一事务内四步：赠卡清零作废 + 主卡余额/水量原子加 + 权益批次整体改挂主卡
 * （批次携带原 EXPIRE_TIME，「合并后显示多久到期」由批次层承载，主卡自身有效期不动）。
 * 已自然过期的赠卡不走本 Mapper：批次作废统一经 EntitlementLedger.settleExpired 唯一清算入口（审计 P0-1）。</p>
 *
 * <p>四条 SQL 全部 CAS（WHERE 带锁内读取的全部前态），任何一条影响行数 ≠ 预期即整事务回滚。
 * 逐卡不变式「SUM(批次剩余) == 卡余额」必须在合并前后对两张卡同时成立：
 * 迁移分支批次剩余随行搬走（赠卡两侧同减、主卡两侧同增）；过期分支两侧同清零。</p>
 *
 * <p>资金铁律：禁止读出→内存计算→写回；SQL 内不写注释（Druid WallFilter）。</p>
 *
 * @author dakang
 * @since 2026-08-07
 */
@Mapper
public interface GiftMergeMapper {

    /**
     * 主卡原子入账（合并转入）。
     *
     * <p>与 {@code RechargeCreditMapper.creditFiniteCard} 的差别是刻意的：合并<b>不改</b>
     * EXPIRE_TIME / PACKAGE_ID / PACKAGE_SNAP——转入权益的时效在批次层，主卡口径保持原样。
     * {@code CARD_STATUS = 1} 收紧于售后返还的 (1,2,3) 白名单：冻结/过期的主卡不接收合并
     * （权益进去立刻被冻结或到期日绑住），调用方在锁内先拒。</p>
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
     * 赠卡清零并作废（合并转出侧）。
     *
     * <p>WHERE 里的 {@code CARD_TYPE = 1 AND EXPIRE_TIME IS NOT NULL AND ISSUE_ORDER_ID IS NULL}（与 CardEligibility#isGiftCard 同构）是赠卡形态谓词
     * 的库层防线：即使调用方判错，这条 UPDATE 也碰不到付费卡。状态白名单 (1,3) 与转正入口
     * 同口径——冻结(2)的赠卡不允许合并，已注销(4)的由调用方走幂等分支。</p>
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
     * 把赠卡上仍有剩余的可消费批次整体改挂到主卡（携带原 EXPIRE_TIME / SCOPE_JSON /
     * NON_REFUNDABLE 状态原样搬家）。
     *
     * <p>选「改挂」而不是「关旧建新」：批次是退款折算与消费选取的唯一基准，改挂保持
     * 发放快照与批次连续性，主卡的消费次序（最早到期先用）自动把它排进正确位置；
     * 关旧建新则要给 {@code EntitlementBatchWriter} 开第三个入口并复制快照，对账还要
     * 解释「赠卡批次为何剩余清零却不是过期」。</p>
     *
     * <p>剩余为 0 的批次留在赠卡上作历史，不搬。状态集 (1, 6) 与
     * {@code lockConsumableByCard} 逐值一致；调用方必须先经该方法锁内清点，
     * 影响行数与清点数不等即回滚。</p>
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
