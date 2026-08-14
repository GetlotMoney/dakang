package com.jbk.serve.mapper.mall;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.mall.po.WsMallLogisticsOutbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 商城物流出站动作 Mapper（E2E-09 L1）。
 *
 * @author dakang
 * @since 2026-08-11
 */
@Mapper
public interface WsMallLogisticsOutboxMapper extends BaseMapper<WsMallLogisticsOutbox> {

    @Select("SELECT * FROM ws_mall_logistics_outbox WHERE BIZ_ACTION_KEY = #{key}")
    WsMallLogisticsOutbox selectByKeyIncludingDeleted(@Param("key") String key);

    /**
     * 认领：待处理、到点的待重试，或租约已过期的处理中。判据与 {@link #scanClaimableIds} 必须逐字一致：
     * 分叉会让被逻辑删除的动作仍能经 processOne(id) 单独认领并真的发出去。
     */
    @Update("""
            UPDATE ws_mall_logistics_outbox
               SET PROCESSING_STATUS = 2, CLAIM_TIME = #{now}, LEASE_UNTIL = #{leaseUntil},
                   UPDATE_TIME = #{now}
             WHERE ID = #{id}
               AND DATA_STATUS = 0
               AND (PROCESSING_STATUS = 1
                    OR (PROCESSING_STATUS = 4 AND (NEXT_RETRY_TIME IS NULL OR NEXT_RETRY_TIME <= #{now}))
                    OR (PROCESSING_STATUS = 2 AND (LEASE_UNTIL IS NULL OR LEASE_UNTIL < #{now})))
            """)
    int claimAction(@Param("id") Long id, @Param("now") String now,
                    @Param("leaseUntil") String leaseUntil);

    @Update("""
            UPDATE ws_mall_logistics_outbox
               SET PROCESSING_STATUS = 3, LEASE_UNTIL = NULL, LAST_ERROR = NULL, UPDATE_TIME = #{now}
             WHERE ID = #{id} AND PROCESSING_STATUS = 2
            """)
    int markProcessed(@Param("id") Long id, @Param("now") String now);

    @Update("""
            UPDATE ws_mall_logistics_outbox
               SET PROCESSING_STATUS = 4, RETRY_COUNT = RETRY_COUNT + 1,
                   NEXT_RETRY_TIME = #{nextRetryTime}, LEASE_UNTIL = NULL,
                   LAST_ERROR = #{reason}, UPDATE_TIME = #{now}
             WHERE ID = #{id} AND PROCESSING_STATUS = 2
            """)
    int markRetry(@Param("id") Long id, @Param("nextRetryTime") String nextRetryTime,
                  @Param("reason") String reason, @Param("now") String now);

    @Update("""
            UPDATE ws_mall_logistics_outbox
               SET PROCESSING_STATUS = 5, LEASE_UNTIL = NULL, LAST_ERROR = #{reason},
                   UPDATE_TIME = #{now}
             WHERE ID = #{id} AND PROCESSING_STATUS = 2
            """)
    int markNeedManual(@Param("id") Long id, @Param("reason") String reason,
                       @Param("now") String now);

    @Select("""
            SELECT ID FROM ws_mall_logistics_outbox
             WHERE DATA_STATUS = 0
               AND (PROCESSING_STATUS = 1
                    OR (PROCESSING_STATUS = 4 AND (NEXT_RETRY_TIME IS NULL OR NEXT_RETRY_TIME <= #{now}))
                    OR (PROCESSING_STATUS = 2 AND (LEASE_UNTIL IS NULL OR LEASE_UNTIL < #{now})))
             ORDER BY ID LIMIT #{limit}
            """)
    List<Long> scanClaimableIds(@Param("now") String now, @Param("limit") int limit);
}
