package com.jbk.serve.service.mini.recharge;

import com.jbk.tool.data.trade.po.WsOrder;

/**
 * 充值订单创建的独立事务边界（L2 契约 §4.2）。
 *
 * <p>order 与 payment 必须在<b>同一个</b> {@code @Transactional(rollbackFor=Exception.class)}
 * 事务内写入，任一插入失败整体回滚——绝不允许出现"有订单没支付单"的半成品。</p>
 */
public interface IRechargeCreateTx {

    /**
     * 同事务写入 order + payment。
     *
     * @return 已落库的订单（含生成的 ID）
     */
    WsOrder create(WsOrder order, String payExpireTime, int paySource);
}
