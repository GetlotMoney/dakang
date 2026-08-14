package com.jbk.serve.service.settlement.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.aftersale.WsAfterSaleActionMapper;
import com.jbk.serve.mapper.aftersale.WsCardEntitlementBatchMapper;
import com.jbk.serve.mapper.aftersale.WsEntitlementAllocationMapper;
import com.jbk.serve.mapper.ops.WsDomainEventMapper;
import com.jbk.serve.mapper.settlement.WsIncomeAccountMapper;
import com.jbk.serve.mapper.settlement.WsIncomeFlowMapper;
import com.jbk.serve.mapper.settlement.WsSplitClawbackActionMapper;
import com.jbk.serve.mapper.settlement.WsSplitClawbackMapper;
import com.jbk.serve.mapper.settlement.WsSplitConfigMapper;
import com.jbk.serve.mapper.settlement.WsSplitRecordMapper;
import com.jbk.serve.mapper.trade.TradeCardMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.service.aftersale.AfterSaleNo;
import com.jbk.serve.service.aftersale.IAfterSaleActionTxService;
import com.jbk.serve.service.aftersale.batch.EntitlementFixture;
import com.jbk.serve.service.aftersale.batch.EntitlementLedger;
import com.jbk.serve.service.aftersale.impl.AfterSaleActionTxServiceImpl;
import com.jbk.serve.service.delivery.impl.DeliveryDbSchema;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.serve.service.ops.impl.WsDomainEventServiceImpl;
import com.jbk.serve.service.settlement.IIncomeService;
import com.jbk.serve.service.settlement.ISplitClawbackTxService;
import com.jbk.serve.service.settlement.ISplitService;
import com.jbk.tool.consts.aftersale.AfterSaleEnum.ActionStatus;
import com.jbk.tool.consts.aftersale.AfterSaleEnum.ActionType;
import com.jbk.tool.consts.aftersale.AfterSaleEnum.SourceType;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.data.aftersale.po.WsAfterSaleAction;
import com.jbk.tool.data.settlement.po.WsSplitRecord;
import com.jbk.tool.exception.JbkException;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
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
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * D-420 R2-P1-2：分润冲减<b>真实生产入口</b>真库集成（真实 MySQL + 真实 Spring 事务 +
 * 真实 AfterSaleActionTxServiceImpl / SplitClawbackTxServiceImpl / SplitClawbackWorker，
 * 冲减服务不打任何 Mock）。
 *
 * <p>钉住 R2 驳回的三条资金级性质：</p>
 * <ol>
 *   <li><b>登记不可失败（P0-1）</b>：客户退款事务内只落一条动作级 outbox；比例快照
 *       损坏等证据异常下退款照常成功、卡与流水不回滚，冲减在执行段转人工。</li>
 *   <li><b>执行以动作为权威（P0-2）</b>：Worker 扫动作级 outbox——明细缺失/被改
 *       照样发现动作；对账不合格=整动作转人工、零资金/份额更新。</li>
 *   <li><b>重放幂等（P1-1）</b>：同动作整额退款重复登记/执行 N 次资金恰动一次；
 *       参数漂移 fail-closed 转人工。</li>
 * </ol>
 *
 * <p>无 Docker 环境自动跳过。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringJUnitConfig(SplitClawbackProductionChainDbTest.Ctx.class)
class SplitClawbackProductionChainDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static final long USER_ID = 9L;
    private static final long OWNER = 9L;
    private static final long COURIER = 8L;
    private static final long OP_USER = 9L;
    private static final long CARD_ID = 100L;
    private static final long INIT_BALANCE = 10_000L;
    private static final long INIT_ML = 50_000L;
    private static final String NOW = "20260808120000";

    private static Object unwrap(Object proxy) {
        try {
            return org.springframework.test.util.AopTestUtils.getTargetObject(proxy);
        } catch (Exception e) {
            return proxy;
        }
    }

    @Configuration
    @EnableTransactionManagement
    static class Ctx {

        /**
         * 绑号闸放行版：最小 schema 无 ws_user 表。闸本身由 MiniPhoneGateTest /
         * PhoneGateAnchorContractTest / MiniPhoneGateChainDbTest 专门覆盖。
         */
        @Bean
        com.jbk.serve.service.mini.auth.MiniPhoneGate miniPhoneGate() {
            com.jbk.serve.mapper.user.WsUserIdentityMapper m =
                    Mockito.mock(com.jbk.serve.mapper.user.WsUserIdentityMapper.class);
            Mockito.when(m.selectPhoneByIdIncludingDeleted(Mockito.anyLong()))
                    .thenReturn("13900000000");
            return new com.jbk.serve.service.mini.auth.MiniPhoneGate(m);
        }
        @Bean
        DataSource dataSource() {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl(MYSQL.getJdbcUrl());
            ds.setUsername(MYSQL.getUsername());
            ds.setPassword(MYSQL.getPassword());
            // 并发用例：2 线程 ×（执行 REQUIRES_NEW + 嵌套读）各需独立连接
            ds.setMaximumPoolSize(12);
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
            // 生产同一份 XML：售后 CAS、分账锁定读全是被测物本身
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath:mapper/*/*.xml"));
            return new SqlSessionTemplate(factory.getObject());
        }

        private static <M> MapperFactoryBean<M> mapper(Class<M> type, SqlSessionTemplate template) {
            MapperFactoryBean<M> bean = new MapperFactoryBean<>(type);
            bean.setSqlSessionTemplate(template);
            return bean;
        }


        @Bean
        MapperFactoryBean<WsAfterSaleActionMapper> wsAfterSaleActionMapper(SqlSessionTemplate t) {
            return mapper(WsAfterSaleActionMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsOrderMapper> wsOrderMapper(SqlSessionTemplate t) {
            return mapper(WsOrderMapper.class, t);
        }

        @Bean
        MapperFactoryBean<TradeCardMapper> tradeCardMapper(SqlSessionTemplate t) {
            return mapper(TradeCardMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsWalletFlowMapper> wsWalletFlowMapper(SqlSessionTemplate t) {
            return mapper(WsWalletFlowMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsDomainEventMapper> wsDomainEventMapper(SqlSessionTemplate t) {
            return mapper(WsDomainEventMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsCardEntitlementBatchMapper> wsCardEntitlementBatchMapper(SqlSessionTemplate t) {
            return mapper(WsCardEntitlementBatchMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsEntitlementAllocationMapper> wsEntitlementAllocationMapper(SqlSessionTemplate t) {
            return mapper(WsEntitlementAllocationMapper.class, t);
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
        MapperFactoryBean<com.jbk.serve.mapper.delivery.WsDeliveryTaskMapper> wsDeliveryTaskMapper(
                SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.delivery.WsDeliveryTaskMapper.class, t);
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
        IWsDomainEventService domainEventService() {
            // 真实现：AFTERSALE_DONE 审计与退款同事务是链路的一环
            return new WsDomainEventServiceImpl();
        }

        @Bean
        EntitlementLedger entitlementLedger(WsCardEntitlementBatchMapper b, WsEntitlementAllocationMapper a,
                                            TradeCardMapper card, WsWalletFlowMapper flow) {
            return new EntitlementLedger(b, a, card, flow);
        }

        // V2 依赖（本 Ctx 跑 V1 口径：开关默认 false，V2 路径不触发，仅满足装配）
        @Bean
        com.jbk.serve.service.settlement.ISplitPlanService splitPlanService() {
            com.jbk.serve.service.settlement.ISplitPlanService mock =
                    Mockito.mock(com.jbk.serve.service.settlement.ISplitPlanService.class);
            Mockito.when(mock.activePlanAt(Mockito.anyString()))
                    .thenReturn(java.util.Optional.empty());
            return mock;
        }

        @Bean
        com.jbk.serve.service.settlement.IOwnerAttributionService ownerAttributionService() {
            return Mockito.mock(com.jbk.serve.service.settlement.IOwnerAttributionService.class);
        }

        @Bean
        com.jbk.serve.mapper.settlement.WsSplitComponentMapper wsSplitComponentMapper() {
            return Mockito.mock(com.jbk.serve.mapper.settlement.WsSplitComponentMapper.class);
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
            // 本类的关键：冲减服务是真实现，不打 Mock
            return new SplitClawbackTxServiceImpl();
        }

        @Bean
        SplitClawbackWorker splitClawbackWorker() {
            return new SplitClawbackWorker();
        }

        @Bean
        IAfterSaleActionTxService afterSaleActionTxService(WsAfterSaleActionMapper actionMapper,
                                                           WsOrderMapper orderMapper,
                                                           TradeCardMapper tradeCardMapper,
                                                           WsWalletFlowMapper walletFlowMapper,
                                                           IWsDomainEventService domainEventService,
                                                           EntitlementLedger entitlementLedger,
                                                           ISplitClawbackTxService clawbackTxService) {
            // 真实卡退款事务服务 + 真实冲减登记（同事务）——P1-2 驳回点就是这里曾是 Mock
            return new AfterSaleActionTxServiceImpl(actionMapper, orderMapper, tradeCardMapper,
                    walletFlowMapper, domainEventService, entitlementLedger, clawbackTxService);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    @Autowired
    private IAfterSaleActionTxService afterSale;
    @Autowired
    private ISplitService splitService;
    @Autowired
    private ISplitClawbackTxService clawbackService;
    @Autowired
    private SplitClawbackWorker worker;
    @Autowired
    private JdbcTemplate jdbc;

    // ------------------------------------------------------------------
    // 基座
    // ------------------------------------------------------------------

    @BeforeEach
    void reset() {
        org.springframework.test.util.ReflectionTestUtils.setField(
                unwrap(splitService), "freezeDays", 0);
        DeliveryDbSchema.createAll(jdbc);
        DeliveryDbSchema.truncateAll(jdbc);
        seedConfig(1, 1, 7000);
        seedConfig(2, 1, 6000);
        seedConfig(2, 2, 3000);
        seedCard();
    }

    private void seedConfig(int line, int receiver, int rate) {
        jdbc.update("INSERT INTO ws_split_config (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY,"
                        + " UPDATE_TIME, PRODUCT_LINE, RECEIVER_TYPE, SPLIT_RATE, EFFECT_TIME)"
                        + " VALUES (0,1,'20260101000000',1,'20260101000000',?,?,?,'20260101000000')",
                line, receiver, rate);
    }

    private void seedCard() {
        jdbc.update("INSERT INTO ws_card(ID,DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,"
                        + "CARD_NO,CARD_TYPE,USER_ID,BALANCE_AMOUNT,BALANCE_ML,CARD_STATUS) "
                        + "VALUES(?,0,1,?,1,?,'VC-CLW-100',1,?,?,?,1)",
                CARD_ID, NOW, NOW, USER_ID, INIT_BALANCE, INIT_ML);
        EntitlementFixture.seedLegacyBatch(jdbc, CARD_ID, USER_ID, INIT_BALANCE, INIT_ML);
    }

    /** payWay=2 配送订单：水费 2600（余额扣）+ 配送费 400，原扣款 -3000 分。 */
    private void seedBalanceOrder(long orderId) {
        String snap = "{\"payWay\":2,\"waterAmountFen\":2600,\"deliveryFeeFen\":400,\"waterMl\":40000,"
                + "\"deliveryCount\":2,\"unitWaterPriceFen\":1300,\"deliveryFeePerContainerFen\":200,"
                + "\"priceWaterAmountFen\":2600}";
        String orderNo = "WD2026080800000000000000000" + orderId;
        jdbc.update("INSERT INTO ws_order(ID,DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,"
                        + "ORDER_NO,ORDER_TYPE,USER_ID,CARD_ID,PACKAGE_SNAP,ORDER_AMOUNT,PAY_WAY,ORDER_STATUS) "
                        + "VALUES(?,0,1,?,1,?,?,?,?,?,?,3000,?,7)",
                orderId, NOW, NOW, orderNo, TradeEnum.OrderType.DELIVERY.getValue(),
                USER_ID, CARD_ID, snap, TradeEnum.PayWay.CARD_BALANCE.getValue());
        jdbc.update("INSERT INTO ws_wallet_flow(DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,"
                        + "CARD_ID,USER_ID,FLOW_TYPE,AMOUNT_CHANGE,ML_CHANGE,AMOUNT_AFTER,ML_AFTER,"
                        + "ORDER_ID,FLOW_REMARK,BIZ_IDEMPOTENCY_KEY) VALUES(0,1,?,1,?,?,?,?,-3000,0,?,?,?,?,?)",
                NOW, NOW, CARD_ID, USER_ID, TradeEnum.FlowType.DELIVERY_CONSUME.getValue(),
                INIT_BALANCE, INIT_ML, orderId, "配送扣减 " + orderNo, "DELIVERY:" + orderNo);
    }

    /** 配送任务行（状态可指定：5已签收/6已取消），返回任务 ID（申诉共键用）。 */
    private long seedTask(long orderId, int taskStatus) {
        jdbc.update("INSERT INTO ws_delivery_task (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY,"
                        + " UPDATE_TIME, TASK_NO, ORDER_ID, USER_ID, STATION_ID, DELIVERY_COUNT,"
                        + " PLAN_RETURN_COUNT, WATER_AMOUNT, DELIVERY_FEE, TASK_STATUS, VERSION)"
                        + " VALUES (0,1,?,1,?,?,?,?,1,2,0,2600,400,?,1)",
                NOW, NOW, "DT-" + orderId, orderId, USER_ID, taskStatus);
        return jdbc.queryForObject(
                "SELECT ID FROM ws_delivery_task WHERE ORDER_ID=?", Long.class, orderId);
    }

    /** 申诉实体（R4-P1-1 三共键：订单/用户/任务），appealId 即售后动作的 SOURCE_ID。 */
    private void seedAppeal(long appealId, long orderId, long taskId) {
        jdbc.update("INSERT INTO ws_delivery_appeal (ID, DATA_STATUS, CREATE_BY, CREATE_TIME,"
                        + " UPDATE_BY, UPDATE_TIME, TASK_ID, ORDER_ID, USER_ID, APPEAL_REASON,"
                        + " APPEAL_STATUS) VALUES (?, 0, ?, ?, ?, ?, ?, ?, ?, '少水', 3)",
                appealId, USER_ID, NOW, USER_ID, NOW, taskId, orderId, USER_ID);
    }

    /** 已结算的配送分账：水 2600（机主 W7000=1820）＋费 400（机主 60%/配送员 30%/平台余数）。 */
    private void seedSettledSplits(long orderId) {
        seedTask(orderId, 5);
        splitService.enqueueForDeliveryOrder(orderId, "DO-CLW-" + orderId, 2600, 400,
                OWNER, COURIER, NOW);
        splitService.list(Wrappers.lambdaQuery(WsSplitRecord.class)
                        .eq(WsSplitRecord::getOrderId, orderId).orderByAsc(WsSplitRecord::getId))
                .forEach(r -> splitService.settleOne(r.getId()));
    }

    /** 真实生产入口：直落已认领态（PROCESSING/VERSION=2）动作，走 executeInTx 完成退款。 */
    private long executeRealRefund(long sourceId, long orderId, ActionType type,
                                   long productFen, long serviceFen) {
        return executeRealRefund(SourceType.DELIVERY_CANCEL, sourceId, orderId, type,
                productFen, serviceFen);
    }

    private long executeRealRefund(SourceType source, long sourceId, long orderId, ActionType type,
                                   long productFen, long serviceFen) {
        String no = AfterSaleNo.derive(source.getValue(), sourceId);
        jdbc.update("INSERT INTO ws_after_sale_action(DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,"
                        + "AFTER_SALE_NO,SOURCE_TYPE,SOURCE_ID,ORDER_ID,USER_ID,CARD_ID,ACTION_TYPE,"
                        + "REFUND_PRODUCT_FEN,REFUND_SERVICE_FEN,REFUND_PRODUCT_ML,REFUND_AMOUNT,"
                        + "ACTION_STATUS,VERSION,RETRY_COUNT) VALUES(0,1,?,1,?,?,?,?,?,?,?,?,?,?,0,?,?,2,0)",
                NOW, NOW, no, source.getValue(), sourceId, orderId, USER_ID, CARD_ID,
                type.getValue(), productFen, serviceFen, productFen + serviceFen,
                ActionStatus.PROCESSING.getValue());
        long actionId = jdbc.queryForObject(
                "SELECT ID FROM ws_after_sale_action WHERE AFTER_SALE_NO=?", Long.class, no);
        afterSale.executeInTx(actionId, OP_USER, NOW);
        return actionId;
    }

    // ------------------------------------------------------------------
    // 读取助手
    // ------------------------------------------------------------------

    private long cardBalance() {
        return jdbc.queryForObject("SELECT BALANCE_AMOUNT FROM ws_card WHERE ID=?", Long.class, CARD_ID);
    }

    private long balanceOf(long userId) {
        Long v = jdbc.queryForObject(
                "SELECT BALANCE_FEN FROM ws_income_account WHERE USER_ID=?", Long.class, userId);
        return v == null ? 0L : v;
    }

    private long reversedOf(long orderId, long receiver) {
        return jdbc.queryForObject(
                "SELECT REVERSED_AMOUNT FROM ws_split_record WHERE ORDER_ID=? AND RECEIVER_USER_ID=?",
                Long.class, orderId, receiver);
    }

    private long platformReversed(long orderId) {
        return jdbc.queryForObject(
                "SELECT REVERSED_AMOUNT FROM ws_split_record WHERE ORDER_ID=? AND RECEIVER_TYPE=3",
                Long.class, orderId);
    }

    private Map<String, Object> outboxRow(long actionId) {
        return jdbc.queryForMap("SELECT * FROM ws_split_clawback_action WHERE ACTION_ID=?", actionId);
    }

    private int outboxStatus(long actionId) {
        return jdbc.queryForObject(
                "SELECT OUTBOX_STATUS FROM ws_split_clawback_action WHERE ACTION_ID=?", Integer.class, actionId);
    }

    private long factCount(long actionId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_split_clawback WHERE ACTION_ID=?", Long.class, actionId);
    }

    private long factCount(long actionId, int status) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_split_clawback WHERE ACTION_ID=? AND CLAWBACK_STATUS=?",
                Long.class, actionId, status);
    }

    private long clawbackFlowCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_income_flow WHERE BIZ_IDEMPOTENCY_KEY LIKE 'CLAWBACK:%'", Long.class);
    }

    private static long num(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }

    /** 生产重放形状：与真实挂点同款的四字段稀疏对象再次登记。 */
    private void replayRegister(long actionId, long orderId, ActionType type, long productFen) {
        WsAfterSaleAction sparse = new WsAfterSaleAction();
        sparse.setId(actionId);
        sparse.setOrderId(orderId);
        sparse.setActionType(type.getValue());
        sparse.setRefundProductFen(productFen);
        clawbackService.registerForAction(sparse, NOW);
    }

    // ==================================================================
    // P0-1：登记与退款同事务、不可失败
    // ==================================================================

    /** ① 卡退款真实入口：退款成功 + 同事务恰一条动作级 outbox（冻结参数逐字），登记零明细。 */
    @Test
    void cardRefundRealEntryRegistersSingleActionOutboxInSameTx() {
        seedBalanceOrder(601L);
        seedSettledSplits(601L);
        long actionId = executeRealRefund(601L, 601L, ActionType.CARD_REFUND, 1_000L, 0L);

        assertEquals(INIT_BALANCE + 1_000L, cardBalance(), "客户退款真实入账");
        assertEquals(ActionStatus.SUCCESS.getValue(), (int) jdbc.queryForObject(
                "SELECT ACTION_STATUS FROM ws_after_sale_action WHERE ID=?", Integer.class, actionId));
        Map<String, Object> outbox = outboxRow(actionId);
        assertEquals(601L, num(outbox, "ORDER_ID"), "orderId 登记时冻结");
        assertEquals(ActionType.CARD_REFUND.getValue(), (int) num(outbox, "ACTION_TYPE"));
        assertEquals(1_000L, num(outbox, "REFUND_PRODUCT_FEN"), "基数=实际退款额");
        assertEquals(1L, num(outbox, "OUTBOX_STATUS"), "登记态=待处理");
        assertEquals(0L, factCount(actionId), "登记段零明细——分摊全在执行段");

        assertEquals(1, worker.runOnce(NOW));
        assertEquals(700L, reversedOf(601L, OWNER), "机主 1820*1000/2600=700");
        assertEquals(300L, platformReversed(601L), "平台吃舍入余数 300");
        assertEquals(2_060L - 700L, balanceOf(OWNER), "已入账份额收益扣回");
        assertEquals(2L, factCount(actionId, 2), "明细两行由执行段生成并完成");
        assertEquals(2, outboxStatus(actionId), "outbox 完成");
    }

    /** ② 补偿动作真实入口：类型闸 no-op——退款成功、零 outbox 零明细零资金动作。 */
    @Test
    void compensateRealEntryRegistersNothing() {
        seedBalanceOrder(602L);
        seedSettledSplits(602L);
        executeRealRefund(602L, 602L, ActionType.CARD_COMPENSATE, 1_000L, 0L);

        assertEquals(INIT_BALANCE + 1_000L, cardBalance(), "补偿本体照常入卡");
        assertEquals(0L, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_split_clawback_action", Long.class), "补偿不登记冲减");
        assertEquals(0, worker.runOnce(NOW), "Worker 无事可做");
        assertEquals(0L, reversedOf(602L, OWNER));
    }

    /** ③ P0-1 核心：比例快照损坏——退款照常成功（卡/流水/动作不回滚），冲减执行段转人工。 */
    @Test
    void snapshotCorruptionRefundStillSucceedsClawbackParksManual() {
        seedBalanceOrder(603L);
        seedSettledSplits(603L);
        // 破坏分账证据：快照改成不可解析形态（R1 实现会在登记段抛出并回滚客户退款——本例即驳回场景）
        jdbc.update("UPDATE ws_split_record SET SPLIT_RATE_SNAP='X999' WHERE ORDER_ID=603 AND RECEIVER_USER_ID=?",
                OWNER);

        long actionId = executeRealRefund(603L, 603L, ActionType.CARD_REFUND, 1_000L, 0L);

        assertEquals(INIT_BALANCE + 1_000L, cardBalance(), "证据损坏退款仍成功——绝不回滚");
        assertEquals(1L, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_wallet_flow WHERE BIZ_IDEMPOTENCY_KEY LIKE 'AFTERSALE:%'",
                Long.class), "退款流水完好");
        assertEquals(1L, num(outboxRow(actionId), "OUTBOX_STATUS"), "登记成功待执行");

        assertEquals(1, worker.runOnce(NOW), "Worker 发现并处置（转人工不抛出）");
        assertEquals(3, outboxStatus(actionId), "证据异常整动作转人工");
        assertTrue(((String) outboxRow(actionId).get("PROCESS_REMARK")).contains("分账证据异常"));
        assertEquals(0L, factCount(actionId), "零明细生成");
        assertEquals(0L, reversedOf(603L, OWNER), "零份额更新");
        assertEquals(2_060L, balanceOf(OWNER), "零资金扣回");
    }

    // ==================================================================
    // P1-1：重放幂等
    // ==================================================================

    /** ④ 整额退款同动作重放 5 次：恰一条动作事实、资金恰动一次（R1 在第二次登记即抛出）。 */
    @Test
    void fullRefundReplayFiveTimesMovesFundsExactlyOnce() {
        seedBalanceOrder(604L);
        seedSettledSplits(604L);
        long actionId = executeRealRefund(604L, 604L, ActionType.CARD_REFUND, 2_600L, 0L);
        for (int i = 0; i < 5; i++) {
            replayRegister(actionId, 604L, ActionType.CARD_REFUND, 2_600L);
            clawbackService.processAction(actionId, NOW);
        }
        assertEquals(1L, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_split_clawback_action WHERE ACTION_ID=?", Long.class, actionId),
                "恰一条动作事实");
        assertEquals(2, outboxStatus(actionId));
        assertEquals(1_820L, reversedOf(604L, OWNER), "整额冲减恰一次：1820");
        assertEquals(780L, platformReversed(604L), "平台 780");
        assertEquals(2_060L - 1_820L, balanceOf(OWNER), "资金恰动一次");
        assertEquals(2L, factCount(actionId, 2), "明细恒两行");
        assertEquals(1L, clawbackFlowCount(), "恰一条扣回流水");
    }

    /** ⑤ 超五成部分退款（1400/2600）重放：单套明细、金额不翻倍。 */
    @Test
    void overHalfPartialRefundReplayIsIdempotent() {
        seedBalanceOrder(605L);
        seedSettledSplits(605L);
        long actionId = executeRealRefund(605L, 605L, ActionType.CARD_REFUND, 1_400L, 0L);
        clawbackService.processAction(actionId, NOW);
        assertEquals(980L, reversedOf(605L, OWNER), "1820*1400/2600=980");

        replayRegister(actionId, 605L, ActionType.CARD_REFUND, 1_400L);
        clawbackService.processAction(actionId, NOW);
        assertEquals(980L, reversedOf(605L, OWNER), "重放不叠加");
        assertEquals(420L, platformReversed(605L));
        assertEquals(2L, factCount(actionId), "单套明细");
        assertEquals(1L, clawbackFlowCount());
    }

    /** ⑥ 重放参数漂移（1000→800）：既有登记 fail-closed 转人工，Worker 不再自动执行。 */
    @Test
    void replayWithDriftedParamsParksActionManual() {
        seedBalanceOrder(606L);
        seedSettledSplits(606L);
        long actionId = executeRealRefund(606L, 606L, ActionType.CARD_REFUND, 1_000L, 0L);
        replayRegister(actionId, 606L, ActionType.CARD_REFUND, 800L);

        assertEquals(3, outboxStatus(actionId), "参数漂移置需人工");
        assertTrue(((String) outboxRow(actionId).get("PROCESS_REMARK")).contains("参数漂移"));
        assertEquals(0, worker.runOnce(NOW), "人工件不自动重试");
        assertEquals(0L, reversedOf(606L, OWNER), "零资金动作");
        assertEquals(0L, factCount(actionId), "绝不产生第二份事实集");
    }

    // ==================================================================
    // P0-2：Worker 扫动作级 outbox + 完整性对账
    // ==================================================================

    /** ⑦ 明细缺失（半套）：Worker 仍经动作发现，整动作转人工、零部分冲减。 */
    @Test
    void missingDetailFactParksWholeActionWithZeroPartialClawback() {
        seedBalanceOrder(607L);
        seedSettledSplits(607L);
        long actionId = executeRealRefund(607L, 607L, ActionType.CARD_REFUND, 1_000L, 0L);
        // 构造残缺明细集：只存在机主一行（模拟明细丢失/半套写入），期望集应为 2 行
        long ownerSplitId = jdbc.queryForObject(
                "SELECT ID FROM ws_split_record WHERE ORDER_ID=607 AND RECEIVER_USER_ID=?", Long.class, OWNER);
        jdbc.update("INSERT INTO ws_split_clawback (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY,"
                        + " UPDATE_TIME, ACTION_ID, ORDER_ID, SPLIT_ID, CLAWBACK_AMOUNT, CLAWBACK_STATUS)"
                        + " VALUES (0,1,?,1,?,?,607,?,700,1)", NOW, NOW, actionId, ownerSplitId);

        assertEquals(1, worker.runOnce(NOW), "动作级 outbox 保证发现——不依赖明细完备");
        assertEquals(3, outboxStatus(actionId), "明细集不完备整动作转人工");
        assertTrue(((String) outboxRow(actionId).get("PROCESS_REMARK")).contains("不完备"));
        assertEquals(1L, factCount(actionId, 3), "残缺明细一并冻结为人工");
        assertEquals(0L, reversedOf(607L, OWNER), "零部分冲减");
        assertEquals(0L, platformReversed(607L));
        assertEquals(2_060L, balanceOf(OWNER), "零资金扣回");
    }

    /** ⑧ 明细金额被改小：合计≠登记退款额，整动作转人工、零副作用。 */
    @Test
    void shrunkDetailAmountParksWholeActionWithZeroSideEffects() {
        seedBalanceOrder(608L);
        seedSettledSplits(608L);
        long actionId = executeRealRefund(608L, 608L, ActionType.CARD_REFUND, 1_000L, 0L);
        long ownerSplitId = jdbc.queryForObject(
                "SELECT ID FROM ws_split_record WHERE ORDER_ID=608 AND RECEIVER_USER_ID=?", Long.class, OWNER);
        long platformSplitId = jdbc.queryForObject(
                "SELECT ID FROM ws_split_record WHERE ORDER_ID=608 AND RECEIVER_TYPE=3", Long.class);
        // 两行都在但机主行金额被改小（700→100）：与重算 700 不符且合计 400≠1000
        jdbc.update("INSERT INTO ws_split_clawback (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY,"
                        + " UPDATE_TIME, ACTION_ID, ORDER_ID, SPLIT_ID, CLAWBACK_AMOUNT, CLAWBACK_STATUS)"
                        + " VALUES (0,1,?,1,?,?,608,?,100,1)", NOW, NOW, actionId, ownerSplitId);
        jdbc.update("INSERT INTO ws_split_clawback (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY,"
                        + " UPDATE_TIME, ACTION_ID, ORDER_ID, SPLIT_ID, CLAWBACK_AMOUNT, CLAWBACK_STATUS)"
                        + " VALUES (0,1,?,1,?,?,608,?,300,1)", NOW, NOW, actionId, platformSplitId);

        worker.runOnce(NOW);
        assertEquals(3, outboxStatus(actionId));
        assertTrue(((String) outboxRow(actionId).get("PROCESS_REMARK")).contains("重算不符"));
        assertEquals(2L, factCount(actionId, 3), "全部明细冻结为人工——不存在部分完成");
        assertEquals(0L, reversedOf(608L, OWNER));
        assertEquals(2_060L, balanceOf(OWNER));
        assertEquals(0L, clawbackFlowCount());
    }

    /** ⑨ 权威动作被动过（逻辑删/类型漂移/订单漂移）：三种形态全部 fail-closed 转人工。 */
    @Test
    void authorityActionDriftAllFailClosed() {
        seedBalanceOrder(609L);
        seedSettledSplits(609L);
        long deleted = executeRealRefund(609L, 609L, ActionType.CARD_REFUND, 300L, 0L);
        jdbc.update("UPDATE ws_after_sale_action SET DATA_STATUS=1 WHERE ID=?", deleted);

        seedBalanceOrder(610L);
        seedSettledSplits(610L);
        long typeFlip = executeRealRefund(610L, 610L, ActionType.CARD_REFUND, 300L, 0L);
        jdbc.update("UPDATE ws_after_sale_action SET ACTION_TYPE=2 WHERE ID=?", typeFlip);

        seedBalanceOrder(611L);
        seedSettledSplits(611L);
        long orderFlip = executeRealRefund(611L, 611L, ActionType.CARD_REFUND, 300L, 0L);
        jdbc.update("UPDATE ws_after_sale_action SET ORDER_ID=99999 WHERE ID=?", orderFlip);

        assertEquals(3, worker.runOnce(NOW), "三个动作都被发现并处置");
        assertEquals(3, outboxStatus(deleted), "逻辑删动作转人工");
        assertTrue(((String) outboxRow(deleted).get("PROCESS_REMARK")).contains("逻辑删除"));
        assertEquals(3, outboxStatus(typeFlip), "类型漂移转人工");
        assertEquals(3, outboxStatus(orderFlip), "订单漂移转人工");
        for (long orderId : new long[]{609L, 610L, 611L}) {
            assertEquals(0L, reversedOf(orderId, OWNER), "订单 " + orderId + " 零份额更新");
        }
        assertEquals(0L, clawbackFlowCount(), "三案零资金动作");
    }

    /** ⑩ 动作事实在、明细为零：Worker 发现动作并完整执行（R2 常态首执形状）。 */
    @Test
    void workerDiscoversZeroDetailActionAndExecutesFully() {
        seedBalanceOrder(612L);
        seedSettledSplits(612L);
        long actionId = executeRealRefund(612L, 612L, ActionType.CARD_REFUND, 1_000L, 0L);
        assertEquals(0L, factCount(actionId), "执行前零明细——R1 的 Worker 在此永远发现不了该动作");

        assertEquals(1, worker.runOnce(NOW));
        assertEquals(2, outboxStatus(actionId));
        assertEquals(2L, factCount(actionId, 2), "明细由执行段生成并全部完成");
        assertEquals(700L, reversedOf(612L, OWNER));
        assertEquals(2_060L - 700L, balanceOf(OWNER));
    }

    /** ⑪ 两个不同部分退款动作并发执行：不超冲、不覆盖、无死锁，败者可重试（申诉共键形态）。 */
    @Test
    void twoConcurrentPartialActionsNeverOverClaw() throws Exception {
        seedBalanceOrder(613L);
        seedSettledSplits(613L);
        long taskId = jdbc.queryForObject(
                "SELECT ID FROM ws_delivery_task WHERE ORDER_ID=613", Long.class);
        seedAppeal(86131L, 613L, taskId);
        seedAppeal(86132L, 613L, taskId);
        long a1 = executeRealRefund(SourceType.DELIVERY_APPEAL, 86131L, 613L,
                ActionType.CARD_REFUND, 400L, 0L);
        long a2 = executeRealRefund(SourceType.DELIVERY_APPEAL, 86132L, 613L,
                ActionType.CARD_REFUND, 600L, 0L);

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.invokeAll(List.of(
                    () -> race(barrier, a1),
                    () -> race(barrier, a2)), 30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(700L, reversedOf(613L, OWNER), "并发累计恰 280+420，无超冲无覆盖");
        assertEquals(300L, platformReversed(613L));
        assertEquals(2_060L - 700L, balanceOf(OWNER));
        assertEquals(2, outboxStatus(a1));
        assertEquals(2, outboxStatus(a2));
        assertEquals(2L, factCount(a1, 2));
        assertEquals(2L, factCount(a2, 2));
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

    /**
     * ⑫ 待接单取消（R3-P1-1 白名单来源 / R4-P1-1 终态确认）：履约未发生、分账从未派发——
     * 「无分账」是业务事实而非证据缺失，允许收敛为合法零冲减完成。收敛依据是
     * <b>来源共键+取消终态</b>（DELIVERY_CANCEL＋sourceId==orderId＋售后号派生一致＋
     * 订单已退款＋任务已取消），不是「没有分账行」本身。
     */
    @Test
    void deliveryCancelBeforeSplitsIsLegalZeroClawback() {
        seedBalanceOrder(614L);
        seedTask(614L, 6);
        long actionId = executeRealRefund(SourceType.DELIVERY_CANCEL, 614L, 614L,
                ActionType.CARD_REFUND, 1_000L, 0L);

        assertEquals(1L, num(outboxRow(actionId), "OUTBOX_STATUS"), "登记不预判分账形态");
        assertEquals(1, worker.runOnce(NOW));
        assertEquals(2, outboxStatus(actionId), "白名单来源+取消终态确认，合法零冲减完成");
        assertTrue(((String) outboxRow(actionId).get("PROCESS_REMARK")).contains("合法零冲减"));
        assertEquals(0L, factCount(actionId));
        assertEquals(0L, clawbackFlowCount());
    }

    /** ⑬（R3-P1-1）配送申诉对应完成配送单、但分账行缺失：证据丢失转人工，零资金副作用。 */
    @Test
    void appealOnFulfilledOrderWithMissingSplitsParksManual() {
        // 履约完成的配送单（任务行在位=履约事实），但分账行整体缺失——申诉来源理应带分账证据
        seedBalanceOrder(615L);
        long taskId = seedTask(615L, 5);
        seedAppeal(8616L, 615L, taskId);
        long actionId = executeRealRefund(SourceType.DELIVERY_APPEAL, 8616L, 615L,
                ActionType.CARD_REFUND, 1_000L, 0L);

        assertEquals(INIT_BALANCE + 1_000L, cardBalance(), "客户退款照常成功，绝不回滚");
        assertEquals(1, worker.runOnce(NOW), "Worker 发现并处置（转人工不抛出）");
        assertEquals(3, outboxStatus(actionId), "履约后来源分账行缺失=证据丢失，整动作转人工");
        assertTrue(((String) outboxRow(actionId).get("PROCESS_REMARK")).contains("分账行缺失"));
        assertEquals(0L, factCount(actionId), "零明细");
        assertEquals(0L, clawbackFlowCount(), "零账户扣回");
        assertEquals(0L, (long) jdbc.queryForObject(
                "SELECT COALESCE(SUM(REVERSED_AMOUNT),0) FROM ws_split_record", Long.class),
                "零 REVERSED_AMOUNT 更新");
    }

    /** ⑭（R3-P1-1/R4-P1-2）平台行缺失：份额会被静默转嫁给其他收款方——整动作转人工。 */
    @Test
    void missingPlatformRowParksWholeActionWithoutShareShift() {
        seedBalanceOrder(616L);
        seedSettledSplits(616L);
        // 删除平台 REMAINDER 行：R2 实现会把分摊分母缩成非平台份额之和（1820），
        // 机主被冲整额 1000 而非应担的 700——平台份额被转嫁。R3 起必须整动作转人工
        jdbc.update("DELETE FROM ws_split_record WHERE ORDER_ID=616 AND RECEIVER_TYPE=3");
        long actionId = executeRealRefund(SourceType.DELIVERY_CANCEL, 616L, 616L,
                ActionType.CARD_REFUND, 1_000L, 0L);

        assertEquals(1, worker.runOnce(NOW));
        assertEquals(3, outboxStatus(actionId), "平台行缺失=分账证据不完整，整动作转人工");
        assertTrue(((String) outboxRow(actionId).get("PROCESS_REMARK")).contains("完整平台行缺失"));
        assertEquals(0L, reversedOf(616L, OWNER), "机主分毫未被转嫁冲减");
        assertEquals(2_060L, balanceOf(OWNER), "零资金扣回");
        assertEquals(0L, factCount(actionId), "零明细，禁止部分冲减");
    }

    // ==================================================================
    // R4-P1-1：来源共键（SOURCE_ID 锚定 / 申诉实体 / 取消终态）
    // ==================================================================

    /** ⑮ 取消动作 SOURCE_ID 与订单错位（复验驳回反例的 park 版）：转人工，退款不回滚。 */
    @Test
    void cancelSourceIdMismatchParksManual() {
        seedBalanceOrder(617L);
        seedTask(617L, 6);
        long actionId = executeRealRefund(SourceType.DELIVERY_CANCEL, 96170L, 617L,
                ActionType.CARD_REFUND, 1_000L, 0L);

        assertEquals(INIT_BALANCE + 1_000L, cardBalance(), "客户退款照常成功，绝不回滚");
        assertEquals(1, worker.runOnce(NOW));
        assertEquals(3, outboxStatus(actionId), "直锚来源 sourceId≠orderId=证据错绑，转人工");
        assertTrue(((String) outboxRow(actionId).get("PROCESS_REMARK")).contains("来源主体与订单不一致"));
        assertEquals(0L, factCount(actionId), "零明细");
        assertEquals(0L, clawbackFlowCount(), "零资金动作");
    }

    /** ⑯ 取消动作挂已签收订单且无分账：终态确认不过（履约已发生），转人工。 */
    @Test
    void cancelOnSignedTaskParksManual() {
        seedBalanceOrder(618L);
        seedTask(618L, 5);
        long actionId = executeRealRefund(SourceType.DELIVERY_CANCEL, 618L, 618L,
                ActionType.CARD_REFUND, 1_000L, 0L);

        assertEquals(1, worker.runOnce(NOW));
        assertEquals(3, outboxStatus(actionId), "任务已签收=履约发生，取消零冲减不成立");
        assertTrue(((String) outboxRow(actionId).get("PROCESS_REMARK")).contains("未处于已取消态"));
        assertEquals(0L, clawbackFlowCount(), "零资金动作");
    }

    /** ⑰ 申诉来源指向不存在的申诉：转人工。 */
    @Test
    void appealNotFoundParksManual() {
        seedBalanceOrder(619L);
        seedTask(619L, 5);
        long actionId = executeRealRefund(SourceType.DELIVERY_APPEAL, 96190L, 619L,
                ActionType.CARD_REFUND, 500L, 0L);

        assertEquals(1, worker.runOnce(NOW));
        assertEquals(3, outboxStatus(actionId), "申诉实体缺失=来源归属无法证明，转人工");
        assertTrue(((String) outboxRow(actionId).get("PROCESS_REMARK")).contains("申诉记录不存在"));
        assertEquals(0L, clawbackFlowCount());
    }

    /** ⑱ 申诉来源指向另一订单的申诉：归属错绑，转人工。 */
    @Test
    void appealOfAnotherOrderParksManual() {
        seedBalanceOrder(620L);
        seedTask(620L, 5);
        // 申诉真实存在，但归属订单 621
        jdbc.update("INSERT INTO ws_order(ID,DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,"
                        + "ORDER_NO,ORDER_TYPE,USER_ID,CARD_ID,ORDER_AMOUNT,PAY_WAY,ORDER_STATUS) "
                        + "VALUES(621,0,1,?,1,?,'WD-CLW-621',?,?,?,3000,2,7)",
                NOW, NOW, TradeEnum.OrderType.DELIVERY.getValue(), USER_ID, CARD_ID);
        long otherTask = seedTask(621L, 5);
        seedAppeal(96210L, 621L, otherTask);
        long actionId = executeRealRefund(SourceType.DELIVERY_APPEAL, 96210L, 620L,
                ActionType.CARD_REFUND, 500L, 0L);

        assertEquals(1, worker.runOnce(NOW));
        assertEquals(3, outboxStatus(actionId), "申诉归属订单不一致=错绑，转人工");
        assertTrue(((String) outboxRow(actionId).get("PROCESS_REMARK")).contains("申诉归属订单不一致"));
        assertEquals(0L, clawbackFlowCount());
    }

    // ==================================================================
    // R4-P1-2：平台行完整身份三元组
    // ==================================================================

    /** ⑲ 删真平台行+机主行伪装 REMAINDER：转人工，机主余额与 REVERSED_AMOUNT 分毫不动。 */
    @Test
    void fakePlatformRowAfterDeletingRealOneParksManual() {
        seedBalanceOrder(622L);
        seedSettledSplits(622L);
        // R3 判据只认快照字符串：删真平台行+把机主行快照改成 REMAINDER 即可骗过行数检查，
        // 机主行被当平台余数吃下全部基数、随后又按真实 receiverUserId 被账户扣回——双重错账
        jdbc.update("DELETE FROM ws_split_record WHERE ORDER_ID=622 AND RECEIVER_TYPE=3");
        jdbc.update("UPDATE ws_split_record SET SPLIT_RATE_SNAP='REMAINDER'"
                + " WHERE ORDER_ID=622 AND RECEIVER_USER_ID=?", OWNER);
        long actionId = executeRealRefund(SourceType.DELIVERY_CANCEL, 622L, 622L,
                ActionType.CARD_REFUND, 1_000L, 0L);

        assertEquals(1, worker.runOnce(NOW));
        assertEquals(3, outboxStatus(actionId), "伪装平台行被三元组判据拦停");
        assertTrue(((String) outboxRow(actionId).get("PROCESS_REMARK")).contains("非平台行使用 REMAINDER"));
        assertEquals(0L, reversedOf(622L, OWNER), "机主 REVERSED_AMOUNT 分毫不动");
        assertEquals(2_060L, balanceOf(OWNER), "机主余额分毫不动");
        assertEquals(0L, factCount(actionId));
        assertEquals(0L, clawbackFlowCount());
    }

    /** ⑳ PLATFORM 行收款方非 0：错置收款人，转人工。 */
    @Test
    void platformRowWithNonZeroReceiverParksManual() {
        seedBalanceOrder(623L);
        seedSettledSplits(623L);
        // 错置收款人用独立 ID（777）：不与机主行撞收款方，断言查询保持单行语义
        jdbc.update("UPDATE ws_split_record SET RECEIVER_USER_ID=777 WHERE ORDER_ID=623 AND RECEIVER_TYPE=3");
        long actionId = executeRealRefund(SourceType.DELIVERY_CANCEL, 623L, 623L,
                ActionType.CARD_REFUND, 1_000L, 0L);

        assertEquals(1, worker.runOnce(NOW));
        assertEquals(3, outboxStatus(actionId), "平台行收款方非0=身份残缺，转人工");
        assertTrue(((String) outboxRow(actionId).get("PROCESS_REMARK")).contains("平台行收款方非0"));
        assertEquals(0L, reversedOf(623L, OWNER));
        assertEquals(2_060L, balanceOf(OWNER), "平台责任绝不错扣普通收款人账户");
        assertEquals(0L, clawbackFlowCount());
    }

    /** ㉑ PLATFORM 行快照非 REMAINDER：身份残缺，转人工。 */
    @Test
    void platformRowWithNonRemainderSnapParksManual() {
        seedBalanceOrder(624L);
        seedSettledSplits(624L);
        jdbc.update("UPDATE ws_split_record SET SPLIT_RATE_SNAP='W1000' WHERE ORDER_ID=624 AND RECEIVER_TYPE=3");
        long actionId = executeRealRefund(SourceType.DELIVERY_CANCEL, 624L, 624L,
                ActionType.CARD_REFUND, 1_000L, 0L);

        assertEquals(1, worker.runOnce(NOW));
        assertEquals(3, outboxStatus(actionId));
        assertTrue(((String) outboxRow(actionId).get("PROCESS_REMARK")).contains("平台行快照非 REMAINDER"));
        assertEquals(0L, reversedOf(624L, OWNER));
        assertEquals(0L, clawbackFlowCount());
    }

    /** ㉒ 普通收款行伪装 REMAINDER 而真平台行仍在：转人工（不因「恰一行完整平台」放行）。 */
    @Test
    void ordinaryRowFakingRemainderBesideRealPlatformParksManual() {
        seedBalanceOrder(625L);
        seedSettledSplits(625L);
        jdbc.update("UPDATE ws_split_record SET SPLIT_RATE_SNAP='REMAINDER'"
                + " WHERE ORDER_ID=625 AND RECEIVER_USER_ID=?", COURIER);
        long actionId = executeRealRefund(SourceType.DELIVERY_CANCEL, 625L, 625L,
                ActionType.CARD_REFUND, 1_000L, 0L);

        assertEquals(1, worker.runOnce(NOW));
        assertEquals(3, outboxStatus(actionId), "任何非平台行使用 REMAINDER 都整动作拦停");
        assertTrue(((String) outboxRow(actionId).get("PROCESS_REMARK")).contains("非平台行使用 REMAINDER"));
        assertEquals(0L, reversedOf(625L, OWNER));
        assertEquals(0L, reversedOf(625L, COURIER));
        assertEquals(2_060L, balanceOf(OWNER));
        assertEquals(0L, clawbackFlowCount());
    }
}
