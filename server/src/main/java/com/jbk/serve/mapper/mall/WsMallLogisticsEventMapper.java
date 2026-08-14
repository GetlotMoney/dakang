package com.jbk.serve.mapper.mall;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.mall.po.WsMallLogisticsEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 商城物流事件收件箱 Mapper（E2E-09 L1）。
 *
 * <p>认领用 CAS + 租约，与支付/退款事实同形：崩溃后租约到期即可被其他处理者接管，
 * 而不是留下一条永远「处理中」的事实等人去改库。</p>
 *
 * @author dakang
 * @since 2026-08-11
 */
@Mapper
public interface WsMallLogisticsEventMapper extends BaseMapper<WsMallLogisticsEvent> {

    /** 按事件唯一键原样读：同键重放复用原行，改参一律拒绝。 */
    @Select("""
            SELECT * FROM ws_mall_logistics_event
             WHERE PROVIDER_CODE = #{providerCode} AND FACT_CHANNEL = #{factChannel}
               AND PROVIDER_EVENT_KEY = #{providerEventKey}
            """)
    WsMallLogisticsEvent selectByKeyIncludingDeleted(@Param("providerCode") String providerCode,
                                                     @Param("factChannel") Integer factChannel,
                                                     @Param("providerEventKey") String providerEventKey);

    /** 某包裹已处理的事件时间线（按事件时间升序，供三端展示）。 */
    @Select("""
            SELECT * FROM ws_mall_logistics_event
             WHERE SHIPMENT_ID = #{shipmentId} AND DATA_STATUS = 0 AND PROCESSING_STATUS = 3
             ORDER BY EVENT_TIME, ID
            """)
    List<WsMallLogisticsEvent> selectTimeline(@Param("shipmentId") Long shipmentId);

    /**
     * 认领：只有待处理或租约已过期的处理中才可领。
     *
     * <p>影响 0 行有两种解释（已终态 / 被别人持有），调用方必须重读区分——
     * 一律当成「别人在做」会让转人工的事实永远没人再看。</p>
     */
    @Update("""
            UPDATE ws_mall_logistics_event
               SET PROCESSING_STATUS = 2, CLAIM_TIME = #{now}, LEASE_UNTIL = #{leaseUntil},
                   UPDATE_TIME = #{now}
             WHERE ID = #{id}
               AND DATA_STATUS = 0
               AND (PROCESSING_STATUS IN (1, 4)
                    OR (PROCESSING_STATUS = 2 AND (LEASE_UNTIL IS NULL OR LEASE_UNTIL < #{now})))
            """)
    int claimEvent(@Param("id") Long id, @Param("now") String now,
                   @Param("leaseUntil") String leaseUntil);

    @Update("""
            UPDATE ws_mall_logistics_event
               SET PROCESSING_STATUS = 3, PROCESSED_TIME = #{now}, LEASE_UNTIL = NULL,
                   LAST_ERROR = NULL, UPDATE_TIME = #{now}
             WHERE ID = #{id} AND PROCESSING_STATUS = 2
            """)
    int markProcessed(@Param("id") Long id, @Param("now") String now);

    @Update("""
            UPDATE ws_mall_logistics_event
               SET PROCESSING_STATUS = 5, PROCESSED_TIME = #{now}, LEASE_UNTIL = NULL,
                   LAST_ERROR = #{reason}, UPDATE_TIME = #{now}
             WHERE ID = #{id} AND PROCESSING_STATUS = 2
            """)
    int markNeedManual(@Param("id") Long id, @Param("reason") String reason,
                       @Param("now") String now);

    @Update("""
            UPDATE ws_mall_logistics_event
               SET PROCESSING_STATUS = 4, RETRY_COUNT = RETRY_COUNT + 1, LEASE_UNTIL = NULL,
                   LAST_ERROR = #{reason}, UPDATE_TIME = #{now}
             WHERE ID = #{id} AND PROCESSING_STATUS = 2
            """)
    int markRetry(@Param("id") Long id, @Param("reason") String reason, @Param("now") String now);

    /** 可重投的事实：待处理、待重试，或租约已过期的处理中（崩溃残留）。 */
    @Select("""
            SELECT ID FROM ws_mall_logistics_event
             WHERE DATA_STATUS = 0
               AND (PROCESSING_STATUS IN (1, 4)
                    OR (PROCESSING_STATUS = 2 AND (LEASE_UNTIL IS NULL OR LEASE_UNTIL < #{now})))
             ORDER BY ID LIMIT #{limit}
            """)
    List<Long> scanClaimableIds(@Param("now") String now, @Param("limit") int limit);
}
