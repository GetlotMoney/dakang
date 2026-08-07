package com.jbk.serve.service.trade.impl;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.aftersale.WsCardEntitlementBatchMapper;
import com.jbk.serve.mapper.aftersale.WsEntitlementAllocationMapper;
import com.jbk.serve.mapper.device.WsDeviceMapper;
import com.jbk.serve.mapper.device.WsDeviceOutletMapper;
import com.jbk.serve.mapper.device.WsFaultDictMapper;
import com.jbk.serve.mapper.device.WsQrcodeMapper;
import com.jbk.serve.mapper.station.WsStationMapper;
import com.jbk.serve.mapper.trade.TradeCardMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.mapper.user.WsCardMemberMapper;
import com.jbk.serve.service.aftersale.batch.EntitlementFixture;
import com.jbk.serve.service.aftersale.batch.EntitlementLedger;
import com.jbk.serve.service.delivery.impl.DeliveryDbSchema;
import com.jbk.serve.service.device.DeviceAvailabilityGuard;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.serve.service.trade.ITradeOrderTxService;
import com.jbk.tool.data.mini.vo.ScanSessionInfo;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.exception.JbkException;
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

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CARD-MEMBER 成员取水事务的<b>真实 MySQL + 真实 Spring 事务</b>集成测试
 * （搭法对齐 WaterOrderTxDbTest）。这里测的都是单测 Mock 无法证明的事实：
 * <ol>
 *   <li><b>双主体落库</b>：订单/流水 USER_ID=成员（实际使用人），扣减作用卡主的卡；</li>
 *   <li><b>非成员/无效授权拒绝零副作用</b>：无授权、已撤销、未生效、已失效一律拒且卡/订单/流水零变化；</li>
 *   <li><b>日限额边界</b>：恰好用完放行、超 1 毫升拒绝；</li>
 *   <li><b>20 并发不破限额</b>：靠 uk_card_member_user 成员关系行锁串行化；</li>
 *   <li><b>退差回卡主卡</b>：成员订单结算退差加回卡主的卡，流水 USER_ID 仍为成员。</li>
 * </ol>
 * 无 Docker 环境自动跳过。
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = WaterOrderMemberTxDbTest.Ctx.class)
class WaterOrderMemberTxDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_member_it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static final long OWNER_ID = 9L;
    private static final long MEMBER_ID = 4L;
    private static final long STRANGER_ID = 8L;
    private static final long CARD_ID = 100L;
    private static final long STATION_ID = 41L;
    private static final long DEVICE_ID = 21L;
    private static final long OUTLET_ID = 31L;
    private static final long QRCODE_ID = 11L;
    private static final String NOW = "20260723120000";
    private static final long BALANCE_FEN = 5_000L;
    private static final long BALANCE_ML = 200_000L;
    private static final long DAY_LIMIT_ML = 10_000L;
    private static final String SCOPE_MATCH =
            "{\"scopeType\":\"specified\",\"stationIds\":[41],\"deviceIds\":[21],\"outletIds\":[31]}";

    @Configuration
    @EnableTransactionManagement
    static class Ctx {
        @Bean
        DataSource dataSource() {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl(MYSQL.getJdbcUrl());
            ds.setUsername(MYSQL.getUsername());
            ds.setPassword(MYSQL.getPassword());
            // 并发限额测试需要 20 线程同时持有连接排队在成员关系行锁上
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
            // 生产同一份 XML：TradeCardMapper（扣减/锁卡）+ WsOrderMapper（成员日限额统计）
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath:mapper/trade/*.xml"));
            return new SqlSessionTemplate(factory.getObject());
        }

        @Bean
        MapperFactoryBean<TradeCardMapper> tradeCardMapper(SqlSessionTemplate t) {
            return mapper(TradeCardMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsCardMemberMapper> wsCardMemberMapper(SqlSessionTemplate t) {
            return mapper(WsCardMemberMapper.class, t);
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
        MapperFactoryBean<WsQrcodeMapper> wsQrcodeMapper(SqlSessionTemplate t) {
            return mapper(WsQrcodeMapper.class, t);
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
        MapperFactoryBean<WsStationMapper> wsStationMapper(SqlSessionTemplate t) {
            return mapper(WsStationMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsCardEntitlementBatchMapper> wsCardEntitlementBatchMapper(SqlSessionTemplate t) {
            // 包D-4：取水扣减同事务写权益分摊，故本上下文必须提供这两个 Mapper
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
            // 真实台账而不是 Mock：分摊要摊到真表上，才能验证「卡扣了、批次也扣了」
            return new EntitlementLedger(batchMapper, allocationMapper, tradeCardMapper, walletFlowMapper);
        }

        private static <M> MapperFactoryBean<M> mapper(Class<M> type, SqlSessionTemplate template) {
            MapperFactoryBean<M> bean = new MapperFactoryBean<>(type);
            bean.setSqlSessionTemplate(template);
            return bean;
        }

        @Bean
        IWsDomainEventService domainEventService() {
            return Mockito.mock(IWsDomainEventService.class);
        }

        @Bean
        com.jbk.serve.service.settlement.ISplitService splitService() {
            // E2E-08 完成挂点协作方：本类锁既有资金事实，分账行为由 SettlementDbTest 用真库锁定
            return org.mockito.Mockito.mock(com.jbk.serve.service.settlement.ISplitService.class);
        }

        @Bean
        MapperFactoryBean<WsFaultDictMapper> wsFaultDictMapper(SqlSessionTemplate t) {
            // B20：事务内可用性复验要读故障码字典（未登记码 fail-closed）
            return mapper(WsFaultDictMapper.class, t);
        }

        @Bean
        DeviceAvailabilityGuard deviceAvailabilityGuard(WsDeviceMapper d, WsDeviceOutletMapper o,
                                                        WsFaultDictMapper f) {
            // 真实 Guard 而不是 Mock：FOR UPDATE 当前读与判定接线必须在真库上被证明
            return new DeviceAvailabilityGuard(d, o, f);
        }

        @Bean
        TradeOrderTxServiceImpl tradeOrderTxService() {
            return new TradeOrderTxServiceImpl();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    @Autowired
    private ITradeOrderTxService tx;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_card (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  CARD_NO VARCHAR(32), CARD_TYPE TINYINT, USER_ID BIGINT,
                  BALANCE_AMOUNT BIGINT NOT NULL DEFAULT 0, BALANCE_ML BIGINT NOT NULL DEFAULT 0,
                  PACKAGE_ID BIGINT NULL, PACKAGE_SNAP MEDIUMTEXT NULL, SCOPE_JSON VARCHAR(2000) NULL,
                  EXPIRE_TIME VARCHAR(20) NULL, CARD_STATUS TINYINT, CARD_REMARK VARCHAR(255) NULL,
                  ISSUE_ORDER_ID BIGINT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        // 与迁移后的权威结构同形：uk_card_member_user 是并发限额串行化的行锁锚点
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_card_member (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  CARD_ID BIGINT, MEMBER_USER_ID BIGINT, MEMBER_NAME VARCHAR(50),
                  DAY_LIMIT_ML BIGINT NULL, EFFECTIVE_TIME VARCHAR(20) NULL, EXPIRE_TIME VARCHAR(20) NULL,
                  MEMBER_STATUS TINYINT,
                  UNIQUE KEY uk_card_member_user (CARD_ID, MEMBER_USER_ID)
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
                  REFERRER_USER_ID BIGINT NULL,
                  UNIQUE KEY uk_order_no (ORDER_NO),
                  KEY idx_order_card_user_type_time (CARD_ID, USER_ID, ORDER_TYPE, CREATE_TIME)
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
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_command (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT, ORDER_ID BIGINT, CMD_STATUS TINYINT,
                  DATA_STATUS TINYINT DEFAULT 0
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_station (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  STATION_NAME VARCHAR(100), STATION_CODE VARCHAR(50), STATION_REGION VARCHAR(100),
                  STATION_ADDRESS VARCHAR(200), STATION_LNG VARCHAR(20), STATION_LAT VARCHAR(20),
                  STATION_STATUS TINYINT, OWNER_USER_ID BIGINT NULL, CHANNEL_USER_ID BIGINT NULL,
                  STATION_REMARK VARCHAR(255) NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_device (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  DEVICE_NO VARCHAR(50), DEVICE_NAME VARCHAR(100), DEVICE_MODEL VARCHAR(50),
                  STATION_ID BIGINT, OWNER_USER_ID BIGINT NULL, CHANNEL_USER_ID BIGINT NULL,
                  FIRMWARE_VERSION VARCHAR(50), SIM_ICCID VARCHAR(50), SIM_CARRIER VARCHAR(20),
                  SIM_STATUS TINYINT NULL, SIM_EXPIRE_TIME VARCHAR(14) NULL,
                  ONLINE_STATUS TINYINT, RUN_STATUS TINYINT, LAST_HEARTBEAT VARCHAR(20),
                  LAST_FAULT_CODE VARCHAR(20), LAST_STATUS_DEVICE_TIME VARCHAR(14),
                  SIGNAL_STRENGTH INT, DEVICE_REMARK VARCHAR(255)
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
                CREATE TABLE IF NOT EXISTS ws_qrcode (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  QRCODE_CONTENT VARCHAR(200), QRCODE_TYPE TINYINT, DEVICE_ID BIGINT NULL,
                  OUTLET_ID BIGINT NULL, QRCODE_STATUS TINYINT
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        // 包D-4：取水扣减同事务写权益分摊，建表与生产同源（SchemaParityTest 常态守卫）
        DeliveryDbSchema.createEntitlementTables(jdbc);
        jdbc.execute("TRUNCATE TABLE ws_entitlement_allocation");
        jdbc.execute("TRUNCATE TABLE ws_card_entitlement_batch");
        jdbc.execute("TRUNCATE TABLE ws_card_member");
        jdbc.execute("TRUNCATE TABLE ws_wallet_flow");
        jdbc.execute("TRUNCATE TABLE ws_order");
        jdbc.execute("TRUNCATE TABLE ws_command");
        jdbc.execute("TRUNCATE TABLE ws_card");
        jdbc.execute("TRUNCATE TABLE ws_station");
        jdbc.execute("TRUNCATE TABLE ws_device");
        jdbc.execute("TRUNCATE TABLE ws_device_outlet");
        jdbc.execute("TRUNCATE TABLE ws_qrcode");

        jdbc.update("INSERT INTO ws_station(ID,STATION_NAME,STATION_CODE,STATION_STATUS) VALUES(?,?,?,1)",
                STATION_ID, "测试站", "ST-41");
        jdbc.update("INSERT INTO ws_device(ID,DEVICE_NO,DEVICE_NAME,STATION_ID,ONLINE_STATUS,RUN_STATUS) "
                + "VALUES(?,?,?,?,1,1)", DEVICE_ID, "DK-DEV-0021", "测试机", STATION_ID);
        jdbc.update("INSERT INTO ws_device_outlet(ID,DEVICE_ID,OUTLET_NO,WATER_TYPE_ID,WATER_TYPE,"
                + "OUTLET_PRICE,OUTLET_STATUS) VALUES(?,?,1,8,'纯净水','20',1)", OUTLET_ID, DEVICE_ID);
        jdbc.update("INSERT INTO ws_qrcode(ID,QRCODE_CONTENT,QRCODE_TYPE,DEVICE_ID,OUTLET_ID,QRCODE_STATUS) "
                + "VALUES(?,?,1,?,?,1)", QRCODE_ID, "DK-QR-0021-O1", DEVICE_ID, OUTLET_ID);
        jdbc.update("INSERT INTO ws_card(ID,DATA_STATUS,CARD_NO,CARD_TYPE,USER_ID,BALANCE_AMOUNT,BALANCE_ML,"
                        + "SCOPE_JSON,EXPIRE_TIME,CARD_STATUS) VALUES(?,0,?,1,?,?,?,?,NULL,1)",
                CARD_ID, "VC-TEST-100", OWNER_ID, BALANCE_FEN, BALANCE_ML, SCOPE_MATCH);
        // 包D-4：卡是裸 INSERT，补历史聚合批次以满足「批次剩余合计 == 卡聚合值」
        EntitlementFixture.seedLegacyBatch(jdbc, CARD_ID, OWNER_ID, BALANCE_FEN, BALANCE_ML);
        seedMember(0, 1, null, null, DAY_LIMIT_ML);
    }

    /** 重建唯一成员关系行（uk 定位）；参数摆布状态/时间窗/限额。 */
    private void seedMember(int dataStatus, int memberStatus, String effective, String expire, Long dayLimit) {
        jdbc.update("DELETE FROM ws_card_member WHERE CARD_ID=? AND MEMBER_USER_ID=?", CARD_ID, MEMBER_ID);
        jdbc.update("INSERT INTO ws_card_member(DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,"
                        + "CARD_ID,MEMBER_USER_ID,MEMBER_NAME,DAY_LIMIT_ML,EFFECTIVE_TIME,EXPIRE_TIME,MEMBER_STATUS) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                dataStatus, OWNER_ID, NOW, OWNER_ID, NOW, CARD_ID, MEMBER_ID, "赵先生",
                dayLimit, effective, expire, memberStatus);
    }

    private ScanSessionInfo session(long userId) {
        return new ScanSessionInfo()
                // S2：会话冻结报价（与 order() 的 PACKAGE_SNAP 同源，事务内三方一致校验据此比对）
                .setScanSessionId("scan-m")
                .setWaterTypeId(8L)
                .setUnitPriceFenPerLiter(20)
                .setQuotedAt(NOW)
                .setUserId(userId)
                .setQrcodeId(QRCODE_ID)
                .setStationId(STATION_ID)
                .setDeviceId(DEVICE_ID)
                .setOutletId(OUTLET_ID);
    }

    /**
     * 与编排层同构的订单装配（USER_ID=实际使用人）。测试 Ctx 未注册 MetaObjectHandler，
     * 生产中由其填充的字段这里显式补齐：CREATE_TIME=NOW（日限额统计按 CREATE_TIME 圈当天窗口，
     * 缺了它订单漏出统计）；DATA_STATUS=0（fill 字段会被 MP 强制写入 INSERT，无 handler 时写 NULL，
     * `@TableLogic` 过滤的 selectById 将读不到该单，结算直接失效）。
     */
    private WsOrder order(long actorUserId, String orderNo, int payWay, long planMl, long orderAmount) {
        WsOrder order = new WsOrder()
                .setOrderNo(orderNo)
                .setOrderType(1)
                .setUserId(actorUserId)
                .setStationId(STATION_ID)
                .setDeviceId(DEVICE_ID)
                .setOutletId(OUTLET_ID)
                .setCardId(CARD_ID)
                .setPackageSnap("{\"requestId\":\"scan-m\",\"unitPriceFenPerLiter\":20,\"planMl\":"
                        + planMl + ",\"payWay\":" + payWay + ",\"waterTypeId\":8}")
                .setPlanMl(planMl)
                .setOrderAmount(orderAmount)
                .setPayWay(payWay)
                .setOrderStatus(2);
        order.setDataStatus(0);
        order.setCreateBy(actorUserId);
        order.setCreateTime(NOW);
        order.setUpdateBy(actorUserId);
        order.setUpdateTime(NOW);
        return order;
    }

    private long cardValue(String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM ws_card WHERE ID=?", Long.class, CARD_ID);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void assertZeroSideEffects() {
        assertEquals(BALANCE_FEN, cardValue("BALANCE_AMOUNT"), "余额必须原封不动");
        assertEquals(BALANCE_ML, cardValue("BALANCE_ML"), "水量必须原封不动");
        assertEquals(0, count("ws_order"), "订单零新增");
        assertEquals(0, count("ws_wallet_flow"), "流水零新增");
        assertEquals(0, count("ws_command"), "指令零新增");
    }

    // ================= 1：成员正常取水（双主体落库） =================

    @Test
    void memberOrderChargesOwnerCardAndRecordsActor() {
        WsOrder saved = tx.createWaterOrder(order(MEMBER_ID, "WOM-OK-1", 3, 5_000L, 0L), session(MEMBER_ID), NOW);

        assertNotNull(saved.getId());
        // 扣减作用卡主的卡；UPDATE_BY=实际使用人（成员）
        assertEquals(BALANCE_ML - 5_000L, cardValue("BALANCE_ML"));
        assertEquals(OWNER_ID, jdbc.queryForObject("SELECT USER_ID FROM ws_card WHERE ID=?", Long.class, CARD_ID),
                "ws_card.USER_ID 恒为卡主，绝不因成员取水漂移");
        assertEquals(MEMBER_ID, jdbc.queryForObject("SELECT UPDATE_BY FROM ws_card WHERE ID=?", Long.class, CARD_ID));
        // 订单与流水 USER_ID=实际使用人
        assertEquals(MEMBER_ID, jdbc.queryForObject("SELECT USER_ID FROM ws_order WHERE ID=?", Long.class, saved.getId()));
        assertEquals(MEMBER_ID, jdbc.queryForObject("SELECT USER_ID FROM ws_wallet_flow LIMIT 1", Long.class));
        assertEquals(CARD_ID, jdbc.queryForObject("SELECT CARD_ID FROM ws_wallet_flow LIMIT 1", Long.class));
        assertEquals(-5_000L, jdbc.queryForObject("SELECT ML_CHANGE FROM ws_wallet_flow LIMIT 1", Long.class));
    }

    // ================= 2：非成员与无效授权拒绝零副作用 =================

    /** 无任何授权关系的陌生用户：与「卡不存在」同口径拒绝。 */
    @Test
    void strangerWithoutGrantIsRejectedWithZeroSideEffects() {
        JbkException ex = assertThrows(JbkException.class,
                () -> tx.createWaterOrder(order(STRANGER_ID, "WOM-ST-1", 3, 5_000L, 0L), session(STRANGER_ID), NOW));

        assertTrue(ex.getMessage().contains("不存在或不属于当前用户"), "实际=" + ex.getMessage());
        assertZeroSideEffects();
    }

    /** 已撤销（MEMBER_STATUS=2）。 */
    @Test
    void revokedMemberIsRejectedWithZeroSideEffects() {
        seedMember(0, 2, null, null, DAY_LIMIT_ML);
        assertThrows(JbkException.class,
                () -> tx.createWaterOrder(order(MEMBER_ID, "WOM-RV-1", 3, 5_000L, 0L), session(MEMBER_ID), NOW));
        assertZeroSideEffects();
    }

    /** 逻辑删除的授权（DATA_STATUS=1）：锁得到但必须显式拒绝。 */
    @Test
    void deletedGrantIsRejectedWithZeroSideEffects() {
        seedMember(1, 1, null, null, DAY_LIMIT_ML);
        assertThrows(JbkException.class,
                () -> tx.createWaterOrder(order(MEMBER_ID, "WOM-DL-1", 3, 5_000L, 0L), session(MEMBER_ID), NOW));
        assertZeroSideEffects();
    }

    /** 未生效（EFFECTIVE_TIME 在未来）。 */
    @Test
    void notYetEffectiveGrantIsRejectedWithZeroSideEffects() {
        seedMember(0, 1, "20260724000000", null, DAY_LIMIT_ML);
        assertThrows(JbkException.class,
                () -> tx.createWaterOrder(order(MEMBER_ID, "WOM-NE-1", 3, 5_000L, 0L), session(MEMBER_ID), NOW));
        assertZeroSideEffects();
    }

    /** 已失效（EXPIRE_TIME<=now，边界含相等）。 */
    @Test
    void expiredGrantIsRejectedWithZeroSideEffects() {
        seedMember(0, 1, null, NOW, DAY_LIMIT_ML);
        assertThrows(JbkException.class,
                () -> tx.createWaterOrder(order(MEMBER_ID, "WOM-EX-1", 3, 5_000L, 0L), session(MEMBER_ID), NOW));
        assertZeroSideEffects();
    }

    /** 撤销后再取拒绝：先成功一单，撤销后第二单必须拒，且第一单事实保持不变。 */
    @Test
    void orderAfterRevokeIsRejectedKeepingFirstOrderFacts() {
        tx.createWaterOrder(order(MEMBER_ID, "WOM-SEQ-1", 3, 4_000L, 0L), session(MEMBER_ID), NOW);
        // 撤销授权（模拟卡主 revoke 的条件状态更新）
        jdbc.update("UPDATE ws_card_member SET MEMBER_STATUS=2 WHERE CARD_ID=? AND MEMBER_USER_ID=?",
                CARD_ID, MEMBER_ID);

        assertThrows(JbkException.class,
                () -> tx.createWaterOrder(order(MEMBER_ID, "WOM-SEQ-2", 3, 1_000L, 0L), session(MEMBER_ID), NOW));

        assertEquals(BALANCE_ML - 4_000L, cardValue("BALANCE_ML"), "撤销后的拒绝不得动已扣水量");
        assertEquals(1, count("ws_order"));
        assertEquals(1, count("ws_wallet_flow"));
    }

    // ================= 3：日限额边界 =================

    /** 恰好用完限额放行；再超 1 毫升拒绝（Math.addExact 语义在真库端到端生效）。 */
    @Test
    void dayLimitAllowsExactFillAndRejectsOneMlOver() {
        tx.createWaterOrder(order(MEMBER_ID, "WOM-LMT-1", 3, 6_000L, 0L), session(MEMBER_ID), NOW);
        // 恰好用完：6000 + 4000 = 10000 = DAY_LIMIT
        tx.createWaterOrder(order(MEMBER_ID, "WOM-LMT-2", 3, 4_000L, 0L), session(MEMBER_ID), NOW);
        assertEquals(BALANCE_ML - DAY_LIMIT_ML, cardValue("BALANCE_ML"));

        JbkException ex = assertThrows(JbkException.class,
                () -> tx.createWaterOrder(order(MEMBER_ID, "WOM-LMT-3", 3, 1L, 0L), session(MEMBER_ID), NOW));

        assertTrue(ex.getMessage().contains("单日取水限额"), "实际=" + ex.getMessage());
        assertEquals(BALANCE_ML - DAY_LIMIT_ML, cardValue("BALANCE_ML"), "超限拒绝零扣减");
        assertEquals(2, count("ws_order"));
    }

    /** 不限额成员（DAY_LIMIT_ML NULL）不受限。 */
    @Test
    void unlimitedMemberIsNotBlockedByDayLimit() {
        seedMember(0, 1, null, null, null);
        tx.createWaterOrder(order(MEMBER_ID, "WOM-UL-1", 3, 50_000L, 0L), session(MEMBER_ID), NOW);
        assertEquals(BALANCE_ML - 50_000L, cardValue("BALANCE_ML"));
    }

    /** 已退款单不占限额：退款后同额度仍可再取。 */
    @Test
    void refundedOrderReleasesDayLimitQuota() {
        WsOrder first = tx.createWaterOrder(order(MEMBER_ID, "WOM-RF-1", 3, DAY_LIMIT_ML, 0L), session(MEMBER_ID), NOW);
        // 设备零出水，结算全额退（订单 7 已退款，退差回卡主卡）
        assertTrue(tx.settleWaterOrder(first.getId(), true, 0L));
        assertEquals(BALANCE_ML, cardValue("BALANCE_ML"), "全额退差后水量应复原");

        // 状态 7 按 0 占用：同成员当天仍可再取满额
        tx.createWaterOrder(order(MEMBER_ID, "WOM-RF-2", 3, DAY_LIMIT_ML, 0L), session(MEMBER_ID), NOW);
        assertEquals(BALANCE_ML - DAY_LIMIT_ML, cardValue("BALANCE_ML"));
    }

    // ================= 4：20 并发不破限额 =================

    /**
     * 20 线程并发下单，每单 1000ml、限额 10000ml：只允许恰好 10 单成功。
     * 串行化依据是 ④ 步对 uk_card_member_user 行的 FOR UPDATE——没有它，
     * 并发线程会同时读到相同的「已占用」，集体放行突破限额。
     */
    @Test
    void twentyConcurrentMemberOrdersCannotExceedDayLimit() throws Exception {
        final int threads = 20;
        final long perOrderMl = 1_000L;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                final int seq = i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await(30, TimeUnit.SECONDS);
                        tx.createWaterOrder(order(MEMBER_ID, "WOM-CC-" + seq, 3, perOrderMl, 0L),
                                session(MEMBER_ID), NOW);
                        success.incrementAndGet();
                    } catch (JbkException rejected) {
                        limited.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS), "线程未全部就绪");
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS), "并发下单未在期限内完成");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(10, success.get(), "限额 10000ml/每单 1000ml：只允许恰好 10 单成功");
        assertEquals(10, limited.get());
        assertEquals(10, count("ws_order"));
        assertEquals(10, count("ws_wallet_flow"));
        assertEquals(BALANCE_ML - DAY_LIMIT_ML, cardValue("BALANCE_ML"), "总扣减恰好等于限额，绝不突破");
        // 当天占用口径复核：所有成功单均计入统计源
        List<Long> plans = jdbc.queryForList("SELECT PLAN_ML FROM ws_order WHERE USER_ID=?", Long.class, MEMBER_ID);
        assertEquals(10, plans.size());
    }

    // ================= 5：退差回卡主卡 =================

    /** 成员订单结算退差：加回卡主的卡（归属条件），补偿流水 USER_ID 仍为实际使用人。 */
    @Test
    void settlementRefundGoesBackToOwnerCardWithActorFlow() {
        WsOrder saved = tx.createWaterOrder(order(MEMBER_ID, "WOM-RT-1", 3, 6_000L, 0L), session(MEMBER_ID), NOW);
        assertEquals(BALANCE_ML - 6_000L, cardValue("BALANCE_ML"));

        // 实际只出 3500ml：退差 2500ml 回卡主卡
        assertTrue(tx.settleWaterOrder(saved.getId(), true, 3_500L));

        assertEquals(BALANCE_ML - 3_500L, cardValue("BALANCE_ML"), "退差必须加回卡主的同一张卡");
        assertEquals(OWNER_ID, jdbc.queryForObject("SELECT USER_ID FROM ws_card WHERE ID=?", Long.class, CARD_ID));
        Long compensateUser = jdbc.queryForObject(
                "SELECT USER_ID FROM ws_wallet_flow WHERE FLOW_TYPE=4", Long.class);
        assertEquals(MEMBER_ID, compensateUser, "补偿流水 USER_ID=实际使用人（成员）");
        assertEquals(2_500L, jdbc.queryForObject(
                "SELECT ML_CHANGE FROM ws_wallet_flow WHERE FLOW_TYPE=4", Long.class));
    }
}
