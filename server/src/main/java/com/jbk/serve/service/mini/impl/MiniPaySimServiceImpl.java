package com.jbk.serve.service.mini.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.jbk.serve.mapper.trade.RechargeCreditMapper;
import com.jbk.serve.mapper.trade.RechargeIdentityMapper;
import com.jbk.serve.service.mini.IMiniPaySimService;
import com.jbk.serve.service.mini.recharge.IRechargePayFactService;
import com.jbk.serve.service.mini.recharge.IRechargePaySourceAdapter;
import com.jbk.serve.service.mini.recharge.RechargePayExpire;
import com.jbk.serve.service.mini.recharge.RechargePayStatus;
import com.jbk.serve.service.mini.recharge.RechargeQueryEventKey;
import com.jbk.tool.data.mini.bo.MiniPaySimBo;
import com.jbk.tool.data.mini.vo.MiniPaySimVo;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsPayment;
import com.jbk.tool.data.trade.po.WsPaymentEvent;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Pay-Sim 实现（仅 {@code mini.pay-sim.enabled=true} 时注册）。
 *
 * <p>外部事件号刻意<b>由订单号稳定派生</b>而非随机：这样重复点"支付"就等价于支付方重复通知，
 * 第二次会命中 {@code uk_payment_event_source_channel_key} 而复用同一条事实，
 * 于是「重复回调不会重复入账」在演示时可以被真正点出来，而不是只写在文档里。</p>
 *
 * <p>本类不写任何资金字段——它只造事实，钱由 {@code IRechargePayFactService} 那条唯一路径去动。</p>
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mini.pay-sim.enabled", havingValue = "true")
public class MiniPaySimServiceImpl implements IMiniPaySimService {

    private static final int ORDER_TYPE_RECHARGE = 2;
    /** 事实渠道：2 QUERY（主动查单）。真实微信查单接进来后用的是同一个通道号与同一条处理路径。 */
    private static final int FACT_CHANNEL_QUERY = 2;
    /** 事实渠道：3 Pay-Sim。 */
    private static final int FACT_CHANNEL_PAY_SIM = 3;
    /**
     * 校验方式：3 服务端内部模拟校验（1 微信签名 / 2 微信查询）。
     * 刻意不叫 HMAC——本实现只有服务端自造正文 + SHA-256 完整性摘要，没有共享密钥验证，
     * 称 HMAC 会让对账方误以为该事实具备不可伪造性。Demo 阶段也不为模拟通道添置无必要的密码学。
     */
    private static final int VERIFY_METHOD_SIM_INTERNAL = 3;
    private static final String CURRENCY_CNY = "CNY";

    private final RechargeIdentityMapper identityMapper;
    private final RechargeCreditMapper creditMapper;
    private final IRechargePayFactService factService;
    private final IRechargePaySourceAdapter paySourceAdapter;

    @Override
    public MiniPaySimVo pay(MiniPaySimBo bo, Long userId) {
        Target target = resolve(bo, userId, "拒绝模拟支付");
        WsOrder order = target.order();
        WsPayment payment = target.payment();
        String orderNo = order.getOrderNo();
        int paySource = target.paySource();

        String now = DateUtils.time();
        // 可支付性前置校验必须在造事实之前。若先落 SUCCESS 事实再由事务 A 判超时，
        // 结果是「钱没真收到、订单却被推进到 6 转人工」——比直接拒绝更糟。
        String reject = rejectReason(order, payment, now);
        if (reject != null) {
            throw new JbkException(reject);
        }

        String providerEventKey = "SIM-" + orderNo;
        String transactionId = "SIMTX" + orderNo;

        WsPaymentEvent existing = creditMapper.selectEventByProviderKey(
                paySource, FACT_CHANNEL_PAY_SIM, providerEventKey);
        Long eventId;
        if (existing != null) {
            eventId = existing.getId();
        } else {
            // 造一份与真实回调同构的报文并算真实摘要：RAW_BODY_SHA256 是「正文完整性摘要」，
            // 塞占位符会让这条事实在排障与对账时完全没有证据价值。
            String rawBody = simRawBody(orderNo, transactionId, payment.getPayAmount(), now);
            try {
                creditMapper.insertEvent(orderNo, order.getId(), payment.getId(), paySource,
                        FACT_CHANNEL_PAY_SIM, providerEventKey, RechargePayStatus.SUCCESS,
                        transactionId, payment.getPayAmount(), CURRENCY_CNY, now, userId, now,
                        rawBody, sha256Hex(rawBody), VERIFY_METHOD_SIM_INTERNAL);
            } catch (DuplicateKeyException e) {
                // 并发下另一方先落了同一条事实：唯一键挡住了第二条，下面重读复用即可
            }
            WsPaymentEvent saved = creditMapper.selectEventByProviderKey(
                    paySource, FACT_CHANNEL_PAY_SIM, providerEventKey);
            if (saved == null) {
                throw new JbkException("支付事实落库失败");
            }
            eventId = saved.getId();
        }

        IRechargePayFactService.Outcome outcome = factService.process(eventId);

        MiniPaySimVo vo = new MiniPaySimVo();
        vo.setOrderNo(orderNo);
        vo.setResultCode(outcome.code());
        vo.setMessage(outcome.message());
        vo.setTransactionId(transactionId);
        return vo;
    }

