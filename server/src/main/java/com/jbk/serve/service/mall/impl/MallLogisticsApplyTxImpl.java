package com.jbk.serve.service.mall.impl;

import cn.hutool.core.util.ObjectUtil;
import com.jbk.serve.mapper.mall.WsMallFulfillmentMapper;
import com.jbk.serve.mapper.mall.WsMallLogisticsEventMapper;
import com.jbk.serve.mapper.mall.WsMallOrderMapper;
import com.jbk.serve.mapper.mall.WsMallShipmentMapper;
import com.jbk.serve.service.mall.IMallLogisticsApplyTx;
import com.jbk.serve.service.mall.IMallPayApplyTx;
import com.jbk.serve.service.mall.IMallShipmentService;
import com.jbk.serve.service.message.IWsMessageService;
import com.jbk.tool.consts.mall.MallEnum;
import com.jbk.tool.consts.mini.WechatShippingEnum;
import com.jbk.tool.consts.message.MessageEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.mall.po.WsMallFulfillment;
import com.jbk.tool.data.mall.po.WsMallLogisticsEvent;
import com.jbk.tool.data.mall.po.WsMallOrder;
import com.jbk.tool.data.mall.po.WsMallShipment;
import com.jbk.tool.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 物流事实推进段实现（事务B，E2E-09 L1）。事务B 不信任事务A：全部共键本事务内重读重新核验，
 * 任一错位转人工。只推进到「已送达待确认」为止：平台的 7 已签收永远不由物流事件写入，
 * 订单完成恒由用户确认收货落定（自动确认期限未获业务决策，本轮不发明）。
 *
 * @author dakang
 * @since 2026-08-11
 */
@Slf4j
@Service
public class MallLogisticsApplyTxImpl implements IMallLogisticsApplyTx {

    /** 物流节点审计幂等键前缀。 */
    static final String AUDIT_KEY_PREFIX = "MALL_LOGI:";
    private static final long SYSTEM_OPERATOR = 0L;

