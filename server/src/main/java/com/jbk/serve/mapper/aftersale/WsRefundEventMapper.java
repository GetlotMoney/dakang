package com.jbk.serve.mapper.aftersale;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.aftersale.po.WsRefundEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 退款事实收件箱 Mapper（E2E-04 包B，R0-8）。
 *
 * <p>claim / 租约 / 扫描三条 SQL 与 {@code RechargeCreditMapper} 的支付事实版本<b>逐条同构</b>。
 * 这是刻意的：两套收件箱面对同一类故障，语义一旦分叉，恢复行为就得各记一套，
 * 而记错任何一套都是资金事故。</p>
 *
 * <p>SQL 内不含任何 SQL 注释（Druid WallFilter comment-not-allow），说明写在 Javadoc。</p>
 *
 * @author dakang
 * @since 2026-07-29
 */
@Mapper
public interface WsRefundEventMapper extends BaseMapper<WsRefundEvent> {

    String COLUMNS = "ID AS id, REFUND_SOURCE AS refundSource, FACT_CHANNEL AS factChannel, "
            + "PROVIDER_EVENT_KEY AS providerEventKey, REFUND_ID AS refundId, AFTER_SALE_ID AS afterSaleId, "
            + "ORDER_ID AS orderId, REFUND_NO AS refundNo, ORDER_NO AS orderNo, REFUND_STATE AS refundState, "
            + "PROVIDER_REFUND_ID AS providerRefundId, REFUND_AMOUNT AS refundAmount, CURRENCY AS currency, "
            + "REFUND_SUCCESS_TIME AS refundSuccessTime, RAW_BODY_SHA256 AS rawBodySha256, "
            + "VERIFY_METHOD AS verifyMethod, PROCESSING_STATUS AS processingStatus, RETRY_COUNT AS retryCount, "
            + "NEXT_RETRY_TIME AS nextRetryTime, CLAIM_TIME AS claimTime, LEASE_UNTIL AS leaseUntil, "
            + "LAST_ERROR AS lastError, RECEIVED_TIME AS receivedTime, PROCESSED_TIME AS processedTime, "
            + "DATA_STATUS AS dataStatus";

    /**
     * 按事实幂等键查既有事实。
     *
     * <p>刻意<b>不</b>选 RAW_BODY：原始报文只用于摘要比对与人工排查，
     * 把它带进每次读取会让它顺着 VO 一路漏到接口上（禁止事项：不向前端返回原始退款报文）。</p>
     */
    @Select("SELECT " + COLUMNS + " FROM ws_refund_event WHERE REFUND_SOURCE = #{refundSource} "
            + "AND FACT_CHANNEL = #{factChannel} AND PROVIDER_EVENT_KEY = #{providerEventKey}")
    WsRefundEvent selectByProviderKey(@Param("refundSource") Integer refundSource,
                                      @Param("factChannel") Integer factChannel,
                                      @Param("providerEventKey") String providerEventKey);

    @Select("SELECT " + COLUMNS + " FROM ws_refund_event WHERE ID = #{id}")
    WsRefundEvent selectDetailById(@Param("id") Long id);

    /**
     * 同键重复到达时的正文完整性比对。
     *
     * <p>返回 0 表示同一事实键下的正文摘要与本次重算结果不一致 —— 那意味着同一个事实号
     * 承载了两份不同内容，调用方必须拒绝并转人工，而不是复用旧事实继续推进。</p>
     */
    @Select("SELECT COUNT(*) FROM ws_refund_event WHERE ID = #{id} AND RAW_BODY_SHA256 = #{sha256}")
    int countMatchingDigest(@Param("id") Long id, @Param("sha256") String sha256);

    /**
     * 认领事实：1待处理 / 4到期待重试 / 2租约已过期 → 2处理中。
     *
     * <p>三类候选与 {@link #selectDueEventIds} 完全同构。CAS 保证并发下只有一个处理者拿到，
     * 影响 0 行说明已被别人认领或已处理完，调用方必须放弃而不是继续推进退款。</p>
     *
     * <p>「2处理中 且租约过期」这一支是崩溃恢复的全部：处理者进程在 claim 之后、
     * 落终态之前挂掉，事实会永远停在 2处理中；没有这一支，那笔退款就再也无人推进
     * （包A 遗留的孤儿 PROCESSING 问题，在退款侧用租约一次性解决）。</p>
     */
    @Update("UPDATE ws_refund_event SET PROCESSING_STATUS = 2, RETRY_COUNT = RETRY_COUNT + 1, "
            + "CLAIM_TIME = #{now}, LEASE_UNTIL = #{leaseUntil}, NEXT_RETRY_TIME = NULL, LAST_ERROR = NULL, "
            + "UPDATE_BY = #{opUserId}, UPDATE_TIME = #{now} "
            + "WHERE ID = #{id} AND DATA_STATUS = 0 AND (PROCESSING_STATUS = 1 "
            + "OR (PROCESSING_STATUS = 4 AND (NEXT_RETRY_TIME IS NULL OR NEXT_RETRY_TIME <= #{now})) "
            + "OR (PROCESSING_STATUS = 2 AND LEASE_UNTIL IS NOT NULL AND LEASE_UNTIL <= #{now}))")
    int claim(@Param("id") Long id,
              @Param("now") String now,
              @Param("leaseUntil") String leaseUntil,
              @Param("opUserId") Long opUserId);

