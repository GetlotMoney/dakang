package com.jbk.serve.service.delivery;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsWalletFlow;
import com.jbk.tool.exception.JbkException;

/**
 * 配送扣款流水的定位与解读——<b>单一出处</b>。
 *
 * <p>这条流水是售后返还封顶的<b>基准</b>：{@code AfterSaleQuota.caps} 用它的
 * {@code AMOUNT_CHANGE}/{@code ML_CHANGE} 绝对值给出上限。基准若有两种取法，
 * 取消时算出的 caps 与执行时算出的 caps 就可能不同，而两次都"看起来合法"——
 * 封顶判定于是失去意义。故创单写入、取消回读、售后执行回读一律经过本类。</p>
 *
 * <p>定位口径固定为<b>业务幂等键</b>而非 {@code (ORDER_ID, FLOW_TYPE)}：
 * 幂等键由 {@code uk_wallet_flow_biz_key} 保证全库唯一，天然是"恰一条"；
 * 按订单+类型查则依赖"业务上不会有第二条"这一约定，约定一旦被破坏（补录、迁移、
 * 人工修数）就退化成静默取第一条或抛"不唯一"，而前者会直接退错钱。</p>
 *
 * @author dakang
 * @since 2026-07-29
 */
public final class DeliveryConsumeFlow {

    /** 配送扣款流水的业务幂等键前缀——创单写入与售后回读共用这一份约定。 */
    private static final String BIZ_KEY_PREFIX = "DELIVERY:";

    private DeliveryConsumeFlow() {
    }

    /** 业务幂等键：{@code DELIVERY:<订单号>}。写入与回读必须走同一个方法，否则前缀漂移无人察觉。 */
    public static String bizKey(String orderNo) {
        if (StrUtil.isBlank(orderNo)) {
            throw new JbkException("订单号缺失，无法生成配送扣款流水幂等键");
        }
        return BIZ_KEY_PREFIX + orderNo;
    }

    /**
     * 按订单回读扣款流水，并核验归属。
     *
     * <p>键对上而 {@code ORDER_ID}/{@code CARD_ID} 对不上，说明流水被改写或订单号被复用；
     * 此时按任一侧退款都可能把钱退到别人的卡上，故 fail-closed 而不是"就近取一个"。</p>
     */
    public static WsWalletFlow require(WsWalletFlowMapper mapper, WsOrder order) {
        if (mapper == null || ObjectUtil.isNull(order)) {
            throw new JbkException("配送扣款流水回读入参缺失");
        }
        WsWalletFlow flow = mapper.selectOne(Wrappers.lambdaQuery(WsWalletFlow.class)
                .eq(WsWalletFlow::getBizIdempotencyKey, bizKey(order.getOrderNo())));
        if (ObjectUtil.isNull(flow)) {
            throw new JbkException("配送订单原扣款流水缺失，无法确定返还基准");
        }
        if (ObjectUtil.notEqual(flow.getOrderId(), order.getId())
                || ObjectUtil.notEqual(flow.getCardId(), order.getCardId())) {
            throw new JbkException("配送订单原扣款流水归属错位，拒绝作为返还基准");
        }
        return flow;
    }

    /**
     * 扣款变动量的读取闸：必须为负或零。
     *
     * <p>正值意味着取到的是一条入账流水（例如此前的补偿），拿它当返还基准会把上限算成负数
     * 或反向放大，故直接拒绝而不是取绝对值糊过去。</p>
     */
    public static long requireChange(Long value, String label) {
        if (ObjectUtil.isNull(value)) {
            throw new JbkException(label + "缺失，无法确定返还基准");
        }
        if (value > 0) {
            throw new JbkException(label + "为正数(" + value + ")，不是扣款流水，拒绝作为返还基准");
        }
        return value;
    }
}
