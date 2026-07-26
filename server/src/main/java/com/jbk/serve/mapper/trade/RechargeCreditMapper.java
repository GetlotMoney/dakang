package com.jbk.serve.mapper.trade;

import com.jbk.tool.data.trade.po.WsPaymentEvent;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsPayment;
import com.jbk.tool.data.trade.po.WsWalletFlow;
import com.jbk.tool.data.user.po.WsCard;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 充值入账与支付事实推进 Mapper（L2-T）。手写 SQL，全部状态推进用 <b>CAS（WHERE 带前置状态）</b>。
 *
 * <p>资金铁律：余额/水量只能用原子 {@code UPDATE ... SET X = X + n} 推进，禁止读出→内存计算→写回；
 * 每次状态跃迁都必须校验影响行数，影响 0 行意味着「别人已经推进过」或「前置状态不符」，
 * 两者都不得当成成功继续往下走。</p>
 *
 * <p>入账幂等<b>不靠应用层查重</b>，靠 {@code ws_wallet_flow.BIZ_IDEMPOTENCY_KEY} 唯一键：
 * 同一事务内先加钱再插流水，插流水撞唯一键则整个事务回滚，加的钱一并撤销。
 * 这样即使两个处理者同时进来，也不可能双倍入账。</p>
 */
@Mapper
public interface RechargeCreditMapper {

    // ------------------------------------------------------------------
    // 支付事实（ws_payment_event）
    // ------------------------------------------------------------------

    /**
     * 落一条支付事实。{@code uk_payment_event_source_channel_key} 保证同一
     * (来源, 通道, 外部事件号) 只会存在一条——重复回调在数据库层就被挡掉。
     */
    @Insert("INSERT INTO ws_payment_event(DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, "
            + "ORDER_NO, ORDER_ID, PAYMENT_ID, PAY_SOURCE, FACT_CHANNEL, PROVIDER_EVENT_KEY, "
            + "TRADE_STATE, TRANSACTION_ID, PAY_AMOUNT, CURRENCY, PAY_SUCCESS_TIME, PROCESSING_STATUS, "
            + "RAW_BODY, RAW_BODY_SHA256, VERIFY_METHOD, RECEIVED_TIME) "
            + "VALUES(0, #{createBy}, #{now}, #{createBy}, #{now}, "
            + "#{orderNo}, #{orderId}, #{paymentId}, #{paySource}, #{factChannel}, #{providerEventKey}, "
            + "#{tradeState}, #{transactionId}, #{payAmount}, #{currency}, #{paySuccessTime}, 1, "
            + "#{rawBody}, #{rawBodySha256}, #{verifyMethod}, #{now})")
    int insertEvent(@Param("orderNo") String orderNo,
                    @Param("orderId") Long orderId,
                    @Param("paymentId") Long paymentId,
                    @Param("paySource") Integer paySource,
                    @Param("factChannel") Integer factChannel,
                    @Param("providerEventKey") String providerEventKey,
                    @Param("tradeState") String tradeState,
                    @Param("transactionId") String transactionId,
                    @Param("payAmount") Long payAmount,
                    @Param("currency") String currency,
                    @Param("paySuccessTime") String paySuccessTime,
                    @Param("createBy") Long createBy,
                    @Param("now") String now,
                    // 以下四列在权威结构里 NOT NULL 且刻意无默认值：事实的来源与完整性证据
                    // 必须由落库方显式给出，不允许"先建一条空壳事实、以后再补"。
                    @Param("rawBody") String rawBody,
                    @Param("rawBodySha256") String rawBodySha256,
                    @Param("verifyMethod") Integer verifyMethod);

