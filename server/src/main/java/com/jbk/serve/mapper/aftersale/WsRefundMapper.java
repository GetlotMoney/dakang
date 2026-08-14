package com.jbk.serve.mapper.aftersale;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.aftersale.po.WsRefund;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsPayment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 退款单 Mapper（E2E-04 包B）。SQL 内不含 SQL 注释：Druid WallFilter comment-not-allow 会在运行期 500，Testcontainers 单测不经 Druid 墙抓不到。
 *
 * @author dakang
 * @since 2026-07-29
 */
@Mapper
public interface WsRefundMapper extends BaseMapper<WsRefund> {

    String COLUMNS = "ID AS id, REFUND_NO AS refundNo, ORDER_ID AS orderId, ORDER_NO AS orderNo, "
            + "PAYMENT_ID AS paymentId, AFTER_SALE_ID AS afterSaleId, REFUND_AMOUNT AS refundAmount, "
            + "REFUND_SOURCE AS refundSource, CURRENCY AS currency, REFUND_STATUS AS refundStatus, "
            + "PROVIDER_REFUND_ID AS providerRefundId, REQUEST_TIME AS requestTime, SUCCESS_TIME AS successTime, "
            + "VERSION AS version, RETRY_COUNT AS retryCount, NEXT_RETRY_TIME AS nextRetryTime, "
            + "LAST_ERROR AS lastError, DATA_STATUS AS dataStatus";

    /** 创建请求的幂等裁决使用当前读，避免 RR 快照漏掉刚提交的并发退款单。 */
    @Select("SELECT " + COLUMNS + " FROM ws_refund WHERE AFTER_SALE_ID = #{afterSaleId} FOR UPDATE")
    WsRefund lockByAfterSaleId(@Param("afterSaleId") Long afterSaleId);

    /** 受理凭据回填必须锁定退款单，才能区分同号重放与不同服务方单号冲突。 */
    @Select("SELECT " + COLUMNS + " FROM ws_refund WHERE ID = #{id} FOR UPDATE")
    WsRefund lockById(@Param("id") Long id);

    /** 按商户退款单号定位（事实回来时的关联入口）。 */
    @Select("SELECT " + COLUMNS + " FROM ws_refund WHERE REFUND_NO = #{refundNo}")
    WsRefund selectByRefundNo(@Param("refundNo") String refundNo);

    /**
     * 聚合该支付单已成功的退款金额，用于累计封顶。FOR UPDATE：RR 下普通读视图更早冻结，并发两笔各自算出「还有额度」会合计超退，锁定读还让第二笔排队。
     * 刻意不统计 4待重试/5需对账：钱还没出去，计入会虚占额度、永久阻断同支付单的其余退款。
     */
    @Select("SELECT IFNULL(SUM(REFUND_AMOUNT), 0) FROM ws_refund "
            + "WHERE PAYMENT_ID = #{paymentId} AND REFUND_STATUS = 2 AND DATA_STATUS = 0 FOR UPDATE")
    long sumSucceededByPaymentForUpdate(@Param("paymentId") Long paymentId);

    /**
     * 受理回填：写入服务方退款单号与请求时间，仅允许 1退款中 且 PROVIDER_REFUND_ID IS NULL——覆盖既有单号等于服务方两笔退款、事实无从归属。
     */
    @Update("UPDATE ws_refund SET PROVIDER_REFUND_ID = #{providerRefundId}, REQUEST_TIME = #{now}, "
            + "VERSION = VERSION + 1, UPDATE_BY = #{opUserId}, UPDATE_TIME = #{now} "
            + "WHERE ID = #{id} AND VERSION = #{expectedVersion} AND DATA_STATUS = 0 "
            + "AND REFUND_STATUS = 1 AND PROVIDER_REFUND_ID IS NULL")
    int fillAcceptance(@Param("id") Long id,
                       @Param("expectedVersion") Integer expectedVersion,
                       @Param("providerRefundId") String providerRefundId,
                       @Param("opUserId") Long opUserId,
                       @Param("now") String now);

