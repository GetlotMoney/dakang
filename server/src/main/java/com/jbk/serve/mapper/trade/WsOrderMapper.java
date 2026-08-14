package com.jbk.serve.mapper.trade;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.jbk.tool.data.trade.bo.AdminOrderBo;
import com.jbk.tool.data.mini.vo.MiniAfterSaleProgressVo;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.vo.AdminOrderItemVo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 订单 Mapper
 *
 * @author dakang
 * @since 2026-07-19
 */
@Mapper
public interface WsOrderMapper extends BaseMapper<WsOrder> {

    /**
     * 管理端订单分页（LEFT JOIN 派生展示字段）。afterSaleConfirmed 用 EXISTS 与订单同一条 SQL 带出（逐行查即 N+1）；
     * 来源/状态取值由调用方以 {@code AfterSaleEnum} 传入，SQL 内不写字面量。
     */
    IPage<AdminOrderItemVo> pageAdminOrders(IPage<AdminOrderItemVo> page, @Param("bo") AdminOrderBo bo,
                                            @Param("afterSaleSourceType") Integer afterSaleSourceType,
                                            @Param("afterSaleSuccessStatus") Integer afterSaleSuccessStatus);

    /** 管理端订单详情（同分页 JOIN，按订单ID取单条）。两个售后参数语义同 {@link #pageAdminOrders}，两条路径同一份核账口径。 */
    AdminOrderItemVo selectAdminOrderById(@Param("id") Long id,
                                          @Param("afterSaleSourceType") Integer afterSaleSourceType,
                                          @Param("afterSaleSuccessStatus") Integer afterSaleSuccessStatus);

    /**
     * 用户订单详情的最近一条售后进度。同时限定 USER_ID 与订单关系（原订单按 ORDER_ID、补送子单按 RESULT_ORDER_ID）；
     * 只投影用户可见字段，不下发运营说明/错误原因/退款报文。
     */
    MiniAfterSaleProgressVo selectLatestMiniAfterSaleProgress(@Param("orderId") Long orderId,
                                                              @Param("userId") Long userId);

    /**
     * 成员日限额统计源（CARD-MEMBER）：同一成员同一张卡当天窗口内的取水订单，命中 idx_order_card_user_type_time。
     * 刻意跨全部 DATA_STATUS：删单不得成为绕过日限额的后门；状态→水量映射由 {@code MemberDayLimitMath} 统一负责。
     *
     * @param dayStart 当天窗口起点（含），yyyyMMdd000000
     * @param dayEnd   次日窗口起点（不含），yyyyMMdd000000
     * @return 仅含 ID/ORDER_STATUS/PLAN_ML/ACTUAL_ML 的订单行
     */
    java.util.List<WsOrder> selectMemberDayWaterOrders(@Param("cardId") Long cardId,
                                                       @Param("actorUserId") Long actorUserId,
                                                       @Param("dayStart") String dayStart,
                                                       @Param("dayEnd") String dayEnd);

    /**
     * 同上的锁定读（FOR UPDATE）版本，仅供取水事务的日限额闸：RR 快照在等行锁之前已建立，普通读会集体读到旧占用、突破限额；
     * 锁定读恒取最新已提交，且同成员并发已被成员关系行锁串行化，不引入新死锁面。
     */
    java.util.List<WsOrder> selectMemberDayWaterOrdersForUpdate(@Param("cardId") Long cardId,
                                                                @Param("actorUserId") Long actorUserId,
                                                                @Param("dayStart") String dayStart,
                                                                @Param("dayEnd") String dayEnd);

    /**
     * 当前读锁定订单行。条件 UPDATE 0 行后必须分清「已被推得更远（合法幂等）」与「仍停在原状态（异常）」，普通 SELECT 的快照会混淆两者。
     *
     * @return 未删除的订单行；不存在或已删除返回 null
     */
    @Select("SELECT * FROM ws_order WHERE ID = #{orderId} AND DATA_STATUS = 0 FOR UPDATE")
    WsOrder selectByIdForUpdate(@Param("orderId") Long orderId);
}