    String EVENT_COLUMNS = "ID AS id, ORDER_NO AS orderNo, ORDER_ID AS orderId, PAYMENT_ID AS paymentId, "
            + "PAY_SOURCE AS paySource, FACT_CHANNEL AS factChannel, PROVIDER_EVENT_KEY AS providerEventKey, "
            + "TRADE_STATE AS tradeState, TRANSACTION_ID AS transactionId, PAY_AMOUNT AS payAmount, "
            + "CURRENCY AS currency, PAY_SUCCESS_TIME AS paySuccessTime, PROCESSING_STATUS AS processingStatus, "
            + "RETRY_COUNT AS retryCount, NEXT_RETRY_TIME AS nextRetryTime, CLAIM_TIME AS claimTime, "
            + "LEASE_UNTIL AS leaseUntil, RECOVERY_APPROVAL_GROUP_KEY AS recoveryApprovalGroupKey, "
            + "RECOVERY_APPROVED_BY AS recoveryApprovedBy, RECOVERY_APPROVED_TIME AS recoveryApprovedTime, "
            + "RECOVERY_APPROVAL_REASON AS recoveryApprovalReason, LAST_ERROR AS lastError, "
            + "PROCESSED_TIME AS processedTime, DATA_STATUS AS dataStatus";

    /** 按主键读事实（跨全部 DATA_STATUS——被逻辑删除的事实同样要看得见，钱不会因为记录被删就没收到）。 */
    @Select("SELECT " + EVENT_COLUMNS + " FROM ws_payment_event WHERE ID = #{eventId}")
    WsPaymentEvent selectEventById(@Param("eventId") Long eventId);

    /** 按外部事件号查已存在的事实（重复回调时用于返回既有结果，而不是再插一条）。 */
    @Select("SELECT ID AS id, ORDER_NO AS orderNo, ORDER_ID AS orderId, PAYMENT_ID AS paymentId, "
            + "PAY_SOURCE AS paySource, FACT_CHANNEL AS factChannel, PROVIDER_EVENT_KEY AS providerEventKey, "
            + "TRADE_STATE AS tradeState, TRANSACTION_ID AS transactionId, PAY_AMOUNT AS payAmount, "
            + "CURRENCY AS currency, PAY_SUCCESS_TIME AS paySuccessTime, PROCESSING_STATUS AS processingStatus, "
            + "DATA_STATUS AS dataStatus "
            + "FROM ws_payment_event WHERE PAY_SOURCE = #{paySource} AND FACT_CHANNEL = #{factChannel} "
            + "AND PROVIDER_EVENT_KEY = #{providerEventKey}")
    WsPaymentEvent selectEventByProviderKey(@Param("paySource") Integer paySource,
                                            @Param("factChannel") Integer factChannel,
                                            @Param("providerEventKey") String providerEventKey);

    /**
     * 认领事实：PROCESSING_STATUS 1待处理 → 2处理中。CAS 保证并发下只有一个处理者拿到，
     * 影响 0 行说明已被别人认领或已处理完，调用方必须放弃而不是继续入账。
     */
    @Update("UPDATE ws_payment_event SET PROCESSING_STATUS = 2, RETRY_COUNT = RETRY_COUNT + 1, "
            + "CLAIM_TIME = #{now}, LEASE_UNTIL = #{leaseUntil}, NEXT_RETRY_TIME = NULL, LAST_ERROR = NULL, "
            + "UPDATE_TIME = #{now} WHERE ID = #{eventId} AND TRADE_STATE = 'SUCCESS' AND DATA_STATUS = 0 "
            + "AND (PROCESSING_STATUS = 1 "
            + "OR (PROCESSING_STATUS = 4 AND (NEXT_RETRY_TIME IS NULL OR NEXT_RETRY_TIME <= #{now})) "
            + "OR (PROCESSING_STATUS = 2 AND (LEASE_UNTIL IS NULL OR LEASE_UNTIL <= #{now})))")
    int claimEvent(@Param("eventId") Long eventId,
                   @Param("now") String now,
                   @Param("leaseUntil") String leaseUntil);

