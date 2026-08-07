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
import com.jbk.tool.consts.ApiEnum;
import com.jbk.tool.consts.mini.MiniRejectCode;
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
            // 共键拒绝走 recordReliable 独立留痕；此处只验证资金事实，不建 ws_domain_event 表
            return Mockito.mock(IWsDomainEventService.class);
        }


        @Bean
        com.jbk.serve.service.settlement.ISplitService splitService() {
            // E2E-08 完成挂点协作方：本类锁资金事实，分账行为由 SettlementDbTest 用真库锁定
            return Mockito.mock(com.jbk.serve.service.settlement.ISplitService.class);
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

    /** 按接口注入：@Transactional 走 JDK 接口代理（注入成功即证明事务增强已织入）。 */
    @Autowired
    private ITradeOrderTxService tx;
    @Autowired
    private JdbcTemplate jdbc;
    /** 快照 vs 当前读用例需要显式外层事务边界。 */
    @Autowired
    private PlatformTransactionManager txManager;

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
                  REFERRER_USER_ID BIGINT NULL,
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
        // B20：事务内设备可用性复验要查故障码字典（未登记码 fail-closed）
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_fault_dict (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  FAULT_CODE VARCHAR(20), FAULT_NAME VARCHAR(50), FAULT_LEVEL TINYINT,
                  BLOCK_ORDER_FLAG TINYINT, FAULT_ADVICE VARCHAR(500) NULL
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
        // 包D-4：取水扣减同事务写权益分摊，建表与生产同源（SchemaParityTest 常态守卫）
        DeliveryDbSchema.createEntitlementTables(jdbc);
        jdbc.execute("TRUNCATE TABLE ws_entitlement_allocation");
        jdbc.execute("TRUNCATE TABLE ws_card_entitlement_batch");
        jdbc.execute("TRUNCATE TABLE ws_card_member");
        jdbc.execute("TRUNCATE TABLE ws_fault_dict");
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
        // 卡是裸 INSERT 出来的，没经过入账，故补一条历史聚合批次让「批次剩余合计 == 卡聚合值」成立
        EntitlementFixture.seedLegacyBatch(jdbc, CARD_ID, userId, fen, ml);
    }

    private ScanSessionInfo session() {
        return new ScanSessionInfo()
                // S2：会话冻结报价（与 order() 的 PACKAGE_SNAP 同源，事务内三方一致校验据此比对）
                .setScanSessionId("scan-x")
                .setWaterTypeId(8L)
                .setUnitPriceFenPerLiter(20)
                .setQuotedAt(NOW)
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
        // 包D-4：拒绝路径必须零分摊——留下分摊行等于「批次扣了、卡没扣」，方向相反同样是断裂
        assertEquals(0, count("ws_entitlement_allocation"));
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

    // ================= 0：设备可用性事务内复验（B20，预检不是安全边界） =================
    //
    // 预检在扣款事务之前，两者之间隔着编排层装配与锁卡等待。这段窗口里设备离线、进入维护、
    // 上报阻断故障码都不会回滚已经通过的预检结论——事务内不重判，就会对一台已知不可用的
    // 设备照常扣款建单。以下用例把「预检通过后设备变化」压到最贴近的位置：直接改库后进事务。

    private void seedFault(String code, int blockOrderFlag) {
        jdbc.update("INSERT INTO ws_fault_dict(FAULT_CODE,FAULT_NAME,FAULT_LEVEL,BLOCK_ORDER_FLAG) "
                + "VALUES(?,?,3,?)", code, "故障" + code, blockOrderFlag);
    }

    /**
     * 审计 P0-1：扣卡前必须先经 settleExpired 作废已到期批次——过期权益不得参与支付。
     * 卡 5000 分中 3000 属过期批次：清算后可用 2000，扣 2500 必须拒绝；扣 1500 成功且
     * 只落在未过期批次上，账本等式在清算后与消费后都成立。
     */
    @Test
    void expiredBatchEntitlementCannotPayForWater() {
        // 既有 legacy 批次（5000/20000 永久）压缩为 2000/20000，另插一条已过期批次 3000/0
        jdbc.update("UPDATE ws_card_entitlement_batch SET GRANT_AMOUNT_FEN=2000, REMAIN_AMOUNT_FEN=2000 "
                + "WHERE CARD_ID=" + CARD_ID);
        jdbc.update("INSERT INTO ws_card_entitlement_batch(CARD_ID, USER_ID, SOURCE_TYPE, "
                + "PAY_AMOUNT_FEN, GRANT_AMOUNT_FEN, GRANT_BONUS_FEN, GRANT_WATER_ML, "
                + "REMAIN_AMOUNT_FEN, REMAIN_WATER_ML, EXPIRE_TIME, SCOPE_JSON, BATCH_STATUS, "
                + "REFUNDED_AMOUNT_FEN, VERSION, DATA_STATUS, CREATE_TIME) "
                + "VALUES(" + CARD_ID + ", " + USER_ID + ", 4, 0, 3000, 3000, 0, 3000, 0, "
                + "'20250101000000', '" + SCOPE_MATCH + "', 6, 0, 1, 0, '20240101000000')");

        // 超过清算后可用额：拒绝——过期的 3000 不作数。整事务回滚（含清算），零副作用：
        // 清算与扣减同生共死，拒绝单不留下"半清算"状态
        assertThrows(JbkException.class, () -> tx.createWaterOrder(order(2, 125_000L, 2500L), session(), NOW));
        assertEquals(5000L, jdbc.queryForObject(
                "SELECT BALANCE_AMOUNT FROM ws_card WHERE ID=" + CARD_ID, Long.class),
                "拒绝单整体回滚：清算不单独持久化");

        // 清算后额度内：成功——同一事务里先作废过期 3000 再扣 1500，只用未过期权益
        tx.createWaterOrder(order(2, 75_000L, 1500L), session(), NOW);
        assertEquals(500L, jdbc.queryForObject(
                "SELECT BALANCE_AMOUNT FROM ws_card WHERE ID=" + CARD_ID, Long.class));
        assertEquals(5, jdbc.queryForObject("SELECT BATCH_STATUS FROM ws_card_entitlement_batch "
                + "WHERE CARD_ID=" + CARD_ID + " AND SOURCE_TYPE=4", Integer.class), "过期批次置5");
        assertEquals(500L, jdbc.queryForObject("SELECT IFNULL(SUM(REMAIN_AMOUNT_FEN),0) "
                + "FROM ws_card_entitlement_batch WHERE CARD_ID=" + CARD_ID + " AND DATA_STATUS=0",
                Long.class), "消费后账本等式保持");
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM ws_wallet_flow WHERE FLOW_TYPE=6",
                Long.class), "成功单同事务留 EXPIRE_CLEAR 痕，恰一条");
    }

    @Test
    void deviceOfflineAfterPrecheckRejectsWithZeroSideEffects() {
        jdbc.update("UPDATE ws_device SET ONLINE_STATUS=2 WHERE ID=?", DEVICE_ID);

        assertThrows(JbkException.class, () -> tx.createWaterOrder(order(2, 5_000L, 100L), session(), NOW));

        assertZeroSideEffects();
    }

    @Test
    void deviceUnderMaintenanceAfterPrecheckRejectsWithZeroSideEffects() {
        jdbc.update("UPDATE ws_device SET RUN_STATUS=4 WHERE ID=?", DEVICE_ID);

        assertThrows(JbkException.class, () -> tx.createWaterOrder(order(2, 5_000L, 100L), session(), NOW));

        assertZeroSideEffects();
    }

    @Test
    void blockingFaultAfterPrecheckRejectsWithZeroSideEffects() {
        seedFault("E001", ApiEnum.Flag.YES.value());
        jdbc.update("UPDATE ws_device SET RUN_STATUS=3, LAST_FAULT_CODE='E001' WHERE ID=?", DEVICE_ID);

        assertThrows(JbkException.class, () -> tx.createWaterOrder(order(2, 5_000L, 100L), session(), NOW));

        assertZeroSideEffects();
    }

    @Test
    void unregisteredFaultCodeFailsClosedInsideTransaction() {
        // 字典没有这条码：没有「不阻断」的依据，一律拒绝（厂商新码在字典跟上前绝不放行）
        jdbc.update("UPDATE ws_device SET RUN_STATUS=3, LAST_FAULT_CODE='E999' WHERE ID=?", DEVICE_ID);

        assertThrows(JbkException.class, () -> tx.createWaterOrder(order(2, 5_000L, 100L), session(), NOW));

        assertZeroSideEffects();
    }

    @Test
    void registeredNonBlockingFaultStillAllowsDeduction() {
        // 反向对照：已登记且不阻断的故障不得被这道闸误杀，否则设备一有提示码就全站停售
        seedFault("E100", ApiEnum.Flag.NO.value());
        jdbc.update("UPDATE ws_device SET RUN_STATUS=3, LAST_FAULT_CODE='E100' WHERE ID=?", DEVICE_ID);

        tx.createWaterOrder(order(2, 5_000L, 100L), session(), NOW);

        assertEquals(BALANCE_FEN - 100L, cardValue("BALANCE_AMOUNT"));
        assertEquals(1, count("ws_order"));
    }

    // ===== 0b：快照 vs 当前读（R1-P1-1）——事务已建立一致性快照后，档案被别的事务改掉 =====
    //
    // 这四条是上一组用例证明不了的：那里的档案在事务开始前就已经是坏状态，普通快照读也能看见。
    // 真正的漏洞形态是「本事务先读了一眼（快照定格），之后别的事务改了档案」——此时普通读永远
    // 看不到新值，只有当前读（FOR UPDATE / LOCK IN SHARE MODE）能读到。用例用外层事务显式
    // 建立快照，再用独立连接改库并提交，最后在同一事务内下单：读到旧值即放行，就是漏洞。

    /** 在事务外用独立连接改库并提交——模拟并发的另一个事务。 */
    private void commitFromAnotherConnection(String sql, Object... args) throws Exception {
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    /**
     * 在一个外层事务里：① 先普通读钉住一致性快照；② 让另一连接改档案并提交；③ 同事务内下单。
     * createWaterOrder 是 REQUIRED，会加入外层事务，因此它面对的正是「快照已过期」的现场。
     */
    private void inTxAfterSnapshot(String snapshotSql, Runnable mutateAndAssert) {
        new org.springframework.transaction.support.TransactionTemplate(txManager).execute(status -> {
            jdbc.queryForList(snapshotSql);
            mutateAndAssert.run();
            status.setRollbackOnly();
            return null;
        });
    }

    @Test
    void deviceMigratedToAnotherStationAfterSnapshotIsRejected() {
        jdbc.update("INSERT INTO ws_station(ID,STATION_NAME,STATION_CODE,STATION_STATUS) VALUES(?,?,?,1)",
                99L, "迁入站", "ST-99");

        inTxAfterSnapshot("SELECT * FROM ws_device WHERE ID=" + DEVICE_ID, () -> {
            try {
                commitFromAnotherConnection("UPDATE ws_device SET STATION_ID=? WHERE ID=?", 99L, DEVICE_ID);
            }
            catch (Exception e) {
                throw new IllegalStateException(e);
            }
            assertThrows(JbkException.class,
                    () -> tx.createWaterOrder(order(2, 5_000L, 100L), session(), NOW));
        });

        assertZeroSideEffects();
    }

    @Test
    void outletReboundToAnotherDeviceAfterSnapshotIsRejected() {
        inTxAfterSnapshot("SELECT * FROM ws_device_outlet WHERE ID=" + OUTLET_ID, () -> {
            try {
                commitFromAnotherConnection("UPDATE ws_device_outlet SET DEVICE_ID=? WHERE ID=?",
                        DEVICE_ID + 1, OUTLET_ID);
            }
            catch (Exception e) {
                throw new IllegalStateException(e);
            }
            assertThrows(JbkException.class,
                    () -> tx.createWaterOrder(order(2, 5_000L, 100L), session(), NOW));
        });

        assertZeroSideEffects();
    }

    @Test
    void faultDictFlippedToBlockingAfterSnapshotIsRejected() {
        seedFault("E100", ApiEnum.Flag.NO.value());
        jdbc.update("UPDATE ws_device SET RUN_STATUS=3, LAST_FAULT_CODE='E100' WHERE ID=?", DEVICE_ID);

        inTxAfterSnapshot("SELECT * FROM ws_fault_dict WHERE FAULT_CODE='E100'", () -> {
            try {
                commitFromAnotherConnection(
                        "UPDATE ws_fault_dict SET BLOCK_ORDER_FLAG=? WHERE FAULT_CODE=?",
                        ApiEnum.Flag.YES.value(), "E100");
            }
            catch (Exception e) {
                throw new IllegalStateException(e);
            }
            assertThrows(JbkException.class,
                    () -> tx.createWaterOrder(order(2, 5_000L, 100L), session(), NOW));
        });

        assertZeroSideEffects();
    }

    @Test
    void faultDictStillNonBlockingAfterSnapshotKeepsOrderFlowing() {
        // 反向对照：字典没变（仍不阻断）时，当前读不得把正常单误杀
        seedFault("E100", ApiEnum.Flag.NO.value());
        jdbc.update("UPDATE ws_device SET RUN_STATUS=3, LAST_FAULT_CODE='E100' WHERE ID=?", DEVICE_ID);

        new org.springframework.transaction.support.TransactionTemplate(txManager).execute(status -> {
            jdbc.queryForList("SELECT * FROM ws_fault_dict WHERE FAULT_CODE='E100'");
            tx.createWaterOrder(order(2, 5_000L, 100L), session(), NOW);
            assertEquals(1, count("ws_order"));
            status.setRollbackOnly();
            return null;
        });
    }

    // ===== 0c：冻结快照与当前档案一致（R2-P1-1）=====
    //
    // 快照冻结的是「用户当时认可的交易身份」。共键全对得上、设备也可用，但同一个出水口把水种
    // 从 8 改成 9——用户买的已经不是他确认的那种水了。不核这一条，改水种就是无痕换货。

    @Test
    void waterTypeChangedAfterPrecheckIsRejectedWithZeroSideEffects() {
        jdbc.update("UPDATE ws_device_outlet SET WATER_TYPE_ID=? WHERE ID=?", 9L, OUTLET_ID);

        JbkException denied = assertThrows(JbkException.class,
                () -> tx.createWaterOrder(order(2, 5_000L, 100L), session(), NOW));

        assertTrue(denied.getMsg().contains("水种"), "拒因必须点明水种变更，实际=" + denied.getMsg());
        assertZeroSideEffects();
    }

    @Test
    void waterTypeChangedAfterSnapshotIsRejected() {
        // 与 0b 同构：本事务先建立一致性快照，再由独立连接改水种并提交
        inTxAfterSnapshot("SELECT * FROM ws_device_outlet WHERE ID=" + OUTLET_ID, () -> {
            try {
                commitFromAnotherConnection("UPDATE ws_device_outlet SET WATER_TYPE_ID=? WHERE ID=?",
                        9L, OUTLET_ID);
            }
            catch (Exception e) {
                throw new IllegalStateException(e);
            }
            assertThrows(JbkException.class,
                    () -> tx.createWaterOrder(order(2, 5_000L, 100L), session(), NOW));
        });

        assertZeroSideEffects();
    }

    @Test
    void missingSnapshotIsRejectedWithZeroSideEffects() {
        WsOrder bad = order(2, 5_000L, 100L).setPackageSnap(null);

        assertThrows(JbkException.class, () -> tx.createWaterOrder(bad, session(), NOW));

        assertZeroSideEffects();
    }

    @Test
    void malformedSnapshotIsRejectedWithZeroSideEffects() {
        WsOrder bad = order(2, 5_000L, 100L).setPackageSnap("not-a-json");

        assertThrows(JbkException.class, () -> tx.createWaterOrder(bad, session(), NOW));

        assertZeroSideEffects();
    }

    @Test
    void nonIntegralWaterTypeIdInSnapshotIsRejected() {
        // 小数、带前导零的字符串、科学计数一律不是规范整数——宽松解析等于给篡改留编码空间
        for (String raw : new String[]{"8.5", "\"08\"", "\"8e0\"", "-8"}) {
            WsOrder bad = order(2, 5_000L, 100L).setPackageSnap(
                    "{\"requestId\":\"scan-x\",\"unitPriceFenPerLiter\":20,\"planMl\":5000,"
                            + "\"payWay\":2,\"waterTypeId\":" + raw + "}");
            assertThrows(JbkException.class, () -> tx.createWaterOrder(bad, session(), NOW),
                    "waterTypeId=" + raw + " 必须被拒");
            assertZeroSideEffects();
        }
    }

    @Test
    void orderAmountInconsistentWithSnapshotIsRejected() {
        // 编排层装配漂移：金额少算一分也不许落库
        WsOrder bad = order(2, 5_000L, 99L);

        assertThrows(JbkException.class, () -> tx.createWaterOrder(bad, session(), NOW));

        assertZeroSideEffects();
    }

    @Test
    void unchangedWaterTypeKeepsNormalPathWorking() {
        tx.createWaterOrder(order(2, 5_000L, 100L), session(), NOW);

        assertEquals(BALANCE_FEN - 100L, cardValue("BALANCE_AMOUNT"));
        assertEquals(1, count("ws_order"));
    }

    // ===== 0e：扫码报价三方一致（S2）=====
    //
    // 事务终判要同时看三份：Redis 会话冻结报价、订单 PACKAGE_SNAP、锁内当前出水口。
    // 少核任何一边都留着缝：只核快照与档案，装配层就能拿别的会话装单；
    // 只核会话与快照，后台调价仍会按旧价扣款而用户看到的是另一个数。

    @Test
    void priceChangedAfterScanIsRejectedWithZeroSideEffects() {
        jdbc.update("UPDATE ws_device_outlet SET OUTLET_PRICE='30' WHERE ID=?", OUTLET_ID);

        JbkException denied = assertThrows(JbkException.class,
                () -> tx.createWaterOrder(order(2, 5_000L, 100L), session(), NOW));

        assertEquals(MiniRejectCode.SCAN_QUOTE_CHANGED, denied.getCode(), "必须是稳定的报价变化码");
        assertTrue(denied.getMsg().contains("价格"), "实际=" + denied.getMsg());
        assertZeroSideEffects();
    }

    @Test
    void waterTypeChangedAfterScanIsRejectedWithQuoteChangedCode() {
        jdbc.update("UPDATE ws_device_outlet SET WATER_TYPE_ID=9 WHERE ID=?", OUTLET_ID);

        JbkException denied = assertThrows(JbkException.class,
                () -> tx.createWaterOrder(order(2, 5_000L, 100L), session(), NOW));

        assertEquals(MiniRejectCode.SCAN_QUOTE_CHANGED, denied.getCode());
        assertZeroSideEffects();
    }

    @Test
    void snapshotQuoteDifferentFromSessionIsRejected() {
        // 装配层拿了别的会话/别的价去装单：快照与会话对不上，同样拒
        ScanSessionInfo drifted = session().setUnitPriceFenPerLiter(30);

        JbkException denied = assertThrows(JbkException.class,
                () -> tx.createWaterOrder(order(2, 5_000L, 100L), drifted, NOW));

        assertEquals(MiniRejectCode.SCAN_QUOTE_CHANGED, denied.getCode());
        assertZeroSideEffects();
    }

    @Test
    void snapshotFromAnotherScanSessionIsRejected() {
        ScanSessionInfo other = session().setScanSessionId("scan-other");

        JbkException denied = assertThrows(JbkException.class,
                () -> tx.createWaterOrder(order(2, 5_000L, 100L), other, NOW));

        assertEquals(MiniRejectCode.SCAN_QUOTE_CHANGED, denied.getCode());
        assertZeroSideEffects();
    }

    @Test
    void unchangedQuoteKeepsAmountSnapshotAndFlowConsistent() {
        WsOrder saved = tx.createWaterOrder(order(2, 5_000L, 100L), session(), NOW);

        assertEquals(100L, saved.getOrderAmount());
        assertEquals(BALANCE_FEN - 100L, cardValue("BALANCE_AMOUNT"));
        assertEquals(-100L, jdbc.queryForObject("SELECT AMOUNT_CHANGE FROM ws_wallet_flow", Long.class));
        String snap = jdbc.queryForObject("SELECT PACKAGE_SNAP FROM ws_order WHERE ID=?",
                String.class, saved.getId());
        assertTrue(snap.contains("\"unitPriceFenPerLiter\":20"), "快照必须留住冻结单价，实际=" + snap);
        assertTrue(snap.contains("\"waterTypeId\":8"), "快照必须留住冻结水种，实际=" + snap);
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

        // 包D-4 正向断言：卡扣了，权益批次必须同事务一起扣，且分摊键为 DISPENSE:<订单号>。
        // 删掉 createWaterOrder 里的 allocateOnConsume 那一段，本段立刻红——
        // 只看卡与流水的话，「卡扣了、批次没扣」是查不出来的，
        // 而它的后果是这笔消费在退款折算里等于没发生过（批次剩余虚高 ⇒ 多退）。
        assertEquals(1, count("ws_entitlement_allocation"));
        assertEquals("DISPENSE:" + saved.getOrderNo(),
                jdbc.queryForObject("SELECT BIZ_KEY FROM ws_entitlement_allocation", String.class));
        assertEquals(5_000L,
                jdbc.queryForObject("SELECT ALLOC_WATER_ML FROM ws_entitlement_allocation", Long.class));
        assertEquals(0L,
                jdbc.queryForObject("SELECT ALLOC_AMOUNT_FEN FROM ws_entitlement_allocation", Long.class),
                "水量支付不得在金额维度上分摊");
        assertEquals(cardValue("BALANCE_ML"), EntitlementFixture.sumRemainMl(jdbc, CARD_ID),
                "不变式：逐卡批次剩余合计恒等于卡聚合值");
        assertEquals(cardValue("BALANCE_AMOUNT"), EntitlementFixture.sumRemainFen(jdbc, CARD_ID));
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
                return tx.claimCommandSlot(saved.getId(), commandId, STATION_ID, DEVICE_ID, OUTLET_ID);
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
        assertEquals(0, tx.claimCommandSlot(saved.getId(), 9_999L, STATION_ID, DEVICE_ID, OUTLET_ID));
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

        // 包D-4：余额支付只在金额维度分摊，水量维度必须为 0（两维绝不互相折算）
        assertEquals(100L,
                jdbc.queryForObject("SELECT ALLOC_AMOUNT_FEN FROM ws_entitlement_allocation", Long.class));
        assertEquals(0L,
                jdbc.queryForObject("SELECT ALLOC_WATER_ML FROM ws_entitlement_allocation", Long.class));
        assertEquals(cardValue("BALANCE_AMOUNT"), EntitlementFixture.sumRemainFen(jdbc, CARD_ID));
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
