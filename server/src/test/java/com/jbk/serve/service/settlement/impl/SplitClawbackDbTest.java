package com.jbk.serve.service.settlement.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.aftersale.WsAfterSaleActionMapper;
import com.jbk.serve.mapper.delivery.WsDeliveryTaskMapper;
import com.jbk.serve.mapper.settlement.WsIncomeAccountMapper;
import com.jbk.serve.mapper.settlement.WsIncomeFlowMapper;
import com.jbk.serve.mapper.settlement.WsSplitClawbackActionMapper;
import com.jbk.serve.mapper.settlement.WsSplitClawbackMapper;
import com.jbk.serve.mapper.settlement.WsSplitConfigMapper;
import com.jbk.serve.mapper.settlement.WsSplitRecordMapper;
import com.jbk.serve.service.delivery.impl.DeliveryDbSchema;
import com.jbk.serve.service.settlement.IIncomeService;
import com.jbk.serve.service.settlement.ISplitClawbackTxService;
import com.jbk.serve.service.settlement.ISplitService;
import com.jbk.tool.consts.settlement.SettlementEnum;
import com.jbk.tool.data.aftersale.po.WsAfterSaleAction;
import com.jbk.tool.data.settlement.po.WsSplitRecord;
import com.jbk.tool.exception.JbkException;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * D-420 R2 分润冲减真库回归（两段式：动作级 outbox）。
 *
 * <p>业务源=售后动作：登记（段1）单行落动作级 outbox（零分账读、不可失败），
 * 执行（段2）walk processAction/Worker——权威复核→重算期望明细→对账→动账。
 * 基数=REFUND_PRODUCT_FEN 实际退款额，分摊=行原始份额比例向下取整+平台吃余数；
 * 多次部分退款按动作各自登记、REVERSED_AMOUNT 累计；重放走不可变参数等价核验，
 * 证据不合格=整动作转人工、零资金/份额更新。真实生产入口链见
 * {@link SplitClawbackProductionChainDbTest}。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringJUnitConfig(SplitClawbackDbTest.Ctx.class)
class SplitClawbackDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static Object unwrap(Object proxy) {
        try {
            return org.springframework.test.util.AopTestUtils.getTargetObject(proxy);
        } catch (Exception e) {
            return proxy;
        }
    }

    private static final long OWNER = 9L;
    private static final long COURIER = 8L;
    private static final String NOW = "20260808120000";

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
            com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean factory =
                    new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();
            factory.setDataSource(ds);
            com.baomidou.mybatisplus.core.MybatisConfiguration cfg =
                    new com.baomidou.mybatisplus.core.MybatisConfiguration();
            cfg.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(cfg);
            com.baomidou.mybatisplus.core.config.GlobalConfig globalConfig =
                    new com.baomidou.mybatisplus.core.config.GlobalConfig();
            globalConfig.setMetaObjectHandler(new com.jbk.tool.config.system.mybatis.MpMetaObjectHandler());
            factory.setGlobalConfig(globalConfig);
            factory.setMapperLocations(new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                    .getResources("classpath:mapper/*/*.xml"));
            return new SqlSessionTemplate(factory.getObject());
        }

        private static <M> MapperFactoryBean<M> mapper(Class<M> type, SqlSessionTemplate template) {
            MapperFactoryBean<M> bean = new MapperFactoryBean<>(type);
            bean.setSqlSessionTemplate(template);
            return bean;
        }

        @Bean
        MapperFactoryBean<WsSplitRecordMapper> wsSplitRecordMapper(SqlSessionTemplate t) {
            return mapper(WsSplitRecordMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsSplitClawbackMapper> wsSplitClawbackMapper(SqlSessionTemplate t) {
            return mapper(WsSplitClawbackMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsSplitClawbackActionMapper> wsSplitClawbackActionMapper(SqlSessionTemplate t) {
            return mapper(WsSplitClawbackActionMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsSplitConfigMapper> wsSplitConfigMapper(SqlSessionTemplate t) {
            return mapper(WsSplitConfigMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsIncomeAccountMapper> wsIncomeAccountMapper(SqlSessionTemplate t) {
            return mapper(WsIncomeAccountMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsIncomeFlowMapper> wsIncomeFlowMapper(SqlSessionTemplate t) {
            return mapper(WsIncomeFlowMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsAfterSaleActionMapper> wsAfterSaleActionMapper(SqlSessionTemplate t) {
            return mapper(WsAfterSaleActionMapper.class, t);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.trade.WsOrderMapper> wsOrderMapper(SqlSessionTemplate t) {
            // R3-P1-1：执行段读权威订单做来源分类
            return mapper(com.jbk.serve.mapper.trade.WsOrderMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsDeliveryTaskMapper> wsDeliveryTaskMapper(SqlSessionTemplate t) {
            return mapper(WsDeliveryTaskMapper.class, t);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.delivery.WsDeliveryAppealMapper> wsDeliveryAppealMapper(
                SqlSessionTemplate t) {
            // R4-P1-1：来源共键校验器需要申诉实体当前读
            return mapper(com.jbk.serve.mapper.delivery.WsDeliveryAppealMapper.class, t);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.device.WsDeviceMapper> wsDeviceMapper(SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.device.WsDeviceMapper.class, t);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.station.WsStationMapper> wsStationMapper(SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.station.WsStationMapper.class, t);
        }

        @Bean
        ISplitService splitService() {
            return new SplitServiceImpl();
        }

        @Bean
        IIncomeService incomeService() {
            return new IncomeServiceImpl();
        }

        @Bean
        ISplitClawbackTxService splitClawbackTxService() {
            return new SplitClawbackTxServiceImpl();
        }

        @Bean
        SplitClawbackWorker splitClawbackWorker() {
            return new SplitClawbackWorker();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    @Autowired
    private ISplitService splitService;
    @Autowired
    private IIncomeService incomeService;
    @Autowired
    private ISplitClawbackTxService clawbackService;
    @Autowired
    private SplitClawbackWorker worker;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        org.springframework.test.util.ReflectionTestUtils.setField(
                unwrap(splitService), "freezeDays", 0);
        DeliveryDbSchema.createAll(jdbc);
        DeliveryDbSchema.truncateAll(jdbc);
        jdbc.execute("TRUNCATE TABLE ws_after_sale_action");
        seedConfig(1, 1, 7000, "20260101000000");
        seedConfig(2, 1, 6000, "20260101000000");
        seedConfig(2, 2, 3000, "20260101000000");
    }

    private void seedConfig(int line, int receiver, int rate, String effect) {
        jdbc.update("INSERT INTO ws_split_config (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY,"
                + " UPDATE_TIME, PRODUCT_LINE, RECEIVER_TYPE, SPLIT_RATE, EFFECT_TIME)"
                + " VALUES (0,1,'20260101000000',1,'20260101000000',?,?,?,?)",
                line, receiver, rate, effect);
    }

    /**
     * 成功态售后动作（真实动作行：冲减执行段会锁内复核其状态、冻结参数与来源共键）。
     * 默认=待接单取消（直锚来源，sourceId 恒等 orderId，售后号确定性派生）——
     * 配送单单动作场景的共键一致形态；多动作/申诉场景用 {@link #seedAppealAction}。
     */
    private WsAfterSaleAction seedSuccessAction(long id, long orderId, int actionType,
                                                long productFen, long serviceFen, long productMl) {
        return seedSuccessAction(id, orderId, 1, orderId, actionType,
                productFen, serviceFen, productMl);
    }

    private WsAfterSaleAction seedSuccessAction(long id, long orderId, int sourceType, long sourceId,
                                                int actionType, long productFen, long serviceFen,
                                                long productMl) {
        return seedSuccessAction(id, orderId, sourceType, sourceId,
                com.jbk.serve.service.aftersale.AfterSaleNo.derive(sourceType, sourceId),
                actionType, productFen, serviceFen, productMl);
    }

    /** 最底层种子：售后号可显式传入（伪造号场景专用，常规调用一律确定性派生）。 */
    private WsAfterSaleAction seedSuccessAction(long id, long orderId, int sourceType, long sourceId,
                                                String afterSaleNo, int actionType, long productFen,
                                                long serviceFen, long productMl) {
        jdbc.update("INSERT INTO ws_after_sale_action (ID, DATA_STATUS, CREATE_BY, CREATE_TIME,"
                + " UPDATE_BY, UPDATE_TIME, AFTER_SALE_NO, SOURCE_TYPE, SOURCE_ID, ACTION_TYPE,"
                + " ORDER_ID, USER_ID, CARD_ID, REFUND_PRODUCT_FEN, REFUND_SERVICE_FEN,"
                + " REFUND_PRODUCT_ML, REFUND_AMOUNT, ACTION_STATUS, VERSION)"
                + " VALUES (?, 0, 1, ?, 1, ?, ?, ?, ?, ?, ?, 9, 31, ?, ?, ?, ?, 3, 1)",
                id, NOW, NOW, afterSaleNo, sourceType, sourceId, actionType, orderId,
                productFen, serviceFen, productMl, productFen + serviceFen);
        WsAfterSaleAction action = new WsAfterSaleAction();
        action.setId(id);
        action.setOrderId(orderId);
        action.setActionType(actionType);
        action.setRefundProductFen(productFen);
        return action;
    }

    /** 申诉来源动作：申诉实体三共键（订单/用户/任务）一并种齐——多动作同订单的合法形态。 */
    private WsAfterSaleAction seedAppealAction(long id, long orderId, long appealId, int actionType,
                                               long productFen) {
        jdbc.update("INSERT INTO ws_delivery_appeal (ID, DATA_STATUS, CREATE_BY, CREATE_TIME,"
                + " UPDATE_BY, UPDATE_TIME, TASK_ID, ORDER_ID, USER_ID, APPEAL_REASON, APPEAL_STATUS)"
                + " VALUES (?, 0, 9, ?, 9, ?, ?, ?, 9, '少水', 3)",
                appealId, NOW, NOW, taskIdOf(orderId), orderId);
        return seedSuccessAction(id, orderId, 2, appealId, actionType, productFen, 0, 0);
    }

    private long taskIdOf(long orderId) {
        return jdbc.queryForObject(
                "SELECT ID FROM ws_delivery_task WHERE ORDER_ID=?", Long.class, orderId);
    }

    /** 权威订单行（R3-P1-1 来源分类锚）：orderType 1取水 2购卡充值 3配送。 */
    private void seedOrder(long orderId, int orderType) {
        jdbc.update("INSERT INTO ws_order (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY,"
                + " UPDATE_TIME, ORDER_NO, ORDER_TYPE, USER_ID, ORDER_AMOUNT, PAY_WAY, ORDER_STATUS)"
                + " VALUES (?, 0, 1, ?, 1, ?, ?, ?, 9, 3000, 2, 7)",
                orderId, NOW, NOW, "OD-CLW-" + orderId, orderType);
    }

    private void seedDeliveryTask(long orderId, long waterFen, long feeFen) {
        jdbc.update("INSERT INTO ws_delivery_task (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY,"
                + " UPDATE_TIME, TASK_NO, ORDER_ID, USER_ID, STATION_ID, DELIVERY_COUNT,"
                + " PLAN_RETURN_COUNT, WATER_AMOUNT, DELIVERY_FEE, TASK_STATUS, VERSION)"
                + " VALUES (0,1,'20260731120000',1,'20260731120000',?,?,7,1,2,0,?,?,5,1)",
                "DT-" + orderId, orderId, waterFen, feeFen);
    }

    /** 构造既有明细行（对账/篡改场景专用——R2 常态下明细由执行段生成）。 */
    private void seedFact(long actionId, long orderId, long splitId, long amount) {
        jdbc.update("INSERT INTO ws_split_clawback (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY,"
                + " UPDATE_TIME, ACTION_ID, ORDER_ID, SPLIT_ID, CLAWBACK_AMOUNT, CLAWBACK_STATUS)"
                + " VALUES (0,1,?,1,?,?,?,?,?,1)", NOW, NOW, actionId, orderId, splitId, amount);
    }

    private List<WsSplitRecord> rowsOf(long orderId) {
        return splitService.list(Wrappers.lambdaQuery(WsSplitRecord.class)
                .eq(WsSplitRecord::getOrderId, orderId).orderByAsc(WsSplitRecord::getId));
    }

    private void settleAll(long orderId) {
        rowsOf(orderId).forEach(r -> splitService.settleOne(r.getId()));
    }

    private long balanceOf(long userId) {
        Long v = jdbc.queryForObject(
                "SELECT BALANCE_FEN FROM ws_income_account WHERE USER_ID=?", Long.class, userId);
        return v == null ? 0L : v;
    }

    private long deficitOf(long userId) {
        Long v = jdbc.queryForObject(
                "SELECT CLAWBACK_DEFICIT_FEN FROM ws_income_account WHERE USER_ID=?", Long.class, userId);
        return v == null ? 0L : v;
    }

    private long reversedOf(long orderId, long receiver) {
        return jdbc.queryForObject(
                "SELECT REVERSED_AMOUNT FROM ws_split_record WHERE ORDER_ID=? AND RECEIVER_USER_ID=?",
                Long.class, orderId, receiver);
    }

    private long splitIdOf(long orderId, long receiver) {
        return jdbc.queryForObject(
                "SELECT ID FROM ws_split_record WHERE ORDER_ID=? AND RECEIVER_USER_ID=?",
                Long.class, orderId, receiver);
    }

    private long platformSplitIdOf(long orderId) {
        return jdbc.queryForObject(
                "SELECT ID FROM ws_split_record WHERE ORDER_ID=? AND RECEIVER_TYPE=3", Long.class, orderId);
    }

    private int outboxStatus(long actionId) {
        return jdbc.queryForObject(
                "SELECT OUTBOX_STATUS FROM ws_split_clawback_action WHERE ACTION_ID=?", Integer.class, actionId);
    }

    private String outboxRemark(long actionId) {
        return jdbc.queryForObject(
                "SELECT PROCESS_REMARK FROM ws_split_clawback_action WHERE ACTION_ID=?", String.class, actionId);
    }

    private long outboxCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ws_split_clawback_action", Long.class);
    }

    private long factCount(long actionId, int status) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_split_clawback WHERE ACTION_ID=? AND CLAWBACK_STATUS=?",
                Long.class, actionId, status);
    }

    /** 建一张已结算的配送分线单：水 2600（机主 70%=1820）＋费 400（机主 60%/配送员 30%）。 */
    private void seedSettledDeliveryOrder(long orderId) {
        seedOrder(orderId, 3);
        seedDeliveryTask(orderId, 2600, 400);
        splitService.enqueueForDeliveryOrder(orderId, "DO-CLW-" + orderId, 2600, 400,
                OWNER, COURIER, "20260731120000");
        settleAll(orderId);
    }

    /** 场景1：CARD_REFUND 成功→登记单行 outbox（零明细）→执行段生成明细并扣回。 */
    @Test
    void cardRefundActionProducesClawbackFacts() {
        seedSettledDeliveryOrder(301L);
        assertEquals(2060L, balanceOf(OWNER));
        WsAfterSaleAction action = seedSuccessAction(3001L, 301L, 1, 1000, 0, 0);

        clawbackService.registerForAction(action, NOW);
        assertEquals(1, outboxStatus(3001L), "登记=单行动作级 outbox（待处理）");
        assertEquals(0L, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_split_clawback WHERE ACTION_ID=3001", Long.class),
                "登记段零明细——分摊/校验全在执行段（R2-P0-1）");

        assertEquals(1, worker.runOnce(NOW), "Worker 扫动作级 outbox 发现并执行");
        // 水品退款 1000 只冲 1000——机主 1820*1000/2600=700、平台=300
        assertEquals(700L, reversedOf(301L, OWNER), "机主按份额比例冲 700");
        assertEquals(2060L - 700L, balanceOf(OWNER), "已结算份额从收益扣回");
        assertEquals(300L, jdbc.queryForObject(
                "SELECT REVERSED_AMOUNT FROM ws_split_record WHERE ORDER_ID=301 AND RECEIVER_TYPE=3",
                Long.class), "平台吃舍入余数 300");
        assertEquals(0L, reversedOf(301L, COURIER), "配送员分毫不动");
        assertEquals(2L, factCount(3001L, 2), "机主+平台两行明细由执行段生成并完成");
        assertEquals(2, outboxStatus(3001L), "outbox 推进已完成");
    }

    /** 场景2：GATEWAY_REFUND 走同一冲减机制（登记+processAction 直调）。 */
    @Test
    void gatewayRefundWalksSameClawbackPath() {
        seedSettledDeliveryOrder(302L);
        WsAfterSaleAction action = seedSuccessAction(3002L, 302L, 3, 1000, 0, 0);
        clawbackService.registerForAction(action, NOW);
        clawbackService.processAction(3002L, NOW);
        assertEquals(700L, reversedOf(302L, OWNER));
        assertEquals(2060L - 700L, balanceOf(OWNER));
        assertEquals(2, outboxStatus(3002L));
    }

    /** 场景3：CARD_COMPENSATE 补偿不冲减（登记 no-op，连 outbox 都不落）。 */
    @Test
    void compensationNeverClawsBack() {
        seedSettledDeliveryOrder(303L);
        WsAfterSaleAction action = seedSuccessAction(3003L, 303L, 2, 1000, 0, 0);
        clawbackService.registerForAction(action, NOW);
        assertEquals(0L, outboxCount(), "补偿零登记");
        assertEquals(0, worker.runOnce(NOW));
        assertEquals(2060L, balanceOf(OWNER), "收益分毫不动");
    }

    /** 场景5：只退配送费（REFUND_PRODUCT_FEN=0）——水费线冲减必须为 0。 */
    @Test
    void serviceFeeOnlyRefundClawsNothing() {
        seedSettledDeliveryOrder(304L);
        WsAfterSaleAction action = seedSuccessAction(3004L, 304L, 1, 0, 400, 0);
        clawbackService.registerForAction(action, NOW);
        assertEquals(0L, outboxCount());
        assertEquals(0L, reversedOf(304L, OWNER));
        assertEquals(2060L, balanceOf(OWNER));
    }

    /** 场景6（R1-1.6）：纯水量返还（金额 0、水量>0）零货币冲减。 */
    @Test
    void waterMlOnlyRefundClawsNothing() {
        seedSettledDeliveryOrder(305L);
        WsAfterSaleAction action = seedSuccessAction(3005L, 305L, 1, 0, 0, 5000);
        clawbackService.registerForAction(action, NOW);
        assertEquals(0L, outboxCount());
        assertEquals(2060L, balanceOf(OWNER));
    }

    /** 场景7（R1-3.5）：两次部分退款 400+600 累计 1000，第二笔绝不被拦（多动作=申诉共键形态）。 */
    @Test
    void twoPartialRefundsAccumulate() {
        seedSettledDeliveryOrder(306L);
        WsAfterSaleAction first = seedAppealAction(3006L, 306L, 90061L, 1, 400);
        clawbackService.registerForAction(first, NOW);
        clawbackService.processAction(3006L, NOW);
        // 400：机主 1820*400/2600=280、平台 120
        assertEquals(280L, reversedOf(306L, OWNER));

        WsAfterSaleAction second = seedAppealAction(3007L, 306L, 90062L, 1, 600);
        clawbackService.registerForAction(second, NOW);
        clawbackService.processAction(3007L, NOW);
        // 600：机主 1820*600/2600=420、平台 180；累计 280+420=700
        assertEquals(700L, reversedOf(306L, OWNER), "两笔部分退款按行累计");
        assertEquals(300L, jdbc.queryForObject(
                "SELECT REVERSED_AMOUNT FROM ws_split_record WHERE ORDER_ID=306 AND RECEIVER_TYPE=3",
                Long.class), "平台累计 120+180=300");
        assertEquals(2060L - 700L, balanceOf(OWNER), "收益累计扣 700");
        assertEquals(4L, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_split_clawback WHERE CLAWBACK_STATUS=2", Long.class),
                "两动作各两行明细，全部完成");
    }

    /** 场景8（R2-P1-1）：<b>整额</b>退款同动作重复登记+执行 5 次——资金恰动一次。 */
    @Test
    void fullRefundReplayFiveTimesZeroSideEffect() {
        seedSettledDeliveryOrder(307L);
        WsAfterSaleAction action = seedSuccessAction(3008L, 307L, 1, 2600, 0, 0);
        for (int i = 0; i < 5; i++) {
            // R1 缺陷形状：第二轮登记时 registered(2600)+refund(2600)>share(2600) 先于撞键分支
            // 抛出——整额重放必炸。R2 撞键即做不可变参数等价核验，静默幂等
            clawbackService.registerForAction(action, NOW);
            clawbackService.processAction(3008L, NOW);
        }
        assertEquals(1820L, reversedOf(307L, OWNER), "整额冲减恰一次，重放不叠加");
        assertEquals(2060L - 1820L, balanceOf(OWNER));
        assertEquals(1L, outboxCount(), "恰一条动作事实");
        assertEquals(2L, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_split_clawback WHERE ACTION_ID=3008", Long.class),
                "明细恒两行");
        assertEquals(1L, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_income_flow WHERE BIZ_IDEMPOTENCY_KEY LIKE 'CLAWBACK:%'",
                Long.class), "恰一条扣回流水");
    }

    /** 场景9：两个动作并发执行不超冲、不覆盖证据（outbox 锁+分账行锁串行化；申诉共键形态）。 */
    @Test
    void concurrentActionsNeverOverClaw() throws Exception {
        seedSettledDeliveryOrder(308L);
        WsAfterSaleAction a1 = seedAppealAction(3009L, 308L, 90091L, 1, 400);
        WsAfterSaleAction a2 = seedAppealAction(3010L, 308L, 90092L, 1, 600);
        clawbackService.registerForAction(a1, NOW);
        clawbackService.registerForAction(a2, NOW);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.invokeAll(List.of(
                    () -> race(barrier, 3009L),
                    () -> race(barrier, 3010L)), 30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(700L, reversedOf(308L, OWNER), "并发累计恰 280+420，无超冲无覆盖");
        assertEquals(2060L - 700L, balanceOf(OWNER));
        assertEquals(4L, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_split_clawback WHERE CLAWBACK_STATUS=2", Long.class));
    }

    private boolean race(CyclicBarrier barrier, long actionId) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
            clawbackService.processAction(actionId, NOW);
            return true;
        }
        catch (JbkException legalLoss) {
            // 锁竞争/版本漂移输方：outbox 保持待处理，Worker 下轮重试兜底（此处手动重试一次模拟）
            clawbackService.processAction(actionId, NOW);
            return false;
        }
        catch (Exception infra) {
            fail("并发基建异常：" + infra.getMessage());
            return false;
        }
    }

    /** 场景10：冻结期内（PENDING）冲减后钱包显示净额；结算按净额入账。 */
    @Test
    void pendingRowsShowNetAmountInWallet() {
        org.springframework.test.util.ReflectionTestUtils.setField(unwrap(splitService), "freezeDays", 1);
        try {
            seedOrder(309L, 3);
            seedDeliveryTask(309L, 2600, 400);
            splitService.enqueueForDeliveryOrder(309L, "DO-CLW-309", 2600, 400,
                    OWNER, COURIER, "20260731120000");
            WsAfterSaleAction action = seedSuccessAction(3011L, 309L, 1, 1000, 0, 0);
            clawbackService.registerForAction(action, NOW);
            clawbackService.processAction(3011L, NOW);
            assertEquals(700L, reversedOf(309L, OWNER), "冻结期内登记累计冲减");
            assertEquals(0L, (long) jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ws_income_flow", Long.class), "冻结期内零资金动作");
            // 钱包在途=净额：机主 2060-700=1360 → 各自账户口径
            assertEquals(2060L - 700L, (long) incomeService.walletFor(OWNER).getPendingSplitFen(),
                    "在途展示净额而非毛额");
            // 满期结算：机主入净额 1360
            org.springframework.test.util.ReflectionTestUtils.setField(unwrap(splitService), "freezeDays", 0);
            jdbc.update("UPDATE ws_split_record SET CREATE_TIME='20260729000000' WHERE ORDER_ID=309");
            settleAll(309L);
            assertEquals(2060L - 700L, balanceOf(OWNER), "结算按净额入账");
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(unwrap(splitService), "freezeDays", 0);
        }
    }

    /** 场景11：已结算分润按净额扣回，余额不足形成待补差额并阻断提现。 */
    @Test
    void settledClawbackFormsDeficitWhenBalanceShort() {
        seedSettledDeliveryOrder(310L);
        incomeService.applyWithdraw(OWNER, 2000, java.util.UUID.randomUUID().toString());
        assertEquals(60L, balanceOf(OWNER), "可用只剩 60");
        WsAfterSaleAction action = seedSuccessAction(3012L, 310L, 1, 1000, 0, 0);
        clawbackService.registerForAction(action, NOW);
        clawbackService.processAction(3012L, NOW);
        assertEquals(0L, balanceOf(OWNER), "只扣可用部分，恒不为负");
        assertEquals(640L, deficitOf(OWNER), "缺口 700-60 记入待补差额");
        JbkException blocked = assertThrows(JbkException.class,
                () -> incomeService.applyWithdraw(OWNER, 1, java.util.UUID.randomUUID().toString()));
        assertTrue(blocked.getMessage().contains("暂不可提现"));
    }

    /** 场景12（R2-P0-2）：权威动作状态漂移=证据被动过——整动作转人工、零资金、不自动重试。 */
    @Test
    void authorityStatusDriftParksWholeActionManual() {
        seedSettledDeliveryOrder(311L);
        WsAfterSaleAction action = seedSuccessAction(3013L, 311L, 1, 1000, 0, 0);
        clawbackService.registerForAction(action, NOW);
        // 登记后动作状态被改回执行中：outbox 冻结参数与权威动作不再自洽
        jdbc.update("UPDATE ws_after_sale_action SET ACTION_STATUS=2 WHERE ID=3013");
        clawbackService.processAction(3013L, NOW);
        assertEquals(3, outboxStatus(3013L), "证据被动过整动作转人工——R1 在此抛出无限重试");
        assertTrue(outboxRemark(3013L).contains("不是成功状态"));
        assertEquals(0L, reversedOf(311L, OWNER), "零份额更新");
        assertEquals(2060L, balanceOf(OWNER), "零资金动作");
        // 状态恢复也不自动重试：人工件冻结，防「先转人工后自动补扣」双轨并行
        jdbc.update("UPDATE ws_after_sale_action SET ACTION_STATUS=3 WHERE ID=3013");
        assertEquals(0, worker.runOnce(NOW), "Worker 不扫人工件");
        assertEquals(3, outboxStatus(3013L));
    }

    /** 场景13（R2-P0-2.4）：明细金额被篡改——对账不符整动作转人工，两行明细一并冻结。 */
    @Test
    void tamperedFactAmountParksWholeActionZeroUpdates() {
        seedSettledDeliveryOrder(312L);
        WsAfterSaleAction bad = seedSuccessAction(3014L, 312L, 1, 100, 0, 0);
        clawbackService.registerForAction(bad, NOW);
        // 构造篡改明细集：机主行 70→99999、平台行 30 原样（期望重算 70/30）
        seedFact(3014L, 312L, splitIdOf(312L, OWNER), 99999L);
        seedFact(3014L, 312L, platformSplitIdOf(312L), 30L);
        clawbackService.processAction(3014L, NOW);
        assertEquals(3, outboxStatus(3014L), "金额与重算不符整动作转人工");
        assertTrue(outboxRemark(3014L).contains("重算不符"));
        assertEquals(2L, factCount(3014L, 3), "全部明细一并冻结——不存在部分完成部分人工");
        assertEquals(0L, reversedOf(312L, OWNER), "零份额更新");
        assertEquals(2060L, balanceOf(OWNER), "零资金动作");
    }

    /** 场景14（R2-P1-1）：重复登记参数漂移——既有登记 fail-closed 转人工，绝无第二份事实。 */
    @Test
    void reRegisterParamDriftFailsClosed() {
        seedSettledDeliveryOrder(313L);
        WsAfterSaleAction action = seedSuccessAction(3015L, 313L, 1, 1000, 0, 0);
        clawbackService.registerForAction(action, NOW);
        // 同 actionId 参数漂移重放：金额 1000→800（绝不抛出，绝不回滚调用方退款事务）
        WsAfterSaleAction drifted = new WsAfterSaleAction();
        drifted.setId(3015L);
        drifted.setOrderId(313L);
        drifted.setActionType(1);
        drifted.setRefundProductFen(800L);
        clawbackService.registerForAction(drifted, NOW);
        assertEquals(3, outboxStatus(3015L), "参数漂移置需人工");
        assertTrue(outboxRemark(3015L).contains("参数漂移"));
        assertEquals(1L, outboxCount(), "同 actionId 恒一行登记，绝无第二份事实");
        assertEquals(0, worker.runOnce(NOW), "人工件不执行");
        assertEquals(0L, reversedOf(313L, OWNER));
    }

    /** 场景15：取水订单按行金额比例分摊（来源=取水异常核账，sourceId 直锚订单）。 */
    @Test
    void waterOrderClawsProportionally() {
        seedOrder(314L, 1);
        splitService.enqueueForOrder(314L, "WO-CLW-314", 1000, SettlementEnum.ProductLine.WATER,
                OWNER, null, "20260731120000");
        settleAll(314L);
        assertEquals(700L, balanceOf(OWNER));
        WsAfterSaleAction action = seedSuccessAction(3017L, 314L, 3, 314L, 1, 500, 0, 0);
        clawbackService.registerForAction(action, NOW);
        clawbackService.processAction(3017L, NOW);
        // 500：机主 700*500/1000=350、平台 150
        assertEquals(350L, reversedOf(314L, OWNER));
        assertEquals(350L, balanceOf(OWNER));
        assertEquals(2, outboxStatus(3017L));
    }

    /** 场景16（R3-P1-1）：取水异常对应应分账取水单、但分账行缺失——转人工，零资金副作用。 */
    @Test
    void waterAbnormalMissingSplitsParksManualNotLegalZero() {
        // 取水单已存在（履约后来源理应带分账证据），但分账行整体缺失——不许收敛「合法零冲减」
        seedOrder(315L, 1);
        WsAfterSaleAction action = seedSuccessAction(3019L, 315L, 3, 315L, 1, 500, 0, 0);
        clawbackService.registerForAction(action, NOW);
        assertEquals(1, worker.runOnce(NOW), "Worker 发现并处置（转人工不抛出）");
        assertEquals(3, outboxStatus(3019L), "履约后来源分账行缺失=证据丢失，整动作转人工");
        assertTrue(outboxRemark(3019L).contains("分账行缺失"));
        assertEquals(0L, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_split_clawback WHERE ACTION_ID=3019", Long.class), "零明细");
        assertEquals(0L, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_income_flow", Long.class), "零资金动作");
    }

    /** 场景17：订单累计上限——超限第二笔登记照落、执行段转人工（多动作=申诉共键形态）。 */
    @Test
    void overLimitSecondActionGoesManual() {
        seedSettledDeliveryOrder(317L);
        WsAfterSaleAction first = seedAppealAction(3020L, 317L, 90171L, 1, 2000);
        clawbackService.registerForAction(first, NOW);
        clawbackService.processAction(3020L, NOW);
        assertEquals(1400L, reversedOf(317L, OWNER), "2000：机主 1820*2000/2600=1400");
        // 超限第二笔：累计 2000+900=2900 > 份额 2600。R1 在登记期抛出（连带回滚客户退款——
        // 正是 P0-1 驳回形状）；R2 起登记照落，执行段对账转人工，退款不受影响
        WsAfterSaleAction over = seedAppealAction(3021L, 317L, 90172L, 1, 900);
        clawbackService.registerForAction(over, NOW);
        assertEquals(1, outboxStatus(3021L), "登记不做额度校验（不可失败路径）");
        clawbackService.processAction(3021L, NOW);
        assertEquals(3, outboxStatus(3021L), "订单累计超限执行段转人工");
        assertTrue(outboxRemark(3021L).contains("超出订单水费线份额"));
        assertEquals(0L, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_split_clawback WHERE ACTION_ID=3021", Long.class),
                "超限路径零明细");
        assertEquals(1400L, reversedOf(317L, OWNER), "首笔冲减不受影响");
    }

    /** 场景18（R4-P1-1）：售后号与来源派生不一致——伪造号动作整动作转人工。 */
    @Test
    void forgedAfterSaleNoParksManual() {
        seedSettledDeliveryOrder(318L);
        // 32 位伪造号（列宽 varchar(32)）：形似派生号但与 derive(1,318) 必然不同
        WsAfterSaleAction forged = seedSuccessAction(3022L, 318L, 1, 318L,
                "ASFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF", 1, 500, 0, 0);
        clawbackService.registerForAction(forged, NOW);
        clawbackService.processAction(3022L, NOW);
        assertEquals(3, outboxStatus(3022L), "售后号非确定性派生=证据链断裂，转人工");
        assertTrue(outboxRemark(3022L).contains("售后号与来源派生不一致"));
        assertEquals(0L, reversedOf(318L, OWNER), "零份额更新");
        assertEquals(2060L, balanceOf(OWNER), "零资金动作");
    }

    /** 场景20（复验遗留加固）：非正 sourceId 损坏数据——转人工为终态，Worker 绝不空转重试。 */
    @Test
    void nonPositiveSourceIdParksManualAndWorkerStopsRetrying() {
        seedSettledDeliveryOrder(321L);
        // 加固前形态：derive 对非正 sourceId 抛出，且共键校验在执行段证据 catch 之外——
        // 执行段异常、outbox 停在待处理、Worker 每 30 秒空转重试。加固后=一次转人工即终态
        WsAfterSaleAction zero = seedSuccessAction(3025L, 321L, 1, 0L,
                "ASZEROSRCFFFFFFFFFFFFFFFFFFFFFFF", 1, 300, 0, 0);
        WsAfterSaleAction negative = seedSuccessAction(3026L, 321L, 1, -5L,
                "ASNEGSRCFFFFFFFFFFFFFFFFFFFFFFFF", 1, 300, 0, 0);
        clawbackService.registerForAction(zero, NOW);
        clawbackService.registerForAction(negative, NOW);
        assertEquals(2, worker.runOnce(NOW), "首轮：两个损坏动作都被发现并处置（转人工不抛出）");
        assertEquals(3, outboxStatus(3025L), "sourceId=0 转人工");
        assertEquals(3, outboxStatus(3026L), "sourceId=-5 转人工");
        assertTrue(outboxRemark(3025L).contains("来源主体ID非法"));
        assertTrue(outboxRemark(3026L).contains("来源主体ID非法"));
        assertEquals(0, worker.runOnce(NOW), "次轮：人工件不再被扫描——零空转重试");
        assertEquals(0L, reversedOf(321L, OWNER), "零份额更新");
        assertEquals(0L, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_income_flow WHERE BIZ_IDEMPOTENCY_KEY LIKE 'CLAWBACK:%'",
                Long.class), "零资金动作");
    }

    /** 场景21（复验遗留加固）：任务已取消但带派单/履约痕迹——待接单取消终态不成立，转人工。 */
    @Test
    void cancelledTaskWithFulfillmentTracesParksManual() {
        // 配送员痕迹：任务状态 6 已取消，但 COURIER_ID 在场（取消事务以 COURIER_ID IS NULL
        // 为前态守卫，正常取消不可能留下配送员）——零分账收敛不放行
        seedOrder(322L, 3);
        seedDeliveryTask(322L, 2600, 400);
        jdbc.update("UPDATE ws_delivery_task SET TASK_STATUS=6, COURIER_ID=8 WHERE ORDER_ID=322");
        WsAfterSaleAction courierTrace = seedSuccessAction(3027L, 322L, 1, 300, 0, 0);
        clawbackService.registerForAction(courierTrace, NOW);
        clawbackService.processAction(3027L, NOW);
        assertEquals(3, outboxStatus(3027L), "已挂配送员的取消任务不是待接单取消");
        assertTrue(outboxRemark(3027L).contains("已挂配送员"));

        // 履约时间痕迹：配送员为空但 ACCEPT_TIME 在场——同样不放行
        seedOrder(323L, 3);
        seedDeliveryTask(323L, 2600, 400);
        jdbc.update("UPDATE ws_delivery_task SET TASK_STATUS=6, ACCEPT_TIME='20260808110000'"
                + " WHERE ORDER_ID=323");
        WsAfterSaleAction timeTrace = seedSuccessAction(3028L, 323L, 1, 300, 0, 0);
        clawbackService.registerForAction(timeTrace, NOW);
        clawbackService.processAction(3028L, NOW);
        assertEquals(3, outboxStatus(3028L), "带履约时间痕迹的取消任务不放行");
        assertTrue(outboxRemark(3028L).contains("履约时间痕迹"));
        assertEquals(0L, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_income_flow", Long.class), "两案零资金动作");
    }

    /** 场景19（R4-P1-1）：取水异常/充值退款 SOURCE_ID 与订单错位——分别转人工。 */
    @Test
    void orderAnchoredSourceIdMismatchParksManual() {
        // 取水异常：sourceId=999 ≠ orderId=319
        seedOrder(319L, 1);
        WsAfterSaleAction water = seedSuccessAction(3023L, 319L, 3, 999L, 1, 500, 0, 0);
        clawbackService.registerForAction(water, NOW);
        clawbackService.processAction(3023L, NOW);
        assertEquals(3, outboxStatus(3023L), "取水异常 SOURCE_ID 错位转人工");
        assertTrue(outboxRemark(3023L).contains("来源主体与订单不一致"));
        // 充值退款：sourceId=998 ≠ orderId=320（购卡充值单）
        seedOrder(320L, 2);
        WsAfterSaleAction recharge = seedSuccessAction(3024L, 320L, 4, 998L, 3, 500, 0, 0);
        clawbackService.registerForAction(recharge, NOW);
        clawbackService.processAction(3024L, NOW);
        assertEquals(3, outboxStatus(3024L), "充值退款 SOURCE_ID 错位转人工");
        assertTrue(outboxRemark(3024L).contains("来源主体与订单不一致"));
        assertEquals(0L, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_income_flow", Long.class), "两案零资金动作");
    }
}