    /**
     * 认领<b>非成功查单事实</b>（NOTPAY/CLOSED）：PROCESSING_STATUS 1待处理 → 2处理中。
     *
     * <p>刻意与 {@link #claimEvent} 分成两个方法而不是放宽后者的 {@code TRADE_STATE='SUCCESS'}：
     * 权益 Worker 用的正是那把 SUCCESS 锁（契约 §6.3「NOTPAY/CLOSED 永不进入权益 Worker」），
     * 一旦把它改成 IN (...)，一条 CLOSED 事实就可能被 Worker 认领并走到入账分支。</p>
     *
     * <p>这里不给租约续期/重试路径：查单事实不入账、没有可恢复失败，
     * 处理不掉就该停在 5待对账等人工，而不是自动重试出第二种结论。</p>
     */
    @Update("UPDATE ws_payment_event SET PROCESSING_STATUS = 2, RETRY_COUNT = RETRY_COUNT + 1, "
            + "CLAIM_TIME = #{now}, LEASE_UNTIL = #{leaseUntil}, NEXT_RETRY_TIME = NULL, LAST_ERROR = NULL, "
            + "UPDATE_TIME = #{now} WHERE ID = #{eventId} AND TRADE_STATE IN ('NOTPAY', 'CLOSED') "
            + "AND DATA_STATUS = 0 AND (PROCESSING_STATUS = 1 "
            + "OR (PROCESSING_STATUS = 2 AND (LEASE_UNTIL IS NULL OR LEASE_UNTIL <= #{now})))")
    int claimQueryEvent(@Param("eventId") Long eventId,
                        @Param("now") String now,
                        @Param("leaseUntil") String leaseUntil);

    /**
     * 同键重复到达时的正文完整性比对（契约 §5.3：同键不同正文或事实进入人工核查）。
     * 返回 0 表示同一事件键下的正文摘要与本次重算结果不一致，调用方必须拒绝而不是复用旧事实。
     */
    @Select("SELECT COUNT(*) FROM ws_payment_event WHERE ID = #{eventId} AND RAW_BODY_SHA256 = #{sha256}")
    int countEventMatchingDigest(@Param("eventId") Long eventId, @Param("sha256") String sha256);

    /**
     * 扫描待推进的支付事实（重试 Worker 专用，只出 ID 不出内容）。
     *
     * <p>这只是<b>宽松预筛</b>，不是安全判定：真正的并发安全仍由 claim 的 CAS 保证——
     * 扫到的事实可能在本 Worker 处理前被同步调用抢先，届时 claim 影响 0 行自然放弃。
     * 三类候选与 claim 的 WHERE 完全同构：1待处理、4到期待重试、2租约已过期（处理者崩溃遗留）。
     * LIMIT 兜住单轮批量，单条失败不阻断批次由 Worker 侧保证。</p>
     */
    @Select("SELECT ID FROM ws_payment_event WHERE DATA_STATUS = 0 "
            + "AND (PROCESSING_STATUS = 1 "
            + "OR (PROCESSING_STATUS = 4 AND (NEXT_RETRY_TIME IS NULL OR NEXT_RETRY_TIME <= #{now})) "
            + "OR (PROCESSING_STATUS = 2 AND LEASE_UNTIL IS NOT NULL AND LEASE_UNTIL <= #{now})) "
            + "ORDER BY ID LIMIT 50")
    List<Long> selectDueEventIds(@Param("now") String now);

    /** 事实处理完毕：2处理中 → 3已处理。 */
    @Update("UPDATE ws_payment_event SET PROCESSING_STATUS = 3, PROCESSED_TIME = #{now}, "
            + "LEASE_UNTIL = NULL, NEXT_RETRY_TIME = NULL, LAST_ERROR = NULL, UPDATE_TIME = #{now} "
            + "WHERE ID = #{eventId} AND PROCESSING_STATUS = #{expectedStatus} AND DATA_STATUS = 0")
    int markEventProcessed(@Param("eventId") Long eventId,
                           @Param("expectedStatus") Integer expectedStatus,
                           @Param("now") String now);

    /**
     * 事实转待重试：2处理中 → 4待重试。仅用于<b>可恢复</b>失败（当前只有卡冻结）。
     * 与 5待对账 必须分开：把一张只是临时冻结的卡判成需人工对账，会让本可自动恢复的订单堆进人工队列。
     */
    @Update("UPDATE ws_payment_event SET PROCESSING_STATUS = 4, NEXT_RETRY_TIME = #{nextRetryTime}, "
            + "LEASE_UNTIL = NULL, LAST_ERROR = #{reason}, UPDATE_TIME = #{now} "
            + "WHERE ID = #{eventId} AND PROCESSING_STATUS = #{expectedStatus} AND DATA_STATUS = 0")
    int markEventRetryWait(@Param("eventId") Long eventId,
                           @Param("expectedStatus") Integer expectedStatus,
                           @Param("nextRetryTime") String nextRetryTime,
                           @Param("reason") String reason,
                           @Param("now") String now);

