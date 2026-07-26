package com.jbk.serve.service.trade;

import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.trade.bo.AdminOrderBo;
import com.jbk.tool.data.trade.vo.AdminOrderItemVo;
import com.jbk.tool.data.trade.vo.AdminOrderTraceVo;

/**
 * 管理端订单查询服务（PC 订单中心，只读）。
 * <p>
 * 只读接口，不触碰资金：资金增减一律走下单/结算的原子扣减+流水（铁律1）。
 * 需 DRIVER_MANAGE 会话（Controller 层声明），管理端可见全量订单。
 * </p>
 *
 * @author dakang
 * @since 2026-07-20
 */
public interface IAdminOrderService {

    /**
     * 分页查询订单（订单号/类型/状态/用户关键词筛选，派生用户/水站/设备/出水口/卡展示字段）。
     */
    PageDataVo<AdminOrderItemVo> pageOrders(AdminOrderBo bo);

    /**
     * 订单全链路追溯（订单本体 + 指令追溯 + 流水追溯）。
     *
     * @param id 订单ID
     * @return 追溯视图；订单不存在时抛业务异常
     */
    AdminOrderTraceVo getOrderTrace(Long id);
}
