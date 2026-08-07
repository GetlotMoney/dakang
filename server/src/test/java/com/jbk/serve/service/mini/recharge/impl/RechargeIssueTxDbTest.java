package com.jbk.serve.service.mini.recharge.impl;

import com.jbk.serve.mapper.aftersale.WsCardEntitlementBatchMapper;
import com.jbk.serve.service.aftersale.batch.EntitlementLedger;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.trade.RechargeCreditMapper;
import com.jbk.serve.service.mini.recharge.IRechargeCreditTx;
import com.jbk.serve.service.mini.recharge.IRechargeIssueTx;
import com.jbk.serve.service.mini.recharge.RechargePayExpire;
import com.jbk.serve.service.mini.card.WaterCardScope;
import com.jbk.serve.service.mini.recharge.RechargeSnapshot;
import com.jbk.tool.data.product.po.WsPackage;
import com.jbk.tool.data.user.po.WsCard;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * L2-A 发卡事务的<b>真实 MySQL + 真实 Spring 事务</b>集成测试（决策 A1~A4）。
 *
 * <p>这里测的三件事都无法用单测证明：</p>
 * <ol>
 *   <li><b>发卡原子性</b>：建卡、加权益、唯一流水、CARD_ID 回填、订单 2→4 必须同生共死——
 *       任一步失败不留卡、不留流水、订单不动（决策 A2）。</li>
 *   <li><b>发卡幂等</b>靠 {@code uk_card_issue_order} + {@code uk_wallet_flow_biz_key} 双唯一键
 *       + Spring 回滚共同保证；重放与 20 并发全库只能出现一张卡、一条流水（决策 A3）。</li>
 *   <li><b>资格复查</b>在锁内真实生效：已有任意 DATA_STATUS=0 的卡即不可恢复且零残留（决策 A4）。</li>
 * </ol>
 *
 * <p>无 Docker 环境自动跳过。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = RechargeIssueTxDbTest.Ctx.class)
class RechargeIssueTxDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_issue_it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static final long USER_ID = 9L;
    private static final long ORDER_ID = 777L;
    private static final long PAYMENT_ID = 888L;
    private static final long EVENT_ID = 999L;
    private static final String ORDER_NO = "RC0000000000000000000000000A01";
    private static final long PAY_AMOUNT = 10000L;
    private static final long WATER_ML = 500000L;
    private static final int EXPIRE_DAYS = 365;
    private static final String SCOPE = "{\"scopeType\":\"specified\",\"stationIds\":[1]}";
    private static final String UUID = "550e8400-e29b-41d4-a716-446655440000";

    /** 订单创建时间与权威支付成功时间（同一支付窗口内）。 */
    private static final String CREATE_TIME = "20260722194200";
    private static final String PAID = "20260722194314";
    private static final String PROCESSING = "20260722194320";

    @Configuration
    @EnableTransactionManagement
    static class Ctx {
        @Bean
        DataSource dataSource() {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl(MYSQL.getJdbcUrl());
            ds.setUsername(MYSQL.getUsername());
            ds.setPassword(MYSQL.getPassword());
            ds.setMaximumPoolSize(24);
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
            com.baomidou.mybatisplus.core.MybatisConfiguration cfg =
                    new com.baomidou.mybatisplus.core.MybatisConfiguration();
            cfg.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(cfg);
            factory.setMapperLocations(new org.springframework.core.io.support
                    .PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/trade/TradeCardMapper.xml"));
            return new SqlSessionTemplate(factory.getObject());
        }

        @Bean
        MapperFactoryBean<RechargeCreditMapper> creditMapper(SqlSessionTemplate template) {
            MapperFactoryBean<RechargeCreditMapper> bean = new MapperFactoryBean<>(RechargeCreditMapper.class);
            bean.setSqlSessionTemplate(template);
            return bean;
        }

        @Bean
        MapperFactoryBean<WsCardEntitlementBatchMapper> wsCardEntitlementBatchMapper(SqlSessionTemplate t) {
            MapperFactoryBean<WsCardEntitlementBatchMapper> bean = new MapperFactoryBean<>(
                    WsCardEntitlementBatchMapper.class);
            bean.setSqlSessionTemplate(t);
            return bean;
        }

        @Bean
        RechargeLedgerVerifier ledgerVerifier() {
            return new RechargeLedgerVerifier();
        }

        @Bean
        RechargeIssueTxImpl issueTx(RechargeCreditMapper mapper, RechargeLedgerVerifier ledger,
                                    WsCardEntitlementBatchMapper batchMapper) {
            return new RechargeIssueTxImpl(mapper, ledger, batchMapper);
        }

        @Bean
        RechargeLockedState lockedState(RechargeCreditMapper mapper) {
            return new RechargeLockedState(mapper);
        }

        @Bean
        RechargeCreditTxImpl creditTx(RechargeCreditMapper mapper, RechargeLockedState locked,
                                      RechargeLedgerVerifier ledger,
                                      WsCardEntitlementBatchMapper batchMapper,
                                      EntitlementLedger entitlementLedger) {
            return new RechargeCreditTxImpl(mapper, locked, ledger, batchMapper, entitlementLedger);
        }

        @Bean
        RechargeCreditFailureTxImpl creditFailureTx(RechargeCreditMapper mapper, RechargeLockedState locked,
                                                    RechargeLedgerVerifier ledger) {
            return new RechargeCreditFailureTxImpl(mapper, locked, ledger);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.aftersale.WsEntitlementAllocationMapper> allocationMapper(
                SqlSessionTemplate t) {
            MapperFactoryBean<com.jbk.serve.mapper.aftersale.WsEntitlementAllocationMapper> bean =
                    new MapperFactoryBean<>(com.jbk.serve.mapper.aftersale.WsEntitlementAllocationMapper.class);
            bean.setSqlSessionTemplate(t);
            return bean;
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.trade.TradeCardMapper> tradeCardMapper(SqlSessionTemplate t) {
            MapperFactoryBean<com.jbk.serve.mapper.trade.TradeCardMapper> bean =
                    new MapperFactoryBean<>(com.jbk.serve.mapper.trade.TradeCardMapper.class);
            bean.setSqlSessionTemplate(t);
            return bean;
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.trade.WsWalletFlowMapper> walletFlowMapperBean(SqlSessionTemplate t) {
            MapperFactoryBean<com.jbk.serve.mapper.trade.WsWalletFlowMapper> bean =
                    new MapperFactoryBean<>(com.jbk.serve.mapper.trade.WsWalletFlowMapper.class);
            bean.setSqlSessionTemplate(t);
            return bean;
        }

        @Bean
        EntitlementLedger entitlementLedger(WsCardEntitlementBatchMapper batchMapper,
                                            com.jbk.serve.mapper.aftersale.WsEntitlementAllocationMapper allocationMapper,
                                            com.jbk.serve.mapper.trade.TradeCardMapper tradeCardMapper,
                                            com.jbk.serve.mapper.trade.WsWalletFlowMapper walletFlowMapper) {
            return new EntitlementLedger(batchMapper, allocationMapper, tradeCardMapper, walletFlowMapper);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    /** 按接口注入：@Transactional 走 JDK 接口代理，注入实现类会失败（注入本身即证明事务增强已织入）。 */
    @Autowired
    private IRechargeIssueTx issueTx;
    /** 已完成购卡单的重放事实按路由规则走 creditTx（CARD_ID 已回填）——必须一并验证该真实路径。 */
    @Autowired
    private IRechargeCreditTx creditTx;
    @Autowired
    private com.jbk.serve.service.mini.recharge.IRechargeCreditFailureTx creditFailureTx;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_user (
                  ID BIGINT PRIMARY KEY
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        // E2E-04 包D：充值入账同事务建立权益批次，故本类的 schema 必须包含它。
        // uk_batch_order 是「每笔充值恰好一个批次」的物理保证——本类的重放用例
        // 正是靠它与 uk_card_issue_order 一起把「重放不得二次发权益」钉死。
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_card_entitlement_batch (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(14),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(14),
                  CARD_ID BIGINT NOT NULL, USER_ID BIGINT NOT NULL, SOURCE_TYPE TINYINT NOT NULL,
                  ORDER_ID BIGINT NULL, ORDER_NO VARCHAR(32) NULL, PAYMENT_ID BIGINT NULL,
                  PACKAGE_ID BIGINT NULL, PACKAGE_SNAP TEXT NULL,
                  PAY_AMOUNT_FEN BIGINT NOT NULL DEFAULT 0,
                  GRANT_AMOUNT_FEN BIGINT NOT NULL DEFAULT 0,
                  GRANT_BONUS_FEN BIGINT NOT NULL DEFAULT 0,
                  GRANT_WATER_ML BIGINT NOT NULL DEFAULT 0,
                  REMAIN_AMOUNT_FEN BIGINT NOT NULL DEFAULT 0,
                  REMAIN_WATER_ML BIGINT NOT NULL DEFAULT 0,
                  EXPIRE_TIME VARCHAR(14) NULL, SCOPE_JSON TEXT NULL,
                  BATCH_STATUS TINYINT NOT NULL, REFUND_LOCKED_BY BIGINT NULL,
                  REFUND_LOCK_TIME VARCHAR(14) NULL,
                  REFUNDED_AMOUNT_FEN BIGINT NOT NULL DEFAULT 0,
                  VERSION INT NOT NULL DEFAULT 1,
                  UNIQUE KEY uk_batch_order (ORDER_ID),
                  KEY idx_batch_pick (CARD_ID, BATCH_STATUS, EXPIRE_TIME, CREATE_TIME)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        // uk_card_no + uk_card_issue_order 双唯一键是本测试的主角之一：发卡幂等的最后防线在数据库层
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_card (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  CARD_NO VARCHAR(32), CARD_TYPE TINYINT, USER_ID BIGINT,
                  BALANCE_AMOUNT BIGINT NOT NULL DEFAULT 0, BALANCE_ML BIGINT NOT NULL DEFAULT 0,
                  PACKAGE_ID BIGINT NULL, PACKAGE_SNAP MEDIUMTEXT NULL, SCOPE_JSON VARCHAR(2000) NULL,
                  EXPIRE_TIME VARCHAR(20) NULL, CARD_STATUS TINYINT, ISSUE_ORDER_ID BIGINT NULL,
                  UNIQUE KEY uk_card_no (CARD_NO),
                  UNIQUE KEY uk_card_issue_order (ISSUE_ORDER_ID)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_order (
                  ID BIGINT PRIMARY KEY, ORDER_NO VARCHAR(64), ORDER_TYPE TINYINT, USER_ID BIGINT,
                  CARD_ID BIGINT, PACKAGE_ID BIGINT, PACKAGE_SNAP MEDIUMTEXT, ORDER_AMOUNT BIGINT,
                  PAY_WAY TINYINT, ORDER_STATUS TINYINT,
                  FINISH_TIME VARCHAR(20) NULL, CANCEL_REASON VARCHAR(200) NULL,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_TIME VARCHAR(20), UPDATE_TIME VARCHAR(20)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_payment (
                  ID BIGINT PRIMARY KEY, ORDER_ID BIGINT, ORDER_NO VARCHAR(64), TRANSACTION_ID VARCHAR(64),
                  PAY_AMOUNT BIGINT, PAY_STATUS TINYINT, PAY_SOURCE TINYINT, CURRENCY VARCHAR(16),
                  PAY_EXPIRE_TIME VARCHAR(20), PAY_SUCCESS_TIME VARCHAR(20), DATA_STATUS TINYINT DEFAULT 0,
                  CREATE_TIME VARCHAR(20)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_payment_event (
                  ID BIGINT PRIMARY KEY, ORDER_NO VARCHAR(64), ORDER_ID BIGINT, PAYMENT_ID BIGINT,
                  PAY_SOURCE TINYINT, FACT_CHANNEL TINYINT, PROVIDER_EVENT_KEY VARCHAR(100),
                  TRADE_STATE VARCHAR(32), TRANSACTION_ID VARCHAR(64), PAY_AMOUNT BIGINT, CURRENCY VARCHAR(16),
                  PAY_SUCCESS_TIME VARCHAR(20), PROCESSING_STATUS TINYINT, RETRY_COUNT INT DEFAULT 0,
                  NEXT_RETRY_TIME VARCHAR(20), CLAIM_TIME VARCHAR(20), LEASE_UNTIL VARCHAR(20),
                  RECOVERY_APPROVAL_GROUP_KEY VARCHAR(64), RECOVERY_APPROVED_BY BIGINT,
                  RECOVERY_APPROVED_TIME VARCHAR(20), RECOVERY_APPROVAL_REASON VARCHAR(500),
                  LAST_ERROR VARCHAR(500), PROCESSED_TIME VARCHAR(20), DATA_STATUS TINYINT DEFAULT 0,
                  UPDATE_TIME VARCHAR(20)
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
        jdbc.execute("TRUNCATE TABLE ws_wallet_flow");
        // 包D 批次表也要逐用例清空：uk_batch_order 跨用例复用同一 ORDER_ID 会撞键，
        // 表现为与被测逻辑无关的 DuplicateKey，掩盖真正的断言
        jdbc.execute("TRUNCATE TABLE ws_card_entitlement_batch");
        jdbc.execute("TRUNCATE TABLE ws_card");
        jdbc.execute("DELETE FROM ws_payment_event");
        jdbc.execute("DELETE FROM ws_payment");
        jdbc.execute("DELETE FROM ws_order");
        jdbc.execute("DELETE FROM ws_user");
        jdbc.update("INSERT INTO ws_user(ID) VALUES(?)", USER_ID);
        seedPurchaseOrder(EXPIRE_DAYS, CREATE_TIME, PAID);
    }

    /** 以给定套餐有效期/创建时间/支付时间落一套 purchase 待入账现场（order 2 / payment 2 / 事件已认领）。 */
    private void seedPurchaseOrder(Integer expireDays, String createTime, String paid) {
        jdbc.update("DELETE FROM ws_payment_event");
        jdbc.update("DELETE FROM ws_payment");
        jdbc.update("DELETE FROM ws_order");
        String snapshot = purchaseSnapshot(expireDays, createTime);
        String payExpire = RechargePayExpire.compute(createTime, null);
        jdbc.update("INSERT INTO ws_order(ID,ORDER_NO,ORDER_TYPE,USER_ID,CARD_ID,PACKAGE_ID,PACKAGE_SNAP,"
                        + "ORDER_AMOUNT,PAY_WAY,ORDER_STATUS,DATA_STATUS,CREATE_TIME) "
                        + "VALUES(?,?,?,?,NULL,?,?,?,?,?,0,?)",
                ORDER_ID, ORDER_NO, 2, USER_ID, 3L, snapshot, PAY_AMOUNT, 1, 2, createTime);
        jdbc.update("INSERT INTO ws_payment(ID,ORDER_ID,ORDER_NO,TRANSACTION_ID,PAY_AMOUNT,PAY_STATUS,PAY_SOURCE,"
                        + "CURRENCY,PAY_EXPIRE_TIME,PAY_SUCCESS_TIME,DATA_STATUS,CREATE_TIME) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,0,?)",
                PAYMENT_ID, ORDER_ID, ORDER_NO, "SIMTX-1", PAY_AMOUNT, 2, 2, "CNY",
                payExpire, paid, createTime);
        addClaimedEvent(EVENT_ID, paid);
    }

    private String purchaseSnapshot(Integer expireDays, String createTime) {
        // D-213 后 buildForPurchase 造不出有限期付费快照；历史旧单铸造见 LegacySnapshots
        return com.jbk.serve.service.mini.recharge.LegacySnapshots.forgeExpireDays(
                RechargeSnapshot.buildForPurchase(UUID, purchasePackage(null),
                        WaterCardScope.normalize(SCOPE, "套餐"), createTime),
                expireDays);
    }

    private WsPackage purchasePackage(Integer expireDays) {
        WsPackage p = new WsPackage();
        p.setId(3L);
        p.setPackageName("100元500升卡");
        p.setPayAmount(PAY_AMOUNT);
        p.setWaterMl(WATER_ML);
        p.setBonusAmount(0L);
        p.setUnitPriceSnap("20.00");
        p.setExpireDays(expireDays);
        return p;
    }

    // ================= 1：有限期正常发卡 =================

    @Test
    void issuesFiniteVirtualCardWithDeterministicCardNoAndExactBenefits() {
        assertEquals(IRechargeCreditTx.Outcome.CREDITED, issueTx.issue(EVENT_ID, PROCESSING).outcome());

        assertEquals(1, cardCount(), "恰好一张卡");
        long cardId = jdbc.queryForObject("SELECT ID FROM ws_card LIMIT 1", Long.class);
        assertEquals(expectedCardNo(ORDER_NO), cardValue("CARD_NO", String.class),
                "卡号必须由订单号确定性派生（VC + sha256 前 14 位大写）");
        assertEquals(1, cardValue("CARD_TYPE", Integer.class), "决策 A5：一期固定虚拟卡");
        assertEquals(USER_ID, cardValue("USER_ID", Long.class));
        assertEquals(0L, cardValue("BALANCE_AMOUNT", Long.class), "水量套餐售价不进余额");
        assertEquals(WATER_ML, cardValue("BALANCE_ML", Long.class));
        // 决策 A1：paySuccessTime + 365 天，期望值独立复算
        assertEquals(independentPlusDays(PAID, EXPIRE_DAYS), cardValue("EXPIRE_TIME", String.class));
        assertEquals("20270722194314", cardValue("EXPIRE_TIME", String.class));
        assertEquals(1, cardValue("CARD_STATUS", Integer.class));
        assertEquals(ORDER_ID, cardValue("ISSUE_ORDER_ID", Long.class), "发行锚点必写（决策 A3）");
        // 决策 A6：新卡精确继承创单冻结的套餐范围（语义比较，不比字符串表面）
        WaterCardScope cardScope = WaterCardScope.normalize(cardValue("SCOPE_JSON", String.class), "新卡");
        org.junit.jupiter.api.Assertions.assertTrue(
                cardScope.sameAuthorityAs(WaterCardScope.normalize(SCOPE, "套餐")));

        assertEquals(1, flowCount());
        assertEquals("RECHARGE:" + ORDER_NO,
                jdbc.queryForObject("SELECT BIZ_IDEMPOTENCY_KEY FROM ws_wallet_flow", String.class));
        assertEquals(0L, jdbc.queryForObject("SELECT AMOUNT_CHANGE FROM ws_wallet_flow", Long.class));
        assertEquals(WATER_ML, jdbc.queryForObject("SELECT ML_CHANGE FROM ws_wallet_flow", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT AMOUNT_AFTER FROM ws_wallet_flow", Long.class),
                "AFTER = 0 + 权益值");
        assertEquals(WATER_ML, jdbc.queryForObject("SELECT ML_AFTER FROM ws_wallet_flow", Long.class));

        assertEquals(cardId, jdbc.queryForObject("SELECT CARD_ID FROM ws_order WHERE ID=?", Long.class, ORDER_ID),
                "CARD_ID 必须回填为新卡");
        assertEquals(4, orderStatus());
        assertEquals(PROCESSING, jdbc.queryForObject("SELECT FINISH_TIME FROM ws_order WHERE ID=?",
                String.class, ORDER_ID));
        assertEquals(3, eventStatus(EVENT_ID), "事件组必须在同一事务收敛 PROCESSED");
    }

    /** 闰年跨度必须精确（项目踩过 20280709/20280710 的坑）：2027-07-10 + 365 天区间含 2028-02-29。 */
    @Test
    void leapYearExpiryIsExactDayArithmetic() {
        seedPurchaseOrder(EXPIRE_DAYS, "20270710114500", "20270710120000");
        assertEquals(IRechargeCreditTx.Outcome.CREDITED, issueTx.issue(EVENT_ID, "20270710120500").outcome());
        assertEquals("20280709120000", cardValue("EXPIRE_TIME", String.class));
        assertEquals(independentPlusDays("20270710120000", EXPIRE_DAYS), cardValue("EXPIRE_TIME", String.class));
    }

    // ================= 2：永久套餐 EXPIRE_TIME IS NULL =================

    @Test
    void permanentPackageIssuesCardWithNullExpiry() {
        seedPurchaseOrder(null, CREATE_TIME, PAID);
        assertEquals(IRechargeCreditTx.Outcome.CREDITED, issueTx.issue(EVENT_ID, PROCESSING).outcome());
        assertNull(cardValue("EXPIRE_TIME", String.class), "永久的唯一持久语义是 SQL NULL");
        assertEquals(WATER_ML, cardValue("BALANCE_ML", Long.class));
        assertEquals(4, orderStatus());
    }

    // ================= 3：重放幂等（发行锚点 + 唯一流水），有效期不变 =================

    @Test
    void replayNeitherIssuesSecondCardNorCreditsTwice() {
        issueTx.issue(EVENT_ID, PROCESSING);
        String expireAfterFirst = cardValue("EXPIRE_TIME", String.class);

        // 同一支付事实组的第二条 SUCCESS 事件（已认领）重放进发卡事务
        addClaimedEvent(1000L, PAID);
        assertEquals(IRechargeCreditTx.Outcome.ALREADY, issueTx.issue(1000L, "20260722194330").outcome());

        assertEquals(1, cardCount(), "全库恰好一张卡");
        assertEquals(1, flowCount(), "全库恰好一条流水");
        assertEquals(expireAfterFirst, cardValue("EXPIRE_TIME", String.class), "有效期不得被复算第二次");
        assertEquals(WATER_ML, cardValue("BALANCE_ML", Long.class), "水量不得被加第二次");
        assertEquals(3, eventStatus(1000L));
    }

    /**
     * 已完成购卡单 CARD_ID 已被回填，事实路由会把后续重放送进<b>事务 B（creditTx）</b>的
     * 只读幂等核验分支——purchase 快照在该分支必须按 NewCardExpiry 下限通过完成态核验。
     */
    @Test
    void replayThroughCreditTxAfterBackfillAlsoConverges() {
        issueTx.issue(EVENT_ID, PROCESSING);
        addClaimedEvent(1001L, PAID);

        assertEquals(IRechargeCreditTx.Outcome.ALREADY, creditTx.credit(1001L, "20260722194330").outcome(),
                "回填后 CARD_ID 非空，重放走 creditTx 也必须收敛而不是误判 mismatch");
        assertEquals(1, cardCount());
        assertEquals(1, flowCount());
        assertEquals(3, eventStatus(1001L));
    }

    // ================= 4：20 线程并发恰好一张卡 =================

    @Test
    void twentyConcurrentWorkersIssueExactlyOneCard() throws Exception {
        int threads = 20;
        for (int i = 0; i < threads; i++) {
            addClaimedEvent(2000L + i, PAID);
        }
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger credited = new AtomicInteger();
        AtomicInteger already = new AtomicInteger();

        List<Callable<Void>> jobs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            long eventId = 2000L + i;
            jobs.add(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                try {
                    IRechargeCreditTx.Outcome outcome = issueTx.issue(eventId, PROCESSING).outcome();
                    if (outcome == IRechargeCreditTx.Outcome.CREDITED) {
                        credited.incrementAndGet();
                    } else if (outcome == IRechargeCreditTx.Outcome.ALREADY) {
                        already.incrementAndGet();
                    }
                } catch (RuntimeException ignored) {
                    // 撞唯一键/事件已被收敛都算未发卡，下面用总账断言兜底
                }
                return null;
            });
        }
        for (Future<Void> f : pool.invokeAll(jobs)) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(1, credited.get(), "恰好一个线程真正发卡");
        assertEquals(1, cardCount(), "并发下全库只允许一张卡");
        assertEquals(1, flowCount(), "并发下全库只允许一条流水");
        assertEquals(WATER_ML, cardValue("BALANCE_ML", Long.class), "权益只能被加一次");
        assertEquals(4, orderStatus());
    }

    // ================= 4b：同用户两笔不同购卡单并发 → 恰好一张卡 =================

    /**
     * 口径修正 1（先测后删）：同一用户两笔<b>不同</b>已支付购卡单（不同 orderNo/requestId）并发发卡。
     * 与用例 4 的区别：那里 20 个事实同属一单，幂等由发行锚点/唯一键兜住；这里两单锚点、卡号、
     * 流水幂等键全都不同，唯一防线是「锁 ws_user 行串行化 + 锁后资格复查看得见对方刚发的卡」。
     * 期望：恰好一单发卡（order 4），另一单资格复查拒绝并按 A2 落痕为 payment 2 / order 6，
     * 全库恰好一张卡、一条流水——零第二卡、零第二流水。
     *
     * <p><b>2026-07-23 实测为红（竞态真实存在，暂禁用留作复现器）</b>：两线程各发出一张卡。
     * 根因：REPEATABLE READ 下 {@code countLiveCardsByUser} 是普通一致性读，读视图在事务首条
     * SELECT（selectEventById）时就已建立；后到者在 {@code lockUserRow} 上排队醒来后，
     * 资格复查读到的仍是先行者提交<b>前</b>的快照，看不见对方刚发的卡——锁串行化了执行顺序，
     * 但没让复查读到最新账。已修复（N-15）：发卡事务降级 READ_COMMITTED，锁后复查读最新已提交版本；
     * 刻意未用 FOR SHARE（无卡用户的二级索引锁定读会与相邻用户互等 gap 死锁）。本用例即回归闸。
     * 边界与回归状态统一记录在 docs/demo-module-status.md。</p>
     */
    @Test
    void twoDistinctPaidPurchaseOrdersOfSameUserIssueExactlyOneCard() throws Exception {
        long orderIdB = 778L;
        long paymentIdB = 889L;
        long eventIdB = 998L;
        String orderNoB = "RC0000000000000000000000000A02";
        String uuidB = "550e8400-e29b-41d4-a716-446655440111";
        String snapshotB = com.jbk.serve.service.mini.recharge.LegacySnapshots.forgeExpireDays(
                RechargeSnapshot.buildForPurchase(uuidB, purchasePackage(null),
                        WaterCardScope.normalize(SCOPE, "套餐"), CREATE_TIME), EXPIRE_DAYS);
        String payExpireB = RechargePayExpire.compute(CREATE_TIME, null);
        jdbc.update("INSERT INTO ws_order(ID,ORDER_NO,ORDER_TYPE,USER_ID,CARD_ID,PACKAGE_ID,PACKAGE_SNAP,"
                        + "ORDER_AMOUNT,PAY_WAY,ORDER_STATUS,DATA_STATUS,CREATE_TIME) "
                        + "VALUES(?,?,?,?,NULL,?,?,?,?,?,0,?)",
                orderIdB, orderNoB, 2, USER_ID, 3L, snapshotB, PAY_AMOUNT, 1, 2, CREATE_TIME);
        jdbc.update("INSERT INTO ws_payment(ID,ORDER_ID,ORDER_NO,TRANSACTION_ID,PAY_AMOUNT,PAY_STATUS,PAY_SOURCE,"
                        + "CURRENCY,PAY_EXPIRE_TIME,PAY_SUCCESS_TIME,DATA_STATUS,CREATE_TIME) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,0,?)",
                paymentIdB, orderIdB, orderNoB, "SIMTX-2", PAY_AMOUNT, 2, 2, "CNY",
                payExpireB, PAID, CREATE_TIME);
        jdbc.update("INSERT INTO ws_payment_event(ID,ORDER_NO,ORDER_ID,PAYMENT_ID,PAY_SOURCE,FACT_CHANNEL,"
                        + "PROVIDER_EVENT_KEY,TRADE_STATE,TRANSACTION_ID,PAY_AMOUNT,CURRENCY,PAY_SUCCESS_TIME,"
                        + "PROCESSING_STATUS,DATA_STATUS) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,2,0)",
                eventIdB, orderNoB, orderIdB, paymentIdB, 2, 3, "evt-" + eventIdB, "SUCCESS", "SIMTX-2",
                PAY_AMOUNT, "CNY", PAID);

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger credited = new AtomicInteger();
        AtomicInteger unrecoverable = new AtomicInteger();
        List<Callable<Void>> jobs = new ArrayList<>();
        for (long eventId : new long[]{EVENT_ID, eventIdB}) {
            jobs.add(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                // 复刻 Worker 的编排：发卡事务返回不可恢复时按 A2 落痕（payment 2 / order 6）
                IRechargeCreditTx.CreditResult result = issueTx.issue(eventId, PROCESSING);
                if (result.outcome() == IRechargeCreditTx.Outcome.CREDITED) {
                    credited.incrementAndGet();
                } else if (result.outcome() == IRechargeCreditTx.Outcome.UNRECOVERABLE) {
                    unrecoverable.incrementAndGet();
                    issueTx.recordFailure(eventId, false, result.reason(), PROCESSING);
                }
                return null;
            });
        }
        for (Future<Void> f : pool.invokeAll(jobs)) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(1, cardCount(), "同用户并发两笔不同购卡单，全库只允许一张卡");
        assertEquals(1, flowCount(), "零第二流水");
        assertEquals(1, credited.get(), "恰好一单真正发卡");
        assertEquals(1, unrecoverable.get(), "另一单必须被资格复查拒绝为不可恢复");
        // 输单：payment 保持 2（钱已收），order 转 6 待人工；赢单：order 4
        List<Integer> orderStatuses = jdbc.queryForList(
                "SELECT ORDER_STATUS FROM ws_order ORDER BY ORDER_STATUS", Integer.class);
        assertEquals(List.of(4, 6), orderStatuses);
        assertEquals(2, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_payment WHERE PAY_STATUS = 2", Integer.class),
                "两笔支付事实都保持成功态（钱都已收）");
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_order WHERE CARD_ID IS NOT NULL", Integer.class),
                "只有发卡成功的订单回填 CARD_ID");
    }

    // ================= 5：已有活卡 → 不可恢复且零残留 =================

    /** 决策 A4：任意 DATA_STATUS=0 的卡（这里故意用冻结卡）都阻断发卡；钱已收但不能再发卡，转人工。 */
    @Test
    void existingLiveCardEvenFrozenIsUnrecoverableWithZeroResidue() {
        insertForeignCard("OLDCARD000000001", 2, null);
        IRechargeCreditTx.CreditResult result = issueTx.issue(EVENT_ID, PROCESSING);

        assertEquals(IRechargeCreditTx.Outcome.UNRECOVERABLE, result.outcome());
        assertEquals(1, cardCount(), "只允许保留既有卡，不得新发");
        assertEquals(0, flowCount(), "零残留：无流水");
        assertEquals(2, orderStatus(), "订单不动，由失败落痕事务决定 2→6");
        assertNull(jdbc.queryForObject("SELECT CARD_ID FROM ws_order WHERE ID=?", Long.class, ORDER_ID));
    }

    /** 不可恢复失败经 recordFailure 落痕：payment 2 / order 6、事件组待对账、依旧无卡（决策 A2）。 */
    @Test
    void unrecoverableFailureRecordsOrderAbnormalWithoutCard() {
        insertForeignCard("OLDCARD000000001", 1, null);
        IRechargeCreditTx.CreditResult result = issueTx.issue(EVENT_ID, PROCESSING);
        assertEquals(IRechargeCreditTx.Outcome.UNRECOVERABLE, result.outcome());

        issueTx.recordFailure(EVENT_ID, false, result.reason(), PROCESSING);
        assertEquals(6, orderStatus(), "不可恢复：payment 2/order 6 无卡转人工");
        assertEquals(5, eventStatus(EVENT_ID), "事件组转待对账");
        assertEquals(1, cardCount(), "仍然只有既有卡");
        assertEquals(0, flowCount());

        // 可恢复落痕对照：订单保持现状、事件转待重试（防御性路径，发卡当前不产出可恢复分类）
        seedPurchaseOrder(EXPIRE_DAYS, CREATE_TIME, PAID);
        issueTx.recordFailure(EVENT_ID, true, "临时故障", PROCESSING);
        assertEquals(2, orderStatus(), "可恢复：订单保持 2 无卡等重试");
        assertEquals(4, eventStatus(EVENT_ID));
    }

    // ================= 6：步骤 g 撞幂等键 → 整体回滚无卡残留 =================

    @Test
    void duplicateBizKeyRollsBackCardAndCredit() {
        // 先手工占用同 bizKey（模拟历史/污染流水）
        jdbc.update("INSERT INTO ws_wallet_flow(DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,"
                        + "CARD_ID,USER_ID,FLOW_TYPE,AMOUNT_CHANGE,ML_CHANGE,AMOUNT_AFTER,ML_AFTER,"
                        + "ORDER_ID,FLOW_REMARK,BIZ_IDEMPOTENCY_KEY) VALUES(0,?,?,?,?,?,?,1,0,1,0,1,NULL,?,?)",
                USER_ID, CREATE_TIME, USER_ID, CREATE_TIME, 424242L, 424242L, "污染流水",
                "RECHARGE:" + ORDER_NO);

        assertThrows(RuntimeException.class, () -> issueTx.issue(EVENT_ID, PROCESSING),
                "撞唯一键必须抛出并整体回滚");
        assertEquals(0, cardCount(), "回滚后不得留下任何新卡");
        assertEquals(1, flowCount(), "只保留手工插入的占位流水");
        assertEquals(2, orderStatus(), "订单不动");
        assertNull(jdbc.queryForObject("SELECT CARD_ID FROM ws_order WHERE ID=?", Long.class, ORDER_ID));
        assertEquals(2, eventStatus(EVENT_ID), "事件保持已认领，由编排层落痕");
    }

    // ================= 7：回填 CAS 失败 → 回滚无卡残留 =================

    @Test
    void backfillCasFailureRollsBackEverything() {
        // 先把 CARD_ID 改成非 NULL：步骤 h 的 CAS（WHERE CARD_ID IS NULL）必须影响 0 行并触发整体回滚
        jdbc.update("UPDATE ws_order SET CARD_ID=424242 WHERE ID=?", ORDER_ID);

        assertThrows(JbkException.class, () -> issueTx.issue(EVENT_ID, PROCESSING));
        assertEquals(0, cardCount(), "回滚后不得留下新卡");
        assertEquals(0, flowCount(), "回滚后不得留下流水");
        assertEquals(2, orderStatus());
        assertEquals(424242L, jdbc.queryForObject("SELECT CARD_ID FROM ws_order WHERE ID=?", Long.class, ORDER_ID),
                "既有 CARD_ID 关联绝不被覆盖");
    }

    // ================= 8：身份自相矛盾与延迟过期 =================

    /** CARD_ID 为空却挂着 L2-B 充值快照：订单身份自相矛盾，绝不能按「无卡」发卡。 */
    @Test
    void rechargeSnapshotOnCardlessOrderRefusesIssuance() {
        WsCard c = new WsCard();
        c.setId(100L);
        c.setUserId(USER_ID);
        c.setCardStatus(1);
        c.setScopeJson(SCOPE);
        c.setExpireTime(null);
        WsPackage p = new WsPackage();
        p.setId(3L);
        p.setPackageName("100元500升卡");
        p.setPayAmount(PAY_AMOUNT);
        p.setWaterMl(WATER_ML);
        p.setBonusAmount(0L);
        p.setUnitPriceSnap("20.00");
        p.setExpireDays(null);
        String l2bSnap = RechargeSnapshot.build(UUID, p, c,
                WaterCardScope.normalize(SCOPE, "水卡"), null, CREATE_TIME);
        jdbc.update("UPDATE ws_order SET PACKAGE_SNAP=? WHERE ID=?", l2bSnap, ORDER_ID);

        assertThrows(JbkException.class, () -> issueTx.issue(EVENT_ID, PROCESSING));
        assertEquals(0, cardCount());
        assertEquals(0, flowCount());
        assertEquals(2, orderStatus());
    }

    /** 处理严重延迟到新卡有效期已过去：不可恢复、零残留，不发一张当场作废的卡。 */
    @Test
    void computedExpiryAlreadyPassedIsUnrecoverable() {
        seedPurchaseOrder(1, "20260101000000", "20260101000500");
        IRechargeCreditTx.CreditResult result = issueTx.issue(EVENT_ID, "20260601000000");
        assertEquals(IRechargeCreditTx.Outcome.UNRECOVERABLE, result.outcome());
        assertEquals(0, cardCount());
        assertEquals(0, flowCount());
        assertEquals(2, orderStatus());
    }

    // ------------------------------------------------------------------

    /** 转正现场造数（审计 R2 P1-3）：过期耗尽赠卡 + 已支付转正单 + 已认领事件。 */
    private void seedPromoteScene(long giftCardId, long orderId, long paymentId, long eventId,
                                  long ownerUserId, String orderNo) {
        String expiredAt = "20250101120000";
        jdbc.update("INSERT INTO ws_card(ID,DATA_STATUS,CARD_NO,CARD_TYPE,USER_ID,BALANCE_AMOUNT,BALANCE_ML,"
                        + "SCOPE_JSON,EXPIRE_TIME,CARD_STATUS,ISSUE_ORDER_ID) "
                        + "VALUES(?,0,?,1,?,0,0,?,?,3,NULL)",
                giftCardId, "GC-" + giftCardId, ownerUserId, SCOPE, expiredAt);
        WsPackage permanent = purchasePackage(null);
        com.jbk.tool.data.user.po.WsCard c = new com.jbk.tool.data.user.po.WsCard();
        c.setId(giftCardId);
        c.setCardStatus(3);
        c.setExpireTime(expiredAt);
        c.setScopeJson(SCOPE);
        String requestId = "550e8400-e29b-41d4-a716-4466554400" + (giftCardId % 90 + 10);
        String snap = RechargeSnapshot.build(requestId, permanent, c,
                WaterCardScope.normalize(SCOPE, "水卡"), null, CREATE_TIME, true);
        String payExpire = RechargePayExpire.compute(CREATE_TIME, null);
        jdbc.update("INSERT INTO ws_order(ID,ORDER_NO,ORDER_TYPE,USER_ID,CARD_ID,PACKAGE_ID,PACKAGE_SNAP,"
                        + "ORDER_AMOUNT,PAY_WAY,ORDER_STATUS,DATA_STATUS,CREATE_TIME) VALUES(?,?,?,?,?,?,?,?,?,?,0,?)",
                orderId, orderNo, 2, ownerUserId, giftCardId, 3L, snap, PAY_AMOUNT, 1, 2, CREATE_TIME);
        jdbc.update("INSERT INTO ws_payment(ID,ORDER_ID,ORDER_NO,TRANSACTION_ID,PAY_AMOUNT,PAY_STATUS,PAY_SOURCE,"
                        + "CURRENCY,PAY_EXPIRE_TIME,PAY_SUCCESS_TIME,DATA_STATUS,CREATE_TIME) VALUES(?,?,?,?,?,?,?,?,?,?,0,?)",
                paymentId, orderId, orderNo, "SIMTX-P" + orderId, PAY_AMOUNT, 2, 2, "CNY",
                payExpire, PAID, CREATE_TIME);
        jdbc.update("INSERT INTO ws_payment_event(ID,ORDER_NO,ORDER_ID,PAYMENT_ID,PAY_SOURCE,FACT_CHANNEL,"
                        + "PROVIDER_EVENT_KEY,TRADE_STATE,TRANSACTION_ID,PAY_AMOUNT,CURRENCY,PAY_SUCCESS_TIME,"
                        + "PROCESSING_STATUS,DATA_STATUS) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,2,0)",
                eventId, orderNo, orderId, paymentId, 2, 3, "evt-" + eventId, "SUCCESS",
                "SIMTX-P" + orderId, PAY_AMOUNT, "CNY", PAID);
    }

    /**
     * 审计 R2 P1-3：<b>首次购卡 × 赠卡转正</b>两条不同事务实现并发争夺同一用户的付费名额。
     * 两链共用 ws_user 行锁（payment→order→user→card 同序）+ READ_COMMITTED 锁内复查：
     * 任一方可赢，但最终恰一张付费卡；输家 UNRECOVERABLE（订单6/事实待对账通道），
     * payment 的成功事实保持不变，零卡权益、零充值流水。
     */
    @Test
    void concurrentFirstPurchaseAndPromotionYieldExactlyOnePaidCard() throws Exception {
        long giftCardId = 500L;
        seedPromoteScene(giftCardId, ORDER_ID + 100, PAYMENT_ID + 100, EVENT_ID + 100,
                USER_ID, "RC0000000000000000000000000B01");

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Callable<IRechargeCreditTx.CreditResult>> jobs = List.of(
                () -> { barrier.await(10, TimeUnit.SECONDS); return issueTx.issue(EVENT_ID, PROCESSING); },
                () -> { barrier.await(10, TimeUnit.SECONDS); return creditTx.credit(EVENT_ID + 100, PROCESSING); });
        // R3：invokeAll 自带 30s 上限（死锁在这里表现为任务被取消，而不是无限等待）
        List<Future<IRechargeCreditTx.CreditResult>> futures = pool.invokeAll(jobs, 30, TimeUnit.SECONDS);
        assertFalse(futures.get(0).isCancelled(), "首购事务 30s 内完成（无死锁）");
        assertFalse(futures.get(1).isCancelled(), "转正事务 30s 内完成（无死锁）");
        IRechargeCreditTx.CreditResult issueResult = futures.get(0).get();
        IRechargeCreditTx.CreditResult promoteResult = futures.get(1).get();
        pool.shutdown();

        long credited = List.of(issueResult, promoteResult).stream()
                .filter(r -> r.outcome() == IRechargeCreditTx.Outcome.CREDITED).count();
        long unrecoverable = List.of(issueResult, promoteResult).stream()
                .filter(r -> r.outcome() == IRechargeCreditTx.Outcome.UNRECOVERABLE).count();
        assertEquals(1L, credited, "恰一方入账/发卡成功");
        assertEquals(1L, unrecoverable, "另一方 UNRECOVERABLE");

        // R3：按实际输家执行失败落痕（生产中由 worker 在 unrecoverable 后调用同一事务），
        // 把「订单→6 / 事实→5待对账」从口头承诺变成本用例的真库断言
        boolean promoteLost = promoteResult.outcome() == IRechargeCreditTx.Outcome.UNRECOVERABLE;
        if (promoteLost) {
            creditFailureTx.record(EVENT_ID + 100, false, promoteResult.reason(), PROCESSING);
        } else {
            issueTx.recordFailure(EVENT_ID, false, issueResult.reason(), PROCESSING);
        }

        List<Integer> orderStatuses = jdbc.queryForList(
                "SELECT ORDER_STATUS FROM ws_order ORDER BY ORDER_STATUS", Integer.class);
        assertEquals(List.of(4, 6), orderStatuses, "赢家订单已完成(4)、输家订单异常待人工(6)");
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM ws_payment WHERE PAY_STATUS = 2",
                Long.class), "两笔支付成功事实都保持");
        long winnerEventId = promoteLost ? EVENT_ID : EVENT_ID + 100;
        long loserEventId = promoteLost ? EVENT_ID + 100 : EVENT_ID;
        assertEquals(3, jdbc.queryForObject(
                "SELECT PROCESSING_STATUS FROM ws_payment_event WHERE ID = " + winnerEventId, Integer.class),
                "赢家事件已处理(3)");
        assertEquals(5, jdbc.queryForObject(
                "SELECT PROCESSING_STATUS FROM ws_payment_event WHERE ID = " + loserEventId, Integer.class),
                "输家事件待对账(5)");
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM ws_card WHERE "
                + com.jbk.serve.service.mini.card.CardEligibility.SQL_NOT_GIFT, Long.class),
                "最终恰一张付费卡（发卡赢=新卡 / 转正赢=赠卡转永久）");
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM ws_wallet_flow WHERE FLOW_TYPE = 1",
                Long.class), "只有赢家一条充值流水");
        long winnerOrderId = promoteLost ? ORDER_ID : ORDER_ID + 100;
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM ws_wallet_flow WHERE FLOW_TYPE = 1 "
                + "AND ORDER_ID = " + winnerOrderId, Long.class), "充值流水只关联赢家订单");
        if (promoteLost) {
            assertEquals(0L, jdbc.queryForObject("SELECT BALANCE_AMOUNT FROM ws_card WHERE ID = " + giftCardId,
                    Long.class), "输掉的转正单零卡权益变动");
            assertEquals(3, jdbc.queryForObject("SELECT CARD_STATUS FROM ws_card WHERE ID = " + giftCardId,
                    Integer.class), "赠卡保持过期态未被转正");
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM ws_card_entitlement_batch "
                    + "WHERE CARD_ID = " + giftCardId, Long.class), "输家零批次写入");
        } else {
            assertEquals(0L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ws_card WHERE ISSUE_ORDER_ID = " + ORDER_ID, Long.class),
                    "输掉的首购单零发卡");
        }
    }

    /** 审计 R2 P1-3：两个不同用户并发转正/发卡——用户行锁按行隔离，无关用户不互阻不死锁。 */
    @Test
    void concurrentDifferentUsersDoNotBlockEachOther() throws Exception {
        long userB = USER_ID + 1;
        jdbc.update("INSERT INTO ws_user(ID) VALUES(?)", userB);
        seedPromoteScene(600L, ORDER_ID + 200, PAYMENT_ID + 200, EVENT_ID + 200,
                userB, "RC0000000000000000000000000C01");

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Callable<IRechargeCreditTx.CreditResult>> jobs = List.of(
                () -> { barrier.await(10, TimeUnit.SECONDS); return issueTx.issue(EVENT_ID, PROCESSING); },
                () -> { barrier.await(10, TimeUnit.SECONDS); return creditTx.credit(EVENT_ID + 200, PROCESSING); });
        List<Future<IRechargeCreditTx.CreditResult>> futures = pool.invokeAll(jobs, 30, TimeUnit.SECONDS);
        assertFalse(futures.get(0).isCancelled(), "用户A 事务 30s 内完成（无互阻）");
        assertFalse(futures.get(1).isCancelled(), "用户B 事务 30s 内完成（无互阻）");
        IRechargeCreditTx.CreditResult a = futures.get(0).get();
        IRechargeCreditTx.CreditResult b = futures.get(1).get();
        pool.shutdown();

        assertEquals(IRechargeCreditTx.Outcome.CREDITED, a.outcome(), "用户A 首购成功，不被用户B 阻塞");
        assertEquals(IRechargeCreditTx.Outcome.CREDITED, b.outcome(), "用户B 转正成功，不被用户A 阻塞");
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM ws_card WHERE "
                + com.jbk.serve.service.mini.card.CardEligibility.SQL_NOT_GIFT, Long.class),
                "两个用户各得一张付费卡");
    }

    private void addClaimedEvent(long id, String paid) {
        jdbc.update("INSERT INTO ws_payment_event(ID,ORDER_NO,ORDER_ID,PAYMENT_ID,PAY_SOURCE,FACT_CHANNEL,"
                        + "PROVIDER_EVENT_KEY,TRADE_STATE,TRANSACTION_ID,PAY_AMOUNT,CURRENCY,PAY_SUCCESS_TIME,"
                        + "PROCESSING_STATUS,DATA_STATUS) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,2,0)",
                id, ORDER_NO, ORDER_ID, PAYMENT_ID, 2, 3, "evt-" + id, "SUCCESS", "SIMTX-1",
                PAY_AMOUNT, "CNY", paid);
    }

    /** 用户名下既有卡（非本单发行）。 */
    private void insertForeignCard(String cardNo, int cardStatus, Long issueOrderId) {
        jdbc.update("INSERT INTO ws_card(DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,"
                        + "CARD_NO,CARD_TYPE,USER_ID,BALANCE_AMOUNT,BALANCE_ML,SCOPE_JSON,EXPIRE_TIME,"
                        + "CARD_STATUS,ISSUE_ORDER_ID) VALUES(0,?,?,?,?,?,1,?,0,0,?,NULL,?,?)",
                USER_ID, CREATE_TIME, USER_ID, CREATE_TIME, cardNo, USER_ID, SCOPE, cardStatus, issueOrderId);
    }

    /** 独立复算卡号（实现路径与被测类不同：String.format 而非 Character.forDigit）。 */
    private static String expectedCardNo(String orderNo) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(orderNo.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return "VC" + hex.substring(0, 14).toUpperCase();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String independentPlusDays(String time14, int days) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
        return LocalDateTime.parse(time14, fmt).plusDays(days).format(fmt);
    }

    private int cardCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ws_card", Integer.class);
    }

    private <T> T cardValue(String column, Class<T> type) {
        return jdbc.queryForObject("SELECT " + column + " FROM ws_card ORDER BY ID LIMIT 1", type);
    }

    private int flowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ws_wallet_flow", Integer.class);
    }

    private int orderStatus() {
        return jdbc.queryForObject("SELECT ORDER_STATUS FROM ws_order WHERE ID = ?", Integer.class, ORDER_ID);
    }

    private int eventStatus(long id) {
        return jdbc.queryForObject("SELECT PROCESSING_STATUS FROM ws_payment_event WHERE ID=?",
                Integer.class, id);
    }

    /**
     * E2E-04 包D：首次购卡入账必须<b>同事务</b>建出权益批次，且批次剩余恰等于发放量。
     *
     * <p>没有这条正向断言，「接了但没生效」是看不出来的——把 createOnCredit 那一行删掉，
     * 既有用例一条都不会红（它们只看卡余额与流水）。而批次缺失的后果是这笔充值
     * 永远无法退款：折算没有基准。</p>
     */
    @Test
    void firstPurchaseCreatesEntitlementBatchMatchingGrantedAmounts() {
        issueTx.issue(EVENT_ID, PROCESSING);
        java.util.Map<String, Object> batch = jdbc.queryForMap("SELECT * FROM ws_card_entitlement_batch");
        assertEquals(1, ((Number) batch.get("SOURCE_TYPE")).intValue(), "首次购卡来源");
        assertEquals(1, ((Number) batch.get("BATCH_STATUS")).intValue(), "新批次可用");
        assertEquals(PAYMENT_ID, ((Number) batch.get("PAYMENT_ID")).longValue(),
                "首次购卡权益批次必须锚定本次原支付单");
        assertEquals(((Number) batch.get("GRANT_AMOUNT_FEN")).longValue(),
                ((Number) batch.get("REMAIN_AMOUNT_FEN")).longValue(), "新批次剩余必须等于发放额");
        assertEquals(((Number) batch.get("GRANT_WATER_ML")).longValue(),
                ((Number) batch.get("REMAIN_WATER_ML")).longValue(), "水量同理");
        Long cardFen = jdbc.queryForObject("SELECT BALANCE_AMOUNT FROM ws_card", Long.class);
        Long cardMl = jdbc.queryForObject("SELECT BALANCE_ML FROM ws_card", Long.class);
        assertEquals(cardFen, ((Number) batch.get("REMAIN_AMOUNT_FEN")).longValue(),
                "批次剩余必须与卡聚合一致——这是消费分摊成立的前提");
        assertEquals(cardMl, ((Number) batch.get("REMAIN_WATER_ML")).longValue());
    }

    /** 同一笔充值重放不得建出第二个批次（uk_batch_order）。 */
    @Test
    void replayDoesNotCreateSecondBatch() {
        issueTx.issue(EVENT_ID, PROCESSING);
        try {
            issueTx.issue(EVENT_ID, PROCESSING);
        } catch (RuntimeException ignored) {
            // 重放被幂等闸拒绝是预期结果，本用例只关心批次没有变成两条
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_card_entitlement_batch", Integer.class));
    }
}