    /** 事实转人工对账：2处理中 → 5待对账（钱已收但入账不安全时走这条，绝不静默丢弃）。 */
    @Update("UPDATE ws_payment_event SET PROCESSING_STATUS = 5, LEASE_UNTIL = NULL, "
            + "LAST_ERROR = #{reason}, UPDATE_TIME = #{now} "
            + "WHERE ID = #{eventId} AND PROCESSING_STATUS = #{expectedStatus} AND DATA_STATUS = 0")
    int markEventReconciliation(@Param("eventId") Long eventId,
                                @Param("expectedStatus") Integer expectedStatus,
                                @Param("reason") String reason,
                                @Param("now") String now);

    // ------------------------------------------------------------------
    // 事务 B 固定锁序：payment -> order -> card -> events -> flows
    // ------------------------------------------------------------------

    String PAYMENT_COLUMNS = "ID AS id, ORDER_ID AS orderId, ORDER_NO AS orderNo, "
            + "TRANSACTION_ID AS transactionId, PAY_AMOUNT AS payAmount, PAY_STATUS AS payStatus, "
            + "PAY_SOURCE AS paySource, CURRENCY AS currency, PAY_EXPIRE_TIME AS payExpireTime, "
            + "PAY_SUCCESS_TIME AS paySuccessTime, DATA_STATUS AS dataStatus, CREATE_TIME AS createTime";

    String ORDER_COLUMNS = "ID AS id, ORDER_NO AS orderNo, ORDER_TYPE AS orderType, USER_ID AS userId, "
            + "CARD_ID AS cardId, PACKAGE_ID AS packageId, PACKAGE_SNAP AS packageSnap, "
            + "ORDER_AMOUNT AS orderAmount, PAY_WAY AS payWay, ORDER_STATUS AS orderStatus, "
            + "FINISH_TIME AS finishTime, CANCEL_REASON AS cancelReason, DATA_STATUS AS dataStatus, "
            + "CREATE_TIME AS createTime, UPDATE_TIME AS updateTime";

    String FLOW_COLUMNS = "ID AS id, CARD_ID AS cardId, USER_ID AS userId, FLOW_TYPE AS flowType, "
            + "AMOUNT_CHANGE AS amountChange, ML_CHANGE AS mlChange, AMOUNT_AFTER AS amountAfter, "
            + "ML_AFTER AS mlAfter, ORDER_ID AS orderId, FLOW_REMARK AS flowRemark, "
            + "BIZ_IDEMPOTENCY_KEY AS bizIdempotencyKey, DATA_STATUS AS dataStatus, CREATE_TIME AS createTime";

    @Select("SELECT " + PAYMENT_COLUMNS + " FROM ws_payment WHERE ID = #{paymentId} FOR UPDATE")
    WsPayment lockPayment(@Param("paymentId") Long paymentId);

    @Select("SELECT " + ORDER_COLUMNS + " FROM ws_order WHERE ID = #{orderId} FOR UPDATE")
    WsOrder lockOrder(@Param("orderId") Long orderId);

    @Select("SELECT " + EVENT_COLUMNS + " FROM ws_payment_event WHERE ORDER_NO = #{orderNo} ORDER BY ID FOR UPDATE")
    List<WsPaymentEvent> lockEventsByOrderNo(@Param("orderNo") String orderNo);

    @Select("SELECT " + FLOW_COLUMNS + " FROM ws_wallet_flow WHERE CARD_ID = #{cardId} ORDER BY ID FOR UPDATE")
    List<WsWalletFlow> lockCardFlows(@Param("cardId") Long cardId);

    // ------------------------------------------------------------------
    // 支付单 / 订单状态推进（全部 CAS）
    // ------------------------------------------------------------------

