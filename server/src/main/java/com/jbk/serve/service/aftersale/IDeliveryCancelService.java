package com.jbk.serve.service.aftersale;

import com.jbk.tool.data.aftersale.bo.DeliveryCancelBo;

/**
 * 待接单取消编排（E2E-04 包A，售后来源 1配送取消）。实现类不带 {@code @Transactional}：
 * 业务段（任务 1→6 + 订单 2→7 + 登记返还）、认领段（REQUIRES_NEW）、资金段
 * （REQUIRES_NEW + READ_COMMITTED）三段必须分开提交，裹进一个事务即 P0#1 的病灶。
 *
 * <p>「订单已取消而资金未落」的中间态是刻意代价：先退钱再改状态会让并发取消各退一次款，
 * 状态先行至少保证最多退一次，缺口由「需人工对账」承接。用户提示必须说
 * 「退款处理中，如未到账请联系客服」，绝不能说成已到账。</p>
 *
 * @author dakang
 * @since 2026-07-29
 */
public interface IDeliveryCancelService {

    /**
     * 用户自助取消待接单的配送订单，并推进整单满额返还。
     *
     * @param bo     取消入参（只有订单号）
     * @param userId 会话用户ID（归属过滤，铁律6：不接受前端自报身份）
     * @return 面向用户的结果文案：资金已落卡与「退款处理中」两种口径必须可区分
     */
    String cancelPendingOrder(DeliveryCancelBo bo, Long userId);
}
