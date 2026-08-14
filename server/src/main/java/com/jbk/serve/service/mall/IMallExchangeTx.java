package com.jbk.serve.service.mall;

/**
 * 商城换货补发事务（E2E-09 S4）。
 *
 * <p><b>不复制第二套履约状态机。</b>补发走一张 ORDER_AMOUNT_FEN=0 的内部订单，
 * 由 SOURCE_AFTER_SALE_ID 标识来源并直接进入「已支付待履约」，随后完全复用 S3 的七态
 * 履约链（拣货→打包→分配→取货→送达→用户签收）。该列唯一键保证一张售后单只补发一次。</p>
 *
 * <p>零价是刻意的：补发不计新销售收入，也不产生支付单与支付事实——它是履约动作，不是交易。</p>
 *
 * @author dakang
 * @since 2026-08-10
 */
public interface IMallExchangeTx {

    /**
     * 为已质检通过的换货售后单创建补发单：零价订单 + 明细 + 换货预占 + 履约任务。
     *
     * <p>幂等：同一售后单重复调用返回同一张补发单；库存不足整事务回滚，绝不留半张单。</p>
     *
     * @return 补发订单号
     */
    String createReshipment(Long afterSaleId, Long operatorId);
}
