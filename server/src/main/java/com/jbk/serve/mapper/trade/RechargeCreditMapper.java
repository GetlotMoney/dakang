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
 * 充值入账与支付事实推进 Mapper（L2-T）。全部状态推进用 CAS（WHERE 带前置状态），影响 0 行不得当成功。
 * 资金铁律：余额/水量只能原子 {@code UPDATE ... SET X = X + n}，禁止读出→内存计算→写回。
 * 入账幂等靠 {@code ws_wallet_flow.BIZ_IDEMPOTENCY_KEY} 唯一键（撞键整事务回滚），不靠应用层查重。
 */
@Mapper
public interface RechargeCreditMapper {

    // ------------------------------------------------------------------
    // 支付事实（ws_payment_event）
    // ------------------------------------------------------------------

    /** 落一条支付事实；{@code uk_payment_event_source_channel_key} 在库层挡掉重复回调。 */
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
                    // rawBody 等四列 NOT NULL 且刻意无默认值：来源与完整性证据必须显式落库
                    @Param("rawBody") String rawBody,
                    @Param("rawBodySha256") String rawBodySha256,
                    @Param("verifyMethod") Integer verifyMethod);

    /** NOTIFY 通道专用：比 {@link #insertEvent} 多落四列验签材料，供事后用平台公钥复核。 */
    @Insert("INSERT INTO ws_payment_event(DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, "
            + "ORDER_NO, ORDER_ID, PAYMENT_ID, PAY_SOURCE, FACT_CHANNEL, PROVIDER_EVENT_KEY, "
            + "TRADE_STATE, TRANSACTION_ID, PAY_AMOUNT, CURRENCY, PAY_SUCCESS_TIME, PROCESSING_STATUS, "
            + "RAW_BODY, RAW_BODY_SHA256, VERIFY_METHOD, RECEIVED_TIME, "
            + "SIGNATURE_SERIAL, SIGNATURE_TIMESTAMP, SIGNATURE_NONCE, SIGNATURE_VALUE) "
            + "VALUES(0, #{createBy}, #{now}, #{createBy}, #{now}, "
            + "#{orderNo}, #{orderId}, #{paymentId}, #{paySource}, #{factChannel}, #{providerEventKey}, "
            + "#{tradeState}, #{transactionId}, #{payAmount}, #{currency}, #{paySuccessTime}, 1, "
            + "#{rawBody}, #{rawBodySha256}, #{verifyMethod}, #{now}, "
            + "#{signatureSerial}, #{signatureTimestamp}, #{signatureNonce}, #{signatureValue})")
    int insertNotifyEvent(@Param("orderNo") String orderNo,
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
                          @Param("rawBody") String rawBody,
                          @Param("rawBodySha256") String rawBodySha256,
                          @Param("verifyMethod") Integer verifyMethod,
                          @Param("signatureSerial") String signatureSerial,
                          @Param("signatureTimestamp") String signatureTimestamp,
                          @Param("signatureNonce") String signatureNonce,
                          @Param("signatureValue") String signatureValue);

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

    /** 认领事实：1待处理 → 2处理中。CAS 影响 0 行=已被别人认领或已处理，调用方必须放弃入账。 */
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
     * 认领非成功查单事实（NOTPAY/CLOSED）：1待处理 → 2处理中。刻意与 {@link #claimEvent} 分开：
     * 权益 Worker 依赖那把 {@code TRADE_STATE='SUCCESS'} 锁（契约 §6.3），放宽会让 CLOSED 事实走到入账分支。
     * 无重试路径：处理不掉停在 5待对账等人工。
     */
    @Update("UPDATE ws_payment_event SET PROCESSING_STATUS = 2, RETRY_COUNT = RETRY_COUNT + 1, "
            + "CLAIM_TIME = #{now}, LEASE_UNTIL = #{leaseUntil}, NEXT_RETRY_TIME = NULL, LAST_ERROR = NULL, "
            + "UPDATE_TIME = #{now} WHERE ID = #{eventId} AND TRADE_STATE IN ('NOTPAY', 'CLOSED') "
            + "AND DATA_STATUS = 0 AND (PROCESSING_STATUS = 1 "
            + "OR (PROCESSING_STATUS = 2 AND (LEASE_UNTIL IS NULL OR LEASE_UNTIL <= #{now})))")
    int claimQueryEvent(@Param("eventId") Long eventId,
                        @Param("now") String now,
                        @Param("leaseUntil") String leaseUntil);

    /** 同键重复到达的正文摘要比对（契约 §5.3）。返回 0=摘要不符，调用方必须拒绝而非复用旧事实。 */
    @Select("SELECT COUNT(*) FROM ws_payment_event WHERE ID = #{eventId} AND RAW_BODY_SHA256 = #{sha256}")
    int countEventMatchingDigest(@Param("eventId") Long eventId, @Param("sha256") String sha256);

    /**
     * 扫描待推进的支付事实（重试 Worker 专用，只出 ID）。宽松预筛，并发安全仍由 claim 的 CAS 保证；
     * 三类候选与 claim 的 WHERE 完全同构。
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

    /** 事实转待重试：2处理中 → 4待重试。仅用于可恢复失败（当前只有卡冻结），与 5待对账 必须分开。 */
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

    /** 支付单置成功：1待支付 → 2成功，回填权威交易号与成功时间；WHERE 带 PAY_STATUS=1，重复回调只有第一次生效。 */
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
     * 支付单置关闭：1待支付 → 4已关闭（契约 §7.1：仅支付方确认 CLOSED 后允许）。
     * 影响 0 行=已被权威成功事实推进，调用方必须整体回滚转人工，绝不能把已收款覆盖成已关闭。
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

    /** 事务 B 锁卡读取（FOR UPDATE，契约 §6.4，跨全部 DATA_STATUS）：后续 CAS 前态全部取自锁内读取。 */
    @Select("SELECT ID AS id, CARD_NO AS cardNo, USER_ID AS userId, BALANCE_AMOUNT AS balanceAmount, "
            + "BALANCE_ML AS balanceMl, EXPIRE_TIME AS expireTime, CARD_STATUS AS cardStatus, "
            + "SCOPE_JSON AS scopeJson, PACKAGE_ID AS packageId, DATA_STATUS AS dataStatus "
            + "FROM ws_card WHERE ID = #{cardId} FOR UPDATE")
    WsCard lockCard(@Param("cardId") Long cardId);

    /**
     * 有限卡原子入账（契约 §6.4 步骤 6）：一条 UPDATE 同时加余额/水量、续 EXPIRE_TIME、恢复自然过期态、写套餐快照，拆开会留中间态。
     * WHERE 带锁内读到的全部前态，任一被并发改过即 0 行整体回滚；{@code <=>} 为 NULL 安全等值（永久有效期）。
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

    /** 永久卡原子入账。WHERE 显式要求 EXPIRE_TIME IS NULL：永久卡出现有效期即数据被改写，0 行整体回滚。 */
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

    /** 充值入账流水。BIZ_IDEMPOTENCY_KEY 唯一键是整条充值链防重复入账的最后一道：撞键连带回滚同事务刚加的余额。 */
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

    /** 按订单主键核对充值流水占用（跨全部 DATA_STATUS，不依赖幂等键内容）：写错幂等键的污染流水也不得让订单重复发放。 */
    @Select("SELECT COUNT(*) FROM ws_wallet_flow WHERE ORDER_ID = #{orderId} AND FLOW_TYPE = 1")
    long countRechargeFlowByOrderId(@Param("orderId") Long orderId);

    // ------------------------------------------------------------------
    // L2-A 首次购卡发卡（决策 A2/A3/A4）
    // ------------------------------------------------------------------

    /** 锁用户行（决策 A4）：串行化同一用户的发卡，堵住资格复查与建卡之间的并发双发窗口。 */
    @Select("SELECT ID FROM ws_user WHERE ID = #{userId} FOR UPDATE")
    Long lockUserRow(@Param("userId") Long userId);

    /**
     * 按发行锚点查已发出的卡（决策 A3，跨全部 DATA_STATUS 并锁行）：被逻辑删除的发行卡也必须可见，只能人工对账，绝不再发第二张。
     * 只允许在订单已完成(4)分支调用：fresh 发卡路径（订单 2）查不存在的唯一索引行会留 gap 锁，并发发卡在相邻空隙互等死锁。
     */
    @Select("SELECT ID AS id, CARD_NO AS cardNo, CARD_TYPE AS cardType, USER_ID AS userId, "
            + "BALANCE_AMOUNT AS balanceAmount, BALANCE_ML AS balanceMl, PACKAGE_ID AS packageId, "
            + "SCOPE_JSON AS scopeJson, EXPIRE_TIME AS expireTime, CARD_STATUS AS cardStatus, "
            + "ISSUE_ORDER_ID AS issueOrderId, DATA_STATUS AS dataStatus "
            + "FROM ws_card WHERE ISSUE_ORDER_ID = #{orderId} FOR UPDATE")
    WsCard selectCardByIssueOrderId(@Param("orderId") Long orderId);

    /**
     * 发卡事务内的资格复查（决策 A4）：只看 DATA_STATUS=0、跨全部卡状态，口径必须与创单侧
     * {@code RechargeIdentityMapper#selectCountLiveCardsByUser} 一致。仅排除活动赠卡（判据与 {@code CardEligibility#isGiftCard} 同构，E2E-08/审计 P1-2），有限期付费卡仍占名额。
     */
    @Select("SELECT COUNT(*) FROM ws_card WHERE USER_ID = #{userId} AND DATA_STATUS = 0"
            + " AND " + com.jbk.serve.service.mini.card.CardEligibility.SQL_NOT_GIFT)
    long countLiveCardsByUser(@Param("userId") Long userId);

    /**
     * 建零余额零水量虚拟卡（决策 A2 步骤 e）：权益随后经 {@link #creditFiniteCard}/{@link #creditPermanentCard} 以 0,0 前态原子加上，权益路径只有一条；
     * uk_card_no 与 uk_card_issue_order 双唯一键让并发重放天然幂等。
     */
    @Insert("INSERT INTO ws_card(DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, "
            + "CARD_NO, CARD_TYPE, USER_ID, BALANCE_AMOUNT, BALANCE_ML, PACKAGE_ID, PACKAGE_SNAP, "
            + "SCOPE_JSON, EXPIRE_TIME, CARD_STATUS, ISSUE_ORDER_ID) "
            + "VALUES(#{dataStatus}, #{createBy}, #{createTime}, #{updateBy}, #{updateTime}, "
            + "#{cardNo}, #{cardType}, #{userId}, #{balanceAmount}, #{balanceMl}, #{packageId}, #{packageSnap}, "
            + "#{scopeJson}, #{expireTime}, #{cardStatus}, #{issueOrderId})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "ID")
    int insertIssuedCard(WsCard card);

    /** 回填订单 CARD_ID（决策 A2 步骤 h）。CAS 条件 CARD_ID IS NULL：0 行=已有卡关联，调用方必须整体回滚，绝不覆盖。 */
    @Update("UPDATE ws_order SET CARD_ID = #{cardId}, UPDATE_TIME = #{now} "
            + "WHERE ID = #{orderId} AND CARD_ID IS NULL")
    int backfillOrderCardId(@Param("orderId") Long orderId,
                            @Param("cardId") Long cardId,
                            @Param("now") String now);
}
