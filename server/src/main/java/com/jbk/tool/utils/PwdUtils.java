package com.jbk.tool.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.jbk.tool.exception.JbkException;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 员工口令的哈希、校验、初始口令生成与强度规则（R-201）。存储格式统一 BCrypt，
 * {@link #verify} 对非 BCrypt 存量值一律返回 false（fail-closed），禁止保留旧格式兼容分支。
 * RSA（{@link RSAUtils}）只做传输层加密，与本类存储职责无关。
 */
public final class PwdUtils {

    /** BCrypt 哈希前缀（$2a$/$2b$/$2y$）；不满足即视为非法存储格式。 */
    private static final String BCRYPT_PREFIX = "$2";

    /**
     * 初始口令字符集刻意剔除易混字符（0/O、1/l/I 等）：
     * 初始口令由管理员口头或纸面转达员工，抄错一位就要重置一次。
     */
    private static final String INIT_UPPER = "ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final String INIT_LOWER = "abcdefghjkmnpqrstuvwxyz";
    private static final String INIT_DIGIT = "23456789";

    private static final int INIT_UPPER_COUNT = 2;
    private static final int INIT_LOWER_COUNT = 4;
    private static final int INIT_DIGIT_COUNT = 4;

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 32;

    /** BCrypt 的输入上限；超出部分被算法静默截断，故在入口处直接拒绝。 */
    private static final int BCRYPT_MAX_BYTES = 72;

    /** 口令生成必须用强随机源；RandomUtil 的 ThreadLocalRandom 可被预测，禁止在此使用。 */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private PwdUtils() {
    }

    /** 明文口令 → BCrypt 哈希（默认 cost，含随机盐；同一明文两次哈希结果不同）。 */
    public static String hash(String plainPwd) {
        if (StrUtil.isBlank(plainPwd)) {
            throw new JbkException("密码不能为空");
        }
        return BCrypt.hashpw(plainPwd, BCrypt.gensalt());
    }

    /**
     * 校验明文口令与库内存储值是否匹配。
     *
     * <p>存储值为空、非 BCrypt 格式（含历史 RSA 密文）或格式损坏时一律返回 false，
     * 不抛异常——调用方统一按"账号或密码错误"处理，不向外暴露存储格式信息。</p>
     */
    public static boolean verify(String plainPwd, String storedHash) {
        if (StrUtil.isBlank(plainPwd) || StrUtil.isBlank(storedHash)) {
            return false;
        }
        if (!storedHash.startsWith(BCRYPT_PREFIX)) {
            return false;
        }
        try {
            return BCrypt.checkpw(plainPwd, storedHash);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 生成一次性初始口令：10 位，固定含 2 大写 + 4 小写 + 4 数字后整体打乱，
     * 天然满足 {@link #checkStrength} 的字母+数字要求。
     */
    public static String generateInitialPwd() {
        List<Character> chars = new ArrayList<>(INIT_UPPER_COUNT + INIT_LOWER_COUNT + INIT_DIGIT_COUNT);
        for (int i = 0; i < INIT_UPPER_COUNT; i++) {
            chars.add(INIT_UPPER.charAt(SECURE_RANDOM.nextInt(INIT_UPPER.length())));
        }
        for (int i = 0; i < INIT_LOWER_COUNT; i++) {
            chars.add(INIT_LOWER.charAt(SECURE_RANDOM.nextInt(INIT_LOWER.length())));
        }
        for (int i = 0; i < INIT_DIGIT_COUNT; i++) {
            chars.add(INIT_DIGIT.charAt(SECURE_RANDOM.nextInt(INIT_DIGIT.length())));
        }
        Collections.shuffle(chars, SECURE_RANDOM);
        StringBuilder sb = new StringBuilder(chars.size());
        chars.forEach(sb::append);
        return sb.toString();
    }

    /**
     * 用户自设口令的强度底线：8~32 位且同时包含字母与数字。
     * 不满足时抛出带具体原因的业务异常，供改密接口直接透传给页面。
     */
    public static void checkStrength(String plainPwd) {
        if (StrUtil.isBlank(plainPwd)) {
            throw new JbkException("新密码不能为空");
        }
        int length = plainPwd.length();
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw new JbkException("新密码长度必须为 " + MIN_LENGTH + "~" + MAX_LENGTH + " 位");
        }
        // BCrypt 只取前 72 字节，超出部分被静默丢弃：32 个汉字即 96 字节，
        // 前 24 字符相同的两个不同口令会互相登录成功。宁可拒绝也不接受被截断的口令
        if (plainPwd.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            throw new JbkException("新密码过长（按 UTF-8 计不得超过 " + BCRYPT_MAX_BYTES + " 字节）");
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < length; i++) {
            char ch = plainPwd.charAt(i);
            if (Character.isLetter(ch)) {
                hasLetter = true;
            } else if (Character.isDigit(ch)) {
                hasDigit = true;
            }
        }
        if (!hasLetter || !hasDigit) {
            throw new JbkException("新密码必须同时包含字母和数字");
        }
    }
}
