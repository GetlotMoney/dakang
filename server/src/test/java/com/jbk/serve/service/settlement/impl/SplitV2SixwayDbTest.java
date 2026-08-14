package com.jbk.serve.service.settlement.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.settlement.WsIncomeAccountMapper;
import com.jbk.serve.mapper.settlement.WsIncomeFlowMapper;
import com.jbk.serve.mapper.settlement.WsOwnerAttributionMapper;
import com.jbk.serve.mapper.settlement.WsOwnerReferrerMapper;
import com.jbk.serve.mapper.settlement.WsSplitComponentMapper;
import com.jbk.serve.mapper.settlement.WsSplitConfigMapper;
import com.jbk.serve.mapper.settlement.WsSplitLineLockMapper;
import com.jbk.serve.mapper.settlement.WsSplitPlanItemMapper;
import com.jbk.serve.mapper.settlement.WsSplitPlanMapper;
import com.jbk.serve.mapper.settlement.WsSplitRecordMapper;
import com.jbk.serve.service.delivery.impl.DeliveryDbSchema;
import com.jbk.serve.service.settlement.IIncomeService;
import com.jbk.serve.service.settlement.IOwnerAttributionService;
import com.jbk.serve.service.settlement.ISplitPlanService;
import com.jbk.serve.service.settlement.ISplitService;
import com.jbk.tool.consts.settlement.SettlementEnum;
import com.jbk.tool.data.settlement.bo.OwnerAttributionBo;
import com.jbk.tool.data.settlement.bo.SplitPlanBo;
import com.jbk.tool.data.settlement.po.WsSplitRecord;
import com.jbk.tool.exception.JbkException;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分润 V2 六方接线的真实 MySQL 集成测试（D-428/E2E-08 S2）。
 *
 * <p>建表直接执行真实迁移文件（抄本会漂移）：split-v2-s1（计划三表）、split-line-lock、
 * split-sixway（归属两表+菜单，双轮执行证幂等）。钉住的口径：无计划回落 V1；六方按
 * 计划拆分且 Σ行=基数；快照形态与冲减链兼容（取水单数字、分线单 W/D、平台恒
 * REMAINDER）；重放幂等；版本锚=订单创建时点（发新计划不追溯旧单）；归属校验 fail-closed。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = SplitV2SixwayDbTest.Ctx.class)
