package com.jbk.serve.mapper.aftersale;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.aftersale.po.WsEntitlementAllocation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 权益分摊 Mapper（E2E-04 包D，REQ-061）。SQL 内不含 SQL 注释（Druid WallFilter）。
 *
 * <p><b>刻意没有「按批次求已消费合计」的查询</b>。D-2 最初设想用分摊合计反推「这批次用掉了多少」，
 * 而 D-5 冻结的折算口径是「已消费 = 发放量 − 批次剩余」，取自批次行本身——那才是 CAS 保护的值。
 * 两个来源并存就会有两个答案，且它们在部分回补后并不相等。故收敛时删掉了那三个零调用查询
 * （selectActiveByBatch / sumConsumedFenByBatch / sumConsumedMlByBatch）：
 * 折算的唯一输入是 {@code ws_card_entitlement_batch} 的剩余列。</p>
 */
@Mapper
public interface WsEntitlementAllocationMapper extends BaseMapper<WsEntitlementAllocation> {

    String COLUMNS = "ID AS id, BATCH_ID AS batchId, CARD_ID AS cardId, FLOW_ID AS flowId, "
            + "ORDER_ID AS orderId, BIZ_KEY AS bizKey, ALLOC_SEQ AS allocSeq, "
            + "ALLOC_AMOUNT_FEN AS allocAmountFen, ALLOC_WATER_ML AS allocWaterMl, "
            + "REVERSED_FLAG AS reversedFlag, DATA_STATUS AS dataStatus";

    /**
     * 某次消费的分摊行，按<b>回补次序</b>（后扣的先还）返回。
     *
     * <p>回补取消费的逆序（LIFO）而不是同序，理由有两条，方向一致：</p>
     * <ol>
     *   <li>全额回补时，逆序是贪心分摊的<b>精确逆运算</b>，批次剩余逐行回到扣减前的值；</li>
     *   <li>部分回补时，先还的是最后被扣的批次。消费按「最早到期优先」取，最后被扣的
     *       恰是最晚到期或永久的那批——历史聚合批次（有效期取卡聚合值，即最晚）通常就在这一端。
     *       把部分返还先还给它，不可退权益仍留在不可退桶里；反过来先还可退批次，
     *       会把「本来退不了款的消费」置换成「可以套现的剩余」。</li>
     * </ol>
     *
     * <p>不过滤 {@code REVERSED_FLAG}，但调用方必须自己跳过已冲正行：
     * {@link #reverseByBatch}（退款成功时调用）只置标记、<b>不清零额度</b>——额度是账本事实，
     * 留着才能回答「这批权益当初被谁用掉了」。因此这里返回的已冲正行仍带着非零额度，
     * 回补时必须按标记跳过，否则会把已经退给用户的权益又补回卡上。
     * 过滤在 SQL 里做则会让「这次消费一共分摊过哪些批次」少一半证据。</p>
     *
     * <p>同样不过滤 {@code DATA_STATUS}：分摊是账本事实，被逻辑删除的分摊同样证明权益被消费过；
     * 只看未删除的行，等于给「删掉分摊再申请全额退款」留后门。</p>
     */
    @Select("SELECT " + COLUMNS + " FROM ws_entitlement_allocation "
            + "WHERE BIZ_KEY = #{bizKey} ORDER BY ALLOC_SEQ DESC, ID DESC")
    List<WsEntitlementAllocation> selectByBizKeyForRestore(@Param("bizKey") String bizKey);

    /**
     * 分摊行部分冲减 CAS：把本次回补的额度从分摊记录里核销掉。
     *
     * <p>这条 CAS 是<b>返还的幂等锚点</b>：额度前态（{@code ALLOC_AMOUNT_FEN}/{@code ALLOC_WATER_ML}
     * 全等）进 WHERE，同一笔返还重放时前态已变，影响 0 行 → 调用方整体回滚，
     * 绝不会把同一笔分摊回补两次。少了额度前态，只靠 {@code REVERSED_FLAG = 0}
     * 就挡不住「部分回补两次」——两次都看到未冲正的行，各自都能减一笔。</p>
     *
     * <p>{@code REVERSED_FLAG} 只在两列都归零时才置 1：标记的语义是「这条分摊已被整条核销」。
     * 提前置 1 会让「这次消费还剩多少可回补」失去依据——下一次部分回补会把已经还过的额度再还一遍。</p>
     *
     * <p><b>SET 里的赋值次序不是排版问题</b>：MySQL 在<b>单条</b> UPDATE 内按从左到右求值，
     * 后面的表达式读到的是前面已经改过的新值。故 {@code REVERSED_FLAG} 必须写在两列冲减
     * <b>之前</b>并直接比较「本次回补量 == 当前分摊量」；写在之后就会拿已经减过的值再减一次，
     * 判定恒不成立，全额回补的分摊行永远停在未冲正——而它对退款折算的影响是
     * 「已被退款抵消的消费仍算作消费」。这条被 EntitlementLedgerDbTest 的两条用例钉住。</p>
     */
    @Update("UPDATE ws_entitlement_allocation "
            + "SET REVERSED_FLAG = CASE WHEN ALLOC_AMOUNT_FEN = #{amountFen} "
            + "AND ALLOC_WATER_ML = #{waterMl} THEN 1 ELSE 0 END, "
            + "ALLOC_AMOUNT_FEN = ALLOC_AMOUNT_FEN - #{amountFen}, "
            + "ALLOC_WATER_ML = ALLOC_WATER_ML - #{waterMl}, "
            + "UPDATE_BY = #{opUserId}, UPDATE_TIME = #{now} "
            + "WHERE ID = #{id} AND REVERSED_FLAG = 0 "
            + "AND ALLOC_AMOUNT_FEN = #{expectedAmountFen} AND ALLOC_WATER_ML = #{expectedWaterMl} "
            + "AND ALLOC_AMOUNT_FEN >= #{amountFen} AND ALLOC_WATER_ML >= #{waterMl}")
    int reduceAllocated(@Param("id") Long id,
                        @Param("expectedAmountFen") Long expectedAmountFen,
                        @Param("expectedWaterMl") Long expectedWaterMl,
                        @Param("amountFen") Long amountFen,
                        @Param("waterMl") Long waterMl,
                        @Param("opUserId") Long opUserId,
                        @Param("now") String now);

    /** 冲正某批次的全部分摊。退款成功后调用，标记这些消费已被退款抵消。 */
    @Update("UPDATE ws_entitlement_allocation SET REVERSED_FLAG = 1, UPDATE_BY = #{opUserId}, "
            + "UPDATE_TIME = #{now} WHERE BATCH_ID = #{batchId} AND REVERSED_FLAG = 0")
    int reverseByBatch(@Param("batchId") Long batchId,
                       @Param("opUserId") Long opUserId,
                       @Param("now") String now);
}
