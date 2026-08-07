package com.jbk.serve.service.settlement.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.settlement.WsSplitConfigMapper;
import com.jbk.serve.mapper.settlement.WsSplitLineLockMapper;
import com.jbk.serve.service.settlement.ISplitConfigService;
import com.jbk.tool.data.settlement.bo.FinanceQueryBo;
import com.jbk.tool.exception.JbkException;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分账比例配置写入的<b>真实 MySQL + 真实 Spring 事务</b>并发测试（R1 P1-3）。
 *
 * <p>被钉住的窗口：改<b>不同收款方</b>的两个请求互不撞 {@code uk_split_config_version}，
 * 各自按旧视图校验通过后双双写入，同线未来断点合计可超 100%——到点后所有配送签收
 * 在分账引擎 fail-closed，配送完成链熔断。修复=商品线锁锚行 {@code FOR UPDATE} 串行化。
 * <b>删除商品线锁（lockLine 调用）后，本类并发用例必须转红。</b></p>
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = SplitConfigConcurrencyDbTest.Ctx.class)
class SplitConfigConcurrencyDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_cfg_it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    @Configuration
    @EnableTransactionManagement
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
        PlatformTransactionManager transactionManager(DataSource ds) {
            return new DataSourceTransactionManager(ds);
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(DataSource ds) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(ds);
            MybatisConfiguration cfg = new MybatisConfiguration();
            cfg.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(cfg);
            com.baomidou.mybatisplus.core.config.GlobalConfig globalConfig =
                    new com.baomidou.mybatisplus.core.config.GlobalConfig();
            globalConfig.setMetaObjectHandler(new com.jbk.tool.config.system.mybatis.MpMetaObjectHandler());
            factory.setGlobalConfig(globalConfig);
            return new SqlSessionTemplate(factory.getObject());
        }

        @Bean
        MapperFactoryBean<WsSplitConfigMapper> configMapper(SqlSessionTemplate t) {
            MapperFactoryBean<WsSplitConfigMapper> b = new MapperFactoryBean<>(WsSplitConfigMapper.class);
            b.setSqlSessionTemplate(t);
            return b;
        }

        @Bean
        MapperFactoryBean<WsSplitLineLockMapper> lineLockMapper(SqlSessionTemplate t) {
            MapperFactoryBean<WsSplitLineLockMapper> b = new MapperFactoryBean<>(WsSplitLineLockMapper.class);
            b.setSqlSessionTemplate(t);
            return b;
        }

        @Bean
        SplitConfigServiceImpl splitConfigService(WsSplitConfigMapper configMapper,
                                                  WsSplitLineLockMapper lineLockMapper) {
            return new SplitConfigServiceImpl(configMapper, lineLockMapper);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    /** 按接口注入：@Transactional 代理织入是锁生效的前提。 */
    @Autowired
    private ISplitConfigService splitConfigService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_split_config (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT NOT NULL DEFAULT 0, CREATE_BY BIGINT NOT NULL,
                  CREATE_TIME VARCHAR(14) NOT NULL, UPDATE_BY BIGINT NOT NULL, UPDATE_TIME VARCHAR(14) NOT NULL,
                  PRODUCT_LINE TINYINT NOT NULL, RECEIVER_TYPE TINYINT NOT NULL,
                  SPLIT_RATE INT NOT NULL, EFFECT_TIME VARCHAR(14) NOT NULL, CONFIG_REMARK VARCHAR(200) NULL,
                  UNIQUE KEY uk_split_config_version (PRODUCT_LINE, RECEIVER_TYPE, EFFECT_TIME)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_split_line_lock (
                  PRODUCT_LINE TINYINT NOT NULL, PRIMARY KEY (PRODUCT_LINE)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("TRUNCATE TABLE ws_split_config");
        jdbc.execute("DELETE FROM ws_split_line_lock");
        jdbc.update("INSERT INTO ws_split_line_lock(PRODUCT_LINE) VALUES (1), (2)");
        // 现行配置（复审复现场景）：配送线机主 3000 / 配送员 3000
        seed(2, 1, 3000, "20260101000000");
        seed(2, 2, 3000, "20260101000000");
    }

    private void seed(int line, int receiver, int rate, String effect) {
        jdbc.update("INSERT INTO ws_split_config(DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, "
                        + "PRODUCT_LINE, RECEIVER_TYPE, SPLIT_RATE, EFFECT_TIME) "
                        + "VALUES(0, 1, '20260101000000', 1, '20260101000000', ?, ?, ?, ?)",
                line, receiver, rate, effect);
    }

    private FinanceQueryBo bo(int line, int receiver, int rate, String effect) {
        FinanceQueryBo bo = new FinanceQueryBo();
        bo.setProductLine(line);
        bo.setReceiverType(receiver);
        bo.setSplitRate(rate);
        bo.setEffectTime(effect);
        return bo;
    }

    private long futureSum(int line, String at) {
        // 该断点各收款方现行版本合计（与引擎选版口径一致：每收款方取 ≤at 的最晚生效版）
        return jdbc.queryForObject("""
                SELECT IFNULL(SUM(rate), 0) FROM (
                  SELECT (SELECT c2.SPLIT_RATE FROM ws_split_config c2
                           WHERE c2.PRODUCT_LINE = c1.PRODUCT_LINE AND c2.RECEIVER_TYPE = c1.RECEIVER_TYPE
                             AND c2.EFFECT_TIME <= ? ORDER BY c2.EFFECT_TIME DESC LIMIT 1) AS rate
                    FROM ws_split_config c1 WHERE c1.PRODUCT_LINE = ? GROUP BY c1.RECEIVER_TYPE
                ) t""", Long.class, at, line);
    }

    /**
     * R1 P1-3 核心：同一商品线、不同收款方并发写未来版本——两请求在旧视图下单独看均不超限
     * （3000+6000=9000 ≤ 10000；3000+5000=8000 ≤ 10000），组合后 11000 超限。
     * 锁锚串行化下恰一笔成功、另一笔在锁内看到先行者后明确拒绝；全部未来断点合计 ≤10000。
     * <b>删除 lockLine 后本用例必转红</b>（双成功 → 断点合计断言失败）。
     */
    @Test
    void concurrentDifferentReceiversOnSameLineYieldExactlyOneWrite() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Callable<Long>> jobs = List.of(
                () -> { barrier.await(10, TimeUnit.SECONDS);
                        return splitConfigService.createVersion(bo(2, 1, 6000, "20991201000000"), "20991201000000"); },
                () -> { barrier.await(10, TimeUnit.SECONDS);
                        return splitConfigService.createVersion(bo(2, 2, 5000, "20991101000000"), "20991101000000"); });
        List<Future<Long>> futures = pool.invokeAll(jobs, 30, TimeUnit.SECONDS);
        assertFalse(futures.get(0).isCancelled(), "写入事务 30s 内完成（无死锁/无限等待）");
        assertFalse(futures.get(1).isCancelled(), "写入事务 30s 内完成（无死锁/无限等待）");
        pool.shutdown();

        int ok = 0;
        int rejected = 0;
        for (Future<Long> f : futures) {
            try {
                assertTrue(f.get() > 0);
                ok++;
            } catch (ExecutionException e) {
                assertTrue(e.getCause() instanceof JbkException, "拒绝必须是明确业务异常：" + e.getCause());
                assertTrue(e.getCause().getMessage().contains("超出 100%"), "实际=" + e.getCause().getMessage());
                rejected++;
            }
        }
        assertEquals(1, ok, "恰一笔成功");
        assertEquals(1, rejected, "另一笔明确拒绝（不是静默丢失，也不是双成功）");
        assertTrue(futureSum(2, "20991201000000") <= 10_000, "全部未来断点合计必须 ≤10000");
        assertTrue(futureSum(2, "20991101000000") <= 10_000);
    }

    /** 不同商品线并发：各锁各行，互不阻塞，双双成功。 */
    @Test
    void differentLinesWriteConcurrently() throws Exception {
        seed(1, 1, 7000, "20260101000000");
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Callable<Long>> jobs = List.of(
                () -> { barrier.await(10, TimeUnit.SECONDS);
                        return splitConfigService.createVersion(bo(1, 1, 6000, "20991201000000"), "20991201000000"); },
                () -> { barrier.await(10, TimeUnit.SECONDS);
                        return splitConfigService.createVersion(bo(2, 1, 4000, "20991201000000"), "20991201000000"); });
        List<Future<Long>> futures = pool.invokeAll(jobs, 30, TimeUnit.SECONDS);
        assertFalse(futures.get(0).isCancelled());
        assertFalse(futures.get(1).isCancelled());
        assertTrue(futures.get(0).get() > 0, "售水线写入成功，不被配送线阻塞");
        assertTrue(futures.get(1).get() > 0, "配送线写入成功，不被售水线阻塞");
        pool.shutdown();
    }

    /** 同收款方同生效时点：uk_split_config_version 仍有效（锁内串行化不改变唯一键语义）。 */
    @Test
    void sameReceiverSameEffectStillHitsUniqueKey() {
        splitConfigService.createVersion(bo(2, 1, 4000, "20991201000000"), "20991201000000");
        JbkException ex = assertThrows(JbkException.class,
                () -> splitConfigService.createVersion(bo(2, 1, 5000, "20991201000000"), "20991201000000"));
        assertTrue(ex.getMessage().contains("已有版本"), "实际=" + ex.getMessage());
    }

    /** 顺序写入的全未来断点校验保持：复审场景按顺序提交时第二笔就该被拒。 */
    @Test
    void sequentialFutureBreakpointSweepStillRejects() {
        splitConfigService.createVersion(bo(2, 1, 6000, "20991201000000"), "20991201000000");
        JbkException ex = assertThrows(JbkException.class,
                () -> splitConfigService.createVersion(bo(2, 2, 5000, "20991101000000"), "20991101000000"));
        assertTrue(ex.getMessage().contains("超出 100%"), "实际=" + ex.getMessage());
        assertTrue(futureSum(2, "20991201000000") <= 10_000);
    }

    /** 锁锚缺失（新库未跑迁移）：fail-closed 拒绝写入，绝不退化为无锁校验。 */
    @Test
    void missingLockAnchorFailsClosed() {
        jdbc.execute("DELETE FROM ws_split_line_lock");
        JbkException ex = assertThrows(JbkException.class,
                () -> splitConfigService.createVersion(bo(2, 1, 1000, "20991201000000"), "20991201000000"));
        assertTrue(ex.getMessage().contains("锁锚未初始化"), "实际=" + ex.getMessage());
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM ws_split_config", Long.class), "零写入");
    }
}
