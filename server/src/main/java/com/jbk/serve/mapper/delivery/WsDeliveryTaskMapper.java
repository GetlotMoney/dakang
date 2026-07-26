package com.jbk.serve.mapper.delivery;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jbk.tool.data.delivery.bo.AdminDeliveryTaskBo;
import com.jbk.tool.data.delivery.po.WsDeliveryTask;
import com.jbk.tool.data.delivery.vo.AdminDeliveryTaskItemVo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 配送任务 Mapper（E2E-03 包A）。
 * <p>可接任务池查询在 XML：过滤条件与接单事务同源
 * （待接单+无人认领+服务范围+非本人下单+预约到点+关联订单可履约），
 * 少一条过滤就存在「列表可见但接单必拒」或更糟的「不可履约任务被接走」。</p>
 *
 * @author dakang
 * @since 2026-07-23
 */
@Mapper
public interface WsDeliveryTaskMapper extends BaseMapper<WsDeliveryTask> {

    /**
     * 配送员可接任务池（铁律6/7 在 SQL 层强制）：
     * TASK_STATUS=1、COURIER_ID 为空、水站在服务范围、下单人≠当前配送员、
     * 预约单到点（SCHEDULED_TIME 为空或 <= now）、关联订单 存在+ORDER_TYPE=3+共键一致+ORDER_STATUS=2。
     *
     * @param courierUserId 当前配送员的用户ID（排除自配送）
     * @param stationIds    配送员服务水站集合（空集时调用方必须直接拒绝，不得进本查询）
     * @param now           yyyyMMddHHmmss（预约到点判定）
     */
    List<WsDeliveryTask> selectAvailableTasks(@Param("courierUserId") Long courierUserId,
                                              @Param("stationIds") List<Long> stationIds,
                                              @Param("now") String now);

    /**
     * 管理端配送任务分页（E2E-03 包C）：任务 join 订单/用户/配送员/水站派生展示列。
     * 手机号取原值别名 *Raw，由 Service 层统一 PhoneMask 脱敏后下发。
     */
    IPage<AdminDeliveryTaskItemVo> pageAdminTasks(Page<AdminDeliveryTaskItemVo> page,
                                                  @Param("bo") AdminDeliveryTaskBo bo);

    /** 管理端配送任务详情（与分页同一列集，按任务ID单查）。 */
    AdminDeliveryTaskItemVo selectAdminTaskById(@Param("id") Long id);
}
