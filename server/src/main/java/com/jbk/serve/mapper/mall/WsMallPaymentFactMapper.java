package com.jbk.serve.mapper.mall;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.mall.po.WsMallPaymentFact;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 商城支付事实 Mapper（E2E-09 S2）。
 *
 * <p>并发安全不靠扫描条件，靠 {@link #claimFact} 的 CAS 认领：多个 Worker 实例同时
 * 捞到同一条事实时，只有一条 UPDATE 能命中，其余拿 0 行直接放弃。租约到期的
 * "处理中"可被重新认领——否则进程崩溃会让事实永久卡在处理中。</p>
 *
 * @author dakang
 * @since 2026-08-08
 */
@Mapper
public interface WsMallPaymentFactMapper extends BaseMapper<WsMallPaymentFact> {

    /** 按幂等三元组原样读（绕过逻辑删除过滤）：重复事实复用同一行的入口。 */
    @Select("""
            SELECT * FROM ws_mall_payment_fact
             WHERE PAY_SOURCE = #{paySource} AND FACT_CHANNEL = #{factChannel}
               AND PROVIDER_EVENT_KEY = #{providerEventKey}""")
    WsMallPaymentFact selectByKeyIncludingDeleted(@Param("paySource") Integer paySource,
                                                  @Param("factChannel") Integer factChannel,
                                                  @Param("providerEventKey") String providerEventKey);

    /**
     * CAS 认领：待处理(1)/待重试(4) 可认领；处理中(2) 仅在租约过期后可被重新认领。
     * 返回 1 表示认领成功，0 表示已被别人认领或已终态。
     */
    @Update("""
            UPDATE ws_mall_payment_fact
               SET PROCESSING_STATUS = 2, CLAIM_TIME = #{now}, LEASE_UNTIL = #{leaseUntil},
                   UPDATE_BY = 0, UPDATE_TIME = #{now}
             WHERE ID = #{id} AND DATA_STATUS = 0
               AND (PROCESSING_STATUS IN (1, 4)
                    OR (PROCESSING_STATUS = 2 AND (LEASE_UNTIL IS NULL OR LEASE_UNTIL < #{now})))""")
    int claimFact(@Param("id") Long id, @Param("now") String now,
                  @Param("leaseUntil") String leaseUntil);

    /** 认领后置终态：已处理(3)。 */
    @Update("""
            UPDATE ws_mall_payment_fact
               SET PROCESSING_STATUS = 3, PROCESSED_TIME = #{now}, LEASE_UNTIL = NULL,
                   LAST_ERROR = NULL, UPDATE_BY = 0, UPDATE_TIME = #{now}
             WHERE ID = #{id} AND DATA_STATUS = 0 AND PROCESSING_STATUS = 2""")
    int markProcessed(@Param("id") Long id, @Param("now") String now);

    /** 认领后置待重试(4)：记结构化原因与下次可捞时间，重试次数+1。 */
    @Update("""
            UPDATE ws_mall_payment_fact
               SET PROCESSING_STATUS = 4, RETRY_COUNT = RETRY_COUNT + 1,
                   NEXT_RETRY_TIME = #{nextRetryTime}, LEASE_UNTIL = NULL,
                   LAST_ERROR = #{lastError}, UPDATE_BY = 0, UPDATE_TIME = #{now}
             WHERE ID = #{id} AND DATA_STATUS = 0 AND PROCESSING_STATUS = 2""")
    int markRetry(@Param("id") Long id, @Param("nextRetryTime") String nextRetryTime,
                  @Param("lastError") String lastError, @Param("now") String now);

    /**
     * 认领后置需对账(5)：事实与订单/支付单对不上时的终点。
     * 这里不再自动重试——金额不符或订单已关闭却收到成功事实，重试多少次都不会自愈。
     */
    @Update("""
            UPDATE ws_mall_payment_fact
               SET PROCESSING_STATUS = 5, LEASE_UNTIL = NULL, PROCESSED_TIME = #{now},
                   LAST_ERROR = #{lastError}, UPDATE_BY = 0, UPDATE_TIME = #{now}
             WHERE ID = #{id} AND DATA_STATUS = 0 AND PROCESSING_STATUS = 2""")
    int markNeedReconcile(@Param("id") Long id, @Param("lastError") String lastError,
                          @Param("now") String now);

    /**
     * 落库时即判定不可推进（金额/币种/共键/来源不符）：从待处理(1) 直接转需对账(5)。
     *
     * <p>与 {@link #markNeedReconcile} 分开是因为那条要求先被 claim 成 2——而这里的事实
     * 从未进入过处理流程，它在收下的那一刻就已经确定推不动了。</p>
     */
    @Update("""
            UPDATE ws_mall_payment_fact
               SET PROCESSING_STATUS = 5, PROCESSED_TIME = #{now},
                   LAST_ERROR = #{lastError}, UPDATE_BY = 0, UPDATE_TIME = #{now}
             WHERE ID = #{id} AND DATA_STATUS = 0 AND PROCESSING_STATUS = 1""")
    int markNeedReconcileFromPending(@Param("id") Long id, @Param("lastError") String lastError,
                                     @Param("now") String now);

    /**
     * Worker 批量捞取：待处理/待重试到点的，以及租约已过期的处理中。
     * 只是宽松预筛，真正的并发裁决在 claimFact。
     */
    @Select("""
            SELECT ID FROM ws_mall_payment_fact
             WHERE DATA_STATUS = 0
               AND (PROCESSING_STATUS = 1
                    OR (PROCESSING_STATUS = 4 AND (NEXT_RETRY_TIME IS NULL OR NEXT_RETRY_TIME <= #{now}))
                    OR (PROCESSING_STATUS = 2 AND LEASE_UNTIL IS NOT NULL AND LEASE_UNTIL < #{now}))
             ORDER BY ID LIMIT #{batch}""")
    List<Long> scanClaimableIds(@Param("now") String now, @Param("batch") int batch);
}
