package com.jbk.serve.service.mini.wxpay;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 微信支付密码学原语（WX-ECO S3）：签名、验签、解密的协议正确性。
 * 密钥对现场生成（任务书边界：真实凭据不得出现在代码/测试/交付物）；
 * 验证走独立重建规范串 + 公钥真实验签，规范串拼错一个换行即全红。
 */
class WechatPayCryptoTest {

    private static final String API_V3_KEY = "0123456789abcdef0123456789abcdef";

    private static KeyPair merchant;
    private static KeyPair platform;

    @TempDir
    static Path dir;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        merchant = gen.generateKeyPair();
        platform = gen.generateKeyPair();
    }

    // ------------------------------------------------------------------
    // 装配
    // ------------------------------------------------------------------

    private WechatPayCredentials credentials() throws Exception {
        Path priv = dir.resolve("merchant.pem");
        Path pub = dir.resolve("platform.pem");
        Files.writeString(priv, pem("PRIVATE KEY", merchant.getPrivate().getEncoded()));
        Files.writeString(pub, pem("PUBLIC KEY", platform.getPublic().getEncoded()));
        WechatPayCredentials c = new WechatPayCredentials();
        ReflectionTestUtils.setField(c, "mchid", "1900000001");
        ReflectionTestUtils.setField(c, "merchantSerial", "SERIALTEST");
        ReflectionTestUtils.setField(c, "privateKeyPath", priv.toString());
        ReflectionTestUtils.setField(c, "apiV3Key", API_V3_KEY);
        ReflectionTestUtils.setField(c, "platformPublicKeyId", "PUB_KEY_ID_TEST");
        ReflectionTestUtils.setField(c, "platformPublicKeyPath", pub.toString());
        return c;
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    private static String signWith(PrivateKey key, String message) throws Exception {
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(key);
        s.update(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(s.sign());
    }

    private static boolean verifyWith(PublicKey key, String message, String sigB64) throws Exception {
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initVerify(key);
        s.update(message.getBytes(StandardCharsets.UTF_8));
        return s.verify(Base64.getDecoder().decode(sigB64));
    }

    // ------------------------------------------------------------------
    // 出站签名：Authorization 头
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Authorization 头：解析出的 nonce/timestamp 重建规范串后能用商户公钥验回")
    void authorizationHeaderIsMathematicallyValid() throws Exception {
        WechatPaySigner signer = new WechatPaySigner(credentials());
        ReflectionTestUtils.setField(signer, "appid", "wxTESTAPPID");
        String body = "{\"out_trade_no\":\"RC1\"}";
        String header = signer.authorization("POST", "/v3/pay/transactions/jsapi", body);

        assertTrue(header.startsWith("WECHATPAY2-SHA256-RSA2048 "), "鉴权类型前缀不对");
        String nonce = extract(header, "nonce_str");
        String timestamp = extract(header, "timestamp");
        String signature = extract(header, "signature");
        assertEquals("1900000001", extract(header, "mchid"));
        assertEquals("SERIALTEST", extract(header, "serial_no"));

        // 独立重建规范串（method\nurl\nts\nnonce\nbody\n）——末尾换行少一个就验不回
        String message = "POST\n/v3/pay/transactions/jsapi\n" + timestamp + "\n" + nonce + "\n" + body + "\n";
        assertTrue(verifyWith(merchant.getPublic(), message, signature),
                "签名与独立重建的规范串不符——联调时微信侧会拒绝且不告诉你差在哪");
        // 时间戳必须是当前秒：漏 /1000 时数学验签照过，微信侧按超窗拒绝且不说原因
        assertTrue(Math.abs(Long.parseLong(timestamp) - System.currentTimeMillis() / 1000L) < 60,
                "timestamp 不是当前秒值：" + timestamp);
    }

    @Test
    @DisplayName("paySign：五参数按 appId\\n时间\\n随机串\\npackage\\n 验回，signType 恒为 RSA")
    void miniPayParamsAreMathematicallyValid() throws Exception {
        WechatPaySigner signer = new WechatPaySigner(credentials());
        ReflectionTestUtils.setField(signer, "appid", "wxTESTAPPID");
        WechatPaySigner.MiniPayParams p = signer.miniPayParams("wx20260813prepay000001");

        assertEquals("RSA", p.signType());
        assertEquals("prepay_id=wx20260813prepay000001", p.packageValue());
        String message = "wxTESTAPPID\n" + p.timeStamp() + "\n" + p.nonceStr() + "\n" + p.packageValue() + "\n";
        assertTrue(verifyWith(merchant.getPublic(), message, p.paySign()),
                "paySign 与规范串不符——端上 requestPayment 会报「支付验签失败」");
        assertTrue(Math.abs(Long.parseLong(p.timeStamp()) - System.currentTimeMillis() / 1000L) < 60,
                "timeStamp 不是当前秒值");
    }

    @Test
    @DisplayName("AppID 未配置时拒绝出 paySign，而不是签一个空 appId")
    void miniPayParamsFailClosedWithoutAppid() throws Exception {
        WechatPaySigner signer = new WechatPaySigner(credentials());
        ReflectionTestUtils.setField(signer, "appid", "");
        assertThrows(IllegalStateException.class, () -> signer.miniPayParams("wxprepay"),
                "空 appId 签出的参数端上必失败，且错误与真实原因无关");
    }

    // ------------------------------------------------------------------
    // 回调验签
    // ------------------------------------------------------------------

    private WechatPayNotifyVerifier verifier() throws Exception {
        return new WechatPayNotifyVerifier(credentials());
    }

    private String nowTs() {
        return String.valueOf(System.currentTimeMillis() / 1000L);
    }

    @Test
    @DisplayName("平台私钥签出的回调验签通过；正文改一个字节即失败")
    void notifyVerifyRoundTripAndTamper() throws Exception {
        String ts = nowTs();
        String body = "{\"id\":\"EV-1\"}";
        String sig = signWith(platform.getPrivate(), ts + "\nNONCE1\n" + body + "\n");

        assertTrue(verifier().verify("PUB_KEY_ID_TEST", ts, "NONCE1", body, sig));
        assertFalse(verifier().verify("PUB_KEY_ID_TEST", ts, "NONCE1", body + " ", sig),
                "正文被改动仍验签通过——任何人都能给本系统记收款");
    }

    @Test
    @DisplayName("serial 不匹配拒收：用错公钥验签的失败与伪造无法区分，先排除这一维")
    void notifyVerifyRejectsForeignSerial() throws Exception {
        String ts = nowTs();
        String body = "{}";
        String sig = signWith(platform.getPrivate(), ts + "\nN\n" + body + "\n");
        assertFalse(verifier().verify("OTHER_SERIAL", ts, "N", body, sig));
    }

    @Test
    @DisplayName("时间窗契约恒为 300 秒：窗口被悄悄放宽时这里变红，而不是重放面静默全开")
    void skewWindowIsPinnedToFiveMinutes() {
        assertEquals(300L, WechatPayNotifyVerifier.MAX_SKEW_SECONDS);
    }

    @Test
    @DisplayName("时间戳超窗拒收：重放旧报文不该再触发处理")
    void notifyVerifyRejectsStaleTimestamp() throws Exception {
        String stale = String.valueOf(System.currentTimeMillis() / 1000L
                - WechatPayNotifyVerifier.MAX_SKEW_SECONDS - 10);
        String body = "{}";
        String sig = signWith(platform.getPrivate(), stale + "\nN\n" + body + "\n");
        assertFalse(verifier().verify("PUB_KEY_ID_TEST", stale, "N", body, sig));
    }

    @Test
    @DisplayName("凭据未配置时恒拒收（fail-closed），而不是放行")
    void notifyVerifyFailClosedWhenUnconfigured() throws Exception {
        WechatPayNotifyVerifier bare = new WechatPayNotifyVerifier(new WechatPayCredentials());
        String ts = nowTs();
        String sig = signWith(platform.getPrivate(), ts + "\nN\n{}\n");
        assertFalse(bare.verify("PUB_KEY_ID_TEST", ts, "N", "{}", sig),
                "没配公钥却放行回调，等于任何人都能给本系统记收款");
    }

    // ================= R1 P2：serial 日志脱敏不得抛异常 =================

    /**
     * "!" 曾触发 StringIndexOutOfBounds：过滤后为空串而截断上限用了原串长度。
     * 公网端点上任何 serial 输入都只能得到"验签失败"，不允许异常逃逸改变响应形态。
     */
    @Test
    @DisplayName("R1：恶意/异常 serial 一律验签失败零异常；脱敏只出白名单字符且限长 48")
    void hostileSerialsNeverThrow() throws Exception {
        String ts = nowTs();
        String body = "{}";
        String sig = signWith(platform.getPrivate(), ts + "\nN\n" + body + "\n");
        String[] hostile = {
                "!", "/", "中文序列号", "line1\r\nline2", "\u0000\u0001\u0007",
                "A".repeat(48), "A".repeat(49), "abc!@#中文\r\n" + "Z".repeat(100),
        };
        WechatPayNotifyVerifier v = verifier();
        for (String serial : hostile) {
            assertFalse(v.verify(serial, ts, "N", body, sig),
                    "异常 serial 应验签失败而不是别的：" + serial.length());
        }
        // 脱敏函数本体：空过滤结果给占位、只出白名单、上限 48
        assertEquals("<empty>", WechatPayNotifyVerifier.sanitizedForLog("!"));
        assertEquals("<null>", WechatPayNotifyVerifier.sanitizedForLog(null));
        assertEquals("line1line2", WechatPayNotifyVerifier.sanitizedForLog("line1\r\nline2"));
        assertEquals(48, WechatPayNotifyVerifier.sanitizedForLog("B".repeat(49)).length());
        // 合法但不匹配的 serial：正常走"不符"分支返回 false
        assertFalse(v.verify("LEGIT_BUT_UNKNOWN", ts, "N", body, sig));
    }

    // ------------------------------------------------------------------
    // 回调解密
    // ------------------------------------------------------------------

    private static String encrypt(String key, String aad, String nonce, String plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
        if (aad != null) {
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        }
        return Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("AEAD 解密往返；密文或 AAD 被改动即抛异常，没有降级")
    void cipherRoundTripAndTamper() throws Exception {
        WechatPayCipher cipher = new WechatPayCipher(credentials());
        String plain = "{\"out_trade_no\":\"RC1\",\"trade_state\":\"SUCCESS\"}";
        String ct = encrypt(API_V3_KEY, "transaction", "0123456789ab", plain);

        assertEquals(plain, cipher.decrypt("transaction", "0123456789ab", ct));
        assertThrows(Exception.class, () -> cipher.decrypt("transaction", "0123456789ab",
                        ct.substring(0, ct.length() - 8) + "AAAAAAA="),
                "密文被改动仍解出来——GCM 认证形同虚设");
        assertThrows(Exception.class, () -> cipher.decrypt("tampered-aad", "0123456789ab", ct),
                "AAD 被改动仍解出来——随附数据没参与认证");
    }

    @Test
    @DisplayName("APIv3 密钥长度非 32 字节：明确报长度错，不是等到解密时一律 tag mismatch")
    void apiV3KeyLengthIsValidatedEagerly() throws Exception {
        WechatPayCredentials c = credentials();
        ReflectionTestUtils.setField(c, "apiV3Key", "short");
        assertThrows(IllegalStateException.class, c::apiV3KeyBytes);
    }

    // ------------------------------------------------------------------
    // 凭据 fail-closed
    // ------------------------------------------------------------------

    @Test
    @DisplayName("六项凭据缺任何一项即未配置，取值方法一律拒绝")
    void credentialsFailClosed() throws Exception {
        WechatPayCredentials bare = new WechatPayCredentials();
        assertFalse(bare.configured());
        assertThrows(IllegalStateException.class, bare::mchid);
        assertThrows(IllegalStateException.class, bare::merchantPrivateKey);

        // 六项逐一置空：只测一两项的话，configured() 的清单里漏掉谁都不会变红
        for (String field : new String[]{"mchid", "merchantSerial", "privateKeyPath",
                "apiV3Key", "platformPublicKeyId", "platformPublicKeyPath"}) {
            WechatPayCredentials partial = credentials();
            ReflectionTestUtils.setField(partial, field, "");
            assertFalse(partial.configured(), "缺 " + field + " 仍报已配置——半套凭据没有可用的子集");
        }
    }

    @Test
    @DisplayName("任务书对抗⑩：私钥/公钥文件是垃圾内容时明确拒绝解析——格式错不得静默变成可用适配器")
    void malformedPemFilesAreRejected() throws Exception {
        WechatPayCredentials c = credentials();
        Path badPriv = dir.resolve("bad-priv.pem");
        Files.writeString(badPriv, "-----BEGIN PRIVATE KEY-----\nnot-base64!!\n-----END PRIVATE KEY-----\n");
        ReflectionTestUtils.setField(c, "privateKeyPath", badPriv.toString());
        assertThrows(IllegalStateException.class, c::merchantPrivateKey, "坏私钥被解析成功了");

        WechatPayCredentials c2 = credentials();
        Path badPub = dir.resolve("bad-pub.pem");
        // 合法 Base64 但不是 X.509 结构：过了解码这关也必须在 KeyFactory 处拒绝
        Files.writeString(badPub, "-----BEGIN PUBLIC KEY-----\nQUJDREVGRw==\n-----END PUBLIC KEY-----\n");
        ReflectionTestUtils.setField(c2, "platformPublicKeyPath", badPub.toString());
        assertThrows(IllegalStateException.class, c2::platformPublicKey, "坏公钥被解析成功了");

        WechatPayCredentials c3 = credentials();
        ReflectionTestUtils.setField(c3, "privateKeyPath", dir.resolve("missing.pem").toString());
        assertThrows(IllegalStateException.class, c3::merchantPrivateKey, "文件不存在没有明确报错");
    }

    private static String extract(String header, String field) {
        Matcher m = Pattern.compile(field + "=\"([^\"]+)\"").matcher(header);
        assertTrue(m.find(), "Authorization 头缺少 " + field);
        return m.group(1);
    }
}
