package com.jbk.serve.mapper.dashboard;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 运营总览聚合查询（只读；手写 SQL，不走 BaseMapper）。
 *
 * <p>不继承 BaseMapper 的原因与身份 Mapper 相同：聚合口径必须显式可审计，
 * 每条 SQL 自带 {@code DATA_STATUS = 0}，不依赖 {@code @TableLogic} 的隐式过滤。
 * 时间边界一律由服务层生成后传入（yyyyMMdd / yyyyMMddHHmmss 字符串前缀比较，
 * 与全仓 varchar(14) 时间口径一致），SQL 内不做任何时区运算。</p>
 *
 * @author dakang
 * @since 2026-08-02
 */
@Mapper
public interface DashboardStatsMapper {

    /** 今日取水订单分状态计数：key=ORDER_STATUS, value=数量。 */
    @Select("SELECT ORDER_STATUS AS k, COUNT(*) AS v FROM ws_order "
            + "WHERE DATA_STATUS = 0 AND ORDER_TYPE = 1 AND CREATE_TIME LIKE CONCAT(#{day}, '%') "
            + "GROUP BY ORDER_STATUS")
    List<Map<String, Object>> countTodayWaterOrdersByStatus(@Param("day") String day);

    /** 今日营收(分)：取水+配送、已支付族（2/3/4/6/8），口径见 DashboardOverviewVo 类注释。 */
    @Select("SELECT COALESCE(SUM(ORDER_AMOUNT), 0) FROM ws_order "
            + "WHERE DATA_STATUS = 0 AND ORDER_TYPE IN (1, 3) "
            + "AND ORDER_STATUS IN (2, 3, 4, 6, 8) AND CREATE_TIME LIKE CONCAT(#{day}, '%')")
    long sumTodayRevenueFen(@Param("day") String day);

    /** 设备在线数（ONLINE_STATUS=1）。 */
    @Select("SELECT COUNT(*) FROM ws_device WHERE DATA_STATUS = 0 AND ONLINE_STATUS = 1")
    long countOnlineDevices();

    /** 设备总数。 */
    @Select("SELECT COUNT(*) FROM ws_device WHERE DATA_STATUS = 0")
    long countTotalDevices();

    /** 24h 指令终态计数：key=CMD_STATUS(4成功/5失败/6超时), value=数量。 */
    @Select("SELECT CMD_STATUS AS k, COUNT(*) AS v FROM ws_command "
            + "WHERE DATA_STATUS = 0 AND CMD_STATUS IN (4, 5, 6) AND CREATE_TIME >= #{since} "
            + "GROUP BY CMD_STATUS")
    List<Map<String, Object>> countCommandTerminalsSince(@Param("since") String since);

    /** 异常待补偿订单数（状态6，全量待办口径）。 */
    @Select("SELECT COUNT(*) FROM ws_order WHERE DATA_STATUS = 0 AND ORDER_STATUS = 6")
    long countPendingExceptionOrders();

    /** 待处理申诉数（状态1）。 */
    @Select("SELECT COUNT(*) FROM ws_delivery_appeal WHERE DATA_STATUS = 0 AND APPEAL_STATUS = 1")
    long countPendingAppeals();

    /** 待审核配送员数（状态1）。 */
    @Select("SELECT COUNT(*) FROM ws_courier WHERE DATA_STATUS = 0 AND COURIER_STATUS = 1")
    long countPendingCouriers();

    /** 待接单配送任务数（状态1）。 */
    @Select("SELECT COUNT(*) FROM ws_delivery_task WHERE DATA_STATUS = 0 AND TASK_STATUS = 1")
    long countPendingDeliveries();

    /** 起始日以来按日×类型的订单量：key=日期(yyyyMMdd)+类型, 缺日由服务层补零。 */
    @Select("SELECT LEFT(CREATE_TIME, 8) AS d, ORDER_TYPE AS t, COUNT(*) AS v FROM ws_order "
            + "WHERE DATA_STATUS = 0 AND CREATE_TIME >= #{sinceDay} "
            + "GROUP BY LEFT(CREATE_TIME, 8), ORDER_TYPE")
    List<Map<String, Object>> countOrdersByDayAndType(@Param("sinceDay") String sinceDay);
}
