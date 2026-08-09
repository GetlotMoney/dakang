package com.jbk.serve.service.settlement;

import com.jbk.tool.data.aftersale.po.WsAfterSaleAction;

/**
 * 分润退款冲减（D-420 R2 两段式：动作级 outbox）。
 *
 * <p>业务源=售后动作（ws_after_sale_action）：CARD_REFUND 卡内退款与 GATEWAY_REFUND
 * 机构退款成功后按 <b>REFUND_PRODUCT_FEN</b>（实际水品退款额）冲减水费线分润；
 * CARD_COMPENSATE 补偿与 REFUND_SERVICE_FEN 配送费返还不冲减（D-414/D-419 冻结口径）；
 * 纯水量返还（REFUND_PRODUCT_FEN=0）零货币冲减。</p>
 *
 * <p>两段式：{@link #registerForAction} 在客户退款成功的同一事务内登记<b>一条</b>
 * 动作级 outbox（ws_split_clawback_action，ACTION_ID 唯一，冻结不可变参数）——
 * 登记是不可失败路径，冲减侧任何证据/业务异常都置需人工而绝不回滚退款；
 * {@link #processAction} 独立事务对账后动账，可重试、可转人工。
 * 多次部分退款=多个动作各自登记、同一分账行 REVERSED_AMOUNT 累计。</p>
 */
public interface ISplitClawbackTxService {

    /**
     * 段1·登记（调用方持有退款成功事务，R2-P0-1 不可失败路径）：单行 INSERT 动作级
     * outbox，冻结 actionId/orderId/actionType/refundProductFen。<b>零</b>分账行读、
     * <b>零</b>任务行读、<b>零</b>快照解析、<b>零</b>额度校验——重活全在段2。
     *
     * <p>非冲减动作（补偿/纯水量/零额）为合法 no-op。ACTION_ID 撞键走不可变参数
     * 等价核验（R2-P1-1）：逐字相同=幂等返回；参数漂移=既有登记置需人工（fail-closed），
     * 两种情况都不产生第二份事实、不抛异常、不回滚本次退款。</p>
     */
    void registerForAction(WsAfterSaleAction action, String now);

    /**
     * 段2·执行（REQUIRES_NEW 独立事务，幂等可重试，R2-P0-2 对账后动账）：
     * 锁 outbox 行 → 权威动作复核（过滤逻辑删；SUCCESS；类型仍为冲减型；
     * actionId/orderId/refundProductFen 与登记逐字相等）→ 权威订单与来源分类
     * （R3-P1-1：无分账收敛仅限待接单取消/充值退款白名单；申诉、取水异常等履约后
     * 来源分账行缺失=证据丢失转人工；来源↔订单类型不符或未知来源恒 fail-closed）→
     * 锁分账行与明细事实（平台 REMAINDER 行恒一行）→ 按锁内权威份额重算期望明细集
     * （累计上限排除本动作）→ 对账（集合完备/双键匹配/逐行金额相等/合计恒等退款额）
     * → 全部合格才动账：首执插明细、行 REVERSED_AMOUNT 累计、已入账（DONE）份额
     * 从收益账户扣回（缺口进 CLAWBACK_DEFICIT_FEN，恒不为负）。完成置 2；结构性
     * 失败抛出由 Worker 下轮重试；任何证据不合格=<b>整动作</b>置 3 转人工、
     * 零资金/份额更新——不存在部分完成部分人工的混合中间态。
     */
    void processAction(Long actionId, String now);
}
