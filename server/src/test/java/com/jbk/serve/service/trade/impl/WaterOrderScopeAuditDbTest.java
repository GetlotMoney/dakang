package com.jbk.serve.service.trade.impl;

import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.device.WsDeviceMapper;
import com.jbk.serve.mapper.device.WsDeviceOutletMapper;
import com.jbk.serve.mapper.device.WsQrcodeMapper;
import com.jbk.serve.mapper.ops.WsDomainEventMapper;
import com.jbk.serve.mapper.station.WsStationMapper;
import com.jbk.serve.mapper.trade.TradeCardMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.mapper.user.WsCardMemberMapper;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.serve.service.ops.impl.WsDomainEventServiceImpl;
import com.jbk.serve.service.trade.ITradeOrderTxService;
import com.jbk.tool.config.system.mybatis.MpMetaObjectHandler;
import com.jbk.tool.data.mini.vo.ScanSessionInfo;
import com.jbk.tool.data.trade.po.WsOrder;
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
 * P1-C 范围拒绝审计的<b>真实 MySQL + 真实 Spring 事务</b>集成测试（搭法同 WaterOrderTxDbTest，
 * 差异在于接入真实 {@link WsDomainEventServiceImpl}——独立提交语义只有真库真事务能证明）：
 * <ol>
 *   <li>范围外真实提交：主事务整体回滚（卡/订单/流水/指令零业务副作用），但拒绝证据经
 *       recordReliableOnceIndependent（REQUIRES_NEW）独立存活——回滚正是拒绝的业务结果，
 *       证据消失拒绝就成了无痕事件（E2E-03 复审边界：REQUIRES_NEW 只用于独立的拒绝/失败证据）；</li>
 *   <li>跨时刻重复强提交：decidedAt 不同、报文必不相同，撞 uk_domain_event_biz_key 必须按
 *       「已留痕」幂等处理——若误走读回核验会把正常范围拒绝炸成「语义不一致」审计异常，
 *       全库仍恰一条、业务零累积；</li>
 *   <li>非法范围（CARD_SCOPE_INVALID）同样业务回滚 + 证据存活；</li>
 *   <li>范围内成功下单：零拒绝事件。</li>
 * </ol>
 *
 * <p>无 Docker 环境自动跳过。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = WaterOrderScopeAuditDbTest.Ctx.class)
class WaterOrderScopeAuditDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_scope_audit_it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static final long USER_ID = 9L;
    private static final long CARD_ID = 100L;
    private static final long STATION_ID = 41L;
    private static final long DEVICE_ID = 21L;
    private static final long OUTLET_ID = 31L;
    private static final long QRCODE_ID = 11L;
    private static final String NOW = "20260723120000";
    private static final long BALANCE_FEN = 5_000L;
    private static final long BALANCE_ML = 20_000L;
    private static final String SCOPE_MATCH =
            "{\"scopeType\":\"specified\",\"stationIds\":[41],\"deviceIds\":[21],\"outletIds\":[31]}";
    private static final String SCOPE_MISS = "{\"scopeType\":\"specified\",\"stationIds\":[99]}";
    private static final String ORDER_NO = "WOSCOPEAUDIT0001";
    private static final String BIZ_KEY = "CARD_SCOPE_DENY:" + ORDER_NO;

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
            // 领域事件经 ServiceImpl.save 落库：审计字段（CREATE_*/DATA_STATUS）依赖生产同一个填充器
            GlobalConfig globalConfig = new GlobalConfig();
            globalConfig.setMetaObjectHandler(new MpMetaObjectHandler());
            factory.setGlobalConfig(globalConfig);
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
        MapperFactoryBean<WsDomainEventMapper> wsDomainEventMapper(SqlSessionTemplate t) {
            return mapper(WsDomainEventMapper.class, t);
        }

        private static <M> MapperFactoryBean<M> mapper(Class<M> type, SqlSessionTemplate template) {
            MapperFactoryBean<M> bean = new MapperFactoryBean<>(type);
            bean.setSqlSessionTemplate(template);
            return bean;
        }

        @Bean
        WsDomainEventServiceImpl domainEventService() {
            // 真实实现：与业务同事务 + 唯一键幂等是被测物本身，绝不 mock
            return new WsDomainEventServiceImpl();
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
    private IWsDomainEventService domainEventService;
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
        // 与生产 02-ws-business.sql 同名唯一键：uk_domain_event_biz_key 是"不重复刷"的物理保证
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_domain_event (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  EVENT_TYPE TINYINT, EVENT_KEY VARCHAR(64), EVENT_PAYLOAD TEXT,
                  ACTOR_ID BIGINT NULL, ACTOR_PORTAL TINYINT, ACTOR_ROLE VARCHAR(20),
                  WHITELIST_FLAG TINYINT, CONSUMED_FLAG TINYINT,
                  BIZ_IDEMPOTENCY_KEY VARCHAR(64) NULL,
                  UNIQUE KEY uk_domain_event_biz_key (BIZ_IDEMPOTENCY_KEY)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        for (String table : new String[]{"ws_card_member", "ws_wallet_flow", "ws_order", "ws_command",
                "ws_card", "ws_station", "ws_device", "ws_device_outlet", "ws_qrcode", "ws_domain_event"}) {
            jdbc.execute("TRUNCATE TABLE " + table);
        }
        jdbc.update("INSERT INTO ws_station(ID,STATION_NAME,STATION_CODE,STATION_STATUS) VALUES(?,?,?,1)",
                STATION_ID, "测试站", "ST-41");
        jdbc.update("INSERT INTO ws_device(ID,DEVICE_NO,DEVICE_NAME,STATION_ID,ONLINE_STATUS,RUN_STATUS) "
                + "VALUES(?,?,?,?,1,1)", DEVICE_ID, "DK-DEV-0021", "测试机", STATION_ID);
        jdbc.update("INSERT INTO ws_device_outlet(ID,DEVICE_ID,OUTLET_NO,WATER_TYPE_ID,WATER_TYPE,"
                + "OUTLET_PRICE,OUTLET_STATUS) VALUES(?,?,1,8,'纯净水','20',1)", OUTLET_ID, DEVICE_ID);
        jdbc.update("INSERT INTO ws_qrcode(ID,QRCODE_CONTENT,QRCODE_TYPE,DEVICE_ID,OUTLET_ID,QRCODE_STATUS) "
                + "VALUES(?,?,1,?,?,1)", QRCODE_ID, "DK-QR-0021-O1", DEVICE_ID, OUTLET_ID);
    }

    private void seedCard(String scopeJson) {
        jdbc.update("DELETE FROM ws_card WHERE ID=?", CARD_ID);
        jdbc.update("INSERT INTO ws_card(ID,DATA_STATUS,CARD_NO,CARD_TYPE,USER_ID,BALANCE_AMOUNT,BALANCE_ML,"
                        + "SCOPE_JSON,EXPIRE_TIME,CARD_STATUS) VALUES(?,0,'VC-TEST-100',1,?,?,?,?,NULL,1)",
                CARD_ID, USER_ID, BALANCE_FEN, BALANCE_ML, scopeJson);
    }

    private ScanSessionInfo session() {
        return new ScanSessionInfo()
                .setUserId(USER_ID)
                .setQrcodeId(QRCODE_ID)
                .setStationId(STATION_ID)
                .setDeviceId(DEVICE_ID)
                .setOutletId(OUTLET_ID);
    }

    private WsOrder order() {
        return new WsOrder()
                .setOrderNo(ORDER_NO)
                .setOrderType(1)
                .setUserId(USER_ID)
                .setStationId(STATION_ID)
                .setDeviceId(DEVICE_ID)
                .setOutletId(OUTLET_ID)
                .setCardId(CARD_ID)
                .setPackageSnap("{\"requestId\":\"scan-x\",\"unitPriceFenPerLiter\":20,\"planMl\":5000,"
                        + "\"payWay\":3,\"waterTypeId\":8}")
                .setPlanMl(5_000L)
                .setOrderAmount(0L)
                .setPayWay(3)
                .setOrderStatus(2);
    }

    private int denyEventCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_domain_event WHERE BIZ_IDEMPOTENCY_KEY = ?", Integer.class, BIZ_KEY);
    }

    private void assertZeroBusinessSideEffects() {
        assertEquals(BALANCE_FEN, (long) jdbc.queryForObject(
                "SELECT BALANCE_AMOUNT FROM ws_card WHERE ID=?", Long.class, CARD_ID));
        assertEquals(BALANCE_ML, (long) jdbc.queryForObject(
                "SELECT BALANCE_ML FROM ws_card WHERE ID=?", Long.class, CARD_ID));
        assertEquals(0, (int) jdbc.queryForObject("SELECT COUNT(*) FROM ws_order", Integer.class));
        assertEquals(0, (int) jdbc.queryForObject("SELECT COUNT(*) FROM ws_wallet_flow", Integer.class));
        assertEquals(0, (int) jdbc.queryForObject("SELECT COUNT(*) FROM ws_command", Integer.class));
    }

    // 1. 范围外真实提交：主事务回滚零业务副作用，拒绝证据独立存活恰一条
    @Test
    void outOfScopeSubmitKeepsIndependentEvidenceWhileBusinessRollsBack() {
        seedCard(SCOPE_MISS);

        JbkException ex = assertThrows(JbkException.class,
                () -> tx.createWaterOrder(order(), session(), NOW));
        assertTrue(ex.getMessage().contains("不适用于当前设备"), "实际=" + ex.getMessage());

        assertZeroBusinessSideEffects();
        // 独立提交（REQUIRES_NEW）：业务回滚是拒绝的既定结果，证据必须存活——
        // 证据消失，TOCTOU 窗口内的范围拒绝就成了无痕事件，运营/风控无从追溯。
        assertEquals(1, denyEventCount(), "拒绝证据必须独立于业务回滚存活");
    }

    // 2. 跨时刻重复强提交：撞键按已留痕处理（不得被读回核验炸成语义不一致），全库仍恰一条
    @Test
    void repeatedForcedSubmitAtDifferentTimesKeepsExactlyOneEvidence() {
        seedCard(SCOPE_MISS);

        // 三次判定时刻各不相同 → 证据 decidedAt/报文互异。第二三次撞唯一键时若误走
        // 读回核验，这里抛出的将是「审计幂等键被占用且语义不一致」而非范围拒绝文案。
        for (String at : new String[]{NOW, "20260723120001", "20260723120002"}) {
            JbkException ex = assertThrows(JbkException.class,
                    () -> tx.createWaterOrder(order(), session(), at));
            assertTrue(ex.getMessage().contains("不适用于当前设备"),
                    "重复强提交必须仍按范围拒绝返回，实际=" + ex.getMessage());
        }

        assertEquals(1, denyEventCount(), "同一请求跨次重试全库至多一条证据（uk_domain_event_biz_key）");
        assertZeroBusinessSideEffects();
    }

    // 3. 非法/未配置范围（CARD_SCOPE_INVALID）：业务回滚 + 证据同样存活
    @Test
    void invalidScopeSubmitAlsoKeepsIndependentEvidence() {
        seedCard(null);

        assertThrows(JbkException.class, () -> tx.createWaterOrder(order(), session(), NOW));

        assertEquals(1, denyEventCount(), "CARD_SCOPE_INVALID 的拒绝证据同样独立存活");
        assertZeroBusinessSideEffects();
    }

    // 4. 范围内成功下单：零拒绝事件（预检不经过本事务，任何成功路径也不得误落拒绝审计）
    @Test
    void inScopeSuccessWritesNoDenyEvent() {
        seedCard(SCOPE_MATCH);

        WsOrder saved = tx.createWaterOrder(order(), session(), NOW);

        assertNotNull(saved.getId());
        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_domain_event", Integer.class), "成功下单不得留下任何拒绝审计");
        assertEquals(BALANCE_ML - 5_000L, (long) jdbc.queryForObject(
                "SELECT BALANCE_ML FROM ws_card WHERE ID=?", Long.class, CARD_ID));
    }
}