@DisplayName("分润V2六方接线真库判定")
class SplitV2SixwayDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_sixway_it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static final long OWNER = 9L;
    private static final long REFERRER = 22L;
    private static final long PROVINCE = 31L;
    private static final long CITY = 32L;
    private static final long COUNTY = 33L;
    private static final long COURIER = 44L;

    @Configuration
    @EnableTransactionManagement
    static class Ctx {

        /** 绑号闸放行版：最小 schema 无 ws_user 表；闸行为由 MiniPhoneGate 系列测试专门覆盖。 */
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
            return new SqlSessionTemplate(factory.getObject());
        }

        private static <M> MapperFactoryBean<M> mapper(Class<M> type, SqlSessionTemplate template) {
            MapperFactoryBean<M> bean = new MapperFactoryBean<>(type);
            bean.setSqlSessionTemplate(template);
            return bean;
        }

        @Bean
        MapperFactoryBean<WsSplitRecordMapper> wsSplitRecordMapper(SqlSessionTemplate t) {
            return mapper(WsSplitRecordMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsSplitConfigMapper> wsSplitConfigMapper(SqlSessionTemplate t) {
            return mapper(WsSplitConfigMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsSplitPlanMapper> wsSplitPlanMapper(SqlSessionTemplate t) {
            return mapper(WsSplitPlanMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsSplitPlanItemMapper> wsSplitPlanItemMapper(SqlSessionTemplate t) {
            return mapper(WsSplitPlanItemMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsSplitLineLockMapper> wsSplitLineLockMapper(SqlSessionTemplate t) {
            return mapper(WsSplitLineLockMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsSplitComponentMapper> wsSplitComponentMapper(SqlSessionTemplate t) {
            return mapper(WsSplitComponentMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsOwnerReferrerMapper> wsOwnerReferrerMapper(SqlSessionTemplate t) {
            return mapper(WsOwnerReferrerMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsOwnerAttributionMapper> wsOwnerAttributionMapper(SqlSessionTemplate t) {
            return mapper(WsOwnerAttributionMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsIncomeAccountMapper> wsIncomeAccountMapper(SqlSessionTemplate t) {
            return mapper(WsIncomeAccountMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsIncomeFlowMapper> wsIncomeFlowMapper(SqlSessionTemplate t) {
            return mapper(WsIncomeFlowMapper.class, t);
        }

        // resolveWaterOwner 装配依赖（本类直接传 ownerUserId，不触发解析）
        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.device.WsDeviceMapper> wsDeviceMapper(SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.device.WsDeviceMapper.class, t);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.station.WsStationMapper> wsStationMapper(SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.station.WsStationMapper.class, t);
        }

        /** 用户存在性校验放行：用户域行为由各自模块看守，这里只测归属与分账。 */
        @Bean
        com.jbk.serve.mapper.user.WsUserMapper wsUserMapper() {
            com.jbk.serve.mapper.user.WsUserMapper mock =
                    Mockito.mock(com.jbk.serve.mapper.user.WsUserMapper.class);
            Mockito.when(mock.selectById(Mockito.any()))
                    .thenReturn(new com.jbk.tool.data.user.po.WsUser());
            return mock;
        }

        @Bean
        ISplitPlanService splitPlanService(WsSplitPlanMapper planMapper,
                                           WsSplitPlanItemMapper itemMapper,
                                           WsSplitLineLockMapper lockMapper) {
            return new SplitPlanServiceImpl(planMapper, itemMapper, lockMapper);
        }

        @Bean
        IOwnerAttributionService ownerAttributionService(WsOwnerReferrerMapper r,
                                                         WsOwnerAttributionMapper a,
                                                         com.jbk.serve.mapper.user.WsUserMapper u) {
            return new OwnerAttributionServiceImpl(r, a, u);
        }

        @Bean
        ISplitService splitService() {
            return new SplitServiceImpl();
        }

        @Bean
        IIncomeService incomeService() {
            return new IncomeServiceImpl();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    @Autowired
    private ISplitService splitService;
    @Autowired
    private ISplitPlanService planService;
    @Autowired
    private IOwnerAttributionService attributionService;
    @Autowired
    private JdbcTemplate jdbc;

    private static Object unwrap(Object proxy) {
        try {
            return org.springframework.test.util.AopTestUtils.getTargetObject(proxy);
        }
        catch (Exception e) {
            return proxy;
        }
    }

    @BeforeEach
    void cleanSlate() throws IOException {
        DeliveryDbSchema.createAll(jdbc);
        DeliveryDbSchema.truncateAll(jdbc);
        // 六方迁移的菜单段依赖 rbac 两表与父菜单 1004——建最小承载并种父行
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS api_rbac_menu (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  MENU_NAME VARCHAR(50) NOT NULL, MENU_TYPE TINYINT NOT NULL,
                  MENU_ICON VARCHAR(100) NULL, MENU_PARENT_ID BIGINT NULL, MENU_SORT INT NULL,
                  MENU_PATH VARCHAR(200) NULL, MENU_COMPONENT VARCHAR(255) NULL,
                  MENU_FRAME_FLAG TINYINT NULL, MENU_API_PERMS VARCHAR(200) NULL,
                  MENU_WEB_PERMS VARCHAR(200) NULL, MENU_VISIBLE_FLAG TINYINT NULL,
                  MENU_DISABLED_FLAG TINYINT NULL, DATA_STATUS TINYINT NOT NULL DEFAULT 0,
                  CREATE_BY BIGINT NOT NULL, CREATE_TIME VARCHAR(14) NOT NULL,
                  UPDATE_BY BIGINT NOT NULL, UPDATE_TIME VARCHAR(14) NOT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS api_rbac_role_menu (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT NOT NULL DEFAULT 0,
                  CREATE_BY BIGINT NOT NULL, CREATE_TIME VARCHAR(14) NOT NULL,
                  UPDATE_BY BIGINT NOT NULL, UPDATE_TIME VARCHAR(14) NOT NULL,
                  ROLE_ID BIGINT NOT NULL, MENU_ID BIGINT NOT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.update("DELETE FROM api_rbac_menu");
        jdbc.update("DELETE FROM api_rbac_role_menu");
        jdbc.update("INSERT INTO api_rbac_menu (ID,MENU_NAME,MENU_TYPE,MENU_PARENT_ID,DATA_STATUS,"
                + "CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME) VALUES "
                + "(1004,'订单中心',1,0,0,1,'20260714120000',1,'20260714120000')");
        // 真实迁移文件建 V2 计划三表 / 商品线锁锚 / 归属两表；六方迁移跑两轮证幂等
        applyMigration("2026-08-06-split-v2-s1.sql");
        applyMigration("2026-08-07-split-line-lock.sql");
        applyMigration("2026-08-14-split-sixway.sql");
        applyMigration("2026-08-14-split-sixway.sql");
        for (String table : new String[] {"ws_split_plan", "ws_split_plan_item", "ws_split_component",
                "ws_owner_referrer", "ws_owner_attribution"}) {
            jdbc.update("TRUNCATE TABLE " + table);
        }
        Object impl = unwrap(splitService);
        ReflectionTestUtils.setField(impl, "v2Enabled", true);
        ReflectionTestUtils.setField(impl, "freezeDays", 0);
        // V1 回落基线：售水机主 70%
        jdbc.update("INSERT INTO ws_split_config (DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,"
                + "UPDATE_TIME,PRODUCT_LINE,RECEIVER_TYPE,SPLIT_RATE,EFFECT_TIME) "
                + "VALUES (0,1,'20260101000000',1,'20260101000000',1,1,7000,'20260101000000')");
    }

    private void applyMigration(String name) throws IOException {
        String sql = new String(Files.readAllBytes(
                repoRoot().resolve("deploy/mysql/migrations/" + name)), StandardCharsets.UTF_8);
        List<String> statements = new ArrayList<>();
        for (String stmt : sql.replaceAll("(?m)^\\s*--.*$", "").split(";")) {
            if (!stmt.isBlank()) {
                statements.add(stmt.trim());
            }
        }
        jdbc.execute((java.sql.Connection conn) -> {
            try (java.sql.Statement st = conn.createStatement()) {
                for (String stmt : statements) {
                    st.execute(stmt);
                }
            }
            return null;
        });
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("deploy/mysql/migrations"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("未找到仓库根");
        }
        return dir;
    }

    /**
     * 发布六方计划：水站50/推广5/省5市3区县2/配送员90（甲方 8.14 表格 + 8.6 会议三级口径）。
     * 生效时间取服务端当下（发布不允许过去时点）；订单创建时间一律用未来戳，
     * 保证「计划生效 ≤ 订单创建」的版本选取关系成立。
     */
    private Long publishSixway() {
        return planService.publish(new SplitPlanBo()
                .setWaterOwnerBp(5000).setWaterReferrerBp(500)
                .setRegionProvinceCumBp(500).setRegionCityCumBp(300).setRegionCountyCumBp(200)
                .setDeliveryCourierBp(9000), 1L);
    }

    private void seedAttribution() {
        attributionService.createReferrer(new OwnerAttributionBo()
                .setOwnerUserId(OWNER).setReferrerUserId(REFERRER), 1L);
        attributionService.createAttribution(new OwnerAttributionBo()
                .setOwnerUserId(OWNER).setAttributionSource("PRIVATE_REFERRAL")
                .setProvinceAgentUserId(PROVINCE).setCityAgentUserId(CITY)
                .setCountyAgentUserId(COUNTY), 1L);
    }

    private List<WsSplitRecord> rowsOf(long orderId) {
        return splitService.list(Wrappers.lambdaQuery(WsSplitRecord.class)
                .eq(WsSplitRecord::getOrderId, orderId).orderByAsc(WsSplitRecord::getId));
    }

    private Map<String, WsSplitRecord> byReceiver(List<WsSplitRecord> rows) {
        return rows.stream().collect(Collectors.toMap(
                r -> r.getReceiverType() + ":" + r.getReceiverUserId(), Function.identity()));
    }

    @Test
    @DisplayName("无生效计划：回落 V1 口径（机主70%+平台余数），零组件证据")
    void noPlanFallsBackToV1() {
        splitService.enqueueForOrder(101L, "ORD-101", 10_000,
                SettlementEnum.ProductLine.WATER, OWNER, null, "20990101100000");
        List<WsSplitRecord> rows = rowsOf(101L);
        assertEquals(2, rows.size());
        Map<String, WsSplitRecord> map = byReceiver(rows);
        assertEquals(7_000, map.get("1:" + OWNER).getSplitAmount());
        assertEquals("7000", map.get("1:" + OWNER).getSplitRateSnap());
        assertEquals(3_000, map.get("3:0").getSplitAmount());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_split_component", Integer.class));
    }

    @Test
    @DisplayName("六方取水单：五收款人+平台，快照为数字比例，Σ行=基数，重放幂等")
    void sixwayWaterOrderSplitsAllRoles() {
        publishSixway();
        seedAttribution();
        splitService.enqueueForOrder(102L, "ORD-102", 10_000,
                SettlementEnum.ProductLine.WATER, OWNER, null, "20990101100000");
        List<WsSplitRecord> rows = rowsOf(102L);
        assertEquals(6, rows.size());
        Map<String, WsSplitRecord> map = byReceiver(rows);
        assertEquals(5_000, map.get("1:" + OWNER).getSplitAmount());
        assertEquals("5000", map.get("1:" + OWNER).getSplitRateSnap());
        assertEquals(500, map.get("5:" + REFERRER).getSplitAmount());
        assertEquals("500", map.get("5:" + REFERRER).getSplitRateSnap());
        // 级差：区县拿区县累计200，市拿300-200=100，省拿500-300=200
        assertEquals(200, map.get("6:" + COUNTY).getSplitAmount());
        assertEquals(100, map.get("6:" + CITY).getSplitAmount());
        assertEquals(200, map.get("6:" + PROVINCE).getSplitAmount());
        // 平台=公司40%整块（市场激励/公司运营/设备为内部记账口径，不伪造收款人）
        assertEquals(4_000, map.get("3:0").getSplitAmount());
        assertEquals("REMAINDER", map.get("3:0").getSplitRateSnap());
        assertEquals(10_000, rows.stream().mapToLong(WsSplitRecord::getSplitAmount).sum());
        // 组件证据：6 条（机主/推广/三级区域/平台），键含订单号与角色
        assertEquals(6, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_split_component WHERE ORDER_NO='ORD-102'", Integer.class));
        // 重放幂等：行数与组件数零增长
        splitService.enqueueForOrder(102L, "ORD-102", 10_000,
                SettlementEnum.ProductLine.WATER, OWNER, null, "20990101100000");
        assertEquals(6, rowsOf(102L).size());
        assertEquals(6, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_split_component WHERE ORDER_NO='ORD-102'", Integer.class));
    }

    @Test
    @DisplayName("公域未分配：推广与运营中心份额全部落平台")
    void publicUnassignedAllToPlatform() {
        publishSixway();
        splitService.enqueueForOrder(103L, "ORD-103", 10_000,
                SettlementEnum.ProductLine.WATER, OWNER, null, "20990101100000");
        List<WsSplitRecord> rows = rowsOf(103L);
        assertEquals(2, rows.size());
        Map<String, WsSplitRecord> map = byReceiver(rows);
        assertEquals(5_000, map.get("1:" + OWNER).getSplitAmount());
        assertEquals(5_000, map.get("3:0").getSplitAmount());
    }

    @Test
    @DisplayName("配送分线单：W/D 快照形态与冲减链同源；机主不再参与配送费线")
    void deliveryOrderKeepsLineSplitSnapshots() {
        publishSixway();
        seedAttribution();
        splitService.enqueueForDeliveryOrder(104L, "ORD-104", 1_000, 500,
                OWNER, COURIER, "20990101100000");
        List<WsSplitRecord> rows = rowsOf(104L);
        Map<String, WsSplitRecord> map = byReceiver(rows);
        assertEquals(500, map.get("1:" + OWNER).getSplitAmount());
        assertEquals("W5000", map.get("1:" + OWNER).getSplitRateSnap());
        assertEquals(50, map.get("5:" + REFERRER).getSplitAmount());
        assertEquals("W500", map.get("5:" + REFERRER).getSplitRateSnap());
        assertEquals(20, map.get("6:" + COUNTY).getSplitAmount());
        assertEquals("W200", map.get("6:" + COUNTY).getSplitRateSnap());
        assertEquals(450, map.get("2:" + COURIER).getSplitAmount());
        assertEquals("D9000", map.get("2:" + COURIER).getSplitRateSnap());
        assertEquals("REMAINDER", map.get("3:0").getSplitRateSnap());
        assertEquals(1_500, rows.stream().mapToLong(WsSplitRecord::getSplitAmount).sum());
    }

    @Test
    @DisplayName("版本锚=订单创建时点：发新计划不追溯旧单，重放旧单行零变化")
    void newPlanDoesNotRewriteExistingRows() {
        publishSixway();
        seedAttribution();
        splitService.enqueueForOrder(105L, "ORD-105", 10_000,
                SettlementEnum.ProductLine.WATER, OWNER, null, "20990101100000");
        List<WsSplitRecord> before = rowsOf(105L);
        // 新计划：水站降到 40%（生效于旧单创建之后）
        planService.publish(new SplitPlanBo()
                .setWaterOwnerBp(4000).setWaterReferrerBp(500)
                .setRegionProvinceCumBp(500).setRegionCityCumBp(300).setRegionCountyCumBp(200)
                .setDeliveryCourierBp(9000).setEffectTime("20990201000000"), 1L);
        // 旧单重放：仍按旧计划快照，行零变化（撞键幂等）
        splitService.enqueueForOrder(105L, "ORD-105", 10_000,
                SettlementEnum.ProductLine.WATER, OWNER, null, "20990101100000");
        List<WsSplitRecord> after = rowsOf(105L);
        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i).getSplitAmount(), after.get(i).getSplitAmount());
            assertEquals(before.get(i).getSplitRateSnap(), after.get(i).getSplitRateSnap());
        }
        // 新单（创建时点在新计划生效后）按新比例
        splitService.enqueueForOrder(106L, "ORD-106", 10_000,
                SettlementEnum.ProductLine.WATER, OWNER, null, "20990301100000");
        assertEquals(4_000, byReceiver(rowsOf(106L)).get("1:" + OWNER).getSplitAmount());
    }

    @Test
    @DisplayName("六方行结算入账：推荐人/区域行真实进收益账户，平台行不入个人钱包")
    void sixwayRowsSettleIntoIncomeAccounts() {
        publishSixway();
        seedAttribution();
        splitService.enqueueForOrder(107L, "ORD-107", 10_000,
                SettlementEnum.ProductLine.WATER, OWNER, null, "20990101100000");
        for (WsSplitRecord row : rowsOf(107L)) {
            assertTrue(splitService.settleOne(row.getId()) || row.getReceiverUserId() == 0L
                    || row.getSplitAmount() == 0,
                    "行推进失败：receiver=" + row.getReceiverType());
        }
        // 收益账户余额=各自份额；平台哨兵 0 不建账户
        assertEquals(500L, jdbc.queryForObject(
                "SELECT BALANCE_FEN FROM ws_income_account WHERE USER_ID=" + REFERRER, Long.class));
        assertEquals(200L, jdbc.queryForObject(
                "SELECT BALANCE_FEN FROM ws_income_account WHERE USER_ID=" + COUNTY, Long.class));
        assertEquals(100L, jdbc.queryForObject(
                "SELECT BALANCE_FEN FROM ws_income_account WHERE USER_ID=" + CITY, Long.class));
        assertEquals(200L, jdbc.queryForObject(
                "SELECT BALANCE_FEN FROM ws_income_account WHERE USER_ID=" + PROVINCE, Long.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_income_account WHERE USER_ID=0", Integer.class));
        // 幂等锚 INCOME:<splitId>：重复推进不重复入账
        for (WsSplitRecord row : rowsOf(107L)) {
            splitService.settleOne(row.getId());
        }
        assertEquals(500L, jdbc.queryForObject(
                "SELECT BALANCE_FEN FROM ws_income_account WHERE USER_ID=" + REFERRER, Long.class));
    }

    @Test
    @DisplayName("归属校验 fail-closed：自荐/同人兼两级/重复录入一律拒绝")
    void attributionValidationFailClosed() {
        assertThrows(JbkException.class, () -> attributionService.createReferrer(
                new OwnerAttributionBo().setOwnerUserId(OWNER).setReferrerUserId(OWNER), 1L));
        assertThrows(JbkException.class, () -> attributionService.createAttribution(
                new OwnerAttributionBo().setOwnerUserId(OWNER).setAttributionSource("PRIVATE_REFERRAL")
                        .setProvinceAgentUserId(PROVINCE).setCityAgentUserId(PROVINCE), 1L));
        attributionService.createReferrer(new OwnerAttributionBo()
                .setOwnerUserId(OWNER).setReferrerUserId(REFERRER), 1L);
        JbkException e = assertThrows(JbkException.class, () -> attributionService.createReferrer(
                new OwnerAttributionBo().setOwnerUserId(OWNER).setReferrerUserId(COURIER), 1L));
        assertTrue(e.getMsg().contains("冻结"), "实际=" + e.getMsg());
    }

    @Test
    @DisplayName("计划整版校验：区域累计倒挂整版拒绝，半套不落库")
    void planMonotonicViolationRejectedAtomically() {
        JbkException e = assertThrows(JbkException.class, () -> planService.publish(new SplitPlanBo()
                .setWaterOwnerBp(5000).setWaterReferrerBp(500)
                .setRegionProvinceCumBp(200).setRegionCityCumBp(300).setRegionCountyCumBp(500)
                .setDeliveryCourierBp(9000), 1L));
        assertTrue(e.getMsg().contains("倒挂"), "实际=" + e.getMsg());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ws_split_plan", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ws_split_plan_item", Integer.class));
    }
}