    /**
     * 支付单置成功：1待支付 → 2成功，同时回填权威交易号与成功时间。
     * WHERE 带 {@code PAY_STATUS = 1}，重复回调只有第一次能改成功。
     */
    @Update("UPDATE ws_payment SET PAY_STATUS = 2, TRANSACTION_ID = #{transactionId}, "
            + "PAY_SUCCESS_TIME = #{paySuccessTime}, CALLBACK_TIME = #{now}, UPDATE_TIME = #{now} "
            + "WHERE ID = #{paymentId} AND PAY_STATUS = 1 AND DATA_STATUS = 0")
    int markPaymentSuccess(@Param("paymentId") Long paymentId,
                           @Param("transactionId") String transactionId,
                           @Param("paySuccessTime") String paySuccessTime,
                           @Param("now") String now);

    /** 订单置已支付：1待支付 → 2已支付。 */
    @Update("UPDATE ws_order SET ORDER_STATUS = 2, UPDATE_TIME = #{now} "
            + "WHERE ID = #{orderId} AND ORDER_STATUS = 1 AND DATA_STATUS = 0")
    int markOrderPaid(@Param("orderId") Long orderId, @Param("now") String now);

    /**
     * 支付单置关闭：1待支付 → 4已关闭（契约 §7.1：<b>仅支付方确认 CLOSED 后</b>才允许）。
     *
     * <p>WHERE 死锁在 {@code PAY_STATUS = 1}：迟到的权威成功事实可能已把支付单推到 2，
     * 此时影响行数为 0，调用方必须整体回滚转人工，绝不能把一笔已收到的款项覆盖成"已关闭"。</p>
     */
    @Update("UPDATE ws_payment SET PAY_STATUS = 4, UPDATE_TIME = #{now} "
            + "WHERE ID = #{paymentId} AND PAY_STATUS = 1 AND DATA_STATUS = 0")
    int markPaymentClosed(@Param("paymentId") Long paymentId, @Param("now") String now);

    /** 订单置已关闭：1待支付 → 5已取消（与 {@link #markPaymentClosed} 同事务，两条 CAS 都必须影响 1 行）。 */
    @Update("UPDATE ws_order SET ORDER_STATUS = 5, CANCEL_REASON = #{reason}, UPDATE_TIME = #{now} "
            + "WHERE ID = #{orderId} AND ORDER_STATUS = 1 AND DATA_STATUS = 0")
    int markOrderClosed(@Param("orderId") Long orderId,
                        @Param("reason") String reason,
                        @Param("now") String now);

    /** 订单置已完成：2已支付 → 4已完成（权益已入账）。 */
    @Update("UPDATE ws_order SET ORDER_STATUS = 4, FINISH_TIME = #{now}, UPDATE_TIME = #{now} "
            + "WHERE ID = #{orderId} AND ORDER_STATUS = 2 AND DATA_STATUS = 0")
    int markOrderFinished(@Param("orderId") Long orderId, @Param("now") String now);

    /** 订单置异常待人工：2已支付 → 6异常（钱已收但入账前置条件不成立时用）。 */
    @Update("UPDATE ws_order SET ORDER_STATUS = 6, CANCEL_REASON = #{reason}, UPDATE_TIME = #{now} "
            + "WHERE ID = #{orderId} AND ORDER_STATUS = 2 AND DATA_STATUS = 0")
    int markOrderAbnormal(@Param("orderId") Long orderId,
                          @Param("reason") String reason,
                          @Param("now") String now);

    // ------------------------------------------------------------------
    // 入账
    // ------------------------------------------------------------------

    /**
     * 目标卡（跨 DATA_STATUS 读，逻辑删除的卡也要看得见——钱已收，不能因为看不见卡就当无事发生）。
     */
    @Select("SELECT ID AS id, CARD_NO AS cardNo, USER_ID AS userId, BALANCE_AMOUNT AS balanceAmount, "
            + "BALANCE_ML AS balanceMl, EXPIRE_TIME AS expireTime, CARD_STATUS AS cardStatus, "
            + "DATA_STATUS AS dataStatus FROM ws_card WHERE ID = #{cardId}")
    WsCard selectCardIncludingDeleted(@Param("cardId") Long cardId);

