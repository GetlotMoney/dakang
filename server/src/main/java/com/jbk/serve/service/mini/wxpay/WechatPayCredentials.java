package com.jbk.serve.service.mini.wxpay;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 微信支付 APIv3 商户凭据（WX-ECO S3）。商户号/序列号/APIv3 密钥经 .env 注入；
 * 两把 PEM 密钥走文件路径（secret 挂载），避免多行密钥进环境变量被 docker inspect 导出；
 * 真值不得出现在仓库任何文件（check-secret-leak.py 看守）。
 * fail-closed：凭据缺失取值直接抛错、绝不退化为默认值，调用方用 {@link #configured()} 预判。
 * PEM 解析结果缓存；密钥轮换重启生效，不做热加载（半新半旧的密钥组合更难排查）。
 *
 * @author dakang
 * @since 2026-08-13
 */
@Slf4j
@Component
public class WechatPayCredentials {

    /** 商户号。 */
    @Value("${wechat.pay.mchid:}")
    private String mchid;

    /** 商户 API 证书序列号（Authorization 头的 serial_no）。 */
    @Value("${wechat.pay.merchant-serial:}")
    private String merchantSerial;

    /** 商户私钥 PEM 文件路径（PKCS#8）。 */
    @Value("${wechat.pay.private-key-path:}")
    private String privateKeyPath;

    /** APIv3 密钥（32 字节，对称，解密回调 resource 用）。 */
    @Value("${wechat.pay.api-v3-key:}")
    private String apiV3Key;

    /** 微信支付平台公钥 ID（回调头 Wechatpay-Serial 必须与之一致）。 */
    @Value("${wechat.pay.platform-public-key-id:}")
    private String platformPublicKeyId;

    /** 微信支付平台公钥 PEM 文件路径（X.509 SubjectPublicKeyInfo）。 */
    @Value("${wechat.pay.platform-public-key-path:}")
    private String platformPublicKeyPath;

    private volatile PrivateKey cachedPrivateKey;
    private volatile PublicKey cachedPlatformKey;

    /** 六项凭据是否齐备。缺任何一项都算未配置——半套凭据没有可用的子集。 */
    public boolean configured() {
        return StrUtil.isAllNotBlank(mchid, merchantSerial, privateKeyPath,
                apiV3Key, platformPublicKeyId, platformPublicKeyPath);
    }

    public String mchid() {
        requireConfigured();
        return mchid;
    }

    public String merchantSerial() {
        requireConfigured();
        return merchantSerial;
    }

    public String platformPublicKeyId() {
        requireConfigured();
        return platformPublicKeyId;
    }

    /** APIv3 密钥字节。长度必须恰为 32：AES-256-GCM 的密钥长度没有协商余地。 */
    public byte[] apiV3KeyBytes() {
        requireConfigured();
        byte[] key = apiV3Key.getBytes(StandardCharsets.UTF_8);
        if (key.length != 32) {
            // 长度错在这里明确报出，别等到解密时一律 tag mismatch
            throw new IllegalStateException("APIv3 密钥长度必须为 32 字节，当前 " + key.length);
        }
        return key;
    }

    public PrivateKey merchantPrivateKey() {
        requireConfigured();
        PrivateKey key = cachedPrivateKey;
        if (key == null) {
            synchronized (this) {
                key = cachedPrivateKey;
                if (key == null) {
                    key = parsePrivateKey(readPem(privateKeyPath, "商户私钥"));
                    cachedPrivateKey = key;
                }
            }
        }
        return key;
    }

    public PublicKey platformPublicKey() {
        requireConfigured();
        PublicKey key = cachedPlatformKey;
        if (key == null) {
            synchronized (this) {
                key = cachedPlatformKey;
                if (key == null) {
                    key = parsePublicKey(readPem(platformPublicKeyPath, "平台公钥"));
                    cachedPlatformKey = key;
                }
            }
        }
        return key;
    }

    private void requireConfigured() {
        if (!configured()) {
            throw new IllegalStateException("微信支付凭据未配置（wechat.pay.*），拒绝执行支付操作");
        }
    }

    private static String readPem(String path, String label) {
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new IllegalStateException(label + "文件不可读：" + path, e);
        }
    }

    private static PrivateKey parsePrivateKey(String pem) {
        try {
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(pemBody(pem, "PRIVATE KEY")));
        }
        catch (Exception e) {
            throw new IllegalStateException("商户私钥解析失败（需 PKCS#8 PEM）", e);
        }
    }

    private static PublicKey parsePublicKey(String pem) {
        try {
            return KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(pemBody(pem, "PUBLIC KEY")));
        }
        catch (Exception e) {
            throw new IllegalStateException("平台公钥解析失败（需 X.509 PEM）", e);
        }
    }

    private static byte[] pemBody(String pem, String type) {
        String body = pem.replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}
