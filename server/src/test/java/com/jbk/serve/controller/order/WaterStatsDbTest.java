package com.jbk.serve.controller.order;

import com.jbk.serve.mapper.trade.WaterStatsMapper;
import com.jbk.serve.service.delivery.impl.DeliveryDbSchema;
import com.jbk.tool.exception.JbkException;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S5 按水种业务用量统计真库回归（任务书 8.2）。
 *
 * <p>统计只聚合现有订单与任务事实：完成量只认已完成(4)；超量异常(6)单列不入完成量；
 * 取消(5)不计；补送（配送域唯一零元子单）单列不与销售单混算；折算水量走
 * DeliveryPricing 单源；时间为服务端闭区间；水站过滤在 SQL 参数层（Service 强制）。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringJUnitConfig(WaterStatsDbTest.Ctx.class)
class WaterStatsDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    @Configuration
    static class Ctx {
        @Bean
        DataSource dataSource() {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl(MYSQL.getJdbcUrl());
            ds.setUsername(MYSQL.getUsername());
            ds.setPassword(MYSQL.getPassword());
            ds.setMaximumPoolSize(4);
            return ds;
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
            return new SqlSessionTemplate(factory.getObject());
        }

        @Bean
        MapperFactoryBean<WaterStatsMapper> waterStatsMapper(SqlSessionTemplate t) {
            MapperFactoryBean<WaterStatsMapper> bean = new MapperFactoryBean<>(WaterStatsMapper.class);
            bean.setSqlSessionTemplate(t);
            return bean;
        }

        @Bean
        WaterStatsController waterStatsController() {
            return new WaterStatsController();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    @Autowired
    private WaterStatsController controller;
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
                + " DEVICE_ID BIGINT NOT NULL, OUTLET_NO INT NOT NULL, WATER_TYPE VARCHAR(20) NOT NULL"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("TRUNCATE TABLE ws_device_outlet");
        jdbc.update("INSERT INTO ws_device_outlet (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY,"
                + " UPDATE_TIME, DEVICE_ID, OUTLET_NO, WATER_TYPE)"
                + " VALUES (1, 0, 1, '20260801000000', 1, '20260801000000', 5, 1, '纯净水'),"
                + " (2, 0, 1, '20260801000000', 1, '20260801000000', 5, 2, '矿泉水')");
    }

    private void seedWaterOrder(long id, long outletId, long stationId, int status,
                                Long planMl, Long actualMl, String createTime) {
        jdbc.update("INSERT INTO ws_order (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME,"
                + " ORDER_NO, ORDER_TYPE, USER_ID, STATION_ID, OUTLET_ID, PLAN_ML, ACTUAL_ML,"
                + " ORDER_AMOUNT, PAY_WAY, ORDER_STATUS)"
                + " VALUES (?, 0, 9, ?, 9, ?, ?, 1, 9, ?, ?, ?, ?, 100, 2, ?)",
                id, createTime, createTime, "WO-ST-" + id, stationId, outletId, planMl, actualMl, status);
    }

    private void seedDelivery(long orderId, long stationId, long amountFen, String spec,
                              int count, Integer actualCount, String createTime) {
        jdbc.update("INSERT INTO ws_order (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME,"
                + " ORDER_NO, ORDER_TYPE, USER_ID, STATION_ID, ORDER_AMOUNT, PAY_WAY, ORDER_STATUS)"
                + " VALUES (?, 0, 9, ?, 9, ?, ?, 3, 9, ?, ?, 2, 4)",
                orderId, createTime, createTime, "DO-ST-" + orderId, stationId, amountFen);
        jdbc.update("INSERT INTO ws_delivery_task (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY,"
                + " UPDATE_TIME, TASK_NO, ORDER_ID, USER_ID, STATION_ID, WATER_TYPE_ID, WATER_TYPE,"
                + " CONTAINER_SPEC, DELIVERY_COUNT, PLAN_RETURN_COUNT, ACTUAL_DELIVERY_COUNT,"
                + " WATER_AMOUNT, DELIVERY_FEE, TASK_STATUS, VERSION)"
                + " VALUES (0, 9, ?, 9, ?, ?, ?, 9, ?, 1, '纯净水', ?, ?, 0, ?, 1000, 200, 5, 1)",
                createTime, createTime, "DT-ST-" + orderId, orderId, stationId, spec, count, actualCount);
    }

    private Map<String, WaterStatsController.WaterStatsRow> summary(String start, String end, Long stationId) {
        WaterStatsController.StatsQuery query = new WaterStatsController.StatsQuery();
        query.setStartTime(start);
        query.setEndTime(end);
        query.setStationId(stationId);
        List<WaterStatsController.WaterStatsRow> rows = controller.summary(query).getData();
        return rows.stream().collect(Collectors.toMap(
                WaterStatsController.WaterStatsRow::getWaterTypeName, Function.identity()));
    }

    /** 8.2-1/2/3：水种分组正确；计划与实际分开；不足退差不改实际出水量。 */
    @Test
    void groupsByWaterTypeWithPlanActualAndShortfallSeparated() {
        seedWaterOrder(101, 1, 7, 4, 5000L, 5000L, "20260805100000");
        seedWaterOrder(102, 1, 7, 4, 5000L, 4200L, "20260805110000");
        seedWaterOrder(103, 2, 7, 4, 3000L, 3000L, "20260805120000");

        Map<String, WaterStatsController.WaterStatsRow> rows =
                summary("20260805000000", "20260805235959", null);

        WaterStatsController.WaterStatsRow pure = rows.get("纯净水");
        assertEquals(10_000L, pure.getPlanMl(), "计划量=两单计划合计");
        assertEquals(9_200L, pure.getActualMl(), "实际量按设备回传原值累计，不被退差改写");
        assertEquals(800L, pure.getShortfallMl(), "退差量=计划−实际正差");
        assertEquals(3_000L, rows.get("矿泉水").getActualMl(), "水种分组互不串量");
    }

    /** 8.2-4/5：超量异常不算正常完成；取消单不计入完成量。 */
    @Test
    void abnormalAndCancelledOrdersExcludedFromCompletion() {
        seedWaterOrder(111, 1, 7, 6, 5000L, 6100L, "20260805100000");
        seedWaterOrder(112, 1, 7, 5, 5000L, null, "20260805110000");
        seedWaterOrder(113, 1, 7, 4, 2000L, 2000L, "20260805120000");

        WaterStatsController.WaterStatsRow pure =
                summary("20260805000000", "20260805235959", null).get("纯净水");

        assertEquals(2_000L, pure.getPlanMl(), "异常(6)与取消(5)不入完成计划量");
        assertEquals(2_000L, pure.getActualMl(), "超量单的出水量不冒充正常完成量");
        assertEquals(1L, pure.getAbnormalCount(), "异常单单列计数");
    }

    /** 8.2-6/7：补送单单列不与销售混算；桶数与折算水量分别可核（DeliveryPricing 单源）。 */
    @Test
    void resendSeparatedAndBucketConversionAuditable() {
        seedDelivery(201, 7, 3000, "10L桶", 2, 2, "20260805100000");
        seedDelivery(202, 7, 0, "10L桶", 1, 1, "20260805110000");

        WaterStatsController.WaterStatsRow pure =
                summary("20260805000000", "20260805235959", null).get("纯净水");

        assertEquals(2L, pure.getDeliveryBuckets(), "销售桶数");
        assertEquals(20_000L, pure.getDeliveryMl(), "折算水量=2×10L=20000ml");
        assertEquals(1L, pure.getResendBuckets(), "补送（零元子单）单列");
        assertEquals(10_000L, pure.getResendMl());
    }

    /** 8.2-8：时间为服务端闭区间（边界时间戳双端含入）。 */
    @Test
    void timeWindowIsClosedInterval() {
        seedWaterOrder(121, 1, 7, 4, 1000L, 1000L, "20260805000000");
        seedWaterOrder(122, 1, 7, 4, 1000L, 1000L, "20260805235959");
        seedWaterOrder(123, 1, 7, 4, 1000L, 1000L, "20260806000000");

        WaterStatsController.WaterStatsRow pure =
                summary("20260805000000", "20260805235959", null).get("纯净水");
        assertEquals(2_000L, pure.getPlanMl(), "闭区间双端含入、区间外排除");

        assertThrows(JbkException.class, () -> {
            WaterStatsController.StatsQuery bad = new WaterStatsController.StatsQuery();
            bad.setStartTime("2026-08-05");
            bad.setEndTime("20260805235959");
            controller.summary(bad);
        }, "非业务时间格式拒绝");
    }

    /** 8.2-9：水站过滤在服务端参数层强制。 */
    @Test
    void stationFilterEnforcedServerSide() {
        seedWaterOrder(131, 1, 7, 4, 1000L, 1000L, "20260805100000");
        seedWaterOrder(132, 1, 8, 4, 9000L, 9000L, "20260805110000");
        seedDelivery(203, 8, 3000, "5L桶", 3, 3, "20260805120000");

        Map<String, WaterStatsController.WaterStatsRow> station7 =
                summary("20260805000000", "20260805235959", 7L);
        assertEquals(1_000L, station7.get("纯净水").getPlanMl(), "只统计目标水站取水量");
        assertEquals(0L, station7.get("纯净水").getDeliveryBuckets(), "他站配送量被过滤");

        Map<String, WaterStatsController.WaterStatsRow> all =
                summary("20260805000000", "20260805235959", null);
        assertEquals(10_000L, all.get("纯净水").getPlanMl());
        assertTrue(all.get("纯净水").getDeliveryMl() == 15_000L, "5L×3=15000ml");
    }
}