    /**
     * 事务 B 锁卡读取（{@code FOR UPDATE}）。契约 §6.4 要求按固定顺序锁定并读取跨全部 DATA_STATUS 的卡，
     * 后续的 CAS 前态全部取自这一次锁内读取，不允许用锁外的旧值。
     */
    @Select("SELECT ID AS id, CARD_NO AS cardNo, USER_ID AS userId, BALANCE_AMOUNT AS balanceAmount, "
            + "BALANCE_ML AS balanceMl, EXPIRE_TIME AS expireTime, CARD_STATUS AS cardStatus, "
            + "SCOPE_JSON AS scopeJson, PACKAGE_ID AS packageId, DATA_STATUS AS dataStatus "
            + "FROM ws_card WHERE ID = #{cardId} FOR UPDATE")
    WsCard lockCard(@Param("cardId") Long cardId);

    /**
     * 有限卡原子入账（契约 §6.4 步骤 6）。
     *
     * <p>一条 UPDATE 同时完成：加余额、加水量、写续期后的 {@code EXPIRE_TIME}、把仅因自然过期
     * 形成的 {@code CARD_STATUS=3} 恢复为 1、写最近 {@code PACKAGE_ID/PACKAGE_SNAP}。
     * 拆成多条会出现「加了钱但有效期没续上」的中间态。</p>
     *
     * <p>WHERE 带上锁内读到的<b>全部前态</b>——旧余额、旧水量、旧有效期、旧范围、状态只允许 1/3、
     * 归属与未删除。任一前态在我们计算期间被别的事务改过，影响行数即为 0，整个事务回滚。
     * 这比「读出来算完再写回去」多挡住的正是并发下的丢失更新。
     * {@code <=>} 是 MySQL 的 NULL 安全等值，让永久有效期(NULL)与具体值走同一套比较。</p>
     */
    @Update("UPDATE ws_card SET BALANCE_AMOUNT = BALANCE_AMOUNT + #{amountFen}, "
            + "BALANCE_ML = BALANCE_ML + #{ml}, EXPIRE_TIME = #{newExpireTime}, CARD_STATUS = 1, "
            + "PACKAGE_ID = #{packageId}, PACKAGE_SNAP = #{packageSnap}, "
            + "UPDATE_BY = #{opUserId}, UPDATE_TIME = #{now} "
            + "WHERE ID = #{cardId} AND USER_ID = #{opUserId} AND DATA_STATUS = 0 "
            + "AND CARD_STATUS IN (1, 3) "
            + "AND BALANCE_AMOUNT = #{oldAmount} AND BALANCE_ML = #{oldMl} "
            + "AND EXPIRE_TIME <=> #{oldExpireTime} AND SCOPE_JSON <=> #{oldScopeJson}")
    int creditFiniteCard(@Param("cardId") Long cardId,
                         @Param("amountFen") Long amountFen,
                         @Param("ml") Long ml,
                         @Param("newExpireTime") String newExpireTime,
                         @Param("packageId") Long packageId,
                         @Param("packageSnap") String packageSnap,
                         @Param("opUserId") Long opUserId,
                         @Param("now") String now,
                         @Param("oldAmount") Long oldAmount,
                         @Param("oldMl") Long oldMl,
                         @Param("oldExpireTime") String oldExpireTime,
                         @Param("oldScopeJson") String oldScopeJson);

