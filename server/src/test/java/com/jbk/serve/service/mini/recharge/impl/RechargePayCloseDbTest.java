package com.jbk.serve.service.mini.recharge.impl;

import com.jbk.serve.mapper.aftersale.WsCardEntitlementBatchMapper;
import com.jbk.serve.service.aftersale.batch.EntitlementLedger;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.trade.RechargeCreditMapper;
import com.jbk.serve.mapper.trade.RechargeIdentityMapper;
import com.jbk.serve.service.mini.impl.MiniPaySimServiceImpl;
import com.jbk.serve.service.mini.impl.MiniPayStatusServiceImpl;
import com.jbk.serve.service.mini.recharge.IRechargePayCloseTx;
import com.jbk.serve.service.mini.recharge.IRechargePayFactService;
import com.jbk.serve.service.mini.recharge.RechargePayExpire;
import com.jbk.serve.service.mini.recharge.RechargeQueryEventKey;
import com.jbk.serve.service.mini.recharge.RechargeRefundEvidenceVerifier;
import com.jbk.serve.service.mini.card.WaterCardScope;
import com.jbk.serve.service.mini.recharge.RechargeSnapshot;
import com.jbk.tool.data.mini.bo.MiniPaySimBo;
import com.jbk.tool.data.mini.bo.MiniPayStatusBo;
import com.jbk.tool.data.mini.vo.MiniPayStatusVo;
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
import org.mockito.Mockito;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 超时关单集成测试（L2 契约 v2 §6.2 第 5/6 条、§7.1、§9.1；真实 MySQL + Spring 事务）。
 * 核心：本地到点不关单，关单只由支付方 CLOSED 事实经 {@code IRechargePayFactService} 驱动；
 * 真库验证 CAS+事实收敛同事务、唯一键防事实堆积、关单零资金副作用。无 Docker 自动跳过。
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = RechargePayCloseDbTest.Ctx.class)
class RechargePayCloseDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_close_it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static final long CARD_ID = 100L;
    private static final long USER_ID = 9L;
    private static final long ORDER_ID = 777L;
    private static final long PAYMENT_ID = 888L;
    private static final String ORDER_NO = "RC0000000000000000000000000001";
    private static final long PAY_AMOUNT = 10000L;
    private static final long WATER_ML = 500000L;
    private static final int EXPIRE_DAYS = 365;
    private static final String SCOPE = "{\"scopeType\":\"all\"}";
    private static final String CARD_EXPIRE = "20270710120000";

    private static final int PAY_SIM = 2;
    private static final int FACT_CHANNEL_QUERY = 2;

    private static final String CREATE_TIME = "20260722194200";
    /** 由 §2.2 冻结算法算出的不可变付款截止时间：创建时刻 + 30min。 */
    private static final String PAY_EXPIRE = "20260722201200";
    /** 处理时刻：已过付款截止时间（支付方因此回答 CLOSED）。 */
    private static final String AFTER_EXPIRE = "20260722201500";
    /** 处理时刻：仍在窗口内（支付方回答 NOTPAY）。 */
    private static final String BEFORE_EXPIRE = "20260722195000";

    private static final long CARD_ML_BEFORE = 470120L;
    private static final long CARD_AMOUNT_BEFORE = 5500L;

    @Configuration
    @EnableTransactionManagement
    static class Ctx {
        /** 通知登记用真实实现：要验「回滚后库里没有那一行」，mock 证不了。 */
        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.mini.WsWechatNotifyOutboxMapper>
                wechatNotifyOutboxMapper(SqlSessionTemplate t) {
            MapperFactoryBean<com.jbk.serve.mapper.mini.WsWechatNotifyOutboxMapper> bean =
                    new MapperFactoryBean<>(com.jbk.serve.mapper.mini.WsWechatNotifyOutboxMapper.class);
            bean.setSqlSessionTemplate(t);
            return bean;
        }

        @Bean
        com.jbk.serve.service.mini.notify.WechatNotifyEnqueue notifyEnqueue(
                com.jbk.serve.mapper.mini.WsWechatNotifyOutboxMapper m) {
            return new com.jbk.serve.service.mini.notify.WechatNotifyEnqueue(m);
        }

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
            com.baomidou.mybatisplus.core.MybatisConfiguration cfg =
                    new com.baomidou.mybatisplus.core.MybatisConfiguration();
            cfg.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(cfg);
            // 与生产同款自动填充：缺了它，填充字段以 NULL 落库，NOT NULL 列报错、可空列假绿。
            com.baomidou.mybatisplus.core.config.GlobalConfig globalConfig =
                    new com.baomidou.mybatisplus.core.config.GlobalConfig();
            globalConfig.setMetaObjectHandler(new com.jbk.tool.config.system.mybatis.MpMetaObjectHandler());
            factory.setGlobalConfig(globalConfig);
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


        /** E2E-04 包D：入账同事务建批次，缺此 Mapper 上下文起不来。 */
        @Bean
        MapperFactoryBean<WsCardEntitlementBatchMapper> wsCardEntitlementBatchMapper(SqlSessionTemplate t) {
            MapperFactoryBean<WsCardEntitlementBatchMapper> bean = new MapperFactoryBean<>(
                    WsCardEntitlementBatchMapper.class);
            bean.setSqlSessionTemplate(t);
            return bean;
        }

        @Bean
        MapperFactoryBean<RechargeIdentityMapper> identityMapper(SqlSessionTemplate template) {
            MapperFactoryBean<RechargeIdentityMapper> bean = new MapperFactoryBean<>(RechargeIdentityMapper.class);
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
                                      RechargeLedgerVerifier ledger,
                                      WsCardEntitlementBatchMapper batchMapper,
                                      EntitlementLedger entitlementLedger,
                                      com.jbk.serve.service.mini.notify.WechatNotifyEnqueue notifyEnqueue) {
            return new RechargeCreditTxImpl(mapper, locked, ledger, batchMapper, entitlementLedger, notifyEnqueue);
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
        RechargeCreditFailureTxImpl failureTx(RechargeCreditMapper mapper, RechargeLockedState locked,
                                              RechargeLedgerVerifier ledger) {
            return new RechargeCreditFailureTxImpl(mapper, locked, ledger);
        }

        @Bean
        RechargePayConfirmTxImpl confirmTx(RechargeCreditMapper mapper, RechargeIdentityMapper identity,
                                           com.jbk.serve.service.mini.notify.WechatNotifyEnqueue notifyEnqueue) {
            return new RechargePayConfirmTxImpl(mapper, identity, notifyEnqueue);
        }

        @Bean
        RechargePayCloseTxImpl closeTx(RechargeCreditMapper mapper) {
            return new RechargePayCloseTxImpl(mapper);
        }

        @Bean
        RechargeIssueTxImpl issueTx(RechargeCreditMapper mapper, RechargeLedgerVerifier ledger,
                                    WsCardEntitlementBatchMapper batchMapper) {
            return new RechargeIssueTxImpl(mapper, ledger, batchMapper);
        }

        /** 按接口注入 confirm/close/credit/issue：@Transactional 走 JDK 接口代理，注入实现类会失败。 */
        @Bean
        RechargePayFactServiceImpl factService(RechargeCreditMapper credit, RechargeIdentityMapper identity,
                                               com.jbk.serve.service.mini.recharge.IRechargePayConfirmTx confirm,
                                               IRechargePayCloseTx close,
                                               com.jbk.serve.service.mini.recharge.IRechargeCreditTx creditTx,
                                               com.jbk.serve.service.mini.recharge.IRechargeCreditFailureTx failure,
                                               com.jbk.serve.service.mini.recharge.IRechargeIssueTx issue) {
            return new RechargePayFactServiceImpl(credit, identity, confirm, close, creditTx, failure, issue);
        }

        @Bean
        MiniPayStatusServiceImpl payStatusService(RechargeIdentityMapper identity) {
            return new MiniPayStatusServiceImpl(identity,
                    Mockito.mock(RechargeRefundEvidenceVerifier.class));
        }

        @Bean
        PaySimSourceAdapter paySimSourceAdapter() {
            return new PaySimSourceAdapter();
        }

        @Bean
        MiniPaySimServiceImpl paySimService(RechargeIdentityMapper identity, RechargeCreditMapper credit,
                                            IRechargePayFactService facts, PaySimSourceAdapter adapter) {
            return new MiniPaySimServiceImpl(identity, credit, facts, adapter);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    @Autowired
    private IRechargePayFactService factService;
    @Autowired
    private RechargeCreditMapper creditMapper;
    @Autowired
    private MiniPayStatusServiceImpl payStatusService;
    @Autowired
    private MiniPaySimServiceImpl paySimService;
    @Autowired
    private JdbcTemplate jdbc;

    private String snapshotJson;

    @BeforeEach
    void reset() {
        // E2E-04 包D：入账同事务建批次；通知 outbox 表缺失会让整个事务失败
        com.jbk.serve.service.mini.notify.WechatNotifyTestSchema.create(jdbc);
        com.jbk.serve.service.mini.notify.WechatNotifyTestSchema.truncate(jdbc);
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
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_payment (
                  ID BIGINT PRIMARY KEY, ORDER_ID BIGINT, ORDER_NO VARCHAR(64), TRANSACTION_ID VARCHAR(64),
                  PAY_AMOUNT BIGINT, PAY_STATUS TINYINT, PAY_SOURCE TINYINT, CURRENCY VARCHAR(16),
                  PAY_EXPIRE_TIME VARCHAR(20), PAY_SUCCESS_TIME VARCHAR(20), PREPAY_ID VARCHAR(64),
                  CALLBACK_TIME VARCHAR(20), DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT,
                  CREATE_TIME VARCHAR(20), UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        // uk_payment_event_source_channel_key 是"重复查单不会堆事实"的那道闸，必须真实存在
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_payment_event (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT, ORDER_NO VARCHAR(64), ORDER_ID BIGINT, PAYMENT_ID BIGINT,
                  PAY_SOURCE TINYINT, FACT_CHANNEL TINYINT, PROVIDER_EVENT_KEY VARCHAR(100),
                  TRADE_STATE VARCHAR(32), TRANSACTION_ID VARCHAR(64), PAY_AMOUNT BIGINT, CURRENCY VARCHAR(16),
                  PAY_SUCCESS_TIME VARCHAR(20), PROCESSING_STATUS TINYINT, RETRY_COUNT INT DEFAULT 0,
                  NEXT_RETRY_TIME VARCHAR(20), CLAIM_TIME VARCHAR(20), LEASE_UNTIL VARCHAR(20),
                  RECOVERY_APPROVAL_GROUP_KEY VARCHAR(64), RECOVERY_APPROVED_BY BIGINT,
                  RECOVERY_APPROVED_TIME VARCHAR(20), RECOVERY_APPROVAL_REASON VARCHAR(500),
                  RAW_BODY MEDIUMTEXT, RAW_BODY_SHA256 CHAR(64), VERIFY_METHOD TINYINT,
                  RECEIVED_TIME VARCHAR(20), LAST_ERROR VARCHAR(500), PROCESSED_TIME VARCHAR(20),
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  UNIQUE KEY uk_payment_event_source_channel_key (PAY_SOURCE, FACT_CHANNEL, PROVIDER_EVENT_KEY)
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
        // 批次表逐用例清空：uk_batch_order 跨用例复用同一 ORDER_ID 会撞键
        jdbc.execute("TRUNCATE TABLE ws_card_entitlement_batch");
        jdbc.execute("TRUNCATE TABLE ws_wallet_flow");
        jdbc.execute("TRUNCATE TABLE ws_payment_event");
        jdbc.execute("DELETE FROM ws_payment");
        jdbc.execute("DELETE FROM ws_card");
        jdbc.execute("DELETE FROM ws_order");

        jdbc.update("INSERT INTO ws_card(ID,CARD_NO,USER_ID,BALANCE_AMOUNT,BALANCE_ML,SCOPE_JSON,"
                        + "EXPIRE_TIME,CARD_STATUS,DATA_STATUS) VALUES(?,?,?,?,?,?,?,1,0)",
                CARD_ID, "VC001", USER_ID, CARD_AMOUNT_BEFORE, CARD_ML_BEFORE, SCOPE, CARD_EXPIRE);
        snapshotJson = buildSnapshot();
        jdbc.update("INSERT INTO ws_order(ID,ORDER_NO,ORDER_TYPE,USER_ID,CARD_ID,PACKAGE_ID,PACKAGE_SNAP,"
                        + "ORDER_AMOUNT,PAY_WAY,ORDER_STATUS,DATA_STATUS,CREATE_TIME) VALUES(?,?,2,?,?,3,?,?,1,1,0,?)",
                ORDER_ID, ORDER_NO, USER_ID, CARD_ID, snapshotJson, PAY_AMOUNT, CREATE_TIME);
        jdbc.update("INSERT INTO ws_payment(ID,ORDER_ID,ORDER_NO,PAY_AMOUNT,PAY_STATUS,PAY_SOURCE,"
                        + "CURRENCY,PAY_EXPIRE_TIME,DATA_STATUS,CREATE_TIME) VALUES(?,?,?,?,1,?,'CNY',?,0,?)",
                PAYMENT_ID, ORDER_ID, ORDER_NO, PAY_AMOUNT, PAY_SIM, PAY_EXPIRE, CREATE_TIME);
    }

    /** 冻结算法必须与生产一致：截止时间对不上，事实处理器会按契约整体拒绝。 */
    private String buildSnapshot() {
        WsPackage p = new WsPackage();
        p.setId(3L);
        p.setPackageName("100元500升卡");
        p.setPayAmount(PAY_AMOUNT);
        p.setWaterMl(WATER_ML);
        p.setBonusAmount(0L);
        p.setUnitPriceSnap("20.00");
        p.setExpireDays(null); // D-213：历史有限期由下方铸造
        WsCard c = new WsCard();
        c.setId(CARD_ID);
        c.setCardStatus(1);
        c.setExpireTime(CARD_EXPIRE);
        c.setScopeJson(SCOPE);
        String json = com.jbk.serve.service.mini.recharge.LegacySnapshots.forgeExpireDays(
                RechargeSnapshot.build("550e8400-e29b-41d4-a716-446655440000",
                        p, c, WaterCardScope.normalize(SCOPE, "水卡"), null, CREATE_TIME), EXPIRE_DAYS);
        RechargeSnapshot.Parsed parsed = RechargeSnapshot.parse(json);
        assertEquals(PAY_EXPIRE, RechargePayExpire.compute(parsed.capturedTime(), parsed.expireTimeAtCreate()),
                "测试基线的付款截止时间必须由冻结算法算出，不能手填");
        return json;
    }

    // ================= 0：支付成功通知与支付事实同事务 =================

    /** SUCCESS 事实 → 登记支付成功通知恰一行；CLOSED 一条都不该有（对照在下一用例）。 */
    @Test
    void successFactEnqueuesPaymentSucceededNotice() {
        // 付款窗内：过期单上的成功事实会被送去人工核查而不是直接认定
        reseedTiming(-5);
        // 成功事实必须带齐交易号/成功时间/金额（共键校验），缺项的 SUCCESS 会被判伪证拒绝
        long eventId = insertRawEvent("SUCCESS", 3, "SIM-" + ORDER_NO, "SIMTX-1",
                "20260722194314", PAY_AMOUNT, null);
        // 共享 helper 恒写 CURRENCY=NULL，成功事实必须带币种，故单独补上
        jdbc.update("UPDATE ws_payment_event SET CURRENCY='CNY' WHERE ID=?", eventId);
        IRechargePayFactService.Outcome outcome = factService.process(eventId);

        assertEquals(2, payStatus(),
                "前置不成立本用例就什么都没验：支付单应已被推进为成功，outcome=" + outcome.code()
                        + "/" + outcome.message());
        assertEquals(1, noticeCount("PAYMENT_SUCCEEDED"),
                "支付被认定却没登记通知，outcome=" + outcome.code());
    }

    /** 对照：登记误写在 process 开头时，关单也会发「支付成功」——本条防这个。 */
    @Test
    void closedFactEnqueuesNoPaymentSucceededNotice() {
        factService.process(insertQueryFact("CLOSED", AFTER_EXPIRE));
        assertEquals(0, noticeCount("PAYMENT_SUCCEEDED"), "关单也发了「支付成功」");
    }

    /** 某类通知的登记条数。查真表，不查 mock。 */
    private int noticeCount(String eventType) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_wechat_notify_outbox WHERE EVENT_TYPE = ?", Integer.class, eventType);
    }

    // ================= 1：支付方 CLOSED → payment 1/order 1 → payment 4/order 5 =================

    @Test
    void providerClosedDrivesPaymentAndOrderToClosedState() {
        long eventId = insertQueryFact("CLOSED", AFTER_EXPIRE);

        IRechargePayFactService.Outcome outcome = factService.process(eventId);

        assertEquals("CLOSED", outcome.code());
        assertEquals(4, payStatus(), "payment 必须由 1 推进到 4");
        assertEquals(5, orderStatus(), "order 必须由 1 推进到 5");
        assertEquals(3, eventStatus(eventId), "关闭事实必须收敛为已处理");
        assertNoMoneyTouched();
    }

    /** 零副作用：关单绝不能碰卡余额、水量、有效期或任何资金流水。 */
    private void assertNoMoneyTouched() {
        assertEquals(CARD_ML_BEFORE, cardMl(), "关单不得改动卡水量");
        assertEquals(CARD_AMOUNT_BEFORE, cardAmount(), "关单不得改动卡余额");
        assertEquals(CARD_EXPIRE, cardExpire(), "关单不得改动卡有效期");
        assertEquals(1, cardStatus(), "关单不得改动卡状态");
        assertEquals(0, flowCount(), "关单不得产生任何资金流水");
    }

    // ================= 2：重复 CLOSED 幂等 =================

    /** 同一事实重复查单命中同一条事件：第二次只回 ALREADY，不重复推进也不报错。 */
    @Test
    void repeatedQueryOfSameClosedFactIsIdempotent() {
        long eventId = insertQueryFact("CLOSED", AFTER_EXPIRE);
        factService.process(eventId);

        IRechargePayFactService.Outcome again = factService.process(eventId);

        assertEquals("ALREADY", again.code());
        assertEquals(4, payStatus());
        assertEquals(5, orderStatus());
        assertNoMoneyTouched();
    }

    /** 另一条独立到达的 CLOSED 事实（例如换渠道重查）：只能在精确 payment 4/order 5 下幂等收敛。 */
    @Test
    void secondClosedFactConvergesIdempotentlyOnExactClosedState() {
        factService.process(insertQueryFact("CLOSED", AFTER_EXPIRE));
        long second = insertRawEvent("CLOSED", 1, "notify-closed-1", null, null, null, null);

        IRechargePayFactService.Outcome outcome = factService.process(second);

        assertEquals("ALREADY", outcome.code());
        assertEquals(3, eventStatus(second));
        assertEquals(4, payStatus());
        assertEquals(5, orderStatus());
        assertNoMoneyTouched();
    }

    // ================= 3：内部已 SUCCESS 时 CLOSED 转人工对账 =================

    /** 已收到款的订单收到 CLOSED：不得回退成已关闭，否则真实收款凭空消失。 */
    @Test
    void closedConflictingWithInternalSuccessGoesToReconciliation() {
        advanceToPaid();
        long eventId = insertRawEvent("CLOSED", FACT_CHANNEL_QUERY,
                RechargeQueryEventKey.derive(PAY_SIM, ORDER_NO, "CLOSED", null, null, null, null),
                null, null, null, null);

        IRechargePayFactService.Outcome outcome = factService.process(eventId);

        assertEquals("RECONCILIATION", outcome.code());
        assertEquals(2, payStatus(), "冲突时 payment 必须保持 2，不得被关单覆盖");
        assertEquals(2, orderStatus(), "冲突时 order 必须保持 2");
        assertEquals(5, eventStatus(eventId), "冲突事实必须进入人工对账");
        assertNoMoneyTouched();
    }

    // ================= 4：NOTPAY 保持 1 且置 PROCESSED =================

    @Test
    void notpayKeepsPendingAndMarksFactProcessed() {
        long eventId = insertQueryFact("NOTPAY", BEFORE_EXPIRE);

        IRechargePayFactService.Outcome outcome = factService.process(eventId);

        assertEquals("NOTPAY", outcome.code());
        assertEquals(1, payStatus(), "支付方返回未支付时 payment 必须保持 1");
        assertEquals(1, orderStatus(), "支付方返回未支付时 order 必须保持 1");
        assertEquals(3, eventStatus(eventId));
        assertNoMoneyTouched();
    }

    // ================= 5：NOTPAY 但内部已推进 → 人工对账 =================

    @Test
    void notpayAgainstAdvancedInternalStateGoesToReconciliation() {
        advanceToPaid();
        long eventId = insertQueryFact("NOTPAY", BEFORE_EXPIRE);

        IRechargePayFactService.Outcome outcome = factService.process(eventId);

        assertEquals("RECONCILIATION", outcome.code());
        assertEquals(2, payStatus(), "不得因一条 NOTPAY 把已支付回退");
        assertEquals(2, orderStatus());
        assertEquals(5, eventStatus(eventId));
        assertNoMoneyTouched();
    }

    // ================= 6：关单后 pay-status =================

    @Test
    void payStatusAfterCloseIsClosedAndNotRetryable() {
        factService.process(insertQueryFact("NOTPAY", BEFORE_EXPIRE));
        factService.process(insertQueryFact("CLOSED", AFTER_EXPIRE));

        MiniPayStatusBo bo = new MiniPayStatusBo();
        bo.setOrderNo(ORDER_NO);
        MiniPayStatusVo vo = payStatusService.query(bo, USER_ID);

        assertEquals("CLOSED", vo.getPayStatusCode(), "payment 4/order 5 必须解释为已关闭");
        assertFalse(vo.getRetryable(), "已关闭是终态，客户端不得继续轮询");
        assertEquals(4, vo.getPayStatus());
        assertEquals(5, vo.getOrderStatus());
        assertEquals(PAY_EXPIRE, vo.getPayExpireTime());
    }

    /** §9.1：payment 4/order 5 下可以保留更早的 PROCESSED NOTPAY，但未收敛的查询事实必须暴露。 */
    @Test
    void payStatusRejectsUnconvergedQueryFactUnderClosedState() {
        factService.process(insertQueryFact("CLOSED", AFTER_EXPIRE));
        insertRawEvent("NOTPAY", 1, "notify-notpay-1", null, null, null, null);

        MiniPayStatusBo bo = new MiniPayStatusBo();
        bo.setOrderNo(ORDER_NO);
        assertEquals("MISMATCH", payStatusService.query(bo, USER_ID).getPayStatusCode());
    }

    // ================= 7：查单事实永不进入权益 Worker =================

    /** 契约 §6.3：NOTPAY/CLOSED 永不进入权益 Worker，守卫是 claimEvent 的 {@code TRADE_STATE='SUCCESS'}。 */
    @Test
    void creditWorkerCannotClaimQueryFacts() {
        long closed = insertQueryFact("CLOSED", AFTER_EXPIRE);
        long notpay = insertQueryFact("NOTPAY", BEFORE_EXPIRE);

        assertEquals(0, creditMapper.claimEvent(closed, AFTER_EXPIRE, "20260722202000"));
        assertEquals(0, creditMapper.claimEvent(notpay, AFTER_EXPIRE, "20260722202000"));
        assertEquals(1, eventStatus(closed), "未被认领的事实必须保持待处理");
        assertEquals(1, eventStatus(notpay));
    }

    // ================= 8：共键错位与自相矛盾的查单事实 =================

    @Test
    void queryFactWithForeignPaymentIdIsRejectedWithoutTouchingOrder() {
        long eventId = insertRawEvent("CLOSED", FACT_CHANNEL_QUERY, "Q:foreign", null, null, null, null);
        jdbc.update("UPDATE ws_payment_event SET PAYMENT_ID = 12345 WHERE ID = ?", eventId);

        assertEquals("RECONCILIATION", factService.process(eventId).code());
        assertEquals(1, payStatus());
        assertEquals(1, orderStatus());
        assertEquals(5, eventStatus(eventId));
    }

    /** 非成功事实携带交易号/成功时间即自相矛盾，必须拒绝——否则一条"关闭"事实能偷带成功语义。 */
    @Test
    void nonSuccessFactCarryingSuccessFieldsIsRejected() {
        long eventId = insertRawEvent("CLOSED", FACT_CHANNEL_QUERY, "Q:contradiction",
                "SIMTX-X", "20260722194314", null, null);

        assertEquals("RECONCILIATION", factService.process(eventId).code());
        assertEquals(1, payStatus());
        assertEquals(1, orderStatus());
    }

    /** 支付方返回了金额但与内部不符：不得关单。 */
    @Test
    void queryFactWithWrongAmountIsRejected() {
        long eventId = insertRawEvent("CLOSED", FACT_CHANNEL_QUERY, "Q:wrong-amount",
                null, null, PAY_AMOUNT + 1, null);

        assertEquals("RECONCILIATION", factService.process(eventId).code());
        assertEquals(1, payStatus());
        assertEquals(1, orderStatus());
    }

    /** 未登记的支付方状态只留证转人工，绝不静默跳过。 */
    @Test
    void unknownTradeStateIsParkedForReconciliation() {
        long eventId = insertRawEvent("REVOKED", FACT_CHANNEL_QUERY, "Q:revoked", null, null, null, null);

        assertEquals("RECONCILIATION", factService.process(eventId).code());
        assertEquals(5, eventStatus(eventId));
        assertEquals(1, payStatus());
        assertEquals(1, orderStatus());
    }

    /** NOTPAY 与 CLOSED 必须是两条不同的事实键，先落的 NOTPAY 不得把 CLOSED 挡在唯一键外。 */
    @Test
    void notpayAndClosedOccupyDifferentEventKeys() {
        long notpay = insertQueryFact("NOTPAY", BEFORE_EXPIRE);
        long closed = insertQueryFact("CLOSED", AFTER_EXPIRE);
        assertNotEquals(notpay, closed);
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_payment_event WHERE FACT_CHANNEL = 2", Integer.class));
    }

    // ================= 9：Pay-Sim 扮演支付方，端到端走同一条路径 =================

    /**
     * 截止已过 → Pay-Sim 回答 CLOSED → 统一处理器 CAS 关单。断言的是路径而非结果：
     * FACT_CHANNEL=2、事件键按契约规范串独立复算、四个外部字段全空。
     */
    @Test
    void paySimQueryAfterExpiryClosesOrderThroughTheSharedFactPath() {
        reseedTiming(-40);

        paySimService.query(orderBo(), USER_ID);

        assertEquals(4, payStatus());
        assertEquals(5, orderStatus());
        assertNoMoneyTouched();
        assertEquals(RechargeQueryEventKey.derive(PAY_SIM, ORDER_NO, "CLOSED", null, null, null, null),
                jdbc.queryForObject("SELECT PROVIDER_EVENT_KEY FROM ws_payment_event", String.class));
        assertEquals(2, jdbc.queryForObject("SELECT FACT_CHANNEL FROM ws_payment_event", Integer.class),
                "查单事实必须落在 QUERY 通道，将来真实微信查单用的是同一个通道号");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ws_payment_event WHERE "
                        + "TRANSACTION_ID IS NULL AND PAY_AMOUNT IS NULL AND CURRENCY IS NULL "
                        + "AND PAY_SUCCESS_TIME IS NULL", Integer.class),
                "非成功事实不得用内部订单金额补造外部字段");
    }

    /** 仍在付款窗口内 → 支付方回答 NOTPAY → 订单保持待支付。这里正是"本地不自行关单"的分界线。 */
    @Test
    void paySimQueryBeforeExpiryKeepsOrderPending() {
        reseedTiming(-5);

        paySimService.query(orderBo(), USER_ID);

        assertEquals(1, payStatus());
        assertEquals(1, orderStatus());
        assertNoMoneyTouched();
    }

    /** 重复查单必须稳定命中同一条事实，不为每次轮询堆一条新记录。 */
    @Test
    void repeatedPaySimQueryReusesTheSameFact() {
        reseedTiming(-40);
        paySimService.query(orderBo(), USER_ID);
        paySimService.query(orderBo(), USER_ID);

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ws_payment_event", Integer.class));
        assertEquals(4, payStatus());
        assertEquals(5, orderStatus());
    }

    /** 已存在成功事实时，模拟支付方不得回答"未支付/已关闭"——那是与已知事实矛盾的伪证。 */
    @Test
    void paySimQueryRefusesToContradictAnExistingSuccessFact() {
        reseedTiming(-40);
        insertRawEvent("SUCCESS", 3, "SIM-" + ORDER_NO, "SIMTX-1", "20260722194314", PAY_AMOUNT, null);

        assertThrows(JbkException.class, () -> paySimService.query(orderBo(), USER_ID));
        assertEquals(1, payStatus(), "拒绝查单时不得改动支付单");
        assertEquals(1, orderStatus());
    }

    /** 契约 §5.3：同一事实键下正文摘要被改动，必须拒绝复用并转人工核查。 */
    @Test
    void tamperedRawBodyDigestUnderSameKeyIsRefused() {
        reseedTiming(-5);
        paySimService.query(orderBo(), USER_ID);
        jdbc.update("UPDATE ws_payment_event SET RAW_BODY_SHA256 = ?", "f".repeat(64));

        assertThrows(JbkException.class, () -> paySimService.query(orderBo(), USER_ID));
        assertEquals(1, payStatus());
        assertEquals(1, orderStatus());
    }

    /** 非本人订单：与 pay-status 同一口径拒绝，绝不成为订单号探测信道。 */
    @Test
    void paySimQueryRejectsForeignOrder() {
        reseedTiming(-40);
        assertThrows(JbkException.class, () -> paySimService.query(orderBo(), USER_ID + 1));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ws_payment_event", Integer.class));
    }

    private MiniPaySimBo orderBo() {
        MiniPaySimBo bo = new MiniPaySimBo();
        bo.setOrderNo(ORDER_NO);
        return bo;
    }

    /**
     * 把时间轴挪到相对真实当前时刻的位置：Pay-Sim 读系统时钟，且 PAY_EXPIRE_TIME
     * 必须由 §2.2 冻结算法从快照算出，故快照要一起重建。
     *
     * @param createMinutesAgo 订单创建时刻相对现在的分钟偏移（负数表示过去）
     */
    private void reseedTiming(int createMinutesAgo) {
        String createTime = java.time.LocalDateTime.now().plusMinutes(createMinutesAgo)
                .format(RechargePayExpire.FMT);
        WsPackage p = new WsPackage();
        p.setId(3L);
        p.setPackageName("100元500升卡");
        p.setPayAmount(PAY_AMOUNT);
        p.setWaterMl(WATER_ML);
        p.setBonusAmount(0L);
        p.setUnitPriceSnap("20.00");
        p.setExpireDays(null); // D-213：历史有限期由下方铸造
        WsCard c = new WsCard();
        c.setId(CARD_ID);
        c.setCardStatus(1);
        c.setExpireTime(CARD_EXPIRE);
        c.setScopeJson(SCOPE);
        String json = com.jbk.serve.service.mini.recharge.LegacySnapshots.forgeExpireDays(
                RechargeSnapshot.build("550e8400-e29b-41d4-a716-446655440000",
                        p, c, WaterCardScope.normalize(SCOPE, "水卡"), null, createTime), EXPIRE_DAYS);
        RechargeSnapshot.Parsed parsed = RechargeSnapshot.parse(json);
        String expire = RechargePayExpire.compute(parsed.capturedTime(), parsed.expireTimeAtCreate());
        jdbc.update("UPDATE ws_order SET PACKAGE_SNAP=?, CREATE_TIME=? WHERE ID=?", json, createTime, ORDER_ID);
        jdbc.update("UPDATE ws_payment SET PAY_EXPIRE_TIME=? WHERE ID=?", expire, PAYMENT_ID);
    }

    // ------------------------------------------------------------------

    /** 落一条与 Pay-Sim 查单完全同构的查询事实（键由内容派生，外部字段全空）。 */
    private long insertQueryFact(String tradeState, String receivedTime) {
        String key = RechargeQueryEventKey.derive(PAY_SIM, ORDER_NO, tradeState, null, null, null, null);
        return insertRawEvent(tradeState, FACT_CHANNEL_QUERY, key, null, null, null, receivedTime);
    }

    private long insertRawEvent(String tradeState, int factChannel, String providerKey,
                                String transactionId, String paySuccessTime, Long payAmount,
                                String receivedTime) {
        String now = receivedTime == null ? BEFORE_EXPIRE : receivedTime;
        jdbc.update("INSERT INTO ws_payment_event(ORDER_NO,ORDER_ID,PAYMENT_ID,PAY_SOURCE,FACT_CHANNEL,"
                        + "PROVIDER_EVENT_KEY,TRADE_STATE,TRANSACTION_ID,PAY_AMOUNT,CURRENCY,PAY_SUCCESS_TIME,"
                        + "PROCESSING_STATUS,RAW_BODY_SHA256,VERIFY_METHOD,RECEIVED_TIME,DATA_STATUS,"
                        + "CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,NULL,?,1,?,3,?,0,?,?,?,?)",
                ORDER_NO, ORDER_ID, PAYMENT_ID, PAY_SIM, factChannel, providerKey, tradeState,
                transactionId, payAmount, paySuccessTime, "0".repeat(64), now,
                USER_ID, now, USER_ID, now);
        return jdbc.queryForObject("SELECT ID FROM ws_payment_event WHERE PROVIDER_EVENT_KEY = ? "
                + "AND FACT_CHANNEL = ?", Long.class, providerKey, factChannel);
    }

    /** 把订单推进到「已收到权威付款事实、权益待入账」，用于制造与 CLOSED/NOTPAY 的冲突。 */
    private void advanceToPaid() {
        jdbc.update("UPDATE ws_payment SET PAY_STATUS=2, TRANSACTION_ID='SIMTX-1', PAY_SUCCESS_TIME=? "
                + "WHERE ID=?", "20260722194314", PAYMENT_ID);
        jdbc.update("UPDATE ws_order SET ORDER_STATUS=2 WHERE ID=?", ORDER_ID);
    }

    private int payStatus() {
        return jdbc.queryForObject("SELECT PAY_STATUS FROM ws_payment WHERE ID=?", Integer.class, PAYMENT_ID);
    }

    private int orderStatus() {
        return jdbc.queryForObject("SELECT ORDER_STATUS FROM ws_order WHERE ID=?", Integer.class, ORDER_ID);
    }

    private int eventStatus(long id) {
        return jdbc.queryForObject("SELECT PROCESSING_STATUS FROM ws_payment_event WHERE ID=?",
                Integer.class, id);
    }

    private long cardMl() {
        return jdbc.queryForObject("SELECT BALANCE_ML FROM ws_card WHERE ID=?", Long.class, CARD_ID);
    }

    private long cardAmount() {
        return jdbc.queryForObject("SELECT BALANCE_AMOUNT FROM ws_card WHERE ID=?", Long.class, CARD_ID);
    }

    private String cardExpire() {
        return jdbc.queryForObject("SELECT EXPIRE_TIME FROM ws_card WHERE ID=?", String.class, CARD_ID);
    }

    private int cardStatus() {
        return jdbc.queryForObject("SELECT CARD_STATUS FROM ws_card WHERE ID=?", Integer.class, CARD_ID);
    }

    private int flowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ws_wallet_flow", Integer.class);
    }
}
