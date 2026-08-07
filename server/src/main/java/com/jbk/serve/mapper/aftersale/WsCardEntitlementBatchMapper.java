package com.jbk.serve.mapper.aftersale;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.aftersale.po.WsCardEntitlementBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 权益批次 Mapper（E2E-04 包D，REQ-061）。
 *
 * <p>SQL 内不含任何 SQL 注释（Druid WallFilter comment-not-allow），说明写在 Javadoc。</p>
 *
 * <p><b>本类刻意没有「解除退款锁定」的 CAS</b>。曾经有一个 {@code unlockRefund}（2退款锁定 → 1可用），
 * 落地至今零调用方——任务书 3.4 明令「退款永久失败时不得自动恢复为未退款，进入人工处理」：
 * 一笔可能已在服务方侧出款的退款若把批次改回可消费，就是双花窗口。
 * 留一个没有调用方的解锁 CAS 只会诱导后来者接上自动解锁，故删除。
 * 将来确实要做人工解锁，应连同「谁批准、留什么痕、如何与在途退款单互斥」一起设计，
 * 而不是复活一条裸 UPDATE。</p>
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
     * 按消费次序锁定该卡的可消费批次。
     *
     * <p><b>ORDER BY 必须与 {@code EntitlementBatchOrder.consumeOrder()} 同口径</b>：
     * SQL 侧与内存侧给出不同次序，就等于「先扣谁」有两个答案，而退款折算按批次剩余算——
     * 次序不确定则批次剩余不可复现。永久批次（EXPIRE_TIME IS NULL）排最后，
     * 用 IS NULL 升序表达（false=0 在前，true=1 在后）。</p>
     *
     * <p>FOR UPDATE 是必须的：分摊是读-判-写，不锁定则两笔并发消费会读到同一份剩余，
     * 各自都认为够扣，合起来超发。锁定读把同一张卡的消费串行化。</p>
     *
     * <p><b>为什么 6不可退 也在可消费集合里</b>：6 表达的是「退不了款」，不是「花不了钱」。
     * 历史聚合批次（D-2 回填）承载的正是存量卡当下的真实余额与水量，用户随时可以取水、下配送单。
     * 只认 1可用 会让每一张存量卡在 D-4 上线当天变成「余额看得见、一分也扣不动」——
     * 卡侧 CAS 扣减成功、批次侧凑不出额度，整笔消费回滚，E2E-01 与 E2E-03 全线瘫痪。
     * 退款侧的收口在别处（{@link #lockForRefund} 只认 1可用），两侧口径刻意不同。</p>
     *
     * <p>剩余全零的批次不进结果集：它一分钱也贡献不了，锁它只是白扩大锁面；
     * 而零剩余批次唯一的增长路径是 {@link #restoreAllocated}/{@link #growLegacy}，
     * 两者都按 ID 锁定后 CAS，不依赖本次范围锁定读，故排除它不会丢更新。</p>
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
     * 消费扣减 CAS：从批次剩余里扣走指定额度。
     *
     * <p>WHERE 带 {@code REMAIN_* >= 扣减量} 与 VERSION：影响 0 行即余额不足或已被并发改动，
     * 调用方必须整体回滚而不是补一次读-算-写（铁律①）。</p>
     *
     * <p>{@code BATCH_STATUS IN (1, 6)} 与 {@link #lockConsumableByCard} 同集合，一处放宽另一处不放
     * 就会出现「选得出、扣不动」的死路。<b>2退款锁定必须挡在这里</b>：退款受理后继续消费，
     * 退款成功时冲正的就是一笔已经被花掉的余额。这条谓词是那道闸的库层落点，
     * 内存侧的 {@code EntitlementBatchOrder.requireConsumable} 只是它的前置提示。</p>
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
     * 返还回补 CAS：把一笔已分摊的消费额度还回原批次（退差、配送取消、申诉补偿共用）。
     *
     * <p><b>为什么返还必须回到批次而不是只加卡</b>：卡聚合值与批次剩余合计必须逐卡相等，
     * 这是消费分摊能成立的全部前提（D-2 回填脚本的后置不变式已经把这条钉死）。
     * 只给卡加钱不给批次回补，用户账面上多出的那部分永远凑不出批次额度——
     * 下一次取水会在「卡扣成功、批次不足」处整笔回滚，表现为「有余额却下不了单」。</p>
     *
     * <p>{@code REMAIN_* + 回补量 <= GRANT_*} 是第二道网：单批次剩余永远不该超过它当初的发放量。
     * 越界说明回补量不是来自本批次的分摊记录（调用方算错了或分摊行被改写），
     * 此时宁可 0 行整体回滚，也不能凭空把发放量之外的权益塞进一个可退款批次——
     * 那等于用返还制造出可以套现的额度。</p>
     *
     * <p>{@code BATCH_STATUS IN (1, 6)}：已退款(3)批次不接受回补——它的剩余已经清零并原路退给用户，
     * 再加回来就是同一笔权益既退了钱又还在卡上；2退款锁定同理，回补要等退款结论出来。
     * 落到这两种状态时影响 0 行，调用方按账本断裂转人工，不得静默跳过。</p>
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
     * 历史聚合批次扩容 CAS：把无法归属到任何批次的权益并入该卡的「不可退」桶。
     *
     * <p>用途只有一个：D-4 上线<b>之前</b>发生的消费没有分摊记录，对它的返还找不到可回补的批次。
     * 若就此不回补，卡聚合值会永久高于批次剩余合计，那张卡从此扣不动这部分余额。
     * 故把这部分并入 {@code SOURCE_TYPE=3} 的历史聚合批次：不变式恢复，用户能继续消费，
     * 而 {@code BATCH_STATUS=6} + {@code PAY_AMOUNT_FEN=0} 保证它<b>永远不会变成可退款基准</b>
     * ——这与回填脚本「绝不反向伪造到历史充值订单」是同一条口径。</p>
     *
     * <p>WHERE 里三条身份谓词（{@code SOURCE_TYPE=3}、{@code BATCH_STATUS=6}、{@code PAY_AMOUNT_FEN=0}）
     * 缺一不可：少了它们，本方法就成了「可以给任意批次凭空加发放量」的后门，
     * 而发放量正是水量套餐折算公式的分母。</p>
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

    /**
     * 锁定该卡的历史聚合批次（每卡至多一条，由回填脚本的后置不变式保证）。
     *
     * <p>FOR UPDATE：{@link #growLegacy} 是读版本再 CAS，不锁定则两笔并发返还会拿到同一版本，
     * 输家 0 行、整事务回滚——正确但没必要，锁定让它们排队。</p>
     */
    @Select("SELECT " + COLUMNS + " FROM ws_card_entitlement_batch "
            + "WHERE CARD_ID = #{cardId} AND DATA_STATUS = 0 AND SOURCE_TYPE = 3 "
            + "ORDER BY ID LIMIT 1 FOR UPDATE")
    WsCardEntitlementBatch lockLegacyByCard(@Param("cardId") Long cardId);

    /**
     * 退款锁定：1可用 → 2退款锁定。
     *
     * <p>只允许从 1可用 出发：已退款或已耗尽的批次再锁一次没有意义，
     * 而允许从任意状态锁定会让「已退款」被拖回「处理中」，同一笔退两次。</p>
     */
    @Update("UPDATE ws_card_entitlement_batch SET BATCH_STATUS = 2, REFUND_LOCKED_BY = #{afterSaleId}, "
            + "REFUND_LOCK_TIME = #{now}, VERSION = VERSION + 1, UPDATE_BY = #{opUserId}, UPDATE_TIME = #{now} "
            + "WHERE ID = #{id} AND VERSION = #{expectedVersion} AND DATA_STATUS = 0 AND BATCH_STATUS = 1")
    int lockForRefund(@Param("id") Long id,
                      @Param("expectedVersion") Integer expectedVersion,
                      @Param("afterSaleId") Long afterSaleId,
                      @Param("opUserId") Long opUserId,
                      @Param("now") String now);

    /**
     * 退款成功冲正：2退款锁定 → 3已退款，剩余权益清零并累计已退金额。
     *
     * <p>{@code REFUND_LOCKED_BY = #{afterSaleId}} 进 WHERE：只有锁定它的那笔售后能冲正它。
     * 少了这条谓词，任意一笔退款成功都能把某个批次标成已退款并清零其剩余。</p>
     */
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
     * 按稳定 ID 序锁定该卡<b>全部</b>未删除且尚有正剩余的批次——<b>不限状态</b>
     * （审计 R2 P0-2，2026-08-07）。
     *
     * <p>与 {@link #lockConsumableByCard} 的分工：那边是「能花哪些」（状态 1/6，消费选取），
     * 这边是「账上还有哪些」（含退款锁定 2 等非消费态）。到期清算与赠卡转正的账本等式
     * 「SUM(全部正剩余) == 卡余额」必须对<b>全集</b>成立——只看可消费子集就宣称账本归零，
     * 恰是 R2 驳回的 P0：状态 2 的正余额批次会在转正时被静默永久化。</p>
     *
     * <p>ORDER BY ID 恒定锁序（防死锁）；调用方需要消费次序时自行内存重排。</p>
     */
    @Select("SELECT " + COLUMNS + " FROM ws_card_entitlement_batch "
            + "WHERE CARD_ID = #{cardId} AND DATA_STATUS = 0 "
            + "AND (REMAIN_AMOUNT_FEN > 0 OR REMAIN_WATER_ML > 0) "
            + "ORDER BY ID FOR UPDATE")
    List<WsCardEntitlementBatch> lockAllPositiveRemainByCard(@Param("cardId") Long cardId);

    /**
     * 批次到期作废 CAS（审计 P0-1 整改，2026-08-07）：置5（已过期）并清零剩余。
     *
     * <p>只能由 {@code EntitlementLedger.settleExpired} 在持卡行锁内调用——它是全仓
     * <b>唯一</b>的批次到期清算入口，卡聚合值的同步扣减与 EXPIRE_CLEAR 流水都在那里
     * 同一事务完成。单独调本 CAS 会把「批次清了、卡没减」的断裂账本直接写进库。</p>
     *
     * <p>VERSION 前态 + 状态白名单 (1,6) + 剩余>0 三重防线：行锁在手时 0 行意味着
     * 同事务内数据已不自洽，调用方必须抛出回滚，不得跳过。</p>
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
