package com.jbk.serve.service.mini.wxship;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.json.JSONUtil;
import com.jbk.serve.mapper.mall.WsMallPaymentMapper;
import com.jbk.serve.mapper.mall.WsWechatShippingOutboxMapper;
import com.jbk.tool.consts.mall.MallEnum;
import com.jbk.tool.consts.mini.WechatNotifyEnum;
import com.jbk.tool.consts.mini.WechatShippingEnum;
import com.jbk.tool.data.mall.po.WsMallPayment;
import com.jbk.tool.data.mall.po.WsWechatShippingOutbox;
import com.jbk.tool.utils.DateUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 发货同步登记（WX-ECO S4）：业务事务内只写 outbox，随发货事实一起提交/回滚。
 * 刻意不加 @Transactional——必须运行在调用方（发货挂点）的事务里。
 * Pay-Sim 单登记即置「已处理+SKIP」：留痕可审计，但绝不进 Worker 外呼循环。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WechatShippingEnqueue {

    private static final long SYSTEM_ACTOR = 0L;
    private static final int PAY_SUCCESS = 2;

    private final WsWechatShippingOutboxMapper outboxMapper;
    private final WsMallPaymentMapper paymentMapper;

    /**
     * 登记一个待同步包裹。撞唯一键=已登记，直接返回。
     *
     * @param direction 包裹方向（幂等键组成）；seq 同方向内序号
     * @param logisticsType 微信协议值（自营=2 同城；第三方=1 快递）
     */
    public void enqueue(Long orderId, String orderNo, Long userId, Long shipmentId,
                        int direction, int seq, WechatShippingEnum.LogisticsType logisticsType,
                        String providerCode, String waybillNo, String itemDesc) {
        if (orderId == null || orderNo == null || userId == null || shipmentId == null
                || logisticsType == null) {
            // 同步是业务的附属品，入参不全只告警跳过，绝不让发货动作因此失败
            log.warn("发货同步登记入参不完整，已跳过 orderNo={} shipmentId={}", orderNo, shipmentId);
            return;
        }
        WsMallPayment payment = paymentMapper.selectByOrderNoIncludingDeleted(orderNo);
        if (payment == null || !ObjectUtil.equals(payment.getPayStatus(), PAY_SUCCESS)) {
            // 发货时支付单必然已成功；查不到属数据异常，登记为需人工而不是静默丢
            insert(buildRow(orderId, orderNo, userId, shipmentId, direction, seq, logisticsType,
                    providerCode, waybillNo, itemDesc, "UNKNOWN",
                    WechatNotifyEnum.ProcessingStatus.NEED_MANUAL.getValue(), null,
                    "发货时未找到成功支付单"));
            return;
        }
        boolean wechatPay = ObjectUtil.equals(payment.getPaySource(),
                MallEnum.PaySource.WECHAT.getValue());
        if (!wechatPay) {
            // Pay-Sim 单：留痕即终态，不进外呼循环——模拟单绝不进真实微信发货台账
            insert(buildRow(orderId, orderNo, userId, shipmentId, direction, seq, logisticsType,
                    providerCode, waybillNo, itemDesc, payment.getTransactionId(),
                    WechatNotifyEnum.ProcessingStatus.PROCESSED.getValue(),
                    WechatShippingEnum.SkipReason.NOT_WECHAT_PAY.getCode(), null));
            return;
        }
        insert(buildRow(orderId, orderNo, userId, shipmentId, direction, seq, logisticsType,
                providerCode, waybillNo, itemDesc, payment.getTransactionId(),
                WechatNotifyEnum.ProcessingStatus.PENDING.getValue(), null, null));
    }

    private WsWechatShippingOutbox buildRow(Long orderId, String orderNo, Long userId,
                                            Long shipmentId, int direction, int seq,
                                            WechatShippingEnum.LogisticsType logisticsType,
                                            String providerCode, String waybillNo, String itemDesc,
                                            String transactionId, int status, String skipReason,
                                            String lastError) {
        String now = DateUtils.time();
        WsWechatShippingOutbox row = new WsWechatShippingOutbox()
                .setOrderId(orderId)
                .setOrderNo(orderNo)
                .setShipmentId(shipmentId)
                .setTransactionId(transactionId)
                .setReceiverUserId(userId)
                .setBizSyncKey(String.join(":", WechatShippingEnum.SYNC_KEY_PREFIX,
                        orderNo, String.valueOf(direction), String.valueOf(seq)))
                .setLogisticsType(logisticsType.getValue())
                .setProviderCode(providerCode)
                .setWaybillNo(waybillNo)
                .setItemDesc(itemDesc)
                // 报文快照事务内冻结：Worker 外呼时不反查业务表
                .setPayloadSnap(JSONUtil.createObj()
                        .set("logisticsType", logisticsType.getValue())
                        .set("providerCode", providerCode)
                        .set("waybillNo", waybillNo)
                        .set("itemDesc", itemDesc).toString())
                .setProcessingStatus(status)
                .setRetryCount(0)
                .setSkipReason(skipReason)
                .setLastError(lastError);
        row.setCreateBy(SYSTEM_ACTOR);
        row.setCreateTime(now);
        row.setUpdateBy(SYSTEM_ACTOR);
        row.setUpdateTime(now);
        return row;
    }

    private void insert(WsWechatShippingOutbox row) {
        try {
            outboxMapper.insert(row);
        }
        catch (DuplicateKeyException e) {
            // 撞键即已登记：发货动作重放属正常路径
            log.debug("发货同步已登记，跳过重复入队 key={}", row.getBizSyncKey());
        }
    }
}
