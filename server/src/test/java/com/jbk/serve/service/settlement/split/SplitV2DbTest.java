package com.jbk.serve.service.settlement.split;

import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.settlement.WsSplitComponentMapper;
import com.jbk.tool.config.system.mybatis.MpMetaObjectHandler;
import com.jbk.tool.data.settlement.po.WsSplitComponent;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DuplicateKeyException;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 分润 V2 的<b>真实 MySQL</b> 回归（E2E-08 S1 测试矩阵 11/12/16/17）。
 *
 * <p>这四条只有真库能证明：唯一键并发语义、多角色共存、迁移幂等、迁移 fail-closed。
 * Mockito 对它们没有证明力（任务书第六节明确要求）。</p>
 *
 * <p>DDL 一律执行真实迁移文件，不在测试里抄第二份——抄本会漂移，漂移后测试照绿。
 * 无 Docker 环境自动跳过。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = SplitV2DbTest.Ctx.class)
class SplitV2DbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_splitv2_it")
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
            ds.setMaximumPoolSize(8);
            return ds;
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(DataSource ds) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(ds);
            com.baomidou.mybatisplus.core.MybatisConfiguration cfg =
                    new com.baomidou.mybatisplus.core.MybatisConfiguration();
            cfg.setMapUnderscoreToCamelCase(true);
            GlobalConfig globalConfig = new GlobalConfig();
            globalConfig.setMetaObjectHandler(new MpMetaObjectHandler());
            factory.setGlobalConfig(globalConfig);
            return new SqlSessionTemplate(factory.getObject());
        }

        @Bean
        MapperFactoryBean<WsSplitComponentMapper> componentMapper(SqlSessionTemplate t) {
            MapperFactoryBean<WsSplitComponentMapper> bean =
                    new MapperFactoryBean<>(WsSplitComponentMapper.class);
            bean.setSqlSessionTemplate(t);
            return bean;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private WsSplitComponentMapper componentMapper;

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

    /** 去行注释后按分号切句；本迁移语句边界干净，够用且不引入第三方解析器。 */
    private static List<String> migrationStatements() throws IOException {
        String sql = new String(Files.readAllBytes(
                repoRoot().resolve("deploy/mysql/migrations/2026-08-06-split-v2-s1.sql")),
                StandardCharsets.UTF_8);
        String noComment = sql.replaceAll("(?m)^\\s*--.*$", "");
        List<String> out = new ArrayList<>();
        for (String stmt : noComment.split(";")) {
            if (!stmt.isBlank()) {
                out.add(stmt.trim());
            }
        }
        return out;
    }

    /**
     * 执行迁移——必须在<b>单一连接</b>上逐句执行。
     *
     * <p>迁移里的 {@code @tbl/@sql} 是连接级用户变量：JdbcTemplate 每句独立
     * {@code execute} 会从池里拿连接，SET 落在连接 A、PREPARE 在连接 B 读到 NULL，
     * 直接 BadSqlGrammar（实测踩到；此前个别用例通过只是碰巧复用了同一连接）。
     * mysql CLI 天然单连接，这里用 ConnectionCallback 还原同一语义。</p>
     *
     * <p>哨兵中止的实现是 PREPARE 一条 {@code SELECT `中止：…`}——反引号里是
     * <b>列名</b>，MySQL 抛 Unknown column，mysql CLI 非 --force 即停。JDBC 下表现为
     * SQLException，消息里带着那段中文，据此还原「是否被守卫中止」。</p>
     */
    private boolean runMigrationAborted() throws IOException {
        List<String> statements = migrationStatements();
        return Boolean.TRUE.equals(jdbc.execute((java.sql.Connection conn) -> {
            try (java.sql.Statement st = conn.createStatement()) {
                for (String stmt : statements) {
                    try {
                        st.execute(stmt);
                    }
                    catch (java.sql.SQLException e) {
                        if (String.valueOf(e.getMessage()).contains("中止")) {
                            return true;
                        }
                        throw e;
                    }
                }
            }
            return false;
        }));
    }

    @BeforeEach
    void cleanSlate() {
        jdbc.execute("DROP TABLE IF EXISTS ws_split_plan");
        jdbc.execute("DROP TABLE IF EXISTS ws_split_plan_item");
        jdbc.execute("DROP TABLE IF EXISTS ws_split_component");
    }

    // ==================== 矩阵 16：迁移幂等 ====================

    @Test
    void migrationIsIdempotent() throws IOException {
        assertTrue(!runMigrationAborted(), "首跑不应中止");
        long tables = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()"
                        + " AND table_name IN ('ws_split_plan','ws_split_plan_item','ws_split_component')",
                Long.class);
        assertEquals(3, tables);

        assertTrue(!runMigrationAborted(), "重复执行不应中止");
        assertEquals(3, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()"
                        + " AND table_name IN ('ws_split_plan','ws_split_plan_item','ws_split_component')",
                Long.class), "重复执行后仍恰三张表，零变更");
        assertEquals(0, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_split_plan WHERE PLAN_STATUS = 2", Long.class),
                "任何一次执行都不得产生生效计划");
    }

    // ==================== 矩阵 17：存量结构不符 fail-closed ====================

    @Test
    void migrationAbortsOnUnexpectedLegacyStructure() throws IOException {
        // 伪造一张缺 COMPONENT_KEY 唯一键的存量表（比如有人手工建过旧结构）
        jdbc.execute("CREATE TABLE ws_split_component (ID bigint PRIMARY KEY, X varchar(10))");
        assertTrue(runMigrationAborted(), "存量结构不符必须中止，绝不带病继续");
        // 中止发生在任何建表之前：另外两张表不得被创建（无半执行状态）
        assertEquals(0, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()"
                        + " AND table_name IN ('ws_split_plan','ws_split_plan_item')", Long.class));
        // 存量表原样未动
        assertEquals(2, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()"
                        + " AND table_name = 'ws_split_component'", Long.class));
    }

    // ==================== 矩阵 11：同键并发恰一次 ====================

    @Test
    void concurrentSameComponentKeyInsertsExactlyOnce() throws Exception {
        assertTrue(!runMigrationAborted());
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    try {
                        componentMapper.insert(component("SPLITV2:ORD-C1:WATER_SALE:WATER_OWNER",
                                "WATER_OWNER", 11L));
                        return true;
                    }
                    catch (DuplicateKeyException e) {
                        return false;
                    }
                }));
            }
            gate.countDown();
            int succeeded = 0;
            for (Future<Boolean> f : futures) {
                if (f.get(30, TimeUnit.SECONDS)) {
                    succeeded++;
                }
            }
            assertEquals(1, succeeded, "同一 COMPONENT_KEY 八线程并发写入必须恰一次成功");
            assertEquals(1, (long) jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ws_split_component", Long.class));
        }
        finally {
            pool.shutdownNow();
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                fail("线程池未能收干净");
            }
        }
    }

    // ==================== 矩阵 12：多角色同人不被合并 ====================

    @Test
    void sameReceiverWithMultipleRolesKeepsAllRows() throws IOException {
        assertTrue(!runMigrationAborted());
        long same = 77L;
        // 同一收益人以机主/推荐人/省级三种角色出现：key 因角色不同而不同，三行都得留下
        componentMapper.insert(component("SPLITV2:ORD-M1:WATER_SALE:WATER_OWNER", "WATER_OWNER", same));
        componentMapper.insert(component("SPLITV2:ORD-M1:WATER_SALE:WATER_DIRECT_REFERRER",
                "WATER_DIRECT_REFERRER", same));
        componentMapper.insert(component("SPLITV2:ORD-M1:WATER_SALE:REGION_PROVINCE",
                "REGION_PROVINCE", same));
        assertEquals(3, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_split_component WHERE RECEIVER_USER_ID = " + same, Long.class),
                "多角色同一收益人绝不被唯一键错误合并");
    }

    private WsSplitComponent component(String key, String role, long receiver) {
        WsSplitComponent c = new WsSplitComponent()
                .setOrderId(1L)
                .setOrderNo("ORD-KEY")
                .setProductLine("WATER_SALE")
                .setBasisAmount(10_000L)
                .setRoleCode(role)
                .setReceiverUserId(receiver)
                .setEffectiveRate(1_000)
                .setSplitAmount(1_000L)
                .setPlanVersion("TEST-V2-001")
                .setAttributionSource("PRIVATE_REFERRAL")
                .setComponentKey(key);
        c.setCreateBy(1L);
        c.setUpdateBy(1L);
        return c;
    }
}
