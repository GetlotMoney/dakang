package com.jbk.serve.service.delivery;

import com.jbk.tool.data.delivery.po.WsDeliveryAutoRule;
import com.jbk.tool.data.delivery.po.WsDeliveryTask;
import com.jbk.tool.data.trade.po.WsOrder;

/**
 * 配送创单资金事务（E2E-03 A2 / 规则1~6/19）。
 *
 * <p>单事务链：锁卡校验 → 原子扣减（复用 TradeCardMapper.deductBalance）→ 唯一流水
 * （DELIVERY:&lt;orderNo&gt;）→ 订单(TYPE=3 直接已支付) → 唯一任务 → 站内消息 → 可靠审计；
 * 任一步失败整体回滚零残留。幂等由 uk_order_no / uk_wallet_flow_biz_key /
 * uk_dtask_order / uk_dtask_task_no 四把数据库唯一键收敛，禁止只靠代码查重。</p>
 *
 * @author dakang
 * @since 2026-07-23
 */
public interface IDeliveryOrderTxService {

    /**
     * 创建已支付配送订单与唯一履约任务（order/task 由编排层装配好业务字段，不含主键）。
     *
     * @param order    待落库订单（ORDER_TYPE=3、ORDER_STATUS=2、金额=水费+配送费）
     * @param task     待落库任务（TASK_STATUS=1、VERSION=1，与订单共键一致）
     * @param autoRule 自动补货模式下随首单一并冻结的规则（非补货模式传 null）
     * @param now      业务时间（yyyyMMddHHmmss，订单/任务/流水/消息/审计同源）
     * @return 落库后的订单（含主键）
     */
    WsOrder createPaidDeliveryOrder(WsOrder order, WsDeliveryTask task, WsDeliveryAutoRule autoRule, String now);
}