    /** 事实处理完毕：2处理中 → 3已处理。 */
    @Update("UPDATE ws_refund_event SET PROCESSING_STATUS = 3, PROCESSED_TIME = #{now}, "
            + "LEASE_UNTIL = NULL, NEXT_RETRY_TIME = NULL, LAST_ERROR = NULL, "
            + "UPDATE_BY = #{opUserId}, UPDATE_TIME = #{now} "
            + "WHERE ID = #{id} AND PROCESSING_STATUS = 2 AND DATA_STATUS = 0")
    int markProcessed(@Param("id") Long id, @Param("opUserId") Long opUserId, @Param("now") String now);

    /**
     * 事实落非成功态：4待重试 或 5需对账。
     *
     * <p>只允许从 2处理中 出发：不加这条前态，一个迟到的失败判定可以把
     * 已经处理完（3）的事实拖回待重试，于是同一条事实被重复消费。</p>
     */
    @Update("UPDATE ws_refund_event SET PROCESSING_STATUS = #{toStatus}, NEXT_RETRY_TIME = #{nextRetryTime}, "
            + "LEASE_UNTIL = NULL, LAST_ERROR = #{lastError}, "
            + "UPDATE_BY = #{opUserId}, UPDATE_TIME = #{now} "
            + "WHERE ID = #{id} AND PROCESSING_STATUS = 2 AND DATA_STATUS = 0")
    int park(@Param("id") Long id,
             @Param("toStatus") Integer toStatus,
             @Param("nextRetryTime") String nextRetryTime,
             @Param("lastError") String lastError,
             @Param("opUserId") Long opUserId,
             @Param("now") String now);

    /**
     * 扫描待推进的退款事实（Worker 专用，只出 ID 不出内容）。
     *
     * <p>这只是<b>宽松预筛</b>而非安全判定：真正的并发安全由 {@link #claim} 的 CAS 保证。
     * 三类候选与 claim 的 WHERE 同构；LIMIT 兜住单轮批量。</p>
     */
    @Select("SELECT ID FROM ws_refund_event WHERE DATA_STATUS = 0 "
            + "AND (PROCESSING_STATUS = 1 "
            + "OR (PROCESSING_STATUS = 4 AND (NEXT_RETRY_TIME IS NULL OR NEXT_RETRY_TIME <= #{now})) "
            + "OR (PROCESSING_STATUS = 2 AND LEASE_UNTIL IS NOT NULL AND LEASE_UNTIL <= #{now})) "
            + "ORDER BY ID LIMIT 50")
    List<Long> selectDueEventIds(@Param("now") String now);

    /**
     * 记录「同键不同正文」异常，<b>只写错因，不动处理状态</b>。
     *
     * <p>为什么不把既有事实 park 成需人工对账：那会让外部输入获得改变合法事实状态的能力——
     * 任何知道事实键的人只要发一份垃圾正文，就能把一笔正在推进的合法退款冻进人工队列，
     * 这是一条现成的拒绝服务路径。摘要不符说明<b>本次到达</b>不可信，
     * 该拒绝的是本次到达（调用方抛出），而不是此前已验证过的那条事实。</p>
     *
     * <p>但异常必须留痕：同一个事实号承载两份内容是明确的安全信号，
     * 运营与安全需要能检索到它，故写进 LAST_ERROR。</p>
     */
    @Update("UPDATE ws_refund_event SET LAST_ERROR = #{lastError}, UPDATE_BY = #{opUserId}, "
            + "UPDATE_TIME = #{now} WHERE ID = #{id} AND DATA_STATUS = 0")
    int flagAnomaly(@Param("id") Long id,
                    @Param("lastError") String lastError,
                    @Param("opUserId") Long opUserId,
                    @Param("now") String now);

    /** 关联回填：事实与本地退款单对上后写回三个共键，便于人工对账时双向定位。 */
    @Update("UPDATE ws_refund_event SET REFUND_ID = #{refundId}, AFTER_SALE_ID = #{afterSaleId}, "
            + "ORDER_ID = #{orderId}, UPDATE_BY = #{opUserId}, UPDATE_TIME = #{now} "
            + "WHERE ID = #{id} AND DATA_STATUS = 0")
    int linkKeys(@Param("id") Long id,
                 @Param("refundId") Long refundId,
                 @Param("afterSaleId") Long afterSaleId,
                 @Param("orderId") Long orderId,
                 @Param("opUserId") Long opUserId,
                 @Param("now") String now);
}
