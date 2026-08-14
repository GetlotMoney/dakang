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
 * 补送履约事务实现（E2E-04 包C）。依赖清单即安全边界：刻意不注入 {@code TradeCardMapper} 与
 * {@code WsWalletFlowMapper}——补送是履约补偿而非二次交易，本类没有扣款能力。
 * 数量与水种只能收窄不能放大（任务书明令）：数量取裁决 {@code APPROVED_COUNT} 且 ≤ 原计划，
 * 水种/规格/地址/电话一律复用原任务快照。
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
        // 幂等短路只是省一次唯一键异常，真正的幂等由下面三道物理闸保证（铁律②）
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
        // 共键核验：申诉、原任务、售后动作必须指向同一张原订单，错位即按别人的单子补送，fail-closed
        if (ObjectUtil.notEqual(origin.getOrderId(), action.getOrderId())
                || ObjectUtil.notEqual(appeal.getOrderId(), action.getOrderId())) {
            throw new JbkException("申诉、原任务与售后动作的订单共键错位，拒绝生成补送");
        }

        int count = requireResendCount(action, origin);

        WsOrder origOrder = orderMapper.selectById(action.getOrderId());
        if (ObjectUtil.isNull(origOrder)) {
            throw new JbkException("补送对应的原订单不存在");
        }

        // ── 闸① 子订单号确定性派生（DeliveryOrderNo.deriveResend）：重复生成必然撞 uk_order_no
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
            // 0 行 = 已被并发生成或状态已变：整事务回滚，绝不留下没人认领的补送单
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
            // 普通配送任务签收会走到这里：不是错误，只是与补送无关
            return false;
        }
        // CAS 带 RESULT_TASK_ID = 本次签收的任务；影响 0 行不抛出——签收本身成功，
        // 回滚会让已收到的水显示成没送到，留给对账
        return actionMapper.markResendSucceeded(action.getId(), taskId, opUserId, now) == 1;
    }

    // ==================== 构造 ====================

    /**
     * 补送数量：取裁决批准数，必须落在 [1, 原计划数量] 内。裁决期已校过上界，
     * 这里是履约期的二次钉死——两处之间隔着人工操作与事务边界，改写必须在生成前拦下。
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
     * 零金额子订单：{@code ORDER_AMOUNT=0} 不写扣款流水；{@code PAY_WAY} 复用原单只为追溯展示；
     * 直接落 {@code 2已支付}——停在待支付会出现在用户待付款列表里。
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
     * 补送任务：水种/规格/地址/电话全部复用原任务快照、金额一律 0——
     * 重新取值等于给「换水种/换地址」留入口（任务书明令禁止）。
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
        // 补送不回收空桶：原单的回收已在原任务计划，再记一次会让回收数量凭空翻倍
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
