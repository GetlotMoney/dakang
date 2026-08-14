package com.jbk.serve.service.mall.impl;

import cn.hutool.core.util.ObjectUtil;
import com.jbk.serve.mapper.mall.WsMallAfterSaleItemMapper;
import com.jbk.serve.mapper.mall.WsMallAfterSaleMapper;
import com.jbk.serve.mapper.mall.WsMallOrderItemMapper;
import com.jbk.serve.mapper.mall.WsMallOrderMapper;
import com.jbk.serve.service.mall.IMallExchangeTx;
import com.jbk.serve.service.mall.IMallFulfillmentService;
import com.jbk.tool.consts.mall.MallEnum;
import com.jbk.tool.data.mall.po.WsMallAfterSale;
import com.jbk.tool.data.mall.po.WsMallAfterSaleItem;
import com.jbk.tool.data.mall.po.WsMallOrder;
import com.jbk.tool.data.mall.po.WsMallOrderItem;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 商城换货补发事务实现（E2E-09 S4）。补发单是履约动作不是交易：零价、无支付单、不计新收入，
 * 但必须是一张真的 {@code ws_mall_order}，不在履约表上另开第二套状态机。幂等两层：
 * uk_mall_order_source_after_sale 保证一张售后单只补发一次；订单号/请求号由售后单号
 * 确定性派生，重放得到同一张单而非撞键失败。
 *
 * @author dakang
 * @since 2026-08-10
 */
@Slf4j
@Service
public class MallExchangeTxImpl implements IMallExchangeTx {

    @Autowired
    private WsMallAfterSaleMapper afterSaleMapper;
    @Autowired
    private WsMallAfterSaleItemMapper afterSaleItemMapper;
    @Autowired
    private WsMallOrderMapper orderMapper;
    @Autowired
    private WsMallOrderItemMapper orderItemMapper;
    @Autowired
    private MallAfterSaleStock afterSaleStock;
    @Autowired
    private IMallFulfillmentService fulfillmentService;

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public String createReshipment(Long afterSaleId, Long operatorId) {
        WsMallAfterSale afterSale = afterSaleMapper.selectById(afterSaleId);
        if (ObjectUtil.isNull(afterSale) || !ObjectUtil.equal(afterSale.getDataStatus(), 0)) {
            throw new JbkException("售后单不存在或已删除");
        }
        if (!ObjectUtil.equal(afterSale.getAfterSaleType(),
                MallEnum.AfterSaleType.EXCHANGE.getValue())) {
            throw new JbkException("非换货售后单不得创建补发单");
        }
        WsMallOrder existed = orderMapper.selectBySourceAfterSale(afterSaleId);
        if (ObjectUtil.isNotNull(existed)) {
            // 幂等：同一售后单重复调用返回原补发单，不再预占第二次
            return existed.getOrderNo();
        }

        WsMallOrder origin = orderMapper.selectByOrderNoIncludingDeleted(afterSale.getOrderNo());
        String miss = MallAfterSaleGate.linkMismatch(afterSale, origin, null);
        if (miss != null) {
            throw new JbkException(miss);
        }
        List<WsMallAfterSaleItem> exchangeItems =
                afterSaleItemMapper.selectByAfterSaleOrderBySku(afterSaleId);
        if (exchangeItems.isEmpty()) {
            throw new JbkException("换货明细缺失，无法补发");
        }
        Map<Long, WsMallOrderItem> originItems = new LinkedHashMap<>();
        for (WsMallOrderItem item : orderItemMapper.selectByOrderIdOrderBySku(origin.getId())) {
            originItems.put(item.getId(), item);
        }

        String now = DateUtils.time();
        // 请求号与订单号都由售后单号确定性派生：重放得到同一张单，而不是撞键失败
        String requestId = UUID.nameUUIDFromBytes(
                ("MALLEXCHANGE:" + afterSale.getAfterSaleNo()).getBytes(StandardCharsets.UTF_8))
                .toString();
        String orderNo = MallOrderNo.derive(origin.getUserId(), requestId);

        WsMallOrder reshipment = new WsMallOrder()
                .setOrderNo(orderNo)
                .setUserId(origin.getUserId())
                .setWarehouseId(origin.getWarehouseId())
                .setRequestId(requestId)
                .setAddressId(origin.getAddressId())
                .setProductAmountFen(0L)
                .setDeliveryFeeFen(0L)
                .setOrderAmountFen(0L)
                // 直接进入已支付待履约：补发不收钱，也就没有付款窗可言
                .setOrderStatus(MallEnum.OrderStatus.PAID.getValue())
                .setPayExpireTime(now)
                .setReceiverName(origin.getReceiverName())
                .setReceiverPhone(origin.getReceiverPhone())
                .setReceiverRegion(origin.getReceiverRegion())
                .setReceiverAddress(origin.getReceiverAddress())
                .setReceiverDistrictCode(origin.getReceiverDistrictCode())
                .setSourceAfterSaleId(afterSaleId)
                .setVersion(1);
        reshipment.setCreateTime(now);
        try {
            orderMapper.insert(reshipment);
        }
        catch (DuplicateKeyException race) {
            WsMallOrder winner = orderMapper.selectBySourceAfterSale(afterSaleId);
            if (ObjectUtil.isNull(winner)) {
                throw new JbkException("换货补发单创建冲突，请重试");
            }
            return winner.getOrderNo();
        }

        for (WsMallAfterSaleItem item : exchangeItems) {
            WsMallOrderItem originItem = originItems.get(item.getOrderItemId());
            if (ObjectUtil.isNull(originItem)) {
                throw new JbkException("换货明细与原订单不一致，请人工核查");
            }
            WsMallOrderItem line = new WsMallOrderItem()
                    .setOrderId(reshipment.getId())
                    .setProductId(originItem.getProductId())
                    .setSkuId(originItem.getSkuId())
                    .setProductName(originItem.getProductName())
                    .setSkuName(originItem.getSkuName())
                    .setSpecSnap(originItem.getSpecSnap())
                    // 零价：补发不计新销售收入，库层 CHECK 的 0 = 0 × 数量 同样成立
                    .setUnitPriceFen(0L)
                    .setQuantity(item.getQuantity())
                    .setItemAmountFen(0L)
                    .setWeightGram(originItem.getWeightGram());
            line.setCreateTime(now);
            orderItemMapper.insert(line);
            // 换货预占：库存不足时整事务回滚，绝不留半张补发单
            afterSaleStock.exchangeReserve(reshipment.getWarehouseId(), originItem.getSkuId(),
                    item.getQuantity(), afterSale.getAfterSaleNo(), operatorId, now);
        }

        // 复用 S3 履约内核：补发单从此走与普通订单完全相同的七态链
        fulfillmentService.ensureTask(orderNo);
        log.info("换货补发单已创建 afterSaleNo={} reshipmentOrderNo={}",
                afterSale.getAfterSaleNo(), orderNo);
        return orderNo;
    }
}
