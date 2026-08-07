package com.jbk.serve.service.aftersale.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.delivery.WsDeliveryTaskMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.service.aftersale.IAfterSaleActionTxService;
import com.jbk.serve.service.aftersale.IDeliveryCancelService;
import com.jbk.serve.service.delivery.DeliveryLinkGuard;
import com.jbk.serve.service.delivery.DeliveryTransitions;
import com.jbk.serve.service.delivery.IDeliveryOrderTxService;
import com.jbk.tool.consts.aftersale.AfterSaleEnum.ActionStatus;
import com.jbk.tool.consts.delivery.DeliveryEnum;
import com.jbk.tool.data.aftersale.bo.DeliveryCancelBo;
import com.jbk.tool.data.aftersale.po.WsAfterSaleAction;
import com.jbk.tool.data.delivery.po.WsDeliveryTask;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 待接单取消编排实现（E2E-04 包A）。
 *
 * <p><b>本类没有 {@code @Transactional}，且必须没有。</b>它按固定顺序驱动三段独立提交的事务：
 * 业务段 → 认领段 → 资金段。加上类级事务会把三段吞并成一段，认领与失败落痕随资金回滚一起消失，
 * 失败动作永远停在「待执行」且无任何痕迹（理由详见 {@link IDeliveryCancelService} 与
 * {@code IAfterSaleActionTxService.claimIndependent}）。</p>
 *
 * <p>本类<b>不做任何业务判定</b>：能否取消由业务段事务在 CAS 里裁决，返还额度由售后策略与额度上限
 * 算定，返还是否成功由资金段事务裁决。这里只读一次订单/任务，把「订单不存在 / 已被接单」翻译成
 * 一句用户看得懂的话——同一批判定会在事务内原样重做一遍，本层的结论一概不作数。</p>
 *
 * @author dakang
 * @since 2026-07-29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryCancelServiceImpl implements IDeliveryCancelService {

    /** 资金已落卡。只有 executeInTx 正常返回才允许说这句话。 */
    private static final String MSG_REFUNDED = "配送订单已取消，退款已返还至您的水卡。";

    /**
     * 订单已取消但资金结果未知（认领落空或返还失败）。
     * <p>必须与 {@link #MSG_REFUNDED} 可区分：把在途退款说成已到账，用户就不会去核对，
     * 而这笔钱正卡在「需人工对账」上等人处理。</p>
     */
    private static final String MSG_PROCESSING = "配送订单已取消，退款处理中；如未到账请联系客服。";

    private final IDeliveryOrderTxService deliveryOrderTxService;
    private final IAfterSaleActionTxService afterSaleActionTxService;
    private final WsOrderMapper orderMapper;
    private final WsDeliveryTaskMapper taskMapper;

    @Override
    public String cancelPendingOrder(DeliveryCancelBo bo, Long userId) {
        if (ObjectUtil.isNull(userId) || userId <= 0) {
            throw new JbkException("会话用户非法");
        }
        // 三段事务共用同一个业务时钟：订单、任务、台账、流水与审计的时间轴不允许分叉
        String now = DateUtils.time();

        // ① CAS 前的只读断言：只为给出准确话术，权威判定在业务段事务内重做
        WsOrder order = requireOwnOrder(bo, userId);
        WsDeliveryTask task = taskMapper.selectOne(Wrappers.lambdaQuery(WsDeliveryTask.class)
                .eq(WsDeliveryTask::getOrderId, order.getId()));
        if (ObjectUtil.isNull(task)) {
            throw new JbkException("配送任务不存在，无法取消");
        }
        // 共键 + 可履约栅栏（ORDER_TYPE=3、归属一致、外键闭环、订单精确 2已支付）
        DeliveryLinkGuard.requireFulfillable(order, task);
        if (!DeliveryTransitions.allowed(task.getTaskStatus(), DeliveryEnum.TaskStatus.CANCELLED.getValue())) {
            throw new JbkException("配送员已接单或任务已推进，无法自助取消，请联系客服");
        }

        // ② 业务段：任务 1→6 + 订单 2→7 + 登记待执行返还，三步同事务，任一影响行 ≠ 1 即整体回滚
        WsAfterSaleAction action = deliveryOrderTxService.cancelPendingDeliveryOrder(
                order.getId(), userId, now);

        // ③ 认领段（独立提交）：返回 false 说明该动作已被他人认领或已推进（含重放命中既有行），
        //    此时必须放弃执行——继续调 executeInTx 就是对同一笔返还的二次执行
        if (!afterSaleActionTxService.claimIndependent(action.getId(), action.getVersion(), userId, now)) {
            log.info("配送取消：售后动作认领落空，转由人工/重放收口。orderNo={} afterSaleNo={}",
                    order.getOrderNo(), action.getAfterSaleNo());
            return MSG_PROCESSING;
        }

        // ④ 资金段（独立事务 + READ_COMMITTED）：失败时主事务回滚保住「钱没动」，
        //    再用独立事务把「为什么没动」落成终态——两者缺一，失败就成了无痕事件
        try {
            afterSaleActionTxService.executeInTx(action.getId(), userId, now);
            return MSG_REFUNDED;
        } catch (RuntimeException failed) {
            // 落 5需人工对账 而不是 4可重试：包A 没有重试 Worker，落可重试等于永远没人来捡。
            // nextRetryTime 传 null 与目标态互为充要（内核对这条约束有断言）
            afterSaleActionTxService.markTerminalIndependent(action.getId(), action.getVersion(),
                    ActionStatus.RECONCILIATION_REQUIRED.getValue(), null,
                    failureReason(failed), userId, now);
            log.error("配送取消返还失败，已落需人工对账：orderNo={} afterSaleNo={}",
                    order.getOrderNo(), action.getAfterSaleNo(), failed);
            // 不向上抛：订单已经取消成功了，把整个请求判成失败会让用户以为可以重试取消。
            // 资金缺口由「需人工对账」的动作行承接，用户侧只承诺"处理中"
            return MSG_PROCESSING;
        }
    }

    /** 归属先行（铁律6）：非本人订单与不存在同一口径拒绝，不泄露他人订单存在性。 */
    private WsOrder requireOwnOrder(DeliveryCancelBo bo, Long userId) {
        WsOrder order = orderMapper.selectOne(Wrappers.lambdaQuery(WsOrder.class)
                .eq(WsOrder::getOrderNo, StrUtil.trim(bo.getOrderNo())));
        if (ObjectUtil.isNull(order) || ObjectUtil.notEqual(order.getUserId(), userId)) {
            throw new JbkException("订单不存在或无权访问");
        }
        return order;
    }

    /** 失败原因：JbkException 带业务文案，其余异常只留类型——堆栈进日志，不进 LAST_ERROR。 */
    private String failureReason(RuntimeException failed) {
        if (failed instanceof JbkException business) {
            return business.getMsg();
        }
        return "售后返还执行异常：" + failed.getClass().getSimpleName();
    }
}