    /**
     * 永久卡原子入账。{@code EXPIRE_TIME} 保持 NULL 且 WHERE 显式要求它<b>本来就是 NULL</b>——
     * 永久卡不该有有效期，一旦发现有值说明卡数据被改写，宁可影响 0 行整体回滚。
     */
    @Update("UPDATE ws_card SET BALANCE_AMOUNT = BALANCE_AMOUNT + #{amountFen}, "
            + "BALANCE_ML = BALANCE_ML + #{ml}, CARD_STATUS = 1, "
            + "PACKAGE_ID = #{packageId}, PACKAGE_SNAP = #{packageSnap}, "
            + "UPDATE_BY = #{opUserId}, UPDATE_TIME = #{now} "
            + "WHERE ID = #{cardId} AND USER_ID = #{opUserId} AND DATA_STATUS = 0 "
            + "AND CARD_STATUS = 1 AND EXPIRE_TIME IS NULL "
            + "AND BALANCE_AMOUNT = #{oldAmount} AND BALANCE_ML = #{oldMl} "
            + "AND SCOPE_JSON <=> #{oldScopeJson}")
    int creditPermanentCard(@Param("cardId") Long cardId,
                            @Param("amountFen") Long amountFen,
                            @Param("ml") Long ml,
                            @Param("packageId") Long packageId,
                            @Param("packageSnap") String packageSnap,
                            @Param("opUserId") Long opUserId,
                            @Param("now") String now,
                            @Param("oldAmount") Long oldAmount,
                            @Param("oldMl") Long oldMl,
                            @Param("oldScopeJson") String oldScopeJson);

    /**
     * 充值入账流水。{@code BIZ_IDEMPOTENCY_KEY} 上有唯一键，<b>这是整条充值链防重复入账的最后一道</b>：
     * 同一订单第二次入账会在这里撞键，连带把同事务里刚加的余额一起回滚。
     */
    @Insert("INSERT INTO ws_wallet_flow(DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, "
            + "CARD_ID, USER_ID, FLOW_TYPE, AMOUNT_CHANGE, ML_CHANGE, AMOUNT_AFTER, ML_AFTER, "
            + "ORDER_ID, FLOW_REMARK, BIZ_IDEMPOTENCY_KEY) "
            + "VALUES(0, #{userId}, #{now}, #{userId}, #{now}, "
            + "#{cardId}, #{userId}, 1, #{amountChange}, #{mlChange}, #{amountAfter}, #{mlAfter}, "
            + "#{orderId}, #{remark}, #{bizKey})")
    int insertRechargeFlow(@Param("cardId") Long cardId,
                           @Param("userId") Long userId,
                           @Param("amountChange") Long amountChange,
                           @Param("mlChange") Long mlChange,
                           @Param("amountAfter") Long amountAfter,
                           @Param("mlAfter") Long mlAfter,
                           @Param("orderId") Long orderId,
                           @Param("remark") String remark,
                           @Param("bizKey") String bizKey,
                           @Param("now") String now);

    /** 该订单是否已有充值入账流水（跨全部 DATA_STATUS——被删的流水同样意味着入过账）。 */
    @Select("SELECT COUNT(*) FROM ws_wallet_flow WHERE BIZ_IDEMPOTENCY_KEY = #{bizKey}")
    long countFlowByBizKey(@Param("bizKey") String bizKey);

    /**
     * 按订单主键核对充值流水占用，跨全部 DATA_STATUS 且不依赖幂等键内容。
     * 历史污染流水即使写错 BIZ_IDEMPOTENCY_KEY，也不得让同一订单再次发放权益。
     */
    @Select("SELECT COUNT(*) FROM ws_wallet_flow WHERE ORDER_ID = #{orderId} AND FLOW_TYPE = 1")
    long countRechargeFlowByOrderId(@Param("orderId") Long orderId);

    // ------------------------------------------------------------------
    // L2-A 首次购卡发卡（决策 A2/A3/A4）
    // ------------------------------------------------------------------

    /**
     * 锁用户行（决策 A4）：{@code SELECT ... FOR UPDATE} 串行化<b>同一用户</b>的发卡。
     * 两个 Worker 并发处理同一用户的两笔购卡事实时，第二个会在这里排队，
     * 醒来后要么撞发行锚点走幂等核验，要么在资格复查发现「已有卡」而拒绝——
     * 没有这把锁，资格复查与建卡之间存在窗口，同一用户可能被并发发出两张首卡。
     */
    @Select("SELECT ID FROM ws_user WHERE ID = #{userId} FOR UPDATE")
    Long lockUserRow(@Param("userId") Long userId);

