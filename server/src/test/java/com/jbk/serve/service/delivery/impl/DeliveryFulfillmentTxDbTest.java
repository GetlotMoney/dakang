package com.jbk.serve.service.delivery.impl;

import com.jbk.serve.service.aftersale.impl.ResendFulfillmentTxServiceImpl;
import com.jbk.serve.service.aftersale.IResendFulfillmentTxService;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
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
import com.jbk.serve.mapper.aftersale.WsAfterSaleActionMapper;
import com.jbk.serve.service.aftersale.batch.EntitlementFixture;
import com.jbk.serve.service.aftersale.batch.EntitlementLedger;
import com.jbk.serve.service.aftersale.IAfterSaleActionTxService;
import com.jbk.serve.service.aftersale.impl.AfterSaleActionTxServiceImpl;
import com.jbk.serve.service.delivery.DeliveryClock;
import com.jbk.serve.service.delivery.DeliveryMediaStore;
import com.jbk.serve.service.delivery.IDeliveryAppealTxService;
import com.jbk.serve.service.delivery.IDeliveryMediaService;
import com.jbk.serve.service.delivery.IDeliveryOrderService;
import com.jbk.serve.service.delivery.IDeliveryOrderTxService;
import com.jbk.serve.service.delivery.IDeliveryTaskTxService;
import com.jbk.serve.service.message.IWsMessageService;
import com.jbk.serve.service.message.impl.WsMessageServiceImpl;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.serve.service.ops.impl.WsDomainEventServiceImpl;
import com.jbk.tool.consts.delivery.DeliveryEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.delivery.bo.DeliveryAppealCreateBo;
import com.jbk.tool.data.delivery.bo.DeliveryAppealDecideBo;
import com.jbk.tool.data.delivery.bo.DeliveryAppealEvidenceBo;
import com.jbk.tool.data.delivery.bo.DeliveryCreateBo;
import com.jbk.tool.data.delivery.bo.DeliveryExceptionReportBo;
import com.jbk.tool.data.delivery.bo.DeliverySignBo;
import com.jbk.tool.data.delivery.po.WsDeliveryAppeal;
import com.jbk.tool.data.delivery.po.WsDeliveryException;
import com.jbk.tool.data.delivery.po.WsDeliveryTask;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 * E2E-03 A3/A4 履约状态机与申诉的真实 MySQL 集成测试。
 *
 * <p>钉住的事务事实：20 并发抢单恰一成功（规则9）、非法跳转/重复签收零副作用（规则10/11）、
 * 三照缺一拒签（规则12）、同账号自配送拒绝（铁律7）、预约单提前不可接（规则19）、
 * 24h 申诉窗口双侧（规则15）、裁决三态约束+不写退款（规则17/18）、举证权限（规则16）。</p>
 *
 * <p>无 Docker 环境自动跳过。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = DeliveryFulfillmentTxDbTest.Ctx.class)
class DeliveryFulfillmentTxDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_fulfill_it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static final long USER_ID = 9L;
    private static final long CARD_ID = 100L;
    private static final long STATION_ID = 41L;
    private static final long OTHER_STATION = 42L;
    private static final long WATER_TYPE_ID = 8L;
    private static final long BALANCE_FEN = 100_000L;
    /** 主配送员：用户 70 / 配送员行 ID 由自增产生。 */
    private static final long COURIER_USER = 70L;
    private static final long FOREIGN_COURIER_USER = 71L;

    @Configuration
    @EnableTransactionManagement
    static class Ctx {
        @Bean
        DataSource dataSource() {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl(MYSQL.getJdbcUrl());
            ds.setUsername(MYSQL.getUsername());
            ds.setPassword(MYSQL.getPassword());
            // 20 并发抢单需要每线程一个连接，留少量余量
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
            com.baomidou.mybatisplus.core.config.GlobalConfig globalConfig =
                    new com.baomidou.mybatisplus.core.config.GlobalConfig();
            globalConfig.setMetaObjectHandler(new com.jbk.tool.config.system.mybatis.MpMetaObjectHandler());
            factory.setGlobalConfig(globalConfig);
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
            // 真实审计实现：可靠审计与业务同事务、撞幂等键读回核验是被测性质本身（P1-3），mock 证不了
            return new WsDomainEventServiceImpl();
        }

        @Bean
        IWsMessageService messageService() {
            return new WsMessageServiceImpl();
        }

        @Bean
        DeliveryMediaStore deliveryMediaStore() {
            // 物理落盘不在本测试关注面（库行是权威）；no-op 防测试机脏文件
            return (mediaKey, content) -> {
            };
        }

        @Bean
        IDeliveryMediaService deliveryMediaService() {
            return new DeliveryMediaServiceImpl();
        }

        @Bean
        CourierAccess courierAccess() {
            return new CourierAccess();
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
        com.jbk.serve.service.settlement.ISplitService splitService() {
            // E2E-08 完成挂点协作方：本类锁既有资金事实，分账行为由 SettlementDbTest 用真库锁定
            return org.mockito.Mockito.mock(com.jbk.serve.service.settlement.ISplitService.class);
        }

        @Bean
        IDeliveryOrderService deliveryOrderService() {
            return new DeliveryOrderServiceImpl();
        }

        @Bean
        IDeliveryTaskTxService deliveryTaskTxService() {
            return new DeliveryTaskTxServiceImpl();
        }

        /**
         * E2E-04 包C：签收事务在成功后会调用它把补送售后动作推成完成。
         * 普通配送任务签收时它恒返回 false、不产生写入，但 Bean 必须存在——
         * 缺它整个上下文起不来，这也正是本类此前一次性红掉 14 条的原因。
         */
        @Bean
        IResendFulfillmentTxService resendFulfillmentTxService(WsAfterSaleActionMapper a,
                                                               WsDeliveryAppealMapper ap,
                                                               WsDeliveryTaskMapper t,
                                                               WsOrderMapper o) {
            return new ResendFulfillmentTxServiceImpl(a, ap, t, o);
        }

        @Bean
        MapperFactoryBean<WsAfterSaleActionMapper> wsAfterSaleActionMapper(SqlSessionTemplate t) {
            return mapper(WsAfterSaleActionMapper.class, t);
        }

        @Bean
        IAfterSaleActionTxService afterSaleActionTxService(WsAfterSaleActionMapper actionMapper,
                                                          WsOrderMapper orderMapper,
                                                          TradeCardMapper tradeCardMapper,
                                                          WsWalletFlowMapper walletFlowMapper,
                                                          IWsDomainEventService domainEventService,
                                                          EntitlementLedger entitlementLedger) {
            // 真实现而非 mock：裁决登记的待执行动作要真的撞到 uk_after_sale_source，
            // 幂等与「同事务回滚一并消失」这两条性质 mock 证不了
            return new AfterSaleActionTxServiceImpl(actionMapper, orderMapper, tradeCardMapper,
                    walletFlowMapper, domainEventService, entitlementLedger,
                    org.mockito.Mockito.mock(com.jbk.serve.service.settlement.ISplitClawbackTxService.class));
        }

        @Bean
        IDeliveryAppealTxService deliveryAppealTxService() {
            return new DeliveryAppealTxServiceImpl();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    @Autowired
    private IDeliveryOrderService orderService;
    @Autowired
    private IDeliveryTaskTxService taskService;
    @Autowired
    private IDeliveryAppealTxService appealService;
    @Autowired
    private IDeliveryMediaService mediaService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        DeliveryDbSchema.createAll(jdbc);
        DeliveryDbSchema.truncateAll(jdbc);
        jdbc.update("INSERT INTO ws_station(ID,STATION_NAME,STATION_CODE,STATION_STATUS) VALUES(?,?,?,1)",
                STATION_ID, "测试站", "ST-41");
        jdbc.update("INSERT INTO ws_station(ID,STATION_NAME,STATION_CODE,STATION_STATUS) VALUES(?,?,?,1)",
                OTHER_STATION, "邻站", "ST-42");
        jdbc.update("INSERT INTO ws_water_type(ID,WATER_NAME,WATER_SORT,DEFAULT_FLAG,WATER_STATUS) "
                + "VALUES(?,?,1,2,1)", WATER_TYPE_ID, "纯净水");
        jdbc.update("INSERT INTO ws_card(ID,DATA_STATUS,CARD_NO,CARD_TYPE,USER_ID,BALANCE_AMOUNT,BALANCE_ML,"
                + "CARD_STATUS) VALUES(?,0,'VC-TEST-100',1,?,?,0,1)", CARD_ID, USER_ID, BALANCE_FEN);
        // 包D-4：卡是裸 INSERT，补历史聚合批次；创单扣减会从它摊走额度，返还再回补进来
        EntitlementFixture.seedLegacyBatch(jdbc, CARD_ID, USER_ID, BALANCE_FEN, 0L);
        seedCourier(COURIER_USER, String.valueOf(STATION_ID), 2);
        seedCourier(FOREIGN_COURIER_USER, String.valueOf(STATION_ID), 2);
    }

    private void seedCourier(long userId, String stationIds, int status) {
        jdbc.update("INSERT INTO ws_courier(DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,"
                        + "USER_ID,COURIER_NAME,COURIER_PHONE,STATION_IDS,COURIER_STATUS) "
                        + "VALUES(0,1,'20260723120000',1,'20260723120000',?,?,?,?,?)",
                userId, "配送员" + userId, "13800000" + userId, stationIds, status);
    }

    private WsDeliveryTask createTask() {
        DeliveryCreateBo bo = new DeliveryCreateBo();
        bo.setRequestId(UUID.randomUUID().toString());
        bo.setCardId(String.valueOf(CARD_ID));
        bo.setStationId(String.valueOf(STATION_ID));
        bo.setWaterTypeId(String.valueOf(WATER_TYPE_ID));
        bo.setContainerSpec("20L桶");
        bo.setDeliveryCount(1);
        bo.setPlanReturnCount(1);
        bo.setReceiveAddress("光谷软件园 A1 栋 502");
        bo.setReceivePhone("13900001111");
        bo.setDeliveryMode(1);
        return orderService.createDeliveryOrder(bo, USER_ID).task();
    }

    private String registerPhoto(long ownerUserId, String seed) {
        return mediaService.register(ownerUserId, DeliveryEnum.MediaPurpose.SIGN_PHOTO,
                ("photo-" + seed).getBytes(StandardCharsets.UTF_8), "image/jpeg", DateUtils.time());
    }

    private DeliverySignBo signBo(WsDeliveryTask task, int expectedVersion, List<String> keys, Integer locationStatus) {
        DeliverySignBo bo = new DeliverySignBo();
        bo.setTaskNo(task.getTaskNo());
        bo.setExpectedVersion(expectedVersion);
        bo.setActualDeliveryCount(1);
        bo.setActualReturnCount(1);
        bo.setLocationStatus(locationStatus);
        List<DeliverySignBo.SignPhotoBo> photos = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            DeliverySignBo.SignPhotoBo photo = new DeliverySignBo.SignPhotoBo();
            photo.setType(i + 1);
            photo.setMediaKey(keys.get(i));
            photo.setLatitude(30.4586);
            photo.setLongitude(114.4276);
            photos.add(photo);
        }
        bo.setPhotos(photos);
        return bo;
    }

    /** 完整链：接单→离站→送达→签收，返回签收后任务。 */
    private WsDeliveryTask fulfillToSigned(WsDeliveryTask task) {
        String now = DateUtils.time();
        WsDeliveryTask accepted = taskService.acceptTask(task.getTaskNo(), task.getVersion(), COURIER_USER, now);
        WsDeliveryTask departed = taskService.advanceTask(task.getTaskNo(), 3, accepted.getVersion(), COURIER_USER, now);
        WsDeliveryTask arrived = taskService.advanceTask(task.getTaskNo(), 4, departed.getVersion(), COURIER_USER, now);
        List<String> keys = List.of(registerPhoto(COURIER_USER, task.getTaskNo() + "-1"),
                registerPhoto(COURIER_USER, task.getTaskNo() + "-2"),
                registerPhoto(COURIER_USER, task.getTaskNo() + "-3"));
        return taskService.signTask(signBo(arrived, arrived.getVersion(), keys, 1), COURIER_USER, DateUtils.time());
    }

    private WsDeliveryTask taskRow(String taskNo) {
        return jdbc.queryForObject("SELECT TASK_STATUS, VERSION, COURIER_ID, ACCEPT_TIME, SIGN_TIME, "
                        + "APPEAL_DEADLINE, ORDER_ID FROM ws_delivery_task WHERE TASK_NO=?",
                (rs, i) -> {
                    WsDeliveryTask t = new WsDeliveryTask();
                    t.setTaskStatus(rs.getInt("TASK_STATUS"));
                    t.setVersion(rs.getInt("VERSION"));
                    t.setCourierId((Long) rs.getObject("COURIER_ID"));
                    t.setAcceptTime(rs.getString("ACCEPT_TIME"));
                    t.setSignTime(rs.getString("SIGN_TIME"));
                    t.setAppealDeadline(rs.getString("APPEAL_DEADLINE"));
                    t.setOrderId(rs.getLong("ORDER_ID"));
                    return t;
                }, taskNo);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    // ================= 1：并发抢单恰一成功（规则9） =================

    @Test
    void twentyConcurrentAcceptsAllowExactlyOneWinner() throws Exception {
        WsDeliveryTask task = createTask();
        int threads = 20;
        for (int i = 0; i < threads; i++) {
            seedCourier(200L + i, String.valueOf(STATION_ID), 2);
        }
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            long courierUser = 200L + i;
            futures.add(pool.submit(() -> {
                barrier.await(15, TimeUnit.SECONDS);
                try {
                    taskService.acceptTask(task.getTaskNo(), task.getVersion(), courierUser, DateUtils.time());
                    return true;
                } catch (JbkException e) {
                    return false;
                }
            }));
        }
        int winners = 0;
        for (Future<Boolean> f : futures) {
            if (f.get(60, TimeUnit.SECONDS)) {
                winners++;
            }
        }
        pool.shutdown();

        assertEquals(1, winners, "并发抢单必须恰好一个赢家");
        WsDeliveryTask after = taskRow(task.getTaskNo());
        assertEquals(2, after.getTaskStatus());
        assertEquals(2, after.getVersion(), "恰一次转换：版本恰好+1");
        assertNotNull(after.getCourierId());
    }

    // ================= 2：自配送拒绝（铁律7） =================

    @Test
    void selfDeliveryIsExcludedFromPoolAndRejectedAtAccept() {
        WsDeliveryTask task = createTask();
        // 下单用户自己也是启用配送员：列表必须排除，接单必须拒绝
        seedCourier(USER_ID, String.valueOf(STATION_ID), 2);

        List<WsDeliveryTask> pool = taskService.listAvailableTasks(USER_ID, DateUtils.time());
        assertTrue(pool.stream().noneMatch(t -> t.getTaskNo().equals(task.getTaskNo())), "可接列表过滤自配送");

        JbkException ex = assertThrows(JbkException.class,
                () -> taskService.acceptTask(task.getTaskNo(), task.getVersion(), USER_ID, DateUtils.time()));
        assertTrue(ex.getMessage().contains("不能配送自己的订单"), "实际=" + ex.getMessage());
        WsDeliveryTask after = taskRow(task.getTaskNo());
        assertEquals(1, after.getTaskStatus());
        assertNull(after.getCourierId());

        // 其他配送员正常可见可接（对照组：排除不是「查询坏了」）
        assertTrue(taskService.listAvailableTasks(COURIER_USER, DateUtils.time()).stream()
                .anyMatch(t -> t.getTaskNo().equals(task.getTaskNo())));
    }

    // ================= 3：准入与范围（规则8） =================

    @Test
    void disabledCourierAndOutOfScopeStationAreRejected() {
        WsDeliveryTask task = createTask();
        // 停用配送员
        seedCourier(300L, String.valueOf(STATION_ID), 3);
        assertThrows(JbkException.class, () -> taskService.listAvailableTasks(300L, DateUtils.time()));
        assertThrows(JbkException.class,
                () -> taskService.acceptTask(task.getTaskNo(), task.getVersion(), 300L, DateUtils.time()));

        // 范围不含任务水站
        seedCourier(301L, String.valueOf(OTHER_STATION), 2);
        assertTrue(taskService.listAvailableTasks(301L, DateUtils.time()).isEmpty());
        JbkException scope = assertThrows(JbkException.class,
                () -> taskService.acceptTask(task.getTaskNo(), task.getVersion(), 301L, DateUtils.time()));
        assertTrue(scope.getMessage().contains("不在当前配送范围"), "实际=" + scope.getMessage());

        // 范围未配置：默认拒绝
        seedCourier(302L, null, 2);
        assertThrows(JbkException.class, () -> taskService.listAvailableTasks(302L, DateUtils.time()));
    }

    // ================= 4：预约单到点语义（规则19） =================

    @Test
    void scheduledTaskIsNotAcceptableBeforeDueTimeButAcceptableAfter() {
        String now = DateUtils.time();
        String due = DeliveryClock.plusHours(now, 2);
        DeliveryCreateBo bo = new DeliveryCreateBo();
        bo.setRequestId(UUID.randomUUID().toString());
        bo.setCardId(String.valueOf(CARD_ID));
        bo.setStationId(String.valueOf(STATION_ID));
        bo.setWaterTypeId(String.valueOf(WATER_TYPE_ID));
        bo.setContainerSpec("20L桶");
        bo.setDeliveryCount(1);
        bo.setPlanReturnCount(0);
        bo.setReceiveAddress("光谷软件园 A1 栋 502");
        bo.setReceivePhone("13900001111");
        bo.setDeliveryMode(2);
        bo.setScheduledTime(due);
        WsDeliveryTask task = orderService.createDeliveryOrder(bo, USER_ID).task();

        // 提前：不入池、不可接
        assertTrue(taskService.listAvailableTasks(COURIER_USER, now).stream()
                .noneMatch(t -> t.getTaskNo().equals(task.getTaskNo())));
        JbkException early = assertThrows(JbkException.class,
                () -> taskService.acceptTask(task.getTaskNo(), task.getVersion(), COURIER_USER, now));
        assertTrue(early.getMessage().contains("预约配送时间未到"), "实际=" + early.getMessage());
        assertEquals(1, taskRow(task.getTaskNo()).getTaskStatus());

        // 到点：入池且可接
        String afterDue = DeliveryClock.plusHours(due, 1);
        assertTrue(taskService.listAvailableTasks(COURIER_USER, afterDue).stream()
                .anyMatch(t -> t.getTaskNo().equals(task.getTaskNo())));
        assertEquals(2, taskService.acceptTask(task.getTaskNo(), task.getVersion(), COURIER_USER, afterDue)
                .getTaskStatus());
    }

    // ================= 5：非法跳转/版本错位零副作用（规则10/11） =================

    @Test
    void illegalTransitionsAndStaleVersionsAreRejectedWithZeroSideEffects() {
        WsDeliveryTask task = createTask();
        int messagesBefore = count("ws_message");

        // 待接单直接离站/签收：状态维度拒绝
        assertThrows(JbkException.class,
                () -> taskService.advanceTask(task.getTaskNo(), 3, task.getVersion(), COURIER_USER, DateUtils.time()));
        assertThrows(JbkException.class,
                () -> taskService.signTask(signBo(task, task.getVersion(),
                        List.of("K1", "K2", "K3"), null), COURIER_USER, DateUtils.time()));
        assertEquals(1, taskRow(task.getTaskNo()).getTaskStatus());
        assertEquals(1, taskRow(task.getTaskNo()).getVersion());
        assertEquals(messagesBefore, count("ws_message"), "拒绝路径零消息");

        // 版本错位：接单拒绝
        assertThrows(JbkException.class,
                () -> taskService.acceptTask(task.getTaskNo(), 99, COURIER_USER, DateUtils.time()));

        // 接单后跳过离站直接送达：必须拒
        WsDeliveryTask accepted = taskService.acceptTask(task.getTaskNo(), 1, COURIER_USER, DateUtils.time());
        assertThrows(JbkException.class,
                () -> taskService.advanceTask(task.getTaskNo(), 4, accepted.getVersion(), COURIER_USER, DateUtils.time()));
        assertEquals(2, taskRow(task.getTaskNo()).getTaskStatus());

        // 非归属配送员推进：拒
        assertThrows(JbkException.class,
                () -> taskService.advanceTask(task.getTaskNo(), 3, accepted.getVersion(),
                        FOREIGN_COURIER_USER, DateUtils.time()));
        assertEquals(2, taskRow(task.getTaskNo()).getTaskStatus());
    }

    // ================= 6：三照缺一/定位声明（规则12） =================

    @Test
    void signRejectsIncompletePhotosBadLocationClaimAndForeignMedia() {
        WsDeliveryTask task = createTask();
        String now = DateUtils.time();
        WsDeliveryTask accepted = taskService.acceptTask(task.getTaskNo(), 1, COURIER_USER, now);
        WsDeliveryTask departed = taskService.advanceTask(task.getTaskNo(), 3, accepted.getVersion(), COURIER_USER, now);
        WsDeliveryTask arrived = taskService.advanceTask(task.getTaskNo(), 4, departed.getVersion(), COURIER_USER, now);

        String k1 = registerPhoto(COURIER_USER, "p1");
        String k2 = registerPhoto(COURIER_USER, "p2");
        String k3 = registerPhoto(COURIER_USER, "p3");

        // 只有两照
        DeliverySignBo twoPhotos = signBo(arrived, arrived.getVersion(), List.of(k1, k2), null);
        JbkException incomplete = assertThrows(JbkException.class,
                () -> taskService.signTask(twoPhotos, COURIER_USER, DateUtils.time()));
        assertTrue(incomplete.getMessage().contains("三照缺一不可"), "实际=" + incomplete.getMessage());

        // 三照但类型重复（两张门牌）
        DeliverySignBo dupType = signBo(arrived, arrived.getVersion(), List.of(k1, k2, k3), null);
        dupType.getPhotos().get(1).setType(1);
        assertThrows(JbkException.class, () -> taskService.signTask(dupType, COURIER_USER, DateUtils.time()));

        // 声明定位已记录但缺坐标
        DeliverySignBo noCoords = signBo(arrived, arrived.getVersion(), List.of(k1, k2, k3), 1);
        noCoords.getPhotos().forEach(p -> {
            p.setLatitude(null);
            p.setLongitude(null);
        });
        JbkException location = assertThrows(JbkException.class,
                () -> taskService.signTask(noCoords, COURIER_USER, DateUtils.time()));
        assertTrue(location.getMessage().contains("坐标"), "实际=" + location.getMessage());

        // 他人登记的媒体：claim 必拒
        String foreignKey = registerPhoto(FOREIGN_COURIER_USER, "foreign");
        DeliverySignBo foreignMedia = signBo(arrived, arrived.getVersion(), List.of(k1, k2, foreignKey), null);
        assertThrows(JbkException.class, () -> taskService.signTask(foreignMedia, COURIER_USER, DateUtils.time()));

        // 同一照片顶两个位置：拒
        DeliverySignBo reused = signBo(arrived, arrived.getVersion(), List.of(k1, k1, k3), null);
        reused.getPhotos().get(1).setType(2);
        assertThrows(JbkException.class, () -> taskService.signTask(reused, COURIER_USER, DateUtils.time()));

        // 全部拒绝后任务原地不动，订单未被完成
        WsDeliveryTask after = taskRow(task.getTaskNo());
        assertEquals(4, after.getTaskStatus());
        assertEquals(arrived.getVersion(), after.getVersion());
        assertEquals(2, jdbc.queryForObject("SELECT ORDER_STATUS FROM ws_order WHERE ID=?",
                Integer.class, after.getOrderId()));
    }

    // ================= 7：签收闭环 + 重复签收零副作用（规则13/14） =================

    @Test
    void signClosesOrderWithServerTimeAndSecondSignHasZeroSideEffects() {
        WsDeliveryTask task = createTask();
        WsDeliveryTask signed = fulfillToSigned(task);

        assertEquals(5, signed.getTaskStatus());
        assertEquals(1, signed.getActualDeliveryCount());
        assertEquals(1, signed.getActualReturnCount());
        assertEquals(1, signed.getLocationStatus(), "三照带合法坐标=定位已记录");
        assertNotNull(signed.getSignTime());
        assertEquals(DeliveryClock.plusHours(signed.getSignTime(), 24), signed.getAppealDeadline(),
                "申诉截止=签收+24h（规则14）");

        // 订单完成态与时间同源
        assertEquals(4, jdbc.queryForObject("SELECT ORDER_STATUS FROM ws_order WHERE ID=?",
                Integer.class, signed.getOrderId()));
        assertEquals(signed.getSignTime(), jdbc.queryForObject("SELECT FINISH_TIME FROM ws_order WHERE ID=?",
                String.class, signed.getOrderId()));

        // 三照 JSON 权威时间=签收时间；媒体绑定到本任务
        String photosJson = jdbc.queryForObject("SELECT SIGN_PHOTOS FROM ws_delivery_task WHERE ID=?",
                String.class, signed.getId());
        assertNotNull(photosJson);
        assertEquals(3, cn.hutool.json.JSONUtil.parseArray(photosJson).size());
        cn.hutool.json.JSONUtil.parseArray(photosJson).forEach(item ->
                assertEquals(signed.getSignTime(), ((cn.hutool.json.JSONObject) item).getStr("time"),
                        "照片时间必须由签收动作统一写入"));
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_delivery_media WHERE BOUND_TASK_ID=?", Integer.class, signed.getId()));

        // 时间单调：创建<=接单<=离站<=送达<=签收
        List<String> chain = jdbc.queryForObject(
                "SELECT CREATE_TIME, ACCEPT_TIME, DEPART_TIME, ARRIVE_TIME, SIGN_TIME "
                        + "FROM ws_delivery_task WHERE ID=?",
                (rs, i) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)),
                signed.getId());
        List<String> sorted = new ArrayList<>(chain);
        java.util.Collections.sort(sorted);
        assertEquals(sorted, chain, "履约时间轴必须单调不减");

        // 重复签收：状态已 5，必须拒且零副作用（订单完成时间不被改写）
        String finishBefore = jdbc.queryForObject("SELECT FINISH_TIME FROM ws_order WHERE ID=?",
                String.class, signed.getOrderId());
        List<String> again = List.of(registerPhoto(COURIER_USER, "again-1"),
                registerPhoto(COURIER_USER, "again-2"), registerPhoto(COURIER_USER, "again-3"));
        assertThrows(JbkException.class, () -> taskService.signTask(
                signBo(signed, signed.getVersion(), again, null), COURIER_USER, DateUtils.time()));
        assertEquals(5, taskRow(task.getTaskNo()).getTaskStatus());
        assertEquals(signed.getVersion(), taskRow(task.getTaskNo()).getVersion());
        assertEquals(finishBefore, jdbc.queryForObject("SELECT FINISH_TIME FROM ws_order WHERE ID=?",
                String.class, signed.getOrderId()));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_delivery_media WHERE MEDIA_KEY IN (?,?,?) AND BOUND_TASK_ID IS NOT NULL",
                Integer.class, again.get(0), again.get(1), again.get(2)), "拒签不占用媒体");
    }

    // ================= 8：异常上报（履约中、归属限定、不动状态机） =================

    @Test
    void exceptionReportIsLimitedToFulfillmentPhasesAndAssignedCourier() {
        WsDeliveryTask task = createTask();
        WsDeliveryTask accepted = taskService.acceptTask(task.getTaskNo(), 1, COURIER_USER, DateUtils.time());

        DeliveryExceptionReportBo bo = new DeliveryExceptionReportBo();
        bo.setTaskNo(task.getTaskNo());
        bo.setExpectedVersion(accepted.getVersion());
        bo.setReason(1);
        bo.setDescription("接单后联系不上用户");
        WsDeliveryException record = taskService.reportException(bo, COURIER_USER, DateUtils.time());
        assertNotNull(record.getId());
        assertTrue(record.getCreateTime().compareTo(accepted.getAcceptTime()) >= 0,
                "异常时间不早于最后已发生节点");
        // 异常不推进状态、不加版本
        assertEquals(2, taskRow(task.getTaskNo()).getTaskStatus());
        assertEquals(accepted.getVersion(), taskRow(task.getTaskNo()).getVersion());

        // 非归属配送员上报：拒且零新增
        assertThrows(JbkException.class,
                () -> taskService.reportException(bo, FOREIGN_COURIER_USER, DateUtils.time()));
        assertEquals(1, count("ws_delivery_exception"));

        // 签收后不能再上报
        WsDeliveryTask signed = fulfillToSignedFromAccepted(task, accepted);
        DeliveryExceptionReportBo late = new DeliveryExceptionReportBo();
        late.setTaskNo(task.getTaskNo());
        late.setExpectedVersion(signed.getVersion());
        late.setReason(5);
        late.setDescription("签收后的异常");
        assertThrows(JbkException.class, () -> taskService.reportException(late, COURIER_USER, DateUtils.time()));
        assertEquals(1, count("ws_delivery_exception"));
    }

    private WsDeliveryTask fulfillToSignedFromAccepted(WsDeliveryTask task, WsDeliveryTask accepted) {
        String now = DateUtils.time();
        WsDeliveryTask departed = taskService.advanceTask(task.getTaskNo(), 3, accepted.getVersion(), COURIER_USER, now);
        WsDeliveryTask arrived = taskService.advanceTask(task.getTaskNo(), 4, departed.getVersion(), COURIER_USER, now);
        List<String> keys = List.of(registerPhoto(COURIER_USER, task.getTaskNo() + "-x1"),
                registerPhoto(COURIER_USER, task.getTaskNo() + "-x2"),
                registerPhoto(COURIER_USER, task.getTaskNo() + "-x3"));
        return taskService.signTask(signBo(arrived, arrived.getVersion(), keys, null), COURIER_USER, DateUtils.time());
    }

    // ================= 9：申诉窗口双侧（规则15） =================

    @Test
    void appealWindowRejectsBeforeSignAndAfterTwentyFourHours() {
        WsDeliveryTask task = createTask();
        WsDeliveryTask signed = fulfillToSigned(task);
        DeliveryAppealCreateBo bo = appealBo(task, signed);

        // 早于签收（时钟异常/伪造）：拒
        assertThrows(JbkException.class, () -> appealService.createAppeal(bo, USER_ID, "20200101000000"));
        // 超过 24h：拒
        String beyond = DeliveryClock.plusHours(signed.getSignTime(), 25);
        JbkException expired = assertThrows(JbkException.class,
                () -> appealService.createAppeal(bo, USER_ID, beyond));
        assertTrue(expired.getMessage().contains("24 小时内"), "实际=" + expired.getMessage());
        assertEquals(0, count("ws_delivery_appeal"), "窗口外拒绝零残留");
        assertEquals(5, taskRow(task.getTaskNo()).getTaskStatus());

        // 窗口内：成立，任务转 7，订单保持已完成
        String inWindow = DeliveryClock.plusHours(signed.getSignTime(), 1);
        WsDeliveryAppeal appeal = appealService.createAppeal(bo, USER_ID, inWindow);
        assertEquals(1, appeal.getAppealStatus());
        assertEquals(7, taskRow(task.getTaskNo()).getTaskStatus());
        assertEquals(4, jdbc.queryForObject("SELECT ORDER_STATUS FROM ws_order WHERE ID=?",
                Integer.class, signed.getOrderId()));

        // 非订单本人：拒（属主先行）
        DeliveryAppealCreateBo foreign = appealBo(task, signed);
        assertThrows(JbkException.class, () -> appealService.createAppeal(foreign, 999L, inWindow));
    }

    private DeliveryAppealCreateBo appealBo(WsDeliveryTask task, WsDeliveryTask signed) {
        String orderNo = jdbc.queryForObject("SELECT ORDER_NO FROM ws_order WHERE ID=?",
                String.class, signed.getOrderId());
        DeliveryAppealCreateBo bo = new DeliveryAppealCreateBo();
        bo.setOrderNo(orderNo);
        bo.setTaskNo(task.getTaskNo());
        bo.setReason("QUANTITY");
        bo.setDescription("实收数量与订单不一致");
        bo.setReceivedCount(0);
        return bo;
    }

    // ================= 10：申诉唯一 + 裁决约束（规则16/17/18） =================

    @Test
    void appealUniquenessVerdictOutcomesAndNoRefundWrites() {
        WsDeliveryTask task = createTask();
        WsDeliveryTask signed = fulfillToSigned(task);
        String inWindow = DeliveryClock.plusHours(signed.getSignTime(), 1);
        WsDeliveryAppeal appeal = appealService.createAppeal(appealBo(task, signed), USER_ID, inWindow);

        // 重复申诉：任务已 7 → 按状态拒绝
        assertThrows(JbkException.class,
                () -> appealService.createAppeal(appealBo(task, signed), USER_ID, inWindow));
        assertEquals(1, count("ws_delivery_appeal"));

        // 策略码白名单：字典外的码一律拒绝，绝不当作 REJECT 兜底
        DeliveryAppealDecideBo bad = decideBo(appeal.getId(), "WITHDRAW", null, "撤销不属于裁决策略");
        assertThrows(JbkException.class, () -> appealService.decideAppeal(bad, 1L, DateUtils.time()));
        DeliveryAppealDecideBo pendingBack = decideBo(appeal.getId(), "product_only", 1, "大小写不符也拒绝");
        assertThrows(JbkException.class, () -> appealService.decideAppeal(pendingBack, 1L, DateUtils.time()));
        DeliveryAppealDecideBo noReason = decideBo(appeal.getId(), "REJECT", null, "  ");
        assertThrows(JbkException.class, () -> appealService.decideAppeal(noReason, 1L, DateUtils.time()));
        // 数量越界：QUANTITY 的上界是「计划−实收」，越界拒绝且本事务零写入
        DeliveryAppealDecideBo tooMany = decideBo(appeal.getId(), "PRODUCT_ONLY", 999, "批准数量越界");
        assertThrows(JbkException.class, () -> appealService.decideAppeal(tooMany, 1L, DateUtils.time()));

        long flowsBefore = count("ws_wallet_flow");
        long balanceBefore = jdbc.queryForObject("SELECT BALANCE_AMOUNT FROM ws_card WHERE ID=?",
                Long.class, CARD_ID);

        // 资金补偿裁决：只落「成立待补偿」+ 一条待执行售后动作，绝不写退款成功/入账（规则18）
        assertTrue(appealService.decideAppeal(
                decideBo(appeal.getId(), "PRODUCT_ONLY", 1, "核实少送一桶，转资金补偿待处理"),
                1L, DateUtils.time()));
        assertEquals(2, jdbc.queryForObject("SELECT APPEAL_STATUS FROM ws_delivery_appeal WHERE ID=?",
                Integer.class, appeal.getId()));
        assertEquals(5, taskRow(task.getTaskNo()).getTaskStatus(), "裁决后任务归档回已签收");
        assertEquals(4, jdbc.queryForObject("SELECT ORDER_STATUS FROM ws_order WHERE ID=?",
                Integer.class, signed.getOrderId()), "订单保持已完成，不产生退款状态");
        assertEquals(flowsBefore, count("ws_wallet_flow"), "裁决不产生任何资金流水");
        assertEquals(balanceBefore, jdbc.queryForObject("SELECT BALANCE_AMOUNT FROM ws_card WHERE ID=?",
                Long.class, CARD_ID), "裁决不动卡余额");
        assertEquals(1, count("ws_after_sale_action"), "资金策略裁决登记恰一条待执行售后动作");
        assertEquals(1, jdbc.queryForObject(
                "SELECT ACTION_STATUS FROM ws_after_sale_action WHERE ORDER_ID=?",
                Integer.class, signed.getOrderId()), "售后动作只到待执行，裁决事务不执行返还");

        // 重复裁决：拒
        assertThrows(JbkException.class, () -> appealService.decideAppeal(
                decideBo(appeal.getId(), "REJECT", null, "再裁一次"), 1L, DateUtils.time()));

        // 裁决后活动位释放：窗口内可再次申诉（生成列唯一键只约束活动申诉）
        WsDeliveryAppeal second = appealService.createAppeal(appealBo(task, signed), USER_ID,
                DeliveryClock.plusHours(signed.getSignTime(), 2));
        assertEquals(1, second.getAppealStatus());
        assertEquals(2, count("ws_delivery_appeal"));
    }

    /**
     * 裁决入参只有策略码一个真相源（E2E-04 R0-2）：申诉终态由 AfterSaleStrategy.deriveOutcome 派生，
     * Bo 上不再有 outcome。数量对 REJECT 无意义，传 null。
     */
    private DeliveryAppealDecideBo decideBo(Long appealId, String strategyCode, Integer approvedCount,
                                            String result) {
        DeliveryAppealDecideBo bo = new DeliveryAppealDecideBo();
        bo.setAppealId(String.valueOf(appealId));
        bo.setStrategyCode(strategyCode);
        bo.setApprovedCount(approvedCount);
        bo.setHandleResult(result);
        return bo;
    }

    // ================= 10b：裁决完整共键栅栏（E2E-03 验收 P1-2） =================

    /**
     * 裁决前完整校验：订单存在、ORDER_TYPE=3、订单处于 4已完成、appeal/task/order 三方
     * userId 一致、任务/订单外键闭环——每道栅栏各一条拒绝用例且零写入，全对齐后放行。
     */
    @Test
    void decideAppealEnforcesFullLinkGuardWithZeroWritesOnRejection() {
        WsDeliveryTask task = createTask();
        WsDeliveryTask signed = fulfillToSigned(task);
        WsDeliveryAppeal appeal = appealService.createAppeal(appealBo(task, signed), USER_ID,
                DeliveryClock.plusHours(signed.getSignTime(), 1));
        long orderId = signed.getOrderId();
        DeliveryAppealDecideBo decide = decideBo(appeal.getId(), "PRODUCT_ONLY", 1, "核实成立，转补偿待处理");

        // ① 订单缺失（逻辑删）：拒
        jdbc.update("UPDATE ws_order SET DATA_STATUS=1 WHERE ID=?", orderId);
        assertThrows(JbkException.class, () -> appealService.decideAppeal(decide, 1L, DateUtils.time()));
        assertAppealStillPending(appeal.getId(), task.getTaskNo());
        jdbc.update("UPDATE ws_order SET DATA_STATUS=0 WHERE ID=?", orderId);

        // ② 订单类型错位（非配送单）：拒
        jdbc.update("UPDATE ws_order SET ORDER_TYPE=1 WHERE ID=?", orderId);
        assertThrows(JbkException.class, () -> appealService.decideAppeal(decide, 1L, DateUtils.time()));
        assertAppealStillPending(appeal.getId(), task.getTaskNo());
        jdbc.update("UPDATE ws_order SET ORDER_TYPE=3 WHERE ID=?", orderId);

        // ③ 订单不在允许裁决的 4已完成：拒
        jdbc.update("UPDATE ws_order SET ORDER_STATUS=2 WHERE ID=?", orderId);
        JbkException statusEx = assertThrows(JbkException.class,
                () -> appealService.decideAppeal(decide, 1L, DateUtils.time()));
        assertTrue(statusEx.getMessage().contains("已完成"), "实际=" + statusEx.getMessage());
        assertAppealStillPending(appeal.getId(), task.getTaskNo());
        jdbc.update("UPDATE ws_order SET ORDER_STATUS=4 WHERE ID=?", orderId);

        // ④ 三方 userId 错位（申诉归属被改）：拒
        jdbc.update("UPDATE ws_delivery_appeal SET USER_ID=999 WHERE ID=?", appeal.getId());
        assertThrows(JbkException.class, () -> appealService.decideAppeal(decide, 1L, DateUtils.time()));
        assertAppealStillPending(appeal.getId(), task.getTaskNo());
        jdbc.update("UPDATE ws_delivery_appeal SET USER_ID=? WHERE ID=?", USER_ID, appeal.getId());

        // ⑤ 任务/订单外键错位（申诉指向他单）：拒
        jdbc.update("UPDATE ws_delivery_appeal SET ORDER_ID=? WHERE ID=?", orderId + 1000, appeal.getId());
        assertThrows(JbkException.class, () -> appealService.decideAppeal(decide, 1L, DateUtils.time()));
        assertAppealStillPending(appeal.getId(), task.getTaskNo());
        jdbc.update("UPDATE ws_delivery_appeal SET ORDER_ID=? WHERE ID=?", orderId, appeal.getId());

        // 全对齐：放行，申诉落终态、任务归档回已签收
        assertTrue(appealService.decideAppeal(decide, 1L, DateUtils.time()));
        assertEquals(2, jdbc.queryForObject("SELECT APPEAL_STATUS FROM ws_delivery_appeal WHERE ID=?",
                Integer.class, appeal.getId()));
        assertEquals(5, taskRow(task.getTaskNo()).getTaskStatus());
    }

    // ================= 10c：配送员动作审计身份（E2E-03 验收 P1-3） =================

    /**
     * 配送员履约动作的审计必须显式落 COURIER 端口（portal=4）并与接单业务同事务落库，
     * 不得再被会话推断记成 USER；幂等键为 DNODE_<节点>:<任务号>。真库断言事件行本体：
     * 与业务同生共死由毒键用例（下一条）和 WsDomainEventTxDbTest 的回滚原子性钉住。
     */
    @Test
    void courierNodeAuditGoesReliableWithCourierPortal() {
        WsDeliveryTask task = createTask();
        WsDeliveryTask accepted = taskService.acceptTask(task.getTaskNo(), task.getVersion(),
                COURIER_USER, DateUtils.time());

        java.util.Map<String, Object> acceptEvent = jdbc.queryForMap(
                "SELECT EVENT_TYPE, EVENT_KEY, ACTOR_ID, ACTOR_PORTAL, ACTOR_ROLE FROM ws_domain_event "
                        + "WHERE BIZ_IDEMPOTENCY_KEY=?", "DNODE_ACCEPT:" + task.getTaskNo());
        assertEquals(OpsEnum.EventType.DELIVERY_NODE.getValue(),
                ((Number) acceptEvent.get("EVENT_TYPE")).intValue());
        assertEquals(task.getTaskNo(), acceptEvent.get("EVENT_KEY"));
        assertEquals(COURIER_USER, ((Number) acceptEvent.get("ACTOR_ID")).longValue());
        assertEquals(OpsEnum.ActorPortal.COURIER.getValue(),
                ((Number) acceptEvent.get("ACTOR_PORTAL")).intValue(), "portal 必须为配送端 4");
        assertEquals(OpsEnum.ActorPortal.COURIER.getDesc(), acceptEvent.get("ACTOR_ROLE"));

        taskService.advanceTask(task.getTaskNo(), 3, accepted.getVersion(), COURIER_USER, DateUtils.time());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ws_domain_event WHERE BIZ_IDEMPOTENCY_KEY=?",
                Integer.class, "DNODE_DEPART:" + task.getTaskNo()), "离站节点同样落 COURIER 可靠审计");
        assertEquals(OpsEnum.ActorPortal.COURIER.getValue(), jdbc.queryForObject(
                "SELECT ACTOR_PORTAL FROM ws_domain_event WHERE BIZ_IDEMPOTENCY_KEY=?",
                Integer.class, "DNODE_DEPART:" + task.getTaskNo()));
    }

    /**
     * E2E-03 复审 P1-3 毒键回归：审计幂等键被伪造事件（他人/他键/他语义）预占时，
     * 接单绝不能把撞键当幂等成功——必须 fail-closed 整体回滚：任务原地不动、
     * 伪造行不被覆盖、也不追加任何新事件（盲信撞键=攻击者可用假审计顶掉真实履约记录）。
     */
    @Test
    void poisonedAuditIdempotencyKeyFailsClosedAndRollsBackAccept() {
        WsDeliveryTask task = createTask();
        String poisonedKey = "DNODE_ACCEPT:" + task.getTaskNo();
        jdbc.update("INSERT INTO ws_domain_event(DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,"
                        + "EVENT_TYPE,EVENT_KEY,EVENT_PAYLOAD,ACTOR_ID,ACTOR_PORTAL,ACTOR_ROLE,"
                        + "WHITELIST_FLAG,CONSUMED_FLAG,BIZ_IDEMPOTENCY_KEY) "
                        + "VALUES(0,1,'20260723120000',1,'20260723120000',?,?,?,?,?,?,1,1,?)",
                OpsEnum.EventType.DELIVERY_NODE.getValue(), "BOGUS-EVENT",
                "{\"old\":\"污染\",\"new\":\"伪造\",\"time\":\"20260723120000\"}",
                USER_ID, OpsEnum.ActorPortal.USER.getValue(), OpsEnum.ActorPortal.USER.getDesc(), poisonedKey);
        int messagesBefore = count("ws_message");

        JbkException ex = assertThrows(JbkException.class,
                () -> taskService.acceptTask(task.getTaskNo(), task.getVersion(), COURIER_USER, DateUtils.time()));
        assertTrue(ex.getMessage().contains("语义不一致"), "实际=" + ex.getMessage());

        // 任务保持待接单原样（状态 1、版本 1、无人认领）：接单动作被整体回滚
        WsDeliveryTask after = taskRow(task.getTaskNo());
        assertEquals(1, after.getTaskStatus());
        assertEquals(1, after.getVersion());
        assertNull(after.getCourierId());
        assertEquals(messagesBefore, count("ws_message"), "接单站内消息一并回滚");

        // 该幂等键下仍只有那条伪造行，且未被覆盖或改写
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ws_domain_event WHERE BIZ_IDEMPOTENCY_KEY=?",
                Integer.class, poisonedKey));
        java.util.Map<String, Object> row = jdbc.queryForMap(
                "SELECT EVENT_KEY, EVENT_PAYLOAD, ACTOR_PORTAL FROM ws_domain_event WHERE BIZ_IDEMPOTENCY_KEY=?",
                poisonedKey);
        assertEquals("BOGUS-EVENT", row.get("EVENT_KEY"), "伪造行不得被覆盖");
        assertTrue(String.valueOf(row.get("EVENT_PAYLOAD")).contains("伪造"));
        assertEquals(OpsEnum.ActorPortal.USER.getValue(), ((Number) row.get("ACTOR_PORTAL")).intValue());
    }

    /** 栅栏拒绝必须零写入：申诉停留待处理且无裁决痕迹，任务停留申诉中。 */
    private void assertAppealStillPending(Long appealId, String taskNo) {
        assertEquals(1, jdbc.queryForObject("SELECT APPEAL_STATUS FROM ws_delivery_appeal WHERE ID=?",
                Integer.class, appealId), "申诉必须仍为待处理");
        assertNull(jdbc.queryForObject("SELECT HANDLE_BY FROM ws_delivery_appeal WHERE ID=?",
                Long.class, appealId), "拒绝不得写裁决人");
        assertNull(jdbc.queryForObject("SELECT HANDLE_TIME FROM ws_delivery_appeal WHERE ID=?",
                String.class, appealId), "拒绝不得写裁决时间");
        assertEquals(7, taskRow(taskNo).getTaskStatus(), "任务必须仍在申诉中");
    }

    // ================= 11：举证权限（规则16） =================

    @Test
    void courierEvidenceIsLimitedToAssignedCourierAndActiveAppeal() {
        WsDeliveryTask task = createTask();
        WsDeliveryTask signed = fulfillToSigned(task);
        String inWindow = DeliveryClock.plusHours(signed.getSignTime(), 1);
        WsDeliveryAppeal appeal = appealService.createAppeal(appealBo(task, signed), USER_ID, inWindow);

        DeliveryAppealEvidenceBo bo = new DeliveryAppealEvidenceBo();
        bo.setTaskNo(task.getTaskNo());
        bo.setAppealId(String.valueOf(appeal.getId()));
        bo.setDescription("送达时已按门牌拍照，实际配送为用户当场确认。");

        // 非归属配送员：拒
        assertThrows(JbkException.class,
                () -> appealService.appendCourierEvidence(bo, FOREIGN_COURIER_USER, DateUtils.time()));
        // 归属配送员：两次追加，时间不减
        WsDeliveryAppeal first = appealService.appendCourierEvidence(bo, COURIER_USER, DateUtils.time());
        DeliveryAppealEvidenceBo second = new DeliveryAppealEvidenceBo();
        second.setTaskNo(task.getTaskNo());
        second.setAppealId(String.valueOf(appeal.getId()));
        second.setDescription("补充第二份说明。");
        WsDeliveryAppeal updated = appealService.appendCourierEvidence(second, COURIER_USER, DateUtils.time());
        cn.hutool.json.JSONArray evidences = cn.hutool.json.JSONUtil.parseArray(updated.getCourierEvidences());
        assertEquals(2, evidences.size());
        String t1 = ((cn.hutool.json.JSONObject) evidences.get(0)).getStr("time");
        String t2 = ((cn.hutool.json.JSONObject) evidences.get(1)).getStr("time");
        assertTrue(signed.getSignTime().compareTo(t1) <= 0 && t1.compareTo(t2) <= 0, "举证时间单调");
        assertNotNull(first.getCourierEvidences());

        // 证据访问收口：归属配送员可读申诉与异常；他人配送员拒
        assertNotNull(appealService.getTaskAppealForCourier(task.getTaskNo(), COURIER_USER));
        assertThrows(JbkException.class,
                () -> appealService.getTaskAppealForCourier(task.getTaskNo(), FOREIGN_COURIER_USER));
        assertThrows(JbkException.class,
                () -> appealService.listTaskExceptionsForCourier(task.getTaskNo(), FOREIGN_COURIER_USER));

        // 裁决后任务回 5：举证关闭
        appealService.decideAppeal(decideBo(appeal.getId(), "REJECT", null, "证据充分，驳回"), 1L, DateUtils.time());
        assertThrows(JbkException.class,
                () -> appealService.appendCourierEvidence(second, COURIER_USER, DateUtils.time()));
    }
}
