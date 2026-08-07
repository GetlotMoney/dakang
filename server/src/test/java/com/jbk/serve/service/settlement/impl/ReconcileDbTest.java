package com.jbk.serve.service.settlement.impl;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.settlement.WsReconcileDiffMapper;
import com.jbk.serve.mapper.settlement.WsReconcileTaskMapper;
import com.jbk.serve.service.delivery.impl.DeliveryDbSchema;
import com.jbk.serve.service.settlement.IReconcileService;
import com.jbk.tool.data.settlement.po.WsReconcileTask;
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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E-08 日对账的<b>真实 MySQL</b> 集成测试（包C）。
 * 钉住口径：平账日零差异；四类差异（单边/金额不符/状态不符/账本断裂）各可检出；
 * 同账期重跑=差异整批替换（不叠加）；对账只读不改业务数据。
 *
 * <p>无 Docker 环境自动跳过。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ReconcileDbTest.Ctx.class)
class ReconcileDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_reconcile_it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static final String DAY = "20260731";
    private static final String TS = DAY + "120000";

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
            com.baomidou.mybatisplus.core.MybatisConfiguration cfg =
                    new com.baomidou.mybatisplus.core.MybatisConfiguration();
            cfg.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(cfg);
            com.baomidou.mybatisplus.core.config.GlobalConfig globalConfig =
                    new com.baomidou.mybatisplus.core.config.GlobalConfig();
            globalConfig.setMetaObjectHandler(new com.jbk.tool.config.system.mybatis.MpMetaObjectHandler());
            factory.setGlobalConfig(globalConfig);
            return new SqlSessionTemplate(factory.getObject());
        }

        private static <M> MapperFactoryBean<M> mapper(Class<M> type, SqlSessionTemplate template) {
            MapperFactoryBean<M> bean = new MapperFactoryBean<>(type);
            bean.setSqlSessionTemplate(template);
            return bean;
        }

        @Bean
        MapperFactoryBean<WsReconcileTaskMapper> wsReconcileTaskMapper(SqlSessionTemplate t) {
            return mapper(WsReconcileTaskMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsReconcileDiffMapper> wsReconcileDiffMapper(SqlSessionTemplate t) {
            return mapper(WsReconcileDiffMapper.class, t);
        }

        @Bean
        IReconcileService reconcileService() {
            return new ReconcileServiceImpl();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    @Autowired
    private IReconcileService reconcileService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        DeliveryDbSchema.createAll(jdbc);
        DeliveryDbSchema.truncateAll(jdbc);
        jdbc.execute("TRUNCATE TABLE ws_payment");
    }

    /** 平账素材：充值单+支付单+RECHARGE 流水+卡（余额与末笔 AFTER 一致）。 */
    private void seedBalancedRecharge(String orderNo, long amount) {
        jdbc.update("INSERT INTO ws_order (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, ORDER_NO, ORDER_TYPE, USER_ID, ORDER_AMOUNT, PAY_WAY, ORDER_STATUS) VALUES (0,1,?,1,?,?,2,1001,?,1,4)",
                TS, TS, orderNo, amount);
        long orderId = jdbc.queryForObject("SELECT ID FROM ws_order WHERE ORDER_NO=?", Long.class, orderNo);
        jdbc.update("INSERT INTO ws_payment (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, ORDER_ID, ORDER_NO, PAY_AMOUNT, PAY_STATUS, PAY_SOURCE, PAY_EXPIRE_TIME) VALUES (0,1,?,1,?,?,?,?,2,2,?)",
                TS, TS, orderId, orderNo, amount, TS);
        long cardId = seedCard(amount, 0);
        jdbc.update("INSERT INTO ws_wallet_flow (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, CARD_ID, USER_ID, FLOW_TYPE, AMOUNT_CHANGE, ML_CHANGE, AMOUNT_AFTER, ML_AFTER, BIZ_IDEMPOTENCY_KEY) VALUES (0,1,?,1,?,?,1001,1,?,0,?,0,?)",
                TS, TS, cardId, amount, amount, "RECHARGE:" + orderNo);
    }

    private long seedCard(long balance, long ml) {
        jdbc.update("INSERT INTO ws_card (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, CARD_NO, CARD_TYPE, USER_ID, BALANCE_AMOUNT, BALANCE_ML, CARD_STATUS) VALUES (0,1,?,1,?,CONCAT('VC', FLOOR(RAND()*1e9)),1,1001,?,?,1)",
                TS, TS, balance, ml);
        return jdbc.queryForObject("SELECT MAX(ID) FROM ws_card", Long.class);
    }

    @Test
    void balancedDayReportsNoDiff() {
        seedBalancedRecharge("RC-OK-1", 10000);
        WsReconcileTask task = reconcileService.runFor(DAY);
        assertEquals(2, task.getTaskStatus(), "平账");
        assertEquals(0, task.getDiffTotal());
        assertTrue(task.getCheckTotal() > 0, "确有核对项");
    }

    /**
     * 配送单（3类/余额支付/已完成）必须进 order-flow 核对，且认 DELIVERY: 前缀。
     * 曾整类漏出核对范围（主环境真实数据实点抓获）：漏检使批次显示"平账"，比误报更危险。
     */
    @Test
    void deliveryBalanceOrderIsCoveredByOrderFlowWithItsOwnKeyPrefix() {
        long cardId = seedCard(0, 0);
        jdbc.update("INSERT INTO ws_order (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, ORDER_NO, ORDER_TYPE, USER_ID, ORDER_AMOUNT, PAY_WAY, ORDER_STATUS) VALUES (0,1,?,1,?,'WD-OK',3,1001,700,2,4)",
                TS, TS);
        jdbc.update("INSERT INTO ws_wallet_flow (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, CARD_ID, USER_ID, FLOW_TYPE, AMOUNT_CHANGE, ML_CHANGE, AMOUNT_AFTER, ML_AFTER, BIZ_IDEMPOTENCY_KEY) VALUES (0,1,?,1,?,?,1001,7,-700,0,0,0,'DELIVERY:WD-OK')",
                TS, TS, cardId);
        // 有 DELIVERY 流水=平账（若核对方拼 CONSUME: 前缀，这里会反向误报单边账）
        WsReconcileTask ok = reconcileService.runFor(DAY);
        assertEquals(0, ok.getDiffTotal(), "配送单流水齐备应平账");

        // 缺流水的配送单必须被抓成单边账（证明该类订单确实进了核对范围，不是被跳过）
        jdbc.update("INSERT INTO ws_order (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, ORDER_NO, ORDER_TYPE, USER_ID, ORDER_AMOUNT, PAY_WAY, ORDER_STATUS) VALUES (0,1,?,1,?,'WD-LOST',3,1001,700,2,4)",
                TS, TS);
        WsReconcileTask bad = reconcileService.runFor(DAY);
        assertEquals(3, bad.getTaskStatus(), "缺流水的配送单应判有差异");
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_reconcile_diff WHERE CHECK_DIMENSION='order-flow' AND BIZ_KEY='WD-LOST' AND DIFF_TYPE=1",
                Integer.class), "配送单缺流水=单边账");
    }

    /**
     * 微信支付订单已达已支付族却无成功支付单=资损方向单边账；
     * 余额/水量支付订单本就无支付单，不得因此误报。
     */
    @Test
    void paidOrderWithoutPaymentFactIsDetectedOnlyForWechatPayWay() {
        jdbc.update("INSERT INTO ws_order (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, ORDER_NO, ORDER_TYPE, USER_ID, ORDER_AMOUNT, PAY_WAY, ORDER_STATUS) VALUES (0,1,?,1,?,'WX-NOPAY',3,1001,3000,1,2)",
                TS, TS);
        // 对照：水量支付完成单（无支付单属正常口径）
        jdbc.update("INSERT INTO ws_order (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, ORDER_NO, ORDER_TYPE, USER_ID, ORDER_AMOUNT, PAY_WAY, ORDER_STATUS) VALUES (0,1,?,1,?,'ML-OK',1,1001,0,3,4)",
                TS, TS);
        WsReconcileTask task = reconcileService.runFor(DAY);
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_reconcile_diff WHERE CHECK_DIMENSION='payment-fact' AND BIZ_KEY='WX-NOPAY' AND DIFF_TYPE=1",
                Integer.class), "微信支付已支付族无支付单=单边账");
        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_reconcile_diff WHERE BIZ_KEY='ML-OK'", Integer.class),
                "水量支付订单不得因无支付单被误报");
        assertEquals(3, task.getTaskStatus());
    }

    @Test
    void fourDiffFamiliesAreDetected() {
        // ① 单边账：支付单无订单
        jdbc.update("INSERT INTO ws_payment (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, ORDER_ID, ORDER_NO, PAY_AMOUNT, PAY_STATUS, PAY_SOURCE, PAY_EXPIRE_TIME) VALUES (0,1,?,1,?,0,'RC-LONE',500,2,2,?)",
                TS, TS, TS);
        // ② 金额不符：支付 900 订单 1000
        jdbc.update("INSERT INTO ws_order (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, ORDER_NO, ORDER_TYPE, USER_ID, ORDER_AMOUNT, PAY_WAY, ORDER_STATUS) VALUES (0,1,?,1,?,'RC-AMT',2,1001,1000,1,4)",
                TS, TS);
        long amountOrderId = jdbc.queryForObject("SELECT ID FROM ws_order WHERE ORDER_NO='RC-AMT'", Long.class);
        jdbc.update("INSERT INTO ws_payment (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, ORDER_ID, ORDER_NO, PAY_AMOUNT, PAY_STATUS, PAY_SOURCE, PAY_EXPIRE_TIME) VALUES (0,1,?,1,?,?,'RC-AMT',900,2,2,?)",
                TS, TS, amountOrderId, TS);
        jdbc.update("INSERT INTO ws_wallet_flow (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, CARD_ID, USER_ID, FLOW_TYPE, AMOUNT_CHANGE, ML_CHANGE, AMOUNT_AFTER, ML_AFTER, BIZ_IDEMPOTENCY_KEY) VALUES (0,1,?,1,?,999,1001,1,1000,0,1000,0,'RECHARGE:RC-AMT')",
                TS, TS);
        // ③ 状态不符：支付成功但订单仍待支付（其 RECHARGE 流水缺失同时构成 order-flow 单边）
        jdbc.update("INSERT INTO ws_order (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, ORDER_NO, ORDER_TYPE, USER_ID, ORDER_AMOUNT, PAY_WAY, ORDER_STATUS) VALUES (0,1,?,1,?,'RC-ST',2,1001,800,1,1)",
                TS, TS);
        long stateOrderId = jdbc.queryForObject("SELECT ID FROM ws_order WHERE ORDER_NO='RC-ST'", Long.class);
        jdbc.update("INSERT INTO ws_payment (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, ORDER_ID, ORDER_NO, PAY_AMOUNT, PAY_STATUS, PAY_SOURCE, PAY_EXPIRE_TIME) VALUES (0,1,?,1,?,?,'RC-ST',800,2,2,?)",
                TS, TS, stateOrderId, TS);
        // ④ 账本断裂：卡余额 500 但末笔 AFTER 400
        long cardId = seedCard(500, 0);
        jdbc.update("INSERT INTO ws_wallet_flow (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, CARD_ID, USER_ID, FLOW_TYPE, AMOUNT_CHANGE, ML_CHANGE, AMOUNT_AFTER, ML_AFTER, BIZ_IDEMPOTENCY_KEY) VALUES (0,1,?,1,?,?,1001,1,400,0,400,0,'RECHARGE:RC-LEDGER')",
                TS, TS, cardId);

        WsReconcileTask task = reconcileService.runFor(DAY);
        assertEquals(3, task.getTaskStatus(), "有差异");
        List<Map<String, Object>> diffs = jdbc.queryForList(
                "SELECT DIFF_TYPE, CHECK_DIMENSION FROM ws_reconcile_diff WHERE BIZ_DATE=?", DAY);
        assertTrue(diffs.stream().anyMatch(d -> ((Number) d.get("DIFF_TYPE")).intValue() == 1), "单边账检出");
        assertTrue(diffs.stream().anyMatch(d -> ((Number) d.get("DIFF_TYPE")).intValue() == 2), "金额不符检出");
        assertTrue(diffs.stream().anyMatch(d -> ((Number) d.get("DIFF_TYPE")).intValue() == 3), "状态不符检出");
        assertTrue(diffs.stream().anyMatch(d -> ((Number) d.get("DIFF_TYPE")).intValue() == 4), "账本断裂检出");
    }

    @Test
    void rerunReplacesDiffBatchInsteadOfAppending() {
        jdbc.update("INSERT INTO ws_payment (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, ORDER_ID, ORDER_NO, PAY_AMOUNT, PAY_STATUS, PAY_SOURCE, PAY_EXPIRE_TIME) VALUES (0,1,?,1,?,0,'RC-RERUN',500,2,2,?)",
                TS, TS, TS);
        reconcileService.runFor(DAY);
        WsReconcileTask second = reconcileService.runFor(DAY);
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_reconcile_task WHERE BIZ_DATE=?", Integer.class, DAY), "同账期恒一行");
        assertEquals(second.getDiffTotal().intValue(), (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_reconcile_diff WHERE BIZ_DATE=?", Integer.class, DAY),
                "重跑整批替换不叠加");
    }

    @Test
    void paymentOrderIdMismatchIsDetected() {
        seedBalancedRecharge("RC-LINK-MISMATCH", 1000);
        jdbc.update("UPDATE ws_payment SET ORDER_ID=ORDER_ID+999 WHERE ORDER_NO='RC-LINK-MISMATCH'");

        reconcileService.runFor(DAY);

        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_reconcile_diff WHERE CHECK_DIMENSION='payment-fact' AND BIZ_KEY LIKE '%RC-LINK-MISMATCH%' AND DIFF_TYPE=3",
                Integer.class), "支付单与订单 ID 共键错位必须被对账发现");
    }

    @Test
    void waterSplitUnderAllocationIsDetectedAgainstActualDebit() {
        long cardId = seedCard(0, 0);
        jdbc.update("INSERT INTO ws_order (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, ORDER_NO, ORDER_TYPE, USER_ID, ORDER_AMOUNT, PAY_WAY, ORDER_STATUS) VALUES (0,1,?,1,?,'WO-SPLIT-LOW',1,1001,1000,2,4)", TS, TS);
        long orderId = jdbc.queryForObject("SELECT ID FROM ws_order WHERE ORDER_NO='WO-SPLIT-LOW'", Long.class);
        jdbc.update("INSERT INTO ws_wallet_flow (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, CARD_ID, USER_ID, FLOW_TYPE, AMOUNT_CHANGE, ML_CHANGE, AMOUNT_AFTER, ML_AFTER, ORDER_ID, BIZ_IDEMPOTENCY_KEY) VALUES (0,1,?,1,?,?,1001,2,-1000,0,0,0,?,'CONSUME:WO-SPLIT-LOW')", TS, TS, cardId, orderId);
        jdbc.update("INSERT INTO ws_split_record (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, ORDER_ID, RECEIVER_TYPE, RECEIVER_USER_ID, SPLIT_AMOUNT, SPLIT_RATE_SNAP, SPLIT_STATUS) VALUES (0,1,?,1,?,?,1,1001,400,'4000',2),(0,1,?,1,?,?,3,0,100,'REMAINDER',2)", TS, TS, orderId, TS, TS, orderId);

        reconcileService.runFor(DAY);

        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_reconcile_diff WHERE CHECK_DIMENSION='split-sum' AND BIZ_KEY='WO-SPLIT-LOW' AND DIFF_TYPE=2",
                Integer.class), "售水分账少记也必须被发现，不能只拦截超额分账");
    }

    @Test
    void intermediateIncomeAfterBreakIsDetected() {
        jdbc.update("INSERT INTO ws_income_account (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, USER_ID, BALANCE_FEN, FROZEN_FEN, VERSION) VALUES (0,1,?,1,?,1001,300,0,1)", TS, TS);
        jdbc.update("INSERT INTO ws_income_flow (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, USER_ID, FLOW_TYPE, AMOUNT_FEN, AFTER_FEN, BIZ_IDEMPOTENCY_KEY) VALUES (0,1,?,1,?,1001,1,100,999,'INCOME:broken-1'),(0,1,?,1,?,1001,1,200,300,'INCOME:broken-2')", TS, TS, TS, TS);

        reconcileService.runFor(DAY);

        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_reconcile_diff WHERE CHECK_DIMENSION='income-ledger' AND BIZ_KEY LIKE 'income:1001:%' AND DIFF_TYPE=4",
                Integer.class), "末笔余额正确也不能掩盖中间 AFTER 断链");
    }

    @Test
    void intermediateCardAfterBreakIsDetected() {
        long cardId = seedCard(300, 0);
        jdbc.update("INSERT INTO ws_wallet_flow (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, CARD_ID, USER_ID, FLOW_TYPE, AMOUNT_CHANGE, ML_CHANGE, AMOUNT_AFTER, ML_AFTER, BIZ_IDEMPOTENCY_KEY) VALUES (0,1,?,1,?,?,1001,5,100,0,999,0,'ADJUST:broken-1'),(0,1,?,1,?,?,1001,5,200,0,300,0,'ADJUST:broken-2')", TS, TS, cardId, TS, TS, cardId);

        reconcileService.runFor(DAY);

        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_reconcile_diff WHERE CHECK_DIMENSION='card-ledger' AND BIZ_KEY LIKE CONCAT('card:', ?, ':%') AND DIFF_TYPE=4",
                Integer.class, cardId), "末笔余额正确也不能掩盖水卡中间 AFTER 断链");
    }

    @Test
    void concurrentFirstRunAllowsOnlyOneExecutor() throws Exception {
        jdbc.execute("CREATE TRIGGER slow_reconcile_insert BEFORE INSERT ON ws_reconcile_task FOR EACH ROW DO SLEEP(1)");
        int workers = 6;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        reconcileService.runFor("20260730");
                        return true;
                    }
                    catch (JbkException expected) {
                        return false;
                    }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "并发线程应全部就绪");
            start.countDown();
            int succeeded = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(15, TimeUnit.SECONDS)) {
                    succeeded++;
                }
            }
            assertEquals(1, succeeded, "同账期首次并发触发只能有一个执行者");
        }
        finally {
            executor.shutdownNow();
        }
    }
}
