package com.jbk.serve.service.aftersale.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.aftersale.WsAfterSaleActionMapper;
import com.jbk.serve.mapper.delivery.WsDeliveryAppealMapper;
import com.jbk.serve.mapper.delivery.WsDeliveryTaskMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.service.aftersale.IResendFulfillmentTxService;
import com.jbk.serve.service.delivery.DeliveryOrderNo;
import com.jbk.tool.consts.aftersale.AfterSaleEnum;
import com.jbk.tool.consts.delivery.DeliveryEnum;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.data.aftersale.po.WsAfterSaleAction;
import com.jbk.tool.data.delivery.po.WsDeliveryAppeal;
import com.jbk.tool.data.delivery.po.WsDeliveryTask;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.exception.JbkException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 补送履约事务实现（E2E-04 包C）。
 *
 * <h3>依赖清单即安全边界：本类没有扣款能力</h3>
 * <p>刻意<b>不注入</b> {@code TradeCardMapper} 与 {@code WsWalletFlowMapper}。
 * 补送是履约补偿而非二次交易，用户已经为原单付过钱；写一条扣款流水会让账本多出
 * 一笔用户根本没付的消费，写一次扣卡则是直接从用户卡里再拿一次钱。
 * 把这两种能力从依赖清单里删掉，任何想「顺手扣一下」的改动都必须先加字段和构造参数——
 * 那是评审一眼能看见的动作。</p>
 *
 * <h3>数量与水种只能收窄，不能放大</h3>
 * <p>补送数量取自裁决的 {@code APPROVED_COUNT}，且必须 ≤ 原任务计划数量；
 * 水种、规格、地址、收货电话一律复用原任务快照。
 * 任务书明令「不允许扩大数量或更换水种」——放开任一条，
 * 一次裁决就能变成「按任意数量白送任意水种」。</p>
 *
 * @author dakang
 * @since 2026-07-29
 */
@Service
@RequiredArgsConstructor
public class ResendFulfillmentTxServiceImpl implements IResendFulfillmentTxService {

    private static final int TIME_LEN = 14;

