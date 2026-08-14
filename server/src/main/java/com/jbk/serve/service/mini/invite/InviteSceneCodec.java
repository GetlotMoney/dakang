package com.jbk.serve.service.mini.invite;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.HMac;
import cn.hutool.crypto.digest.HmacAlgorithm;
import com.jbk.tool.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 小程序码 scene 编解码与签名（WX-ECO S2）：{@code <邀请码>.<到期日>.<签名前8位>}。
 * 复用现有唯一邀请码（D-413 只有一个码，不另造第二套）；必须签名——scene 由用户侧原样带回，
 * 不签名即可把推荐人换成自己，而归属一次性不可逆；必须可过期——码会被转发/印在物料上。
 * scene 上限 32 字符（getUnlimitedQRCode），超长直接拒绝而不是截断：截断后签名恒校验失败。
 *
 * @author dakang
 * @since 2026-08-12
 */
@Slf4j
@Component
public class InviteSceneCodec {

    /** 微信 getUnlimitedQRCode 的 scene 上限。 */
    public static final int SCENE_MAX_LENGTH = 32;

    /** 签名截取长度：8 位十六进制 = 32 bit，足以挡住手工试错，且不挤占 scene 空间。 */
    private static final int SIGN_LENGTH = 8;

    private static final char SEP = '.';

    /**
     * 签名密钥。<b>只从环境配置读</b>，且缺失时拒绝出码而不是用默认值——
     * 用默认值会让所有环境的签名可互相伪造，而这件事不会有任何症状。
     */
    @Value("${wechat.invite.scene-secret:}")
    private String sceneSecret;

    /** 码有效天数。 */
    @Value("${wechat.invite.scene-valid-days:90}")
    private int validDays;

    /**
     * 编码：邀请码 → 带签名与到期日的 scene。
     *
     * @throws IllegalStateException 密钥未配置（fail-closed，绝不用默认密钥出码）
     */
    public String encode(String inviteCode) {
        requireSecret();
        if (StrUtil.isBlank(inviteCode)) {
            throw new IllegalArgumentException("邀请码为空");
        }
        String code = inviteCode.trim().toUpperCase();
        String expireDay = DateUtils.plusSeconds(DateUtils.time(), 86400L * validDays)
                .substring(0, 8);
        String payload = code + SEP + expireDay;
        String scene = payload + SEP + sign(payload);
        if (scene.length() > SCENE_MAX_LENGTH) {
            // 截断会让签名恒校验失败：码能生成、扫了没用、错误信息与真实原因无关
            throw new IllegalStateException(
                    "scene 超长（" + scene.length() + " > " + SCENE_MAX_LENGTH + "），拒绝出码");
        }
        return scene;
    }

    /**
     * 解码：校验签名与有效期后返回邀请码。
     *
     * <p>任何一环不过一律返回空，<b>不区分"签名错"与"已过期"</b>：
     * 对外区分这两者等于告诉伪造者"签名对了只是过期"，把爆破的搜索空间砍掉一半。
     * 真实原因只落日志。</p>
     */
    public Optional<String> decode(String scene) {
        if (StrUtil.isBlank(sceneSecret) || StrUtil.isBlank(scene)) {
            return Optional.empty();
        }
        String[] parts = scene.trim().split("\\" + SEP);
        if (parts.length != 3) {
            return Optional.empty();
        }
        String code = parts[0];
        String expireDay = parts[1];
        String givenSign = parts[2];
        String payload = code + SEP + expireDay;

        // 先验签再看有效期：未验签的 expireDay 本身就是攻击者可控的输入
        if (!constantTimeEquals(sign(payload), givenSign)) {
            log.debug("小程序码 scene 签名不符");
            return Optional.empty();
        }
        if (expireDay.compareTo(DateUtils.time().substring(0, 8)) < 0) {
            log.debug("小程序码 scene 已过期");
            return Optional.empty();
        }
        return Optional.of(code);
    }

    private void requireSecret() {
        if (StrUtil.isBlank(sceneSecret)) {
            throw new IllegalStateException("小程序码签名密钥未配置（wechat.invite.scene-secret）");
        }
    }

    private String sign(String payload) {
        HMac mac = new HMac(HmacAlgorithm.HmacSHA256,
                sceneSecret.getBytes(StandardCharsets.UTF_8));
        return mac.digestHex(payload).substring(0, SIGN_LENGTH);
    }

    /**
     * 定长比较不提前返回：String.equals 的逐字节耗时差可被测量，
     * 攻击者能一位一位凑出签名（8 位签名下不是理论问题）。
     */
    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null || expected.length() != actual.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < expected.length(); i++) {
            diff |= expected.charAt(i) ^ actual.charAt(i);
        }
        return diff == 0;
    }
}
