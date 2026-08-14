package com.jbk.serve.service.mall.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.mall.WsMallStockFlowMapper;
import com.jbk.serve.mapper.mall.WsMallStockMapper;
import com.jbk.tool.consts.mall.MallEnum;
import com.jbk.tool.data.mall.po.WsMallStock;
import com.jbk.tool.data.mall.po.WsMallStockFlow;
import com.jbk.tool.exception.JbkException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 商城售后库存动作（E2E-09 S4）：三处不同事务边界（售后服务/退款推进段/换货补发）都要动库存，
 * 只暴露幂等动作供其调用，防止长出三份变体。幂等键就是正确性：业务键库层唯一，
 * 撞键即已发生过直接跳过，不靠「先查再写」（并发重放会各做一次）。
 *
 * @author dakang
 * @since 2026-08-10
 */
@Slf4j
@Component
public class MallAfterSaleStock {

    /** 退货回库幂等键前缀。 */
    static final String RESTOCK_KEY_PREFIX = "MALLRET:";
    /** 换货预占幂等键前缀。 */
    static final String EXCHANGE_RESERVE_KEY_PREFIX = "MALLEXR:";
    /** 换货出库幂等键前缀。 */
    static final String EXCHANGE_OUT_KEY_PREFIX = "MALLEXO:";
    /** 换货释放幂等键前缀。 */
    static final String EXCHANGE_RELEASE_KEY_PREFIX = "MALLEXC:";

    @Autowired
    private WsMallStockMapper stockMapper;
    @Autowired
    private WsMallStockFlowMapper flowMapper;

    /**
     * 退货回库：只加可售，不碰预占。
     *
     * <p>只有质检结论为「通过可重新销售」才允许调用；不可重新销售的退货照样退钱，
     * 但绝不能回到可售库存里去——那等于把坏货重新卖一次。</p>
     */
    public void restock(Long warehouseId, Long skuId, long quantity, String afterSaleNo,
                        Long operator, String now) {
        String key = RESTOCK_KEY_PREFIX + afterSaleNo + ":" + skuId;
        if (ObjectUtil.isNotNull(flowMapper.selectByKeyIncludingDeleted(key))) {
            return;
        }
        int moved = stockMapper.restockAtomic(warehouseId, skuId, quantity, operator, now);
        if (moved != 1) {
            // 库存行不存在＝退货回到了一个没有该 SKU 的仓，属证据链断裂，整事务回滚等人工
            throw new JbkException("回库失败（库存行缺失），售后单 " + afterSaleNo);
        }
        writeFlow(key, warehouseId, skuId, MallEnum.StockFlowType.RETURN_RESTOCK,
                quantity, 0L, "退货回库 " + afterSaleNo, operator, now);
    }

    /** 换货预占：与下单预占同形（可售减、预占增），仅流水类型与幂等键不同。 */
    public void exchangeReserve(Long warehouseId, Long skuId, long quantity, String afterSaleNo,
                                Long operator, String now) {
        String key = EXCHANGE_RESERVE_KEY_PREFIX + afterSaleNo + ":" + skuId;
        if (ObjectUtil.isNotNull(flowMapper.selectByKeyIncludingDeleted(key))) {
            return;
        }
        int moved = stockMapper.reserveAtomic(warehouseId, skuId, quantity, operator, now);
        if (moved != 1) {
            // 可售不足：整事务回滚，绝不留半张补发单——半张单会让用户既没退钱也等不到货
            throw new JbkException("换货补发库存不足，无法预占");
        }
        writeFlow(key, warehouseId, skuId, MallEnum.StockFlowType.EXCHANGE_RESERVE,
                -quantity, quantity, "换货预占 " + afterSaleNo, operator, now);
    }

    /** 换货实际出库：用户签收补发单时发生，只扣预占。 */
    public void exchangeOut(Long warehouseId, Long skuId, long quantity, String afterSaleNo,
                            Long operator, String now) {
        String key = EXCHANGE_OUT_KEY_PREFIX + afterSaleNo + ":" + skuId;
        if (ObjectUtil.isNotNull(flowMapper.selectByKeyIncludingDeleted(key))) {
            return;
        }
        int moved = stockMapper.sellAtomic(warehouseId, skuId, quantity, operator, now);
        if (moved != 1) {
            throw new JbkException("换货出库失败（预占证据异常），售后单 " + afterSaleNo);
        }
        writeFlow(key, warehouseId, skuId, MallEnum.StockFlowType.EXCHANGE_OUT,
                0L, -quantity, "换货出库 " + afterSaleNo, operator, now);
    }

    /** 换货释放：补发取消或不可恢复失败时把预占还回可售。 */
    public void exchangeRelease(Long warehouseId, Long skuId, long quantity, String afterSaleNo,
                                Long operator, String now) {
        String key = EXCHANGE_RELEASE_KEY_PREFIX + afterSaleNo + ":" + skuId;
        if (ObjectUtil.isNotNull(flowMapper.selectByKeyIncludingDeleted(key))) {
            return;
        }
        int moved = stockMapper.releaseAtomic(warehouseId, skuId, quantity, operator, now);
        if (moved != 1) {
            throw new JbkException("换货释放失败（预占证据异常），售后单 " + afterSaleNo);
        }
        writeFlow(key, warehouseId, skuId, MallEnum.StockFlowType.EXCHANGE_RELEASE,
                quantity, -quantity, "换货释放 " + afterSaleNo, operator, now);
    }

    /** 事务内重读终值后落只增流水；唯一键兜并发。 */
    private void writeFlow(String key, Long warehouseId, Long skuId, MallEnum.StockFlowType type,
                           long availableChange, long reservedChange, String reason,
                           Long operator, String now) {
        WsMallStock after = stockMapper.selectOne(Wrappers.lambdaQuery(WsMallStock.class)
                .eq(WsMallStock::getWarehouseId, warehouseId)
                .eq(WsMallStock::getSkuId, skuId));
        if (ObjectUtil.isNull(after)) {
            throw new JbkException("库存行缺失，动作已中止");
        }
        WsMallStockFlow flow = new WsMallStockFlow()
                .setBizIdempotencyKey(key)
                .setWarehouseId(warehouseId)
                .setSkuId(skuId)
                .setFlowType(type.getValue())
                .setAvailableChange(availableChange)
                .setReservedChange(reservedChange)
                .setAvailableAfter(after.getAvailableQty())
                .setReservedAfter(after.getReservedQty())
                .setFlowReason(reason)
                .setOperatorId(operator);
        flow.setCreateTime(now);
        try {
            flowMapper.insert(flow);
        }
        catch (DuplicateKeyException race) {
            throw new JbkException("售后库存动作正在处理中，请稍后再试");
        }
    }
}