    private final WsAfterSaleActionMapper actionMapper;
    private final WsDeliveryAppealMapper appealMapper;
    private final WsDeliveryTaskMapper taskMapper;
    private final WsOrderMapper orderMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long generate(Long afterSaleId, Long opUserId, String now) {
        requireArgs(afterSaleId, opUserId, now);

        WsAfterSaleAction action = actionMapper.selectByIdIncludingDeleted(afterSaleId);
        if (ObjectUtil.isNull(action)) {
            throw new JbkException("售后动作不存在，无法生成补送");
        }
        if (ObjectUtil.notEqual(action.getDataStatus(), 0)) {
            throw new JbkException("售后动作已被逻辑删除，拒绝生成补送");
        }
        if (ObjectUtil.notEqual(action.getActionType(), AfterSaleEnum.ActionType.RESEND.getValue())) {
            throw new JbkException("该售后动作不是补送类型，拒绝生成补送单");
        }
        // 幂等短路：已生成过直接返回既有子订单。这只是省一次唯一键异常，
        // 真正的幂等由下面三道物理闸保证（铁律②）。
        if (action.getResultOrderId() != null) {
            return action.getResultOrderId();
        }
        if (ObjectUtil.notEqual(action.getSourceType(), AfterSaleEnum.SourceType.DELIVERY_APPEAL.getValue())) {
            throw new JbkException("补送只能来自配送申诉裁决");
        }

        WsDeliveryAppeal appeal = appealMapper.selectById(action.getSourceId());
        if (ObjectUtil.isNull(appeal)) {
            throw new JbkException("补送对应的申诉不存在");
        }
        WsDeliveryTask origin = taskMapper.selectById(appeal.getTaskId());
        if (ObjectUtil.isNull(origin)) {
            throw new JbkException("补送对应的原配送任务不存在");
        }
        // 共键核验：申诉、原任务、售后动作必须指向同一张原订单。
        // 任一处错位都意味着我们要按别人的单子补送，fail-closed。
        if (ObjectUtil.notEqual(origin.getOrderId(), action.getOrderId())
                || ObjectUtil.notEqual(appeal.getOrderId(), action.getOrderId())) {
            throw new JbkException("申诉、原任务与售后动作的订单共键错位，拒绝生成补送");
        }

        int count = requireResendCount(action, origin);

        WsOrder origOrder = orderMapper.selectById(action.getOrderId());
        if (ObjectUtil.isNull(origOrder)) {
            throw new JbkException("补送对应的原订单不存在");
        }

        // ── 闸① 子订单号由 RESEND:APPEAL:<appealId> 确定性派生（单一出处见
        //    DeliveryOrderNo.deriveResend）：重复生成必然撞 uk_order_no
        String childOrderNo = DeliveryOrderNo.deriveResend(action.getUserId(), appeal.getId());
        WsOrder child = buildZeroAmountChildOrder(origOrder, action, childOrderNo, opUserId, now);
        if (orderMapper.insert(child) != 1 || child.getId() == null) {
            throw new JbkException("补送子订单创建失败");
        }

        // ── 闸③ 新订单上的 uk_dtask_order 保证一单至多一条任务
        WsDeliveryTask childTask = buildChildTask(origin, child, count, opUserId, now);
        if (taskMapper.insert(childTask) != 1 || childTask.getId() == null) {
            throw new JbkException("补送任务创建失败");
        }

        // ── 闸② RESULT_ORDER_ID IS NULL 的 CAS
        int linked = actionMapper.linkResendResult(action.getId(), action.getVersion(),
                child.getId(), childTask.getId(), opUserId, now);
        if (linked != 1) {
            // 影响 0 行 = 已被并发生成过或动作状态已变。整事务回滚，
            // 刚插入的子订单与任务一并撤销——绝不留下一张没人认领的补送单。
            throw new JbkException("补送结果回填失败（已被并发生成或动作状态已变），本次生成已回滚");
        }
        return child.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean completeOnSigned(Long taskId, Long opUserId, String now) {
        if (taskId == null || taskId <= 0 || now == null || now.length() != TIME_LEN) {
            throw new JbkException("补送回签入参非法");
        }
        WsAfterSaleAction action = actionMapper.selectOne(Wrappers.lambdaQuery(WsAfterSaleAction.class)
                .eq(WsAfterSaleAction::getResultTaskId, taskId)
                .eq(WsAfterSaleAction::getActionType, AfterSaleEnum.ActionType.RESEND.getValue())
                .last("LIMIT 1"));
        if (ObjectUtil.isNull(action)) {
            // 普通配送任务签收会走到这里：不是错误，只是与补送无关。
            return false;
        }
        // CAS 带 RESULT_TASK_ID = 本次签收的任务：签收事务只能完成它自己那条补送动作。
        // 影响 0 行说明动作已终态或共键错位，此时**不抛出**——签收本身是成功的，
        // 把它回滚掉会让用户已经收到的水在系统里显示成没送到。留给对账。
        return actionMapper.markResendSucceeded(action.getId(), taskId, opUserId, now) == 1;
    }

    // ==================== 构造 ====================

    /**
     * 补送数量：取裁决批准数，且必须落在 [1, 原计划数量] 内。
     *
     * <p>上界取<b>原任务计划数量</b>而非实际签收数量：少送 2 桶就补 2 桶，
     * 补到超过原计划就成了白送。裁决期已由 AfterSaleStrategy 校过一次上界，
     * 这里是履约期的二次钉死——两处校验之间隔着一次人工操作与一次事务边界，
     * 中间任何改写都必须在真正生成单子之前被拦下。</p>
     */
    private int requireResendCount(WsAfterSaleAction action, WsDeliveryTask origin) {
        Integer approved = action.getApprovedCount();
        if (approved == null || approved < 1) {
            throw new JbkException("补送批准数量非法（" + approved + "），拒绝生成补送");
        }
        Integer planned = origin.getDeliveryCount();
        if (planned == null || planned < 1) {
            throw new JbkException("原任务计划数量异常，拒绝生成补送");
        }
        if (approved > planned) {
            throw new JbkException("补送数量 " + approved + " 超过原计划 " + planned + "，不得扩大数量");
        }
        return approved;
    }

    /**
     * 零金额子订单。
     *
     * <p>{@code ORDER_AMOUNT=0} 且不写任何扣款流水：补送不是二次交易。
     * {@code PAY_WAY} 复用原单只为让追溯页显示得出「这是哪种支付方式的单子的补送」，
     * 它在本单上不驱动任何扣款逻辑——本类根本没有扣款能力。</p>
     *
     * <p>订单直接落 {@code 2已支付}：补送单没有支付环节，停在待支付会让它出现在
     * 用户的待付款列表里，而它根本不需要付钱。</p>
     */
    private WsOrder buildZeroAmountChildOrder(WsOrder origOrder, WsAfterSaleAction action,
                                              String childOrderNo, Long opUserId, String now) {
        WsOrder child = new WsOrder();
        child.setOrderNo(childOrderNo);
        child.setOrderType(TradeEnum.OrderType.DELIVERY.getValue());
        child.setUserId(action.getUserId());
        child.setCardId(origOrder.getCardId());
        child.setStationId(origOrder.getStationId());
        child.setOrderAmount(0L);
        child.setPayWay(origOrder.getPayWay());
        child.setOrderStatus(TradeEnum.OrderStatus.PAID.getValue());
        child.setPackageSnap(origOrder.getPackageSnap());
        child.setDataStatus(0);
        child.setCreateBy(opUserId);
        child.setCreateTime(now);
        child.setUpdateBy(opUserId);
        child.setUpdateTime(now);
        return child;
    }

    /**
     * 补送任务：水种、规格、地址、电话全部复用原任务快照，金额一律 0。
     *
     * <p>复用而非重新取值是刻意的：从别处重新查水种或地址，等于给「补送时换了个水种/
     * 送到别的地址」留了入口，而这两件事在任务书里都是明令禁止的。</p>
     */
    private WsDeliveryTask buildChildTask(WsDeliveryTask origin, WsOrder child, int count,
                                          Long opUserId, String now) {
        WsDeliveryTask task = new WsDeliveryTask();
        task.setTaskNo(DeliveryOrderNo.deriveTaskNo(child.getOrderNo()));
        task.setOrderId(child.getId());
        task.setUserId(origin.getUserId());
        task.setStationId(origin.getStationId());
        task.setWaterTypeId(origin.getWaterTypeId());
        task.setWaterType(origin.getWaterType());
        task.setContainerSpec(origin.getContainerSpec());
        task.setDeliveryCount(count);
        // 补送不回收空桶：原单的回收在原任务里已经完成或已计划，
        // 在补送单上再记一次会让回收数量凭空翻倍。
        task.setPlanReturnCount(0);
        task.setWaterAmount(0L);
        task.setDeliveryFee(0L);
        task.setReceiveAddress(origin.getReceiveAddress());
        task.setReceivePhone(origin.getReceivePhone());
        task.setTaskStatus(DeliveryEnum.TaskStatus.PENDING.getValue());
        task.setVersion(1);
        task.setDataStatus(0);
        task.setCreateBy(opUserId);
        task.setCreateTime(now);
        task.setUpdateBy(opUserId);
        task.setUpdateTime(now);
        return task;
    }

    private void requireArgs(Long afterSaleId, Long opUserId, String now) {
        if (afterSaleId == null || afterSaleId <= 0) {
            throw new JbkException("售后动作ID非法");
        }
        if (opUserId == null || opUserId <= 0) {
            throw new JbkException("补送发起人缺失，拒绝生成补送");
        }
        if (now == null || now.length() != TIME_LEN) {
            throw new JbkException("业务时间格式非法，拒绝生成补送");
        }
    }
}
