package com.jbk.serve.service.mini.invite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 小程序码 scene 签名的判定（WX-ECO S2）。
 *
 * <p>归属是一次性不可逆的（D-413）：篡改造成的错误归属没有撤销流程，只能人工改库。
 * 因此这组用例的每一条对应一种真实的伪造手法，而不是形式化的正反例。</p>
 *
 * @author dakang
 * @since 2026-08-12
 */
@DisplayName("小程序码 scene 编解码")
class InviteSceneCodecTest {

    private static final String SECRET = "unit-test-scene-secret-0123456789";
    private static final String CODE = "ABCD123456";

    private InviteSceneCodec codec;

    private InviteSceneCodec codecWith(String secret, int validDays) {
        InviteSceneCodec c = new InviteSceneCodec();
        ReflectionTestUtils.setField(c, "sceneSecret", secret);
        ReflectionTestUtils.setField(c, "validDays", validDays);
        return c;
    }

    @BeforeEach
    void setUp() {
        codec = codecWith(SECRET, 90);
    }

    @Test
    @DisplayName("编解码闭环：出的码扫回来就是原邀请码")
    void roundTrip() {
        String scene = codec.encode(CODE);
        assertEquals(Optional.of(CODE), codec.decode(scene));
    }

    @Test
    @DisplayName("scene 不超过微信 32 字符上限")
    void sceneFitsWechatLimit() {
        assertTrue(codec.encode(CODE).length() <= InviteSceneCodec.SCENE_MAX_LENGTH,
                "超长的 scene 微信直接拒绝出码");
    }

    @Test
    @DisplayName("改邀请码：签名不符，拒绝——这正是「把归属换成自己」的手法")
    void tamperedInviteCodeRejected() {
        String scene = codec.encode(CODE);
        String tampered = scene.replace(CODE, "ZZZZ999999");
        assertNotEquals(scene, tampered, "构造的篡改样本与原文相同，用例失效");
        assertTrue(codec.decode(tampered).isEmpty());
    }

    @Test
    @DisplayName("改到期日：签名不符，拒绝——不能靠改日期让过期码复活")
    void tamperedExpiryRejected() {
        String scene = codec.encode(CODE);
        String[] parts = scene.split("\\.");
        String tampered = parts[0] + "." + "29991231" + "." + parts[2];
        assertTrue(codec.decode(tampered).isEmpty());
    }

    @Test
    @DisplayName("换密钥签的码解不开：不同环境的码不可互相冒用")
    void sceneFromAnotherSecretRejected() {
        String foreign = codecWith("another-environment-secret-abcdef", 90).encode(CODE);
        assertTrue(codec.decode(foreign).isEmpty(),
                "别的环境签的码在这里也认，等于所有环境共用一套伪造能力");
    }

    @Test
    @DisplayName("已过期的码拒绝：三年前的海报不该还在记归属")
    void expiredSceneRejected() {
        // validDays 为负 ⇒ 到期日落在过去，签名仍然合法——只有有效期这一关能拦住它
        String expired = codecWith(SECRET, -1).encode(CODE);
        assertTrue(codec.decode(expired).isEmpty(), "过期码仍被接受");
    }

    @Test
    @DisplayName("密钥未配置时拒绝出码，绝不回落默认密钥")
    void missingSecretFailsClosed() {
        InviteSceneCodec noSecret = codecWith("", 90);
        assertThrows(IllegalStateException.class, () -> noSecret.encode(CODE));
        // 解码侧同样拒绝：否则未配密钥的环境会把任何 scene 都当成合法
        assertTrue(noSecret.decode("ABCD123456.29991231.deadbeef").isEmpty());
    }

    @Test
    @DisplayName("畸形 scene 一律拒绝，不抛异常打断调用方")
    void malformedSceneRejectedQuietly() {
        for (String bad : new String[] { "", "  ", "onlycode", "a.b", "a.b.c.d",
                "ABCD123456.20991231", ".", "..." }) {
            assertTrue(codec.decode(bad).isEmpty(), "畸形 scene 被接受了：" + bad);
        }
    }

    @Test
    @DisplayName("邀请码大小写归一：扫码与手输落到同一个人")
    void inviteCodeIsCaseNormalized() {
        assertEquals(codec.decode(codec.encode(CODE)),
                codec.decode(codec.encode(CODE.toLowerCase())),
                "大小写不同的同一个码被当成两个人");
    }

    @Test
    @DisplayName("超长邀请码拒绝出码，而不是截断成一个扫了没用的码")
    void overlongInviteCodeRejected() {
        String tooLong = "A".repeat(40);
        assertThrows(IllegalStateException.class, () -> codec.encode(tooLong),
                "截断后签名恒校验失败：码能生成、扫了没用，错误信息与真实原因无关");
    }
}
