package com.jbk.serve.mapper.mall;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.mall.po.WsWechatShippingOutbox;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 微信发货同步 outbox（WX-ECO S4）。认领/终态动作与通知 outbox 同一套语义（字典 1408）。
 */
public interface WsWechatShippingOutboxMapper extends BaseMapper<WsWechatShippingOutbox> {

    @Select("SELECT * FROM ws_wechat_shipping_outbox WHERE BIZ_SYNC_KEY = #{key}")
    WsWechatShippingOutbox selectByKeyIncludingDeleted(@Param("key") String key);

    /** 扫描可认领的 ID。判据与 {@link #claimSync} 必须逐字一致。 */
    @Select("""
            SELECT ID FROM ws_wechat_shipping_outbox
             WHERE DATA_STATUS = 0
               AND (PROCESSING_STATUS = 1
                    OR (PROCESSING_STATUS = 4 AND (NEXT_RETRY_TIME IS NULL OR NEXT_RETRY_TIME <= #{now}))
                    OR (PROCESSING_STATUS = 2 AND (LEASE_UNTIL IS NULL OR LEASE_UNTIL < #{now})))
             ORDER BY ID
             LIMIT #{limit}
            """)
    List<Long> scanClaimableIds(@Param("now") String now, @Param("limit") int limit);

    /** 认领：待处理、到点的待重试，或租约过期的处理中（防进程崩溃后永久卡在处理中）。 */
    @Update("""
            UPDATE ws_wechat_shipping_outbox
               SET PROCESSING_STATUS = 2, CLAIM_TIME = #{now}, LEASE_UNTIL = #{leaseUntil},
                   UPDATE_TIME = #{now}
             WHERE ID = #{id}
               AND DATA_STATUS = 0
               AND (PROCESSING_STATUS = 1
                    OR (PROCESSING_STATUS = 4 AND (NEXT_RETRY_TIME IS NULL OR NEXT_RETRY_TIME <= #{now}))
                    OR (PROCESSING_STATUS = 2 AND (LEASE_UNTIL IS NULL OR LEASE_UNTIL < #{now})))
            """)
    int claimSync(@Param("id") Long id, @Param("now") String now,
                  @Param("leaseUntil") String leaseUntil);

    /** 标记已处理。skipReason 为空=真同步了；非空=登记完成但没外呼，两者必须可区分。 */
    @Update("""
            UPDATE ws_wechat_shipping_outbox
               SET PROCESSING_STATUS = 3, LEASE_UNTIL = NULL, LAST_ERROR = NULL,
                   SKIP_REASON = #{skipReason}, UPDATE_TIME = #{now}
             WHERE ID = #{id} AND PROCESSING_STATUS = 2
            """)
    int markProcessed(@Param("id") Long id, @Param("skipReason") String skipReason,
                      @Param("now") String now);

    @Update("""
            UPDATE ws_wechat_shipping_outbox
               SET PROCESSING_STATUS = 4, RETRY_COUNT = RETRY_COUNT + 1,
                   NEXT_RETRY_TIME = #{nextRetryTime}, LEASE_UNTIL = NULL,
                   LAST_ERROR = #{reason}, UPDATE_TIME = #{now}
             WHERE ID = #{id} AND PROCESSING_STATUS = 2
            """)
    int markRetry(@Param("id") Long id, @Param("nextRetryTime") String nextRetryTime,
                  @Param("reason") String reason, @Param("now") String now);

    @Update("""
            UPDATE ws_wechat_shipping_outbox
               SET PROCESSING_STATUS = 5, LEASE_UNTIL = NULL, LAST_ERROR = #{reason},
                   UPDATE_TIME = #{now}
             WHERE ID = #{id} AND PROCESSING_STATUS = 2
            """)
    int markNeedManual(@Param("id") Long id, @Param("reason") String reason,
                       @Param("now") String now);
}
