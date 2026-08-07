package com.jbk.serve.service.delivery.impl;

import com.jbk.tool.data.delivery.po.WsDeliveryAppeal;
import com.jbk.tool.data.delivery.po.WsDeliveryTask;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsWalletFlow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E-03 包C 追溯核验纯函数矩阵测试（不连库不起容器）。
 *
 * <p>钉住 fail-closed 口径：履约链共键（订单-任务）、状态-时间矩阵（只认当前状态机
 * 1→2→3→4→5⇄7 可产生的组合）、总额恒等式、三照齐全、申诉锁步、资金链
 * DELIVERY:orderNo 流水核验、申诉行共键——任一错位只给原因，绝不放行拼凑证据。</p>
 */
class AdminDeliveryLinkStateTest {

    // ==================== 构造器 ====================

    private WsOrder order() {
        WsOrder order = new WsOrder()
                .setOrderNo("WDPKGC001")
                .setOrderType(3)
                .setUserId(6L)
                .setStationId(1L)
                .setCardId(9L)
                .setOrderAmount(4200L)
                .setPayWay(2)
                .setOrderStatus(2);
        order.setId(11L);
        order.setDataStatus(0);
        order.setCreateTime("20260724100000");
        return order;
    }

    private WsDeliveryTask pendingTask() {
        WsDeliveryTask task = new WsDeliveryTask()
                .setTaskNo("DTPKGC001")
                .setOrderId(11L)
                .setUserId(6L)
                .setStationId(1L)
                .setWaterAmount(3600L)
                .setDeliveryFee(600L)
                .setTaskStatus(1)
                .setVersion(1);
        task.setId(77L);
        task.setDataStatus(0);
        task.setCreateTime("20260724100000");
        return task;
    }

    private WsDeliveryTask signedTask() {
        WsDeliveryTask task = pendingTask()
                .setCourierId(3L)
                .setTaskStatus(5)
                .setVersion(5)
                .setAcceptTime("20260724101000")
                .setDepartTime("20260724102000")
                .setArriveTime("20260724103000")
                .setSignTime("20260724104000")
                .setAppealDeadline("20260725104000")
                .setLocationStatus(2)
                .setActualDeliveryCount(3)
                .setActualReturnCount(1)
                .setSignPhotos(threePhotos());
        return task;
    }

    private String threePhotos() {
        return "[{\"type\":1,\"mediaKey\":\"DMA\",\"time\":\"20260724104000\"},"
                + "{\"type\":2,\"mediaKey\":\"DMB\",\"time\":\"20260724104000\"},"
                + "{\"type\":3,\"mediaKey\":\"DMC\",\"time\":\"20260724104000\"}]";
    }

    private WsOrder finishedOrder() {
        WsOrder order = order();
        order.setOrderStatus(4);
        return order;
    }

    /** 待接单取消后的订单形态（E2E-04 包A）：原路返还落 7已退款。 */
    private WsOrder refundedOrder() {
        WsOrder order = order();
        order.setOrderStatus(7);
        return order;
    }

    private WsWalletFlow flow() {
        WsWalletFlow flow = new WsWalletFlow()
                .setCardId(9L)
                .setUserId(6L)
                .setFlowType(7)
                .setAmountChange(-4200L)
                .setMlChange(0L)
                .setAmountAfter(800L)
                .setOrderId(11L)
                .setBizIdempotencyKey("DELIVERY:WDPKGC001");
        flow.setId(500L);
        flow.setCreateTime("20260724100000");
        return flow;
    }

    private WsDeliveryAppeal pendingAppeal() {
        WsDeliveryAppeal appeal = new WsDeliveryAppeal()
                .setTaskId(77L)
                .setOrderId(11L)
                .setUserId(6L)
                .setAppealReason("QUANTITY")
                .setAppealStatus(1);
        appeal.setId(300L);
        return appeal;
    }

    // ==================== 履约链共键 ====================

    @Test
    void linkOkForConsistentPair() {
        assertNull(AdminDeliveryServiceImpl.deliveryLinkMismatch(order(), pendingTask()));
    }

