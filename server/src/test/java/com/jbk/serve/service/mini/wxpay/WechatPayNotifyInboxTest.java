package com.jbk.serve.service.mini.wxpay;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.jbk.serve.mapper.trade.RechargeCreditMapper;
import com.jbk.serve.mapper.trade.RechargeIdentityMapper;
import com.jbk.serve.service.mall.IMallPayFactService;
import com.jbk.serve.service.mini.recharge.IRechargePayFactService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.data.mall.po.WsMallPaymentFact;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsPayment;
import com.jbk.tool.data.trade.po.WsPaymentEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 微信支付回调收件箱（WX-ECO S3）：分类、路由与事实字段核对。
 * 验签/解密用自造密钥真算（分类行为即被测性质）；落库 mock，
 * 事实推进由 RechargePayCloseDbTest / MallTradeTxDbTest 真库看守，这里只核对接线。
 */
class WechatPayNotifyInboxTest {

    private static final String API_V3_KEY = "0123456789abcdef0123456789abcdef";
    private static final String SERIAL = "PUB_KEY_ID_TEST";
    private static final String NONCE = "0123456789ab";

    private static KeyPair platform;
    private static KeyPair merchant;

    @TempDir
    static Path dir;

    private RechargeIdentityMapper identityMapper;
    private RechargeCreditMapper creditMapper;
    private IRechargePayFactService rechargeFact;
    private IMallPayFactService mallFact;
    private IWsDomainEventService domainEvents;
    private WechatPayNotifyInbox inbox;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        platform = gen.generateKeyPair();
        merchant = gen.generateKeyPair();
    }

    @BeforeEach
    void wire() throws Exception {
        Path priv = dir.resolve("m.pem");
        Path pub = dir.resolve("p.pem");
        Files.writeString(priv, pem("PRIVATE KEY", merchant.getPrivate().getEncoded()));
        Files.writeString(pub, pem("PUBLIC KEY", platform.getPublic().getEncoded()));
        WechatPayCredentials credentials = new WechatPayCredentials();
        ReflectionTestUtils.setField(credentials, "mchid", "1900000001");
        ReflectionTestUtils.setField(credentials, "merchantSerial", "MSER");
        ReflectionTestUtils.setField(credentials, "privateKeyPath", priv.toString());
        ReflectionTestUtils.setField(credentials, "apiV3Key", API_V3_KEY);
        ReflectionTestUtils.setField(credentials, "platformPublicKeyId", SERIAL);
        ReflectionTestUtils.setField(credentials, "platformPublicKeyPath", pub.toString());

        identityMapper = mock(RechargeIdentityMapper.class);
        creditMapper = mock(RechargeCreditMapper.class);
        rechargeFact = mock(IRechargePayFactService.class);
        mallFact = mock(IMallPayFactService.class);
        domainEvents = mock(IWsDomainEventService.class);
        inbox = new WechatPayNotifyInbox(new WechatPayNotifyVerifier(credentials),
                new WechatPayCipher(credentials), identityMapper, creditMapper,
                rechargeFact, mallFact, domainEvents);
        when(rechargeFact.process(anyLong()))
                .thenReturn(new IRechargePayFactService.Outcome("CREDITED", "ok"));
    }

    // ------------------------------------------------------------------
    // 报文构造：真加密 + 真签名
    // ------------------------------------------------------------------

    private record Notify(String serial, String ts, String nonce, String sig, String body) {
    }

    private Notify buildNotify(String notifyId, String plainTransaction) throws Exception {
        String ct = encrypt(plainTransaction);
        JSONObject envelope = JSONUtil.createObj()
                .set("id", notifyId)
                .set("event_type", "TRANSACTION.SUCCESS")
                .set("resource", JSONUtil.createObj()
                        .set("algorithm", "AEAD_AES_256_GCM")
                        .set("original_type", "transaction")
                        .set("associated_data", "transaction")
                        .set("nonce", NONCE)
                        .set("ciphertext", ct));
        String body = envelope.toString();
        String ts = String.valueOf(System.currentTimeMillis() / 1000L);
        return new Notify(SERIAL, ts, "N1", sign(ts + "\nN1\n" + body + "\n"), body);
    }

    private static String successTransaction(String outTradeNo) {
        return JSONUtil.createObj()
                .set("out_trade_no", outTradeNo)
                .set("transaction_id", "4200000000000000001")
                .set("trade_state", "SUCCESS")
                .set("success_time", "2026-08-13T10:34:56+08:00")
                .set("amount", JSONUtil.createObj().set("total", 5500).set("currency", "CNY"))
                .toString();
    }

    private WechatPayNotifyInbox.Outcome deliver(Notify n) {
        return inbox.onPaymentNotify(n.serial(), n.ts(), n.nonce(), n.sig(), n.body());
    }

    private void seedRechargeOrder(int paySourceOfPayment) {
        WsOrder order = new WsOrder();
        order.setId(70L);
        order.setOrderNo("RC0000000000000000000000000000AA");
        order.setUserId(9L);
        WsPayment payment = new WsPayment();
        payment.setId(80L);
        payment.setPaySource(paySourceOfPayment);
        when(identityMapper.selectOrdersByOrderNoIncludingDeleted(anyString()))
                .thenReturn(List.of(order));
        when(identityMapper.selectPaymentsByOrderIdIncludingDeleted(70L))
                .thenReturn(List.of(payment));
    }

    // ------------------------------------------------------------------
    // 类0：验签
    // ------------------------------------------------------------------

    @Test
    @DisplayName("验签不过：401，且一个 mapper 都没被碰——公网垃圾流量不产生任何库痕")
    void badSignatureLeavesNoTraceAtAll() throws Exception {
        Notify n = buildNotify("EV-1", successTransaction("RC0000000000000000000000000000AA"));
        WechatPayNotifyInbox.Outcome out =
                inbox.onPaymentNotify(n.serial(), n.ts(), n.nonce(), n.sig(), n.body() + " ");
        assertEquals(401, out.httpStatus());
        assertEquals("FAIL", out.code());
        verifyNoInteractions(identityMapper, creditMapper, rechargeFact, mallFact, domainEvents);
    }

    // ------------------------------------------------------------------
    // 充值链正路
    // ------------------------------------------------------------------

    @Test
    @DisplayName("RC 成功回调：事实字段逐项原样落库（键=通知id、金额/币种/交易号/归一时间），推进被驱动")
    void rechargeSuccessRecordsVerbatimFactAndProcesses() throws Exception {
        seedRechargeOrder(1);
        WsPaymentEvent saved = new WsPaymentEvent();
        saved.setId(500L);
        when(creditMapper.selectEventByProviderKey(1, 1, "EV-OK")).thenReturn(null, saved);

        Notify delivered = buildNotify("EV-OK", successTransaction("RC0000000000000000000000000000AA"));
        WechatPayNotifyInbox.Outcome out = deliver(delivered);

        assertEquals(200, out.httpStatus());
        assertEquals("SUCCESS", out.code());
        ArgumentCaptor<String> time14 = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> rawBody = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sha = ArgumentCaptor.forClass(String.class);
        verify(creditMapper).insertNotifyEvent(eq("RC0000000000000000000000000000AA"), eq(70L), eq(80L),
                eq(1), eq(1), eq("EV-OK"), eq("SUCCESS"), eq("4200000000000000001"),
                eq(5500L), eq("CNY"), time14.capture(), eq(0L), anyString(), rawBody.capture(),
                sha.capture(), eq(1), eq(SERIAL), anyString(), eq("N1"), anyString());
        assertEquals("20260813103456", time14.getValue(), "RFC3339 +08:00 必须归一为业务时区 14 位");
        // RAW_BODY 必须是微信签过名的原文，否则事后无法用平台公钥复核证据
        assertEquals(delivered.body(), rawBody.getValue(), "RAW_BODY 不是签名覆盖的原文");
        assertEquals(cn.hutool.crypto.digest.DigestUtil.sha256Hex(delivered.body()), sha.getValue(),
                "RAW_BODY_SHA256 与原文不符，完整性摘要形同虚设");
        verify(rechargeFact).process(500L);
        verifyNoInteractions(domainEvents);
    }

    @Test
    @DisplayName("同一通知 id 重投：复用既有事实，不再插入，推进原事实（幂等锚在唯一键）")
    void duplicateNotifyReusesExistingFact() throws Exception {
        seedRechargeOrder(1);
        WsPaymentEvent existing = new WsPaymentEvent();
        existing.setId(501L);
        // 语义字段与本次报文一致：不一致的重放属于"同键异文"，由另一条用例覆盖
        existing.setTradeState("SUCCESS");
        existing.setTransactionId("4200000000000000001");
        existing.setPayAmount(5500L);
        existing.setCurrency("CNY");
        existing.setPaySuccessTime("20260813103456");
        when(creditMapper.selectEventByProviderKey(1, 1, "EV-DUP")).thenReturn(existing);

        WechatPayNotifyInbox.Outcome out =
                deliver(buildNotify("EV-DUP", successTransaction("RC0000000000000000000000000000AA")));

        assertEquals(200, out.httpStatus());
        verify(creditMapper, never()).insertNotifyEvent(any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any());
        verify(rechargeFact).process(501L);
    }

    @Test
    @DisplayName("非 SUCCESS 状态：外部四字段一律 null 落库——报文里碰巧有值也不抄")
    void nonSuccessStateKeepsExternalFieldsNull() throws Exception {
        seedRechargeOrder(1);
        WsPaymentEvent saved = new WsPaymentEvent();
        saved.setId(502L);
        when(creditMapper.selectEventByProviderKey(1, 1, "EV-CLOSE")).thenReturn(null, saved);
        String closed = JSONUtil.createObj()
                .set("out_trade_no", "RC0000000000000000000000000000AA")
                .set("trade_state", "CLOSED")
                // 关单报文里揣着一个金额：合法解析下它不该进事实表
                .set("amount", JSONUtil.createObj().set("total", 5500).set("currency", "CNY"))
                .toString();

        deliver(buildNotify("EV-CLOSE", closed));

        verify(creditMapper).insertNotifyEvent(eq("RC0000000000000000000000000000AA"), eq(70L), eq(80L),
                eq(1), eq(1), eq("EV-CLOSE"), eq("CLOSED"), isNull(), isNull(), isNull(),
                isNull(), eq(0L), anyString(), anyString(), anyString(), eq(1),
                anyString(), anyString(), anyString(), anyString());
    }

    // ------------------------------------------------------------------
    // 商城链
    // ------------------------------------------------------------------

    @Test
    @DisplayName("MO 前缀路由进商城收件箱：先查后建、两段式驱动")
    void mallOrderRoutesToMallInbox() throws Exception {
        WsMallPaymentFact fact = new WsMallPaymentFact();
        fact.setId(900L);
        when(mallFact.recordFact(eq(1), eq(1), eq("EV-MALL"),
                eq("MO0000000000000000000000000000BB"), eq("SUCCESS"),
                eq("4200000000000000001"), eq(5500L), eq("20260813103456"), eq(1), anyString()))
                .thenReturn(fact);

        WechatPayNotifyInbox.Outcome out =
                deliver(buildNotify("EV-MALL", successTransaction("MO0000000000000000000000000000BB")));

        assertEquals(200, out.httpStatus());
        verify(mallFact).process(900L);
        verifyNoInteractions(creditMapper, rechargeFact);
    }

    // ------------------------------------------------------------------
    // 确定性异常 → 幂等留痕转人工
    // ------------------------------------------------------------------

    @Test
    @DisplayName("解密失败（密文损坏/密钥不符）：留痕转人工 + 500，让微信有限重试当免费补投")
    void undecryptableResourceGoesToManualWithRetry() throws Exception {
        Notify good = buildNotify("EV-BADCT", successTransaction("RC0000000000000000000000000000AA"));
        JSONObject envelope = JSONUtil.parseObj(good.body());
        envelope.getJSONObject("resource").set("ciphertext",
                Base64.getEncoder().encodeToString("garbage-not-gcm".getBytes(StandardCharsets.UTF_8)));
        String body = envelope.toString();
        String ts = String.valueOf(System.currentTimeMillis() / 1000L);
        Notify tampered = new Notify(SERIAL, ts, "N2", sign(ts + "\nN2\n" + body + "\n"), body);

        WechatPayNotifyInbox.Outcome out = deliver(tampered);

        assertEquals(500, out.httpStatus());
        assertEquals("FAIL", out.code());
        verify(domainEvents).recordReliableOnceIndependent(any(), eq("WXPAY_NOTIFY"),
                anyString(), isNull(), any());
        verifyNoInteractions(creditMapper, rechargeFact);
    }

    @Test
    @DisplayName("订单号前缀不属于本系统：留痕 + 200 SUCCESS，且幂等键=正文摘要——重投同报文只留一条")
    void foreignOrderPrefixStopsRetryWithEvidence() throws Exception {
        Notify n = buildNotify("EV-FOREIGN", successTransaction("XX123456"));
        assertEquals(200, deliver(n).httpStatus());
        deliver(n); // 微信重投同一报文

        // 键必须由正文摘要派生：UUID/时间戳会让重投的坏报文每次新落一条人工事件
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(domainEvents, org.mockito.Mockito.times(2)).recordReliableOnceIndependent(
                any(), eq("WXPAY_NOTIFY"), keys.capture(), isNull(), any());
        String expected = "WXPAYRAW:"
                + cn.hutool.crypto.digest.DigestUtil.sha256Hex(n.body()).substring(0, 48);
        assertEquals(expected, keys.getAllValues().get(0), "幂等键不是正文摘要派生");
        assertEquals(keys.getAllValues().get(0), keys.getAllValues().get(1),
                "同一报文两次投递产生了不同幂等键——留痕不幂等");
        verifyNoInteractions(creditMapper, rechargeFact, mallFact);
    }

    @Test
    @DisplayName("回调指向不存在的充值订单：留痕 + 200，重试永远找不到这张单")
    void missingRechargeOrderStopsRetryWithEvidence() throws Exception {
        when(identityMapper.selectOrdersByOrderNoIncludingDeleted(anyString())).thenReturn(List.of());

        WechatPayNotifyInbox.Outcome out =
                deliver(buildNotify("EV-NOORD", successTransaction("RC0000000000000000000000000000AA")));

        assertEquals(200, out.httpStatus());
        verify(domainEvents).recordReliableOnceIndependent(any(), eq("WXPAY_NOTIFY"),
                anyString(), isNull(), any());
        verifyNoInteractions(creditMapper, rechargeFact);
    }

    @Test
    @DisplayName("支付单是 Pay-Sim 来源却收到微信回调：拒入事实表——真实收款不得混进模拟口径")
    void paySourceMismatchRefusesFact() throws Exception {
        seedRechargeOrder(2);

        WechatPayNotifyInbox.Outcome out =
                deliver(buildNotify("EV-MIX", successTransaction("RC0000000000000000000000000000AA")));

        assertEquals(200, out.httpStatus());
        verify(domainEvents).recordReliableOnceIndependent(any(), eq("WXPAY_NOTIFY"),
                anyString(), isNull(), any());
        verify(creditMapper, never()).insertNotifyEvent(any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("成功事实缺 transaction_id：字段非法留痕 + 500，越界值不进事实表")
    void successWithoutTransactionIdIsIllegal() throws Exception {
        seedRechargeOrder(1);
        String broken = JSONUtil.createObj()
                .set("out_trade_no", "RC0000000000000000000000000000AA")
                .set("trade_state", "SUCCESS")
                .set("success_time", "2026-08-13T10:34:56+08:00")
                .set("amount", JSONUtil.createObj().set("total", 5500).set("currency", "CNY"))
                .toString();

        WechatPayNotifyInbox.Outcome out = deliver(buildNotify("EV-NOTX", broken));

        assertEquals(500, out.httpStatus());
        verify(domainEvents).recordReliableOnceIndependent(any(), eq("WXPAY_NOTIFY"),
                anyString(), isNull(), any());
        verifyNoInteractions(creditMapper, rechargeFact);
    }

    // ------------------------------------------------------------------
    // 整改轮新增：异常边界 / 退款闸 / 同键异文
    // ------------------------------------------------------------------

    @Test
    @DisplayName("处理途中 DB 抖动：500 FAIL 让微信重试——绝不能逃逸成全局 200 把重试永久停掉")
    void unexpectedExceptionYieldsRetriableFailInsteadOfEscaping() throws Exception {
        when(identityMapper.selectOrdersByOrderNoIncludingDeleted(anyString()))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("db jitter"));

        WechatPayNotifyInbox.Outcome out =
                deliver(buildNotify("EV-JITTER", successTransaction("RC0000000000000000000000000000AA")));

        assertEquals(500, out.httpStatus(), "异常若逃逸，全局处理器回 200，微信停止重试，已付款事实丢失");
        assertEquals("FAIL", out.code());
    }

    @Test
    @DisplayName("退款事件（REFUND.*）：留痕 + 500——本轮未接入，绝不静默 ACK 一条钱已退的事实")
    void refundEventIsRefusedWithEvidenceNotAcked() throws Exception {
        Notify n = buildNotify("EV-RF", successTransaction("RC0000000000000000000000000000AA"));
        JSONObject envelope = JSONUtil.parseObj(n.body());
        envelope.set("event_type", "REFUND.SUCCESS");
        String body = envelope.toString();
        String ts = String.valueOf(System.currentTimeMillis() / 1000L);
        WechatPayNotifyInbox.Outcome out = inbox.onPaymentNotify(
                SERIAL, ts, "NR", sign(ts + "\nNR\n" + body + "\n"), body);

        assertEquals(500, out.httpStatus());
        verify(domainEvents).recordReliableOnceIndependent(any(), eq("WXPAY_NOTIFY"),
                anyString(), isNull(), any());
        verifyNoInteractions(creditMapper, rechargeFact, mallFact);
    }

    @Test
    @DisplayName("同通知id携带不同金额：拒绝复用旧事实，留痕转人工（语义比对，不比原文摘要）")
    void sameNotifyIdWithDifferentContentIsRefused() throws Exception {
        seedRechargeOrder(1);
        WsPaymentEvent existing = new WsPaymentEvent();
        existing.setId(600L);
        existing.setTradeState("SUCCESS");
        existing.setTransactionId("4200000000000000001");
        existing.setPayAmount(9999L); // 与本次报文的 5500 不一致
        existing.setCurrency("CNY");
        existing.setPaySuccessTime("20260813103456");
        when(creditMapper.selectEventByProviderKey(1, 1, "EV-ALT")).thenReturn(existing);

        WechatPayNotifyInbox.Outcome out =
                deliver(buildNotify("EV-ALT", successTransaction("RC0000000000000000000000000000AA")));

        assertEquals(500, out.httpStatus());
        verify(rechargeFact, never()).process(anyLong());
        verify(domainEvents).recordReliableOnceIndependent(any(), eq("WXPAY_NOTIFY"),
                anyString(), isNull(), any());
    }

    @Test
    @DisplayName("重放语义一致（微信重投重新加密、原文字节不同）：正常复用，不误判为篡改")
    void reencryptedRetryWithSameSemanticsIsReused() throws Exception {
        seedRechargeOrder(1);
        WsPaymentEvent existing = new WsPaymentEvent();
        existing.setId(601L);
        existing.setTradeState("SUCCESS");
        existing.setTransactionId("4200000000000000001");
        existing.setPayAmount(5500L);
        existing.setCurrency("CNY");
        existing.setPaySuccessTime("20260813103456");
        when(creditMapper.selectEventByProviderKey(1, 1, "EV-RETRY")).thenReturn(existing);

        WechatPayNotifyInbox.Outcome out =
                deliver(buildNotify("EV-RETRY", successTransaction("RC0000000000000000000000000000AA")));

        assertEquals(200, out.httpStatus(), "语义一致的重投被拒会让微信空重试三天");
        verify(rechargeFact).process(601L);
    }

    @Test
    @DisplayName("success_time 带 Z（UTC）：必须真做时区换算成 +8，剥字符取前14位的假实现在此变红")
    void utcSuccessTimeIsConvertedToBusinessZone() throws Exception {
        seedRechargeOrder(1);
        WsPaymentEvent saved = new WsPaymentEvent();
        saved.setId(700L);
        when(creditMapper.selectEventByProviderKey(1, 1, "EV-UTC")).thenReturn(null, saved);
        String utc = JSONUtil.createObj()
                .set("out_trade_no", "RC0000000000000000000000000000AA")
                .set("transaction_id", "4200000000000000001")
                .set("trade_state", "SUCCESS")
                .set("success_time", "2026-08-13T02:34:56Z")
                .set("amount", JSONUtil.createObj().set("total", 5500).set("currency", "CNY"))
                .toString();

        deliver(buildNotify("EV-UTC", utc));

        verify(creditMapper).insertNotifyEvent(anyString(), anyLong(), anyLong(), anyInt(),
                anyInt(), anyString(), anyString(), anyString(), anyLong(), anyString(),
                eq("20260813103456"), anyLong(), anyString(), anyString(), anyString(), anyInt(),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("并发重投撞唯一键：输方吃 DuplicateKeyException 后重读复用赢方，仍回 200")
    void concurrentDuplicateKeyIsAbsorbedByReread() throws Exception {
        seedRechargeOrder(1);
        WsPaymentEvent winner = new WsPaymentEvent();
        winner.setId(701L);
        // 首查为空（两边同时都没查到）→ 本方插入撞键 → 重读拿到赢方
        when(creditMapper.selectEventByProviderKey(1, 1, "EV-RACE")).thenReturn(null, winner);
        when(creditMapper.insertNotifyEvent(anyString(), anyLong(), anyLong(), anyInt(),
                anyInt(), anyString(), anyString(), anyString(), anyLong(), anyString(),
                anyString(), anyLong(), anyString(), anyString(), anyString(), anyInt(),
                anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk"));

        WechatPayNotifyInbox.Outcome out =
                deliver(buildNotify("EV-RACE", successTransaction("RC0000000000000000000000000000AA")));

        assertEquals(200, out.httpStatus(), "并发交错被当成失败——微信会白白重试三天");
        verify(rechargeFact).process(701L);
    }

    @Test
    @DisplayName("商城同键异文：recordFact 抛拒绝 → 留痕 + 500，不推进")
    void mallSameKeyDifferentContentGoesManual() throws Exception {
        when(mallFact.recordFact(anyInt(), anyInt(), anyString(), anyString(), anyString(),
                anyString(), anyLong(), anyString(), anyInt(), anyString()))
                .thenThrow(new com.jbk.tool.exception.JbkException("同一支付事实键携带了不一致的内容"));

        WechatPayNotifyInbox.Outcome out =
                deliver(buildNotify("EV-MALT", successTransaction("MO0000000000000000000000000000BB")));

        assertEquals(500, out.httpStatus());
        verify(mallFact, never()).process(anyLong());
        verify(domainEvents).recordReliableOnceIndependent(any(), eq("WXPAY_NOTIFY"),
                anyString(), isNull(), any());
    }

    // ================= R1 P1-1：停止重试必须以证据落库为前提 =================

    @Test
    @DisplayName("R1：缺失订单且证据落库成功 → 200 停重试；证据写入抛异常 → 500 让微信继续重试")
    void stopRetryOnlyAfterEvidencePersisted() throws Exception {
        when(identityMapper.selectOrdersByOrderNoIncludingDeleted(anyString())).thenReturn(List.of());
        Notify n = buildNotify("EV-EVID", successTransaction("RC0000000000000000000000000000AA"));

        // 证据写成功：200/SUCCESS 停掉注定失败的重试
        assertEquals(200, deliver(n).httpStatus());

        // 证据写失败：绝不能回 200——那会让微信永久停止重试，而这笔事实在库里无影无踪
        org.mockito.Mockito.doThrow(new org.springframework.dao.QueryTimeoutException("evidence db down"))
                .when(domainEvents).recordReliableOnceIndependent(any(), anyString(),
                        anyString(), isNull(), any());
        WechatPayNotifyInbox.Outcome out = deliver(n);
        assertEquals(500, out.httpStatus(), "证据没落库却回 200——已付款事实既不在库里也不会再投递");
        assertEquals("FAIL", out.code());
        // 零资金副作用：事实表与推进器一次都没被碰
        verifyNoInteractions(creditMapper, rechargeFact, mallFact);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    private static String sign(String message) throws Exception {
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(platform.getPrivate());
        s.update(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(s.sign());
    }

    private static String encrypt(String plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(API_V3_KEY.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, NONCE.getBytes(StandardCharsets.UTF_8)));
        cipher.updateAAD("transaction".getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
    }
}