    // ------------------------------------------------------------------
    // 模拟支付方主动查单（FACT_CHANNEL=2 QUERY）
    // ------------------------------------------------------------------

    @Override
    public MiniPaySimVo query(MiniPaySimBo bo, Long userId) {
        Target target = resolve(bo, userId, "拒绝模拟查单");
        WsOrder order = target.order();
        WsPayment payment = target.payment();
        String orderNo = order.getOrderNo();
        int paySource = target.paySource();
        String now = DateUtils.time();

        String tradeState = simulateTradeState(orderNo, payment, now);
        // 规范串由「事实内容」派生（契约第 335 行）：同一订单 NOTPAY→CLOSED 会得到不同的键，
        // 因此先落的 NOTPAY 不会把后来的 CLOSED 挡在唯一键外面。
        // 非成功事实的四个外部字段一律传 null——支付方没返回就是没返回，
        // 用内部订单金额补造出来的"外部事实"在对账时是纯粹的伪证。
        String providerEventKey = RechargeQueryEventKey.derive(
                paySource, orderNo, tradeState, null, null, null, null);
        String rawBody = queryRawBody(orderNo, tradeState, now);
        String sha256 = sha256Hex(rawBody);

        WsPaymentEvent existing = creditMapper.selectEventByProviderKey(
                paySource, FACT_CHANNEL_QUERY, providerEventKey);
        Long eventId;
        if (existing != null) {
            eventId = requireSameDigest(existing.getId(), sha256);
        } else {
            try {
                creditMapper.insertEvent(orderNo, order.getId(), payment.getId(), paySource,
                        FACT_CHANNEL_QUERY, providerEventKey, tradeState,
                        null, null, null, null, userId, now,
                        rawBody, sha256, VERIFY_METHOD_SIM_INTERNAL);
            } catch (DuplicateKeyException e) {
                // 并发查单：唯一键挡住第二条，下面重读复用
            }
            WsPaymentEvent saved = creditMapper.selectEventByProviderKey(
                    paySource, FACT_CHANNEL_QUERY, providerEventKey);
            if (saved == null) {
                throw new JbkException("查单事实落库失败");
            }
            eventId = requireSameDigest(saved.getId(), sha256);
        }

        // 与真实查单完全同路：造完事实就交给同一个处理器，Pay-Sim 不自己推进任何状态
        IRechargePayFactService.Outcome outcome = factService.process(eventId);

        MiniPaySimVo vo = new MiniPaySimVo();
        vo.setOrderNo(orderNo);
        vo.setTradeState(tradeState);
        vo.setResultCode(outcome.code());
        vo.setMessage(outcome.message());
        return vo;
    }

