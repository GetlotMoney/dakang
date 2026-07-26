package com.jbk.serve.service.mini.recharge.impl;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.trade.RechargeCreditMapper;
import com.jbk.serve.service.mini.recharge.IRechargeCreditTx;
import com.jbk.serve.service.mini.recharge.IRechargeCreditFailureTx;
import com.jbk.serve.service.mini.card.WaterCardScope;
import com.jbk.serve.service.mini.recharge.RechargeSnapshot;
import com.jbk.serve.service.mini.recharge.RechargePayExpire;
import com.jbk.tool.data.product.po.WsPackage;
import com.jbk.tool.data.trade.po.WsOrder;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 事务 B 的<b>真实 MySQL + 真实 Spring 事务</b>集成测试（L2 契约 v2 §6.4，决策 L2-D4）。
 *
 * <p>这里测的三件事都无法用单测证明：</p>
 * <ol>
 *   <li><b>有限卡续期</b> {@code newExpireTime = max(currentExpireTime, paySuccessTime) + expireDays}
 *       必须与余额、水量、卡状态在<b>同一条 UPDATE</b> 里落地——拆开就会出现「加了钱但有效期没续上」。</li>
 *   <li><b>入账不重复</b>靠 InnoDB 唯一键 + Spring 回滚共同保证，Mock 掉任一边测的就不是这条性质。</li>
 *   <li><b>CAS 前态</b>在并发下真的挡得住丢失更新。</li>
 * </ol>
 *
 * <p>无 Docker 环境自动跳过。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = RechargeCreditTxDbTest.Ctx.class)
class RechargeCreditTxDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_credit_it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static final long CARD_ID = 100L;
    private static final long USER_ID = 9L;
    private static final long ORDER_ID = 777L;
    private static final long PAYMENT_ID = 888L;
    private static final long EVENT_ID = 999L;
    private static final String ORDER_NO = "RC0000000000000000000000000001";
    private static final long PAY_AMOUNT = 10000L;
    private static final long WATER_ML = 500000L;
    private static final int EXPIRE_DAYS = 365;
    private static final String SCOPE = "{\"scopeType\":\"all\"}";

    /** 卡当前有效期：2027-07-10 12:00:00。 */
    private static final String CARD_EXPIRE = "20270710120000";
    /** 支付成功时间：2026-07-22 19:43:14（早于卡有效期）。 */
    private static final String PAID_EARLY = "20260722194314";
    /** 处理时间与支付时间同日。 */
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
            return new SqlSessionTemplate(factory.getObject());
        }

        @Bean
        MapperFactoryBean<RechargeCreditMapper> creditMapper(SqlSessionTemplate template) {
            MapperFactoryBean<RechargeCreditMapper> bean = new MapperFactoryBean<>(RechargeCreditMapper.class);
            bean.setSqlSessionTemplate(template);
            return bean;
        }

        @Bean
        RechargeLockedState lockedState(RechargeCreditMapper mapper) {
            return new RechargeLockedState(mapper);
        }

        @Bean
        RechargeLedgerVerifier ledgerVerifier() {
            return new RechargeLedgerVerifier();
        }

        @Bean
        RechargeCreditTxImpl creditTx(RechargeCreditMapper mapper, RechargeLockedState locked,
                                      RechargeLedgerVerifier ledger) {
            return new RechargeCreditTxImpl(mapper, locked, ledger);
        }

        @Bean
        RechargeCreditFailureTxImpl failureTx(RechargeCreditMapper mapper, RechargeLockedState locked,
                                              RechargeLedgerVerifier ledger) {
            return new RechargeCreditFailureTxImpl(mapper, locked, ledger);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    /**
     * 按<b>接口</b>注入：Spring 为 @Transactional 生成的是 JDK 接口代理，按实现类注入会失败。
     * 这条注入本身即是「事务增强确实织入了」的证据——注解被删掉时这里会退化成裸实现类，
     * 下面所有回滚断言随即失效。
     */
    @Autowired
    private IRechargeCreditTx actualCreditTx;
    @Autowired
    private IRechargeCreditFailureTx failureTx;
    @Autowired
    private RechargeCreditMapper creditMapper;
    private TestCreditHarness creditTx;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_card (
                  ID BIGINT PRIMARY KEY, CARD_NO VARCHAR(50), USER_ID BIGINT,
                  BALANCE_AMOUNT BIGINT NOT NULL DEFAULT 0, BALANCE_ML BIGINT NOT NULL DEFAULT 0,
                  PACKAGE_ID BIGINT NULL, PACKAGE_SNAP MEDIUMTEXT NULL, SCOPE_JSON VARCHAR(2000) NULL,
                  EXPIRE_TIME VARCHAR(20) NULL, CARD_STATUS TINYINT, DATA_STATUS TINYINT DEFAULT 0,
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20)
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
        // uk_wallet_flow_biz_key 是本测试的主角：它，而不是应用层判断，才是防重复入账的那道闸
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
        jdbc.execute("DELETE FROM ws_payment_event");
        jdbc.execute("DELETE FROM ws_payment");
        jdbc.execute("DELETE FROM ws_card");
        jdbc.execute("DELETE FROM ws_order");
        seedCard(CARD_EXPIRE, 1);
        jdbc.update("INSERT INTO ws_order(ID,ORDER_NO,ORDER_TYPE,USER_ID,CARD_ID,PACKAGE_ID,PACKAGE_SNAP,"
                        + "ORDER_AMOUNT,PAY_WAY,ORDER_STATUS,DATA_STATUS,CREATE_TIME) VALUES(?,?,?,?,?,?,?,?,?,?,0,?)",
                ORDER_ID, ORDER_NO, 2, USER_ID, CARD_ID, 3L, "", PAY_AMOUNT, 1, 2, "20260722194200");
        jdbc.update("INSERT INTO ws_payment(ID,ORDER_ID,ORDER_NO,TRANSACTION_ID,PAY_AMOUNT,PAY_STATUS,PAY_SOURCE,"
                        + "CURRENCY,PAY_EXPIRE_TIME,PAY_SUCCESS_TIME,DATA_STATUS,CREATE_TIME) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,0,?)",
                PAYMENT_ID, ORDER_ID, ORDER_NO, "SIMTX-1", PAY_AMOUNT, 2, 2, "CNY",
                "20260722201200", PAID_EARLY, "20260722194200");
        jdbc.update("INSERT INTO ws_payment_event(ID,ORDER_NO,ORDER_ID,PAYMENT_ID,PAY_SOURCE,FACT_CHANNEL,"
                        + "PROVIDER_EVENT_KEY,TRADE_STATE,TRANSACTION_ID,PAY_AMOUNT,CURRENCY,PAY_SUCCESS_TIME,"
                        + "PROCESSING_STATUS,DATA_STATUS) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,2,0)",
                EVENT_ID, ORDER_NO, ORDER_ID, PAYMENT_ID, 2, 3, "evt-1", "SUCCESS", "SIMTX-1",
                PAY_AMOUNT, "CNY", PAID_EARLY);
        creditTx = new TestCreditHarness(actualCreditTx);
    }

    private void seedCard(String expireTime, int status) {
        jdbc.update("DELETE FROM ws_card");
        jdbc.update("INSERT INTO ws_card(ID,CARD_NO,USER_ID,BALANCE_AMOUNT,BALANCE_ML,SCOPE_JSON,"
                + "EXPIRE_TIME,CARD_STATUS,DATA_STATUS) VALUES(?,?,?,?,?,?,?,?,0)",
                CARD_ID, "VC001", USER_ID, 5500L, 470120L, SCOPE, expireTime, status);
    }

    // ================= 1/2：续期基准取 max(当前有效期, 支付成功时间) =================

    /** 卡还没过期：应在**当前有效期**上叠加，用户不吃亏。20270710+365 天 = 20280710。 */
    @Test
    void extendsFromCurrentExpiryWhenCardNotYetExpired() {
        assertEquals(IRechargeCreditTx.Outcome.CREDITED,
                creditTx.credit(order(), snap(EXPIRE_DAYS), PAID_EARLY, PROCESSING).outcome());

        // 2028 是闰年，区间含 2/29，故 20270710 + 365 天落在 07-09 而非 07-10（已用独立实现复算）
        assertEquals("20280709120000", expire(), "应基于当前有效期续期，而不是从支付时间重算");
        assertEquals(970120L, ml());
        assertEquals(5500L, amount(), "水量套餐不加余额");
        assertEquals(1, cardStatus());
        assertEquals(4, orderStatus());
    }

    /** 卡已过期（有效期早于支付时间）：应从**支付成功时刻**起算，不把权益续进一段已经过去的时间。 */
    @Test
    void extendsFromPaySuccessTimeWhenCardAlreadyExpired() {
        seedCard("20260101000000", 1);
        assertEquals(IRechargeCreditTx.Outcome.CREDITED,
                creditTx.credit(order(), snap(EXPIRE_DAYS), PAID_EARLY, PROCESSING).outcome());
        // 20260722194314 + 365 天 = 20270722194314
        assertEquals("20270722194314", expire(), "已过期卡必须从支付时间起算");
    }

    /**
     * 续期基准必须是**支付成功时间**而不是处理时间。
     * 异步 Worker 晚跑一个月时，用处理时间起算会凭空多送用户一个月有效期，且重放结果不稳定。
     */
    @Test
    void neverUsesProcessingTimeAsExtensionBase() {
        seedCard("20260101000000", 1);
        String lateProcessing = "20260822000000"; // 比支付时间晚一个月
        creditTx.credit(order(), snap(EXPIRE_DAYS), PAID_EARLY, lateProcessing);
        assertEquals("20270722194314", expire(), "基准必须是 paySuccessTime，不是处理时间");
    }

    // ================= 3：自然过期的状态 3 可恢复 =================

    @Test
    void restoresNaturallyExpiredCardToNormalAndExtends() {
        seedCard("20260101000000", 3);
        assertEquals(IRechargeCreditTx.Outcome.CREDITED,
                creditTx.credit(order(), snap(EXPIRE_DAYS), PAID_EARLY, PROCESSING).outcome());
        assertEquals(1, cardStatus(), "自然过期的卡入账后应恢复正常");
        assertEquals("20270722194314", expire());
    }

    /** 状态 3 但有效期为空或仍在未来：数据自相矛盾，fail-closed，不得当作自然过期放行。 */
    @Test
    void refusesStatus3WhenExpiryNotConsistent() {
        seedCard(CARD_EXPIRE, 3); // 有效期在未来却标成已过期
        IRechargeCreditTx.CreditResult r = creditTx.credit(order(), snap(EXPIRE_DAYS), PAID_EARLY, PROCESSING);
        assertEquals(IRechargeCreditTx.Outcome.UNRECOVERABLE, r.outcome());
        assertNoSideEffect(CARD_EXPIRE, 3);
    }

    // ================= 4：算得的新有效期已过期 → 零权益 + 不可恢复 =================

    /**
     * Worker 延迟到新有效期都已经过去：绝不写一段当场作废的权益，也不顺手把卡恢复成正常态。
     * 用户已经付了钱，静默发放一份作废权益比不发放更糟。
     */
    @Test
    void refusesWhenComputedExpiryAlreadyPassed() {
        seedCard("20260101000000", 3);
        // 支付 20260101，套餐 1 天 → 新有效期 20260102；处理时间已到 6 月
        IRechargeCreditTx.CreditResult r =
                creditTx.credit(order(), snap(1), "20260101000000", "20260601000000");
        assertEquals(IRechargeCreditTx.Outcome.UNRECOVERABLE, r.outcome());
        assertNoSideEffect("20260101000000", 3);
    }

    // ================= 5：永久卡保持 NULL =================

    @Test
    void permanentCardKeepsNullExpiry() {
        seedCard(null, 1);
        assertEquals(IRechargeCreditTx.Outcome.CREDITED,
                creditTx.credit(order(), snap(null), PAID_EARLY, PROCESSING).outcome());
        assertNull(expire(), "永久卡入账后 EXPIRE_TIME 必须仍为 NULL");
        assertEquals(1, flowCount());
    }

    /** 有限卡买永久套餐（或反之）：类型在支付期间被改动，拒绝且零副作用。 */
    @Test
    void refusesExpiryKindMismatch() {
        IRechargeCreditTx.CreditResult r = creditTx.credit(order(), snap(null), PAID_EARLY, PROCESSING);
        assertEquals(IRechargeCreditTx.Outcome.UNRECOVERABLE, r.outcome());
        assertNoSideEffect(CARD_EXPIRE, 1);
    }

    // ================= 6：冻结可恢复；注销/换主/范围变化不可恢复 =================

    /**
     * 冻结是<b>可恢复</b>的：冻结限制的是用卡，不是没收已付款项。
     * 订单必须保持 2 等待重试，绝不能推进到 6——那会把可自动恢复的单堆进人工队列。
     */
    @Test
    void frozenCardIsRecoverableAndKeepsOrderPending() {
        seedCard(CARD_EXPIRE, 2);
        IRechargeCreditTx.CreditResult r = creditTx.credit(order(), snap(EXPIRE_DAYS), PAID_EARLY, PROCESSING);
        assertEquals(IRechargeCreditTx.Outcome.RETRY_CARD_FROZEN, r.outcome());
        assertNoSideEffect(CARD_EXPIRE, 2);
        assertEquals(2, orderStatus(), "冻结时订单必须保持 2 已支付待入账");
    }

    @Test
    void cancelledCardIsUnrecoverable() {
        seedCard(CARD_EXPIRE, 4);
        assertEquals(IRechargeCreditTx.Outcome.UNRECOVERABLE,
                creditTx.credit(order(), snap(EXPIRE_DAYS), PAID_EARLY, PROCESSING).outcome());
        assertNoSideEffect(CARD_EXPIRE, 4);
    }

    /** 换主：钱是 A 付的，权益绝不能落到 B 名下。 */
    @Test
    void ownerChangeIsUnrecoverable() {
        jdbc.update("UPDATE ws_card SET USER_ID = ? WHERE ID = ?", 999L, CARD_ID);
        assertEquals(IRechargeCreditTx.Outcome.UNRECOVERABLE,
                creditTx.credit(order(), snap(EXPIRE_DAYS), PAID_EARLY, PROCESSING).outcome());
        assertEquals(0, flowCount());
    }

    /** 范围变化：不得把权益落进一个用户下单时没同意的授权范围。 */
    @Test
    void scopeChangeIsUnrecoverable() {
        jdbc.update("UPDATE ws_card SET SCOPE_JSON = ? WHERE ID = ?",
                "{\"scopeType\":\"specified\",\"stationIds\":[7]}", CARD_ID);
        assertEquals(IRechargeCreditTx.Outcome.UNRECOVERABLE,
                creditTx.credit(order(), snap(EXPIRE_DAYS), PAID_EARLY, PROCESSING).outcome());
        assertNoSideEffect(CARD_EXPIRE, 1);
    }

    @Test
    void deletedCardIsUnrecoverable() {
        jdbc.update("UPDATE ws_card SET DATA_STATUS = 1 WHERE ID = ?", CARD_ID);
        assertEquals(IRechargeCreditTx.Outcome.UNRECOVERABLE,
                creditTx.credit(order(), snap(EXPIRE_DAYS), PAID_EARLY, PROCESSING).outcome());
        assertEquals(0, flowCount());
    }

    // ================= 7：重复处理，有效期只增加一次 =================

    /**
     * 第二次必须撞唯一键并整事务回滚。这是最贵的一条断言：
     * 它同时验证了唯一键存在、事务确实回滚、<b>以及有效期不会被续第二次</b>。
     */
    @Test
    void replayNeitherCreditsNorExtendsTwice() {
        creditTx.credit(order(), snap(EXPIRE_DAYS), PAID_EARLY, PROCESSING);
        String afterFirst = expire();
        long mlAfterFirst = ml();

        assertEquals(IRechargeCreditTx.Outcome.ALREADY,
                creditTx.credit(order(), snap(EXPIRE_DAYS), PAID_EARLY, PROCESSING).outcome());

        assertEquals(afterFirst, expire(), "有效期不得被续第二次");
        assertEquals(mlAfterFirst, ml(), "水量不得被加第二次");
        assertEquals(1, flowCount());
    }

    // ================= 8：20 线程并发 =================

    @Test
    void concurrentCreditsApplyExactlyOnce() throws Exception {
        int threads = 20;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger ok = new AtomicInteger();

        List<Callable<Void>> jobs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            jobs.add(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                try {
                    if (creditTx.credit(order(), snap(EXPIRE_DAYS), PAID_EARLY, PROCESSING)
                            .outcome() == IRechargeCreditTx.Outcome.CREDITED) {
                        ok.incrementAndGet();
                    }
                } catch (RuntimeException ignored) {
                    // 撞唯一键/CAS 前态不符都算未入账，下面用总账断言兜底
                }
                return null;
            });
        }
        for (Future<Void> f : pool.invokeAll(jobs)) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(1, ok.get(), "恰好一个线程入账成功");
        assertEquals(1, flowCount());
        assertEquals(970120L, ml(), "水量只能被加一次");
        assertEquals(5500L, amount());
        assertEquals("20280709120000", expire(), "有效期只能被续一次");
    }

    // ================= 9：CAS 前态不匹配整体回滚 =================

    /**
     * 模拟并发：锁卡读到旧有效期后、UPDATE 之前被别人改掉。
     * CAS 的 {@code EXPIRE_TIME <=> 旧值} 会让影响行数为 0，整事务回滚。
     * 这条守卫没了就是经典的丢失更新——两笔充值只有一笔的有效期生效。
     */
    @Test
    void staleExpiryCasBlocksTheWrite() {
        // 直接调 mapper 用一个**过期的**前态值发起 UPDATE，模拟 CAS 失配
        int rows = jdbc.update(
                "UPDATE ws_card SET BALANCE_ML = BALANCE_ML + ?, EXPIRE_TIME = ? "
                        + "WHERE ID = ? AND USER_ID = ? AND DATA_STATUS = 0 AND CARD_STATUS IN (1,3) "
                        + "AND BALANCE_AMOUNT = ? AND BALANCE_ML = ? AND EXPIRE_TIME <=> ?",
                WATER_ML, "20280709120000", CARD_ID, USER_ID, 5500L, 470120L, "20991231000000");
        assertEquals(0, rows, "旧有效期不匹配时影响行数必须为 0");
        assertEquals(470120L, ml(), "CAS 失配不得写入任何变更");
    }

    // ================= 10：流水 AFTER 与卡终值一致 =================

    @Test
    void flowAfterMatchesCardFinalValues() {
        creditTx.credit(order(), snap(EXPIRE_DAYS), PAID_EARLY, PROCESSING);
        assertEquals(ml(), jdbc.queryForObject("SELECT ML_AFTER FROM ws_wallet_flow", Long.class),
                "流水 ML_AFTER 必须等于卡终值，否则对账时看到的是加之前的余额");
        assertEquals(amount(), jdbc.queryForObject("SELECT AMOUNT_AFTER FROM ws_wallet_flow", Long.class));
        assertEquals(WATER_ML, jdbc.queryForObject("SELECT ML_CHANGE FROM ws_wallet_flow", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT AMOUNT_CHANGE FROM ws_wallet_flow", Long.class));
        assertEquals("RECHARGE:" + ORDER_NO,
                jdbc.queryForObject("SELECT BIZ_IDEMPOTENCY_KEY FROM ws_wallet_flow", String.class));
    }

    /** 入账成功后必须写最近 PACKAGE_ID/PACKAGE_SNAP（契约 §6.4 步骤 6），供卡详情展示最近一次充值。 */
    @Test
    void writesLatestPackageOnCard() {
        creditTx.credit(order(), snap(EXPIRE_DAYS), PAID_EARLY, PROCESSING);
        assertEquals(3L, jdbc.queryForObject("SELECT PACKAGE_ID FROM ws_card WHERE ID = ?", Long.class, CARD_ID));
        assertEquals(SCOPE, jdbc.queryForObject("SELECT SCOPE_JSON FROM ws_card WHERE ID = ?", String.class, CARD_ID),
                "入账不得覆盖既有 SCOPE_JSON");
    }

    // ================= 五表编排：事件组、租约、失败落痕、污染流水 =================

    @Test
    void convergesEveryMatchingSuccessEventInTheCreditTransaction() {
        snap(EXPIRE_DAYS);
        creditTx.credit(order(), RechargeSnapshot.parse(lastSnapshotJson), PAID_EARLY, PROCESSING);
        addEvent(1000L, 1);
        jdbc.update("UPDATE ws_order SET ORDER_STATUS=2, FINISH_TIME=NULL WHERE ID=?", ORDER_ID);
        jdbc.update("DELETE FROM ws_wallet_flow");
        seedCard(CARD_EXPIRE, 1);
        jdbc.update("UPDATE ws_payment_event SET PROCESSING_STATUS=2 WHERE ID=?", EVENT_ID);

        actualCreditTx.credit(EVENT_ID, PROCESSING);
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_payment_event WHERE PROCESSING_STATUS=3", Integer.class));
    }

    @Test
    void completedOrderRejectsDeletedOrCorruptIdempotencyFlow() {
        snap(EXPIRE_DAYS);
        creditTx.credit(order(), RechargeSnapshot.parse(lastSnapshotJson), PAID_EARLY, PROCESSING);
        jdbc.update("UPDATE ws_wallet_flow SET DATA_STATUS=1");
        addEvent(1000L, 2);
        long before = ml();

        assertThrows(JbkException.class, () -> actualCreditTx.credit(1000L, PROCESSING));
        assertEquals(before, ml());
        assertEquals(4, orderStatus(), "污染流水不得覆盖已完成订单");
    }

    /**
     * 历史污染可能已经给同一订单写过 FLOW_TYPE=1，却把幂等键写错。只查正确 bizKey 会把它当未入账，
     * 再发一次权益；必须同时按 ORDER_ID+FLOW_TYPE 跨全部 DATA_STATUS 阻断。
     */
    @Test
    void pollutedRechargeFlowWithWrongBizKeyCannotCreditOrderAgain() {
        RechargeSnapshot.Parsed parsed = snap(EXPIRE_DAYS);
        jdbc.update("UPDATE ws_card SET BALANCE_ML=?, EXPIRE_TIME=? WHERE ID=?",
                970120L, "20280709120000", CARD_ID);
        insertRechargeFlow(66L, 0L, WATER_ML, 5500L, 970120L,
                ORDER_ID, "BROKEN:" + ORDER_NO, 0);

        assertThrows(JbkException.class,
                () -> creditTx.credit(order(), parsed, PAID_EARLY, PROCESSING));

        assertEquals(970120L, ml(), "污染订单不得再次增加水量");
        assertEquals("20280709120000", expire(), "污染订单不得再次顺延有效期");
        assertEquals(1, flowCount(), "只能保留原污染流水，不得追加第二笔充值流水");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_wallet_flow WHERE BIZ_IDEMPOTENCY_KEY=?",
                Integer.class, "RECHARGE:" + ORDER_NO));
        assertEquals(2, orderStatus(), "无法确认账本时订单不得伪装成已完成");
    }

    @Test
    void frozenEventCanBeReclaimedAfterRetryTimeAndThenCredited() {
        seedCard(CARD_EXPIRE, 2);
        snap(EXPIRE_DAYS);
        IRechargeCreditTx.CreditResult result = creditTx.credit(
                order(), RechargeSnapshot.parse(lastSnapshotJson), PAID_EARLY, PROCESSING);
        assertEquals(IRechargeCreditTx.Outcome.RETRY_CARD_FROZEN, result.outcome());
        failureTx.record(EVENT_ID, true, result.reason(), PROCESSING);
        assertEquals(4, eventStatus(EVENT_ID));
        assertEquals(0, creditMapper.claimEvent(EVENT_ID, PROCESSING, "20260722195000"));

        jdbc.update("UPDATE ws_card SET CARD_STATUS=1 WHERE ID=?", CARD_ID);
        assertEquals(1, creditMapper.claimEvent(EVENT_ID, "20260722194500", "20260722195000"));
        assertEquals(IRechargeCreditTx.Outcome.CREDITED,
                actualCreditTx.credit(EVENT_ID, "20260722194500").outcome());
    }

    @Test
    void expiredProcessingLeaseCanBeReclaimedButActiveLeaseCannot() {
        jdbc.update("UPDATE ws_payment_event SET PROCESSING_STATUS=2, LEASE_UNTIL='20260722195000' WHERE ID=?",
                EVENT_ID);
        assertEquals(0, creditMapper.claimEvent(EVENT_ID, "20260722194500", "20260722195500"));
        assertEquals(1, creditMapper.claimEvent(EVENT_ID, "20260722195100", "20260722195600"));
        assertEquals(2, eventStatus(EVENT_ID));
    }

    @Test
    void staleFailureWriterCannotDowngradeOrderCompletedByAnotherWorker() {
        snap(EXPIRE_DAYS);
        addEvent(1000L, 2); // Worker A 已认领
        creditTx.credit(order(), RechargeSnapshot.parse(lastSnapshotJson), PAID_EARLY, PROCESSING); // Worker B 完成

        failureTx.record(1000L, false, "Worker A 的旧失败结果", "20260722194330");
        assertEquals(4, orderStatus());
        assertEquals(3, eventStatus(EVENT_ID));
        assertEquals(3, eventStatus(1000L));
        assertEquals(1, flowCount());
    }

    @Test
    void completedOrderDuplicateEventIsReadOnlyAndConverges() {
        snap(EXPIRE_DAYS);
        creditTx.credit(order(), RechargeSnapshot.parse(lastSnapshotJson), PAID_EARLY, PROCESSING);
        addEvent(1000L, 2);
        long beforeMl = ml();
        String beforeExpire = expire();

        assertEquals(IRechargeCreditTx.Outcome.ALREADY,
                actualCreditTx.credit(1000L, "20260722194330").outcome());
        assertEquals(beforeMl, ml());
        assertEquals(beforeExpire, expire());
        assertEquals(1, flowCount());
        assertEquals(3, eventStatus(1000L));
    }

    @Test
    void completedFiniteOrderMissingExpiryGoesToReconciliation() {
        snap(EXPIRE_DAYS);
        creditTx.credit(order(), RechargeSnapshot.parse(lastSnapshotJson), PAID_EARLY, PROCESSING);
        jdbc.update("UPDATE ws_card SET EXPIRE_TIME=NULL WHERE ID=?", CARD_ID);
        addEvent(1000L, 2);
        long beforeMl = ml();

        assertThrows(JbkException.class,
                () -> actualCreditTx.credit(1000L, "20260722194330"));
        failureTx.record(1000L, false, "完成态有效期核验失败", "20260722194330");

        assertEquals(beforeMl, ml(), "只读幂等核验不得再次写权益");
        assertNull(expire(), "核验失败不得擅自补写有效期");
        assertEquals(4, orderStatus(), "完成订单不得被失败落痕降级");
        assertEquals(5, eventStatus(1000L), "有效期权益缺失必须进入人工对账");
    }

    @Test
    void completedFiniteOrderAllowsExpiryAboveItsProvableMinimum() {
        snap(EXPIRE_DAYS);
        creditTx.credit(order(), RechargeSnapshot.parse(lastSnapshotJson), PAID_EARLY, PROCESSING);
        jdbc.update("UPDATE ws_card SET EXPIRE_TIME='20991231235959' WHERE ID=?", CARD_ID);
        addEvent(1000L, 2);

        assertEquals(IRechargeCreditTx.Outcome.ALREADY,
                actualCreditTx.credit(1000L, "20260722194330").outcome(),
                "后续充值可以继续延长聚合有效期，完成态只能校验本单最低下限");
        assertEquals("20991231235959", expire());
        assertEquals(3, eventStatus(1000L));
        assertEquals(1, flowCount(), "只读幂等核验不得新增流水");
    }

    @Test
    void completedPermanentOrderWithExpiryGoesToReconciliation() {
        seedCard(null, 1);
        snap(null);
        creditTx.credit(order(), RechargeSnapshot.parse(lastSnapshotJson), PAID_EARLY, PROCESSING);
        jdbc.update("UPDATE ws_card SET EXPIRE_TIME='20991231235959' WHERE ID=?", CARD_ID);
        addEvent(1000L, 2);

        assertThrows(JbkException.class,
                () -> actualCreditTx.credit(1000L, "20260722194330"));
        failureTx.record(1000L, false, "完成态永久权益核验失败", "20260722194330");

        assertEquals(970120L, ml(), "永久套餐完成态核验不得再次入账");
        assertEquals("20991231235959", expire(), "核验失败不得改写污染有效期");
        assertEquals(4, orderStatus());
        assertEquals(5, eventStatus(1000L), "永久套餐出现有效期必须进入人工对账");
    }

    @Test
    void legacyPrefixBreakDoesNotRejectNewCreditOrCompletedReplay() {
        seedLegacyBrokenPrefix();
        snap(EXPIRE_DAYS);

        assertEquals(IRechargeCreditTx.Outcome.CREDITED,
                creditTx.credit(order(), RechargeSnapshot.parse(lastSnapshotJson), PAID_EARLY, PROCESSING).outcome(),
                "目标充值之前的历史断点不得追溯阻断新订单");
        addEvent(1000L, 2);
        assertEquals(IRechargeCreditTx.Outcome.ALREADY,
                actualCreditTx.credit(1000L, "20260722194330").outcome(),
                "目标充值承接最后历史终值后，完成态重放应通过");
    }

    @Test
    void completedOrderRejectsTargetFlowThatDoesNotConnectToItsPredecessor() {
        seedLegacyBrokenPrefix();
        snap(EXPIRE_DAYS);
        creditTx.credit(order(), RechargeSnapshot.parse(lastSnapshotJson), PAID_EARLY, PROCESSING);
        jdbc.update("UPDATE ws_wallet_flow SET ML_AFTER=ML_AFTER+1 WHERE BIZ_IDEMPOTENCY_KEY=?",
                "RECHARGE:" + ORDER_NO);
        jdbc.update("UPDATE ws_card SET BALANCE_ML=BALANCE_ML+1 WHERE ID=?", CARD_ID);
        addEvent(1000L, 2);

        assertThrows(JbkException.class, () -> actualCreditTx.credit(1000L, "20260722194330"));
    }

    @Test
    void completedOrderRejectsBreakAfterTargetFlow() {
        seedLegacyBrokenPrefix();
        snap(EXPIRE_DAYS);
        creditTx.credit(order(), RechargeSnapshot.parse(lastSnapshotJson), PAID_EARLY, PROCESSING);
        insertFlow(2000L, 0L, -10000L, 5500L, 960121L, null, null, 0);
        jdbc.update("UPDATE ws_card SET BALANCE_ML=960121 WHERE ID=?", CARD_ID);
        addEvent(1000L, 2);

        assertThrows(JbkException.class, () -> actualCreditTx.credit(1000L, "20260722194330"));
    }

    @Test
    void completedOrderRejectsCardTerminalDifferentFromLastFlow() {
        seedLegacyBrokenPrefix();
        snap(EXPIRE_DAYS);
        creditTx.credit(order(), RechargeSnapshot.parse(lastSnapshotJson), PAID_EARLY, PROCESSING);
        jdbc.update("UPDATE ws_card SET BALANCE_ML=BALANCE_ML+1 WHERE ID=?", CARD_ID);
        addEvent(1000L, 2);

        assertThrows(JbkException.class, () -> actualCreditTx.credit(1000L, "20260722194330"));
    }

    @Test
    void newOrderStillRejectsDeletedHistoricalFlow() {
        seedLegacyBrokenPrefix();
        jdbc.update("UPDATE ws_wallet_flow SET DATA_STATUS=1 WHERE ID=67");
        snap(EXPIRE_DAYS);

        assertThrows(JbkException.class, () -> creditTx.credit(
                order(), RechargeSnapshot.parse(lastSnapshotJson), PAID_EARLY, PROCESSING));
        assertEquals(470120L, ml());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_wallet_flow WHERE BIZ_IDEMPOTENCY_KEY=?",
                Integer.class, "RECHARGE:" + ORDER_NO));
    }

    // ------------------------------------------------------------------

    /** 拒绝路径必须零副作用：余额、水量、有效期、卡状态、流水全部不变。 */
    private void assertNoSideEffect(String expectExpire, int expectStatus) {
        assertEquals(470120L, ml(), "拒绝入账时水量不得变化");
        assertEquals(5500L, amount(), "拒绝入账时余额不得变化");
        assertEquals(expectExpire, expire(), "拒绝入账时有效期不得变化");
        assertEquals(expectStatus, cardStatus(), "拒绝入账时卡状态不得被恢复");
        assertEquals(0, flowCount(), "拒绝入账时不得留下流水");
    }

    private WsOrder order() {
        WsOrder o = new WsOrder();
        o.setId(ORDER_ID);
        o.setOrderNo(ORDER_NO);
        o.setUserId(USER_ID);
        o.setCardId(CARD_ID);
        o.setPackageId(3L);
        o.setOrderAmount(PAY_AMOUNT);
        o.setPackageSnap("{\"snap\":\"latest\"}");
        return o;
    }

    private RechargeSnapshot.Parsed snap(Integer expireDays) {
        WsPackage p = new WsPackage();
        p.setId(3L);
        p.setPackageName("100元500升卡");
        p.setPayAmount(PAY_AMOUNT);
        p.setWaterMl(WATER_ML);
        p.setBonusAmount(0L);
        p.setUnitPriceSnap("20.00");
        p.setExpireDays(expireDays);
        WsCard c = new WsCard();
        c.setId(CARD_ID);
        c.setExpireTime(expireDays == null ? null : CARD_EXPIRE);
        c.setScopeJson(SCOPE);
        lastSnapshotJson = RechargeSnapshot.build("550e8400-e29b-41d4-a716-446655440000",
                p, c, WaterCardScope.normalize(SCOPE, "水卡"), null, "20260722194200");
        return RechargeSnapshot.parse(lastSnapshotJson);
    }

    private String lastSnapshotJson;

    private final class TestCreditHarness {
        private final IRechargeCreditTx delegate;

        private TestCreditHarness(IRechargeCreditTx delegate) {
            this.delegate = delegate;
        }

        IRechargeCreditTx.CreditResult credit(WsOrder ignoredOrder, RechargeSnapshot.Parsed ignoredSnap,
                                               String paySuccessTime, String processingTime) {
            String expire = RechargePayExpire.compute("20260722194200", ignoredSnap.expireTimeAtCreate());
            jdbc.update("UPDATE ws_order SET PACKAGE_SNAP=?, PACKAGE_ID=3, ORDER_AMOUNT=? WHERE ID=?",
                    lastSnapshotJson, PAY_AMOUNT, ORDER_ID);
            jdbc.update("UPDATE ws_payment SET PAY_STATUS=2, TRANSACTION_ID='SIMTX-1', PAY_AMOUNT=?, "
                            + "PAY_EXPIRE_TIME=?, PAY_SUCCESS_TIME=? WHERE ID=?",
                    PAY_AMOUNT, expire, paySuccessTime, PAYMENT_ID);
            jdbc.update("UPDATE ws_payment_event SET TRANSACTION_ID='SIMTX-1', PAY_AMOUNT=?, CURRENCY='CNY', "
                            + "PAY_SUCCESS_TIME=?, PROCESSING_STATUS=2, DATA_STATUS=0 WHERE ID=?",
                    PAY_AMOUNT, paySuccessTime, EVENT_ID);
            return delegate.credit(EVENT_ID, processingTime);
        }
    }

    private long ml() {
        return jdbc.queryForObject("SELECT BALANCE_ML FROM ws_card WHERE ID = ?", Long.class, CARD_ID);
    }

    private long amount() {
        return jdbc.queryForObject("SELECT BALANCE_AMOUNT FROM ws_card WHERE ID = ?", Long.class, CARD_ID);
    }

    private String expire() {
        return jdbc.queryForObject("SELECT EXPIRE_TIME FROM ws_card WHERE ID = ?", String.class, CARD_ID);
    }

    private int cardStatus() {
        return jdbc.queryForObject("SELECT CARD_STATUS FROM ws_card WHERE ID = ?", Integer.class, CARD_ID);
    }

    private int flowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ws_wallet_flow", Integer.class);
    }

    private int orderStatus() {
        return jdbc.queryForObject("SELECT ORDER_STATUS FROM ws_order WHERE ID = ?", Integer.class, ORDER_ID);
    }

    private void addEvent(long id, int status) {
        jdbc.update("INSERT INTO ws_payment_event(ID,ORDER_NO,ORDER_ID,PAYMENT_ID,PAY_SOURCE,FACT_CHANNEL,"
                        + "PROVIDER_EVENT_KEY,TRADE_STATE,TRANSACTION_ID,PAY_AMOUNT,CURRENCY,PAY_SUCCESS_TIME,"
                        + "PROCESSING_STATUS,DATA_STATUS) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,0)",
                id, ORDER_NO, ORDER_ID, PAYMENT_ID, 2, 3, "evt-" + id, "SUCCESS", "SIMTX-1",
                PAY_AMOUNT, "CNY", PAID_EARLY, status);
    }

    /** 复刻主库已确认的唯一历史断点：2→67 断裂，67→81 与卡终值连续。 */
    private void seedLegacyBrokenPrefix() {
        insertFlow(2L, 0L, 0L, 5500L, 495000L, null, null, 0);
        insertFlow(67L, 0L, -20000L, 5500L, 480000L, null, null, 0);
        insertFlow(81L, 0L, -9880L, 5500L, 470120L, null, null, 0);
    }

    private void insertFlow(long id, long amountChange, long mlChange, long amountAfter, long mlAfter,
                            Long orderId, String bizKey, int dataStatus) {
        insertFlow(id, amountChange, mlChange, amountAfter, mlAfter, orderId, bizKey, dataStatus, 2);
    }

    private void insertRechargeFlow(long id, long amountChange, long mlChange, long amountAfter, long mlAfter,
                                    Long orderId, String bizKey, int dataStatus) {
        insertFlow(id, amountChange, mlChange, amountAfter, mlAfter, orderId, bizKey, dataStatus, 1);
    }

    private void insertFlow(long id, long amountChange, long mlChange, long amountAfter, long mlAfter,
                            Long orderId, String bizKey, int dataStatus, int flowType) {
        jdbc.update("INSERT INTO ws_wallet_flow(ID,DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,"
                        + "CARD_ID,USER_ID,FLOW_TYPE,AMOUNT_CHANGE,ML_CHANGE,AMOUNT_AFTER,ML_AFTER,ORDER_ID,"
                        + "FLOW_REMARK,BIZ_IDEMPOTENCY_KEY) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, dataStatus, USER_ID, "20260710000000", USER_ID, "20260710000000", CARD_ID, USER_ID,
                flowType, amountChange, mlChange, amountAfter, mlAfter, orderId, "历史流水", bizKey);
    }

    private int eventStatus(long id) {
        return jdbc.queryForObject("SELECT PROCESSING_STATUS FROM ws_payment_event WHERE ID=?",
                Integer.class, id);
    }
}
