package com.jbk.serve.service.settlement.split;

import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.settlement.WsRegionAgentMapper;
import com.jbk.tool.config.system.mybatis.MpMetaObjectHandler;
import com.jbk.tool.consts.settlement.SplitV2Enum;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 区域服务商归属的<b>真实 MySQL</b> 回归。
 *
 * <p>这里断言的每一条都只有真库能证明：版本选取用的是 varchar 字典序比较、
 * 唯一键真的挡住了同区域同生效时点的第二行、迁移对既有库幂等。Mockito 对这些没有证明力。</p>
 *
 * <p><b>边界</b>：本表是 D-407 人工分配用的领地台账，<b>不</b>决定订单分润归属
 * （D-406：归属按推荐血缘冻结、不按地缘重算）。护栏见
 * {@code SplitV2GuardrailTest#regionAgentResolverStaysOutOfSplitCalculation}。</p>
 *
 * <p>DDL 一律执行真实迁移文件，不在测试里抄第二份——抄本会漂移，漂移后测试照绿。</p>
 *
 * @author dakang
 * @since 2026-08-12
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = RegionAgentDbTest.Ctx.class)
@DisplayName("区域服务商归属真库判定")
class RegionAgentDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_region_agent_it")
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
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(ds);
            com.baomidou.mybatisplus.core.MybatisConfiguration cfg =
                    new com.baomidou.mybatisplus.core.MybatisConfiguration();
            cfg.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(cfg);
            GlobalConfig globalConfig = new GlobalConfig();
            globalConfig.setMetaObjectHandler(new MpMetaObjectHandler());
            factory.setGlobalConfig(globalConfig);
            return new SqlSessionTemplate(factory.getObject());
        }

        @Bean
        MapperFactoryBean<WsRegionAgentMapper> regionAgentMapper(SqlSessionTemplate t) {
            MapperFactoryBean<WsRegionAgentMapper> bean =
                    new MapperFactoryBean<>(WsRegionAgentMapper.class);
            bean.setSqlSessionTemplate(t);
            return bean;
        }

        @Bean
        RegionAgentResolver regionAgentResolver(WsRegionAgentMapper mapper) {
            return new RegionAgentResolver(mapper);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    private static final String PROV = "420000";
    private static final String CITY = "420100";
    private static final String COUNTY = "420111";

    /** 光谷软件园水站的区划码（与种子一致）。 */
    private static final RegionAgentResolver.StationRegion WUHAN_HONGSHAN =
            new RegionAgentResolver.StationRegion(PROV, CITY, COUNTY);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RegionAgentResolver resolver;

    private static Path repoRoot() {
        Path cur = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && cur != null; i++) {
            if (Files.isDirectory(cur.resolve("deploy/mysql/migrations"))) {
                return cur;
            }
            cur = cur.getParent();
        }
        throw new IllegalStateException("无法定位仓库根");
    }

    private static List<String> migrationStatements() throws IOException {
        String sql = new String(Files.readAllBytes(
                repoRoot().resolve("deploy/mysql/migrations/2026-08-12-region-agent.sql")),
                StandardCharsets.UTF_8);
        String noComment = sql.replaceAll("(?m)^\\s*--.*$", "");
        List<String> out = new ArrayList<>();
        for (String stmt : noComment.split(";")) {
            if (!stmt.isBlank()) {
                out.add(stmt.trim());
            }
        }
        return out;
    }

    /**
     * 执行迁移必须单一连接逐句执行：@cProv/@sql 是连接级用户变量，
     * 逐句从池拿连接会让 PREPARE 读到 NULL 直接 BadSqlGrammar。
     *
     * @return 是否被守卫中止（哨兵抛出的 Unknown column 消息里带「中止」）
     */
    private boolean runMigrationAborted() throws IOException {
        List<String> statements = migrationStatements();
        return Boolean.TRUE.equals(jdbc.execute((java.sql.Connection conn) -> {
            try (java.sql.Statement st = conn.createStatement()) {
                for (String stmt : statements) {
                    try {
                        st.execute(stmt);
                    }
                    catch (java.sql.SQLException e) {
                        if (String.valueOf(e.getMessage()).contains("中止")) {
                            return true;
                        }
                        throw e;
                    }
                }
            }
            return false;
        }));
    }

    /** 迁移要 ALTER ws_station，故先按最小形态建它——只需迁移探到的那几列存在与否。 */
    private void createStationTable() {
        jdbc.execute("""
                CREATE TABLE ws_station (
                  ID bigint NOT NULL AUTO_INCREMENT,
                  STATION_NAME varchar(50) NOT NULL,
                  STATION_REGION varchar(50),
                  PRIMARY KEY (ID)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    private void insertAgent(String level, String code, long userId, int status, String effectTime) {
        jdbc.update("""
                INSERT INTO ws_region_agent
                  (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME,
                   REGION_LEVEL, REGION_CODE, REGION_NAME, AGENT_USER_ID, AGENT_STATUS, EFFECT_TIME)
                VALUES (0, 1, '20260812000000', 1, '20260812000000', ?, ?, ?, ?, ?, ?)
                """, level, code, "测试区域", userId, status, effectTime);
    }

    @BeforeEach
    void cleanSlate() throws IOException {
        jdbc.execute("DROP TABLE IF EXISTS ws_region_agent");
        jdbc.execute("DROP TABLE IF EXISTS ws_station");
        createStationTable();
        assertFalse(runMigrationAborted(), "干净库上执行迁移不应被守卫中止");
    }

    // ==================== 结构 ====================

    @Test
    @DisplayName("迁移在既有库上幂等：连跑两次零变更，列不重复也不报错")
    void migrationIsIdempotent() throws IOException {
        assertFalse(runMigrationAborted(), "第二次执行迁移不应中止");
        Integer cols = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'ws_station'
                  AND column_name IN ('PROVINCE_CODE','CITY_CODE','DISTRICT_CODE')
                """, Integer.class);
        assertEquals(3, cols, "三个区划码列应恰好各一份");
    }

    @Test
    @DisplayName("守卫 fail-closed：存量表缺版本唯一键时中止，不带病继续")
    void migrationAbortsWhenLegacyTableLacksUniqueKey() throws IOException {
        // 造一个「手工建过、但没有唯一键」的存量表：这正是迁移头部守卫要拦的形状
        jdbc.execute("DROP TABLE IF EXISTS ws_region_agent");
        jdbc.execute("""
                CREATE TABLE ws_region_agent (
                  ID bigint NOT NULL AUTO_INCREMENT,
                  REGION_LEVEL varchar(16) NOT NULL,
                  REGION_CODE varchar(6) NOT NULL,
                  PRIMARY KEY (ID)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        assertTrue(runMigrationAborted(), "缺 uk_region_agent_version 的存量表必须让迁移中止");
    }

    @Test
    @DisplayName("同区域同生效时点只允许一行：第二行撞唯一键")
    void sameRegionSameEffectTimeIsUnique() {
        insertAgent("COUNTY", COUNTY, 1001L, 1, "20260101000000");
        assertThrows(DuplicateKeyException.class,
                () -> insertAgent("COUNTY", COUNTY, 1002L, 1, "20260101000000"),
                "同层级同区域同生效时点插第二行必须被唯一键挡住——否则同一时刻会有两个服务商");
    }

    // ==================== 版本选取 ====================

    @Test
    @DisplayName("按历史时点取版本：查三月份归谁，答案不受后来换签影响")
    void resolvePicksVersionEffectiveAtOrderTime() {
        insertAgent("COUNTY", COUNTY, 1001L, 1, "20260101000000");
        insertAgent("COUNTY", COUNTY, 2002L, 1, "20260601000000");

        // 领地换签后，回查换签之前的时点必须仍得到原服务商——运营复盘与审计要的就是这个。
        // 若这里返回 2002，历史就被"现在归谁"覆盖了，等于这张表只存了当前值。
        assertEquals(Optional.of(1001L),
                resolver.resolve(WUHAN_HONGSHAN, SplitV2Enum.RegionLevel.COUNTY, "20260315120000"));
        assertEquals(Optional.of(2002L),
                resolver.resolve(WUHAN_HONGSHAN, SplitV2Enum.RegionLevel.COUNTY, "20260715120000"));
    }

    @Test
    @DisplayName("生效时间含当刻：EFFECT_TIME 等于订单时间即已生效")
    void resolveIncludesTheEffectInstantItself() {
        insertAgent("COUNTY", COUNTY, 1001L, 1, "20260601000000");
        assertEquals(Optional.of(1001L),
                resolver.resolve(WUHAN_HONGSHAN, SplitV2Enum.RegionLevel.COUNTY, "20260601000000"),
                "生效时间是闭区间左端，等于该时刻的订单应已归属");
        assertTrue(resolver.resolve(WUHAN_HONGSHAN, SplitV2Enum.RegionLevel.COUNTY, "20260531235959")
                .isEmpty(), "生效前一秒不得归属");
    }

    @Test
    @DisplayName("停用版本返回空，不回落到上一个生效版本")
    void retiredVersionDoesNotFallBackToPrevious() {
        insertAgent("COUNTY", COUNTY, 1001L, 1, "20260101000000");
        insertAgent("COUNTY", COUNTY, 1001L, RegionAgentResolver.STATUS_RETIRED, "20260601000000");

        assertEquals(Optional.of(1001L),
                resolver.resolve(WUHAN_HONGSHAN, SplitV2Enum.RegionLevel.COUNTY, "20260315120000"));
        // 解约后必须是"没有服务商"，而不是退回解约前那一版——后者等于把已解约的人又接回来收钱
        assertTrue(resolver.resolve(WUHAN_HONGSHAN, SplitV2Enum.RegionLevel.COUNTY, "20260715120000")
                .isEmpty(), "停用版本之后必须无服务商");
    }

    @Test
    @DisplayName("某级空缺不兜底到上级：区县没人就是没人")
    void missingLevelDoesNotFallBackToParent() {
        insertAgent("PROVINCE", PROV, 3003L, 1, "20260101000000");
        insertAgent("CITY", CITY, 4004L, 1, "20260101000000");

        assertTrue(resolver.resolve(WUHAN_HONGSHAN, SplitV2Enum.RegionLevel.COUNTY, "20260715120000")
                .isEmpty(), "区县空缺时不得把市级或省级顶上——补位是计算器按计划项决定的事");

        Map<SplitV2Enum.RegionLevel, Long> all = resolver.resolveAll(WUHAN_HONGSHAN, "20260715120000");
        assertEquals(Map.of(SplitV2Enum.RegionLevel.PROVINCE, 3003L,
                SplitV2Enum.RegionLevel.CITY, 4004L), all,
                "resolveAll 只应返回真有服务商的层级，空缺层级不出现在 Map 里");
    }

    @Test
    @DisplayName("三级同时解析：各级各归各家，互不串码")
    void resolveAllReturnsEachLevelIndependently() {
        insertAgent("PROVINCE", PROV, 3003L, 1, "20260101000000");
        insertAgent("CITY", CITY, 4004L, 1, "20260101000000");
        insertAgent("COUNTY", COUNTY, 5005L, 1, "20260101000000");
        // 邻区的服务商：若解析漏了层级或区划码条件，就会把这个人错认成本区的
        insertAgent("COUNTY", "420112", 9009L, 1, "20260101000000");

        assertEquals(Map.of(SplitV2Enum.RegionLevel.PROVINCE, 3003L,
                SplitV2Enum.RegionLevel.CITY, 4004L,
                SplitV2Enum.RegionLevel.COUNTY, 5005L),
                resolver.resolveAll(WUHAN_HONGSHAN, "20260715120000"));
    }

    @Test
    @DisplayName("水站没录区划码：返回空而不是拿 NULL 去匹配")
    void blankRegionCodeResolvesToEmpty() {
        insertAgent("COUNTY", COUNTY, 5005L, 1, "20260101000000");
        RegionAgentResolver.StationRegion noCode =
                new RegionAgentResolver.StationRegion(PROV, CITY, null);
        assertTrue(resolver.resolve(noCode, SplitV2Enum.RegionLevel.COUNTY, "20260715120000")
                .isEmpty(), "区划码为空时必须返回空，绝不允许拿 NULL/空串去撞上任何一行");
    }

    @Test
    @DisplayName("COUNTY 取的是 districtCode——这条名字映射错了会静默算错人")
    void countyLevelReadsDistrictCode() {
        // 库里三张表用 DISTRICT_CODE，枚举用 COUNTY，是这套结构里唯一一处名字不一致。
        // 若映射写反（比如 COUNTY 去读 cityCode），解析会稳定返回市级服务商且毫无报错。
        RegionAgentResolver.StationRegion distinct =
                new RegionAgentResolver.StationRegion("110000", "110100", "110108");
        assertEquals("110108",
                RegionAgentResolver.codeOf(distinct, SplitV2Enum.RegionLevel.COUNTY));
        assertEquals("110100", RegionAgentResolver.codeOf(distinct, SplitV2Enum.RegionLevel.CITY));
        assertEquals("110000",
                RegionAgentResolver.codeOf(distinct, SplitV2Enum.RegionLevel.PROVINCE));
        assertEquals(null, RegionAgentResolver.codeOf(distinct, SplitV2Enum.RegionLevel.NONE),
                "NONE 是非区域角色的占位，不得对应任何区划码");
    }
}
