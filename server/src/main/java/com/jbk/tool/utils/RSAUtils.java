package com.jbk.tool.utils;

import cn.hutool.core.util.CharsetUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.asymmetric.KeyType;
import cn.hutool.crypto.asymmetric.RSA;
import com.jbk.tool.exception.JbkException;

import java.nio.charset.StandardCharsets;

public class RSAUtils {

    private static final String PRIVATE_KEY = requireEnv("DAKANG_RSA_PRIVATE_KEY");
    private static final String PUBLIC_KEY = requireEnv("DAKANG_RSA_PUBLIC_KEY");
    private static final RSA RSA_ENGINE = new RSA(PRIVATE_KEY, PUBLIC_KEY);

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (StrUtil.isBlank(value)) {
            throw new JbkException("缺少必需环境变量：" + name);
        }
        return value;
    }

    // 加密
    public static String encrypt(String encryptStr) {
        try {
            return RSA_ENGINE.encryptBase64(encryptStr.getBytes(StandardCharsets.UTF_8), KeyType.PublicKey);
        } catch (Exception e) {
            throw new JbkException("加密失败");
        }

    }

    // 解密
    public static String decrypt(String decryptStr) {
        try {
            return StrUtil.str(RSA_ENGINE.decrypt(decryptStr, KeyType.PrivateKey), CharsetUtil.CHARSET_UTF_8);
        } catch (Exception e) {
            throw new JbkException("解密失败");
        }
    }
}


