package com.jbk.serve.mapper.mini;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.mini.po.WsWechatNotifyOutbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 微信订阅通知 outbox Mapper。认领/完成/重试/转人工四条 SQL 与 {@code WsMallLogisticsOutboxMapper} 逐字同判据，刻意不分叉。
 *
 * @author dakang
 * @since 2026-08-12
 */
@Mapper
public interface WsWechatNotifyOutboxMapper extends BaseMapper<WsWechatNotifyOutbox> {

    @Select("SELECT * FROM ws_wechat_notify_outbox WHERE BIZ_NOTIFY_KEY = #{key}")
    WsWechatNotifyOutbox selectByKeyIncludingDeleted(@Param("key") String key);

    /** 扫描可认领的 ID。判据与 {@link #claimNotify} 必须逐字一致：分叉会让被逻辑删除的通知仍能被单独认领并发出。 */
    @Select("""
            SELECT ID FROM ws_wechat_notify_outbox
             WHERE DATA_STATUS = 0
               AND (PROCESSING_STATUS = 1
                    OR (PROCESSING_STATUS = 4 AND (NEXT_RETRY_TIME IS NULL OR NEXT_RETRY_TIME <= #{now}))
                    OR (PROCESSING_STATUS = 2 AND (LEASE_UNTIL IS NULL OR LEASE_UNTIL < #{now})))
             ORDER BY ID
             LIMIT #{limit}
            """)
    List<Long> scanClaimableIds(@Param("now") String now, @Param("limit") int limit);

    /** 认领：待处理、到点的待重试，或租约已过期的处理中——第三支防进程崩溃后通知永远卡在处理中。 */
    @Update("""
            UPDATE ws_wechat_notify_outbox
               SET PROCESSING_STATUS = 2, CLAIM_TIME = #{now}, LEASE_UNTIL = #{leaseUntil},
                   UPDATE_TIME = #{now}
             WHERE ID = #{id}
               AND DATA_STATUS = 0
               AND (PROCESSING_STATUS = 1
                    OR (PROCESSING_STATUS = 4 AND (NEXT_RETRY_TIME IS NULL OR NEXT_RETRY_TIME <= #{now}))
                    OR (PROCESSING_STATUS = 2 AND (LEASE_UNTIL IS NULL OR LEASE_UNTIL < #{now})))
            """)
    int claimNotify(@Param("id") Long id, @Param("now") String now,
                    @Param("leaseUntil") String leaseUntil);

    /**
     * 标记已处理。
     *
     * @param skipReason 为空=真的发出去了；非空=「已处理但没发」（模板未配/用户无授权额度），两者必须可区分
     */
    @Update("""
            UPDATE ws_wechat_notify_outbox
               SET PROCESSING_STATUS = 3, LEASE_UNTIL = NULL, LAST_ERROR = NULL,
                   SKIP_REASON = #{skipReason}, UPDATE_TIME = #{now}
             WHERE ID = #{id} AND PROCESSING_STATUS = 2
            """)
    int markProcessed(@Param("id") Long id, @Param("skipReason") String skipReason,
                      @Param("now") String now);

    @Update("""
            UPDATE ws_wechat_notify_outbox
               SET PROCESSING_STATUS = 4, RETRY_COUNT = RETRY_COUNT + 1,
                   NEXT_RETRY_TIME = #{nextRetryTime}, LEASE_UNTIL = NULL,
                   LAST_ERROR = #{reason}, UPDATE_TIME = #{now}
             WHERE ID = #{id} AND PROCESSING_STATUS = 2
            """)
    int markRetry(@Param("id") Long id, @Param("nextRetryTime") String nextRetryTime,
                  @Param("reason") String reason, @Param("now") String now);

    @Update("""
            UPDATE ws_wechat_notify_outbox
               SET PROCESSING_STATUS = 5, LEASE_UNTIL = NULL, LAST_ERROR = #{reason},
                   UPDATE_TIME = #{now}
             WHERE ID = #{id} AND PROCESSING_STATUS = 2
            """)
    int markNeedManual(@Param("id") Long id, @Param("reason") String reason,
                       @Param("now") String now);
}
