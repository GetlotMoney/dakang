package com.jbk.serve.service.delivery.impl;

import com.jbk.serve.service.delivery.DeliveryMediaStore;
import com.jbk.tool.exception.JbkException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 受控本地媒体存储（最小实现）：${dakang.delivery.media-root} 下按键前缀两级散列落盘。
 * <p>媒体键是服务端派生的十六进制串；这里仍作白名单校验，
 * 防未来调用方把用户可控字符串带进来造成路径穿越。</p>
 *
 * @author dakang
 * @since 2026-07-23
 */
@Service
public class LocalDeliveryMediaStore implements DeliveryMediaStore {

    /** 仅允许服务端派生键格式（DM+30位大写十六进制），键即文件名，杜绝路径注入。 */
    private static final Pattern SAFE_KEY = Pattern.compile("^DM[0-9A-F]{30}$");

    private final Path root;

    public LocalDeliveryMediaStore(
            @Value("${dakang.delivery.media-root:${user.dir}/data/delivery-media}") String rootDir) {
        this.root = Path.of(rootDir);
    }

    @Override
    public void store(String mediaKey, byte[] content) {
        if (mediaKey == null || !SAFE_KEY.matcher(mediaKey).matches()) {
            throw new JbkException("媒体键格式非法");
        }
        try {
            Path dir = root.resolve(mediaKey.substring(2, 4));
            Files.createDirectories(dir);
            // 同键重写等价（内容寻址键 ⇒ 同键必同内容），无覆盖风险
            Files.write(dir.resolve(mediaKey), content);
        } catch (IOException e) {
            throw new JbkException("媒体内容落盘失败");
        }
    }
}