    @Test
    void linkRejectsMissingTask() {
        String reason = AdminDeliveryServiceImpl.deliveryLinkMismatch(order(), null);
        assertNotNull(reason);
        assertTrue(reason.contains("未关联任何配送任务"));
    }

    @Test
    void linkRejectsMissingOrDeletedOrder() {
        assertNotNull(AdminDeliveryServiceImpl.deliveryLinkMismatch(null, pendingTask()));
        WsOrder deleted = order();
        deleted.setDataStatus(1);
        assertNotNull(AdminDeliveryServiceImpl.deliveryLinkMismatch(deleted, pendingTask()));
    }

    @Test
    void linkRejectsNonDeliveryOrderType() {
        WsOrder water = order().setOrderType(1);
        assertNotNull(AdminDeliveryServiceImpl.deliveryLinkMismatch(water, pendingTask()));
    }

    @Test
    void linkRejectsForeignTaskForeignUserAndStationDrift() {
        // 任务外键指向他单
        assertNotNull(AdminDeliveryServiceImpl.deliveryLinkMismatch(order(), pendingTask().setOrderId(12L)));
        // 收货用户与订单归属错位
        assertNotNull(AdminDeliveryServiceImpl.deliveryLinkMismatch(order(), pendingTask().setUserId(7L)));
        // 水站快照漂移
        assertNotNull(AdminDeliveryServiceImpl.deliveryLinkMismatch(order(), pendingTask().setStationId(2L)));
    }

    // ==================== 状态-时间矩阵 ====================

    @Test
    void stateOkForPendingAndSignedShapes() {
        assertNull(AdminDeliveryServiceImpl.deliveryStateMismatch(order(), pendingTask(), 0));
        assertNull(AdminDeliveryServiceImpl.deliveryStateMismatch(finishedOrder(), signedTask(), 0));
    }

    @Test
    void stateOkForDeliveringShape() {
        WsDeliveryTask delivering = pendingTask()
                .setCourierId(3L)
                .setTaskStatus(3)
                .setVersion(3)
                .setAcceptTime("20260724101000")
                .setDepartTime("20260724102000");
        assertNull(AdminDeliveryServiceImpl.deliveryStateMismatch(order(), delivering, 0));
    }

    @Test
    void stateOkForAppealingWithExactlyOnePending() {
        WsDeliveryTask appealing = signedTask().setTaskStatus(7).setVersion(6);
        assertNull(AdminDeliveryServiceImpl.deliveryStateMismatch(finishedOrder(), appealing, 1));
    }

    @Test
    void stateOkForCancelledTaskWithRefundedOrder() {
        // E2E-04 包A：待接单取消是合法终态——任务落 6已取消、订单原路返还落 7已退款。
        // 取消只发生在 1待接单，因此履约维度必须与待接单完全一致。
        WsDeliveryTask cancelled = pendingTask().setTaskStatus(6).setVersion(2);
        assertNull(AdminDeliveryServiceImpl.deliveryStateMismatch(refundedOrder(), cancelled, 0));
    }

    @Test
    void stateRejectsCancelledTaskCarryingFulfillmentTraces() {
        // 带着履约痕迹的"取消"意味着履约中途被改库，一律拒绝
        assertNotNull(AdminDeliveryServiceImpl.deliveryStateMismatch(refundedOrder(),
                pendingTask().setTaskStatus(6).setVersion(2).setCourierId(3L), 0));
        assertNotNull(AdminDeliveryServiceImpl.deliveryStateMismatch(refundedOrder(),
                pendingTask().setTaskStatus(6).setVersion(2).setAcceptTime("20260724101000"), 0));
        assertNotNull(AdminDeliveryServiceImpl.deliveryStateMismatch(refundedOrder(),
                pendingTask().setTaskStatus(6).setVersion(2).setSignPhotos(threePhotos()), 0));
        assertNotNull(AdminDeliveryServiceImpl.deliveryStateMismatch(refundedOrder(),
                pendingTask().setTaskStatus(6).setVersion(2).setActualDeliveryCount(0), 0));
        // 订单未随取消退款：耦合脱钩
        String reason = AdminDeliveryServiceImpl.deliveryStateMismatch(order(),
                pendingTask().setTaskStatus(6).setVersion(2), 0);
        assertNotNull(reason);
        assertTrue(reason.contains("不属于当前配送状态机"));
    }

