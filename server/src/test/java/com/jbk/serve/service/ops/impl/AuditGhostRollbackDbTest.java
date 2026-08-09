package com.jbk.serve.service.ops.impl;

import com.jbk.serve.mapper.delivery.WsDeliveryAutoRuleMapper;
import com.jbk.serve.mapper.ops.WsDomainEventMapper;
import com.jbk.serve.mapper.product.WsWaterTypeMapper;
import com.jbk.serve.mapper.station.WsStationMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.user.WsCourierMapper;
import com.jbk.serve.service.delivery.IMiniAutoRuleService;
import com.jbk.serve.service.delivery.impl.DeliveryDbSchema;
import com.jbk.serve.service.delivery.impl.MiniAutoRuleServiceImpl;
import com.jbk.serve.service.message.IWsMessageService;
import com.jbk.serve.service.mini.IMiniDeliveryService;
import com.jbk.serve.service.mini.impl.MiniDeliveryServiceImpl;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.data.mini.bo.MiniAdmissionSubmitBo;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * S2/S3 幽灵审计回滚回归（R1 P1）。
 *
 * <p>成功状态审计必须与业务写入同事务（recordReliableInTx，REQUIRED）：
 * 业务回滚→审计一并消失；审计/消息写入失败→业务共同回滚。
 * 数据库中不得留下「业务没成功、审计声称成功」的幽灵记录。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringJUnitConfig(AuditGhostRollbackDbTest.Ctx.class)
class AuditGhostRollbackDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static final long USER = 41L;

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
        TransactionTemplate transactionTemplate(PlatformTransactionManager tm) {
            return new TransactionTemplate(tm);
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
        MapperFactoryBean<WsDeliveryAutoRuleMapper> wsDeliveryAutoRuleMapper(SqlSessionTemplate t) {
            return mapper(WsDeliveryAutoRuleMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsWaterTypeMapper> wsWaterTypeMapper(SqlSessionTemplate t) {
            return mapper(WsWaterTypeMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsOrderMapper> wsOrderMapper(SqlSessionTemplate t) {
            return mapper(WsOrderMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsDomainEventMapper> wsDomainEventMapper(SqlSessionTemplate t) {
            return mapper(WsDomainEventMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsCourierMapper> wsCourierMapper(SqlSessionTemplate t) {
            return mapper(WsCourierMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsStationMapper> wsStationMapper(SqlSessionTemplate t) {
            return mapper(WsStationMapper.class, t);
        }

        @Bean
        IWsDomainEventService domainEventService() {
            // 真实现：REQUIRED 语义的同事务性正是被测行为，Mock 证不了
            return new WsDomainEventServiceImpl();
        }

        @Bean
        IWsMessageService messageService() {
            // S3 用例会对它打桩抛异常：消息失败必须拖回滚整个提交事务
            return Mockito.mock(IWsMessageService.class);
        }

        @Bean
        IMiniAutoRuleService miniAutoRuleService() {
            return new MiniAutoRuleServiceImpl();
        }

        @Bean
        IMiniDeliveryService miniDeliveryService() {
            MiniDeliveryServiceImpl service = new MiniDeliveryServiceImpl();
            return service;
        }

        // ==== MiniDeliveryServiceImpl 其余依赖全 mock（本类只测 submitAdmission 面）====
        @Bean
        com.jbk.serve.mapper.aftersale.WsAfterSaleActionMapper afterSaleActionMapper() {
            return Mockito.mock(com.jbk.serve.mapper.aftersale.WsAfterSaleActionMapper.class);
        }

        @Bean
        com.jbk.serve.service.delivery.IDeliveryOrderService deliveryOrderService() {
            return Mockito.mock(com.jbk.serve.service.delivery.IDeliveryOrderService.class);
        }

        @Bean
        com.jbk.serve.service.delivery.IDeliveryTaskTxService taskTxService() {
            return Mockito.mock(com.jbk.serve.service.delivery.IDeliveryTaskTxService.class);
        }

        @Bean
        com.jbk.serve.service.delivery.IDeliveryAppealTxService appealTxService() {
            return Mockito.mock(com.jbk.serve.service.delivery.IDeliveryAppealTxService.class);
        }

        @Bean
        com.jbk.serve.service.delivery.IDeliveryMediaService mediaService() {
            return Mockito.mock(com.jbk.serve.service.delivery.IDeliveryMediaService.class);
        }

        @Bean
        com.jbk.serve.service.mini.IMiniOrderService miniOrderService() {
            return Mockito.mock(com.jbk.serve.service.mini.IMiniOrderService.class);
        }

        @Bean
        com.jbk.serve.service.delivery.impl.CourierAccess courierAccess() {
            return Mockito.mock(com.jbk.serve.service.delivery.impl.CourierAccess.class);
        }

        @Bean
        com.jbk.serve.mapper.delivery.WsDeliveryTaskMapper taskMapper() {
            return Mockito.mock(com.jbk.serve.mapper.delivery.WsDeliveryTaskMapper.class);
        }

        @Bean
        com.jbk.serve.mapper.delivery.WsDeliveryAppealMapper appealMapper() {
            return Mockito.mock(com.jbk.serve.mapper.delivery.WsDeliveryAppealMapper.class);
        }

        @Bean
        com.jbk.serve.service.mini.IMiniFamilyService familyService() {
            return Mockito.mock(com.jbk.serve.service.mini.IMiniFamilyService.class);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    @Autowired
    private IMiniAutoRuleService autoRuleService;
    @Autowired
    private IMiniDeliveryService miniDeliveryService;
    @Autowired
    private IWsMessageService messageService;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        DeliveryDbSchema.createAll(jdbc);
        DeliveryDbSchema.truncateAll(jdbc);
        jdbc.execute("TRUNCATE TABLE ws_courier");
        Mockito.reset(messageService);
        jdbc.update("INSERT INTO ws_user (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME,"
                + " USER_NAME, USER_PHONE, DISABLED_FLAG, USER_STATUS, POINTS)"
                + " VALUES (41, 0, 1, '20260801120000', 1, '20260801120000', '测试用户', '13900001111', 1, 1, 0)");
        jdbc.update("INSERT INTO ws_station (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME,"
                + " STATION_NAME, STATION_STATUS, OWNER_USER_ID)"
                + " VALUES (7, 0, 1, '20260801120000', 1, '20260801120000', '幸福水站', 1, NULL)");
        jdbc.update("INSERT INTO ws_delivery_auto_rule (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY,"
                + " UPDATE_TIME, RULE_KEY, USER_ID, CARD_ID, STATION_ID, WATER_TYPE_ID, CONTAINER_SPEC,"
                + " DELIVERY_COUNT, PLAN_RETURN_COUNT, RECEIVE_ADDRESS, RECEIVE_PHONE, INTERVAL_DAYS,"
                + " ANCHOR_TIME, RULE_STATUS)"
                + " VALUES (51, 0, 41, '20260801120000', 41, '20260801120000', 'RK-GHOST', 41, 1, 7, 1,"
                + " '10L桶', 2, 0, '幸福小区', '13900001111', 7, '20260801120000', 1)");
    }

    private long auditCount(String keyPrefix) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_domain_event WHERE EVENT_KEY LIKE ?", Long.class, keyPrefix + "%");
    }

    /** S2：外层事务回滚时，成功状态审计随业务一并消失——零幽灵。 */
    @Test
    void ruleTransitionAuditRollsBackWithBusiness() {
        transactionTemplate.executeWithoutResult(status -> {
            autoRuleService.pause(USER, 51L);
            status.setRollbackOnly();
        });
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT RULE_STATUS FROM ws_delivery_auto_rule WHERE ID=51", Integer.class),
                "业务回滚：规则保持启用");
        assertEquals(0L, auditCount("AUTO_RULE:51"), "审计随业务回滚，零幽灵记录");

        // 对照组：正常提交时审计与状态同现
        autoRuleService.pause(USER, 51L);
        assertEquals(2, (int) jdbc.queryForObject(
                "SELECT RULE_STATUS FROM ws_delivery_auto_rule WHERE ID=51", Integer.class));
        assertEquals(1L, auditCount("AUTO_RULE:51"), "提交后恰一条审计");
        // 恢复再暂停：每次流转各成一行，互不撞键
        autoRuleService.resume(USER, 51L);
        autoRuleService.cancel(USER, 51L);
        assertEquals(3L, auditCount("AUTO_RULE:51"), "暂停/恢复/取消各自成行");
    }

    /** S3：站内消息发送失败→提交业务与成功审计共同回滚，数据库零幽灵。 */
    @Test
    void admissionSubmitRollsBackWhenMessageFails() {
        Mockito.doThrow(new JbkException("消息服务不可用"))
                .when(messageService).sendInApp(Mockito.anyLong(), Mockito.any(), Mockito.anyString(),
                        Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
        MiniAdmissionSubmitBo bo = new MiniAdmissionSubmitBo();
        bo.setApplicantName("张三");
        bo.setPhone("13900001111");
        bo.setRequestedStationIds(List.of(7L));
        bo.setDeclarationAccepted(true);

        assertThrows(JbkException.class, () -> miniDeliveryService.submitAdmission(bo, USER));

        assertEquals(0L, (long) jdbc.queryForObject("SELECT COUNT(*) FROM ws_courier", Long.class),
                "申请记录随失败回滚");
        assertEquals(0L, auditCount("ADMISSION:" + USER), "成功审计共同回滚，零幽灵");

        // 故障恢复后重试成功：记录+审计+消息三者同现
        Mockito.reset(messageService);
        miniDeliveryService.submitAdmission(bo, USER);
        assertEquals(1L, (long) jdbc.queryForObject("SELECT COUNT(*) FROM ws_courier", Long.class));
        assertEquals(1L, auditCount("ADMISSION:" + USER), "提交成功恰一条审计");
    }
}
