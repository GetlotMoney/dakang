package com.jbk.serve.service.delivery.impl;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.delivery.WsDeliveryAutoRuleMapper;
import com.jbk.serve.mapper.delivery.WsDeliveryTaskMapper;
import com.jbk.serve.mapper.message.WsMessageMapper;
import com.jbk.serve.mapper.product.WsWaterTypeMapper;
import com.jbk.serve.mapper.station.WsStationMapper;
import com.jbk.serve.mapper.aftersale.WsCardEntitlementBatchMapper;
import com.jbk.serve.mapper.aftersale.WsEntitlementAllocationMapper;
import com.jbk.serve.mapper.trade.TradeCardMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.service.aftersale.batch.EntitlementFixture;
import com.jbk.serve.service.aftersale.batch.EntitlementLedger;
import com.jbk.serve.service.aftersale.IAfterSaleActionTxService;
import com.jbk.serve.service.delivery.DeliveryClock;
import com.jbk.serve.service.delivery.DeliveryOrderNo;
import com.jbk.serve.service.delivery.IDeliveryOrderService;
import com.jbk.serve.service.delivery.IDeliveryOrderTxService;
import com.jbk.serve.service.message.IWsMessageService;
import com.jbk.serve.service.message.impl.WsMessageServiceImpl;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.data.delivery.bo.DeliveryCreateBo;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E-03 A2 创单事务的<b>真实 MySQL + 真实 Spring 事务</b>集成测试（搭法对齐 WaterOrderTxDbTest）。
 *
 * <p>钉住单测 Mock 证不了的事务事实：</p>
 * <ol>
 *   <li><b>创单原子性</b>：扣款+唯一流水+订单+任务同生共死；任一环失败整体回滚零残留。</li>
 *   <li><b>重复创单幂等</b>：同 userId+requestId 恒返回同单，不重复扣款不重复建任务（规则3/4）。</li>
 *   <li><b>预约语义</b>：预约时间必须晚于当前时间；SCHEDULED_TIME 快照落任务。</li>
 *   <li><b>自动补货只生成一次</b>：同期重复生成被 uk_order_no 收敛（规则19）。</li>
 * </ol>
 *
 * <p>无 Docker 环境自动跳过。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = DeliveryOrderTxDbTest.Ctx.class)
class DeliveryOrderTxDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_delivery_it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static final long USER_ID = 9L;
    private static final long OTHER_USER = 7L;
    private static final long CARD_ID = 100L;
    private static final long STATION_ID = 41L;
    private static final long WATER_TYPE_ID = 8L;
    private static final long BALANCE_FEN = 10_000L;
    private static final String REQ_A = "1f4a2b3c-4d5e-4f60-8a9b-0c1d2e3f4a5b";
    private static final String REQ_B = "2b4a2b3c-4d5e-4f60-8a9b-0c1d2e3f4a5b";

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
            // 生产同一份 XML：原子扣减（trade）+ 可接任务池（delivery），不在测试里复刻 SQL
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath:mapper/*/*.xml"));
            return new SqlSessionTemplate(factory.getObject());
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
        MapperFactoryBean<WsCardEntitlementBatchMapper> wsCardEntitlementBatchMapper(SqlSessionTemplate t) {
            // 包D-4：配送创单扣减同事务写权益分摊，故本上下文必须提供这两个 Mapper
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
            // 真实台账而不是 Mock：分摊要摊到真表上，才验证得了「卡扣了、批次也扣了」
            return new EntitlementLedger(batchMapper, allocationMapper, tradeCardMapper, walletFlowMapper);
        }

        private static <M> MapperFactoryBean<M> mapper(Class<M> type, SqlSessionTemplate template) {
            MapperFactoryBean<M> bean = new MapperFactoryBean<>(type);
            bean.setSqlSessionTemplate(template);
            return bean;
        }

        @Bean
        IWsDomainEventService domainEventService() {
            // 这里只验证资金/任务事实；可靠审计（与业务同事务、撞键读回核验）由
            // WsDomainEventTxDbTest 与 DeliveryFulfillmentTxDbTest 用真实现钉住
            return Mockito.mock(IWsDomainEventService.class);
        }

        @Bean
        IWsMessageService messageService() {
            return new WsMessageServiceImpl();
        }

        @Bean
        IAfterSaleActionTxService afterSaleActionTxService() {
            // 本测试只钉创单的资金/任务事实，创单路径一次都不碰售后内核；
            // 取消路径（唯一的调用方）由 DeliveryFulfillmentTxDbTest 用真实现覆盖
            return Mockito.mock(IAfterSaleActionTxService.class);
        }

        @Bean
        IDeliveryOrderTxService deliveryOrderTxService() {
            return new DeliveryOrderTxServiceImpl();
        }

        @Bean
        com.jbk.serve.service.settlement.IInviteService inviteService() {
            // E2E-08 归因快照协作方：mock 恒返回 null 推荐人，归因行为由 AttributionDbTest 锁定
            return org.mockito.Mockito.mock(com.jbk.serve.service.settlement.IInviteService.class);
        }

        @Bean
        IDeliveryOrderService deliveryOrderService() {
            return new DeliveryOrderServiceImpl();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    @Autowired
    private IDeliveryOrderService orderService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        DeliveryDbSchema.createAll(jdbc);
        DeliveryDbSchema.truncateAll(jdbc);
        jdbc.update("INSERT INTO ws_station(ID,STATION_NAME,STATION_CODE,STATION_STATUS) VALUES(?,?,?,1)",
                STATION_ID, "测试站", "ST-41");
        jdbc.update("INSERT INTO ws_water_type(ID,WATER_NAME,WATER_SORT,DEFAULT_FLAG,WATER_STATUS) "
                + "VALUES(?,?,1,2,1)", WATER_TYPE_ID, "纯净水");
        seedCard(USER_ID, 1, BALANCE_FEN, 0);
    }

    private void seedCard(long userId, int cardStatus, long fen, int dataStatus) {
        seedCard(userId, cardStatus, fen, 0L, dataStatus);
    }

    private void seedCard(long userId, int cardStatus, long fen, long ml, int dataStatus) {
        jdbc.update("DELETE FROM ws_card WHERE ID=?", CARD_ID);
        jdbc.update("INSERT INTO ws_card(ID,DATA_STATUS,CARD_NO,CARD_TYPE,USER_ID,BALANCE_AMOUNT,BALANCE_ML,"
                        + "SCOPE_JSON,EXPIRE_TIME,CARD_STATUS) VALUES(?,?,?,1,?,?,?,NULL,NULL,?)",
                CARD_ID, dataStatus, "VC-TEST-100", userId, fen, ml, cardStatus);
        // 包D-4：卡是裸 INSERT，补历史聚合批次以满足「批次剩余合计 == 卡聚合值」
        EntitlementFixture.seedLegacyBatch(jdbc, CARD_ID, userId, fen, ml);
    }

    private DeliveryCreateBo bo(String requestId, int mode) {
        DeliveryCreateBo bo = new DeliveryCreateBo();
        bo.setRequestId(requestId);
        bo.setCardId(String.valueOf(CARD_ID));
        bo.setStationId(String.valueOf(STATION_ID));
        bo.setWaterTypeId(String.valueOf(WATER_TYPE_ID));
        bo.setContainerSpec("20L桶");
        bo.setDeliveryCount(2);
        bo.setPlanReturnCount(1);
        bo.setReceiveAddress("光谷软件园 A1 栋 502");
        bo.setReceivePhone("13900001111");
        bo.setDeliveryMode(mode);
        return bo;
    }

    private long cardBalance() {
        return jdbc.queryForObject("SELECT BALANCE_AMOUNT FROM ws_card WHERE ID=?", Long.class, CARD_ID);
    }

    private long cardMl() {
        return jdbc.queryForObject("SELECT BALANCE_ML FROM ws_card WHERE ID=?", Long.class, CARD_ID);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void assertZeroResidue() {
        // 包D-4：拒绝路径零分摊——留下分摊行等于批次被扣而卡没扣，方向相反同样是账本断裂
        assertEquals(0, count("ws_entitlement_allocation"), "分摊零新增");
        assertEquals(BALANCE_FEN, cardBalance(), "余额必须原封不动");
        assertEquals(0, count("ws_order"), "订单零新增");
        assertEquals(0, count("ws_wallet_flow"), "流水零新增");
        assertEquals(0, count("ws_delivery_task"), "任务零新增");
        assertEquals(0, count("ws_delivery_auto_rule"), "规则零新增");
        assertEquals(0, count("ws_message"), "消息零新增");
    }

    // ================= 1：创单原子性（扣款+流水+订单+任务+消息同生共死） =================

    @Test
    void createDeductsOnceAndWritesOrderTaskFlowMessageAtomically() {
        IDeliveryOrderService.CreatedDelivery created = orderService.createDeliveryOrder(bo(REQ_A, 1), USER_ID);

        // 金额快照（规则1/2）：20L桶 1300×2=2600 水费 + 200×2=400 配送费 = 3000，余额支付不扣水量
        assertEquals(BALANCE_FEN - 3_000L, cardBalance());
        assertEquals(2_600L, created.task().getWaterAmount());
        assertEquals(400L, created.task().getDeliveryFee());
        assertEquals(3_000L, created.order().getOrderAmount());
        assertEquals(2, created.order().getOrderStatus(), "卡即时扣款成功=直接已支付（规则5）");
        assertEquals(1, created.task().getTaskStatus(), "即时单付完即入池");
        assertEquals(1, created.task().getVersion());
        assertEquals(DeliveryOrderNo.deriveTaskNo(created.order().getOrderNo()), created.task().getTaskNo());

        assertEquals(1, count("ws_order"));
        assertEquals(1, count("ws_delivery_task"));
        assertEquals(1, count("ws_wallet_flow"));
        assertEquals(-3_000L, jdbc.queryForObject("SELECT AMOUNT_CHANGE FROM ws_wallet_flow", Long.class));
        assertEquals(BALANCE_FEN - 3_000L, jdbc.queryForObject("SELECT AMOUNT_AFTER FROM ws_wallet_flow", Long.class));
        assertEquals(7, jdbc.queryForObject("SELECT FLOW_TYPE FROM ws_wallet_flow", Integer.class), "配送扣减流水类型");
        assertEquals("DELIVERY:" + created.order().getOrderNo(),
                jdbc.queryForObject("SELECT BIZ_IDEMPOTENCY_KEY FROM ws_wallet_flow", String.class), "规则3幂等键");

        // 包D-4 正向断言：配送扣减必须同事务摊到权益批次，键与上面的流水幂等键同源。
        // 删掉 createPaidDeliveryOrder 里的 allocateOnConsume 那一段，本段立刻红
        assertEquals(1, count("ws_entitlement_allocation"));
        assertEquals("DELIVERY:" + created.order().getOrderNo(),
                jdbc.queryForObject("SELECT BIZ_KEY FROM ws_entitlement_allocation", String.class));
        assertEquals(3_000L,
                jdbc.queryForObject("SELECT ALLOC_AMOUNT_FEN FROM ws_entitlement_allocation", Long.class));
        assertEquals(0L,
                jdbc.queryForObject("SELECT ALLOC_WATER_ML FROM ws_entitlement_allocation", Long.class),
                "payWay=2 全余额支付，水量维度不分摊");
        assertEquals(cardBalance(), EntitlementFixture.sumRemainFen(jdbc, CARD_ID),
                "不变式：逐卡批次剩余合计恒等于卡聚合值");
        assertEquals(1, count("ws_message"), "创建节点站内消息与创单同事务");
    }

    // ================= 2：重复创单幂等（规则3/4） =================

    @Test
    void repeatSameRequestReturnsSameOrderWithoutDoubleChargeOrSecondTask() {
        IDeliveryOrderService.CreatedDelivery first = orderService.createDeliveryOrder(bo(REQ_A, 1), USER_ID);
        IDeliveryOrderService.CreatedDelivery second = orderService.createDeliveryOrder(bo(REQ_A, 1), USER_ID);

        assertEquals(first.order().getOrderNo(), second.order().getOrderNo());
        assertEquals(first.task().getTaskNo(), second.task().getTaskNo());
        assertEquals(BALANCE_FEN - 3_000L, cardBalance(), "只扣一次款");
        assertEquals(1, count("ws_order"));
        assertEquals(1, count("ws_delivery_task"), "不重复建任务");
        assertEquals(1, count("ws_wallet_flow"), "不重复入流水");

        // 不同 requestId 是另一笔业务：再扣一次、各键独立
        IDeliveryOrderService.CreatedDelivery third = orderService.createDeliveryOrder(bo(REQ_B, 1), USER_ID);
        assertTrue(!third.order().getOrderNo().equals(first.order().getOrderNo()));
        assertEquals(BALANCE_FEN - 6_000L, cardBalance());
    }

    /**
     * E2E-03 验收 P1-1：同 requestId 重放但更换关键参数是冲突而不是重放——
     * 逐字段核验创单冻结快照，任一不一致必须拒绝且零副作用（不扣款、不建单、不发消息）。
     */
    @Test
    void replayWithChangedParametersIsRejectedWithZeroSideEffects() {
        IDeliveryOrderService.CreatedDelivery first = orderService.createDeliveryOrder(bo(REQ_A, 1), USER_ID);
        long balanceAfterFirst = cardBalance();

        // 改数量：拒绝
        DeliveryCreateBo changedCount = bo(REQ_A, 1);
        changedCount.setDeliveryCount(3);
        JbkException countEx = assertThrows(JbkException.class,
                () -> orderService.createDeliveryOrder(changedCount, USER_ID));
        assertTrue(countEx.getMessage().contains("不可更换配送参数"), "实际=" + countEx.getMessage());

        // 改地址：拒绝
        DeliveryCreateBo changedAddress = bo(REQ_A, 1);
        changedAddress.setReceiveAddress("光谷软件园 B2 栋 1701");
        JbkException addressEx = assertThrows(JbkException.class,
                () -> orderService.createDeliveryOrder(changedAddress, USER_ID));
        assertTrue(addressEx.getMessage().contains("不可更换配送参数"), "实际=" + addressEx.getMessage());

        // 改水种：拒绝（幂等命中核验先于档案校验，不存在的水种同样按参数冲突拒）
        DeliveryCreateBo changedWaterType = bo(REQ_A, 1);
        changedWaterType.setWaterTypeId("9999");
        JbkException waterEx = assertThrows(JbkException.class,
                () -> orderService.createDeliveryOrder(changedWaterType, USER_ID));
        assertTrue(waterEx.getMessage().contains("不可更换配送参数"), "实际=" + waterEx.getMessage());

        // 三次拒绝全部零副作用：余额、订单、任务、流水、消息均停留在首单水位
        assertEquals(balanceAfterFirst, cardBalance(), "拒绝不得扣款");
        assertEquals(1, count("ws_order"), "拒绝不得建单");
        assertEquals(1, count("ws_delivery_task"), "拒绝不得建任务");
        assertEquals(1, count("ws_wallet_flow"), "拒绝不得入流水");
        assertEquals(1, count("ws_message"), "拒绝不得发消息");

        // 对照：同参重放仍命中原单（既有幂等语义不受栅栏影响）
        IDeliveryOrderService.CreatedDelivery replay = orderService.createDeliveryOrder(bo(REQ_A, 1), USER_ID);
        assertEquals(first.order().getOrderNo(), replay.order().getOrderNo());
        assertEquals(balanceAfterFirst, cardBalance());
    }

    @Test
    void foreignUserReplayOfSameRequestIdCannotHijackExistingOrder() {
        orderService.createDeliveryOrder(bo(REQ_A, 1), USER_ID);
        // 他人用同 requestId：派生出不同订单号，且不会命中我方订单；此处只需不抛出串单
        seedForeignCardAndCreate();
        assertEquals(2, count("ws_order"));
        // 两单归属各自用户
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_order WHERE USER_ID=?", Integer.class, USER_ID));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_order WHERE USER_ID=?", Integer.class, OTHER_USER));
    }

    private void seedForeignCardAndCreate() {
        jdbc.update("INSERT INTO ws_card(ID,DATA_STATUS,CARD_NO,CARD_TYPE,USER_ID,BALANCE_AMOUNT,BALANCE_ML,"
                + "SCOPE_JSON,EXPIRE_TIME,CARD_STATUS) VALUES(101,0,'VC-TEST-101',1,?,?,0,NULL,NULL,1)",
                OTHER_USER, BALANCE_FEN);
        // 包D-4：他人卡同样是裸 INSERT，缺批次会在分摊处 fail-closed（这正是台账该有的行为）
        EntitlementFixture.seedLegacyBatch(jdbc, 101L, OTHER_USER, BALANCE_FEN, 0L);
        DeliveryCreateBo foreign = bo(REQ_A, 1);
        foreign.setCardId("101");
        orderService.createDeliveryOrder(foreign, OTHER_USER);
    }

    // ================= 3：失败整体回滚零残留 =================

    /**
     * 审计 P0-1：配送链与取水链共用 settleExpired——过期批次权益不得支付配送单。
     * 卡 10000 分中 8000 属过期批次：清算后可用 2000，3000 的配送单必须拒绝且零残留；
     * 把过期批次改回未过期后同单成功，证明拦截的确是「过期」而非金额本身。
     */
    @Test
    void expiredBatchEntitlementCannotPayForDelivery() {
        jdbc.update("UPDATE ws_card_entitlement_batch SET GRANT_AMOUNT_FEN=2000, REMAIN_AMOUNT_FEN=2000 "
                + "WHERE CARD_ID=" + CARD_ID);
        jdbc.update("INSERT INTO ws_card_entitlement_batch(CARD_ID, USER_ID, SOURCE_TYPE, "
                + "PAY_AMOUNT_FEN, GRANT_AMOUNT_FEN, GRANT_BONUS_FEN, GRANT_WATER_ML, "
                + "REMAIN_AMOUNT_FEN, REMAIN_WATER_ML, EXPIRE_TIME, SCOPE_JSON, BATCH_STATUS, "
                + "REFUNDED_AMOUNT_FEN, VERSION, DATA_STATUS, CREATE_TIME) "
                + "VALUES(" + CARD_ID + ", " + USER_ID + ", 4, 0, 8000, 8000, 0, 8000, 0, "
                + "'20250101000000', NULL, 6, 0, 1, 0, '20240101000000')");

        assertThrows(JbkException.class, () -> orderService.createDeliveryOrder(bo(REQ_A, 1), USER_ID));
        assertEquals(BALANCE_FEN, cardBalance(), "拒绝单整体回滚（清算与扣减同生共死），零残留");
        assertEquals(0, count("ws_order"));
        assertEquals(0, count("ws_wallet_flow"));

        // 同额度但批次未过期：成功——证明上面拦住的正是「过期」
        jdbc.update("UPDATE ws_card_entitlement_batch SET EXPIRE_TIME='20991231000000' "
                + "WHERE CARD_ID=" + CARD_ID + " AND SOURCE_TYPE=4");
        orderService.createDeliveryOrder(bo(REQ_A, 1), USER_ID);
        assertEquals(BALANCE_FEN - 3_000L, cardBalance());
    }

    @Test
    void insufficientBalanceRejectsWithZeroResidue() {
        seedCard(USER_ID, 1, 2_999L, 0);
        JbkException ex = assertThrows(JbkException.class,
                () -> orderService.createDeliveryOrder(bo(REQ_A, 1), USER_ID));
        assertTrue(ex.getMessage().contains("余额不足"), "实际=" + ex.getMessage());
        assertEquals(2_999L, cardBalance());
        assertEquals(0, count("ws_order"));
        assertEquals(0, count("ws_wallet_flow"));
        assertEquals(0, count("ws_delivery_task"));
        assertEquals(0, count("ws_message"));
        assertEquals(0, count("ws_entitlement_allocation"), "包D-4：拒绝路径零分摊");
    }

    @Test
    void frozenForeignAndDeletedCardsAreRejectedWithZeroResidue() {
        seedCard(USER_ID, 2, BALANCE_FEN, 0);
        assertThrows(JbkException.class, () -> orderService.createDeliveryOrder(bo(REQ_A, 1), USER_ID));
        assertZeroResidue();

        // 他人卡：配送单只允许本人卡支付
        seedCard(OTHER_USER, 1, BALANCE_FEN, 0);
        JbkException foreign = assertThrows(JbkException.class,
                () -> orderService.createDeliveryOrder(bo(REQ_A, 1), USER_ID));
        assertTrue(foreign.getMessage().contains("不存在或不属于当前用户"), "实际=" + foreign.getMessage());
        assertEquals(0, count("ws_order"));

        // 逻辑删除卡：锁得到但必须显式拒绝
        seedCard(USER_ID, 1, BALANCE_FEN, 1);
        assertThrows(JbkException.class, () -> orderService.createDeliveryOrder(bo(REQ_A, 1), USER_ID));
        assertEquals(0, count("ws_order"));
    }

    @Test
    void closedStationAndDisabledWaterTypeAreRejectedWithZeroResidue() {
        jdbc.update("UPDATE ws_station SET STATION_STATUS=2 WHERE ID=?", STATION_ID);
        JbkException closed = assertThrows(JbkException.class,
                () -> orderService.createDeliveryOrder(bo(REQ_A, 1), USER_ID));
        assertTrue(closed.getMessage().contains("暂停营业"), "实际=" + closed.getMessage());
        assertZeroResidue();

        jdbc.update("UPDATE ws_station SET STATION_STATUS=1 WHERE ID=?", STATION_ID);
        jdbc.update("UPDATE ws_water_type SET WATER_STATUS=2 WHERE ID=?", WATER_TYPE_ID);
        assertThrows(JbkException.class, () -> orderService.createDeliveryOrder(bo(REQ_A, 1), USER_ID));
        assertZeroResidue();
    }

    @Test
    void invalidReceiverInputsAreRejectedWithZeroResidue() {
        DeliveryCreateBo badPhone = bo(REQ_A, 1);
        badPhone.setReceivePhone("12345");
        assertThrows(JbkException.class, () -> orderService.createDeliveryOrder(badPhone, USER_ID));
        assertZeroResidue();

        DeliveryCreateBo badAddress = bo(REQ_A, 1);
        badAddress.setReceiveAddress("   ");
        assertThrows(JbkException.class, () -> orderService.createDeliveryOrder(badAddress, USER_ID));
        assertZeroResidue();
    }

    // ================= 3b：D-214 混合结算 payWay=3（水费扣水量 + 配送费扣余额） =================

    /**
     * payWay=3 成功单：同一事务双前态 CAS 落库——BALANCE_ML −40000（20L桶×2）、
     * BALANCE_AMOUNT −400（配送费）；流水恰一条且 AMOUNT/ML 双列、AFTER 双列为预期值；
     * ORDER_AMOUNT=配送费、任务 WATER_AMOUNT=0、快照三新字段（payWay/waterMl/priceWaterAmountFen）。
     */
    @Test
    void mlPayWayDeductsMlAndFeeAtomicallyWithSingleDualColumnFlow() {
        seedCard(USER_ID, 1, BALANCE_FEN, 50_000L, 0);
        DeliveryCreateBo bo = bo(REQ_A, 1);
        bo.setPayWay(3);
        IDeliveryOrderService.CreatedDelivery created = orderService.createDeliveryOrder(bo, USER_ID);

        assertEquals(10_000L, cardMl(), "水量按快照 waterMl 抵扣：50000-40000");
        assertEquals(BALANCE_FEN - 400L, cardBalance(), "余额只扣配送费");
        assertEquals(400L, created.order().getOrderAmount(), "ORDER_AMOUNT=本单实际应扣金额=配送费");
        assertEquals(3, created.order().getPayWay());
        assertEquals(0L, created.task().getWaterAmount(), "任务行 WATER_AMOUNT 同口径记 0");
        assertEquals(400L, created.task().getDeliveryFee(), "DELIVERY_FEE 不变");

        assertEquals(1, count("ws_wallet_flow"), "双支付方式流水仍恰一条");
        assertEquals(-400L, jdbc.queryForObject("SELECT AMOUNT_CHANGE FROM ws_wallet_flow", Long.class));
        assertEquals(-40_000L, jdbc.queryForObject("SELECT ML_CHANGE FROM ws_wallet_flow", Long.class));
        assertEquals(BALANCE_FEN - 400L, jdbc.queryForObject("SELECT AMOUNT_AFTER FROM ws_wallet_flow", Long.class));
        assertEquals(10_000L, jdbc.queryForObject("SELECT ML_AFTER FROM ws_wallet_flow", Long.class));
        assertEquals("DELIVERY:" + created.order().getOrderNo(),
                jdbc.queryForObject("SELECT BIZ_IDEMPOTENCY_KEY FROM ws_wallet_flow", String.class),
                "幂等键口径不随支付方式变化");

        cn.hutool.json.JSONObject snap = cn.hutool.json.JSONUtil.parseObj(
                jdbc.queryForObject("SELECT PACKAGE_SNAP FROM ws_order", String.class));
        assertEquals(3, snap.getInt("payWay"));
        assertEquals(40_000L, snap.getLong("waterMl"));
        assertEquals(2_600L, snap.getLong("priceWaterAmountFen"), "价目参考=原 waterAmountFen 语义");
        assertEquals(0L, snap.getLong("waterAmountFen"), "快照水费=实际应扣口径");

        // 包D-4：payWay=3 的两维在<b>同一条</b>分摊行上各记各的，绝不互相折算。
        // 该卡只有一条历史聚合批次，故两维都落在它上面，一条分摊行
        assertEquals(1, count("ws_entitlement_allocation"), "双支付方式分摊仍恰一条");
        assertEquals(400L,
                jdbc.queryForObject("SELECT ALLOC_AMOUNT_FEN FROM ws_entitlement_allocation", Long.class));
        assertEquals(40_000L,
                jdbc.queryForObject("SELECT ALLOC_WATER_ML FROM ws_entitlement_allocation", Long.class));
        assertEquals(cardBalance(), EntitlementFixture.sumRemainFen(jdbc, CARD_ID));
        assertEquals(cardMl(), EntitlementFixture.sumRemainMl(jdbc, CARD_ID));
    }

    @Test
    void mlPayWayInsufficientMlRejectsWithZeroResidue() {
        seedCard(USER_ID, 1, BALANCE_FEN, 39_999L, 0);
        DeliveryCreateBo bo = bo(REQ_A, 1);
        bo.setPayWay(3);
        JbkException ex = assertThrows(JbkException.class,
                () -> orderService.createDeliveryOrder(bo, USER_ID));
        assertTrue(ex.getMessage().contains("水卡水量不足以抵扣本单水量"), "实际=" + ex.getMessage());
        assertEquals(39_999L, cardMl(), "水量原封不动");
        assertEquals(BALANCE_FEN, cardBalance(), "余额原封不动");
        assertEquals(0, count("ws_order"));
        assertEquals(0, count("ws_wallet_flow"));
        assertEquals(0, count("ws_delivery_task"));
        assertEquals(0, count("ws_message"));
    }

    /**
     * 本包最关键的原子性性质：水量够、但余额不足以支付配送费——第一步 deductMl 已经
     * 命中 1 行，第二步 deductBalance 0 行抛出后，<b>已扣的水量必须随事务整体回滚</b>，
     * 不允许出现「水量被吃掉、单却没建成」的半截扣减。
     */
    @Test
    void mlPayWayFeeBalanceInsufficientRollsBackAlreadyDeductedMl() {
        seedCard(USER_ID, 1, 399L, 40_000L, 0);
        DeliveryCreateBo bo = bo(REQ_A, 1);
        bo.setPayWay(3);
        JbkException ex = assertThrows(JbkException.class,
                () -> orderService.createDeliveryOrder(bo, USER_ID));
        assertTrue(ex.getMessage().contains("水卡余额不足以支付配送费"), "实际=" + ex.getMessage());
        assertEquals(40_000L, cardMl(), "第一步已扣的水量必须随事务回滚");
        assertEquals(399L, cardBalance());
        assertEquals(0, count("ws_order"));
        assertEquals(0, count("ws_wallet_flow"));
        assertEquals(0, count("ws_delivery_task"));
        assertEquals(0, count("ws_message"));
    }

    /** 同参含 payWay 重放返回原单；同 requestId 改 payWay 拒绝且零副作用（冻结参数核验含 payWay）。 */
    @Test
    void mlPayWayReplayReturnsOriginalOrderAndPayWaySwitchIsRejected() {
        seedCard(USER_ID, 1, BALANCE_FEN, 50_000L, 0);
        DeliveryCreateBo first = bo(REQ_A, 1);
        first.setPayWay(3);
        IDeliveryOrderService.CreatedDelivery created = orderService.createDeliveryOrder(first, USER_ID);

        DeliveryCreateBo replay = bo(REQ_A, 1);
        replay.setPayWay(3);
        IDeliveryOrderService.CreatedDelivery hit = orderService.createDeliveryOrder(replay, USER_ID);
        assertEquals(created.order().getOrderNo(), hit.order().getOrderNo(), "同参含 payWay 重放命中原单");
        assertEquals(10_000L, cardMl(), "只扣一次水量");
        assertEquals(BALANCE_FEN - 400L, cardBalance(), "只扣一次配送费");
        assertEquals(1, count("ws_wallet_flow"));

        DeliveryCreateBo switched = bo(REQ_A, 1);
        switched.setPayWay(2);
        JbkException ex = assertThrows(JbkException.class,
                () -> orderService.createDeliveryOrder(switched, USER_ID));
        assertTrue(ex.getMessage().contains("不可更换配送参数"), "实际=" + ex.getMessage());
        assertEquals(10_000L, cardMl());
        assertEquals(BALANCE_FEN - 400L, cardBalance());
        assertEquals(1, count("ws_order"));
    }

    /** payWay=2 显式回归：金额、任务、流水与 null 缺省路径逐字段一致（字节级行为不变）。 */
    @Test
    void explicitBalancePayWayMatchesLegacyPath() {
        DeliveryCreateBo bo = bo(REQ_A, 1);
        bo.setPayWay(2);
        IDeliveryOrderService.CreatedDelivery created = orderService.createDeliveryOrder(bo, USER_ID);

        assertEquals(BALANCE_FEN - 3_000L, cardBalance());
        assertEquals(0L, cardMl(), "余额支付不扣水量");
        assertEquals(3_000L, created.order().getOrderAmount());
        assertEquals(2, created.order().getPayWay());
        assertEquals(2_600L, created.task().getWaterAmount());
        assertEquals(400L, created.task().getDeliveryFee());
        assertEquals(0L, jdbc.queryForObject("SELECT ML_CHANGE FROM ws_wallet_flow", Long.class));
        assertEquals(-3_000L, jdbc.queryForObject("SELECT AMOUNT_CHANGE FROM ws_wallet_flow", Long.class));
        cn.hutool.json.JSONObject snap = cn.hutool.json.JSONUtil.parseObj(
                jdbc.queryForObject("SELECT PACKAGE_SNAP FROM ws_order", String.class));
        assertEquals(2, snap.getInt("payWay"));
        assertEquals(2_600L, snap.getLong("waterAmountFen"), "payWay=2 实际应扣水费=价目水费");
        assertEquals(2_600L, snap.getLong("priceWaterAmountFen"));
    }

    // ================= 4：预约单（规则19 前半） =================

    @Test
    void scheduledOrderPersistsFutureTimeAndRejectsPastTime() {
        String future = DeliveryClock.plusHours(DateUtils.time(), 2);
        DeliveryCreateBo scheduled = bo(REQ_A, 2);
        scheduled.setScheduledTime(future);
        IDeliveryOrderService.CreatedDelivery created = orderService.createDeliveryOrder(scheduled, USER_ID);
        assertEquals(future, created.task().getScheduledTime(), "预约时间快照落任务");
        assertEquals(1, created.task().getTaskStatus(), "预约单同样落待接单，入池由查询时间语义控制");

        DeliveryCreateBo past = bo(REQ_B, 2);
        past.setScheduledTime("20200101000000");
        assertThrows(JbkException.class, () -> orderService.createDeliveryOrder(past, USER_ID));

        DeliveryCreateBo missing = bo(REQ_B, 2);
        missing.setScheduledTime(null);
        assertThrows(JbkException.class, () -> orderService.createDeliveryOrder(missing, USER_ID));
    }

    /**
     * E2E-03 复审 P1-1：预约时间过去之后的同参同 requestId 重放是幂等回看，不是新建——
     * 必须返回原单且零副作用，绝不能被「预约时间必须晚于当前时间」拦下（时效校验只属于新建单）。
     */
    @Test
    void scheduledReplayAfterTimePassedReturnsOriginalOrderNotTimeError() throws Exception {
        String scheduled = plusSeconds(DateUtils.time(), 2);
        DeliveryCreateBo create = bo(REQ_A, 2);
        create.setScheduledTime(scheduled);
        IDeliveryOrderService.CreatedDelivery first = orderService.createDeliveryOrder(create, USER_ID);

        Thread.sleep(3000); // 让预约时间真实过去，重放发生在「scheduledTime <= now」的时点

        DeliveryCreateBo replay = bo(REQ_A, 2);
        replay.setScheduledTime(scheduled);
        IDeliveryOrderService.CreatedDelivery second = orderService.createDeliveryOrder(replay, USER_ID);

        assertEquals(first.order().getOrderNo(), second.order().getOrderNo(), "重放必须命中原单");
        assertEquals(first.task().getTaskNo(), second.task().getTaskNo());
        assertEquals(1, count("ws_order"), "订单恰 1 条");
        assertEquals(1, count("ws_delivery_task"), "任务恰 1 条");
        assertEquals(1, count("ws_wallet_flow"), "扣款流水恰 1 条");
        assertEquals(BALANCE_FEN - 3_000L, cardBalance(), "只扣一次款");
    }

    /** 对照：全新 requestId 带已过去的预约时间仍必须被拒——新建单时效校验不得因重排失效。 */
    @Test
    void freshRequestWithPastScheduledTimeIsStillRejected() {
        DeliveryCreateBo past = bo(REQ_B, 2);
        past.setScheduledTime("20200101000000");
        JbkException ex = assertThrows(JbkException.class,
                () -> orderService.createDeliveryOrder(past, USER_ID));
        assertTrue(ex.getMessage().contains("预约时间必须晚于当前时间"), "实际=" + ex.getMessage());
        assertZeroResidue();
    }

    private static String plusSeconds(String time, long seconds) {
        return LocalDateTime.parse(time, DateUtils.COMPACT_FORMATTER)
                .plusSeconds(seconds).format(DateUtils.COMPACT_FORMATTER);
    }

    // ================= 4b：并发同请求（DuplicateKey 回收路径，E2E-03 复审 P1-1） =================

    /**
     * 两线程同 requestId 同参并发创建：一边正常落库、另一边穿透幂等预检后在事务内撞
     * uk_order_no（事务整体回滚含扣款），编排层捕获 DuplicateKey 重读既有单返回。
     * 两边都必须成功且拿到同一单，订单/任务/扣款各恰一次。
     */
    @Test
    void concurrentSameRequestBothSucceedWithExactlyOneOrderTaskAndCharge() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Callable<String>> jobs = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            jobs.add(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return orderService.createDeliveryOrder(bo(REQ_A, 1), USER_ID).order().getOrderNo();
            });
        }
        List<String> orderNos = new ArrayList<>();
        for (Future<String> f : pool.invokeAll(jobs)) {
            // 任何一边抛异常都算失败：并发同请求必须两边都拿到订单
            orderNos.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertEquals(orderNos.get(0), orderNos.get(1), "并发同请求必须收敛到同一订单号");
        assertEquals(1, count("ws_order"), "订单恰 1 条");
        assertEquals(1, count("ws_delivery_task"), "任务恰 1 条");
        assertEquals(1, count("ws_wallet_flow"), "扣款流水恰 1 条");
        assertEquals(BALANCE_FEN - 3_000L, cardBalance(), "并发下也只扣一次款");
    }

    // ================= 5：自动补货（规则19 后半：只生成一次） =================

    @Test
    void autoRefillCreatesRuleWithFirstOrderAndValidatesInterval() {
        DeliveryCreateBo auto = bo(REQ_A, 3);
        auto.setAutoRefillIntervalDays(7);
        IDeliveryOrderService.CreatedDelivery created = orderService.createDeliveryOrder(auto, USER_ID);

        assertEquals(1, count("ws_delivery_auto_rule"), "规则与首单同事务冻结");
        assertEquals(7, jdbc.queryForObject("SELECT INTERVAL_DAYS FROM ws_delivery_auto_rule", Integer.class));
        assertEquals(1, count("ws_order"));
        assertEquals(BALANCE_FEN - 3_000L, cardBalance());
        assertNotNull(created.order().getId());

        // 重复提交同 requestId：规则、订单、扣款都不重复
        orderService.createDeliveryOrder(auto, USER_ID);
        assertEquals(1, count("ws_delivery_auto_rule"));
        assertEquals(1, count("ws_order"));
        assertEquals(BALANCE_FEN - 3_000L, cardBalance());

        DeliveryCreateBo badInterval = bo(REQ_B, 3);
        badInterval.setAutoRefillIntervalDays(2);
        assertThrows(JbkException.class, () -> orderService.createDeliveryOrder(badInterval, USER_ID));
        badInterval.setAutoRefillIntervalDays(91);
        assertThrows(JbkException.class, () -> orderService.createDeliveryOrder(badInterval, USER_ID));
    }

    @Test
    void autoRefillGeneratesExactlyOncePerPeriod() {
        DeliveryCreateBo auto = bo(REQ_A, 3);
        auto.setAutoRefillIntervalDays(7);
        orderService.createDeliveryOrder(auto, USER_ID);
        long ruleId = jdbc.queryForObject("SELECT ID FROM ws_delivery_auto_rule", Long.class);
        String anchor = jdbc.queryForObject("SELECT ANCHOR_TIME FROM ws_delivery_auto_rule", String.class);

        // 第1期到期：恰生成一单，订单号确定性派生（幂等键含规则与期序）
        String dueTime = DeliveryClock.plusHours(anchor, 24 * 8);
        assertEquals(1, orderService.generateAutoRefillDueOrders(dueTime));
        String expectedNo = DeliveryOrderNo.deriveAutoRefill(USER_ID, ruleId, 1);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_order WHERE ORDER_NO=?", Integer.class, expectedNo));
        assertEquals(BALANCE_FEN - 6_000L, cardBalance(), "第1期正常扣款");
        assertEquals(2, count("ws_delivery_task"));

        // 同期重复生成：零新增零扣款（「只生成一次」）
        assertEquals(0, orderService.generateAutoRefillDueOrders(dueTime));
        assertEquals(2, count("ws_order"));
        assertEquals(BALANCE_FEN - 6_000L, cardBalance());

        // 未到期不生成
        assertEquals(0, orderService.generateAutoRefillDueOrders(DeliveryClock.plusHours(anchor, 24 * 6)));
    }

    @Test
    void autoRefillGenerationFailureLeavesZeroResidueAndKeepsRule() {
        DeliveryCreateBo auto = bo(REQ_A, 3);
        auto.setAutoRefillIntervalDays(7);
        orderService.createDeliveryOrder(auto, USER_ID);
        String anchor = jdbc.queryForObject("SELECT ANCHOR_TIME FROM ws_delivery_auto_rule", String.class);
        // 卡被清零：本期创单失败，规则保留、无半成品
        jdbc.update("UPDATE ws_card SET BALANCE_AMOUNT=0 WHERE ID=?", CARD_ID);

        assertEquals(0, orderService.generateAutoRefillDueOrders(DeliveryClock.plusHours(anchor, 24 * 8)));
        assertEquals(1, count("ws_order"), "只有首单");
        assertEquals(1, count("ws_delivery_task"));
        assertEquals(1, count("ws_wallet_flow"));
        assertEquals(0L, cardBalance());
        assertEquals(1, count("ws_delivery_auto_rule"), "规则不因单期失败被销毁");
        assertNull(jdbc.queryForObject(
                "SELECT MAX(ORDER_NO) FROM ws_order WHERE ORDER_NO LIKE 'WD%' AND ID > 1", String.class));
    }
}
