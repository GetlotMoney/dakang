package com.jbk.serve.service.mini.profile;

import com.jbk.tool.exception.JbkException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 用户头像物理存储（与 {@code LocalDeliveryMediaStore} 同款受控落盘：键前缀两级散列目录）。
 *
 * <p>文件名 = 内容寻址键 + 白名单扩展名（jpg/png/webp）。键由服务端派生（AV+30位大写十六进制），
 * 这里仍作白名单校验，防未来调用方把用户可控字符串带进来造成路径穿越。
 * 读取按完整文件名精确命中；键是内容寻址的，同名必同内容，缓存可视为不可变。</p>
 *
 * @author dakang
 * @since 2026-08-02
 */
@Service
public class ProfileAvatarStore {

    /** 仅允许服务端派生的文件名格式：AV+30位大写十六进制 + 白名单扩展名。 */
    public static final Pattern SAFE_FILE = Pattern.compile("^AV[0-9A-F]{30}\\.(jpg|png|webp)$");

    private final Path root;

    public ProfileAvatarStore(
            @Value("${dakang.profile.avatar-root:${user.dir}/data/avatar}") String rootDir) {
        this.root = Path.of(rootDir);
    }

    /** 按受控文件名落盘；失败必须抛出（上层事务回滚，不允许「列已更新而文件缺失」）。 */
    public void store(String fileName, byte[] content) {
        requireSafe(fileName);
        try {
            Path dir = root.resolve(fileName.substring(2, 4));
            Files.createDirectories(dir);
            // 内容寻址键 ⇒ 同名必同内容，重写等价、无覆盖风险
            Files.write(dir.resolve(fileName), content);
        } catch (IOException e) {
            throw new JbkException("头像保存失败，请稍后重试");
        }
    }

    /** 按受控文件名读取；不存在返回 null（由调用方决定 404 语义）。 */
    public byte[] load(String fileName) {
        requireSafe(fileName);
        Path file = root.resolve(fileName.substring(2, 4)).resolve(fileName);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new JbkException("头像读取失败，请稍后重试");
        }
    }

    private static void requireSafe(String fileName) {
        if (fileName == null || !SAFE_FILE.matcher(fileName).matches()) {
            throw new JbkException("头像文件名格式非法");
        }
    }
}
