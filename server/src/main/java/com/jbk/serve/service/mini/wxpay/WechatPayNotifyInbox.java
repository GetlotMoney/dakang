package com.jbk.serve.service.mini.wxpay;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.jbk.serve.mapper.trade.RechargeCreditMapper;
import com.jbk.serve.mapper.trade.RechargeIdentityMapper;
import com.jbk.serve.service.mall.IMallPayFactService;
import com.jbk.serve.service.mall.impl.MallOrderNo;
import com.jbk.serve.service.mini.recharge.IRechargePayFactService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.mall.MallEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.mall.po.WsMallPaymentFact;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsPayment;
import com.jbk.tool.data.trade.po.WsPaymentEvent;
import com.jbk.tool.utils.DateUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 微信支付回调收件箱（WX-ECO S3）：验签 → 解密 → 按订单号前缀路由进既有支付事实收件箱。
 * 不重造状态机：金额/币种/交易号的共键核验只在 {@link IRechargePayFactService} /
 * {@link IMallPayFactService} 的推进段——这里再比对金额会造出第二套核验口径。
 *
 * <p>异常分类（任务书 S3，确定性异常一律留痕转人工）：验签不过回 401 不留痕；
 * 信封不完整/解密失败/字段非法回 500 留痕；订单不属本系统回 200 停掉注定失败的重试；
 * 事实已落但推进转人工也回 200（重试只会撞幂等键空转）；瞬时异常回 500 不留痕，重试即自愈。
 * 幂等锚 {@code uk(PAY_SOURCE, FACT_CHANNEL, PROVIDER_EVENT_KEY)}，NOTIFY 通道按契约 §335
 * 用支付方通知 id，先查后建、撞键重读，与 Pay-Sim 同一套惯用法。</p>
 *
 * @author dakang
 * @since 2026-08-13
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WechatPayNotifyInbox {

    /** 事实渠道：1 NOTIFY。与 Pay-Sim(3)、QUERY(2) 同一列不同值。 */
    static final int FACT_CHANNEL_NOTIFY = 1;
    /** 校验方式：1 微信签名。 */
    static final int VERIFY_METHOD_WECHAT_SIGNATURE = 1;
    /** 支付来源：1 微信（服务端常量，绝不来自报文自报）。 */
    static final int PAY_SOURCE_WECHAT = 1;

    /** 充值订单号前缀（RechargeOrderNo）。 */
    private static final String RECHARGE_PREFIX = "RC";

    private static final Pattern TRADE_STATE = Pattern.compile("^[A-Z_]{1,32}$");
    private static final Pattern TRANSACTION_ID = Pattern.compile("^[0-9A-Za-z_-]{1,64}$");
    private static final DateTimeFormatter TIME14 = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final WechatPayNotifyVerifier verifier;
    private final WechatPayCipher cipher;
    private final RechargeIdentityMapper rechargeIdentityMapper;
    private final RechargeCreditMapper rechargeCreditMapper;
    private final IRechargePayFactService rechargeFactService;
    private final IMallPayFactService mallPayFactService;
    private final IWsDomainEventService domainEventService;

    /** 收件结果：HTTP 状态 + 微信要求的应答码。SUCCESS 停止重试，FAIL 触发微信按退避重试。 */
    public record Outcome(int httpStatus, String code, String message) {
        static Outcome accepted() {
            return new Outcome(200, "SUCCESS", "成功");
        }

        static Outcome rejected(int status, String message) {
            return new Outcome(status, "FAIL", message);
        }
    }

    /**
     * 处理一条支付结果回调。
     *
     * @param rawBody 原始请求体（验签必须用原文，重新序列化过的 JSON 验不过）
     */
    public Outcome onPaymentNotify(String serial, String timestamp, String nonce,
                                   String signature, String rawBody) {
        // 验签不过不留业务痕；响应不给失败细节，那是伪造者的排错提示
        if (!verifier.verify(serial, timestamp, nonce, rawBody, signature)) {
            return Outcome.rejected(401, "签名验证失败");
        }
        try {
            return acceptVerified(rawBody, new VerifyMaterial(serial, timestamp, nonce, signature));
        }
        catch (Exception e) {
            // 瞬时异常回 500 让微信退避重试。绝不能逃逸到全局处理器——它回 HTTP 200，
            // 微信视为送达永久停止重试，已付款事实丢失；且异常计入 IP 封禁计数。
            log.error("微信支付回调处理异常，已回 FAIL 等待微信重试", e);
            return Outcome.rejected(500, "处理异常，请重试");
        }
    }

    /** 已验签通知的四份核验材料：随事实落库，供事后用平台公钥复核这条通知。 */
    record VerifyMaterial(String serial, String timestamp, String nonce, String signature) {
    }

    private Outcome acceptVerified(String rawBody, VerifyMaterial material) {

        // 已验签：报文确系微信所发，读不懂/解不开都是我方问题，要留痕
        JSONObject envelope;
        String notifyId;
        JSONObject resource;
        try {
            envelope = JSONUtil.parseObj(rawBody);
            String eventType = envelope.getStr("event_type");
            if (eventType != null && eventType.startsWith("REFUND")) {
                // 退款通知本轮未接入：留痕 + 500 让微信持续重试，绝不静默 ACK 一条钱已退的事实
                return manual(rawBody, 500, "退款通知未接入，事实已留痕待人工");
            }
            notifyId = envelope.getStr("id");
            resource = envelope.getJSONObject("resource");
            if (StrUtil.isBlank(notifyId) || resource == null
                    || StrUtil.isBlank(resource.getStr("nonce"))
                    || StrUtil.isBlank(resource.getStr("ciphertext"))) {
                return manual(rawBody, 500, "回调信封结构不完整");
            }
        }
        catch (RuntimeException e) {
            return manual(rawBody, 500, "回调正文不是合法 JSON");
        }

        String plain;
        try {
            plain = cipher.decrypt(resource.getStr("associated_data"),
                    resource.getStr("nonce"), resource.getStr("ciphertext"));
        }
        catch (Exception e) {
            // APIv3 密钥不符或密文损坏：留痕后仍回 500，密钥修好后微信的重试即补投
            log.error("微信支付回调解密失败 notifyId={}", notifyId, e);
            return manual(rawBody, 500, "回调解密失败（核对 APIv3 密钥）notifyId=" + notifyId);
        }

        Transaction tx;
        try {
            tx = parseTransaction(plain);
        }
        catch (IllegalArgumentException e) {
            return manual(rawBody, 500, "回调字段非法：" + e.getMessage() + " notifyId=" + notifyId);
        }
        catch (RuntimeException e) {
            return manual(rawBody, 500, "回调明文不是合法交易报文 notifyId=" + notifyId);
        }

        if (tx.outTradeNo().startsWith(RECHARGE_PREFIX)) {
            return acceptRecharge(notifyId, tx, rawBody, material);
        }
        if (tx.outTradeNo().startsWith(MallOrderNo.PREFIX)) {
            return acceptMall(notifyId, tx, rawBody);
        }
        // 不是本系统的单：回 SUCCESS 停掉注定失败的三天重试，证据已留
        return manualStopRetry(rawBody, "回调订单号前缀不属于本系统：" + tx.outTradeNo());
    }

    // ------------------------------------------------------------------
    // 充值链：ws_payment_event 收件箱（与 Pay-Sim 同一张表、同一个推进器）
    // ------------------------------------------------------------------

    private Outcome acceptRecharge(String notifyId, Transaction tx, String rawBody,
                                   VerifyMaterial material) {
        List<WsOrder> orders = rechargeIdentityMapper.selectOrdersByOrderNoIncludingDeleted(tx.outTradeNo());
        if (orders == null || orders.size() != 1) {
            return manualStopRetry(rawBody, "回调指向不存在的充值订单：" + tx.outTradeNo());
        }
        WsOrder order = orders.get(0);
        List<WsPayment> payments = rechargeIdentityMapper.selectPaymentsByOrderIdIncludingDeleted(order.getId());
        if (payments == null || payments.size() != 1) {
            return manualStopRetry(rawBody, "充值订单支付单缺失或不唯一：" + tx.outTradeNo());
        }
        WsPayment payment = payments.get(0);
        if (!ObjectUtil.equals(payment.getPaySource(), PAY_SOURCE_WECHAT)) {
            // 支付单是 Pay-Sim 来源却收到微信回调：环境窜线，绝不让真实收款混进模拟口径
            return manualStopRetry(rawBody, "支付单来源非微信却收到微信回调：" + tx.outTradeNo());
        }

        String now = DateUtils.time();
        WsPaymentEvent existing = rechargeCreditMapper.selectEventByProviderKey(
                PAY_SOURCE_WECHAT, FACT_CHANNEL_NOTIFY, notifyId);
        Long eventId;
        if (existing != null) {
            // 重放核对语义字段而非正文摘要：微信重投会重新加密（nonce 不同则字节不同），
            // 比对原文会把合法重投误判成篡改；同 id 异内容则必须转人工（契约 §5.3 同向）
            if (!sameSemantics(existing, tx)) {
                return manual(rawBody, 500, "同一通知id携带不一致内容，待人工核查：" + notifyId);
            }
            eventId = existing.getId();
        }
        else {
            try {
                rechargeCreditMapper.insertNotifyEvent(tx.outTradeNo(), order.getId(), payment.getId(),
                        PAY_SOURCE_WECHAT, FACT_CHANNEL_NOTIFY, notifyId, tx.tradeState(),
                        tx.transactionId(), tx.amountFen(), tx.currency(), tx.successTime14(),
                        0L, now, rawBody, DigestUtil.sha256Hex(rawBody),
                        VERIFY_METHOD_WECHAT_SIGNATURE, material.serial(), material.timestamp(),
                        material.nonce(), material.signature());
            }
            catch (DuplicateKeyException e) {
                // 微信并发重投：唯一键挡住第二条，下面重读复用
            }
            WsPaymentEvent saved = rechargeCreditMapper.selectEventByProviderKey(
                    PAY_SOURCE_WECHAT, FACT_CHANNEL_NOTIFY, notifyId);
            if (saved == null) {
                // 落库失败：瞬时问题，回 FAIL 让微信重试，不留痕（重试即自愈）
                return Outcome.rejected(500, "支付事实落库失败，请重试");
            }
            eventId = saved.getId();
        }

        IRechargePayFactService.Outcome outcome = rechargeFactService.process(eventId);
        // 事实已安全落库，无论推进结果如何都回 SUCCESS——重试只会撞幂等键空转，
        // RECONCILIATION/MISMATCH 的证据由推进段留在事实表
        log.info("微信支付回调已入账处理 orderNo={} outcome={}", tx.outTradeNo(), outcome.code());
        return Outcome.accepted();
    }

    // ------------------------------------------------------------------
    // 商城链：ws_mall_payment_fact 收件箱（两段式：recordFact 事务A + process 事务B）
    // ------------------------------------------------------------------

    private Outcome acceptMall(String notifyId, Transaction tx, String rawBody) {
        WsMallPaymentFact fact;
        try {
            // 直调 recordFact 而非先 findFact 短路：短路查询会绕掉 requireSameFact 同键内容核验
            fact = mallPayFactService.recordFact(
                    MallEnum.PaySource.WECHAT.getValue(), MallEnum.FactChannel.NOTIFY.getValue(),
                    notifyId, tx.outTradeNo(), tx.tradeState(), tx.transactionId(),
                    tx.amountFen(), tx.successTime14(), MallEnum.VerifyMethod.WECHAT_SIGNATURE, rawBody);
        }
        catch (JbkException same) {
            // requireSameFact 拒绝：同键异文，确定性问题，重试不自愈
            return manual(rawBody, 500, "商城支付事实同键异文，待人工核查：" + notifyId);
        }
        mallPayFactService.process(fact.getId());
        return Outcome.accepted();
    }

    // ------------------------------------------------------------------
    // 解密后字段的白名单校验与归一
    // ------------------------------------------------------------------

    /** 解密后的交易报文，字段已过白名单。非成功状态时外部四字段保持 null（禁止补造）。 */
    record Transaction(String outTradeNo, String tradeState, String transactionId,
                       Long amountFen, String currency, String successTime14) {
    }

    /** 重放核验：五个语义字段逐项一致才算同一事实。null 与 null 视为一致（非成功事实）。 */
    private static boolean sameSemantics(WsPaymentEvent existing, Transaction tx) {
        return ObjectUtil.equals(existing.getTradeState(), tx.tradeState())
                && ObjectUtil.equals(existing.getTransactionId(), tx.transactionId())
                && ObjectUtil.equals(existing.getPayAmount(), tx.amountFen())
                && ObjectUtil.equals(existing.getCurrency(), tx.currency())
                && ObjectUtil.equals(existing.getPaySuccessTime(), tx.successTime14());
    }

    private static Transaction parseTransaction(String plainJson) {
        JSONObject obj = JSONUtil.parseObj(plainJson);
        String outTradeNo = obj.getStr("out_trade_no");
        String tradeState = obj.getStr("trade_state");
        if (StrUtil.isBlank(outTradeNo) || !outTradeNo.matches("^[0-9A-Za-z]{1,32}$")) {
            throw new IllegalArgumentException("out_trade_no 缺失或越界");
        }
        if (StrUtil.isBlank(tradeState) || !TRADE_STATE.matcher(tradeState).matches()) {
            throw new IllegalArgumentException("trade_state 缺失或越界");
        }
        if (!"SUCCESS".equals(tradeState)) {
            // 非成功事实外部字段一律 null：用报文碰巧存在的值填充会在对账时变成伪证
            return new Transaction(outTradeNo, tradeState, null, null, null, null);
        }
        String transactionId = obj.getStr("transaction_id");
        JSONObject amount = obj.getJSONObject("amount");
        String successTime = obj.getStr("success_time");
        if (StrUtil.isBlank(transactionId) || !TRANSACTION_ID.matcher(transactionId).matches()) {
            throw new IllegalArgumentException("成功事实缺 transaction_id");
        }
        if (amount == null || amount.getLong("total") == null || amount.getLong("total") <= 0) {
            throw new IllegalArgumentException("成功事实金额缺失或非正");
        }
        String currency = amount.getStr("currency");
        if (StrUtil.isBlank(currency) || !currency.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("成功事实币种缺失或非法");
        }
        if (StrUtil.isBlank(successTime)) {
            throw new IllegalArgumentException("成功事实缺 success_time");
        }
        return new Transaction(outTradeNo, tradeState, transactionId,
                amount.getLong("total"), currency, toTime14(successTime));
    }

    /** RFC3339（微信带 +08:00）→ 业务时区 yyyyMMddHHmmss。带其他时差的合法时间也先归一再落库。 */
    private static String toTime14(String rfc3339) {
        try {
            return OffsetDateTime.parse(rfc3339).atZoneSameInstant(BUSINESS_ZONE).format(TIME14);
        }
        catch (RuntimeException e) {
            throw new IllegalArgumentException("success_time 不是合法 RFC3339 时间：" + rfc3339);
        }
    }

    // ------------------------------------------------------------------
    // 人工对账留痕（幂等）
    // ------------------------------------------------------------------

    /** 留痕并回 FAIL：微信会继续按退避重试，修复配置后重试即接住。 */
    /**
     * 留痕并回 FAIL：本就要微信重试的场合。留痕失败不改变重试语义——
     * 反正会重投，下一次还有机会补上证据。
     */
    private Outcome manual(String rawBody, int status, String reason) {
        recordEvidence(rawBody, reason);
        return Outcome.rejected(status, reason);
    }

    /**
     * 留痕并回 SUCCESS：重试注定无效的场合停掉重试，人工从证据接手。
     *
     * <p><b>证据落库成功是回 200 的前提</b>：200/SUCCESS 会让微信永久停止重试，
     * 若此时证据没写进去，这笔已付款事实就既不在库里、也不会再投递一次——钱静默悬空。
     * 因此写入失败必须降级为 500/FAIL 让微信继续按退避重试，而不是吞掉异常照回成功。</p>
     */
    private Outcome manualStopRetry(String rawBody, String reason) {
        if (!recordEvidence(rawBody, reason)) {
            return Outcome.rejected(500, "人工对账证据写入失败，请重试");
        }
        return new Outcome(200, "SUCCESS", reason);
    }

    /**
     * 幂等留痕。
     *
     * @return true=证据已可靠落库；false=写入失败（调用方据此决定能否停掉微信重试）
     */
    private boolean recordEvidence(String rawBody, String reason) {
        // 幂等键=正文摘要：同一问题报文重投多少次都只留一条证据；键前缀与事实表命名空间互斥
        String key = "WXPAYRAW:" + DigestUtil.sha256Hex(rawBody).substring(0, 48);
        try {
            domainEventService.recordReliableOnceIndependent(OpsEnum.EventType.ORDER_STATUS,
                    "WXPAY_NOTIFY", key, null, StrUtil.maxLength(reason, 480));
            return true;
        }
        catch (Exception e) {
            // 异常在此收敛，绝不逃逸到全局处理器——那里返回 HTTP 200，微信会当作已签收
            log.error("微信支付回调人工对账留痕失败：{}", reason, e);
            return false;
        }
    }
}