    /**
     * 模拟<b>支付方</b>对这笔单子的认知，返回 NOTPAY 或 CLOSED。
     *
     * <p><b>这不是本地超时关单。</b>区别在于：本地关单是"我方到点就把订单改成已关闭"，
     * 而这里是"我方去问支付方，支付方按它自己的收单有效期回答该单已关闭"。
     * 契约第 369/446 行要求 {@code payment 4/order 5} 只能由支付方权威结果驱动——
     * 本方法扮演的是那个支付方，真正的关单动作仍由事实处理器按 CAS 完成。</p>
     *
     * <p>模拟支付方的判据只能用它自己知道的信息：这笔单的付款截止时间是否已过。
     * 真实微信同样是按它下发预支付单时的有效期决定 CLOSED 的。</p>
     *
     * <p>若本地已存在该订单的成功事实，说明模拟支付方"收过款"，它就不可能回答未支付/已关闭。
     * 此处不伪造 SUCCESS 查询事实（那需要交易号等权威字段，Pay-Sim 的成功事实由 /pay 产生），
     * 直接拒绝，绝不返回一个与已知事实矛盾的答案。</p>
     */
    private String simulateTradeState(String orderNo, WsPayment payment, String now) {
        List<WsPaymentEvent> events = identityMapper.selectEventsByOrderNoIncludingDeleted(orderNo);
        if (events != null && events.stream()
                .anyMatch(e -> RechargePayStatus.SUCCESS.equals(e.getTradeState()))) {
            throw new JbkException("模拟支付方已记录该订单的支付成功事实，查单不会返回未支付或已关闭");
        }
        if (StrUtil.isBlank(payment.getPayExpireTime())) {
            // 截止时间缺失时支付方无从判断是否该关单，fail-closed 拒绝而不是默认关闭
            throw new JbkException("订单付款截止时间缺失，模拟支付方无法给出查单结论");
        }
        return RechargePayExpire.paidInTime(now, payment.getPayExpireTime())
                ? RechargePayStatus.NOTPAY
                : RechargePayStatus.CLOSED;
    }

    /** 契约 §5.3：同键重复到达必须比对正文摘要，不一致即人工核查，不得复用旧事实。 */
    private Long requireSameDigest(Long eventId, String sha256) {
        if (creditMapper.countEventMatchingDigest(eventId, sha256) != 1) {
            throw new JbkException("同一查单事实键下的正文摘要不一致，已拒绝复用，请人工核查");
        }
        return eventId;
    }

    /**
     * 定位并校验目标订单/支付单。归属校验与 pay-status 同一口径：
     * 不存在与非本人返回同一句，不成为订单号探测信道。
     */
    private Target resolve(MiniPaySimBo bo, Long userId, String refuseLabel) {
        if (userId == null || StrUtil.isBlank(bo.getOrderNo())) {
            throw new JbkException("参数不完整");
        }
        String orderNo = bo.getOrderNo().trim();
        List<WsOrder> orders = identityMapper.selectOrdersByOrderNoIncludingDeleted(orderNo);
        if (orders == null || orders.size() != 1) {
            throw new JbkException("订单不存在或无权访问");
        }
        WsOrder order = orders.get(0);
        if (!ObjectUtil.equals(order.getUserId(), userId)
                || !ObjectUtil.equals(order.getDataStatus(), 0)
                || !ObjectUtil.equals(order.getOrderType(), ORDER_TYPE_RECHARGE)) {
            throw new JbkException("订单不存在或无权访问");
        }
        List<WsPayment> payments = identityMapper.selectPaymentsByOrderIdIncludingDeleted(order.getId());
        if (payments == null || payments.size() != 1 || !ObjectUtil.equals(payments.get(0).getDataStatus(), 0)) {
            throw new JbkException("订单支付单异常，请联系客服");
        }
        WsPayment payment = payments.get(0);
        int paySource = paySourceAdapter.currentSource();
        if (!ObjectUtil.equals(payment.getPaySource(), paySource)) {
            // 支付单是微信来源却想用 Pay-Sim 操作：拒绝，绝不让模拟事实混进真实收款口径
            throw new JbkException("支付单来源与当前适配器不一致，" + refuseLabel);
        }
        return new Target(order, payment, paySource);
    }

    private record Target(WsOrder order, WsPayment payment, int paySource) {
    }

