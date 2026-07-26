package com.jbk.serve.service.trade.impl;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.device.WsDeviceMapper;
import com.jbk.serve.mapper.device.WsDeviceOutletMapper;
import com.jbk.serve.mapper.device.WsQrcodeMapper;
import com.jbk.serve.mapper.station.WsStationMapper;
import com.jbk.serve.mapper.trade.TradeCardMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.mapper.user.WsCardMemberMapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CARD-SCOPE 取水下单事务的<b>真实 MySQL + 真实 Spring 事务</b>集成测试（搭法对齐 RechargeIssueTxDbTest）。
 *
 * <p>这里测的都是单测 Mock 无法证明的事务内事实：</p>
 * <ol>
 *   <li><b>范围/归属/状态失败零副作用</b>：卡余额水量不变、ws_order/ws_wallet_flow/ws_command 零新增
 *       （扫码会话在 Redis，由编排层单测 WaterOrderIdempotencyTest 钉「失败不消费」）。</li>
 *   <li><b>共键错位 fail-closed</b>：qrcode/outlet/device/station 任一错位，锁内重核必须拒单。</li>
 *   <li><b>支付维度隔离</b>：余额支付在 balanceMl=0 时必须放行；水量支付在 balanceMl 不足时必须拒。</li>
 *   <li><b>AND 语义在事务内真实生效</b>：站命中但设备不命中必须拒（否则水站命中绕过设备限制）。</li>
 * </ol>
 *
 * <p>无 Docker 环境自动跳过。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = WaterOrderTxDbTest.Ctx.class)
class WaterOrderTxDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_water_it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static final long USER_ID = 9L;
    private static final long OTHER_USER = 7L;
    private static final long CARD_ID = 100L;
    private static final long STATION_ID = 41L;
    private static final long DEVICE_ID = 21L;
    private static final long OUTLET_ID = 31L;
    private static final long QRCODE_ID = 11L;
    private static final String NOW = "20260723120000";
    private static final long BALANCE_FEN = 5_000L;
    private static final long BALANCE_ML = 20_000L;
    /** 与本站-设备-出水口完全命中的范围。 */
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
            // 与生产同一个审计字段填充器：@TableLogic 的 DATA_STATUS 依赖 INSERT 填充为 0，
            // 缺了它插入行 DATA_STATUS=NULL，后续 MP 条件更新（自动追加 DATA_STATUS=0）会全部落空
            com.baomidou.mybatisplus.core.config.GlobalConfig globalConfig =
                    new com.baomidou.mybatisplus.core.config.GlobalConfig();
            globalConfig.setMetaObjectHandler(new com.jbk.tool.config.system.mybatis.MpMetaObjectHandler());
            factory.setGlobalConfig(globalConfig);
            // 原子扣减/锁卡 SQL 在 XML：必须加载生产同一份 TradeCardMapper.xml，而不是在测试里复刻 SQL；
            // WsOrderMapper.xml 含成员日限额统计（CARD-MEMBER），一并加载生产同一份
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
            // CARD-MEMBER：非卡主分支锁成员关系行；本类只测卡主/无关用户路径，但事务实现已依赖该 Mapper
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

        private static <M> MapperFactoryBean<M> mapper(Class<M> type, SqlSessionTemplate template) {
            MapperFactoryBean<M> bean = new MapperFactoryBean<>(type);
            bean.setSqlSessionTemplate(template);
            return bean;
        }

        @Bean
        IWsDomainEventService domainEventService() {
            // 共键拒绝走 recordReliable 独立留痕；此处只验证资金事实，不建 ws_domain_event 表
            return Mockito.mock(IWsDomainEventService.class);
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

    /** 按接口注入：@Transactional 走 JDK 接口代理（注入成功即证明事务增强已织入）。 */
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
                  UNIQUE KEY uk_order_no (ORDER_NO)
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
        // 本事务不写指令；建表是为了把「指令零新增」变成可执行断言而不是口头保证
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
                  ONLINE_STATUS TINYINT, RUN_STATUS TINYINT, LAST_HEARTBEAT VARCHAR(20),
                  LAST_FAULT_CODE VARCHAR(20), SIGNAL_STRENGTH INT, DEVICE_REMARK VARCHAR(255)
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
        // CARD-MEMBER：非卡主分支会锁查 ws_card_member；本类的他人卡用例（无成员授权）也走到该查询
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
        seedCard(USER_ID, 1, SCOPE_MATCH, BALANCE_FEN, BALANCE_ML, 0);
    }

    private void seedCard(long userId, int cardStatus, String scopeJson, long fen, long ml, int dataStatus) {
        jdbc.update("DELETE FROM ws_card WHERE ID=?", CARD_ID);
        jdbc.update("INSERT INTO ws_card(ID,DATA_STATUS,CARD_NO,CARD_TYPE,USER_ID,BALANCE_AMOUNT,BALANCE_ML,"
                        + "SCOPE_JSON,EXPIRE_TIME,CARD_STATUS) VALUES(?,?,?,1,?,?,?,?,NULL,?)",
                CARD_ID, dataStatus, "VC-TEST-100", userId, fen, ml, scopeJson, cardStatus);
    }

    private ScanSessionInfo session() {
        return new ScanSessionInfo()
                .setUserId(USER_ID)
                .setQrcodeId(QRCODE_ID)
                .setStationId(STATION_ID)
                .setDeviceId(DEVICE_ID)
                .setOutletId(OUTLET_ID);
    }

    /** 与编排层同构的订单装配：payWay3 金额0；payWay2 预扣 ceil。 */
    private WsOrder order(int payWay, long planMl, long orderAmount) {
        return new WsOrder()
                .setOrderNo("WOTEST" + payWay + "X" + planMl)
                .setOrderType(1)
                .setUserId(USER_ID)
                .setStationId(STATION_ID)
                .setDeviceId(DEVICE_ID)
                .setOutletId(OUTLET_ID)
                .setCardId(CARD_ID)
                .setPackageSnap("{\"requestId\":\"scan-x\",\"unitPriceFenPerLiter\":20,\"planMl\":"
                        + planMl + ",\"payWay\":" + payWay + ",\"waterTypeId\":8}")
                .setPlanMl(planMl)
                .setOrderAmount(orderAmount)
                .setPayWay(payWay)
                .setOrderStatus(2);
    }

    private void assertZeroSideEffects() {
        assertEquals(BALANCE_FEN, cardValue("BALANCE_AMOUNT"), "余额必须原封不动");
        assertEquals(BALANCE_ML, cardValue("BALANCE_ML"), "水量必须原封不动");
        assertEquals(0, count("ws_order"), "订单零新增");
        assertEquals(0, count("ws_wallet_flow"), "流水零新增");
        assertEquals(0, count("ws_command"), "指令零新增");
    }

    private long cardValue(String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM ws_card WHERE ID=?", Long.class, CARD_ID);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    // ================= 1：范围命中的正常路径（水量支付） =================

    @Test
    void inScopeMlPaymentDeductsOnceAndWritesOrderWithFlow() {
        WsOrder saved = tx.createWaterOrder(order(3, 5_000L, 0L), session(), NOW);

        assertNotNull(saved.getId());
        assertEquals(BALANCE_ML - 5_000L, cardValue("BALANCE_ML"));
        assertEquals(BALANCE_FEN, cardValue("BALANCE_AMOUNT"), "水量支付不得动余额");
        assertEquals(1, count("ws_order"));
        assertEquals(1, count("ws_wallet_flow"));
        assertEquals(-5_000L, jdbc.queryForObject("SELECT ML_CHANGE FROM ws_wallet_flow", Long.class));
        assertEquals(BALANCE_ML - 5_000L, jdbc.queryForObject("SELECT ML_AFTER FROM ws_wallet_flow", Long.class));
    }

    // ================= 2：范围外拒绝零副作用（含 AND 语义证伪） =================

    /** 站命中但设备不命中：AND 语义必须拒。这条没了就是「水站命中绕过设备限制」。 */
    @Test
    void stationHitDeviceMissIsRejectedWithZeroSideEffects() {
        seedCard(USER_ID, 1,
                "{\"scopeType\":\"specified\",\"stationIds\":[41],\"deviceIds\":[99]}",
                BALANCE_FEN, BALANCE_ML, 0);

        JbkException ex = assertThrows(JbkException.class,
                () -> tx.createWaterOrder(order(3, 5_000L, 0L), session(), NOW));

        assertTrue(ex.getMessage().contains("不适用于当前设备"), "实际=" + ex.getMessage());
        assertZeroSideEffects();
    }

    @Test
    void outOfScopeStationIsRejectedWithZeroSideEffects() {
        seedCard(USER_ID, 1, "{\"scopeType\":\"specified\",\"stationIds\":[99]}",
                BALANCE_FEN, BALANCE_ML, 0);

        assertThrows(JbkException.class,
                () -> tx.createWaterOrder(order(3, 5_000L, 0L), session(), NOW));
        assertZeroSideEffects();
    }

    /** 空/非法范围＝未配置＝默认拒绝，不得因「解析失败」放行。 */
    @Test
    void blankOrMalformedScopeIsRejectedWithZeroSideEffects() {
        seedCard(USER_ID, 1, null, BALANCE_FEN, BALANCE_ML, 0);
        assertThrows(JbkException.class,
                () -> tx.createWaterOrder(order(3, 5_000L, 0L), session(), NOW));
        assertZeroSideEffects();

        seedCard(USER_ID, 1, "not-json", BALANCE_FEN, BALANCE_ML, 0);
        assertThrows(JbkException.class,
                () -> tx.createWaterOrder(order(3, 5_000L, 0L), session(), NOW));
        assertZeroSideEffects();
    }

    // ================= 3：共键错位拒绝 =================

    /** 二维码被改绑到别的出水口（outlet 挂在另一台设备下）：锁内重核必须拒。 */
    @Test
    void qrcodeOutletMismatchIsRejectedWithZeroSideEffects() {
        jdbc.update("INSERT INTO ws_device(ID,DEVICE_NO,STATION_ID,ONLINE_STATUS,RUN_STATUS) "
                + "VALUES(22,'DK-DEV-0022',?,1,1)", STATION_ID);
        jdbc.update("INSERT INTO ws_device_outlet(ID,DEVICE_ID,OUTLET_NO,WATER_TYPE_ID,WATER_TYPE,"
                + "OUTLET_PRICE,OUTLET_STATUS) VALUES(32,22,1,8,'纯净水','20',1)");
        jdbc.update("UPDATE ws_qrcode SET OUTLET_ID=32 WHERE ID=?", QRCODE_ID);

        JbkException ex = assertThrows(JbkException.class,
                () -> tx.createWaterOrder(order(3, 5_000L, 0L), session(), NOW));

        assertTrue(ex.getMessage().contains("请重新扫码"), "实际=" + ex.getMessage());
        assertZeroSideEffects();
    }

    /** outlet 档案被改挂到另一台设备：outlet.deviceId != device.id 必须拒。 */
    @Test
    void outletDeviceMismatchIsRejectedWithZeroSideEffects() {
        jdbc.update("INSERT INTO ws_device(ID,DEVICE_NO,STATION_ID,ONLINE_STATUS,RUN_STATUS) "
                + "VALUES(22,'DK-DEV-0022',?,1,1)", STATION_ID);
        jdbc.update("UPDATE ws_device_outlet SET DEVICE_ID=22 WHERE ID=?", OUTLET_ID);

        assertThrows(JbkException.class,
                () -> tx.createWaterOrder(order(3, 5_000L, 0L), session(), NOW));
        assertZeroSideEffects();
    }

    /** 停用二维码/水站/出水口任一即拒（档案状态可用是共键校验的一部分）。 */
    @Test
    void disabledArchiveIsRejectedWithZeroSideEffects() {
        jdbc.update("UPDATE ws_qrcode SET QRCODE_STATUS=2 WHERE ID=?", QRCODE_ID);
        assertThrows(JbkException.class,
                () -> tx.createWaterOrder(order(3, 5_000L, 0L), session(), NOW));
        assertZeroSideEffects();

        jdbc.update("UPDATE ws_qrcode SET QRCODE_STATUS=1 WHERE ID=?", QRCODE_ID);
        jdbc.update("UPDATE ws_station SET STATION_STATUS=2 WHERE ID=?", STATION_ID);
        assertThrows(JbkException.class,
                () -> tx.createWaterOrder(order(3, 5_000L, 0L), session(), NOW));
        assertZeroSideEffects();
    }

    /** 会话缺 qrcodeId（旧版本铸造）：宁可要求重扫，也不得跳过共键重核。 */
    @Test
    void sessionWithoutQrcodeIdIsRejectedWithZeroSideEffects() {
        ScanSessionInfo legacy = session().setQrcodeId(null);
        assertThrows(JbkException.class,
                () -> tx.createWaterOrder(order(3, 5_000L, 0L), legacy, NOW));
        assertZeroSideEffects();
    }

    // ================= 3b：P1-A 会话过期语境 fail-closed =================

    /**
     * P1-A 回归：会话铸造后设备被迁到站 42，卡仅允许站 41。
     * 旧实现拿会话里的旧站 41 判范围会放行（等于拿旧授权语境放行新档案的扣款）；
     * 现在范围判定只吃事务内核验后的权威三元组，且会话三元组与档案不一致必须拒单、零副作用。
     */
    @Test
    void deviceRehomedAfterSessionMintIsRejectedWithZeroSideEffects() {
        jdbc.update("INSERT INTO ws_station(ID,STATION_NAME,STATION_CODE,STATION_STATUS) VALUES(42,'迁入站','ST-42',1)");
        jdbc.update("UPDATE ws_device SET STATION_ID=42 WHERE ID=?", DEVICE_ID);
        // 编排层重读设备后订单落库共键是新站 42；会话仍带铸造时的旧站 41
        WsOrder stale = order(3, 5_000L, 0L).setStationId(42L);

        JbkException ex = assertThrows(JbkException.class,
                () -> tx.createWaterOrder(stale, session(), NOW));

        assertTrue(ex.getMessage().contains("请重新扫码"), "实际=" + ex.getMessage());
        assertZeroSideEffects();
    }

    /** P1-A 续：重扫后会话带新站 42——判定必须按站 2（42）作出：仅允许站 41 的卡拒；改授站 42 后放行。 */
    @Test
    void rescanAfterRehomeIsJudgedByVerifiedStation() {
        jdbc.update("INSERT INTO ws_station(ID,STATION_NAME,STATION_CODE,STATION_STATUS) VALUES(42,'迁入站','ST-42',1)");
        jdbc.update("UPDATE ws_device SET STATION_ID=42 WHERE ID=?", DEVICE_ID);
        ScanSessionInfo rescanned = session().setStationId(42L);

        // 卡仅允许站 41：即便设备/出水口都命中，也必须按权威站 42 拒绝（AND 语义）
        seedCard(USER_ID, 1,
                "{\"scopeType\":\"specified\",\"stationIds\":[41],\"deviceIds\":[21],\"outletIds\":[31]}",
                BALANCE_FEN, BALANCE_ML, 0);
        JbkException denied = assertThrows(JbkException.class,
                () -> tx.createWaterOrder(order(3, 5_000L, 0L).setStationId(42L), rescanned, NOW));
        assertTrue(denied.getMessage().contains("不适用于当前设备"), "实际=" + denied.getMessage());
        assertZeroSideEffects();

        // 对照组：卡改授站 42 后按新站放行——证明拒绝不是"不判"，而是按权威三元组判
        seedCard(USER_ID, 1,
                "{\"scopeType\":\"specified\",\"stationIds\":[42],\"deviceIds\":[21],\"outletIds\":[31]}",
                BALANCE_FEN, BALANCE_ML, 0);
        WsOrder saved = tx.createWaterOrder(order(3, 5_000L, 0L).setStationId(42L), rescanned, NOW);
        assertNotNull(saved.getId());
        assertEquals(BALANCE_ML - 5_000L, cardValue("BALANCE_ML"));
    }

    // ================= 3c：P0 配套——CMD_ID 抢占 CAS 并发唯一 =================

    /**
     * P0「并发重试最多一条有效指令」的物理保证：CMD_ID 抢占是单条条件 UPDATE（WHERE CMD_ID IS NULL），
     * 并发抢占只允许一个赢家，其余影响 0 行（输方在 WsCommandServiceImpl 中作废自己刚建的 PENDING 指令）。
     */
    @Test
    void concurrentClaimCommandSlotAllowsExactlyOneWinner() throws Exception {
        WsOrder saved = tx.createWaterOrder(order(3, 5_000L, 0L), session(), NOW);
        int threads = 8;
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(threads);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.List<java.util.concurrent.Future<Integer>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            long commandId = 9_000L + i;
            futures.add(pool.submit(() -> {
                barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                return tx.claimCommandSlot(saved.getId(), commandId);
            }));
        }
        int won = 0;
        for (java.util.concurrent.Future<Integer> f : futures) {
            won += f.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(1, won, "并发抢占必须恰好一个赢家");
        Long cmdId = jdbc.queryForObject("SELECT CMD_ID FROM ws_order WHERE ID=?", Long.class, saved.getId());
        assertNotNull(cmdId, "订单必须绑定唯一有效指令");
        // 赢家之后的重复抢占（含赢家自身重放）一律 0 行，CMD_ID 永不被覆盖
        assertEquals(0, tx.claimCommandSlot(saved.getId(), 9_999L));
        assertEquals(cmdId, jdbc.queryForObject("SELECT CMD_ID FROM ws_order WHERE ID=?", Long.class, saved.getId()));
    }

    // ================= 4：归属/状态失败零副作用 =================

    /** 他人卡：锁内重核归属，非卡主必须拒；该用例仅覆盖卡主路径。 */
    @Test
    void foreignCardIsRejectedWithZeroSideEffects() {
        seedCard(OTHER_USER, 1, SCOPE_MATCH, BALANCE_FEN, BALANCE_ML, 0);

        JbkException ex = assertThrows(JbkException.class,
                () -> tx.createWaterOrder(order(3, 5_000L, 0L), session(), NOW));

        assertTrue(ex.getMessage().contains("不存在或不属于当前用户"), "实际=" + ex.getMessage());
        assertEquals(0, count("ws_order"));
        assertEquals(0, count("ws_wallet_flow"));
        assertEquals(BALANCE_ML, cardValue("BALANCE_ML"));
    }

    @Test
    void frozenAndDeletedCardsAreRejectedWithZeroSideEffects() {
        seedCard(USER_ID, 2, SCOPE_MATCH, BALANCE_FEN, BALANCE_ML, 0);
        JbkException frozen = assertThrows(JbkException.class,
                () -> tx.createWaterOrder(order(3, 5_000L, 0L), session(), NOW));
        assertTrue(frozen.getMessage().contains("冻结"), "实际=" + frozen.getMessage());
        assertZeroSideEffects();

        // 逻辑删除卡：锁得到但必须显式拒绝，与「不存在」同口径
        seedCard(USER_ID, 1, SCOPE_MATCH, BALANCE_FEN, BALANCE_ML, 1);
        JbkException deleted = assertThrows(JbkException.class,
                () -> tx.createWaterOrder(order(3, 5_000L, 0L), session(), NOW));
        assertTrue(deleted.getMessage().contains("不存在或不属于当前用户"), "实际=" + deleted.getMessage());
        assertEquals(0, count("ws_order"));
        assertEquals(0, count("ws_wallet_flow"));
    }

    // ================= 5：支付维度隔离 =================

    /** 余额支付在 balanceMl=0 时必须放行：水量维度不得阻断余额支付（CARD-SCOPE 第 5 条）。 */
    @Test
    void balancePaymentSucceedsWhenBalanceMlIsZero() {
        seedCard(USER_ID, 1, SCOPE_MATCH, BALANCE_FEN, 0L, 0);

        WsOrder saved = tx.createWaterOrder(order(2, 5_000L, 100L), session(), NOW);

        assertNotNull(saved.getId());
        assertEquals(BALANCE_FEN - 100L, cardValue("BALANCE_AMOUNT"));
        assertEquals(0L, cardValue("BALANCE_ML"), "余额支付不得动水量");
        assertEquals(1, count("ws_wallet_flow"));
        assertEquals(-100L, jdbc.queryForObject("SELECT AMOUNT_CHANGE FROM ws_wallet_flow", Long.class));
    }

    /** 水量支付在 balanceMl 不足时必须拒，且零副作用。 */
    @Test
    void mlPaymentIsRejectedWhenBalanceMlInsufficient() {
        seedCard(USER_ID, 1, SCOPE_MATCH, BALANCE_FEN, 4_999L, 0);

        JbkException ex = assertThrows(JbkException.class,
                () -> tx.createWaterOrder(order(3, 5_000L, 0L), session(), NOW));

        assertTrue(ex.getMessage().contains("水量不足"), "实际=" + ex.getMessage());
        assertEquals(4_999L, cardValue("BALANCE_ML"));
        assertEquals(BALANCE_FEN, cardValue("BALANCE_AMOUNT"));
        assertEquals(0, count("ws_order"));
        assertEquals(0, count("ws_wallet_flow"));
        assertEquals(0, count("ws_command"));
    }

    /** 余额支付余额不足同样拒且零副作用（对照组：证明放行不是「不校验」）。 */
    @Test
    void balancePaymentIsRejectedWhenBalanceFenInsufficient() {
        seedCard(USER_ID, 1, SCOPE_MATCH, 99L, 0L, 0);

        JbkException ex = assertThrows(JbkException.class,
                () -> tx.createWaterOrder(order(2, 5_000L, 100L), session(), NOW));

        assertTrue(ex.getMessage().contains("余额不足"), "实际=" + ex.getMessage());
        assertEquals(99L, cardValue("BALANCE_AMOUNT"));
        assertEquals(0, count("ws_order"));
        assertEquals(0, count("ws_wallet_flow"));
    }
}
