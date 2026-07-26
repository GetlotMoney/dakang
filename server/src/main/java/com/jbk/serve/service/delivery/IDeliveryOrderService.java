package com.jbk.serve.service.delivery;

import com.jbk.tool.data.delivery.bo.DeliveryCreateBo;
import com.jbk.tool.data.delivery.po.WsDeliveryTask;
import com.jbk.tool.data.trade.po.WsOrder;

/**
 * 配送创单编排（E2E-03 A2：requestId 幂等 + 校验 + 委托资金事务）与自动补货生成。
 *
 * @author dakang
 * @since 2026-07-23
 */
public interface IDeliveryOrderService {

    /** 创建结果：订单 + 唯一任务（幂等命中时返回既有记录，不重复扣款不重复建任务）。 */
    record CreatedDelivery(WsOrder order, WsDeliveryTask task) {
    }

    /**
     * 创建配送订单（即时/预约/自动补货三种方式；userId 只从会话取，铁律6）。
     * 同 userId+requestId 重复调用恒返回同一订单与任务（规则4）。
     */
    CreatedDelivery createDeliveryOrder(DeliveryCreateBo bo, Long userId);

    /**
     * 生成到期的自动补货订单（规则19：每规则每周期恰一单，幂等键含规则与期序）。
     * 无调度器——本方法是幂等生成入口，由后续运维触发方式调用；重复调用不重复生成。
     *
     * @param now 判定时间（yyyyMMddHHmmss）
     * @return 本次实际新生成的订单数
     */
    int generateAutoRefillDueOrders(String now);
}
