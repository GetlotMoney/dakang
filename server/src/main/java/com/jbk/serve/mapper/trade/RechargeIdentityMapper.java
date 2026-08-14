package com.jbk.serve.mapper.trade;

import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsPayment;
import com.jbk.tool.data.trade.po.WsPaymentEvent;
import com.jbk.tool.data.trade.po.WsWalletFlow;
import com.jbk.tool.data.user.po.WsCard;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 充值链身份查询 Mapper：手写 SQL，跨全部 DATA_STATUS 读取，不继承 BaseMapper 避开 {@code @TableLogic} 过滤。
 * §4.2 幂等：按 ORDER_NO 查订单必须跨全部状态，否则被删订单让同 requestId 重新建单；
 * §9.1：被逻辑删除的 payment/event/资金流水视为污染并整体 mismatch，不得被隐藏。
 */
@Mapper
public interface RechargeIdentityMapper {

    String ORDER_COLUMNS = "ID AS id, ORDER_NO AS orderNo, ORDER_TYPE AS orderType, USER_ID AS userId, "
            + "CARD_ID AS cardId, PACKAGE_ID AS packageId, PACKAGE_SNAP AS packageSnap, "
            + "ORDER_AMOUNT AS orderAmount, PAY_WAY AS payWay, ORDER_STATUS AS orderStatus, "
            + "FINISH_TIME AS finishTime, CANCEL_REASON AS cancelReason, DATA_STATUS AS dataStatus, "
            + "CREATE_BY AS createBy, CREATE_TIME AS createTime, UPDATE_BY AS updateBy, UPDATE_TIME AS updateTime";

    String PAYMENT_COLUMNS = "ID AS id, ORDER_ID AS orderId, ORDER_NO AS orderNo, "
            + "TRANSACTION_ID AS transactionId, PAY_AMOUNT AS payAmount, PAY_STATUS AS payStatus, "
            + "PAY_SOURCE AS paySource, CURRENCY AS currency, PAY_EXPIRE_TIME AS payExpireTime, "
            + "PAY_SUCCESS_TIME AS paySuccessTime, PREPAY_ID AS prepayId, CALLBACK_TIME AS callbackTime, "
            + "DATA_STATUS AS dataStatus, CREATE_BY AS createBy, CREATE_TIME AS createTime, "
            + "UPDATE_BY AS updateBy, UPDATE_TIME AS updateTime";

    /** 按订单号跨全部 DATA_STATUS 查订单；正常应 0 或 1 条（uk_order_no 保证）。 */
    @Select("SELECT " + ORDER_COLUMNS + " FROM ws_order WHERE ORDER_NO = #{orderNo}")
    List<WsOrder> selectOrdersByOrderNoIncludingDeleted(@Param("orderNo") String orderNo);

    /** 按订单 ID 跨全部 DATA_STATUS 查支付单；>1 或被逻辑删除均属污染，由服务层拒绝。 */
    @Select("SELECT " + PAYMENT_COLUMNS + " FROM ws_payment WHERE ORDER_ID = #{orderId}")
    List<WsPayment> selectPaymentsByOrderIdIncludingDeleted(@Param("orderId") Long orderId);

