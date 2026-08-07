package com.jbk.serve.service.mini.profile;

import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 头像落盘存储测试：读写往返 + 文件名白名单（路径穿越是这类存储的头号风险）。
 */
class ProfileAvatarStoreTest {

    /** AV + 恰 30 位大写十六进制 + 白名单扩展名。 */
    private static final String GOOD = "AV0123456789ABCDEF0123456789ABCDEF".substring(0, 32) + ".png";

    @Test
    void storeAndLoadRoundTrip(@TempDir Path dir) {
        ProfileAvatarStore store = new ProfileAvatarStore(dir.toString());
        byte[] content = { 1, 2, 3, 4 };

        store.store(GOOD, content);

        assertArrayEquals(content, store.load(GOOD));
    }

    @Test
    void missingFileReturnsNull(@TempDir Path dir) {
        ProfileAvatarStore store = new ProfileAvatarStore(dir.toString());
        assertNull(store.load(GOOD));
    }

    // 路径穿越/异形文件名一律拒绝：键即凭据，白名单是唯一入口
    @Test
    void unsafeNamesRejected(@TempDir Path dir) {
        ProfileAvatarStore store = new ProfileAvatarStore(dir.toString());
        String hex30 = "0123456789ABCDEF0123456789ABCD";
        for (String bad : new String[] {
                "../etc/passwd",
                "AV" + hex30 + ".gif",                  // 扩展名不在白名单
                ("AV" + hex30).toLowerCase() + ".png",  // 小写（键恒大写）
                "DM" + hex30 + ".png",                  // 配送媒体前缀，禁止跨域挪用
                "AV" + hex30,                            // 缺扩展名
                "AV0123/6789ABCDEF0123456789ABCD.png",  // 分隔符注入
                "", }) {
            assertThrows(JbkException.class, () -> store.store(bad, new byte[] { 1 }), "应拒绝：" + bad);
            assertThrows(JbkException.class, () -> store.load(bad), "应拒绝：" + bad);
        }
    }
}