    /**
     * 可支付性判定：返回 {@code null} 表示放行，否则返回可区分的拒因。
     *
     * <p>只放行契约 §9.1 的「待支付」组合 {@code payment 1/order 1}，且当前时刻不晚于不可变
     * {@code PAY_EXPIRE_TIME}。这里刻意<b>不</b>改动任何金额、截止时间或状态机规则——
     * 「继续支付」只是给既有支付动作补一个入口，不引入新的资金语义。</p>
     *
     * <p>拒因按订单态优先给出：订单已终态时，用户要看到的是「这单已完成/已取消/已转人工」，
     * 而不是笼统一句「不可支付」——笼统文案会让用户反复重试同一个永远付不成的单。</p>
     */
    private static String rejectReason(WsOrder order, WsPayment payment, String now) {
        int ord = order.getOrderStatus() == null ? -1 : order.getOrderStatus();
        int pay = payment.getPayStatus() == null ? -1 : payment.getPayStatus();

        switch (ord) {
            case RechargePayStatus.ORDER_PENDING:
                break;
            case RechargePayStatus.ORDER_PAID:
                return "订单已支付，权益正在入账中，无需重复支付";
            case RechargePayStatus.ORDER_FINISHED:
                return "订单已完成，无需再次支付";
            case RechargePayStatus.ORDER_CANCELLED:
                return "订单已取消或关闭，无法继续支付，请重新下单";
            case RechargePayStatus.ORDER_ABNORMAL:
                return "订单支付异常已转人工对账，请联系客服，不可重复支付";
            case RechargePayStatus.ORDER_REFUNDED:
                return "订单已退款，无法继续支付";
            default:
                // 未登记组合一律 fail-closed：不认识的状态绝不当成可支付
                return "订单当前状态不可支付，请联系客服";
        }

        if (pay == RechargePayStatus.PAY_SUCCESS) {
            // order 1/payment 2 本身已是不自洽数据，但对用户而言最重要的事实是「钱已经收到了」
            return "该订单已收到支付成功事实，无需重复支付";
        }
        if (pay == RechargePayStatus.PAY_CLOSED) {
            return "该订单支付已关闭，无法继续支付，请重新下单";
        }
        if (pay != RechargePayStatus.PAY_PENDING) {
            return "订单支付单状态异常，无法继续支付，请联系客服";
        }

        if (StrUtil.isBlank(payment.getPayExpireTime())) {
            // 截止时间是付款资格的唯一依据，缺失时无从判断按时与否，只能拒
            return "订单付款截止时间缺失，无法继续支付，请联系客服";
        }
        if (!RechargePayExpire.paidInTime(now, payment.getPayExpireTime())) {
            return "该订单已超过付款截止时间（" + payment.getPayExpireTime() + "），无法继续支付，请重新下单";
        }
        return null;
    }

    /**
     * 查单证据报文（字段顺序固定，保证摘要可复算）。
     *
     * <p>刻意<b>不含</b>金额与交易号：支付方在 NOTPAY/CLOSED 时本来就不返回这些字段，
     * 把内部值写进"外部证据"会让这条 RAW_BODY 在事后对账时被误当成支付方的原话。</p>
     */
    private static String queryRawBody(String orderNo, String tradeState, String queryTime) {
        return "{\"source\":\"PAY_SIM\",\"channel\":\"QUERY\",\"out_trade_no\":\"" + orderNo
                + "\",\"trade_state\":\"" + tradeState + "\",\"query_time\":\"" + queryTime + "\"}";
    }

    /** 与真实微信通知同构的最小报文（字段顺序固定，保证摘要可复算）。 */
    private static String simRawBody(String orderNo, String transactionId, Long payAmount, String successTime) {
        return "{\"source\":\"PAY_SIM\",\"out_trade_no\":\"" + orderNo
                + "\",\"transaction_id\":\"" + transactionId
                + "\",\"trade_state\":\"SUCCESS\",\"amount\":{\"total\":" + payAmount
                + ",\"currency\":\"" + CURRENCY_CNY + "\"},\"success_time\":\"" + successTime + "\"}";
    }

    private static String sha256Hex(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new JbkException("摘要算法不可用");
        }
    }
}
