package com.jbk.serve.mapper.mall;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.mall.po.WsMallRefundFact;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 商城退款事实 Mapper（E2E-09 S4）：与支付事实同一收件箱结构。
 *
 * @author dakang
 * @since 2026-08-10
 */
@Mapper
public interface WsMallRefundFactMapper extends BaseMapper<WsMallRefundFact> {

    @Select("""
            SELECT * FROM ws_mall_refund_fact
             WHERE REFUND_SOURCE = #{source} AND FACT_CHANNEL = #{channel}
               AND PROVIDER_EVENT_KEY = #{key}""")
    WsMallRefundFact selectByKeyIncludingDeleted(@Param("source") Integer source,
                                                 @Param("channel") Integer channel,
                                                 @Param("key") String key);

    /** 认领：待处理(1)/待重试(4且到点)/处理中但租约已过期，三者之一才可被领走。 */
    @Update("""
            UPDATE ws_mall_refund_fact
               SET PROCESSING_STATUS = 2, CLAIM_TIME = #{now}, LEASE_UNTIL = #{leaseUntil},
                   UPDATE_TIME = #{now}
             WHERE ID = #{id} AND DATA_STATUS = 0
               AND (PROCESSING_STATUS = 1
                    OR (PROCESSING_STATUS = 4 AND NEXT_RETRY_TIME <= #{now})
                    OR (PROCESSING_STATUS = 2 AND LEASE_UNTIL <= #{now}))""")
    int claimFact(@Param("id") Long id, @Param("now") String now,
                  @Param("leaseUntil") String leaseUntil);

    @Update("""
            UPDATE ws_mall_refund_fact
               SET PROCESSING_STATUS = 3, PROCESSED_TIME = #{now}, LAST_ERROR = NULL,
                   UPDATE_TIME = #{now}
             WHERE ID = #{id} AND PROCESSING_STATUS = 2""")
    int markProcessed(@Param("id") Long id, @Param("now") String now);

    @Update("""
            UPDATE ws_mall_refund_fact
               SET PROCESSING_STATUS = 4, RETRY_COUNT = RETRY_COUNT + 1,
                   NEXT_RETRY_TIME = #{nextRetryTime}, LAST_ERROR = #{error}, UPDATE_TIME = #{now}
             WHERE ID = #{id} AND PROCESSING_STATUS = 2""")
    int markRetry(@Param("id") Long id, @Param("nextRetryTime") String nextRetryTime,
                  @Param("error") String error, @Param("now") String now);

    @Update("""
            UPDATE ws_mall_refund_fact
               SET PROCESSING_STATUS = 5, PROCESSED_TIME = #{now}, LAST_ERROR = #{error},
                   UPDATE_TIME = #{now}
             WHERE ID = #{id} AND PROCESSING_STATUS = 2""")
    int markNeedReconcile(@Param("id") Long id, @Param("error") String error,
                          @Param("now") String now);

    @Update("""
            UPDATE ws_mall_refund_fact
               SET PROCESSING_STATUS = 5, PROCESSED_TIME = #{now}, LAST_ERROR = #{error},
                   UPDATE_TIME = #{now}
             WHERE ID = #{id} AND PROCESSING_STATUS = 1""")
    int markNeedReconcileFromPending(@Param("id") Long id, @Param("error") String error,
                                     @Param("now") String now);

    /** Worker 扫描面：待处理、到点待重试、租约过期三类。 */
    @Select("""
            SELECT ID FROM ws_mall_refund_fact
             WHERE DATA_STATUS = 0
               AND (PROCESSING_STATUS = 1
                    OR (PROCESSING_STATUS = 4 AND NEXT_RETRY_TIME <= #{now})
                    OR (PROCESSING_STATUS = 2 AND LEASE_UNTIL <= #{now}))
             ORDER BY ID ASC LIMIT #{limit}""")
    List<Long> scanClaimableIds(@Param("now") String now, @Param("limit") int limit);
}
