package com.jbk.serve.service.aftersale.refund;

import cn.hutool.core.util.StrUtil;
import com.jbk.tool.consts.aftersale.RefundEnum;
import com.jbk.tool.exception.JbkException;

/**
 * 一条<b>规范化后的退款事实</b>（E2E-04 包B，R0-8）。
 *
 * <p>「规范化」指：签名/HMAC 已验、字段已从服务方报文里解出来并对齐到本系统的口径。
 * 适配器负责产出它，收件箱负责存它，Worker 负责消费它。三段职责不重叠。</p>
 *
 * <h3>为什么 SUCCESS 的四个字段必须在这里就校验齐</h3>
 * <p>金额、币种、服务方单号、成功时间——这四项是把一张退款单推成「成功」的全部依据。
 * 缺任何一项都意味着我们无法核对「退的是不是这一单、退了多少」。
 * 若允许它们为空后进库，Worker 就只能在推进时用<b>本地值</b>去顶替，
 * 而那恰恰取消了核对本身：本地值当然等于本地值，任何错款都能对上。
 * 故 fail-closed 在类型边界，而不是等到消费时。</p>
 *
 * @param source           退款来源(1373)，由服务端适配器给出
 * @param factChannel      事实渠道：1通知 2查询 3Refund-Sim
 * @param providerEventKey 外部事实键；与来源+渠道构成幂等键
 * @param refundNo         商户退款单号
 * @param orderNo          事实携带的商户订单号，用于交叉核对；可空
 * @param state            规范化事实状态，见 {@link RefundEnum.FactState}
 * @param providerRefundId 服务方退款单号；SUCCESS 必填
 * @param amountFen        服务方返回退款金额（分）；SUCCESS 必填
 * @param currency         服务方返回币种；SUCCESS 必填且为 CNY
 * @param successTime      服务方给出的成功时间；SUCCESS 必填
 * @param rawBody          原始正文；只用于摘要与人工排查，禁止出接口
 * @param verifyMethod     校验方式，见 {@link RefundEnum.VerifyMethod}
 */
public record RefundFact(int source, int factChannel, String providerEventKey, String refundNo,
                         String orderNo, String state, String providerRefundId, Long amountFen,
                         String currency, String successTime, String rawBody, int verifyMethod) {

    private static final int TIME_LEN = 14;

    public RefundFact {
        if (StrUtil.isBlank(providerEventKey)) {
            throw new JbkException("退款事实缺少外部事实键，无法幂等");
        }
        if (StrUtil.isBlank(refundNo)) {
            throw new JbkException("退款事实缺少商户退款单号");
        }
        if (StrUtil.isBlank(rawBody)) {
            throw new JbkException("退款事实缺少原始正文，无法生成完整性摘要");
        }
        // 来源必须是已登记值：脏来源会让幂等键落进一个没人消费的命名空间
        RefundEnum.Source.getByValue(source);
        if (!isKnownState(state)) {
            throw new JbkException("退款事实状态未规范化：" + state);
        }
        if (RefundEnum.FactState.SUCCESS.equals(state)) {
            if (StrUtil.isBlank(providerRefundId)) {
                throw new JbkException("SUCCESS 退款事实必须携带服务方退款单号");
            }
            if (amountFen == null || amountFen <= 0) {
                throw new JbkException("SUCCESS 退款事实必须携带正的退款金额，实际 " + amountFen);
            }
            if (!"CNY".equals(currency)) {
                throw new JbkException("SUCCESS 退款事实币种必须为 CNY，实际 " + currency);
            }
            if (successTime == null || successTime.length() != TIME_LEN) {
                throw new JbkException("SUCCESS 退款事实必须携带合法成功时间");
            }
        }
    }

    public boolean success() {
        return RefundEnum.FactState.SUCCESS.equals(state);
    }

    private static boolean isKnownState(String state) {
        return RefundEnum.FactState.SUCCESS.equals(state)
                || RefundEnum.FactState.PROCESSING.equals(state)
                || RefundEnum.FactState.CLOSED.equals(state)
                || RefundEnum.FactState.ABNORMAL.equals(state)
                || RefundEnum.FactState.UNKNOWN.equals(state);
    }
}