    @Test
    void stateRejectsAmountIdentityBreak() {
        // 总额恒等式（规则2）：3600+600 != 5000
        WsOrder drifted = order().setOrderAmount(5000L);
        String reason = AdminDeliveryServiceImpl.deliveryStateMismatch(drifted, pendingTask(), 0);
        assertNotNull(reason);
        assertTrue(reason.contains("恒等式"));
    }

    @Test
    void stateRejectsStageTimeShapeViolations() {
        // 待接单却带配送员
        assertNotNull(AdminDeliveryServiceImpl.deliveryStateMismatch(order(),
                pendingTask().setCourierId(3L), 0));
        // 已接单缺接单时间
        assertNotNull(AdminDeliveryServiceImpl.deliveryStateMismatch(order(),
                pendingTask().setTaskStatus(2).setCourierId(3L), 0));
        // 配送中缺离站时间
        assertNotNull(AdminDeliveryServiceImpl.deliveryStateMismatch(order(),
                pendingTask().setTaskStatus(3).setCourierId(3L).setAcceptTime("20260724101000"), 0));
        // 已送达提前携带签收时间
        assertNotNull(AdminDeliveryServiceImpl.deliveryStateMismatch(order(),
                pendingTask().setTaskStatus(4).setCourierId(3L)
                        .setAcceptTime("20260724101000").setDepartTime("20260724102000")
                        .setArriveTime("20260724103000").setSignTime("20260724104000"), 0));
    }

    @Test
    void stateRejectsNonMonotonicTimeline() {
        // 离站早于接单：统一逻辑时钟保证不可能出现，出现即证据不成立
        WsDeliveryTask task = signedTask().setDepartTime("20260724100500");
        String reason = AdminDeliveryServiceImpl.deliveryStateMismatch(finishedOrder(), task, 0);
        assertNotNull(reason);
        assertTrue(reason.contains("倒序"));
    }

    @Test
    void stateRejectsOrderTaskCouplingBreak() {
        // 任务已签收但订单仍在已支付：签收事务保证两者同事务推进，脱钩即异常
        assertNotNull(AdminDeliveryServiceImpl.deliveryStateMismatch(order(), signedTask(), 0));
        // 订单已完成但任务仍在配送中
        WsDeliveryTask delivering = pendingTask()
                .setCourierId(3L).setTaskStatus(3).setVersion(3)
                .setAcceptTime("20260724101000").setDepartTime("20260724102000");
        assertNotNull(AdminDeliveryServiceImpl.deliveryStateMismatch(finishedOrder(), delivering, 0));
    }

    @Test
    void stateRejectsAppealLockstepBreak() {
        // 申诉中必须恰一条待处理申诉
        WsDeliveryTask appealing = signedTask().setTaskStatus(7).setVersion(6);
        assertNotNull(AdminDeliveryServiceImpl.deliveryStateMismatch(finishedOrder(), appealing, 0));
        // 已签收却仍挂待处理申诉
        assertNotNull(AdminDeliveryServiceImpl.deliveryStateMismatch(finishedOrder(), signedTask(), 1));
    }

    @Test
    void stateRejectsSignedWithoutFullSignEvidence() {
        // 缺申诉截止
        assertNotNull(AdminDeliveryServiceImpl.deliveryStateMismatch(finishedOrder(),
                signedTask().setAppealDeadline(null), 0));
        // 定位状态非法
        assertNotNull(AdminDeliveryServiceImpl.deliveryStateMismatch(finishedOrder(),
                signedTask().setLocationStatus(9), 0));
        // 缺实际数量
        assertNotNull(AdminDeliveryServiceImpl.deliveryStateMismatch(finishedOrder(),
                signedTask().setActualDeliveryCount(null), 0));
    }

    // ==================== 三照集合 ====================

