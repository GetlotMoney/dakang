package com.jbk.serve.service.aftersale.refund;

import cn.hutool.core.util.StrUtil;
import com.jbk.tool.exception.JbkException;

/**
 * 支付机构对一次退款请求的<b>受理凭据</b>。
 *
 * <p>刻意不叫 Result：它表达的是「服务方收下了这次请求」，而不是「钱退成功了」。
 * 退款成功只能由退款事实经收件箱、Worker 核验后推进（R0-8）。
 * 如果这里叫 Result 并带一个 success 字段，下一个读者迟早会拿它直接改 REFUND_STATUS=2。</p>
 *
 * @param providerRefundId 服务方退款单号；受理即必须给出，后续事实靠它交叉核对
 * @param state            服务方受理态：{@link #STATE_PROCESSING} 处理中 / {@link #STATE_CLOSED} 已关闭
 */
public record RefundAcceptance(String providerRefundId, String state) {

    /** 服务方已受理、退款处理中。这是受理阶段唯一的正常态。 */
    public static final String STATE_PROCESSING = "PROCESSING";

    /** 服务方明确拒绝并关闭本次退款（例如原交易不可退）。 */
    public static final String STATE_CLOSED = "CLOSED";

    public RefundAcceptance {
        if (StrUtil.isBlank(providerRefundId)) {
            throw new JbkException("退款受理凭据缺少服务方退款单号");
        }
        // 白名单而非黑名单：受理阶段出现 SUCCESS 只可能是适配器实现越权直接判定了成功，
        // 那正是 R0-8 要禁止的事，必须在类型边界上就拒绝，而不是等到写库时才发现
        if (!STATE_PROCESSING.equals(state) && !STATE_CLOSED.equals(state)) {
            throw new JbkException("退款受理态非法：" + state + "（受理阶段只允许 PROCESSING/CLOSED，成功须经退款事实推进）");
        }
    }

    /** 服务方是否已受理并进入处理中。 */
    public boolean accepted() {
        return STATE_PROCESSING.equals(state);
    }
}