    @Autowired
    private WsMallLogisticsEventMapper eventMapper;
    @Autowired
    private WsMallShipmentMapper shipmentMapper;
    @Autowired
    private WsMallFulfillmentMapper fulfillMapper;
    @Autowired
    private WsMallOrderMapper orderMapper;
    @Autowired
    private IMallShipmentService shipmentService;
    @Autowired
    private com.jbk.serve.service.mini.wxship.WechatShippingEnqueue shippingEnqueue;
    @Autowired
    private MallFulfillCore core;
    @Autowired
    private IWsMessageService messageService;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class,
            isolation = Isolation.READ_COMMITTED)
    public IMallPayApplyTx.Outcome apply(Long eventId) {
        WsMallLogisticsEvent event = eventMapper.selectById(eventId);
        if (ObjectUtil.isNull(event)) {
            return IMallPayApplyTx.Outcome.reconcile("物流事实不存在");
        }
        if (!MallEnum.LogisticsEventState.isKnown(event.getEventState())) {
            // 白名单第二道：读不懂的状态绝不当成「反正不是送达那就没事」
            return IMallPayApplyTx.Outcome.reconcile(
                    "未知物流事件状态，不得推进：" + event.getEventState());
        }
        if (ObjectUtil.isNull(event.getShipmentId())) {
            return IMallPayApplyTx.Outcome.reconcile("物流事实未能归属到任何包裹，需人工对账");
        }
        WsMallShipment shipment = shipmentMapper.selectById(event.getShipmentId());
        String factMiss = MallShipmentGate.factMismatch(event, shipment);
        if (factMiss != null) {
            return IMallPayApplyTx.Outcome.reconcile(factMiss);
        }
        WsMallFulfillment task = fulfillMapper.selectById(shipment.getFulfillId());
        WsMallOrder order = ObjectUtil.isNull(task) ? null
                : orderMapper.selectByOrderNoIncludingDeleted(task.getOrderNo());
        String linkMiss = MallShipmentGate.linkMismatch(shipment, task, order);
        if (linkMiss != null) {
            return IMallPayApplyTx.Outcome.reconcile(linkMiss);
        }

        // 下界：物流事件只能推进**已经安排发运**的任务。低于 3 说明有人绕过了创建运单入口，
        // 此时按「小于就推」会把履约从待拣货/待打包直接顶到运输中，那两个节点凭空消失且
        // 再也补不回来（CAS 前态恒对不上）。不设上界是刻意的：承运方不一定推中间态，
        // 4 直接跳到 6 是真实存在的正常回传。
        if (task.getFulfillStatus() < MallEnum.FulfillStatus.PENDING_ASSIGN.getValue()) {
            return IMallPayApplyTx.Outcome.reconcile(
                    "履约尚未安排发运却收到承运方事件（当前状态 " + task.getFulfillStatus()
                            + "），需人工核查");
        }


        String now = DateUtils.time();
        // 异常与取消不自动改状态：它们要人看，自动处理只会让人看不见
        if (MallEnum.LogisticsEventState.EXCEPTION.equals(event.getEventState())
                || MallEnum.LogisticsEventState.CANCELLED.equals(event.getEventState())) {
            shipmentMapper.markNeedManual(shipment.getId(),
                    MallEnum.ShipmentStatus.NEED_MANUAL.getValue(), SYSTEM_OPERATOR, now);
            writeAudit(shipment, event, "物流异常转人工：" + event.getEventState());
            return IMallPayApplyTx.Outcome.already("物流异常已留证转人工，不自动推进状态");
        }

        Integer targetShipStatus = MallShipmentGate.shipmentStatusOf(event.getEventState());
        if (targetShipStatus == null) {
            return IMallPayApplyTx.Outcome.already("该事件不推进包裹状态");
        }
        String timeColumn = timeColumnOf(targetShipStatus);
        IMallShipmentService.Advance moved = shipmentService.advance(shipment, targetShipStatus,
                timeColumn, SYSTEM_OPERATOR, now);
        if (moved == IMallShipmentService.Advance.LOST) {
            // 前态仍朝前却没推动：另一条并发事实先提交把版本号顶走了。这条事实一步也没生效，
            // 必须重投而不是标成已处理——标成已处理它就从重投扫描面永远消失，
            // 而包裹会停在更早的状态上，用户的确认收货入口再也不出现。
            return IMallPayApplyTx.Outcome.retry("包裹状态被并发事实抢占，稍后重投");
        }
        if (moved == IMallShipmentService.Advance.MOVED
                && targetShipStatus == MallEnum.ShipmentStatus.PICKED_UP.getValue()) {
            // WX-ECO S4：承运方揽收=真实发货时点，登记微信发货同步（Pay-Sim 单落 SKIP）。
            // 只在 WON 分支：STALE 重放不再重复登记（登记本身也有唯一键兜底）
            shippingEnqueue.enqueue(task.getOrderId(), task.getOrderNo(), task.getUserId(),
                    shipment.getId(), shipment.getDirection(), shipment.getShipmentSeq(),
                    WechatShippingEnum.LogisticsType.EXPRESS,
                    shipment.getProviderCode(), shipment.getWaybillNo(), "商城订单商品");
        }
        if (moved == IMallShipmentService.Advance.STALE) {
            // 乱序/迟到/重放：包裹已在同态或更后的状态，如实记为已处理而不是失败
            return IMallPayApplyTx.Outcome.already("包裹状态未前进（重放或乱序事件），已留证");
        }

        Integer targetFulfill = MallShipmentGate.drivesForwardFulfillment(shipment)
                ? MallShipmentGate.fulfillStatusOf(targetShipStatus)
                : null;
        if (targetFulfill != null && task.getFulfillStatus() < targetFulfill) {
            // 平台状态跟随包裹前进；7 已签收永远不在这里写入
            MallEnum.FulfillStatus from = statusOf(task.getFulfillStatus());
            MallEnum.FulfillStatus to = statusOf(targetFulfill);
            core.advance(task, from, to, fulfillTimeColumnOf(targetFulfill), SYSTEM_OPERATOR, now);
            core.writeTrace(task, to, MallEnum.ActorType.SYSTEM, SYSTEM_OPERATOR, null, now,
                    "承运方事件：" + event.getEventState()
                            + (event.getEventDesc() == null ? "" : "（" + event.getEventDesc() + "）"));
            if (ObjectUtil.equal(targetFulfill, MallEnum.FulfillStatus.ARRIVED.getValue())) {
                messageService.sendInApp(task.getUserId(), MessageEnum.MsgDomain.MALL,
                        "商城订单已送达",
                        "您的商城订单 " + task.getOrderNo() + " 已送达，请及时确认收货。",
                        "mallOrder", task.getOrderNo(), now);
            }
        }
        writeAudit(shipment, event, "物流事件推进：" + event.getEventState());
        return IMallPayApplyTx.Outcome.applied();
    }

    /** 事件节点审计：同一包裹的同一事件状态只记一次。 */
    private void writeAudit(WsMallShipment shipment, WsMallLogisticsEvent event, String detail) {
        core.recordLogisticsAudit(AUDIT_KEY_PREFIX + shipment.getId() + ":" + event.getEventState(),
                "MALLORDER:" + shipment.getOrderNo(), OpsEnum.ActorPortal.SYSTEM, SYSTEM_OPERATOR,
                "物流事件", detail);
    }

    private static String timeColumnOf(int shipmentStatus) {
        if (shipmentStatus == MallEnum.ShipmentStatus.PICKED_UP.getValue()) {
            return "PICKUP_TIME";
        }
        if (shipmentStatus == MallEnum.ShipmentStatus.DELIVERED.getValue()) {
            return "DELIVER_TIME";
        }
        return null;
    }

    private static String fulfillTimeColumnOf(int fulfillStatus) {
        if (fulfillStatus == MallEnum.FulfillStatus.PENDING_FETCH.getValue()) {
            return "ASSIGN_TIME";
        }
        if (fulfillStatus == MallEnum.FulfillStatus.DELIVERING.getValue()) {
            return "FETCH_TIME";
        }
        if (fulfillStatus == MallEnum.FulfillStatus.ARRIVED.getValue()) {
            return "ARRIVE_TIME";
        }
        return null;
    }

    private static MallEnum.FulfillStatus statusOf(int value) {
        for (MallEnum.FulfillStatus item : MallEnum.FulfillStatus.values()) {
            if (item.getValue() == value) {
                return item;
            }
        }
        throw new IllegalStateException("履约状态越界：" + value);
    }
}
