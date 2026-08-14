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
 * 刻意没有「按批次求已消费合计」查询：D-5 冻结口径「已消费 = 发放量 − 批次剩余」，唯一输入是批次行的剩余列，两个来源并存会给出两个答案。
 */
@Mapper
public interface WsEntitlementAllocationMapper extends BaseMapper<WsEntitlementAllocation> {

    String COLUMNS = "ID AS id, BATCH_ID AS batchId, CARD_ID AS cardId, FLOW_ID AS flowId, "
            + "ORDER_ID AS orderId, BIZ_KEY AS bizKey, ALLOC_SEQ AS allocSeq, "
            + "ALLOC_AMOUNT_FEN AS allocAmountFen, ALLOC_WATER_ML AS allocWaterMl, "
            + "REVERSED_FLAG AS reversedFlag, DATA_STATUS AS dataStatus";

    /**
     * 某次消费的分摊行，按回补次序（LIFO）返回：全额回补是贪心分摊的精确逆运算；部分回补先还最后被扣（最晚到期/不可退）的批次，
     * 反序会把不可退消费置换成可套现剩余。
     * 不过滤 REVERSED_FLAG 与 DATA_STATUS（账本事实必须全量可见，防「删分摊再退款」后门），但调用方必须按标记跳过已冲正行——
     * {@link #reverseByBatch} 只置标记不清零额度，直接回补会把已退给用户的权益又补回卡上。
     */
    @Select("SELECT " + COLUMNS + " FROM ws_entitlement_allocation "
            + "WHERE BIZ_KEY = #{bizKey} ORDER BY ALLOC_SEQ DESC, ID DESC")
    List<WsEntitlementAllocation> selectByBizKeyForRestore(@Param("bizKey") String bizKey);

    /**
     * 分摊行部分冲减 CAS：返还的幂等锚点。额度前态全等进 WHERE，重放时前态已变 0 行整体回滚；只靠 REVERSED_FLAG=0 挡不住部分回补两次。
     * REVERSED_FLAG 只在两列都归零时置 1（语义=整条核销），提前置 1 会让下次部分回补把已还额度再还一遍。
     * SET 赋值次序是要害：MySQL 单条 UPDATE 从左到右求值，REVERSED_FLAG 必须写在两列冲减之前比较「回补量==当前分摊量」，
     * 写在之后判定恒不成立（EntitlementLedgerDbTest 两条用例钉住）。
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
