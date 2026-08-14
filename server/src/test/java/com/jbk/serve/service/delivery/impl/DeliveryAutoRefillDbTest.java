package com.jbk.serve.service.delivery.impl;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.aftersale.WsCardEntitlementBatchMapper;
import com.jbk.serve.mapper.aftersale.WsEntitlementAllocationMapper;
import com.jbk.serve.mapper.delivery.WsDeliveryAutoRuleMapper;
import com.jbk.serve.mapper.delivery.WsDeliveryTaskMapper;
import com.jbk.serve.mapper.message.WsMessageMapper;
import com.jbk.serve.mapper.ops.WsDomainEventMapper;
import com.jbk.serve.mapper.product.WsWaterTypeMapper;
import com.jbk.serve.mapper.station.WsStationMapper;
import com.jbk.serve.mapper.trade.TradeCardMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.service.aftersale.IAfterSaleActionTxService;
import com.jbk.serve.service.aftersale.batch.EntitlementFixture;
import com.jbk.serve.service.aftersale.batch.EntitlementLedger;
import com.jbk.serve.service.delivery.DeliveryClock;
import com.jbk.serve.service.delivery.DeliveryOrderNo;
import com.jbk.serve.service.delivery.IDeliveryOrderService;
import com.jbk.serve.service.delivery.IDeliveryOrderTxService;
import com.jbk.serve.service.message.IWsMessageService;
import com.jbk.serve.service.message.impl.WsMessageServiceImpl;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.serve.service.ops.impl.WsDomainEventServiceImpl;
import com.jbk.tool.config.system.mybatis.MpMetaObjectHandler;
import com.jbk.tool.data.delivery.bo.DeliveryCreateBo;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B08-S1 自动补货固定周期触发方的<b>真实 MySQL + 真实 Spring 事务</b>集成测试。
 *
 * <p>与 {@link DeliveryOrderTxDbTest} 的分工：那边钉创单事务本身，领域事件是 Mock；
 * 这里接<b>真实</b> {@link WsDomainEventServiceImpl}，因为本包要证的恰恰是
 * 「同一期反复失败，失败证据全库恰一条」——那是唯一键行为，Mockito 证不了。</p>
 *
 * <p>覆盖任务书第四节的资金/并发/幂等条目：未到期不生成、当期一单一任务一流水、
 * 重复执行不重复扣款、双 Worker 并发只成一单、跨多期只生成当前期、余额不足零副作用且
 * 失败事件恰一条、恢复后可成功一次、水站停业/水种停用/卡冻结 fail-closed、
 * 首条规则异常不阻塞次条、非法业务时间拒绝。</p>
 *
 * <p>无 Docker 环境自动跳过。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = DeliveryAutoRefillDbTest.Ctx.class)
class DeliveryAutoRefillDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_auto_refill_it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static final long USER_ID = 9L;
    private static final long USER_B = 11L;
    private static final long CARD_ID = 100L;
    private static final long CARD_B = 101L;
    private static final long STATION_ID = 41L;
    private static final long WATER_TYPE_ID = 8L;
    private static final long BALANCE_FEN = 10_000L;
    /** 2 桶 20L：水费+配送费合计 3000 分（口径由 DeliveryPricing 决定，此处只作断言基准）。 */
    private static final long PERIOD_FEN = 3_000L;
    private static final String REQ_A = "1f4a2b3c-4d5e-4f60-8a9b-0c1d2e3f4a5b";
    private static final String REQ_B = "2b4a2b3c-4d5e-4f60-8a9b-0c1d2e3f4a5b";

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
            globalConfig.setMetaObjectHandler(new MpMetaObjectHandler());
            factory.setGlobalConfig(globalConfig);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath:mapper/*/*.xml"));
            return new SqlSessionTemplate(factory.getObject());
        }

        private static <M> MapperFactoryBean<M> mapper(Class<M> type, SqlSessionTemplate template) {
            MapperFactoryBean<M> bean = new MapperFactoryBean<>(type);
            bean.setSqlSessionTemplate(template);
            return bean;
        }


        /** 订阅通知登记：真实实现。失败路径走独立事务，只有真表能证明"回滚后它还在"。 */
        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.mini.WsWechatNotifyOutboxMapper>
                wechatNotifyOutboxMapper(SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.mini.WsWechatNotifyOutboxMapper.class, t);
        }

        @Bean
        com.jbk.serve.service.mini.notify.WechatNotifyEnqueue notifyEnqueue(
                com.jbk.serve.mapper.mini.WsWechatNotifyOutboxMapper m) {
            return new com.jbk.serve.service.mini.notify.WechatNotifyEnqueue(m);
        }

        @Bean
        MapperFactoryBean<TradeCardMapper> tradeCardMapper(SqlSessionTemplate t) {
            return mapper(TradeCardMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsOrderMapper> wsOrderMapper(SqlSessionTemplate t) {
            return mapper(WsOrderMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsWalletFlowMapper> wsWalletFlowMapper(SqlSessionTemplate t) {
            return mapper(WsWalletFlowMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsDeliveryTaskMapper> wsDeliveryTaskMapper(SqlSessionTemplate t) {
            return mapper(WsDeliveryTaskMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsDeliveryAutoRuleMapper> wsDeliveryAutoRuleMapper(SqlSessionTemplate t) {
            return mapper(WsDeliveryAutoRuleMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsStationMapper> wsStationMapper(SqlSessionTemplate t) {
            return mapper(WsStationMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsWaterTypeMapper> wsWaterTypeMapper(SqlSessionTemplate t) {
            return mapper(WsWaterTypeMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsMessageMapper> wsMessageMapper(SqlSessionTemplate t) {
            return mapper(WsMessageMapper.class, t);
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
        EntitlementLedger entitlementLedger(WsCardEntitlementBatchMapper batchMapper,
                                            WsEntitlementAllocationMapper allocationMapper,
                                            TradeCardMapper tradeCardMapper,
                                            WsWalletFlowMapper walletFlowMapper) {
            return new EntitlementLedger(batchMapper, allocationMapper, tradeCardMapper, walletFlowMapper);
        }

        @Bean
        IWsDomainEventService domainEventService() {
            // spy 而非 mock：「同一期失败证据恰一条」是 uk_domain_event_biz_key 的真实行为；
            // 打桩能力只为「失败留痕自身写入失败」那条用例保留
            return org.mockito.Mockito.spy(new WsDomainEventServiceImpl());
        }

        @Bean
        IWsMessageService messageService() {
            return new WsMessageServiceImpl();
        }

        @Bean
        IAfterSaleActionTxService afterSaleActionTxService() {
            // 自动补货生成路径一次都不碰售后内核
            return org.mockito.Mockito.mock(IAfterSaleActionTxService.class);
        }

        @Bean
        com.jbk.serve.service.settlement.IInviteService inviteService() {
            return org.mockito.Mockito.mock(com.jbk.serve.service.settlement.IInviteService.class);
        }

        @Bean
        IDeliveryOrderTxService deliveryOrderTxService() {
            return new DeliveryOrderTxServiceImpl();
        }

        @Bean
        IDeliveryOrderService deliveryOrderService() {
            return new DeliveryOrderServiceImpl();
        }

        @Bean
        com.jbk.serve.service.delivery.IMiniAutoRuleService miniAutoRuleService() {
            // S2 用户自助管理：与生成器同库同事实（本人过滤、三键 CAS、终态不可恢复）
            return new MiniAutoRuleServiceImpl();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    @Autowired
    private IDeliveryOrderService orderService;
    @Autowired
    private com.jbk.serve.service.delivery.IMiniAutoRuleService miniAutoRuleService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    /** spy：默认走真实现；只有「审计写入自身失败」那条用例对失败留痕打桩。 */
    @Autowired
    private IWsDomainEventService domainEventService;

    /** 被测调度层：手工装配（生产路径靠 @ConditionalOnProperty 门控，装配行为由 WorkerTest 钉）。 */
    private DeliveryAutoRefillWorker worker;

    @BeforeEach
    void reset() {
        DeliveryDbSchema.createAll(jdbc);
        DeliveryDbSchema.truncateAll(jdbc);
        jdbc.update("INSERT INTO ws_station(ID,STATION_NAME,STATION_CODE,STATION_STATUS) VALUES(?,?,?,1)",
                STATION_ID, "测试站", "ST-41");
        jdbc.update("INSERT INTO ws_water_type(ID,WATER_NAME,WATER_SORT,DEFAULT_FLAG,WATER_STATUS) "
                + "VALUES(?,?,1,2,1)", WATER_TYPE_ID, "纯净水");
        seedCard(CARD_ID, USER_ID, 1, BALANCE_FEN);
        // 清掉上一条用例可能留下的打桩，spy 回到「全部委派真实现」
        org.mockito.Mockito.reset(domainEventService);
        worker = new DeliveryAutoRefillWorker();
        ReflectionTestUtils.setField(worker, "deliveryOrderService", orderService);
    }

    // ==================== 夹具 ====================

    private void seedCard(long cardId, long userId, int cardStatus, long fen) {
        jdbc.update("DELETE FROM ws_card WHERE ID=?", cardId);
        jdbc.update("INSERT INTO ws_card(ID,DATA_STATUS,CARD_NO,CARD_TYPE,USER_ID,BALANCE_AMOUNT,BALANCE_ML,"
                        + "SCOPE_JSON,EXPIRE_TIME,CARD_STATUS) VALUES(?,0,?,1,?,?,0,NULL,NULL,?)",
                cardId, "VC-TEST-" + cardId, userId, fen, cardStatus);
        EntitlementFixture.seedLegacyBatch(jdbc, cardId, userId, fen, 0L);
    }

    private DeliveryCreateBo autoBo(String requestId, long cardId, int intervalDays) {
        DeliveryCreateBo bo = new DeliveryCreateBo();
        bo.setRequestId(requestId);
        bo.setCardId(String.valueOf(cardId));
        bo.setStationId(String.valueOf(STATION_ID));
        bo.setWaterTypeId(String.valueOf(WATER_TYPE_ID));
        bo.setContainerSpec("20L桶");
        bo.setDeliveryCount(2);
        bo.setPlanReturnCount(1);
        bo.setReceiveAddress("光谷软件园 A1 栋 502");
        bo.setReceivePhone("13900001111");
        bo.setDeliveryMode(3);
        bo.setAutoRefillIntervalDays(intervalDays);
        return bo;
    }

    /** 建一条启用中的自动补货规则（连带首单），返回规则 ID。 */
    private long seedRule(String requestId, long cardId, long userId, int intervalDays) {
        orderService.createDeliveryOrder(autoBo(requestId, cardId, intervalDays), userId);
        return jdbc.queryForObject(
                "SELECT ID FROM ws_delivery_auto_rule WHERE USER_ID=? ORDER BY ID DESC LIMIT 1",
                Long.class, userId);
    }

    private String anchorOf(long ruleId) {
        return jdbc.queryForObject("SELECT ANCHOR_TIME FROM ws_delivery_auto_rule WHERE ID=?",
                String.class, ruleId);
    }

    private long cardBalance(long cardId) {
        return jdbc.queryForObject("SELECT BALANCE_AMOUNT FROM ws_card WHERE ID=?", Long.class, cardId);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private int failEventCount(long ruleId, long period) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ws_domain_event WHERE BIZ_IDEMPOTENCY_KEY=?",
                Integer.class, DeliveryOrderServiceImpl.autoRefillFailKey(ruleId, period));
    }

    /** 全部自动补货失败证据（按键前缀）：正向创单审计不在其列，别拿事件表总数当判据。 */
    private int allFailEventCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_domain_event WHERE BIZ_IDEMPOTENCY_KEY LIKE 'AUTO_REFILL_FAIL:%'",
                Integer.class);
    }

    private int orderCount(String orderNo) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ws_order WHERE ORDER_NO=?", Integer.class, orderNo);
    }

    // ==================== 3. 未到期不生成 ====================

    @Test
    void ruleNotDueGeneratesNothing() {
        long ruleId = seedRule(REQ_A, CARD_ID, USER_ID, 7);
        String anchor = anchorOf(ruleId);

        // 第 6 天：还没跨过第 1 期
        assertEquals(0, worker.runOnce(DeliveryClock.plusHours(anchor, 24 * 6)));
        assertEquals(1, count("ws_order"), "只有建规则时的首单");
        assertEquals(BALANCE_FEN - PERIOD_FEN, cardBalance(CARD_ID), "未到期绝不扣款");
        assertEquals(0, allFailEventCount(), "未到期不是失败，不得留任何失败证据");
    }

    // ==================== 4. 当期到期：一单、一任务、一条扣款流水 ====================

    @Test
    void dueRuleGeneratesExactlyOneOrderOneTaskOneFlow() {
        long ruleId = seedRule(REQ_A, CARD_ID, USER_ID, 7);
        String anchor = anchorOf(ruleId);
        String due = DeliveryClock.plusHours(anchor, 24 * 8);

        assertEquals(1, worker.runOnce(due));

        String expectedNo = DeliveryOrderNo.deriveAutoRefill(USER_ID, ruleId, 1);
        assertEquals(1, orderCount(expectedNo), "订单号按 规则+期序 确定性派生");
        assertEquals(2, count("ws_order"), "首单 + 第1期");
        assertEquals(2, count("ws_delivery_task"), "每单恰一条配送任务");
        assertEquals(2, count("ws_wallet_flow"), "每单恰一条扣款流水");
        assertEquals(BALANCE_FEN - PERIOD_FEN * 2, cardBalance(CARD_ID));
        assertEquals(0, allFailEventCount(), "成功路径零失败留痕");
    }

    // ==================== 5. 同一时刻重复执行不重复扣款 ====================

    @Test
    void repeatedRunsAtSameClockNeverDoubleCharge() {
        long ruleId = seedRule(REQ_A, CARD_ID, USER_ID, 7);
        String due = DeliveryClock.plusHours(anchorOf(ruleId), 24 * 8);

        assertEquals(1, worker.runOnce(due));
        long afterFirst = cardBalance(CARD_ID);

        for (int i = 0; i < 5; i++) {
            assertEquals(0, worker.runOnce(due), "同期重复扫描不得再生成");
        }
        assertEquals(2, count("ws_order"));
        assertEquals(2, count("ws_delivery_task"));
        assertEquals(2, count("ws_wallet_flow"));
        assertEquals(afterFirst, cardBalance(CARD_ID), "重复执行零追加扣款");
    }

    // ==================== 6. 两个 Worker 并发只成功一单 ====================

    @Test
    void concurrentWorkersGenerateExactlyOneOrder() throws Exception {
        long ruleId = seedRule(REQ_A, CARD_ID, USER_ID, 7);
        String due = DeliveryClock.plusHours(anchorOf(ruleId), 24 * 8);
        long before = cardBalance(CARD_ID);

        // 两个独立 Worker 实例模拟多实例部署；栅栏让它们尽量同时穿过「不存在」预检，
        // 逼出「预检不是判据、唯一键才是」这条不变量。
        DeliveryAutoRefillWorker w1 = new DeliveryAutoRefillWorker();
        DeliveryAutoRefillWorker w2 = new DeliveryAutoRefillWorker();
        ReflectionTestUtils.setField(w1, "deliveryOrderService", orderService);
        ReflectionTestUtils.setField(w2, "deliveryOrderService", orderService);

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> a = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return w1.runOnce(due);
            };
            Callable<Integer> b = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return w2.runOnce(due);
            };
            List<Future<Integer>> futures = pool.invokeAll(List.of(a, b));
            int total = futures.get(0).get(30, TimeUnit.SECONDS) + futures.get(1).get(30, TimeUnit.SECONDS);
            assertEquals(1, total, "两个实例合计只应生成一单");
        }
        finally {
            pool.shutdownNow();
        }

        String expectedNo = DeliveryOrderNo.deriveAutoRefill(USER_ID, ruleId, 1);
        assertEquals(1, orderCount(expectedNo));
        assertEquals(2, count("ws_order"));
        assertEquals(2, count("ws_delivery_task"), "输方零任务残留");
        assertEquals(2, count("ws_wallet_flow"), "输方零流水残留");
        assertEquals(before - PERIOD_FEN, cardBalance(CARD_ID), "并发下也只扣一次款");
    }

    // ==================== 7. 停机跨多期：只生成当前期，不补历史 ====================

    @Test
    void longDowntimeGeneratesOnlyCurrentPeriod() {
        long ruleId = seedRule(REQ_A, CARD_ID, USER_ID, 7);
        String anchor = anchorOf(ruleId);
        long before = cardBalance(CARD_ID);

        // 停机 70 天 ≈ 第 10 期。若追补历史，会一次性连扣 10 期。
        String longAfter = DeliveryClock.plusHours(anchor, 24 * 70);
        assertEquals(1, worker.runOnce(longAfter), "只生成当前期这一单");

        assertEquals(before - PERIOD_FEN, cardBalance(CARD_ID), "绝不连环补扣历史期");
        assertEquals(2, count("ws_order"));
        assertEquals(1, orderCount(DeliveryOrderNo.deriveAutoRefill(USER_ID, ruleId, 10)));
        for (long skipped : new long[]{1L, 2L, 5L, 9L}) {
            assertEquals(0, orderCount(DeliveryOrderNo.deriveAutoRefill(USER_ID, ruleId, skipped)),
                    "第" + skipped + "期属于停机期间，不得追补");
        }
    }

    // ==================== 8. 余额不足：零副作用 + 同期失败事件恰一条 ====================

    @Test
    void insufficientBalanceLeavesZeroResidueAndExactlyOneFailEvent() {
        long ruleId = seedRule(REQ_A, CARD_ID, USER_ID, 7);
        String due = DeliveryClock.plusHours(anchorOf(ruleId), 24 * 8);
        jdbc.update("UPDATE ws_card SET BALANCE_AMOUNT=0 WHERE ID=?", CARD_ID);

        // 反复扫描：业务侧每轮都失败，证据侧必须收敛为一条
        for (int i = 0; i < 4; i++) {
            assertEquals(0, worker.runOnce(due));
        }

        assertEquals(1, count("ws_order"), "只有首单");
        assertEquals(1, count("ws_delivery_task"));
        assertEquals(1, count("ws_wallet_flow"));
        assertEquals(1, count("ws_entitlement_allocation"), "分摊只有首单那一条，失败期零分摊");
        assertEquals(0L, cardBalance(CARD_ID));
        assertEquals(1, count("ws_delivery_auto_rule"), "规则不因单期失败被销毁");
        assertEquals(1, failEventCount(ruleId, 1),
                "同一期反复失败，证据必须按 AUTO_REFILL_FAIL:ruleId:period 收敛为一条");
        assertEquals(1, allFailEventCount(), "全库的自动补货失败证据也只有这一条");
    }

    /** 自动补货失败 → 登记失败通知且对象编号带期序（传播方式的区分在下一条）。 */
    @Test
    void autoRefillFailureEnqueuesNotice() {
        long ruleId = seedRule(REQ_A, CARD_ID, USER_ID, 7);
        String due = DeliveryClock.plusHours(anchorOf(ruleId), 24 * 8);
        jdbc.update("UPDATE ws_card SET BALANCE_AMOUNT=0 WHERE ID=?", CARD_ID);

        assertEquals(0, worker.runOnce(due), "前置：本期必须创单失败，否则本用例什么都没验");

        assertEquals(1, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ws_wechat_notify_outbox WHERE EVENT_TYPE='AUTO_REFILL_FAILED'",
                        Integer.class),
                "创单失败回滚后通知没了——用户收不到任何提示，还以为水在路上");
        assertEquals(ruleId + ":1", jdbc.queryForObject(
                        "SELECT BIZ_OBJECT_NO FROM ws_wechat_notify_outbox WHERE EVENT_TYPE='AUTO_REFILL_FAILED'",
                        String.class),
                "对象编号必须带期序：不带的话同一规则第二期失败会被判重复而静默丢弃");
    }

    /**
     * {@code enqueueIndependent} 判据：外层事务回滚时失败通知必须活下来；
     * 对照断言订单/任务随回滚消失，否则可能只是外层压根没回滚。
     */
    @Test
    void autoRefillFailureNoticeSurvivesAnOuterRollback() {
        long ruleId = seedRule(REQ_A, CARD_ID, USER_ID, 7);
        String due = DeliveryClock.plusHours(anchorOf(ruleId), 24 * 8);
        jdbc.update("UPDATE ws_card SET BALANCE_AMOUNT=0 WHERE ID=?", CARD_ID);
        int ordersBefore = count("ws_order");

        TransactionTemplate outer = new TransactionTemplate(txManager);
        try {
            outer.execute(status -> {
                worker.runOnce(due);
                throw new IllegalStateException("制造外层回滚");
            });
        }
        catch (IllegalStateException expected) {
            // 外层回滚正是本用例的前提
        }

        assertEquals(ordersBefore, count("ws_order"), "外层没真的回滚，本用例什么都没验");
        assertEquals(1, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ws_wechat_notify_outbox WHERE EVENT_TYPE='AUTO_REFILL_FAILED'",
                        Integer.class),
                "外层回滚把失败通知一起带走了——用户没收到水，也收不到任何提示");
    }

    /** 8b：失败证据按期收敛而非按规则——幂等键少了期序，后续每期失败都成无痕事件。 */
    @Test
    void eachPeriodKeepsItsOwnFailEvidence() {
        long ruleId = seedRule(REQ_A, CARD_ID, USER_ID, 7);
        String anchor = anchorOf(ruleId);
        jdbc.update("UPDATE ws_card SET BALANCE_AMOUNT=0 WHERE ID=?", CARD_ID);

        // 第 1 期失败
        assertEquals(0, worker.runOnce(DeliveryClock.plusHours(anchor, 24 * 8)));
        // 第 2 期（第 15 天）同样失败
        assertEquals(0, worker.runOnce(DeliveryClock.plusHours(anchor, 24 * 15)));
        // 第 3 期（第 22 天）同样失败
        assertEquals(0, worker.runOnce(DeliveryClock.plusHours(anchor, 24 * 22)));

        assertEquals(1, failEventCount(ruleId, 1), "第1期一条");
        assertEquals(1, failEventCount(ruleId, 2), "第2期另有一条");
        assertEquals(1, failEventCount(ruleId, 3), "第3期再一条");
        assertEquals(3, allFailEventCount(), "三期共三条，绝不能被并成一条");
        assertEquals(0L, cardBalance(CARD_ID), "全程零扣款");
        assertEquals(1, count("ws_order"), "只有首单");
    }

    // ==================== 9. 条件恢复后同一期仍可成功生成一次 ====================

    @Test
    void samePeriodStillGeneratesOnceAfterConditionRecovers() {
        long ruleId = seedRule(REQ_A, CARD_ID, USER_ID, 7);
        String due = DeliveryClock.plusHours(anchorOf(ruleId), 24 * 8);
        jdbc.update("UPDATE ws_card SET BALANCE_AMOUNT=0 WHERE ID=?", CARD_ID);
        assertEquals(0, worker.runOnce(due));
        assertEquals(1, failEventCount(ruleId, 1));

        // 用户充值后：本期必须还能补上——失败幂等键只锁留痕，绝不能把这一期锁死
        jdbc.update("UPDATE ws_card SET BALANCE_AMOUNT=? WHERE ID=?", BALANCE_FEN, CARD_ID);
        EntitlementFixture.seedLegacyBatch(jdbc, CARD_ID, USER_ID, BALANCE_FEN, 0L);

        assertEquals(1, worker.runOnce(due), "条件恢复后同一期应当补生成");
        assertEquals(1, orderCount(DeliveryOrderNo.deriveAutoRefill(USER_ID, ruleId, 1)));
        assertEquals(BALANCE_FEN - PERIOD_FEN, cardBalance(CARD_ID));

        // 成功之后再扫：不重复生成、也不追加失败证据
        assertEquals(0, worker.runOnce(due));
        assertEquals(1, failEventCount(ruleId, 1));
    }

    // ==================== 10. 水站停业 / 水种停用 / 卡冻结分别 fail-closed ====================

    @Test
    void stationClosedFailsClosedWithZeroResidue() {
        long ruleId = seedRule(REQ_A, CARD_ID, USER_ID, 7);
        String due = DeliveryClock.plusHours(anchorOf(ruleId), 24 * 8);
        long before = cardBalance(CARD_ID);
        jdbc.update("UPDATE ws_station SET STATION_STATUS=2 WHERE ID=?", STATION_ID);

        assertEquals(0, worker.runOnce(due));

        assertEquals(1, count("ws_order"));
        assertEquals(before, cardBalance(CARD_ID), "停业不得扣款");
        assertEquals(1, failEventCount(ruleId, 1));
    }

    @Test
    void waterTypeDisabledFailsClosedWithZeroResidue() {
        long ruleId = seedRule(REQ_A, CARD_ID, USER_ID, 7);
        String due = DeliveryClock.plusHours(anchorOf(ruleId), 24 * 8);
        long before = cardBalance(CARD_ID);
        jdbc.update("UPDATE ws_water_type SET WATER_STATUS=2 WHERE ID=?", WATER_TYPE_ID);

        assertEquals(0, worker.runOnce(due));

        assertEquals(1, count("ws_order"));
        assertEquals(before, cardBalance(CARD_ID), "水种停用不得扣款");
        assertEquals(1, failEventCount(ruleId, 1));
    }

    @Test
    void frozenCardFailsClosedWithZeroResidue() {
        long ruleId = seedRule(REQ_A, CARD_ID, USER_ID, 7);
        String due = DeliveryClock.plusHours(anchorOf(ruleId), 24 * 8);
        long before = cardBalance(CARD_ID);
        // 2 = 冻结
        jdbc.update("UPDATE ws_card SET CARD_STATUS=2 WHERE ID=?", CARD_ID);

        assertEquals(0, worker.runOnce(due));

        assertEquals(1, count("ws_order"));
        assertEquals(1, count("ws_wallet_flow"));
        assertEquals(before, cardBalance(CARD_ID), "冻结卡不得被扣");
        assertEquals(1, failEventCount(ruleId, 1));
    }

    // ==================== 11. 首条规则异常不阻塞后续合法规则 ====================

    @Test
    void failingRuleDoesNotBlockLaterHealthyRule() {
        long badRule = seedRule(REQ_A, CARD_ID, USER_ID, 7);
        seedCard(CARD_B, USER_B, 1, BALANCE_FEN);
        long goodRule = seedRule(REQ_B, CARD_B, USER_B, 7);
        // 先扫到的那条（ID 小）注定失败：卡清零
        jdbc.update("UPDATE ws_card SET BALANCE_AMOUNT=0 WHERE ID=?", CARD_ID);
        assertTrue(badRule < goodRule, "夹具必须保证坏规则排在前面，否则本用例证明不了隔离");

        String due = DeliveryClock.plusHours(anchorOf(goodRule), 24 * 8);
        assertEquals(1, worker.runOnce(due), "坏规则失败，后面那条合法规则仍须生成");

        assertEquals(1, orderCount(DeliveryOrderNo.deriveAutoRefill(USER_B, goodRule, 1)));
        assertEquals(0, orderCount(DeliveryOrderNo.deriveAutoRefill(USER_ID, badRule, 1)));
        assertEquals(1, failEventCount(badRule, 1));
        assertEquals(0, failEventCount(goodRule, 1));
        assertEquals(BALANCE_FEN - PERIOD_FEN * 2, cardBalance(CARD_B), "好规则正常扣款");
        assertEquals(0L, cardBalance(CARD_ID), "坏规则零扣款");
    }

    /**
     * 11b：非业务异常（如 INTERVAL_DAYS 为 NULL 的 NPE）同样不得阻塞后续规则——
     * 它走的是外层逐规则隔离的 catch，与内层 JbkException 业务失败是两条路径。
     */
    @Test
    void auditWriteFailureOnOneRuleDoesNotBlockLaterRule() {
        long badRule = seedRule(REQ_A, CARD_ID, USER_ID, 7);
        seedCard(CARD_B, USER_B, 1, BALANCE_FEN);
        long goodRule = seedRule(REQ_B, CARD_B, USER_B, 7);
        assertTrue(badRule < goodRule, "夹具必须保证坏规则排在前面");
        long badCardBefore = cardBalance(CARD_ID);
        // 先让第一条规则业务失败（余额清零），再让它那条失败留痕的写入也抛非业务异常
        jdbc.update("UPDATE ws_card SET BALANCE_AMOUNT=0 WHERE ID=?", CARD_ID);
        org.mockito.Mockito.doThrow(new IllegalStateException("审计表暂时不可用"))
                .when(domainEventService).recordReliableOnceIndependent(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.startsWith("AUTO_REFILL_FAIL:"),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());

        String due = DeliveryClock.plusHours(anchorOf(goodRule), 24 * 8);
        assertEquals(1, worker.runOnce(due), "留痕写入抛非业务异常后，后面那条合法规则仍须生成");

        assertEquals(1, orderCount(DeliveryOrderNo.deriveAutoRefill(USER_B, goodRule, 1)));
        assertEquals(0, orderCount(DeliveryOrderNo.deriveAutoRefill(USER_ID, badRule, 1)));
        assertEquals(BALANCE_FEN - PERIOD_FEN * 2, cardBalance(CARD_B), "好规则正常扣款");
        assertEquals(0L, cardBalance(CARD_ID), "坏规则零扣款");
        assertEquals(badCardBefore, BALANCE_FEN - PERIOD_FEN, "夹具自检：清零前坏卡只扣过首单");
    }

    // ==================== 12. 非法业务时间直接拒绝 ====================

    @Test
    void illegalBusinessTimeIsRejectedBeforeAnyScan() {
        long ruleId = seedRule(REQ_A, CARD_ID, USER_ID, 7);
        long before = cardBalance(CARD_ID);

        for (String bad : new String[]{"", "2026080409", "20260804090000000", "2026-08-04 09:00:00", "abcdefghijklmn"}) {
            assertThrows(JbkException.class, () -> orderService.generateAutoRefillDueOrders(bad),
                    "非法业务时间必须直接拒绝：" + bad);
        }
        assertThrows(JbkException.class, () -> orderService.generateAutoRefillDueOrders(null));

        assertEquals(1, count("ws_order"), "拒绝路径零生成");
        assertEquals(before, cardBalance(CARD_ID));
        assertEquals(0, failEventCount(ruleId, 1), "整体拒绝不是单条规则失败，不得留规则级证据");
    }

    // ==================== 补充：停用的规则不参与扫描 ====================

    @Test
    void disabledRuleIsSkipped() {
        long ruleId = seedRule(REQ_A, CARD_ID, USER_ID, 7);
        String due = DeliveryClock.plusHours(anchorOf(ruleId), 24 * 8);
        long before = cardBalance(CARD_ID);
        jdbc.update("UPDATE ws_delivery_auto_rule SET RULE_STATUS=2 WHERE ID=?", ruleId);

        assertEquals(0, worker.runOnce(due));

        assertEquals(1, count("ws_order"));
        assertEquals(before, cardBalance(CARD_ID), "已停用规则不得扣款");
        assertEquals(0, allFailEventCount(), "停用不是失败，不留失败证据");
    }

    // ==================== 补充：时钟由 Worker 透传，生成器自身校验 ====================

    @Test
    void workerSwallowsIllegalClockRejectionAndChargesNothing() {
        long ruleId = seedRule(REQ_A, CARD_ID, USER_ID, 7);
        long before = cardBalance(CARD_ID);

        // Worker 整轮兜异常：非法时钟被生成器拒绝后，调度不得崩、更不得扣款
        assertEquals(0, worker.runOnce("not-a-time"));

        assertEquals(1, count("ws_order"));
        assertEquals(before, cardBalance(CARD_ID));
        assertEquals(0, failEventCount(ruleId, 1));
        // 合法时钟随后仍能正常工作（证明上一轮异常没有污染状态）
        assertEquals(1, worker.runOnce(DateUtils.time().substring(0, 0)
                + DeliveryClock.plusHours(anchorOf(ruleId), 24 * 8)));
    }

    // ==================== S2. 用户自助管理（查看/暂停/恢复/取消） ====================

    /** S2：规则只对本人可见；他人列表为空（不泄露存在性）。 */
    @Test
    void rulesVisibleOnlyToOwner() {
        long ruleId = seedRule(REQ_A, CARD_ID, USER_ID, 7);
        var mine = miniAutoRuleService.listMine(USER_ID);
        assertEquals(1, mine.size());
        assertEquals(ruleId, mine.get(0).getId());
        assertEquals("纯净水", mine.get(0).getWaterTypeName(), "水种名关联 ws_water_type 派生");
        org.junit.jupiter.api.Assertions.assertNotNull(mine.get(0).getNextDueTime(), "启用规则必给下次到期时间");
        assertEquals(0, miniAutoRuleService.listMine(USER_ID + 1).size(), "他人列表恒空");
    }

    /** S2：他人规则直达操作拒绝，报文与「不存在」同形状，且状态零变化。 */
    @Test
    void othersRuleActionsRejectedWithoutExistenceLeak() {
        long ruleId = seedRule(REQ_A, CARD_ID, USER_ID, 7);
        long stranger = USER_ID + 1;
        for (Runnable action : new Runnable[] {
                () -> miniAutoRuleService.pause(stranger, ruleId),
                () -> miniAutoRuleService.cancel(stranger, ruleId) }) {
            com.jbk.tool.exception.JbkException rejected =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            com.jbk.tool.exception.JbkException.class, action::run);
            org.junit.jupiter.api.Assertions.assertTrue(
                    rejected.getMsg().contains("规则不存在"), "他人规则与不存在规则同报文");
        }
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT RULE_STATUS FROM ws_delivery_auto_rule WHERE ID=?", Integer.class, ruleId),
                "越权操作零状态变化");
    }

    /** S2：暂停后 Worker 不生成订单。 */
    @Test
    void pausedRuleGeneratesNothing() {
        long ruleId = seedRule(REQ_A, CARD_ID, USER_ID, 7);
        miniAutoRuleService.pause(USER_ID, ruleId);
        String due = DeliveryClock.plusHours(anchorOf(ruleId), 24 * 8);
        assertEquals(0, worker.runOnce(due), "停用规则不在扫描集");
        assertEquals(1, count("ws_order"), "只有首单");
        assertEquals(BALANCE_FEN - PERIOD_FEN, cardBalance(CARD_ID), "零追加扣款");
    }

    /** S2：恢复后只生成当前到期期次，不追补暂停期间的历史期。 */
    @Test
    void resumedRuleGeneratesOnlyCurrentPeriod() {
        long ruleId = seedRule(REQ_A, CARD_ID, USER_ID, 7);
        miniAutoRuleService.pause(USER_ID, ruleId);
        String due3 = DeliveryClock.plusHours(anchorOf(ruleId), 24 * 22);
        assertEquals(0, worker.runOnce(due3), "暂停期间零生成");
        miniAutoRuleService.resume(USER_ID, ruleId);
        assertEquals(1, worker.runOnce(due3), "恢复后只生成当前期");
        String expectedNo = DeliveryOrderNo.deriveAutoRefill(USER_ID, ruleId, 3);
        assertEquals(1, orderCount(expectedNo), "生成的是第3期（当前期），不是暂停期间的第1/2期");
        assertEquals(2, count("ws_order"), "首单+当前期，历史期不追补");
    }

    /** S2：取消为终态——Worker 永不再生成，恢复操作永不匹配前态。 */
    @Test
    void cancelledRulePermanentlyStopsGeneration() {
        long ruleId = seedRule(REQ_A, CARD_ID, USER_ID, 7);
        miniAutoRuleService.cancel(USER_ID, ruleId);
        String due = DeliveryClock.plusHours(anchorOf(ruleId), 24 * 8);
        assertEquals(0, worker.runOnce(due), "已取消规则零生成");
        com.jbk.tool.exception.JbkException rejected = org.junit.jupiter.api.Assertions.assertThrows(
                com.jbk.tool.exception.JbkException.class,
                () -> miniAutoRuleService.resume(USER_ID, ruleId));
        org.junit.jupiter.api.Assertions.assertTrue(rejected.getMsg().contains("不支持恢复"),
                "已取消不可恢复");
        assertEquals(0, worker.runOnce(DeliveryClock.plusHours(anchorOf(ruleId), 24 * 15)),
                "后续期次同样零生成");
        assertEquals(1, count("ws_order"));
        assertEquals(BALANCE_FEN - PERIOD_FEN, cardBalance(CARD_ID), "取消后零扣款");
    }

    /** S2：取消与 Worker 扫描并发——取消成功后绝不再产生新订单（期次创单事务内锁定复核）。 */
    @Test
    void cancelConcurrentWithWorkerNeverGeneratesAfterCancel() throws Exception {
        long ruleId = seedRule(REQ_A, CARD_ID, USER_ID, 7);
        String due = DeliveryClock.plusHours(anchorOf(ruleId), 24 * 8);
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            pool.invokeAll(java.util.List.of(
                    () -> {
                        barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                        miniAutoRuleService.cancel(USER_ID, ruleId);
                        return true;
                    },
                    () -> {
                        barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                        worker.runOnce(due);
                        return true;
                    }), 30, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        // 竞态两序都合法：worker 先→本期 1 单后取消；cancel 先→本期零单。
        // 不变式：取消已成功，此后任何期次扫描恒零新增、零追加扣款
        int ordersAfterRace = count("ws_order");
        org.junit.jupiter.api.Assertions.assertTrue(ordersAfterRace <= 2, "至多首单+竞态期1单");
        assertEquals(3, (int) jdbc.queryForObject(
                "SELECT RULE_STATUS FROM ws_delivery_auto_rule WHERE ID=?", Integer.class, ruleId),
                "取消恒成功（终态）");
        assertEquals(0, worker.runOnce(DeliveryClock.plusHours(anchorOf(ruleId), 24 * 15)),
                "取消后的后续期次恒零生成");
        assertEquals(ordersAfterRace, count("ws_order"), "取消后订单数冻结");
    }

    @Test
    @DisplayName("G7-B08 同款补货规则不得并存：第二次相同水种/规格/地址被拒且零副作用")
    void sameShapeRuleIsRejectedWhileAlive() {
        seedCard(CARD_ID, USER_ID, 1, BALANCE_FEN);
        long ruleId = seedRule(REQ_A, CARD_ID, USER_ID, 7);
        int ordersAfterFirst = count("ws_order");

        // 换一个 requestId 就绕过了创建幂等键——同款规则原本可以这样无限叠加，
        // Worker 每期给每条各扣一次款，用户界面上却只看得到「我设了个补货」
        JbkException rejected = assertThrows(JbkException.class,
                () -> orderService.createDeliveryOrder(autoBo(REQ_B, CARD_ID, 7), USER_ID));
        // 拒因必须来自**应用层预检**（「您已有一条…请先取消」），而不是库层撞键后的并发话术。
        // 只断言「含自动补货规则」的话，删掉预检也照样绿——库层索引会给出另一句同样含该词的拒因。
        assertTrue(rejected.getMessage().contains("请先在补货规则页取消"),
                "顺序场景应由预检拒绝，实际：" + rejected.getMessage());
        assertEquals(1, count("ws_delivery_auto_rule"), "被拒不得留下第二条规则");
        assertEquals(ordersAfterFirst, count("ws_order"), "被拒不得留下订单");

        // 取消后同款可以重新建：唯一键只在存活期(1/2)生效，已取消(3)不占位
        jdbc.update("UPDATE ws_delivery_auto_rule SET RULE_STATUS=3 WHERE ID=?", ruleId);
        long rebuilt = seedRule(REQ_B, CARD_ID, USER_ID, 7);
        assertNotEquals(ruleId, rebuilt, "取消后应能建出新规则");
        assertEquals(2, count("ws_delivery_auto_rule"));
    }

    @Test
    @DisplayName("G7-B08 并发同款：库层唯一索引裁出唯一赢家，输方按业务拒绝而非幂等命中")
    void concurrentSameShapeRulesLeaveExactlyOne() throws Exception {
        seedCard(CARD_ID, USER_ID, 1, BALANCE_FEN);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger ok = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger rejected = new java.util.concurrent.atomic.AtomicInteger();
        try {
            for (String req : java.util.List.of(REQ_A, REQ_B)) {
                pool.submit(() -> {
                    go.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    try {
                        orderService.createDeliveryOrder(autoBo(req, CARD_ID, 7), USER_ID);
                        ok.incrementAndGet();
                    }
                    catch (RuntimeException expected) {
                        // 并发输方必须拿到「刚刚被创建」而不是预检那句：预检时它确实还没有
                        assertTrue(String.valueOf(expected.getMessage()).contains("刚刚被创建"),
                                "并发输方拒因应为竞态话术，实际：" + expected.getMessage());
                        rejected.incrementAndGet();
                    }
                    return true;
                });
            }
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS));
        }
        finally {
            pool.shutdownNow();
        }
        // 两个请求各自预检都会看到「还没有同款」——应用层判重在这里必然双开，
        // 唯一裁决者只能是库层索引
        assertEquals(1, ok.get(), "恰一个请求成功");
        assertEquals(1, rejected.get(), "另一个必须被拒");
        assertEquals(1, count("ws_delivery_auto_rule"), "并发后存活规则恰一条");
    }
}
