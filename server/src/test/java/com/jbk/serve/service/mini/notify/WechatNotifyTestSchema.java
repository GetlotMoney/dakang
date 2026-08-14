package com.jbk.serve.service.mini.notify;

import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试库里的 {@code ws_wechat_notify_outbox}：直接执行真实迁移文件，不抄 DDL——
 * 抄本会与迁移漂移，导致测试全绿、部署炸掉。
 */
public final class WechatNotifyTestSchema {

    /** 权威 DDL 的唯一位置。改表结构只改这里，三轨同源由 SchemaParityTest 看守。 */
    private static final String MIGRATION = "deploy/mysql/migrations/2026-08-12-wechat-notify-outbox.sql";

    private WechatNotifyTestSchema() {
    }

    /**
     * 在当前库建出通知 outbox 表。迁移本身幂等（{@code CREATE TABLE IF NOT EXISTS}），
     * 可重复调用。
     */
    public static void create(JdbcTemplate jdbc) {
        String sql;
        try {
            sql = new String(Files.readAllBytes(repoRoot().resolve(MIGRATION)), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            // 找不到迁移文件是环境问题，不是断言失败：明确炸掉，别退化成"建了个空表继续跑"
            throw new UncheckedIOException("读取通知 outbox 迁移失败：" + MIGRATION, e);
        }
        List<String> statements = new ArrayList<>();
        // 去掉整行注释再按分号切：迁移里的中文注释含分号会把语句切碎
        for (String stmt : sql.replaceAll("(?m)^\\s*--.*$", "").split(";")) {
            if (!stmt.isBlank()) {
                statements.add(stmt.trim());
            }
        }
        jdbc.execute((java.sql.Connection conn) -> {
            try (java.sql.Statement st = conn.createStatement()) {
                for (String stmt : statements) {
                    st.execute(stmt);
                }
            }
            return null;
        });
    }

    /** 逐用例清空。表可能尚未建立时调用方应先 {@link #create}。 */
    public static void truncate(JdbcTemplate jdbc) {
        jdbc.execute("TRUNCATE TABLE ws_wechat_notify_outbox");
    }

    /**
     * 从 target/test-classes 逐级上溯找到仓库根（含 deploy 目录的那一层）。
     * 不用相对路径写死层数：测试可能从仓库根或从 server/ 启动，层数不同。
     */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve(MIGRATION))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("未找到仓库根（向上找不到 " + MIGRATION + "）");
        }
        return dir;
    }
}
