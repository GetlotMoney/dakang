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
 * 退款单 Mapper（E2E-04 包B）。
 *
 * <p>SQL 一律写在注解里且<b>不含任何 SQL 注释</b>：Druid WallFilter 默认 comment-not-allow，
 * 语句体内的行注释会被判为注入并在运行期 500，而 Testcontainers 单测不经 Druid 墙抓不到
 * （包A 已实测踩过一次）。说明写在 Javadoc 里。</p>
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
     * 聚合该支付单<b>已成功</b>的退款金额，用于累计封顶。
     *
     * <p><b>为什么是 FOR UPDATE</b>：MySQL 默认 REPEATABLE READ 下，读视图在本事务的
     * 第一条普通 SELECT 时就冻结。若本聚合发生在锁定退款单之前，并发的另一笔退款
     * 即使已经提交也看不见，两笔各自算出「还有额度」，合起来超过原支付金额——
     * 与包A 的三维额度封顶踩的是同一个坑。锁定读强制看到最新已提交版本，
     * 并让并发的第二笔在此处排队而不是并行判定。</p>
     *
     * <p>刻意不统计 4待重试/5需对账：那两态的钱还没出去，计入会让额度被虚占，
     * 一笔卡住的退款将永久阻断同支付单的其余退款。</p>
     */
    @Select("SELECT IFNULL(SUM(REFUND_AMOUNT), 0) FROM ws_refund "
            + "WHERE PAYMENT_ID = #{paymentId} AND REFUND_STATUS = 2 AND DATA_STATUS = 0 FOR UPDATE")
    long sumSucceededByPaymentForUpdate(@Param("paymentId") Long paymentId);

    /**
     * 受理回填：写入服务方退款单号与请求时间。仅允许在 1退款中 且尚未回填时进行。
     *
     * <p>{@code PROVIDER_REFUND_ID IS NULL} 是前态条件而非可省略的谓词：
     * 重复受理若允许覆盖既有服务方单号，事实回来时就无从判断哪个号才是本单的，
     * 而两个号在服务方那边意味着两笔退款。</p>
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
     * 退款成功 CAS：1退款中/4待重试 → 2退款成功。
     *
     * <p>金额与服务方单号进 WHERE 而不是只进 SET：事实携带的金额若与本地退款单不符，
     * 说明这条事实不属于本单（或本单被改写），此时影响 0 行、调用方转人工，
     * 而不是把一笔金额不符的退款记成成功。</p>
     *
     * <p>{@code SUCCESS_TIME} 取自事实而非本地时钟：对账要的是支付机构那边的成功时刻。</p>
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
     * 落非成功终态：3失败 / 4待重试 / 5需人工对账。
     *
     * <p>{@code REFUND_STATUS <> 2} 是本方法最重要的一条谓词：R0-7「已成功动作不得被迟到失败事实降级」。
     * 一条延迟到达的失败通知若能把已成功的退款改回失败，账面就会显示钱没退，
     * 而钱其实已经出去了——重复退款的经典成因。</p>
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

    /**
     * 锁定订单行。准入判定的四项证据必须在<b>同一事务、锁定原单之后</b>取，
     * 否则并发的入账事务可能在判定与建单之间把权益发出去，而我们仍按「未入账」放行退款。
     */
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
     * 是否存在充值入账流水。
     *
     * <p>按<b>业务幂等键</b>而非 (ORDER_ID, FLOW_TYPE) 查：前者由 uk_wallet_flow_biz_key 保证全库唯一，
     * 天然是「有或没有」；后者依赖「业务上不会有第二条」这一约定，约定一破就退化成模糊判断。
     * 与包A 的 DeliveryConsumeFlow 同一条口径。</p>
     *
     * <p>刻意不带 DATA_STATUS = 0：被逻辑删除的入账流水同样证明权益发放过。
     * 只看未删除的行，等于给「删掉流水再申请全额退款」留了一条后门。</p>
     */
    @Select("SELECT COUNT(*) FROM ws_wallet_flow WHERE BIZ_IDEMPOTENCY_KEY = #{bizKey}")
    long countRechargeFlowByBizKey(@Param("bizKey") String bizKey);

    /**
     * 是否存在由该订单发出的水卡。
     *
     * <p>同样不带 DATA_STATUS 过滤：卡被逻辑删除不代表权益没发过。</p>
     */
    @Select("SELECT COUNT(*) FROM ws_card WHERE ISSUE_ORDER_ID = #{orderId}")
    long countIssuedCardByOrder(@Param("orderId") Long orderId);
}