    /**
     * 退款成功 CAS：1退款中/4待重试 → 2退款成功。金额与服务方单号进 WHERE：不符即事实不属本单，0 行转人工；SUCCESS_TIME 取事实而非本地时钟。
     */
    @Update("UPDATE ws_refund SET REFUND_STATUS = 2, SUCCESS_TIME = #{successTime}, "
            + "VERSION = VERSION + 1, RETRY_COUNT = RETRY_COUNT + 1, NEXT_RETRY_TIME = NULL, LAST_ERROR = NULL, "
            + "UPDATE_BY = #{opUserId}, UPDATE_TIME = #{now} "
            + "WHERE ID = #{id} AND VERSION = #{expectedVersion} AND DATA_STATUS = 0 "
            + "AND REFUND_STATUS IN (1, 4) AND REFUND_AMOUNT = #{factAmount} "
            + "AND PROVIDER_REFUND_ID = #{providerRefundId}")
    int markSuccess(@Param("id") Long id,
                    @Param("expectedVersion") Integer expectedVersion,
                    @Param("factAmount") Long factAmount,
                    @Param("providerRefundId") String providerRefundId,
                    @Param("successTime") String successTime,
                    @Param("opUserId") Long opUserId,
                    @Param("now") String now);

    /**
     * 落非成功终态：3失败 / 4待重试 / 5需人工对账。REFUND_STATUS &lt;&gt; 2 最关键（R0-7）：迟到失败事实不得把已成功退款降级，否则钱已出去账面却显示没退。
     */
    @Update("UPDATE ws_refund SET REFUND_STATUS = #{toStatus}, NEXT_RETRY_TIME = #{nextRetryTime}, "
            + "LAST_ERROR = #{lastError}, VERSION = VERSION + 1, RETRY_COUNT = RETRY_COUNT + 1, "
            + "UPDATE_BY = #{opUserId}, UPDATE_TIME = #{now} "
            + "WHERE ID = #{id} AND VERSION = #{expectedVersion} AND DATA_STATUS = 0 AND REFUND_STATUS <> 2")
    int markNonSuccess(@Param("id") Long id,
                       @Param("expectedVersion") Integer expectedVersion,
                       @Param("toStatus") Integer toStatus,
                       @Param("nextRetryTime") String nextRetryTime,
                       @Param("lastError") String lastError,
                       @Param("opUserId") Long opUserId,
                       @Param("now") String now);

    /** 锁定订单行。准入判定的证据必须同一事务、锁定原单之后取，否则并发入账在判定与建单之间发出权益仍被按「未入账」放行退款。 */
    @Select("SELECT ID AS id, ORDER_NO AS orderNo, ORDER_TYPE AS orderType, USER_ID AS userId, "
            + "CARD_ID AS cardId, ORDER_STATUS AS orderStatus, ORDER_AMOUNT AS orderAmount, "
            + "PAY_WAY AS payWay, PACKAGE_ID AS packageId, PACKAGE_SNAP AS packageSnap, DATA_STATUS AS dataStatus "
            + "FROM ws_order WHERE ID = #{orderId} FOR UPDATE")
    WsOrder lockOrder(@Param("orderId") Long orderId);

    /** 锁定该订单的支付单。理由同 {@link #lockOrder}。 */
    @Select("SELECT ID AS id, ORDER_ID AS orderId, ORDER_NO AS orderNo, PAY_AMOUNT AS payAmount, "
            + "PAY_STATUS AS payStatus, PAY_SOURCE AS paySource, CURRENCY AS currency, "
            + "DATA_STATUS AS dataStatus FROM ws_payment "
            + "WHERE ORDER_ID = #{orderId} AND DATA_STATUS = 0 ORDER BY ID FOR UPDATE")
    List<WsPayment> lockPaymentsByOrder(@Param("orderId") Long orderId);

    /**
     * 是否存在充值入账流水。按业务幂等键查（uk_wallet_flow_biz_key 全库唯一，与包A DeliveryConsumeFlow 同口径）；
     * 刻意不带 DATA_STATUS=0：被删流水同样证明发放过，否则「删流水再退款」成后门。
     */
    @Select("SELECT COUNT(*) FROM ws_wallet_flow WHERE BIZ_IDEMPOTENCY_KEY = #{bizKey}")
    long countRechargeFlowByBizKey(@Param("bizKey") String bizKey);

    /** 是否存在由该订单发出的水卡（同样不带 DATA_STATUS 过滤：卡被删不代表权益没发过）。 */
    @Select("SELECT COUNT(*) FROM ws_card WHERE ISSUE_ORDER_ID = #{orderId}")
    long countIssuedCardByOrder(@Param("orderId") Long orderId);
}
