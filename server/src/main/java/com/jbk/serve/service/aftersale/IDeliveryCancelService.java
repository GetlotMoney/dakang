package com.jbk.serve.service.aftersale;

import com.jbk.tool.data.aftersale.bo.DeliveryCancelBo;

/**
 * 待接单取消编排（E2E-04 包A，售后来源 1配送取消）。
 *
 * <p><b>本接口是编排层，实现类不带 {@code @Transactional}。</b>取消跨越三段互不相同的事务边界，
 * 它们<b>必须分开提交</b>，把它们裹进一个事务就是 P0#1 的病灶：</p>
 * <ol>
 *   <li><b>业务段</b>（{@code IDeliveryOrderTxService.cancelPendingDeliveryOrder}）：
 *       任务 1→6 + 订单 2→7 + 登记待执行返还，三步同生共死。</li>
 *   <li><b>认领段</b>（{@code claimIndependent}，REQUIRES_NEW）：认领这一事实必须先于资金结果落库。
 *       与资金同事务时，资金失败回滚会把状态复原成「待执行」，而失败落痕的 CAS 要求前态是
 *       「执行中」→ 影响行恒 0 → 动作无终态、无错因，且因为仍是待执行而可被无限重放。</li>
 *   <li><b>资金段</b>（{@code executeInTx}，REQUIRES_NEW + READ_COMMITTED）：锁卡、封顶、返还、流水。</li>
 * </ol>
 *
 * <p><b>中间态是本链路刻意承担的代价，不是缺陷</b>：业务段提交而资金段失败时，
 * 订单已是 7已退款 而钱尚在卡外，售后动作落「需人工对账」并留下 {@code LAST_ERROR}。
 * 反过来（先退钱再改状态）会让两个并发取消各自退一次款，那是不可逆的资金事故；
 * 状态先行至少保证「最多退一次」，剩下的缺口由人工对账补齐。因此用户提示必须说
 * <b>「退款处理中，如未到账请联系客服」</b>，绝不能说成"已到账"。</p>
 *
 * @author dakang
 * @since 2026-07-29
 */
public interface IDeliveryCancelService {

    /**
     * 用户自助取消待接单的配送订单，并推进整单满额返还。
     *
     * @param bo     取消入参（只有订单号）
     * @param userId 会话用户ID（归属过滤，铁律6：不接受前端自报身份）
     * @return 面向用户的结果文案：资金已落卡与「退款处理中」两种口径必须可区分
     */
    String cancelPendingOrder(DeliveryCancelBo bo, Long userId);
}
