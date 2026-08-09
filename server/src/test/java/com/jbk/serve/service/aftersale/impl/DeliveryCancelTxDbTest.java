package com.jbk.serve.service.aftersale.impl;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.aftersale.WsAfterSaleActionMapper;
import com.jbk.serve.mapper.delivery.WsDeliveryAppealMapper;
import com.jbk.serve.mapper.delivery.WsDeliveryAutoRuleMapper;
import com.jbk.serve.mapper.delivery.WsDeliveryExceptionMapper;
import com.jbk.serve.mapper.delivery.WsDeliveryMediaMapper;
import com.jbk.serve.mapper.delivery.WsDeliveryTaskMapper;
import com.jbk.serve.mapper.message.WsMessageMapper;
import com.jbk.serve.mapper.ops.WsDomainEventMapper;
import com.jbk.serve.mapper.product.WsWaterTypeMapper;
import com.jbk.serve.mapper.station.WsStationMapper;
import com.jbk.serve.mapper.aftersale.WsCardEntitlementBatchMapper;
import com.jbk.serve.mapper.aftersale.WsEntitlementAllocationMapper;
import com.jbk.serve.mapper.trade.TradeCardMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.mapper.user.WsCourierMapper;
import com.jbk.serve.service.aftersale.batch.EntitlementFixture;
import com.jbk.serve.service.aftersale.batch.EntitlementLedger;
import com.jbk.serve.service.aftersale.AfterSaleNo;
import com.jbk.serve.service.aftersale.IAfterSaleActionTxService;
import com.jbk.serve.service.aftersale.IDeliveryCancelService;
import com.jbk.serve.service.delivery.IAdminDeliveryService;
import com.jbk.serve.service.delivery.IDeliveryAppealTxService;
import com.jbk.serve.service.delivery.IDeliveryOrderService;
import com.jbk.serve.service.delivery.IDeliveryOrderTxService;
import com.jbk.serve.service.delivery.impl.AdminDeliveryServiceImpl;
import com.jbk.serve.service.delivery.impl.DeliveryDbSchema;
import com.jbk.serve.service.delivery.impl.DeliveryOrderServiceImpl;
import com.jbk.serve.service.delivery.impl.DeliveryOrderTxServiceImpl;
import com.jbk.serve.service.message.IWsMessageService;
import com.jbk.serve.service.message.impl.WsMessageServiceImpl;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.serve.service.ops.impl.WsDomainEventServiceImpl;
import com.jbk.tool.consts.aftersale.AfterSaleEnum;
import com.jbk.tool.data.aftersale.bo.DeliveryCancelBo;
import com.jbk.tool.data.aftersale.po.WsAfterSaleAction;
import com.jbk.tool.data.delivery.bo.DeliveryCreateBo;
import com.jbk.tool.data.delivery.vo.AdminDeliveryOrderTraceVo;
import com.jbk.tool.data.trade.vo.AdminOrderItemVo;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
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
 * E2E-04 包A 待接单取消的<b>真实 MySQL + 真实 Spring 事务</b>集成测试
 *（搭法对齐 {@code DeliveryOrderTxDbTest} / {@code DeliveryFulfillmentTxDbTest}）。
 *
 * <p>被测的三段事务边界全部用真实现装配（{@code DeliveryOrderTxServiceImpl} +
 * {@code AfterSaleActionTxServiceImpl} + 真实审计/消息服务）：Mock 掉其中任意一段，
 * 「任一失败全回滚」「认领与失败落痕独立提交」「同来源撞唯一键幂等」这三条性质就都测不到了。</p>
 *
 * <p>钉住的事实：</p>
 * <ol>
 *   <li><b>业务段三步同生共死</b>：任务 1→6、订单 2→7、售后动作 PENDING 三者同事务；
 *       订单 CAS 影响 0 行时任务状态与动作行必须一并回滚。</li>
 *   <li><b>拒绝路径零副作用</b>：已接单 / 订单非已支付 / 非本人 / COURIER_ID 被外力挂上，
 *       四条拒绝都不得留下任何状态、资金、消息或台账痕迹。</li>
 *   <li><b>幂等靠键</b>：同来源第二次登记撞 {@code uk_after_sale_source} 返回既有行，
 *       重复取消不产生第二条动作、不退第二次钱。</li>
 *   <li><b>资金段失败可区分</b>：业务段已提交而返还失败时，动作落 5需人工对账 且钱一分未动，
 *       用户话术必须是「处理中」而不是「已到账」。</li>
 *   <li><b>取消后 PC 配送追溯仍为 ok</b>（第三批矩阵扩展的运行时佐证）。</li>
 * </ol>
 *
 * <p>无 Docker 环境自动跳过。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = DeliveryCancelTxDbTest.Ctx.class)
class DeliveryCancelTxDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_cancel_it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static final long USER_ID = 9L;
    private static final long OTHER_USER = 7L;
    private static final long CARD_ID = 100L;
    private static final long STATION_ID = 41L;
    private static final long WATER_TYPE_ID = 8L;
    private static final long COURIER_ID = 3L;
    private static final long BALANCE_FEN = 10_000L;
    /** 20L桶：水费 1300×2 + 配送费 200×2 = 3000 分（与 DeliveryOrderTxDbTest 同一算例）。 */
    private static final long ORDER_FEN = 3_000L;
    private static final long WATER_FEN = 2_600L;
    private static final long FEE_FEN = 400L;
    private static final String REQ_A = "1f4a2b3c-4d5e-4f60-8a9b-0c1d2e3f4a5b";

    @Configuration
    @EnableTransactionManagement
    static class Ctx {
        @Bean
        DataSource dataSource() {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl(MYSQL.getJdbcUrl());
            ds.setUsername(MYSQL.getUsername());
            ds.setPassword(MYSQL.getPassword());
            // 回滚用例要在「外层事务持有连接」的同时用另一条连接提交并发写，留足余量
            ds.setMaximumPoolSize(8);
            return ds;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource ds) {
            return new DataSourceTransactionManager(ds);
        }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager tm) {
            return new TransactionTemplate(tm);
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
            // 生产同一份 XML：售后 CAS（claimForExecute/markSuccess/markTerminal）与原子扣减都在里面，
            // 状态机前态集合由 AfterSaleTransitions 以参数注入，测试里绝不复刻 SQL
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
        MapperFactoryBean<WsDeliveryAppealMapper> wsDeliveryAppealMapper(SqlSessionTemplate t) {
            return mapper(WsDeliveryAppealMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsDeliveryExceptionMapper> wsDeliveryExceptionMapper(SqlSessionTemplate t) {
            return mapper(WsDeliveryExceptionMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsDeliveryMediaMapper> wsDeliveryMediaMapper(SqlSessionTemplate t) {
            return mapper(WsDeliveryMediaMapper.class, t);
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
        MapperFactoryBean<WsCourierMapper> wsCourierMapper(SqlSessionTemplate t) {
            return mapper(WsCourierMapper.class, t);
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
        MapperFactoryBean<WsAfterSaleActionMapper> wsAfterSaleActionMapper(SqlSessionTemplate t) {
            return mapper(WsAfterSaleActionMapper.class, t);
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
            // 真实现而非 mock：「取消审计与业务同事务、回滚一并消失」是本类要断言的事实之一
            return new WsDomainEventServiceImpl();
        }

        @Bean
        IWsMessageService messageService() {
            return new WsMessageServiceImpl();
        }

        @Bean
        IAfterSaleActionTxService afterSaleActionTxService(WsAfterSaleActionMapper actionMapper,
                                                          WsOrderMapper orderMapper,
                                                          TradeCardMapper tradeCardMapper,
                                                          WsWalletFlowMapper walletFlowMapper,
                                                          IWsDomainEventService domainEventService,
                                                          EntitlementLedger entitlementLedger) {
            // 三种传播（REQUIRED 登记 / REQUIRES_NEW 认领与落痕 / REQUIRES_NEW+RC 资金）
            // 必须由真实 Spring 事务织入，mock 掉就等于把被测边界本身拿掉
            return new AfterSaleActionTxServiceImpl(actionMapper, orderMapper, tradeCardMapper,
                    walletFlowMapper, domainEventService, entitlementLedger,
                    org.mockito.Mockito.mock(com.jbk.serve.service.settlement.ISplitClawbackTxService.class));
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
        IDeliveryCancelService deliveryCancelService(IDeliveryOrderTxService deliveryOrderTxService,
                                                     IAfterSaleActionTxService afterSaleActionTxService,
                                                     WsOrderMapper orderMapper,
                                                     WsDeliveryTaskMapper taskMapper) {
            return new DeliveryCancelServiceImpl(deliveryOrderTxService, afterSaleActionTxService,
                    orderMapper, taskMapper);
        }

        /** 裁决事务不在本类被测路径上（追溯聚合只读），用 Mock 隔离。 */
        @Bean
        IDeliveryAppealTxService appealTxService() {
            return Mockito.mock(IDeliveryAppealTxService.class);
        }

        @Bean
        IAdminDeliveryService adminDeliveryService(WsDeliveryTaskMapper taskMapper,
                                                   WsDeliveryAppealMapper appealMapper,
                                                   WsDeliveryExceptionMapper exceptionMapper,
                                                   WsDeliveryMediaMapper mediaMapper,
                                                   WsOrderMapper orderMapper,
                                                   WsWalletFlowMapper walletFlowMapper,
                                                   WsCourierMapper courierMapper,
                                                   IWsMessageService messageService,
                                                   IWsDomainEventService domainEventService,
                                                   IDeliveryAppealTxService appealTxService) {
            return new AdminDeliveryServiceImpl(taskMapper, appealMapper, exceptionMapper, mediaMapper,
                    orderMapper, walletFlowMapper, courierMapper, messageService, domainEventService,
                    appealTxService);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    @Autowired
    private IDeliveryOrderService orderService;
    @Autowired
    private IDeliveryOrderTxService deliveryOrderTxService;
    @Autowired
    private IAfterSaleActionTxService afterSaleActionTxService;
    @Autowired
    private IDeliveryCancelService cancelService;
    @Autowired
    private IAdminDeliveryService adminDeliveryService;
    @Autowired
    private TransactionTemplate txTemplate;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;

    private long orderId;
    private long taskId;
    private String orderNo;

    @BeforeEach
    void reset() {
        DeliveryDbSchema.createAll(jdbc);
        DeliveryDbSchema.truncateAll(jdbc);
        jdbc.update("INSERT INTO ws_station(ID,STATION_NAME,STATION_CODE,STATION_STATUS) VALUES(?,?,?,1)",
                STATION_ID, "测试站", "ST-41");
        jdbc.update("INSERT INTO ws_water_type(ID,WATER_NAME,WATER_SORT,DEFAULT_FLAG,WATER_STATUS) "
                + "VALUES(?,?,1,2,1)", WATER_TYPE_ID, "纯净水");
        jdbc.update("INSERT INTO ws_user(ID,DATA_STATUS,USER_NAME,USER_PHONE,USER_STATUS) VALUES(?,0,?,?,1)",
                USER_ID, "张三", "13900001111");
        jdbc.update("INSERT INTO ws_courier(ID,DATA_STATUS,USER_ID,COURIER_NAME,COURIER_PHONE,COURIER_STATUS) "
                + "VALUES(?,0,?,?,?,2)", COURIER_ID, 70L, "李配送", "13700002222");
        jdbc.update("INSERT INTO ws_card(ID,DATA_STATUS,CARD_NO,CARD_TYPE,USER_ID,BALANCE_AMOUNT,BALANCE_ML,"
                        + "SCOPE_JSON,EXPIRE_TIME,CARD_STATUS) VALUES(?,0,?,1,?,?,0,NULL,NULL,1)",
                CARD_ID, "VC-TEST-100", USER_ID, BALANCE_FEN);
        // 包D-4：卡是裸 INSERT，补历史聚合批次；下面的真实创单会从它摊走额度
        EntitlementFixture.seedLegacyBatch(jdbc, CARD_ID, USER_ID, BALANCE_FEN, 0L);

        // 走真实创单路径落种子：PACKAGE_SNAP 冻结快照与 DELIVERY:<orderNo> 扣款流水
        // 是返还额度的两个锚点，手写它们等于把被测的账实相等断言喂成同义反复
        IDeliveryOrderService.CreatedDelivery created = orderService.createDeliveryOrder(bo(REQ_A), USER_ID);
        orderId = created.order().getId();
        taskId = created.task().getId();
        orderNo = created.order().getOrderNo();
        assertEquals(BALANCE_FEN - ORDER_FEN, cardBalance(), "种子前提：创单已扣款");
        assertEquals(2, orderStatus(), "种子前提：订单 2已支付");
        assertEquals(1, taskStatus(), "种子前提：任务 1待接单");
    }

    private DeliveryCreateBo bo(String requestId) {
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
        bo.setDeliveryMode(1);
        return bo;
    }

    private DeliveryCancelBo cancelBo(String no) {
        DeliveryCancelBo bo = new DeliveryCancelBo();
        bo.setOrderNo(no);
        return bo;
    }

    // ==================== 读取辅助 ====================

    private long cardBalance() {
        return jdbc.queryForObject("SELECT BALANCE_AMOUNT FROM ws_card WHERE ID=?", Long.class, CARD_ID);
    }

    private int orderStatus() {
        return jdbc.queryForObject("SELECT ORDER_STATUS FROM ws_order WHERE ID=?", Integer.class, orderId);
    }

    private int taskStatus() {
        return jdbc.queryForObject("SELECT TASK_STATUS FROM ws_delivery_task WHERE ID=?", Integer.class, taskId);
    }

    private int taskVersion() {
        return jdbc.queryForObject("SELECT VERSION FROM ws_delivery_task WHERE ID=?", Integer.class, taskId);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private <T> T actionColumn(String column, Class<T> type) {
        return jdbc.queryForObject("SELECT " + column + " FROM ws_after_sale_action", type);
    }

    /** 取消尚未发生时的完整水位：创单留下的那一条消息 / 审计 / 流水之外，什么都不该多。 */
    private void assertNoCancelResidue() {
        assertEquals(2, orderStatus(), "订单必须停在 2已支付");
        assertEquals(1, taskStatus(), "任务必须停在 1待接单");
        assertEquals(1, taskVersion(), "任务版本不得被推进");
        assertEquals(0, count("ws_after_sale_action"), "拒绝不得登记售后动作");
        assertEquals(BALANCE_FEN - ORDER_FEN, cardBalance(), "拒绝不得动账");
        assertEquals(1, count("ws_wallet_flow"), "只应存在创单那条扣款流水");
        assertEquals(1, count("ws_message"), "只应存在创单那条站内消息");
        assertEquals(1, count("ws_domain_event"), "只应存在创单那条审计");
    }

    // ==================== 1：业务段三步同生共死 ====================

    /**
     * 业务段（第一段事务）单独驱动：任务 1→6、订单 2→7、售后动作 PENDING 一次落齐。
     * 动作行的不可伪造列（售后号、状态、版本、重试次数）由内核钉死，四元额度等于满额返还。
     */
    @Test
    void businessTxMovesTaskOrderAndRegistersPendingActionInOneTransaction() {
        String now = DateUtils.time();
        WsAfterSaleAction action = deliveryOrderTxService.cancelPendingDeliveryOrder(orderId, USER_ID, now);

        assertEquals(6, taskStatus(), "任务 1→6已取消");
        assertEquals(2, taskVersion(), "任务乐观锁版本 +1");
        assertNull(jdbc.queryForObject("SELECT COURIER_ID FROM ws_delivery_task WHERE ID=?",
                Long.class, taskId), "取消不得挂配送员");
        assertEquals(7, orderStatus(), "订单 2→7已退款");
        assertEquals("用户在配送员接单前自助取消配送订单",
                jdbc.queryForObject("SELECT CANCEL_REASON FROM ws_order WHERE ID=?", String.class, orderId));

        assertEquals(1, count("ws_after_sale_action"));
        assertEquals(AfterSaleNo.derive(AfterSaleEnum.SourceType.DELIVERY_CANCEL.getValue(), orderId),
                action.getAfterSaleNo(), "售后号由 (sourceType,sourceId) 确定性派生");
        assertEquals(1, actionColumn("ACTION_STATUS", Integer.class), "登记即 1待执行");
        assertEquals(1, actionColumn("VERSION", Integer.class));
        assertEquals(0, actionColumn("RETRY_COUNT", Integer.class));
        assertEquals(AfterSaleEnum.SourceType.DELIVERY_CANCEL.getValue(),
                actionColumn("SOURCE_TYPE", Integer.class));
        assertEquals(orderId, actionColumn("SOURCE_ID", Long.class), "取消的 SOURCE_ID 是订单ID");
        assertEquals(AfterSaleEnum.ActionType.CARD_REFUND.getValue(),
                actionColumn("ACTION_TYPE", Integer.class));
        // 取消 = 原样退回：三个维度恰等于额度上限，合计列由 Refund.totalFen() 派生
        assertEquals(WATER_FEN, actionColumn("REFUND_PRODUCT_FEN", Long.class));
        assertEquals(FEE_FEN, actionColumn("REFUND_SERVICE_FEN", Long.class));
        assertEquals(0L, actionColumn("REFUND_PRODUCT_ML", Long.class), "余额支付不返还水量");
        assertEquals(ORDER_FEN, actionColumn("REFUND_AMOUNT", Long.class));
        assertNull(actionColumn("STRATEGY_CODE", String.class), "取消没有补偿策略");
        assertNull(actionColumn("APPROVE_BY", Long.class), "用户自助取消没有运营批准人");

        // 业务段只推状态、只登记待执行：一分钱都还没动
        assertEquals(BALANCE_FEN - ORDER_FEN, cardBalance(), "业务段不得写卡");
        assertEquals(1, count("ws_wallet_flow"), "业务段不得产生返还流水");
        assertEquals(2, count("ws_message"), "创单 + 取消各一条站内消息，与业务同事务");
        assertEquals(2, count("ws_domain_event"), "创单 + 取消各一条正向审计，与业务同事务");
    }

    /**
     * <b>本类最关键的一条：订单 CAS 影响 0 行时，已经成功的任务 1→6 必须一并回滚。</b>
     *
     * <p>构造方式是真实的 TOCTOU 而不是打桩：先在外层事务里做一次普通读固定 RR 读视图，
     * 再用另一条连接把订单推出 2已支付 并提交。事务内 {@code selectById} 仍读到旧快照（status=2），
     * 因此可履约栅栏放行、任务 CAS 命中 1 行；而订单 CAS 是当前读，看见的是最新已提交值 → 0 行 → 抛出。
     * 只要有人把订单那步的影响行校验删掉或改成「≥0 就算过」，这条断言立刻变红。</p>
     */
    @Test
    void orderCasMissRollsBackTaskMoveAndPendingAction() {
        JbkException ex = assertThrows(JbkException.class, () -> txTemplate.execute(status -> {
            // ① 固定本事务的一致性读视图（RR 下由第一条普通 SELECT 建立）
            jdbc.queryForObject("SELECT ORDER_STATUS FROM ws_order WHERE ID=?", Integer.class, orderId);
            // ② 另一条连接推进订单并提交：事务内快照读看不到，CAS 的当前读一定看得到
            commitOnOtherConnection("UPDATE ws_order SET ORDER_STATUS=4 WHERE ID=?", orderId);
            // ③ 业务段并入本事务（REQUIRED）：任务 CAS 会成功，订单 CAS 必然落空
            return deliveryOrderTxService.cancelPendingDeliveryOrder(orderId, USER_ID, DateUtils.time());
        }));
        assertTrue(ex.getMessage().contains("订单状态已变化，取消失败"), "实际=" + ex.getMessage());

        assertEquals(1, taskStatus(), "订单 CAS 落空后，已成功的任务 1→6 必须随事务回滚");
        assertEquals(1, taskVersion(), "任务版本也必须回滚");
        assertEquals(0, count("ws_after_sale_action"), "动作行不得残留（否则会退一笔订单没取消的钱）");
        assertEquals(4, orderStatus(), "并发方的提交保留，本次取消未生效");
        assertEquals(BALANCE_FEN - ORDER_FEN, cardBalance());
        assertEquals(1, count("ws_wallet_flow"));
        assertEquals(1, count("ws_message"), "取消消息随事务回滚");
        assertEquals(1, count("ws_domain_event"), "取消审计随事务回滚");
    }

    /**
     * 在独立线程上执行并立即提交，制造真实的并发已提交写入。
     * <p>事务上下文是 ThreadLocal，另起线程拿到的是一条未参与外层事务的新连接（autoCommit），
     * 因此这条 UPDATE 会在外层事务仍在进行时就对外可见——这正是 CAS 要对抗的时序。</p>
     */
    private void commitOnOtherConnection(String sql, Object... args) {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> done = pool.submit(() -> new JdbcTemplate(dataSource).update(sql, args));
            assertEquals(1, done.get(20, TimeUnit.SECONDS), "并发写入必须真的落库");
        } catch (Exception failed) {
            throw new IllegalStateException("并发写入失败", failed);
        } finally {
            pool.shutdown();
        }
    }

    // ==================== 2：拒绝路径零副作用 ====================

    @Test
    void acceptedTaskCannotBeSelfCancelled() {
        jdbc.update("UPDATE ws_delivery_task SET TASK_STATUS=2, COURIER_ID=?, ACCEPT_TIME=? WHERE ID=?",
                COURIER_ID, DateUtils.time(), taskId);

        JbkException ex = assertThrows(JbkException.class,
                () -> cancelService.cancelPendingOrder(cancelBo(orderNo), USER_ID));
        assertTrue(ex.getMessage().contains("配送员已接单或任务已推进"), "实际=" + ex.getMessage());

        assertEquals(2, taskStatus(), "已接单任务状态不得被改写");
        assertEquals(2, orderStatus());
        assertEquals(0, count("ws_after_sale_action"));
        assertEquals(BALANCE_FEN - ORDER_FEN, cardBalance());
        assertEquals(1, count("ws_wallet_flow"));
    }

    @Test
    void nonPaidOrderCannotBeCancelled() {
        // 订单已完成（4）：任务侧看起来仍是 1待接单，可履约栅栏必须按订单状态精确拒绝
        jdbc.update("UPDATE ws_order SET ORDER_STATUS=4 WHERE ID=?", orderId);

        JbkException ex = assertThrows(JbkException.class,
                () -> cancelService.cancelPendingOrder(cancelBo(orderNo), USER_ID));
        assertTrue(ex.getMessage().contains("关联订单不在已支付待履约状态"), "实际=" + ex.getMessage());
        assertEquals(4, orderStatus());
        assertEquals(1, taskStatus());
        assertEquals(0, count("ws_after_sale_action"));
        assertEquals(BALANCE_FEN - ORDER_FEN, cardBalance());
    }

    /** 归属先行（铁律6）：他人订单与不存在同一口径拒绝，且编排层与事务层各判一次。 */
    @Test
    void foreignUserCannotCancelOthersOrder() {
        JbkException viaOrchestration = assertThrows(JbkException.class,
                () -> cancelService.cancelPendingOrder(cancelBo(orderNo), OTHER_USER));
        assertTrue(viaOrchestration.getMessage().contains("订单不存在或无权访问"),
                "实际=" + viaOrchestration.getMessage());
        assertNoCancelResidue();

        // 绕过编排层直接打事务段：事务内必须自己再判一次归属，不能依赖上层已经判过
        JbkException viaTx = assertThrows(JbkException.class,
                () -> deliveryOrderTxService.cancelPendingDeliveryOrder(orderId, OTHER_USER, DateUtils.time()));
        assertTrue(viaTx.getMessage().contains("订单不存在或无权访问"), "实际=" + viaTx.getMessage());
        assertNoCancelResidue();
    }

    /**
     * COURIER_ID 非空但状态仍为 1（被外力改写）→ 拒绝。
     *
     * <p>这是任务 CAS 刻意叠加的第二条件：接单事务同时写状态与配送员，只认状态时，
     * 一个被改回 1 却仍挂着配送员的任务会被当成可取消——配送员已在路上，钱却退了。
     * 编排层的只读断言（状态维度）在这里会放行，唯一拦住它的就是 CAS 的
     * {@code COURIER_ID IS NULL}；删掉那一条这个用例立刻变红。</p>
     */
    @Test
    void pendingTaskCarryingCourierIsRejectedByCasSecondCondition() {
        jdbc.update("UPDATE ws_delivery_task SET COURIER_ID=? WHERE ID=?", COURIER_ID, taskId);

        JbkException ex = assertThrows(JbkException.class,
                () -> cancelService.cancelPendingOrder(cancelBo(orderNo), USER_ID));
        assertTrue(ex.getMessage().contains("配送任务已被接单或状态已变化，取消失败"),
                "实际=" + ex.getMessage());

        assertEquals(1, taskStatus(), "任务状态不得被改写");
        assertEquals(2, orderStatus(), "订单必须随事务回滚");
        assertEquals(0, count("ws_after_sale_action"));
        assertEquals(BALANCE_FEN - ORDER_FEN, cardBalance());
        assertEquals(1, count("ws_message"));
        assertEquals(1, count("ws_domain_event"));
    }

    // ==================== 3：完整三段链路与幂等 ====================

    /** 端到端取消：三段事务走完，钱精确回卡、唯一返还流水成型、动作落 3已完成。 */
    @Test
    void fullCancelRefundsExactAmountWithSingleFlowAndSuccessAction() {
        String message = cancelService.cancelPendingOrder(cancelBo(orderNo), USER_ID);
        assertTrue(message.contains("退款已返还至您的水卡"), "资金已落卡才允许这句话；实际=" + message);

        assertEquals(6, taskStatus());
        assertEquals(7, orderStatus());
        assertEquals(BALANCE_FEN, cardBalance(), "整单满额原路返还，余额精确回到创单前");
        assertEquals(0L, jdbc.queryForObject("SELECT BALANCE_ML FROM ws_card WHERE ID=?", Long.class, CARD_ID),
                "余额支付不得返还水量");

        String afterSaleNo = AfterSaleNo.derive(
                AfterSaleEnum.SourceType.DELIVERY_CANCEL.getValue(), orderId);
        assertEquals(3, actionColumn("ACTION_STATUS", Integer.class), "动作落 3已完成");
        assertNull(actionColumn("LAST_ERROR", String.class));
        assertEquals(2, count("ws_wallet_flow"), "扣款 + 返还，恰两条");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_wallet_flow WHERE BIZ_IDEMPOTENCY_KEY=?", Integer.class,
                "AFTERSALE:" + afterSaleNo), "返还流水按售后号唯一");
        assertEquals(ORDER_FEN, jdbc.queryForObject(
                "SELECT AMOUNT_CHANGE FROM ws_wallet_flow WHERE BIZ_IDEMPOTENCY_KEY=?", Long.class,
                "AFTERSALE:" + afterSaleNo));
        assertEquals(BALANCE_FEN, jdbc.queryForObject(
                "SELECT AMOUNT_AFTER FROM ws_wallet_flow WHERE BIZ_IDEMPOTENCY_KEY=?", Long.class,
                "AFTERSALE:" + afterSaleNo));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT ML_CHANGE FROM ws_wallet_flow WHERE BIZ_IDEMPOTENCY_KEY=?", Long.class,
                "AFTERSALE:" + afterSaleNo));
        assertEquals(3, jdbc.queryForObject(
                "SELECT FLOW_TYPE FROM ws_wallet_flow WHERE BIZ_IDEMPOTENCY_KEY=?", Integer.class,
                "AFTERSALE:" + afterSaleNo), "取消来源记 3退款返还（申诉才是 4补偿入账）");

        // 包D-4 正向断言：返还必须同事务把额度回补进创单时扣走它的那个批次，
        // 分摊行随之归零并置冲正标记。删掉 executeInTx 里的 restoreOnRefundBack 那一段，本段立刻红——
        // 那时卡回到了 BALANCE_FEN 而批次剩余仍停在扣减后的水位，这张卡从此有一部分余额永远花不掉。
        assertEquals(BALANCE_FEN, EntitlementFixture.sumRemainFen(jdbc, CARD_ID),
                "不变式：逐卡批次剩余合计恒等于卡聚合值");
        assertEquals(0L, jdbc.queryForObject(
                "SELECT ALLOC_AMOUNT_FEN FROM ws_entitlement_allocation WHERE BIZ_KEY=?", Long.class,
                "DELIVERY:" + orderNo), "分摊额度被冲减到零");
        assertEquals(1, jdbc.queryForObject(
                "SELECT REVERSED_FLAG FROM ws_entitlement_allocation WHERE BIZ_KEY=?", Integer.class,
                "DELIVERY:" + orderNo), "两维归零才置冲正标记");
    }

    /** 重复取消：第二次被订单状态闸拦下，不产生第二条动作、不退第二次钱。 */
    @Test
    void repeatedCancelNeitherRefundsTwiceNorRegistersSecondAction() {
        cancelService.cancelPendingOrder(cancelBo(orderNo), USER_ID);
        assertEquals(BALANCE_FEN, cardBalance());

        JbkException ex = assertThrows(JbkException.class,
                () -> cancelService.cancelPendingOrder(cancelBo(orderNo), USER_ID));
        assertTrue(ex.getMessage().contains("关联订单不在已支付待履约状态"), "实际=" + ex.getMessage());

        assertEquals(BALANCE_FEN, cardBalance(), "重复取消绝不能退第二次");
        assertEquals(1, count("ws_after_sale_action"), "动作行恰一条");
        assertEquals(2, count("ws_wallet_flow"), "返还流水恰一条");
        assertEquals(7, orderStatus());
        assertEquals(6, taskStatus());
    }

    /**
     * 同来源第二次登记撞 {@code uk_after_sale_source} → 返回既有行（幂等靠键，不靠应用层查重）。
     *
     * <p>直接驱动内核而不是再走一次编排：编排层在订单状态那一层就被拦住了，
     * 永远走不到唯一键；而这把键正是「同一来源只允许一笔售后」的物理防线，
     * 它挡的是重放、并发与将来任何绕过状态闸的新调用方。</p>
     */
    @Test
    void createPendingIsIdempotentOnSourceKeyAndRejectsOwnershipDrift() {
        String now = DateUtils.time();
        WsAfterSaleAction first = afterSaleActionTxService.createPending(draft(CARD_ID), now);
        WsAfterSaleAction second = afterSaleActionTxService.createPending(draft(CARD_ID), now);

        assertEquals(first.getId(), second.getId(), "同来源必须收敛到同一行");
        assertEquals(first.getAfterSaleNo(), second.getAfterSaleNo());
        assertEquals(1, count("ws_after_sale_action"), "不产生第二条动作");
        assertEquals(ORDER_FEN, actionColumn("REFUND_AMOUNT", Long.class), "既有行额度不被覆盖");

        // 归属漂移比金额错更危险：撞键后读回的行若指向另一张卡，必须拒绝而不是"幂等返回"
        JbkException drift = assertThrows(JbkException.class,
                () -> afterSaleActionTxService.createPending(draft(CARD_ID + 1), now));
        assertTrue(drift.getMessage().contains("归属与本次请求不一致"), "实际=" + drift.getMessage());
        assertEquals(1, count("ws_after_sale_action"));
    }

    /** 与取消事务同口径的待执行草稿（满额返还）。 */
    private WsAfterSaleAction draft(long cardId) {
        WsAfterSaleAction draft = new WsAfterSaleAction()
                .setSourceType(AfterSaleEnum.SourceType.DELIVERY_CANCEL.getValue())
                .setSourceId(orderId)
                .setOrderId(orderId)
                .setUserId(USER_ID)
                .setCardId(cardId)
                .setActionType(AfterSaleEnum.ActionType.CARD_REFUND.getValue())
                .setRefundProductFen(WATER_FEN)
                .setRefundServiceFen(FEE_FEN)
                .setRefundProductMl(0L);
        draft.setCreateBy(USER_ID);
        draft.setUpdateBy(USER_ID);
        return draft;
    }

    /**
     * 资金段失败：业务段已提交（订单 7 / 任务 6），返还必须整体回滚且落 5需人工对账。
     *
     * <p>这条同时验证三段边界：认领若与资金同事务，回滚会把状态复原成 1待执行，
     * 失败落痕的 CAS（前态 2执行中）就恒 0 行，动作将永远无终态、无错因。
     * 用户话术也必须与「已到账」可区分——把在途退款说成已到账，用户就不会去核对。</p>
     */
    @Test
    void refundFailureLeavesMoneyUntouchedAndMarksReconciliationRequired() {
        // 卡被逻辑删除：业务段不碰卡照常提交，资金段锁到删除行后显式拒绝
        jdbc.update("UPDATE ws_card SET DATA_STATUS=1 WHERE ID=?", CARD_ID);

        String message = cancelService.cancelPendingOrder(cancelBo(orderNo), USER_ID);
        assertTrue(message.contains("退款处理中"), "资金未落卡时不得说已到账；实际=" + message);
        assertTrue(message.contains("联系客服"), "实际=" + message);

        assertEquals(7, orderStatus(), "业务段已提交");
        assertEquals(6, taskStatus());
        assertEquals(BALANCE_FEN - ORDER_FEN, cardBalance(), "返还失败必须一分钱都没动");
        assertEquals(1, count("ws_wallet_flow"), "不得留下半截返还流水");
        assertEquals(5, actionColumn("ACTION_STATUS", Integer.class), "落 5需人工对账（包A 无重试 Worker）");
        assertNull(actionColumn("NEXT_RETRY_TIME", String.class), "非可重试落点不得带重试排期");
        assertEquals(0, actionColumn("RETRY_COUNT", Integer.class));
        assertTrue(actionColumn("LAST_ERROR", String.class).contains("水卡已被逻辑删除"),
                "失败原因必须落库，否则失败就成了无痕事件");
        // 认领 + 落痕两次 CAS 各自 VERSION+1：证明它们真的独立提交了，没有被资金回滚带走
        assertEquals(3, actionColumn("VERSION", Integer.class));
    }

    // ==================== 4：取消后 PC 配送追溯仍为 ok ====================

    /**
     * 第三批矩阵扩展的运行时佐证：取消是合法终态，PC 追溯必须照常给出履约与资金证据。
     * 状态矩阵一旦把「任务 6 / 订单 7」重新判成外力改库，这里就会退化成 mismatch。
     */
    @Test
    void orderTraceStaysOkAfterCancel() {
        cancelService.cancelPendingOrder(cancelBo(orderNo), USER_ID);

        AdminDeliveryOrderTraceVo trace = adminDeliveryService.buildOrderTrace(adminOrderItem());
        assertEquals("ok", trace.getDelivery().getLinkStatus(),
                "取消后追溯不得断链，原因=" + trace.getDelivery().getLinkReason());
        assertNull(trace.getDelivery().getLinkReason());
        assertEquals(6, trace.getDelivery().getTaskStatus());
        assertNull(trace.getDelivery().getCourierName(), "取消态不得挂配送员");
        assertEquals(ORDER_FEN, trace.getDelivery().getTotalAmountFen());
        assertEquals("ok", trace.getDelivery().getPayment().getFlowStatus(),
                "资金链核验的锚点仍是 DELIVERY:<orderNo> 扣款流水，返还流水不参与该口径");
        assertEquals(-ORDER_FEN, trace.getDelivery().getPayment().getAmountChangeFen());
        assertTrue(trace.getAppeals().isEmpty());
        // 创单 + 取消两条正向审计都挂在订单号上，运营能在追溯里看到取消发生过
        assertTrue(trace.getAuditEvents().size() >= 2, "取消审计必须出现在追溯时间线里");

        // 对照：把任务改成「已取消却挂着履约痕迹」，矩阵必须立刻 fail-closed
        jdbc.update("UPDATE ws_delivery_task SET COURIER_ID=?, ACCEPT_TIME=? WHERE ID=?",
                COURIER_ID, DateUtils.time(), taskId);
        AdminDeliveryOrderTraceVo broken = adminDeliveryService.buildOrderTrace(adminOrderItem());
        assertEquals("mismatch", broken.getDelivery().getLinkStatus());
        assertNotNull(broken.getDelivery().getLinkReason());
        assertNull(broken.getDelivery().getPayment(), "断链时不下发资金正向证据");
    }

    /** 追溯入参：PC 列表投影，主体核验由服务层用 PO 重读订单完成。 */
    private AdminOrderItemVo adminOrderItem() {
        AdminOrderItemVo item = new AdminOrderItemVo();
        item.setId(orderId);
        item.setOrderNo(orderNo);
        item.setOrderType(3);
        item.setUserId(USER_ID);
        item.setUserName("张三");
        item.setUserPhone("13900001111");
        item.setStationId(STATION_ID);
        item.setStationName("测试站");
        return item;
    }
}
