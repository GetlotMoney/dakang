package com.jbk.serve.service.settlement.impl;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.aftersale.WsCardEntitlementBatchMapper;
import com.jbk.serve.mapper.trade.RechargeIdentityMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.mapper.user.WsCardMapper;
import com.jbk.serve.mapper.user.WsUserMapper;
import com.jbk.serve.service.delivery.impl.DeliveryDbSchema;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.serve.service.ops.impl.WsDomainEventServiceImpl;
import com.jbk.serve.service.settlement.IGiftCardService;
import com.jbk.serve.service.settlement.IInviteService;
import com.jbk.tool.data.settlement.bo.GiftIssueBo;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E-08 归因与发放的<b>真实 MySQL</b> 集成测试（包D）。
 * 钉住口径：邀请码确定性派生+惰性生成；补绑一次性 CAS/防自邀/重复拒绝；
 * 赠卡请求号幂等（重放读回不发第二张）；赠卡=流水+不可退批次+审计同事务；
 * 赠卡不占一人一卡名额（两处闸门 SQL 修正）。
 *
 * <p>无 Docker 环境自动跳过。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = AttributionDbTest.Ctx.class)
class AttributionDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_attr_it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static final long ALICE = 11L;
    private static final long BOB = 12L;
    private static final String NOW = "20260731120000";

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
        MapperFactoryBean<WsUserMapper> wsUserMapper(SqlSessionTemplate t) {
            return mapper(WsUserMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsCardMapper> wsCardMapper(SqlSessionTemplate t) {
            return mapper(WsCardMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsWalletFlowMapper> wsWalletFlowMapper(SqlSessionTemplate t) {
            return mapper(WsWalletFlowMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsCardEntitlementBatchMapper> wsCardEntitlementBatchMapper(SqlSessionTemplate t) {
            return mapper(WsCardEntitlementBatchMapper.class, t);
        }

        @Bean
        MapperFactoryBean<RechargeIdentityMapper> rechargeIdentityMapper(SqlSessionTemplate t) {
            return mapper(RechargeIdentityMapper.class, t);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.ops.WsDomainEventMapper> wsDomainEventMapper(SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.ops.WsDomainEventMapper.class, t);
        }

        @Bean
        IWsDomainEventService domainEventService() {
            return new WsDomainEventServiceImpl();
        }

        @Bean
        IInviteService inviteService() {
            return new InviteServiceImpl();
        }

        @Bean
        IGiftCardService giftCardService() {
            return new GiftCardServiceImpl();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    @Autowired
    private IInviteService inviteService;
    @Autowired
    private IGiftCardService giftCardService;
    @Autowired
    private RechargeIdentityMapper identityMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        DeliveryDbSchema.createAll(jdbc);
        DeliveryDbSchema.truncateAll(jdbc);
        seedUser(ALICE, "爱丽丝");
        seedUser(BOB, "鲍勃");
    }

    private void seedUser(long id, String name) {
        jdbc.update("INSERT INTO ws_user (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, USER_NAME, USER_PHONE) VALUES (?,0,1,?,1,?,?,CONCAT('139', ?))",
                id, NOW, NOW, name, id);
    }

    // ------------------------------------------------------------------
    // 邀请码
    // ------------------------------------------------------------------

    @Test
    void inviteCodeIsDeterministicAndLazy() {
        String first = inviteService.myInviteCode(ALICE);
        String second = inviteService.myInviteCode(ALICE);
        assertEquals(first, second, "确定性派生：两次取码恒同");
        assertTrue(first.startsWith("IV") && first.length() == 10, first);
        assertTrue(!first.equals(inviteService.myInviteCode(BOB)), "不同用户不同码");
    }

    @Test
    void bindIsOneShotWithSelfAndDuplicateRejected() {
        String aliceCode = inviteService.myInviteCode(ALICE);
        assertThrows(JbkException.class, () -> inviteService.bindReferrer(ALICE, aliceCode), "防自邀");
        assertThrows(JbkException.class, () -> inviteService.bindReferrer(BOB, "IVNOEXIST0"), "码不存在拒绝");

        inviteService.bindReferrer(BOB, aliceCode);
        assertEquals(ALICE, inviteService.referrerSnapshotOf(BOB), "绑定生效");
        assertThrows(JbkException.class, () -> inviteService.bindReferrer(BOB, aliceCode), "重复绑定明确拒绝");
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_domain_event WHERE BIZ_IDEMPOTENCY_KEY=?", Integer.class,
                "INVITE_BIND:" + BOB), "绑定审计留痕恰一条");
    }

    @Test
    void unboundUserSnapshotIsNull() {
        assertNull(inviteService.referrerSnapshotOf(ALICE), "未绑定=NULL（订单快照恒空）");
    }

    // ------------------------------------------------------------------
    // 赠卡发放
    // ------------------------------------------------------------------

    @Test
    void giftIssueCreatesCardFlowBatchAuditAtomically() {
        String requestId = UUID.randomUUID().toString();
        Long cardId = giftCardService.issue(new GiftIssueBo()
                .setRequestId(requestId).setUserId(ALICE)
                .setGrantMl(20_000L).setExpireDays(30).setRemark("新人礼"), 1L);

        assertEquals("1", jdbc.queryForObject(
                "SELECT CARD_STATUS FROM ws_card WHERE ID=?", String.class, cardId));
        assertTrue(jdbc.queryForObject(
                "SELECT EXPIRE_TIME FROM ws_card WHERE ID=?", String.class, cardId).compareTo(NOW) > 0,
                "赠卡必带有效期（D-213）");
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_wallet_flow WHERE BIZ_IDEMPOTENCY_KEY=?", Integer.class,
                "GIFT:" + requestId), "发放流水恰一条");
        // 批次：来源=4 运营赠卡、恒不可退、实付 0
        assertEquals("4|6|0", jdbc.queryForObject(
                "SELECT CONCAT(SOURCE_TYPE,'|',BATCH_STATUS,'|',PAY_AMOUNT_FEN) FROM ws_card_entitlement_batch WHERE CARD_ID=?",
                String.class, cardId));
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_domain_event WHERE BIZ_IDEMPOTENCY_KEY=?", Integer.class,
                "GIFT_ISSUE:" + requestId), "发放审计留痕");
    }

    @Test
    void giftIssueIsIdempotentOnReplay() {
        String requestId = UUID.randomUUID().toString();
        GiftIssueBo bo = new GiftIssueBo().setRequestId(requestId).setUserId(ALICE)
                .setGrantFen(500L).setExpireDays(7);
        Long first = giftCardService.issue(bo, 1L);
        Long second = giftCardService.issue(bo, 1L);
        assertEquals(first, second, "重放读回既有卡");
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_card WHERE USER_ID=?", Integer.class, ALICE), "绝不发第二张");
    }

    @Test
    void giftValidationFailsClosed() {
        assertThrows(JbkException.class, () -> giftCardService.issue(new GiftIssueBo()
                .setRequestId("not-a-uuid").setUserId(ALICE).setGrantMl(1L).setExpireDays(7), 1L));
        assertThrows(JbkException.class, () -> giftCardService.issue(new GiftIssueBo()
                .setRequestId(UUID.randomUUID().toString()).setUserId(ALICE).setExpireDays(7), 1L),
                "零权益拒绝");
        assertThrows(JbkException.class, () -> giftCardService.issue(new GiftIssueBo()
                .setRequestId(UUID.randomUUID().toString()).setUserId(ALICE).setGrantMl(1L), 1L),
                "缺有效期拒绝（D-213）");
    }

    @Test
    void giftCardDoesNotBlockFirstPurchase() {
        giftCardService.issue(new GiftIssueBo().setRequestId(UUID.randomUUID().toString())
                .setUserId(ALICE).setGrantMl(20_000L).setExpireDays(30), 1L);
        // 闸门修正（两处 SQL 同步）：带 EXPIRE_TIME 的赠卡不占「一人一卡」名额
        assertEquals(0L, identityMapper.selectCountLiveCardsByUser(ALICE),
                "先领赠卡的用户仍可首次购卡");
        // 永久付费卡仍占名额（口径不变）
        jdbc.update("INSERT INTO ws_card (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, CARD_NO, CARD_TYPE, USER_ID, BALANCE_AMOUNT, BALANCE_ML, CARD_STATUS) VALUES (0,1,?,1,?,'VC-PERM-1',1,?,0,0,1)",
                NOW, NOW, ALICE);
        assertEquals(1L, identityMapper.selectCountLiveCardsByUser(ALICE));
    }
}
