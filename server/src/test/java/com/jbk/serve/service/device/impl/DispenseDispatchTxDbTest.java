package com.jbk.serve.service.device.impl;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.device.WsCommandMapper;
import com.jbk.serve.mapper.device.WsDeviceMapper;
import com.jbk.serve.mapper.device.WsDeviceOutletMapper;
import com.jbk.serve.mapper.device.WsFaultDictMapper;
import com.jbk.serve.mapper.station.WsStationMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.service.device.DeviceAvailabilityGuard;
import com.jbk.serve.service.device.IDispenseDispatchTxService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.serve.service.trade.ITradeOrderTxService;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.exception.JbkException;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;

/**
 * 出水指令准备事务的<b>真实 MySQL + 真实 Spring 事务</b>测试（B20，R2-P1-2）。
 *
 * <p>上一轮把「多读一次」当成了闭合竞态的手段，复审指出：设备、出水口、故障字典、订单、
 * CMD_ID 抢占分处五条自动提交语句时，无论最后一次读放多靠后，读完到抢占之间仍留着一条缝。
 * 本类用独立连接在事务执行期间改库，逼出那条缝——只要还存在，就一定能造出有效指令。</p>
 *
 * <p>无 Docker 环境自动跳过。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = DispenseDispatchTxDbTest.Ctx.class)
class DispenseDispatchTxDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_dispatch_it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static final long ORDER_ID = 701L;
    private static final long STATION_ID = 41L;
    private static final long OTHER_STATION_ID = 99L;
    private static final long DEVICE_ID = 21L;
    private static final long OUTLET_ID = 31L;
    private static final long WATER_TYPE_ID = 8L;
    private static final String SNAP = "{\"requestId\":\"scan-d\",\"unitPriceFenPerLiter\":20,"
            + "\"planMl\":5000,\"payWay\":2,\"waterTypeId\":8}";

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
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath:mapper/trade/*.xml"));
            return new SqlSessionTemplate(factory.getObject());
        }

        private static <M> MapperFactoryBean<M> mapper(Class<M> type, SqlSessionTemplate t) {
            MapperFactoryBean<M> bean = new MapperFactoryBean<>(type);
            bean.setSqlSessionTemplate(t);
            return bean;
        }


        @Bean
        MapperFactoryBean<WsCommandMapper> wsCommandMapper(SqlSessionTemplate t) {
            return mapper(WsCommandMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsOrderMapper> rawWsOrderMapper(SqlSessionTemplate t) {
            return mapper(WsOrderMapper.class, t);
        }

        /** spy：用例在「无锁定位读」返回后立刻让独立连接改库，逼出读到抢占之间的那条缝。 */
        @Bean
        @Primary
        WsOrderMapper wsOrderMapper(@Qualifier("rawWsOrderMapper") WsOrderMapper raw) {
            return Mockito.spy(raw);
        }

        @Bean
        MapperFactoryBean<WsDeviceMapper> wsDeviceMapper(SqlSessionTemplate t) {
            return mapper(WsDeviceMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsDeviceOutletMapper> wsDeviceOutletMapper(SqlSessionTemplate t) {
            return mapper(WsDeviceOutletMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsFaultDictMapper> wsFaultDictMapper(SqlSessionTemplate t) {
            return mapper(WsFaultDictMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsStationMapper> wsStationMapper(SqlSessionTemplate t) {
            return mapper(WsStationMapper.class, t);
        }

        @Bean
        DeviceAvailabilityGuard deviceAvailabilityGuard(WsDeviceMapper d, WsDeviceOutletMapper o,
                                                        WsFaultDictMapper f) {
            return new DeviceAvailabilityGuard(d, o, f);
        }

        @Bean
        IWsDomainEventService domainEventService() {
            return Mockito.mock(IWsDomainEventService.class);
        }

        /** 真实抢占 SQL（与生产同一实现），个别用例让它抛异常以验证 command 一并回滚。 */
        @Bean
        @Primary
        ITradeOrderTxService tradeOrderTxService(@Qualifier("realClaim") ITradeOrderTxService real) {
            return Mockito.spy(real);
        }

        @Bean("realClaim")
        ITradeOrderTxService realClaim(WsOrderMapper orderMapper) {
            return new ClaimOnlyTradeTx(orderMapper);
        }

        @Bean
        IDispenseDispatchTxService dispatchTxService(WsCommandMapper c, WsOrderMapper o, WsStationMapper s,
                                                     DeviceAvailabilityGuard g, ITradeOrderTxService t,
                                                     IWsDomainEventService e) {
            return new DispenseDispatchTxServiceImpl(c, o, s, g, t, e);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    /**
     * 只提供 claimCommandSlot 的轻量实现：本类要验证的是准备事务，
     * 把整个 TradeOrderTxServiceImpl 及其全套 Mapper 拉进上下文会让测试意图被淹没。
     * 抢占 SQL 与生产逐字相同（同一 CAS 谓词），因此仍是真行为而非仿写结论。
     */
    static class ClaimOnlyTradeTx implements ITradeOrderTxService {
        private final WsOrderMapper orderMapper;

        ClaimOnlyTradeTx(WsOrderMapper orderMapper) {
            this.orderMapper = orderMapper;
        }

        @Override
        public com.jbk.tool.data.trade.po.WsOrder createWaterOrder(com.jbk.tool.data.trade.po.WsOrder order,
                com.jbk.tool.data.mini.vo.ScanSessionInfo session, String now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int claimCommandSlot(Long orderId, Long commandId, Long stationId, Long deviceId, Long outletId) {
            return orderMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers
                    .lambdaUpdate(com.jbk.tool.data.trade.po.WsOrder.class)
                    .set(com.jbk.tool.data.trade.po.WsOrder::getCmdId, commandId)
                    .set(com.jbk.tool.data.trade.po.WsOrder::getUpdateTime, "20260803120000")
                    .eq(com.jbk.tool.data.trade.po.WsOrder::getId, orderId)
                    .eq(com.jbk.tool.data.trade.po.WsOrder::getOrderType, TradeEnum.OrderType.WATER.getValue())
                    .eq(com.jbk.tool.data.trade.po.WsOrder::getOrderStatus, TradeEnum.OrderStatus.PAID.getValue())
                    .isNull(com.jbk.tool.data.trade.po.WsOrder::getCmdId)
                    .eq(com.jbk.tool.data.trade.po.WsOrder::getStationId, stationId)
                    .eq(com.jbk.tool.data.trade.po.WsOrder::getDeviceId, deviceId)
                    .eq(com.jbk.tool.data.trade.po.WsOrder::getOutletId, outletId));
        }

        @Override
        public boolean settleWaterOrder(Long orderId, boolean success, Long actualMl) {
            throw new UnsupportedOperationException();
        }
    }

    @Autowired
    private IDispenseDispatchTxService dispatchTxService;
    @Autowired
    private ITradeOrderTxService tradeTxSpy;
    @Autowired
    private WsOrderMapper orderMapperSpy;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        Mockito.reset(tradeTxSpy, orderMapperSpy);
        jdbc.execute("""

                CREATE TABLE IF NOT EXISTS ws_command (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  CMD_NO VARCHAR(64), DEVICE_ID BIGINT, ORDER_ID BIGINT, BATCH_ID BIGINT NULL,
                  CMD_TYPE TINYINT, CMD_PAYLOAD TEXT, CMD_STATUS TINYINT, RETRY_COUNT INT,
                  SENT_TIME VARCHAR(20) NULL, ACK_TIME VARCHAR(20) NULL, FINISH_TIME VARCHAR(20) NULL,
                  RESULT_PAYLOAD TEXT NULL, FAIL_REASON VARCHAR(500) NULL,
                  UNIQUE KEY uk_cmd_no (CMD_NO)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_order (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  ORDER_NO VARCHAR(64), ORDER_TYPE TINYINT, USER_ID BIGINT,
                  STATION_ID BIGINT, DEVICE_ID BIGINT, OUTLET_ID BIGINT, CARD_ID BIGINT,
                  PACKAGE_ID BIGINT NULL, PACKAGE_SNAP MEDIUMTEXT NULL,
                  PLAN_ML BIGINT NULL, ACTUAL_ML BIGINT NULL, ORDER_AMOUNT BIGINT,
                  PAY_WAY TINYINT, ORDER_STATUS TINYINT, CMD_ID BIGINT NULL,
                  FINISH_TIME VARCHAR(20) NULL, CANCEL_REASON VARCHAR(500) NULL,
                  REFERRER_USER_ID BIGINT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_station (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  STATION_NAME VARCHAR(100), STATION_CODE VARCHAR(50), STATION_STATUS TINYINT
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_device (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  DEVICE_NO VARCHAR(50), DEVICE_NAME VARCHAR(100), STATION_ID BIGINT,
                  SIM_STATUS TINYINT NULL, SIM_EXPIRE_TIME VARCHAR(14) NULL,
                  ONLINE_STATUS TINYINT, RUN_STATUS TINYINT, LAST_FAULT_CODE VARCHAR(20)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_device_outlet (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  DEVICE_ID BIGINT, OUTLET_NO INT, WATER_TYPE_ID BIGINT, WATER_TYPE VARCHAR(50),
                  OUTLET_PRICE VARCHAR(20), OUTLET_STATUS TINYINT
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_fault_dict (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  FAULT_CODE VARCHAR(20), FAULT_NAME VARCHAR(50), FAULT_LEVEL TINYINT,
                  BLOCK_ORDER_FLAG TINYINT, FAULT_ADVICE VARCHAR(500) NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        for (String table : new String[]{"ws_command", "ws_order", "ws_station", "ws_device",
                "ws_device_outlet", "ws_fault_dict"}) {
            jdbc.execute("TRUNCATE TABLE " + table);
        }
        jdbc.update("INSERT INTO ws_station(ID,STATION_NAME,STATION_CODE,STATION_STATUS) VALUES(?,?,?,1)",
                STATION_ID, "测试站", "ST-41");
        jdbc.update("INSERT INTO ws_station(ID,STATION_NAME,STATION_CODE,STATION_STATUS) VALUES(?,?,?,1)",
                OTHER_STATION_ID, "迁入站", "ST-99");
        jdbc.update("INSERT INTO ws_device(ID,DEVICE_NO,DEVICE_NAME,STATION_ID,ONLINE_STATUS,RUN_STATUS) "
                + "VALUES(?,?,?,?,1,1)", DEVICE_ID, "DK-DEV-0021", "测试机", STATION_ID);
        jdbc.update("INSERT INTO ws_device_outlet(ID,DEVICE_ID,OUTLET_NO,WATER_TYPE_ID,WATER_TYPE,"
                + "OUTLET_PRICE,OUTLET_STATUS) VALUES(?,?,1,?,'纯净水','20',1)",
                OUTLET_ID, DEVICE_ID, WATER_TYPE_ID);
        seedOrder(TradeEnum.OrderStatus.PAID.getValue(), null);
    }

    private void seedOrder(int status, Long cmdId) {
        jdbc.update("DELETE FROM ws_order WHERE ID=?", ORDER_ID);
        jdbc.update("INSERT INTO ws_order(ID,DATA_STATUS,ORDER_NO,ORDER_TYPE,USER_ID,STATION_ID,DEVICE_ID,"
                        + "OUTLET_ID,CARD_ID,PACKAGE_SNAP,PLAN_ML,ORDER_AMOUNT,PAY_WAY,ORDER_STATUS,CMD_ID) "
                        + "VALUES(?,0,?,1,9,?,?,?,100,?,5000,100,2,?,?)",
                ORDER_ID, "WODISPATCH", STATION_ID, DEVICE_ID, OUTLET_ID, SNAP, status, cmdId);
    }

    private int commandCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ws_command", Integer.class);
    }

    private Long boundCmdId() {
        return jdbc.queryForObject("SELECT CMD_ID FROM ws_order WHERE ID=?", Long.class, ORDER_ID);
    }

    /** 在准备事务执行期间由独立连接改库并提交——模拟并发的另一个事务。 */
    private void commitFromAnotherConnection(String sql, Object... args) {
        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 把并发改库塞进准备事务内部：紧接「无锁定位读」之后触发。
     *
     * <p>这是旧实现真正的缝——那时定位读之后的每一步都是独立自动提交语句，外部改动能一路溜到
     * 抢占前。现在定位读之后会立刻按锁序取锁并重读，外部改动必须在此刻之前提交才可能发生；
     * 注入点选在这里，就是把最有利于攻击者的时序摆出来。（若改在取锁之后注入，外部 UPDATE
     * 会直接被行锁挡住等到超时——那也算闭合，但证明力不如本用例直接。）</p>
     */
    private void mutateAfterRoutingRead(String sql, Object... args) {
        Mockito.doAnswer(invocation -> {
            Object routing = invocation.callRealMethod();
            commitFromAnotherConnection(sql, args);
            return routing;
        }).when(orderMapperSpy).selectById(ORDER_ID);
    }

    // ==================== 正常路径 ====================

    @Test
    void preparesCommandAndClaimsSlotInOneTransaction() {
        IDispenseDispatchTxService.Prepared prepared = dispatchTxService.prepare(ORDER_ID);

        assertTrue(prepared.created());
        assertEquals("DK-DEV-0021", prepared.deviceNo());
        assertTrue(prepared.command().getCmdPayload().contains("\"waterTypeId\":8"));
        assertEquals(1, commandCount());
        assertEquals(prepared.commandId(), boundCmdId());
    }

    @Test
    void alreadyBoundOrderReturnsExistingCommandWithoutCreatingNewOne() {
        seedOrder(TradeEnum.OrderStatus.PAID.getValue(), 555L);

        IDispenseDispatchTxService.Prepared prepared = dispatchTxService.prepare(ORDER_ID);

        assertEquals(555L, prepared.commandId());
        assertEquals(0, commandCount(), "幂等命中不得新建指令");
    }

    // ==================== 缝隙：读完到抢占之间的并发变化 ====================

    @Test
    void concurrentStationMigrationBeforeClaimProducesNoCommand() {
        mutateAfterRoutingRead("UPDATE ws_order SET STATION_ID=? WHERE ID=?", OTHER_STATION_ID, ORDER_ID);

        assertThrows(JbkException.class, () -> dispatchTxService.prepare(ORDER_ID));

        assertEquals(0, commandCount(), "抢占失败必须连同刚建的指令一起回滚");
        assertNullCmdId();
    }

    @Test
    void concurrentOrderAbnormalBeforeClaimProducesNoCommand() {
        mutateAfterRoutingRead("UPDATE ws_order SET ORDER_STATUS=? WHERE ID=?",
                TradeEnum.OrderStatus.ABNORMAL.getValue(), ORDER_ID);

        assertThrows(JbkException.class, () -> dispatchTxService.prepare(ORDER_ID));

        assertEquals(0, commandCount());
        assertNullCmdId();
    }

    @Test
    void claimFailureRollsBackInsertedCommand() {
        Mockito.doThrow(new IllegalStateException("模拟抢占异常"))
                .when(tradeTxSpy).claimCommandSlot(anyLong(), any(), any(), any(), any());

        assertThrows(IllegalStateException.class, () -> dispatchTxService.prepare(ORDER_ID));

        assertEquals(0, commandCount(), "抢占抛异常时新建指令必须随事务回滚，不留垃圾行");
    }

    private void assertNullCmdId() {
        assertEquals(null, boundCmdId(), "订单不得被绑定到任何指令");
    }

    // ==================== 事务前的档案变化 ====================

    @Test
    void outletReboundToAnotherDeviceIsRejected() {
        jdbc.update("UPDATE ws_device_outlet SET DEVICE_ID=? WHERE ID=?", DEVICE_ID + 5, OUTLET_ID);

        assertThrows(JbkException.class, () -> dispatchTxService.prepare(ORDER_ID));

        assertEquals(0, commandCount());
    }

    @Test
    void waterTypeChangedAfterOrderIsRejected() {
        jdbc.update("UPDATE ws_device_outlet SET WATER_TYPE_ID=? WHERE ID=?", 9L, OUTLET_ID);

        JbkException denied = assertThrows(JbkException.class, () -> dispatchTxService.prepare(ORDER_ID));

        assertTrue(denied.getMsg().contains("水种"), "拒因必须点明水种变更，实际=" + denied.getMsg());
        assertEquals(0, commandCount());
        assertNullCmdId();
    }

    @Test
    void offlineDeviceIsRejectedWithoutCommand() {
        jdbc.update("UPDATE ws_device SET ONLINE_STATUS=2 WHERE ID=?", DEVICE_ID);

        assertThrows(JbkException.class, () -> dispatchTxService.prepare(ORDER_ID));

        assertEquals(0, commandCount());
    }

    @Test
    void duplicateFaultDictRowsBlockDispatch() {
        jdbc.update("UPDATE ws_device SET RUN_STATUS=3, LAST_FAULT_CODE='E100' WHERE ID=?", DEVICE_ID);
        jdbc.update("INSERT INTO ws_fault_dict(FAULT_CODE,FAULT_NAME,FAULT_LEVEL,BLOCK_ORDER_FLAG) "
                + "VALUES('E100','轻微提示',1,1)");
        jdbc.update("INSERT INTO ws_fault_dict(FAULT_CODE,FAULT_NAME,FAULT_LEVEL,BLOCK_ORDER_FLAG) "
                + "VALUES('E100','严重故障',3,2)");

        JbkException denied = assertThrows(JbkException.class, () -> dispatchTxService.prepare(ORDER_ID));

        assertTrue(denied.getMsg().contains("配置冲突"), "实际=" + denied.getMsg());
        assertEquals(0, commandCount());
    }

    @Test
    void illegalSnapshotIsRejected() {
        jdbc.update("UPDATE ws_order SET PACKAGE_SNAP=? WHERE ID=?", "{\"planMl\":5000}", ORDER_ID);

        assertThrows(JbkException.class, () -> dispatchTxService.prepare(ORDER_ID));

        assertEquals(0, commandCount());
    }

    // ==================== 复审驱动的补强 ====================

    @Test
    void disabledStationAfterPaymentIsRejected() {
        jdbc.update("UPDATE ws_station SET STATION_STATUS=2 WHERE ID=?", STATION_ID);

        JbkException denied = assertThrows(JbkException.class, () -> dispatchTxService.prepare(ORDER_ID));

        assertTrue(denied.getMsg().contains("水站"), "实际=" + denied.getMsg());
        assertEquals(0, commandCount());
    }

    @Test
    void planMlInconsistentWithSnapshotIsRejected() {
        // 纵深防御：扣款后若有任何路径改写 PLAN_ML，设备就会按新水量出水而用户按旧金额付费
        jdbc.update("UPDATE ws_order SET PLAN_ML=? WHERE ID=?", 9000L, ORDER_ID);

        assertThrows(JbkException.class, () -> dispatchTxService.prepare(ORDER_ID));

        assertEquals(0, commandCount());
    }

    @Test
    void legacySnapshotWithoutWaterTypeStillDispatches() {
        // 水种冻结上线前的历史单没有 waterTypeId：无从比对，按既有兼容口径放行，不把在途老单判死
        jdbc.update("UPDATE ws_order SET PACKAGE_SNAP=? WHERE ID=?",
                "{\"requestId\":\"scan-legacy\",\"unitPriceFenPerLiter\":20,\"planMl\":5000,\"payWay\":2}",
                ORDER_ID);

        IDispenseDispatchTxService.Prepared prepared = dispatchTxService.prepare(ORDER_ID);

        assertTrue(prepared.created());
        assertEquals(1, commandCount());
    }

    // ==================== 并发唯一性 ====================

    @Test
    void concurrentPrepareYieldsExactlyOneEffectiveCommand() throws Exception {
        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(15, TimeUnit.SECONDS);
                try {
                    return dispatchTxService.prepare(ORDER_ID).commandId();
                }
                catch (RuntimeException rejected) {
                    return null;
                }
            }));
        }
        List<Long> ids = new ArrayList<>();
        for (Future<Long> f : futures) {
            Long id = f.get(60, TimeUnit.SECONDS);
            if (id != null) {
                ids.add(id);
            }
        }
        pool.shutdown();

        Long bound = boundCmdId();
        assertNotNull(bound, "必须恰好绑定一条有效指令");
        assertTrue(ids.stream().allMatch(bound::equals),
                "所有成功返回的指令ID必须都是订单绑定的那一条，实际=" + ids);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_command WHERE ORDER_ID=?", Integer.class, ORDER_ID),
                "库里只能留下赢家那一条（输方在设备行锁上串行化后按幂等提前返回，"
                        + "「插入后抢占失败即回滚」由 claimFailureRollsBackInsertedCommand 单独钉）");
    }
}
