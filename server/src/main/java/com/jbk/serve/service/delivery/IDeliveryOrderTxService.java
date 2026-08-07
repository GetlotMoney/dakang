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
 * <p>取消是创单的镜像动作，落在同一个 Bean：它要读回创单写下的那条扣款流水作为返还基准，
 * 而「这条流水的业务幂等键长什么样」只有创单路径知道。放到别处就要复制那份键约定，
 * 两份约定从落地第一天起就可能漂移（铁律⑤）。</p>
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
     * 待接单取消的<b>第一段事务</b>（E2E-04 包A）：任务 1→6 CAS + 订单 2→7 CAS +
     * 登记一笔整单满额的待执行卡内退款——三步同事务，任一影响行 ≠ 1 即整体回滚。
     *
     * <p><b>本方法一分钱都不动。</b>资金返还由售后返还内核在<b>独立事务</b>里执行，
     * 因为认领与资金写入必须分两段提交（合并即是「资金失败回滚把认领一起复原、
     * 失败证据无处落脚」的病灶）。编排层据此承担一个中间态：本事务提交而返还失败时，
     * 订单已是 7已退款 而钱尚在途，售后动作落「需人工对账」，用户提示必须说明
     * 「退款处理中，如未到账请联系客服」——把它说成"已退款到账"才是真正的事故。</p>
     *
     * <p>取消额度恒等于整单满额，且与封顶上限共用同一份维度拆分
     * （{@code AfterSaleStrategy.fullRefund(AfterSaleQuota.caps(...))}），
     * 此处不写任何 payWay 分支：那会让「一笔钱怎么拆成水品/配送费/水量三列」出现第二份实现。</p>
     *
     * @param orderId     配送订单ID（编排层已做只读断言，本事务仍会全量重判，不采信其结论）
     * @param actorUserId 会话用户ID（归属条件之一：非本人订单一律按不存在拒绝）
     * @param now         业务时间（yyyyMMddHHmmss，订单/任务/台账/消息/审计同源）
     * @return 登记好的待执行售后动作（含主键与 VERSION，供编排层认领执行）
     */
    WsAfterSaleAction cancelPendingDeliveryOrder(Long orderId, Long actorUserId, String now);
}
