package com.jbk.serve.service.user.impl;

import com.jbk.serve.mapper.device.WsDeviceMapper;
import com.jbk.serve.mapper.device.WsDeviceOutletMapper;
import com.jbk.serve.mapper.station.WsStationMapper;
import com.jbk.serve.mapper.user.WsCardMapper;
import com.jbk.serve.mapper.user.WsCardMemberMapper;
import com.jbk.serve.service.delivery.impl.DeliveryDbSchema;
import com.jbk.serve.service.user.IWsCardService;
import com.jbk.serve.service.user.IWsUserService;
import com.jbk.tool.data.user.bo.WsCardScopeBo;
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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S4 水卡授权范围维护真库回归。
 *
 * <p>JSON 恒由服务端构造（结构化维度入参），WaterCardScope 唯一校验口；
 * 旧值 CAS 防并发覆盖；修改不追溯历史订单快照。扫码/扣款侧的范围判定语义
 * 由取水链既有测试（CARD-SCOPE 系列）覆盖，本类钉维护面。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringJUnitConfig(CardScopeUpdateDbTest.Ctx.class)
class CardScopeUpdateDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static final long CARD = 31L;

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
            com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean factory =
                    new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();
            factory.setDataSource(ds);
            com.baomidou.mybatisplus.core.MybatisConfiguration cfg =
                    new com.baomidou.mybatisplus.core.MybatisConfiguration();
            cfg.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(cfg);
            com.baomidou.mybatisplus.core.config.GlobalConfig globalConfig =
                    new com.baomidou.mybatisplus.core.config.GlobalConfig();
            globalConfig.setMetaObjectHandler(new com.jbk.tool.config.system.mybatis.MpMetaObjectHandler());
            factory.setGlobalConfig(globalConfig);
            return new SqlSessionTemplate(factory.getObject());
        }

        private static <M> MapperFactoryBean<M> mapper(Class<M> type, SqlSessionTemplate template) {
            MapperFactoryBean<M> bean = new MapperFactoryBean<>(type);
            bean.setSqlSessionTemplate(template);
            return bean;
        }

        @Bean
        MapperFactoryBean<WsCardMapper> wsCardMapper(SqlSessionTemplate t) {
            return mapper(WsCardMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsCardMemberMapper> wsCardMemberMapper(SqlSessionTemplate t) {
            return mapper(WsCardMemberMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsStationMapper> wsStationMapper(SqlSessionTemplate t) {
            return mapper(WsStationMapper.class, t);
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
        IWsUserService wsUserService() {
            return Mockito.mock(IWsUserService.class);
        }

        @Bean
        IWsCardService cardService() {
            return new WsCardServiceImpl();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    @Autowired
    private IWsCardService cardService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        DeliveryDbSchema.createAll(jdbc);
        DeliveryDbSchema.truncateAll(jdbc);
        jdbc.execute("CREATE TABLE IF NOT EXISTS ws_device_outlet ("
                + " ID BIGINT PRIMARY KEY AUTO_INCREMENT,"
                + " DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),"
                + " UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),"
                + " DEVICE_ID BIGINT NOT NULL, OUTLET_NO INT NOT NULL"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("TRUNCATE TABLE ws_device_outlet");
        jdbc.update("INSERT INTO ws_card (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME,"
                + " CARD_NO, CARD_TYPE, USER_ID, BALANCE_AMOUNT, BALANCE_ML, CARD_STATUS, SCOPE_JSON)"
                + " VALUES (31, 0, 1, '20260801120000', 1, '20260801120000', 'KC31', 2, 9, 0, 0, 1,"
                + " '{\"scopeType\":\"all\"}')");
        jdbc.update("INSERT INTO ws_station (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME,"
                + " STATION_NAME, STATION_STATUS, OWNER_USER_ID)"
                + " VALUES (7, 0, 1, '20260801120000', 1, '20260801120000', '幸福水站', 1, NULL)");
        jdbc.update("INSERT INTO ws_device (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME,"
                + " DEVICE_NO, STATION_ID) VALUES (5, 0, 1, '20260801120000', 1, '20260801120000', 'DEV5', 7)");
        jdbc.update("INSERT INTO ws_device_outlet (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY,"
                + " UPDATE_TIME, DEVICE_ID, OUTLET_NO) VALUES (3, 0, 1, '20260801120000', 1, '20260801120000', 5, 1)");
    }

    private WsCardScopeBo bo(String type, List<Long> stations, List<Long> devices, List<Long> outlets,
                             String expected) {
        WsCardScopeBo bo = new WsCardScopeBo();
        bo.setCardId(CARD);
        bo.setScopeType(type);
        bo.setStationIds(stations);
        bo.setDeviceIds(devices);
        bo.setOutletIds(outlets);
        bo.setReason("运营调整服务范围");
        bo.setExpectedScopeJson(expected);
        return bo;
    }

    private String scopeOf() {
        return jdbc.queryForObject("SELECT SCOPE_JSON FROM ws_card WHERE ID=?", String.class, CARD);
    }

    /** 7.2-1：水站/设备/出水口三维度正常保存（服务端构造 JSON，通过唯一校验口）。 */
    @Test
    void savesEachDimensionThroughSingleNormalizer() {
        cardService.updateScope(bo("specified", List.of(7L), null, null, "{\"scopeType\":\"all\"}"));
        assertTrue(scopeOf().contains("\"stationIds\":[\"7\"]"), "站维度落库为字符串 ID 数组");

        cardService.updateScope(bo("specified", null, List.of(5L), List.of(3L), scopeOf()));
        String saved = scopeOf();
        assertTrue(saved.contains("\"deviceIds\":[\"5\"]") && saved.contains("\"outletIds\":[\"3\"]"),
                "设备与出水口维度共存保存");
        // 保存产物必须能再次通过唯一校验口（自洽性）
        com.jbk.serve.service.mini.card.WaterCardScope.normalize(saved, "水卡");

        cardService.updateScope(bo("all", null, null, null, saved));
        assertTrue(scopeOf().contains("\"scopeType\":\"all\""));
    }

    /** 7.2-2：空范围与非法范围拒绝，零写入。 */
    @Test
    void emptyAndIllegalScopesFailClosed() {
        String before = scopeOf();
        assertThrows(JbkException.class,
                () -> cardService.updateScope(bo("specified", null, null, null, before)),
                "specified 无任何维度=空范围拒绝");
        assertThrows(JbkException.class,
                () -> cardService.updateScope(bo("everywhere", null, null, null, before)),
                "未知范围类型拒绝");
        assertEquals(before, scopeOf(), "拒绝路径零写入");
    }

    /** 7.2-3：越权/不存在的卡与幽灵档案 ID 拒绝。 */
    @Test
    void missingCardAndGhostEntitiesRejected() {
        WsCardScopeBo ghost = bo("specified", List.of(999L), null, null, "{\"scopeType\":\"all\"}");
        assertThrows(JbkException.class, () -> cardService.updateScope(ghost), "幽灵水站拒绝");

        jdbc.update("UPDATE ws_card SET DATA_STATUS=1 WHERE ID=?", CARD);
        assertThrows(JbkException.class,
                () -> cardService.updateScope(bo("all", null, null, null, null)),
                "已删除卡不可维护");
    }

    /** 7.2-4：并发修改恰好一个成功（旧值 CAS）。 */
    @Test
    void concurrentUpdatesExactlyOneWins() throws Exception {
        String expected = scopeOf();
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger wins = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.invokeAll(List.of(
                    () -> race(barrier, wins, bo("specified", List.of(7L), null, null, expected)),
                    () -> race(barrier, wins, bo("specified", null, List.of(5L), null, expected))),
                    30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, wins.get(), "同一旧值锚下恰一个修改成功");
    }

    private boolean race(CyclicBarrier barrier, AtomicInteger wins, WsCardScopeBo bo) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        try {
            cardService.updateScope(bo);
            wins.incrementAndGet();
            return true;
        }
        catch (JbkException legalLoss) {
            return false;
        }
    }

    /** 7.2-5：修改只影响后续校验，不追溯历史订单快照。 */
    @Test
    void historicalOrderSnapshotsUntouched() {
        jdbc.update("INSERT INTO ws_order (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME,"
                + " ORDER_NO, ORDER_TYPE, USER_ID, CARD_ID, ORDER_AMOUNT, PAY_WAY, ORDER_STATUS, PACKAGE_SNAP)"
                + " VALUES (901, 0, 9, '20260801120000', 9, '20260801120000', 'WO-SNAP', 1, 9, 31, 100, 2, 4,"
                + " '{\"scope\":{\"scopeType\":\"all\"}}')");
        cardService.updateScope(bo("specified", List.of(7L), null, null, "{\"scopeType\":\"all\"}"));
        assertEquals("{\"scope\":{\"scopeType\":\"all\"}}", jdbc.queryForObject(
                "SELECT PACKAGE_SNAP FROM ws_order WHERE ID=901", String.class),
                "历史订单快照原文不变");
    }
}