    /**
     * 按发行锚点查已发出的卡（决策 A3，<b>跨全部 DATA_STATUS</b> 并锁行）。
     * {@code uk_card_issue_order} 保证同一订单至多一张卡；被逻辑删除的发行卡同样必须可见——
     * 钱已收、卡已发的事实不因记录被删就消失，此时只能人工对账，绝不能再发第二张。
     *
     * <p><b>只允许在订单已完成（4）的分支调用</b>：此时锚点行应当存在，FOR UPDATE 命中既有行
     * 只加行锁。fresh 发卡路径（订单 2）绝不查询此方法——对不存在的唯一索引行加锁会留下 gap 锁，
     * 两笔不相关的并发发卡会在相邻空隙上互等死锁；订单 2 ⟹ 锚点必不存在由发卡事务原子性保证。</p>
     */
    @Select("SELECT ID AS id, CARD_NO AS cardNo, CARD_TYPE AS cardType, USER_ID AS userId, "
            + "BALANCE_AMOUNT AS balanceAmount, BALANCE_ML AS balanceMl, PACKAGE_ID AS packageId, "
            + "SCOPE_JSON AS scopeJson, EXPIRE_TIME AS expireTime, CARD_STATUS AS cardStatus, "
            + "ISSUE_ORDER_ID AS issueOrderId, DATA_STATUS AS dataStatus "
            + "FROM ws_card WHERE ISSUE_ORDER_ID = #{orderId} FOR UPDATE")
    WsCard selectCardByIssueOrderId(@Param("orderId") Long orderId);

    /**
     * 发卡事务内的资格复查（决策 A4）：只看 {@code DATA_STATUS=0}、<b>跨全部卡状态</b>计数。
     * 冻结/过期/注销但未删除的卡同样占用「首次购卡」资格——口径与创单侧
     * {@code RechargeIdentityMapper#selectCountLiveCardsByUser} 必须一致，两处不同就会出现
     * 「创单放行、发卡拒绝」或反之的裂缝。
     */
    @Select("SELECT COUNT(*) FROM ws_card WHERE USER_ID = #{userId} AND DATA_STATUS = 0")
    long countLiveCardsByUser(@Param("userId") Long userId);

    /**
     * 建零余额零水量虚拟卡（决策 A2 步骤 e）。显式列写入，权益随后由
     * {@link #creditFiniteCard}/{@link #creditPermanentCard} 以 0,0 前态原子加上——
     * 建卡与加权益分开走同一套 CAS 入账通道，权益路径就只有一条，不会出现「插卡时顺手写余额」的旁路。
     * {@code uk_card_no} 与 {@code uk_card_issue_order} 双唯一键让并发重放在数据库层天然幂等。
     */
    @Insert("INSERT INTO ws_card(DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, "
            + "CARD_NO, CARD_TYPE, USER_ID, BALANCE_AMOUNT, BALANCE_ML, PACKAGE_ID, PACKAGE_SNAP, "
            + "SCOPE_JSON, EXPIRE_TIME, CARD_STATUS, ISSUE_ORDER_ID) "
            + "VALUES(#{dataStatus}, #{createBy}, #{createTime}, #{updateBy}, #{updateTime}, "
            + "#{cardNo}, #{cardType}, #{userId}, #{balanceAmount}, #{balanceMl}, #{packageId}, #{packageSnap}, "
            + "#{scopeJson}, #{expireTime}, #{cardStatus}, #{issueOrderId})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "ID")
    int insertIssuedCard(WsCard card);

    /**
     * 回填订单 CARD_ID（决策 A2 步骤 h）。CAS 条件 {@code CARD_ID IS NULL}：
     * 该列一旦有值就永不改写——影响 0 行意味着订单已被并发或人工写过卡关联，
     * 调用方必须整体回滚（连同刚建的卡与流水），绝不能覆盖既有关联把权益挂错卡。
     */
    @Update("UPDATE ws_order SET CARD_ID = #{cardId}, UPDATE_TIME = #{now} "
            + "WHERE ID = #{orderId} AND CARD_ID IS NULL")
    int backfillOrderCardId(@Param("orderId") Long orderId,
                            @Param("cardId") Long cardId,
                            @Param("now") String now);
}