    @Test
    void signPhotoSetRejectsBrokenMissingAndDuplicate() {
        assertNotNull(AdminDeliveryServiceImpl.signPhotoSetMismatch("{broken"));
        assertNotNull(AdminDeliveryServiceImpl.signPhotoSetMismatch(
                "[{\"type\":1,\"mediaKey\":\"DMA\",\"time\":\"20260724104000\"}]"));
        assertNotNull(AdminDeliveryServiceImpl.signPhotoSetMismatch(
                "[{\"type\":1,\"mediaKey\":\"DMA\",\"time\":\"20260724104000\"},"
                        + "{\"type\":1,\"mediaKey\":\"DMB\",\"time\":\"20260724104000\"},"
                        + "{\"type\":3,\"mediaKey\":\"DMC\",\"time\":\"20260724104000\"}]"));
        assertNull(AdminDeliveryServiceImpl.signPhotoSetMismatch(threePhotos()));
    }

    // ==================== 资金链 ====================

    @Test
    void flowOkForConsistentDeduction() {
        assertNull(AdminDeliveryServiceImpl.deliveryFlowMismatch(order(), flow()));
    }

    @Test
    void flowRejectsNonCardBalancePayWay() {
        // 历史种子形态：微信支付的配送单没有卡扣款证据，必须显式呈现资金链异常
        WsOrder wechat = order().setPayWay(1);
        String reason = AdminDeliveryServiceImpl.deliveryFlowMismatch(wechat, null);
        assertNotNull(reason);
        assertTrue(reason.contains("水卡余额"));
    }

    @Test
    void flowRejectsMissingFlowOrCard() {
        assertNotNull(AdminDeliveryServiceImpl.deliveryFlowMismatch(order().setCardId(null), flow()));
        assertNotNull(AdminDeliveryServiceImpl.deliveryFlowMismatch(order(), null));
    }

    @Test
    void flowRejectsForeignOwnershipAndWrongNumbers() {
        assertNotNull(AdminDeliveryServiceImpl.deliveryFlowMismatch(order(), flow().setOrderId(12L)));
        assertNotNull(AdminDeliveryServiceImpl.deliveryFlowMismatch(order(), flow().setCardId(8L)));
        assertNotNull(AdminDeliveryServiceImpl.deliveryFlowMismatch(order(), flow().setUserId(7L)));
        assertNotNull(AdminDeliveryServiceImpl.deliveryFlowMismatch(order(), flow().setFlowType(2)));
        assertNotNull(AdminDeliveryServiceImpl.deliveryFlowMismatch(order(), flow().setAmountChange(-4100L)));
        assertNotNull(AdminDeliveryServiceImpl.deliveryFlowMismatch(order(), flow().setMlChange(-100L)));
    }

    // ==================== 资金链（D-214 payWay=3 混合结算） ====================

    /** payWay=3 订单：ORDER_AMOUNT=配送费，抵扣量冻结在创单快照 waterMl。 */
    private WsOrder mlOrder() {
        return order()
                .setPayWay(3)
                .setOrderAmount(600L)
                .setPackageSnap("{\"payWay\":3,\"waterMl\":36000,\"priceWaterAmountFen\":3600,"
                        + "\"waterAmountFen\":0,\"deliveryFeeFen\":600}");
    }

    private WsWalletFlow mlFlow() {
        return flow().setAmountChange(-600L).setMlChange(-36_000L);
    }

    @Test
    void flowOkForMlPayWayWithSnapshotMatchedDualDeduction() {
        assertNull(AdminDeliveryServiceImpl.deliveryFlowMismatch(mlOrder(), mlFlow()));
    }

