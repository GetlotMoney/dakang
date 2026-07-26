package com.jbk.serve.mapper.trade;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.jbk.tool.data.trade.bo.AdminOrderBo;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.vo.AdminOrderItemVo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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
     */
    IPage<AdminOrderItemVo> pageAdminOrders(IPage<AdminOrderItemVo> page, @Param("bo") AdminOrderBo bo);

    /**
     * 管理端订单详情（同分页 JOIN，按订单ID取单条，供追溯抽屉订单本体）。
     */
    AdminOrderItemVo selectAdminOrderById(@Param("id") Long id);

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
}
