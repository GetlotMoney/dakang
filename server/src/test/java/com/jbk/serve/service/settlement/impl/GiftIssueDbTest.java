package com.jbk.serve.service.settlement.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.aftersale.WsCardEntitlementBatchMapper;
import com.jbk.serve.mapper.ops.WsDomainEventMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.mapper.user.WsCardMapper;
import com.jbk.serve.mapper.user.WsUserMapper;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.serve.service.ops.impl.WsDomainEventServiceImpl;
import com.jbk.serve.service.settlement.IGiftCardService;
import com.jbk.tool.data.settlement.bo.GiftIssueBo;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 赠卡发放的<b>真实 MySQL + 真实 Spring 事务</b>并发与重放证据链测试（R1 P1-1 / P1-2）。
 *
 * <p>P1-1 被钉住的缺陷：只把卡查询改锁定读，GIFT 流水与发放批次仍在 REPEATABLE READ 的
 * 旧快照里（读视图在方法开头的 selectById 已建立）——并发合法重放的败方看得见胜方的卡、
 * 看不见胜方的流水，被误判「参数不符/冲突」。修复=类级 READ_COMMITTED 让三项证据同处
 * 最新已提交视野。<b>删除 READ_COMMITTED 或把流水恢复普通旧快照读，本类并发用例必须转红。</b></p>
 *
 * <p>P1-2：重放证据链 fail-closed——批次必须存在且唯一、快照必须可解析、expireDays 必须
 * 存在且一致；任一缺失/重复/损坏拒绝，且拒绝路径卡/流水/批次/审计零新增。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = GiftIssueDbTest.Ctx.class)
class GiftIssueDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_gift_it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static final long USER_ID = 9L;
    private static final String REQUEST_ID = "550e8400-e29b-41d4-a716-446655440077";

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
            // 与生产同一个审计字段填充器：域事件的 DATA_STATUS/CREATE_BY 等靠 INSERT 自动填充
            com.baomidou.mybatisplus.core.config.GlobalConfig globalConfig =
                    new com.baomidou.mybatisplus.core.config.GlobalConfig();
            globalConfig.setMetaObjectHandler(new com.jbk.tool.config.system.mybatis.MpMetaObjectHandler());
            factory.setGlobalConfig(globalConfig);
            return new SqlSessionTemplate(factory.getObject());
        }

        @Bean
        MapperFactoryBean<WsCardMapper> cardMapper(SqlSessionTemplate t) {
            MapperFactoryBean<WsCardMapper> b = new MapperFactoryBean<>(WsCardMapper.class);
            b.setSqlSessionTemplate(t);
            return b;
        }

        @Bean
        MapperFactoryBean<WsUserMapper> userMapper(SqlSessionTemplate t) {
            MapperFactoryBean<WsUserMapper> b = new MapperFactoryBean<>(WsUserMapper.class);
            b.setSqlSessionTemplate(t);
            return b;
        }

        @Bean
        MapperFactoryBean<WsWalletFlowMapper> walletFlowMapper(SqlSessionTemplate t) {
            MapperFactoryBean<WsWalletFlowMapper> b = new MapperFactoryBean<>(WsWalletFlowMapper.class);
            b.setSqlSessionTemplate(t);
            return b;
        }

        @Bean
        MapperFactoryBean<WsCardEntitlementBatchMapper> batchMapper(SqlSessionTemplate t) {
            MapperFactoryBean<WsCardEntitlementBatchMapper> b =
                    new MapperFactoryBean<>(WsCardEntitlementBatchMapper.class);
            b.setSqlSessionTemplate(t);
            return b;
        }

        @Bean
        MapperFactoryBean<WsDomainEventMapper> domainEventMapper(SqlSessionTemplate t) {
            MapperFactoryBean<WsDomainEventMapper> b = new MapperFactoryBean<>(WsDomainEventMapper.class);
            b.setSqlSessionTemplate(t);
            return b;
        }

        /** 审计要求断言「恰一条 GIFT_ISSUE 审计」：域事件服务必须真实落库，不 mock。 */
        @Bean
        IWsDomainEventService domainEventService() {
            return new WsDomainEventServiceImpl();
        }

        @Bean
        GiftCardServiceImpl giftCardService() {
            return new GiftCardServiceImpl();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    /** 按接口注入：@Transactional(READ_COMMITTED) 的织入是本类断言成立的前提。 */
    @Autowired
    private IGiftCardService giftCardService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        // ws_user 用部署真源 DDL（MP selectById 全列映射，手写最小表会漏列没完没了）
        createTableFromInitSql("01-base.sql", "ws_user");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_card (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT, CARD_NO VARCHAR(50), CARD_TYPE TINYINT DEFAULT 1,
                  USER_ID BIGINT, BALANCE_AMOUNT BIGINT NOT NULL DEFAULT 0, BALANCE_ML BIGINT NOT NULL DEFAULT 0,
                  PACKAGE_ID BIGINT NULL, PACKAGE_SNAP MEDIUMTEXT NULL, SCOPE_JSON VARCHAR(2000) NULL,
                  EXPIRE_TIME VARCHAR(20) NULL, CARD_STATUS TINYINT, ISSUE_ORDER_ID BIGINT NULL,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  UNIQUE KEY uk_card_no (CARD_NO)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_wallet_flow (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  CARD_ID BIGINT, USER_ID BIGINT, FLOW_TYPE TINYINT,
                  AMOUNT_CHANGE BIGINT NOT NULL DEFAULT 0, ML_CHANGE BIGINT NOT NULL DEFAULT 0,
                  AMOUNT_AFTER BIGINT NOT NULL, ML_AFTER BIGINT NOT NULL,
                  ORDER_ID BIGINT, FLOW_REMARK VARCHAR(255), BIZ_IDEMPOTENCY_KEY VARCHAR(64) NULL,
                  UNIQUE KEY uk_wallet_flow_biz_key (BIZ_IDEMPOTENCY_KEY)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_card_entitlement_batch (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(14),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(14),
                  CARD_ID BIGINT NOT NULL, USER_ID BIGINT NOT NULL, SOURCE_TYPE TINYINT NOT NULL,
                  ORDER_ID BIGINT NULL, ORDER_NO VARCHAR(32) NULL, PAYMENT_ID BIGINT NULL,
                  PACKAGE_ID BIGINT NULL, PACKAGE_SNAP TEXT NULL,
                  PAY_AMOUNT_FEN BIGINT NOT NULL DEFAULT 0, GRANT_AMOUNT_FEN BIGINT NOT NULL DEFAULT 0,
                  GRANT_BONUS_FEN BIGINT NOT NULL DEFAULT 0, GRANT_WATER_ML BIGINT NOT NULL DEFAULT 0,
                  REMAIN_AMOUNT_FEN BIGINT NOT NULL DEFAULT 0, REMAIN_WATER_ML BIGINT NOT NULL DEFAULT 0,
                  EXPIRE_TIME VARCHAR(14) NULL, SCOPE_JSON TEXT NULL,
                  BATCH_STATUS TINYINT NOT NULL, REFUND_LOCKED_BY BIGINT NULL,
                  REFUND_LOCK_TIME VARCHAR(14) NULL, REFUNDED_AMOUNT_FEN BIGINT NOT NULL DEFAULT 0,
                  VERSION INT NOT NULL DEFAULT 1,
                  UNIQUE KEY uk_batch_order (ORDER_ID)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        createTableFromInitSql("02-ws-business.sql", "ws_domain_event");
        jdbc.execute("TRUNCATE TABLE ws_card");
        jdbc.execute("TRUNCATE TABLE ws_wallet_flow");
        jdbc.execute("TRUNCATE TABLE ws_card_entitlement_batch");
        jdbc.execute("TRUNCATE TABLE ws_domain_event");
        jdbc.execute("DELETE FROM ws_user");
        jdbc.update("INSERT INTO ws_user(ID, USER_NAME, DATA_STATUS, CREATE_BY, CREATE_TIME, "
                + "UPDATE_BY, UPDATE_TIME, DISABLED_FLAG, USER_STATUS, POINTS) "
                + "VALUES(?, 'R1', 0, 1, '20260807000000', 1, '20260807000000', 1, 1, 0)",
                USER_ID);
    }

    /** 从部署真源提取完整建表（IF NOT EXISTS 化）——手写最小表对 MP 全列映射漏列没完没了。 */
    private void createTableFromInitSql(String initFile, String table) {
        try {
            java.nio.file.Path init = java.nio.file.Path.of("..", "deploy", "mysql", "init", initFile);
            String sql = java.nio.file.Files.readString(init);
            int start = sql.indexOf("CREATE TABLE `" + table + "`");
            int end = sql.indexOf(";", start);
            String ddl = sql.substring(start, end + 1)
                    .replace("CREATE TABLE `" + table + "`", "CREATE TABLE IF NOT EXISTS `" + table + "`");
            jdbc.execute(ddl);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读取 init 真源失败：" + table, e);
        }
    }

    private GiftIssueBo bo(long fen, long ml, int days, String scope) {
        GiftIssueBo bo = new GiftIssueBo();
        bo.setRequestId(REQUEST_ID);
        bo.setUserId(USER_ID);
        bo.setGrantFen(fen);
        bo.setGrantMl(ml);
        bo.setExpireDays(days);
        bo.setScopeJson(scope);
        bo.setRemark("R1 测试");
        return bo;
    }

    private void assertExactlyOneOfEverything() {
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM ws_card", Long.class), "恰一张卡");
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM ws_wallet_flow "
                + "WHERE BIZ_IDEMPOTENCY_KEY = 'GIFT:" + REQUEST_ID + "'", Long.class), "恰一条 GIFT 流水");
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM ws_card_entitlement_batch",
                Long.class), "恰一个发放批次");
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM ws_domain_event "
                + "WHERE BIZ_IDEMPOTENCY_KEY = 'GIFT_ISSUE:" + REQUEST_ID + "'", Long.class),
                "恰一条 GIFT_ISSUE 关键审计");
    }

    /**
     * R1 P1-1：同一请求号两个事务<b>同时</b>发放（不是先提交后重放的顺序场景）。
     * READ_COMMITTED 下败方在撞键分支能读到胜方刚提交的卡、流水与批次，三项证据齐备，
     * 两个调用都正常收敛到同一 cardId；全库各类记录恰一条。
     */
    @Test
    void concurrentSameGiftRequestReturnsSameCardId() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Callable<Long>> jobs = List.of(
                () -> { barrier.await(10, TimeUnit.SECONDS); return giftCardService.issue(bo(1000, 50000, 30, null), 1L); },
                () -> { barrier.await(10, TimeUnit.SECONDS); return giftCardService.issue(bo(1000, 50000, 30, null), 1L); });
        List<Future<Long>> futures = pool.invokeAll(jobs, 30, TimeUnit.SECONDS);
        assertFalse(futures.get(0).isCancelled(), "发放事务 30s 内完成（无死锁）");
        assertFalse(futures.get(1).isCancelled(), "发放事务 30s 内完成（无死锁）");
        Long a = futures.get(0).get();
        Long b = futures.get(1).get();
        pool.shutdown();

        assertEquals(a, b, "两个并发合法重放必须收敛到同一 cardId，不得误报冲突");
        assertExactlyOneOfEverything();
    }

    /** R1 P1-2：改有效期的重放必须拒绝，零新增。 */
    @Test
    void changedExpireDaysReplayRejected() {
        giftCardService.issue(bo(1000, 50000, 30, null), 1L);
        JbkException ex = assertThrows(JbkException.class,
                () -> giftCardService.issue(bo(1000, 50000, 60, null), 1L));
        assertTrue(ex.getMessage().contains("有效期与本次不符"), "实际=" + ex.getMessage());
        assertExactlyOneOfEverything();
    }

    /** R1 P1-2：改可用范围的重放必须拒绝，零新增。 */
    @Test
    void changedScopeReplayRejected() {
        giftCardService.issue(bo(1000, 50000, 30, null), 1L);
        JbkException ex = assertThrows(JbkException.class,
                () -> giftCardService.issue(bo(1000, 50000, 30,
                        "{\"scopeType\":\"specified\",\"stationIds\":[\"1\"]}"), 1L));
        assertTrue(ex.getMessage().contains("可用范围与本次不符"), "实际=" + ex.getMessage());
        assertExactlyOneOfEverything();
    }

    /** R1 P1-2：发放批次证据缺失——fail-closed 拒绝，不得按幂等成功放行。 */
    @Test
    void missingGiftBatchReplayRejected() {
        giftCardService.issue(bo(1000, 50000, 30, null), 1L);
        jdbc.update("DELETE FROM ws_card_entitlement_batch");
        JbkException ex = assertThrows(JbkException.class,
                () -> giftCardService.issue(bo(1000, 50000, 30, null), 1L));
        assertTrue(ex.getMessage().contains("缺少发放批次证据"), "实际=" + ex.getMessage());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM ws_card", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM ws_card_entitlement_batch", Long.class),
                "拒绝路径不补建批次");
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM ws_wallet_flow", Long.class));
    }

    /** R1 P1-2：发放快照损坏——fail-closed 拒绝。 */
    @Test
    void malformedGiftSnapshotReplayRejected() {
        giftCardService.issue(bo(1000, 50000, 30, null), 1L);
        jdbc.update("UPDATE ws_card_entitlement_batch SET PACKAGE_SNAP = 'not-json{{'");
        JbkException ex = assertThrows(JbkException.class,
                () -> giftCardService.issue(bo(1000, 50000, 30, null), 1L));
        assertTrue(ex.getMessage().contains("不可解析") || ex.getMessage().contains("缺少有效期证据"),
                "实际=" + ex.getMessage());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM ws_card", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM ws_wallet_flow", Long.class));
    }

    /** R1 P1-2：发放批次重复——账本异常，fail-closed 拒绝。 */
    @Test
    void duplicateGiftBatchReplayRejected() {
        giftCardService.issue(bo(1000, 50000, 30, null), 1L);
        jdbc.update("INSERT INTO ws_card_entitlement_batch(CARD_ID, USER_ID, SOURCE_TYPE, "
                + "PAY_AMOUNT_FEN, GRANT_AMOUNT_FEN, GRANT_BONUS_FEN, GRANT_WATER_ML, "
                + "REMAIN_AMOUNT_FEN, REMAIN_WATER_ML, EXPIRE_TIME, BATCH_STATUS, "
                + "REFUNDED_AMOUNT_FEN, VERSION, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME) "
                + "SELECT CARD_ID, USER_ID, SOURCE_TYPE, PAY_AMOUNT_FEN, GRANT_AMOUNT_FEN, GRANT_BONUS_FEN, "
                + "GRANT_WATER_ML, REMAIN_AMOUNT_FEN, REMAIN_WATER_ML, EXPIRE_TIME, BATCH_STATUS, "
                + "REFUNDED_AMOUNT_FEN, VERSION, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME "
                + "FROM ws_card_entitlement_batch LIMIT 1");
        JbkException ex = assertThrows(JbkException.class,
                () -> giftCardService.issue(bo(1000, 50000, 30, null), 1L));
        assertTrue(ex.getMessage().contains("多个发放批次"), "实际=" + ex.getMessage());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM ws_card", Long.class));
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM ws_card_entitlement_batch", Long.class),
                "拒绝路径不删不改既有批次");
    }
}
