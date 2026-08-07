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
     * 管理端订单分页（LEFT JOIN 派生用户/水站/设备/出水口/卡展示字段，支持订单号/类型/状态/用户关键词筛选）。
     *
     * <p>{@code afterSaleConfirmed} 用 EXISTS 子查询与订单同一条 SQL 带出（E2E-04 包A），
     * 不在 Service 层逐行查库——订单-指令状态矩阵每一行都要用它，逐行查即是 N+1。
     * 来源/状态取值由调用方以 {@code AfterSaleEnum} 传入，SQL 内不写字面量（单一出处）。</p>
     *
     * @param afterSaleSourceType    售后来源=取水异常核账（{@code AfterSaleEnum.SourceType.WATER_ABNORMAL}）
     * @param afterSaleSuccessStatus 售后执行状态=已完成（{@code AfterSaleEnum.ActionStatus.SUCCESS}）
     */
    IPage<AdminOrderItemVo> pageAdminOrders(IPage<AdminOrderItemVo> page, @Param("bo") AdminOrderBo bo,
                                            @Param("afterSaleSourceType") Integer afterSaleSourceType,
                                            @Param("afterSaleSuccessStatus") Integer afterSaleSuccessStatus);

    /**
     * 管理端订单详情（同分页 JOIN，按订单ID取单条，供追溯抽屉订单本体）。
     * <p>两个售后参数语义同 {@link #pageAdminOrders}：读写两条路径必须用同一份核账确认口径。</p>
     */
    AdminOrderItemVo selectAdminOrderById(@Param("id") Long id,
                                          @Param("afterSaleSourceType") Integer afterSaleSourceType,
                                          @Param("afterSaleSuccessStatus") Integer afterSaleSuccessStatus);

    /**
     * 用户订单详情的最近一条售后进度。
     *
     * <p>查询同时限定 {@code USER_ID} 与订单关系；原订单按 {@code ORDER_ID} 命中，补送子单按
     * {@code RESULT_ORDER_ID} 命中。只投影用户可见字段，不下发运营说明、错误原因或退款报文。</p>
     */
    MiniAfterSaleProgressVo selectLatestMiniAfterSaleProgress(@Param("orderId") Long orderId,
                                                              @Param("userId") Long userId);

    /**
     * 成员日限额统计源（CARD-MEMBER）：同一成员（actorUserId）在同一张卡上、
     * 当天时间窗内的全部取水订单（ORDER_TYPE=1）。命中组合索引 idx_order_card_user_type_time。
     *
     * <p><b>刻意跨全部 DATA_STATUS</b>：被逻辑删除的取水订单仍然真实出过水，
     * 过滤掉会让删单变成绕过日限额的后门；占用口径的状态→水量映射由
     * {@code MemberDayLimitMath} 统一负责，这里只取数不加工。</p>
     *
     * @param cardId      卡ID
     * @param actorUserId 成员用户ID（订单 USER_ID=实际使用人）
     * @param dayStart    当天窗口起点（含），yyyyMMdd000000
     * @param dayEnd      次日窗口起点（不含），yyyyMMdd000000
     * @return 仅含 ID/ORDER_STATUS/PLAN_ML/ACTUAL_ML 的订单行
     */
    java.util.List<WsOrder> selectMemberDayWaterOrders(@Param("cardId") Long cardId,
                                                       @Param("actorUserId") Long actorUserId,
                                                       @Param("dayStart") String dayStart,
                                                       @Param("dayEnd") String dayEnd);

    /**
     * 同上的<b>锁定读（FOR UPDATE）</b>版本，仅供取水事务的日限额闸使用。
     *
     * <p>为什么必须锁定读：REPEATABLE READ 下普通 SELECT 走事务快照，而快照在等待
     * uk_card_member_user 行锁<b>之前</b>就已建立——拿到锁后再用快照统计，看不见排队前序
     * 事务刚提交的订单，并发下会集体读到旧占用、突破限额。锁定读永远读最新已提交版本，
     * 且此时同成员并发已被成员关系行锁串行化，不引入新的死锁面。</p>
     */
    java.util.List<WsOrder> selectMemberDayWaterOrdersForUpdate(@Param("cardId") Long cardId,
                                                                @Param("actorUserId") Long actorUserId,
                                                                @Param("dayStart") String dayStart,
                                                                @Param("dayEnd") String dayEnd);

    /**
     * 当前读锁定订单行（{@code SELECT ... FOR UPDATE}）。
     *
     * <p>条件 UPDATE 影响 0 行之后必须能分清「订单已被推得更远」（合法幂等）与「订单还停在
     * 本该推进的状态」（异常）。普通 SELECT 读的是事务快照，看到的可能仍是推进前的旧值，
     * 两种情形会被混为一谈。逻辑删除行不返回。</p>
     *
     * @param orderId 订单ID
     * @return 未删除的订单行；不存在或已删除返回 null
     */
    @Select("SELECT * FROM ws_order WHERE ID = #{orderId} AND DATA_STATUS = 0 FOR UPDATE")
    WsOrder selectByIdForUpdate(@Param("orderId") Long orderId);
}
