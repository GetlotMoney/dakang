package com.jbk.serve.service.delivery;

import com.jbk.tool.data.aftersale.po.WsAfterSaleAction;
import com.jbk.tool.data.delivery.po.WsDeliveryAutoRule;
import com.jbk.tool.data.delivery.po.WsDeliveryTask;
import com.jbk.tool.data.trade.po.WsOrder;

/**
 * 配送订单资金事务（E2E-03 A2 创单 / 规则1~6/19；E2E-04 包A 待接单取消）。
 *
 * <p>创单单事务链：锁卡校验 → 原子扣减（复用 TradeCardMapper.deductBalance）→ 唯一流水
 * （DELIVERY:&lt;orderNo&gt;）→ 订单(TYPE=3 直接已支付) → 唯一任务 → 站内消息 → 可靠审计；
 * 任一步失败整体回滚零残留。幂等由 uk_order_no / uk_wallet_flow_biz_key /
 * uk_dtask_order / uk_dtask_task_no 四把数据库唯一键收敛，禁止只靠代码查重。</p>
 *
 * <p>取消是创单的镜像动作，落在同一个 Bean：它要读回创单的扣款流水作为返还基准，
 * 流水键约定只有创单路径知道，放别处会复制约定并漂移（铁律⑤）。</p>
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

    /**
     * 自动补货期次单创建（S2）：与 {@link #createPaidDeliveryOrder} 唯一差别是事务内
     * 先锁定读规则并复核当前为启用——取消/暂停与 Worker 扫描并发的裁决点，
     * 快照期被取消/停用的规则在此 fail-closed，绝不在取消后生成新订单。
     *
     * @param ruleId 规则ID（锁定复核对象；订单/任务字段仍由编排层按规则装配）
     */
    WsOrder createAutoRefillPeriodOrder(Long ruleId, WsOrder order, WsDeliveryTask task, String now);

    /**
     * 待接单取消的<b>第一段事务</b>（E2E-04 包A）：任务 1→6 CAS + 订单 2→7 CAS +
     * 登记一笔整单满额的待执行卡内退款——三步同事务，任一影响行 ≠ 1 即整体回滚。
     *
     * <p>本方法一分钱都不动：资金返还由售后内核在独立事务执行（认领与资金写入必须分两段提交）。
     * 中间态「订单已 7 而钱在途」由售后动作落「需人工对账」兜底，用户提示必须说「退款处理中」，
     * 不得说成已到账。取消额度恒等于整单满额，与封顶上限共用同一份维度拆分
     * （{@code AfterSaleStrategy.fullRefund(AfterSaleQuota.caps(...))}），不写 payWay 分支。</p>
     *
     * @param orderId     配送订单ID（编排层已做只读断言，本事务仍会全量重判，不采信其结论）
     * @param actorUserId 会话用户ID（归属条件之一：非本人订单一律按不存在拒绝）
     * @param now         业务时间（yyyyMMddHHmmss，订单/任务/台账/消息/审计同源）
     * @return 登记好的待执行售后动作（含主键与 VERSION，供编排层认领执行）
     */
    WsAfterSaleAction cancelPendingDeliveryOrder(Long orderId, Long actorUserId, String now);
}