    /** 创建支付单（与订单同事务）。显式列写入，不依赖 MP 自动填充。 */
    @Insert("INSERT INTO ws_payment(DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, "
            + "ORDER_ID, ORDER_NO, PAY_AMOUNT, PAY_STATUS, PAY_SOURCE, CURRENCY, PAY_EXPIRE_TIME) "
            + "VALUES(#{dataStatus}, #{createBy}, #{createTime}, #{updateBy}, #{updateTime}, "
            + "#{orderId}, #{orderNo}, #{payAmount}, #{payStatus}, #{paySource}, #{currency}, #{payExpireTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "ID")
    int insertPayment(WsPayment payment);

    String EVENT_COLUMNS = "ID AS id, ORDER_NO AS orderNo, ORDER_ID AS orderId, PAYMENT_ID AS paymentId, "
            + "PAY_SOURCE AS paySource, FACT_CHANNEL AS factChannel, PROVIDER_EVENT_KEY AS providerEventKey, "
            + "TRADE_STATE AS tradeState, TRANSACTION_ID AS transactionId, PAY_AMOUNT AS payAmount, "
            + "CURRENCY AS currency, PAY_SUCCESS_TIME AS paySuccessTime, PROCESSING_STATUS AS processingStatus, "
            + "RECOVERY_APPROVAL_GROUP_KEY AS recoveryApprovalGroupKey, RECOVERY_APPROVED_BY AS recoveryApprovedBy, "
            + "DATA_STATUS AS dataStatus";

    /** 按订单号跨全部 DATA_STATUS 查支付事件；被逻辑删除的事件同样必须可见（§9.1 视为污染）。 */
    @Select("SELECT " + EVENT_COLUMNS + " FROM ws_payment_event WHERE ORDER_NO = #{orderNo} ORDER BY ID")
    List<WsPaymentEvent> selectEventsByOrderNoIncludingDeleted(@Param("orderNo") String orderNo);

    String FLOW_COLUMNS = "ID AS id, CARD_ID AS cardId, USER_ID AS userId, FLOW_TYPE AS flowType, "
            + "AMOUNT_CHANGE AS amountChange, ML_CHANGE AS mlChange, AMOUNT_AFTER AS amountAfter, "
            + "ML_AFTER AS mlAfter, ORDER_ID AS orderId, FLOW_REMARK AS flowRemark, "
            + "BIZ_IDEMPOTENCY_KEY AS bizIdempotencyKey, DATA_STATUS AS dataStatus, CREATE_TIME AS createTime";

    /** 按订单读取全部状态的流水；详情不得用 TableLogic 隐藏污染流水。 */
    @Select("SELECT " + FLOW_COLUMNS + " FROM ws_wallet_flow WHERE ORDER_ID = #{orderId} ORDER BY ID")
    List<WsWalletFlow> selectFlowsByOrderIdIncludingDeleted(@Param("orderId") Long orderId);

    /** 按主键读取全部状态的目标卡。 */
    @Select("SELECT ID AS id, CARD_NO AS cardNo, CARD_TYPE AS cardType, USER_ID AS userId, "
            + "BALANCE_AMOUNT AS balanceAmount, BALANCE_ML AS balanceMl, PACKAGE_ID AS packageId, "
            + "PACKAGE_SNAP AS packageSnap, SCOPE_JSON AS scopeJson, EXPIRE_TIME AS expireTime, "
            + "CARD_STATUS AS cardStatus, ISSUE_ORDER_ID AS issueOrderId, DATA_STATUS AS dataStatus "
            + "FROM ws_card WHERE ID = #{cardId}")
    List<WsCard> selectCardsByIdIncludingDeleted(@Param("cardId") Long cardId);

    /** 有效充值入账流水条数（FLOW_TYPE=1 且未被逻辑删除）。 */
    @Select("SELECT COUNT(*) FROM ws_wallet_flow WHERE ORDER_ID = #{orderId} AND FLOW_TYPE = 1 AND DATA_STATUS = 0")
    long countLiveRechargeFlows(@Param("orderId") Long orderId);

    /** 被逻辑删除的充值流水条数（§8/§9.1：被删资金流水即污染，必须整体 mismatch，不得报成"已到账"）。 */
    @Select("SELECT COUNT(*) FROM ws_wallet_flow WHERE ORDER_ID = #{orderId} AND FLOW_TYPE = 1 AND DATA_STATUS <> 0")
    long countDeletedRechargeFlows(@Param("orderId") Long orderId);

    /**
     * 首次购卡资格计数（决策 A4）：只看 DATA_STATUS=0、跨全部卡状态（冻结/过期/注销未删同样阻断），口径必须与发卡侧
     * {@code RechargeCreditMapper#countLiveCardsByUser} 一致。仅排除活动赠卡（判据与 CardEligibility#isGiftCard 同构，E2E-08/审计 P1-2）；
     * 不能整类按 EXPIRE_TIME 排除，否则有限期付费卡（NewCardExpiry）绕过一人一卡。
     */
    @Select("SELECT COUNT(*) FROM ws_card WHERE USER_ID = #{userId} AND DATA_STATUS = 0"
            + " AND " + com.jbk.serve.service.mini.card.CardEligibility.SQL_NOT_GIFT)
    long selectCountLiveCardsByUser(@Param("userId") Long userId);
}
