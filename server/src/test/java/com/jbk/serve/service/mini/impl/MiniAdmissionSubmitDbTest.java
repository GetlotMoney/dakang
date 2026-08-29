package com.jbk.serve.service.mini.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.station.WsStationMapper;
import com.jbk.serve.mapper.user.WsCourierMapper;
import com.jbk.serve.service.delivery.impl.DeliveryDbSchema;
import com.jbk.serve.service.message.IWsMessageService;
import com.jbk.serve.service.mini.IMiniDeliveryService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.user.UserEnum;
import com.jbk.tool.data.mini.bo.MiniAdmissionSubmitBo;
import com.jbk.tool.data.mini.vo.MiniCourierAdmissionVo;
import com.jbk.tool.data.user.po.WsCourier;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S3 配送员自助准入提交真库回归。
 *
 * <p>状态机（1350）：0 未提交/4 已驳回可提交（驳回复用原记录、审核备注保留为证据）；
 * 1 待审核/2 已启用/3 已停用拒绝。并发闸=本人 ws_user 行锁串行化。
 * PC 审核→启用/驳回→停用链路由 B09 既有测试与主环境实点覆盖，本类只钉自助提交面。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringJUnitConfig(MiniAdmissionSubmitDbTest.Ctx.class)
class MiniAdmissionSubmitDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static final long USER = 21L;

    @Configuration
    @EnableTransactionManagement
    static class Ctx {

        /**
         * 绑号闸放行版：最小 schema 无 ws_user 表。闸本身由 MiniPhoneGateTest /
         * PhoneGateAnchorContractTest / MiniPhoneGateChainDbTest 专门覆盖。
         */
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
        MapperFactoryBean<WsCourierMapper> wsCourierMapper(SqlSessionTemplate t) {
            return mapper(WsCourierMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsStationMapper> wsStationMapper(SqlSessionTemplate t) {
            return mapper(WsStationMapper.class, t);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.user.WsUserMapper> wsUserMapper(SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.user.WsUserMapper.class, t);
        }

        // ==== submitAdmission 依赖面之外的字段全部 mock（Spring 对 @Bean 产物做全量
        // required 注入，缺 bean 即拒绝启动）；本类不触碰任务/申诉/媒体/订单路径 ====

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
        com.jbk.serve.mapper.trade.WsOrderMapper orderMapper() {
            return Mockito.mock(com.jbk.serve.mapper.trade.WsOrderMapper.class);
        }

        @Bean
        com.jbk.serve.service.mini.IMiniFamilyService familyService() {
            return Mockito.mock(com.jbk.serve.service.mini.IMiniFamilyService.class);
        }

        @Bean
        IWsMessageService messageService() {
            return Mockito.mock(IWsMessageService.class);
        }

        @Bean
        IWsDomainEventService domainEventService() {
            return Mockito.mock(IWsDomainEventService.class);
        }

        /** 被测服务：真 courier/station mapper + 真事务代理（行锁并发闸必须真事务）。 */
        @Bean
        IMiniDeliveryService miniDeliveryService() {
            return new MiniDeliveryServiceImpl();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    @Autowired
    private IMiniDeliveryService miniDeliveryService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        DeliveryDbSchema.createAll(jdbc);
        DeliveryDbSchema.truncateAll(jdbc);
        jdbc.execute("TRUNCATE TABLE ws_courier");
        jdbc.update("INSERT INTO ws_user (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME,"
                + " USER_NAME, USER_PHONE, DISABLED_FLAG, USER_STATUS, POINTS)"
                + " VALUES (21, 0, 1, '20260801120000', 1, '20260801120000', '测试用户', '13900001111', 1, 1, 0)");
        jdbc.update("INSERT INTO ws_station (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME,"
                + " STATION_NAME, STATION_STATUS, OWNER_USER_ID)"
                + " VALUES (7, 0, 1, '20260801120000', 1, '20260801120000', '幸福水站', 1, NULL)");
        jdbc.update("INSERT INTO ws_station (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME,"
                + " STATION_NAME, STATION_STATUS, OWNER_USER_ID)"
                + " VALUES (8, 0, 1, '20260801120000', 1, '20260801120000', '停业水站', 2, NULL)");
    }

    private MiniAdmissionSubmitBo bo(List<Long> stations, String region) {
        MiniAdmissionSubmitBo bo = new MiniAdmissionSubmitBo();
        bo.setApplicantName("张三");
        bo.setPhone("13900001111");
        bo.setRequestedStationIds(stations);
        bo.setRequestedRegion(region);
        bo.setDeclarationAccepted(true);
        return bo;
    }

    private Integer statusOf(long userId) {
        return jdbc.queryForObject(
                "SELECT COURIER_STATUS FROM ws_courier WHERE USER_ID=? ORDER BY ID DESC LIMIT 1",
                Integer.class, userId);
    }

    /** 6.2-1：未提交用户正常申请 → 进入待审核。 */
    @Test
    void freshUserSubmitsIntoPendingReview() {
        MiniCourierAdmissionVo vo = miniDeliveryService.submitAdmission(bo(List.of(7L), null), USER);
        assertEquals(UserEnum.CourierStatus.PENDING.getValue(), vo.getStatus());
        assertEquals(1, statusOf(USER));
        assertEquals(1L, (long) jdbc.queryForObject("SELECT COUNT(*) FROM ws_courier", Long.class));
    }

    /** 6.2-2：驳回用户重新提交 → 复用原记录、历史审核备注保留、状态回待审核。 */
    @Test
    void rejectedUserResubmitsReusingOriginalRecord() {
        miniDeliveryService.submitAdmission(bo(List.of(7L), null), USER);
        long recordId = jdbc.queryForObject("SELECT ID FROM ws_courier WHERE USER_ID=?", Long.class, USER);
        jdbc.update("UPDATE ws_courier SET COURIER_STATUS=4, AUDIT_REMARK='材料不全' WHERE ID=?", recordId);

        MiniCourierAdmissionVo vo = miniDeliveryService.submitAdmission(bo(List.of(7L), "高新区"), USER);

        assertEquals(UserEnum.CourierStatus.PENDING.getValue(), vo.getStatus());
        assertEquals(1L, (long) jdbc.queryForObject("SELECT COUNT(*) FROM ws_courier", Long.class),
                "复用原记录，不追加第二条");
        assertEquals("材料不全", jdbc.queryForObject(
                "SELECT AUDIT_REMARK FROM ws_courier WHERE ID=?", String.class, recordId),
                "历史审核证据保留");
        assertEquals("高新区", jdbc.queryForObject(
                "SELECT SERVICE_REGION FROM ws_courier WHERE ID=?", String.class, recordId));
    }

    /** 6.2-3/4：待审核、已启用、已停用重复提交全部拒绝且零写入。 */
    @Test
    void activeStatusesRejectResubmission() {
        miniDeliveryService.submitAdmission(bo(List.of(7L), null), USER);
        assertThrows(JbkException.class,
                () -> miniDeliveryService.submitAdmission(bo(List.of(7L), null), USER),
                "待审核重复提交拒绝");
        for (int status : new int[] { 2, 3 }) {
            jdbc.update("UPDATE ws_courier SET COURIER_STATUS=? WHERE USER_ID=?", status, USER);
            assertThrows(JbkException.class,
                    () -> miniDeliveryService.submitAdmission(bo(List.of(7L), null), USER));
        }
        assertEquals(1L, (long) jdbc.queryForObject("SELECT COUNT(*) FROM ws_courier", Long.class),
                "全部拒绝路径零新增记录");
    }

    /** 6.2-6：非法水站、停业水站、区域与水站均空——全部零写入。 */
    @Test
    void illegalInputsProduceZeroWrites() {
        assertThrows(JbkException.class,
                () -> miniDeliveryService.submitAdmission(bo(List.of(), null), USER),
                "区域与水站至少一项");
        assertThrows(JbkException.class,
                () -> miniDeliveryService.submitAdmission(bo(List.of(999L), null), USER),
                "不存在的水站拒绝");
        assertThrows(JbkException.class,
                () -> miniDeliveryService.submitAdmission(bo(List.of(8L), null), USER),
                "停业水站拒绝");
        assertEquals(0L, (long) jdbc.queryForObject("SELECT COUNT(*) FROM ws_courier", Long.class));
    }

    /** 仅填服务区域（不选水站）是合法路径。 */
    @Test
    void regionOnlySubmissionIsLegal() {
        MiniCourierAdmissionVo vo = miniDeliveryService.submitAdmission(bo(List.of(), "高新区北片"), USER);
        assertEquals(UserEnum.CourierStatus.PENDING.getValue(), vo.getStatus());
        assertNotNull(vo.getRequestedRegion());
    }

    /** 6.2-7：并发重复提交只产生一条有效申请（本人 ws_user 行锁串行化）。 */
    @Test
    void concurrentSubmissionsProduceExactlyOneApplication() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger succeeded = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.invokeAll(List.of(
                    () -> raceSubmit(barrier, succeeded),
                    () -> raceSubmit(barrier, succeeded)), 30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, succeeded.get(), "恰一个提交成功");
        assertEquals(1L, (long) jdbc.queryForObject("SELECT COUNT(*) FROM ws_courier", Long.class),
                "库层恰一条申请记录");
        assertEquals(1, statusOf(USER));
    }

    private boolean raceSubmit(CyclicBarrier barrier, AtomicInteger succeeded) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        try {
            miniDeliveryService.submitAdmission(bo(List.of(7L), null), USER);
            succeeded.incrementAndGet();
            return true;
        }
        catch (JbkException legalLoss) {
            // 输方等锁后重读命中赢方的待审核记录：合法拒绝
            return false;
        }
    }

    /** PC 审核动作与自助提交的状态衔接：驳回→可再提交→启用→拒绝再提交（时间线可追溯）。 */
    @Test
    void auditLifecycleInterlocksWithResubmission() {
        miniDeliveryService.submitAdmission(bo(List.of(7L), null), USER);
        // PC 驳回（B09 链路的库层效果）
        jdbc.update("UPDATE ws_courier SET COURIER_STATUS=4, AUDIT_REMARK='区域超范围' WHERE USER_ID=?", USER);
        miniDeliveryService.submitAdmission(bo(List.of(7L), "老城区"), USER);
        assertEquals(1, statusOf(USER), "驳回后重提回待审核");
        // PC 启用后不可再自助提交
        jdbc.update("UPDATE ws_courier SET COURIER_STATUS=2 WHERE USER_ID=?", USER);
        assertThrows(JbkException.class,
                () -> miniDeliveryService.submitAdmission(bo(List.of(7L), null), USER));
        assertEquals("老城区", jdbc.queryForObject(
                "SELECT SERVICE_REGION FROM ws_courier WHERE USER_ID=? ORDER BY ID DESC LIMIT 1",
                String.class, USER), "启用记录承载最后一次提交内容");
    }
}
