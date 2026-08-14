package com.jbk.serve.service.mini.auth;

import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.mini.WsIdentityConflictMapper;
import com.jbk.tool.config.system.mybatis.MpMetaObjectHandler;
import com.jbk.tool.consts.mini.MiniIdentityConflictEnum.Scene;
import com.jbk.tool.consts.mini.MiniIdentityConflictEnum.Type;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 身份冲突台账的<b>真实 MySQL</b> 回归（WX-ECO S1）。
 *
 * <p>这里断言的每一条都只有真库能证明：唯一键真的把同一冲突收敛成一行、
 * 并发下不会插出重复行、次数是数据库自己加的（不是读改写）、
 * 已处理状态不会被后续发生悄悄重置。Mockito 对这些没有证明力。</p>
 *
 * <p>DDL 一律执行真实迁移文件，不在测试里抄第二份。</p>
 *
 * @author dakang
 * @since 2026-08-12
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = MiniIdentityConflictDbTest.Ctx.class)
@DisplayName("身份冲突台账真库判定")
class MiniIdentityConflictDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_identity_conflict_it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    @Configuration
    static class Ctx {
        @Bean
        DataSource dataSource() {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl(MYSQL.getJdbcUrl());
            ds.setUsername(MYSQL.getUsername());
            ds.setPassword(MYSQL.getPassword());
            ds.setMaximumPoolSize(12);
            return ds;
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(DataSource ds) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(ds);
            com.baomidou.mybatisplus.core.MybatisConfiguration cfg =
                    new com.baomidou.mybatisplus.core.MybatisConfiguration();
            cfg.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(cfg);
            GlobalConfig globalConfig = new GlobalConfig();
            globalConfig.setMetaObjectHandler(new MpMetaObjectHandler());
            factory.setGlobalConfig(globalConfig);
            return new SqlSessionTemplate(factory.getObject());
        }

        @Bean
        MapperFactoryBean<WsIdentityConflictMapper> conflictMapper(SqlSessionTemplate t) {
            MapperFactoryBean<WsIdentityConflictMapper> bean =
                    new MapperFactoryBean<>(WsIdentityConflictMapper.class);
            bean.setSqlSessionTemplate(t);
            return bean;
        }

        @Bean
        MiniIdentityConflictRecorder recorder(WsIdentityConflictMapper mapper) {
            return new MiniIdentityConflictRecorder(mapper);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    private static final String PHONE = "13900001111";
    private static final String MASKED = "139****1111";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MiniIdentityConflictRecorder recorder;

    private static Path repoRoot() {
        Path cur = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && cur != null; i++) {
            if (Files.isDirectory(cur.resolve("deploy/mysql/migrations"))) {
                return cur;
            }
            cur = cur.getParent();
        }
        throw new IllegalStateException("无法定位仓库根");
    }

    private void applyMigration() throws IOException {
        String sql = new String(Files.readAllBytes(
                repoRoot().resolve("deploy/mysql/migrations/2026-08-12-identity-conflict.sql")),
                StandardCharsets.UTF_8);
        List<String> statements = new ArrayList<>();
        for (String stmt : sql.replaceAll("(?m)^\\s*--.*$", "").split(";")) {
            if (!stmt.isBlank()) {
                statements.add(stmt.trim());
            }
        }
        // 单一连接：迁移里的 @tbl/@sql 是连接级用户变量，跨连接读到 NULL 直接 BadSqlGrammar
        jdbc.execute((java.sql.Connection conn) -> {
            try (java.sql.Statement st = conn.createStatement()) {
                for (String stmt : statements) {
                    st.execute(stmt);
                }
            }
            return null;
        });
    }

    private Map<String, Object> onlyRow() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM ws_identity_conflict");
        assertEquals(1, rows.size(), "台账应恰有一行，实际 " + rows.size());
        return rows.get(0);
    }

    @BeforeEach
    void cleanSlate() throws IOException {
        jdbc.execute("DROP TABLE IF EXISTS ws_identity_conflict");
        applyMigration();
    }

    @Test
    @DisplayName("首次冲突落一行待处理，且只存脱敏号——不存明文、更不存 openid")
    void firstConflictCreatesPendingRow() {
        recorder.record(Type.PHONE_BOUND_OTHER_WECHAT, Scene.LOGIN_BIND, 7L, null, PHONE);

        Map<String, Object> row = onlyRow();
        assertEquals("PHONE_BOUND_OTHER_WECHAT", row.get("CONFLICT_TYPE"));
        assertEquals("LOGIN_BIND", row.get("OCCUR_SCENE"));
        assertEquals(7L, ((Number) row.get("HOLDER_USER_ID")).longValue());
        // 票据路径此刻还没有账号：0 哨兵而不是 NULL，否则同一冲突每次重试都插新行
        assertEquals(0L, ((Number) row.get("ACTOR_USER_ID")).longValue());
        assertEquals(MASKED, row.get("MASKED_PHONE"));
        assertEquals(1, ((Number) row.get("OCCUR_COUNT")).intValue());
        assertEquals(1, ((Number) row.get("HANDLE_STATUS")).intValue());

        // 全表任何列都不得出现明文号码：脱敏在这里是硬约束，不是展示层礼貌
        String dump = jdbc.queryForList("SELECT * FROM ws_identity_conflict").toString();
        assertTrue(!dump.contains(PHONE), "台账里出现了明文手机号：" + dump);
    }

    @Test
    @DisplayName("同一冲突反复发生：累加次数，绝不插新行")
    void repeatedConflictAccumulatesInsteadOfInserting() {
        for (int i = 0; i < 5; i++) {
            recorder.record(Type.PHONE_OWNED_BY_OTHER, Scene.SELF_BIND, 7L, 9L, PHONE);
        }
        // 被拒的用户会一直重试；每次插行会让台账被同一件事淹掉，
        // 真正需要处置的其他冲突反而看不见
        assertEquals(5, ((Number) onlyRow().get("OCCUR_COUNT")).intValue());
    }

    @Test
    @DisplayName("不同持有方/发起方/类型各自成行，不会被并成一条")
    void distinctConflictsStaySeparate() {
        recorder.record(Type.PHONE_OWNED_BY_OTHER, Scene.SELF_BIND, 7L, 9L, PHONE);
        recorder.record(Type.PHONE_OWNED_BY_OTHER, Scene.SELF_BIND, 8L, 9L, PHONE);
        recorder.record(Type.PHONE_BOUND_OTHER_WECHAT, Scene.LOGIN_BIND, 7L, 9L, PHONE);
        recorder.record(Type.PHONE_OWNED_BY_OTHER, Scene.SELF_BIND, 7L, 10L, PHONE);

        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_identity_conflict", Integer.class),
                "四种不同的冲突被并成了一条——键少了维度，运营会漏处理");
    }

    @Test
    @DisplayName("已处理的冲突再次发生：次数照涨、状态不回退")
    void handledConflictIsNotSilentlyReopened() {
        recorder.record(Type.PHONE_OWNED_BY_OTHER, Scene.SELF_BIND, 7L, 9L, PHONE);
        jdbc.update("UPDATE ws_identity_conflict SET HANDLE_STATUS = 2, HANDLE_BY = 1");

        recorder.record(Type.PHONE_OWNED_BY_OTHER, Scene.SELF_BIND, 7L, 9L, PHONE);

        Map<String, Object> row = onlyRow();
        assertEquals(2, ((Number) row.get("OCCUR_COUNT")).intValue());
        // 状态由运营掌握。系统把它悄悄改回待处理，等于抹掉"有人已经看过并做了裁决"这条事实；
        // "处理完又犯了"要由运营从 LAST_OCCUR_TIME 看出来，而不是被系统替他重开工单。
        assertEquals(2, ((Number) row.get("HANDLE_STATUS")).intValue(),
                "已处理状态被后续发生重置了");
        assertEquals(1L, ((Number) row.get("HANDLE_BY")).longValue(), "处理人被抹掉了");
    }

    @Test
    @DisplayName("20 并发记同一冲突：恰一行，次数恰 20，一次不丢")
    void concurrentRecordingYieldsExactlyOneRowAndNoLostCount() throws Exception {
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        recorder.record(Type.CONCURRENT_BIND_LOST, Scene.LOGIN_BIND, 7L, null, PHONE);
                    }
                    catch (Exception ignored) {
                        Thread.currentThread().interrupt();
                    }
                    finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "并发用例超时");
        }
        finally {
            pool.shutdownNow();
        }

        // 恰一行：唯一键真的收敛了并发插入
        // 次数恰 20：计数是数据库自己 +1 的。若实现改成"查出来改再存"，
        // 两个线程会同时读到同一个值并双双写回，这里必然小于 20。
        assertEquals(20, ((Number) onlyRow().get("OCCUR_COUNT")).intValue(),
                "并发下丢了计数——说明累加不是原子的");
    }
}