    @Test
    void mlFlowRejectsMlMismatchAndBrokenSnapshot() {
        // 水量变动与快照抵扣量不一致 / 缺失
        assertNotNull(AdminDeliveryServiceImpl.deliveryFlowMismatch(mlOrder(), mlFlow().setMlChange(-35_999L)));
        assertNotNull(AdminDeliveryServiceImpl.deliveryFlowMismatch(mlOrder(), mlFlow().setMlChange(null)));
        assertNotNull(AdminDeliveryServiceImpl.deliveryFlowMismatch(mlOrder(), mlFlow().setMlChange(0L)));
        // 快照缺 waterMl / 损坏：无从核验，按证据缺失呈现
        assertNotNull(AdminDeliveryServiceImpl.deliveryFlowMismatch(
                mlOrder().setPackageSnap("{\"payWay\":3}"), mlFlow()));
        assertNotNull(AdminDeliveryServiceImpl.deliveryFlowMismatch(
                mlOrder().setPackageSnap("{broken"), mlFlow()));
        // 金额=-订单总额的既有恒等对 payWay=3 同样必须成立
        assertNotNull(AdminDeliveryServiceImpl.deliveryFlowMismatch(mlOrder(), mlFlow().setAmountChange(-4200L)));
    }

    // ==================== 申诉行 ====================

    @Test
    void appealRowOkForPendingAndDecided() {
        assertNull(AdminDeliveryServiceImpl.appealRowMismatch(finishedOrder(), signedTask(), pendingAppeal()));
        WsDeliveryAppeal decided = pendingAppeal()
                .setAppealStatus(5)
                .setHandleBy(1L)
                .setHandleTime("20260724120000")
                .setHandleResult("核实少送一桶，补送待执行");
        assertNull(AdminDeliveryServiceImpl.appealRowMismatch(finishedOrder(), signedTask(), decided));
    }

    @Test
    void appealRowRejectsCrossKeyDrift() {
        assertNotNull(AdminDeliveryServiceImpl.appealRowMismatch(finishedOrder(), null, pendingAppeal()));
        assertNotNull(AdminDeliveryServiceImpl.appealRowMismatch(finishedOrder(), signedTask(),
                pendingAppeal().setTaskId(78L)));
        assertNotNull(AdminDeliveryServiceImpl.appealRowMismatch(finishedOrder(), signedTask(),
                pendingAppeal().setUserId(7L)));
        assertNotNull(AdminDeliveryServiceImpl.appealRowMismatch(finishedOrder(), signedTask(),
                pendingAppeal().setOrderId(12L)));
    }

    @Test
    void appealRowRejectsStatesNotProducedByMachine() {
        // 4撤销当前无产生路径
        assertNotNull(AdminDeliveryServiceImpl.appealRowMismatch(finishedOrder(), signedTask(),
                pendingAppeal().setAppealStatus(4)));
        // 裁决态缺处理字段
        assertNotNull(AdminDeliveryServiceImpl.appealRowMismatch(finishedOrder(), signedTask(),
                pendingAppeal().setAppealStatus(3)));
        // 待处理不得预写处理字段
        assertNotNull(AdminDeliveryServiceImpl.appealRowMismatch(finishedOrder(), signedTask(),
                pendingAppeal().setHandleResult("提前写入")));
    }

    // ==================== 小工具 ====================

    @Test
    void sumFenReturnsNullOnMissingSide() {
        assertEquals(4200L, AdminDeliveryServiceImpl.sumFen(3600L, 600L));
        assertNull(AdminDeliveryServiceImpl.sumFen(null, 600L));
        assertNull(AdminDeliveryServiceImpl.sumFen(3600L, null));
    }

    @Test
    void appealReasonLabelMapsKnownCodesAndPassesThroughUnknown() {
        assertEquals("数量不符", AdminDeliveryServiceImpl.appealReasonLabel("QUANTITY"));
        assertEquals("水质问题", AdminDeliveryServiceImpl.appealReasonLabel("QUALITY"));
        assertEquals("货品破损", AdminDeliveryServiceImpl.appealReasonLabel("DAMAGE"));
        assertEquals("摆放问题", AdminDeliveryServiceImpl.appealReasonLabel("PLACEMENT"));
        assertEquals("其他", AdminDeliveryServiceImpl.appealReasonLabel("OTHER"));
        // 未登记码原样透出，不猜测语义
        assertEquals("UNKNOWN_X", AdminDeliveryServiceImpl.appealReasonLabel("UNKNOWN_X"));
        assertNull(AdminDeliveryServiceImpl.appealReasonLabel(" "));
    }
}
