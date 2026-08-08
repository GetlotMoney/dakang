package com.jbk.serve.service.minientry.impl;

import com.jbk.serve.mapper.minientry.WsMiniEntryConfigMapper;
import com.jbk.serve.service.minientry.IMiniEntryConfigService;
import com.jbk.tool.data.minientry.po.WsMiniEntryConfig;
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
 * S6 小程序入口配置真库回归（任务书 9.2）。
 *
 * <p>草稿不下发；发布可见/撤回隐藏；排序稳定；非法路由与非法 URL 拒绝；
 * 并发发布 VERSION CAS 只有一个赢家；审计走 @LogOperation（控制器层，另测）。
 * 「无能力账号看不到受限入口」由前端路由合同+能力守卫承担（配置不构成授权）。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringJUnitConfig(MiniEntryConfigDbTest.Ctx.class)
class MiniEntryConfigDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

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

        @Bean
        MapperFactoryBean<WsMiniEntryConfigMapper> wsMiniEntryConfigMapper(SqlSessionTemplate t) {
            MapperFactoryBean<WsMiniEntryConfigMapper> bean =
                    new MapperFactoryBean<>(WsMiniEntryConfigMapper.class);
            bean.setSqlSessionTemplate(t);
            return bean;
        }

        @Bean
        IMiniEntryConfigService miniEntryConfigService() {
            return new MiniEntryConfigServiceImpl();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    @Autowired
    private IMiniEntryConfigService entryService;
    @Autowired
    private JdbcTemplate jdbc;

    private static Object unwrap(Object proxy) {
        try {
            return org.springframework.test.util.AopTestUtils.getTargetObject(proxy);
        } catch (Exception e) {
            return proxy;
        }
    }

    @BeforeEach
    void reset() {
        // @Bean 产物仍会被 @Value 后处理覆盖（无 property source 时注默认空串），
        // 白名单必须在上下文就绪后反射设值——SettlementDbTest freezeDays 同款坑
        org.springframework.test.util.ReflectionTestUtils.setField(
                unwrap(entryService), "externalDomainWhitelist", "example.com");
        jdbc.execute("CREATE TABLE IF NOT EXISTS ws_mini_entry_config ("
                + " ID BIGINT PRIMARY KEY AUTO_INCREMENT,"
                + " DATA_STATUS TINYINT NOT NULL DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(14),"
                + " UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(14),"
                + " ENTRY_KEY VARCHAR(50) NOT NULL, ENTRY_TYPE TINYINT NOT NULL,"
                + " ENTRY_NAME VARCHAR(50) NOT NULL, SORT_NO INT NOT NULL DEFAULT 0,"
                + " ENABLED_FLAG TINYINT NOT NULL DEFAULT 2, JUMP_TYPE TINYINT NULL,"
                + " ROUTE_ID VARCHAR(10) NULL, EXTERNAL_URL VARCHAR(500) NULL,"
                + " CONTENT_TEXT VARCHAR(500) NULL, CONFIG_STATUS TINYINT NOT NULL,"
                + " PUBLISH_TIME VARCHAR(14) NULL, VERSION INT NOT NULL DEFAULT 1,"
                + " UNIQUE KEY uk_mini_entry_key (ENTRY_KEY)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("TRUNCATE TABLE ws_mini_entry_config");
    }

    private WsMiniEntryConfig featureDraft(String routeId, String name, int sort) {
        return new WsMiniEntryConfig()
                .setEntryKey(routeId).setEntryType(1).setEntryName(name)
                .setSortNo(sort).setEnabledFlag(2).setJumpType(1).setRouteId(routeId);
    }

    private WsMiniEntryConfig rowOf(Long id) {
        return entryService.listForAdmin().stream()
                .filter(r -> r.getId().equals(id)).findFirst().orElseThrow();
    }

    /** 9.2-1/2/3：草稿不下发；发布后可见；撤回后隐藏。 */
    @Test
    void draftNeverServedPublishShowsRetractHides() {
        Long id = entryService.saveDraft(featureDraft("U08", "配送订水", 20), null);
        assertEquals(0, entryService.listPublished().size(), "草稿不下发");

        entryService.publish(id, rowOf(id).getVersion());
        List<WsMiniEntryConfig> published = entryService.listPublished();
        assertEquals(1, published.size(), "发布后小程序可见");
        assertEquals("U08", published.get(0).getEntryKey());

        entryService.retract(id, rowOf(id).getVersion());
        assertEquals(0, entryService.listPublished().size(), "撤回后隐藏");
    }

    /** 9.2-4：排序稳定（SORT_NO 升序、同序按 ID）。 */
    @Test
    void orderingIsStable() {
        Long a = entryService.saveDraft(featureDraft("U10", "充值", 30), null);
        Long b = entryService.saveDraft(featureDraft("U07", "附近水站", 10), null);
        Long c = entryService.saveDraft(featureDraft("U08", "配送订水", 10), null);
        for (Long id : List.of(a, b, c)) {
            entryService.publish(id, rowOf(id).getVersion());
        }
        List<String> keys = entryService.listPublished().stream()
                .map(WsMiniEntryConfig::getEntryKey).toList();
        assertEquals(List.of("U07", "U08", "U10"), keys, "SORT 升序、同序按 ID 稳定");
    }

    /** 9.2-5：非法路由与非法 URL 全部拒绝（javascript:/http/未批域名/白名单外路由）。 */
    @Test
    void illegalRouteAndUrlRejected() {
        assertThrows(JbkException.class,
                () -> entryService.saveDraft(featureDraft("U99", "幽灵页", 1), null),
                "白名单外路由拒绝");
        assertThrows(JbkException.class,
                () -> entryService.saveDraft(featureDraft("U01", "首页", 1), null),
                "Tabbar 页不可配置（固定导航不受覆盖）");

        // R1-6：内容链接（type=2）小程序端无消费入口，新增与发布一律拒绝——
        // 连合法 https+白名单域名也不放行，「发布后即刻可见」的虚假声明从源头堵死
        for (String url : new String[] { "javascript:alert(1)", "http://example.com/faq",
                "https://evil.com/faq", "https://www.example.com/faq" }) {
            WsMiniEntryConfig link = new WsMiniEntryConfig()
                    .setEntryKey("faq").setEntryType(2).setEntryName("帮助")
                    .setJumpType(2).setExternalUrl(url);
            JbkException rejected = assertThrows(JbkException.class,
                    () -> entryService.saveDraft(link, null));
            assertTrue(rejected.getMsg().contains("内容链接暂不支持"), "type=2 一律拒绝：" + url);
        }
        assertEquals(0L, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_mini_entry_config", Long.class), "拒绝路径零写入");

        // 存量 type=2 行（历史数据）：禁止发布、允许撤回（只读/撤回语义）
        jdbc.update("INSERT INTO ws_mini_entry_config (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY,"
                + " UPDATE_TIME, ENTRY_KEY, ENTRY_TYPE, ENTRY_NAME, SORT_NO, ENABLED_FLAG, JUMP_TYPE,"
                + " EXTERNAL_URL, CONFIG_STATUS, PUBLISH_TIME, VERSION)"
                + " VALUES (0, 1, '20260807120000', 1, '20260807120000', 'protocol', 2, '用户协议', 1, 2, 2,"
                + " 'https://www.example.com/p', 2, '20260807120000', 1)");
        Long legacyId = jdbc.queryForObject(
                "SELECT ID FROM ws_mini_entry_config WHERE ENTRY_KEY='protocol'", Long.class);
        assertThrows(JbkException.class, () -> entryService.publish(legacyId, 1),
                "存量内容链接禁止（再）发布");
        entryService.retract(legacyId, 1);
        assertEquals(0, entryService.listPublished().size(), "存量内容链接可撤回下线");
    }

    /** 9.2-6：公告必须有正文；发布中的行不可直改（先撤回）。 */
    @Test
    void noticeNeedsTextAndPublishedRowLocked() {
        assertThrows(JbkException.class, () -> entryService.saveDraft(new WsMiniEntryConfig()
                        .setEntryKey("notice").setEntryType(3).setEntryName("维护公告"), null),
                "公告缺正文拒绝");
        Long id = entryService.saveDraft(new WsMiniEntryConfig()
                .setEntryKey("notice").setEntryType(3).setEntryName("维护公告")
                .setContentText("今晚维护"), null);
        entryService.publish(id, rowOf(id).getVersion());
        WsMiniEntryConfig published = rowOf(id);
        assertThrows(JbkException.class,
                () -> entryService.saveDraft(new WsMiniEntryConfig()
                        .setId(id).setEntryKey("notice").setEntryType(3)
                        .setEntryName("维护公告").setContentText("改口"), published.getVersion()),
                "已发布不可直改，须先撤回");
    }

    /** 9.2-7：并发发布只有一个有效版本（VERSION CAS）。 */
    @Test
    void concurrentPublishExactlyOneWins() throws Exception {
        Long id = entryService.saveDraft(featureDraft("U08", "配送订水", 20), null);
        Integer version = rowOf(id).getVersion();
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger wins = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.invokeAll(List.of(
                    () -> racePublish(barrier, wins, id, version),
                    () -> racePublish(barrier, wins, id, version)), 30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, wins.get(), "同版本锚下恰一个发布生效");
        assertEquals(1, entryService.listPublished().size());
        assertEquals(version + 1, rowOf(id).getVersion(), "版本恰好推进一次");
    }

    private boolean racePublish(CyclicBarrier barrier, AtomicInteger wins, Long id, Integer version)
            throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        try {
            entryService.publish(id, version);
            wins.incrementAndGet();
            return true;
        }
        catch (JbkException legalLoss) {
            return false;
        }
    }

    /** 停用（ENABLED=否）的已发布行同样不下发（未配置/已关闭默认隐藏）。 */
    @Test
    void disabledPublishedRowHidden() {
        WsMiniEntryConfig draft = featureDraft("U13", "家庭资料", 40).setEnabledFlag(1);
        Long id = entryService.saveDraft(draft, null);
        entryService.publish(id, rowOf(id).getVersion());
        assertEquals(0, entryService.listPublished().size(), "停用入口默认隐藏");
    }
}
