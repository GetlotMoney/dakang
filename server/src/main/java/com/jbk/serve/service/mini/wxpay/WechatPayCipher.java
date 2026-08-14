package com.jbk.serve.service.mini.wxpay;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

/**
 * 微信支付回调 resource 解密（WX-ECO S3）：AEAD_AES_256_GCM，密钥为 APIv3 Key。
 * 认证失败（篡改或密钥不对）只抛异常不兜底，由调用方按转人工分类处理；
 * associated_data 为 null 时不喂 AAD——喂空串与不喂在 GCM 里是两回事。
 *
 * @author dakang
 * @since 2026-08-13
 */
@Component
@RequiredArgsConstructor
public class WechatPayCipher {

    private static final int TAG_BITS = 128;

    private final WechatPayCredentials credentials;

    /**
     * 解密 resource 三元组为明文 JSON 字符串。
     *
     * @throws GeneralSecurityException 认证失败 / 密钥不符 / 密文损坏——调用方分类转人工
     */
    public String decrypt(String associatedData, String nonce, String ciphertextB64)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(credentials.apiV3KeyBytes(), "AES"),
                new GCMParameterSpec(TAG_BITS, nonce.getBytes(StandardCharsets.UTF_8)));
        if (associatedData != null) {
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
        }
        byte[] plain = cipher.doFinal(Base64.getDecoder().decode(ciphertextB64));
        return new String(plain, StandardCharsets.UTF_8);
    }
}
