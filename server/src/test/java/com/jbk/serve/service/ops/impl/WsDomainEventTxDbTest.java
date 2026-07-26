package com.jbk.serve.service.ops.impl;

import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.ops.WsDomainEventMapper;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.config.system.mybatis.MpMetaObjectHandler;
import com.jbk.tool.consts.ops.OpsEnum;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可靠一次性审计（recordReliableOnce/recordReliableOnceAs）的<b>真实 MySQL + 真实 Spring 事务</b>
 * 集成测试（E2E-03 验收 P1-3）。钉住两条只有真库真事务能证明的性质：
 * <ol>
 *   <li><b>与业务同事务</b>：业务方法先写业务行、再落可靠审计、随后失败——两者必须一并回滚，
 *       不允许留下「业务已回滚、审计称成功」的幽灵记录；</li>
 *   <li><b>撞幂等键读回核验</b>：同语义（同 type/key/actor/portal/old/new）跨事务重放视为
 *       幂等命中不抛出、全库仍恰一条且不被改写；payload 的 time 每次不同，必须排除在比较外。</li>
 * </ol>
 * 毒键（语义不一致）fail-closed 的业务级回归见 DeliveryFulfillmentTxDbTest。
 *
 * <p>无 Docker 环境自动跳过。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = WsDomainEventTxDbTest.Ctx.class)
class WsDomainEventTxDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_event_it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static final String BIZ_KEY = "DNODE_ACCEPT:DT-EVENT-1";
    private static final String EVENT_KEY = "DT-EVENT-1";
    private static final long COURIER_USER = 70L;

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
            return new SqlSessionTemplate(factory.getObject());
        }

        @Bean
        MapperFactoryBean<WsDomainEventMapper> wsDomainEventMapper(SqlSessionTemplate t) {
            MapperFactoryBean<WsDomainEventMapper> bean = new MapperFactoryBean<>(WsDomainEventMapper.class);
            bean.setSqlSessionTemplate(t);
            return bean;
        }

        @Bean
        IWsDomainEventService domainEventService() {
            // 真实实现 + 真实 mapper：同事务与撞键读回核验是被测物本身，绝不 mock
            return new WsDomainEventServiceImpl();
        }

        @Bean
        AuditedBizTx auditedBizTx() {
            return new AuditedBizTx();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    /** 业务事务探针：真实 @Transactional 代理下「业务行 + 可靠审计」同事务写入。 */
    static class AuditedBizTx {
        @Autowired
        private JdbcTemplate jdbc;
        @Autowired
        private IWsDomainEventService domainEventService;

        @Transactional(rollbackFor = Exception.class)
        public void writeBizThenAudit(String bizValue, boolean failAfterAudit) {
            jdbc.update("INSERT INTO biz_probe(BIZ_VALUE) VALUES(?)", bizValue);
            domainEventService.recordReliableOnceAs(OpsEnum.ActorPortal.COURIER, COURIER_USER,
                    OpsEnum.EventType.DELIVERY_NODE, EVENT_KEY, BIZ_KEY, "1:待接单", "2:已接单");
            if (failAfterAudit) {
                throw new IllegalStateException("审计之后的业务环节失败");
            }
        }
    }

    @Autowired
    private AuditedBizTx bizTx;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        // uk_domain_event_biz_key 与生产 02-ws-business.sql 同名：撞键→读回核验路径的物理入口
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
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS biz_probe (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT, BIZ_VALUE VARCHAR(64)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("TRUNCATE TABLE ws_domain_event");
        jdbc.execute("TRUNCATE TABLE biz_probe");
    }

    // ================= 1：同事务原子性（业务失败连审计一起消失） =================

    @Test
    void reliableOnceAuditRollsBackTogetherWithFailingBusinessTransaction() {
        assertThrows(IllegalStateException.class, () -> bizTx.writeBizThenAudit("first", true));

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ws_domain_event", Integer.class),
                "业务回滚后不得残留任何审计行（幽灵审计）");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM biz_probe", Integer.class),
                "业务行必须一并回滚");
    }

    // ================= 2：合法幂等重放（同语义跨事务，time 排除在比较外） =================

    @Test
    void sameSemanticsReplayAcrossTransactionsIsIdempotentAndKeepsSingleRow() throws Exception {
        bizTx.writeBizThenAudit("first", false);
        Map<String, Object> stored = jdbc.queryForMap(
                "SELECT EVENT_PAYLOAD, ACTOR_ID, ACTOR_PORTAL FROM ws_domain_event WHERE BIZ_IDEMPOTENCY_KEY=?",
                BIZ_KEY);
        String storedPayload = String.valueOf(stored.get("EVENT_PAYLOAD"));

        // 跨秒重放：payload.time 必然不同，核验只看 old/new 才能不误杀合法重试
        Thread.sleep(1100);
        assertDoesNotThrow(() -> bizTx.writeBizThenAudit("second", false));

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ws_domain_event", Integer.class),
                "同语义重放全库仍恰一条");
        assertEquals(storedPayload, jdbc.queryForObject(
                "SELECT EVENT_PAYLOAD FROM ws_domain_event WHERE BIZ_IDEMPOTENCY_KEY=?", String.class, BIZ_KEY),
                "既有行不得被重放改写（含 time）");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM biz_probe", Integer.class),
                "两次业务事务本身都必须提交");
        assertEquals(COURIER_USER, ((Number) stored.get("ACTOR_ID")).longValue());
        assertEquals(OpsEnum.ActorPortal.COURIER.getValue(), ((Number) stored.get("ACTOR_PORTAL")).intValue());
    }

    // ================= 3：撞键但语义不一致 → fail-closed（业务整体回滚） =================

    @Test
    void occupiedKeyWithDifferentSemanticsFailsClosedAndRollsBackBusiness() {
        jdbc.update("INSERT INTO ws_domain_event(DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,"
                        + "EVENT_TYPE,EVENT_KEY,EVENT_PAYLOAD,ACTOR_ID,ACTOR_PORTAL,ACTOR_ROLE,"
                        + "WHITELIST_FLAG,CONSUMED_FLAG,BIZ_IDEMPOTENCY_KEY) "
                        + "VALUES(0,1,'20260723120000',1,'20260723120000',?,?,?,?,?,?,1,1,?)",
                OpsEnum.EventType.DELIVERY_NODE.getValue(), "BOGUS-EVENT",
                "{\"old\":\"污染\",\"new\":\"伪造\",\"time\":\"20260723120000\"}",
                9L, OpsEnum.ActorPortal.USER.getValue(), OpsEnum.ActorPortal.USER.getDesc(), BIZ_KEY);

        JbkException ex = assertThrows(JbkException.class, () -> bizTx.writeBizThenAudit("probe", false));
        assertTrue(ex.getMessage().contains("语义不一致"), "实际=" + ex.getMessage());

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM biz_probe", Integer.class),
                "毒键 fail-closed 必须连业务一起回滚");
        assertEquals("BOGUS-EVENT", jdbc.queryForObject(
                "SELECT EVENT_KEY FROM ws_domain_event WHERE BIZ_IDEMPOTENCY_KEY=?", String.class, BIZ_KEY),
                "既有行不得被覆盖");
    }
}
