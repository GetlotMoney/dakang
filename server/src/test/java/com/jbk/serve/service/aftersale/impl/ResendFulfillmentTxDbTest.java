package com.jbk.serve.service.aftersale.impl;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.aftersale.WsAfterSaleActionMapper;
import com.jbk.serve.mapper.delivery.WsDeliveryAppealMapper;
import com.jbk.serve.mapper.delivery.WsDeliveryTaskMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.service.aftersale.IResendFulfillmentTxService;
import com.jbk.serve.service.delivery.impl.DeliveryDbSchema;
import com.jbk.tool.consts.aftersale.AfterSaleEnum;
import com.jbk.tool.data.aftersale.po.WsAfterSaleAction;
import com.jbk.tool.exception.JbkException;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 补送履约真库测试（E2E-04 包C，场景 8/9）。
 *
 * <p>钉住的核心不变式：</p>
 * <ul>
 *   <li>场景8 一次裁决只生成<b>一张</b>子订单与<b>一条</b>任务，重复调用不新增</li>
 *   <li>场景9 只有补送签收成功后，售后动作才转 3已完成</li>
 *   <li>补送<b>不动钱</b>：子订单零金额、不写 DELIVERY: 扣款流水、卡余额与水量分文不变</li>
 *   <li>数量不得放大、水种地址不得更换</li>
 *   <li>共键错位（拿别的任务去回签）不得让补送提前显示完成</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ResendFulfillmentTxDbTest.Ctx.class)
class ResendFulfillmentTxDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_resend_it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static final long USER_ID = 9L;
    private static final long CARD_ID = 100L;
    private static final long STATION_ID = 41L;
    private static final long ORDER_ID = 700L;
    private static final long TASK_ID = 800L;
    private static final long APPEAL_ID = 900L;
    private static final long ACTION_ID = 1000L;
    private static final long OP_USER = 5L;
    private static final String ORDER_NO = "WD20260729000700";
    private static final String NOW = "20260729120000";
    /** 原单计划 5 桶、实收 3 桶，裁决批准补 2 桶。 */
    private static final int PLANNED = 5;
    private static final int APPROVED = 2;
    /** 卡上的钱与水，补送全程一分一毫都不许动。 */
    private static final long CARD_FEN = 8_000L;
    private static final long CARD_ML = 60_000L;

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
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
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
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath:mapper/*/*.xml"));
            SqlSessionFactory sf = factory.getObject();
            assertNotNull(sf);
            return new SqlSessionTemplate(sf);
        }

        @Bean
        MapperFactoryBean<WsAfterSaleActionMapper> wsAfterSaleActionMapper(SqlSessionTemplate t) {
            return mapper(WsAfterSaleActionMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsDeliveryAppealMapper> wsDeliveryAppealMapper(SqlSessionTemplate t) {
            return mapper(WsDeliveryAppealMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsDeliveryTaskMapper> wsDeliveryTaskMapper(SqlSessionTemplate t) {
            return mapper(WsDeliveryTaskMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsOrderMapper> wsOrderMapper(SqlSessionTemplate t) {
            return mapper(WsOrderMapper.class, t);
        }

        @Bean
        IResendFulfillmentTxService resendFulfillmentTxService(WsAfterSaleActionMapper a,
                                                               WsDeliveryAppealMapper ap,
                                                               WsDeliveryTaskMapper t,
                                                               WsOrderMapper o) {
            return new ResendFulfillmentTxServiceImpl(a, ap, t, o);
        }

        private static <T> MapperFactoryBean<T> mapper(Class<T> type, SqlSessionTemplate t) {
            MapperFactoryBean<T> bean = new MapperFactoryBean<>(type);
            bean.setSqlSessionTemplate(t);
            return bean;
        }
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private IResendFulfillmentTxService resendTx;
    @Autowired
    private WsAfterSaleActionMapper actionMapper;

    @BeforeEach
    void reset() {
        DeliveryDbSchema.createAll(jdbc);
        jdbc.execute("DELETE FROM ws_after_sale_action");
        jdbc.execute("DELETE FROM ws_delivery_appeal");
        jdbc.execute("DELETE FROM ws_delivery_task");
        jdbc.execute("DELETE FROM ws_order");
        jdbc.execute("DELETE FROM ws_wallet_flow");
        jdbc.execute("DELETE FROM ws_card");
        seed();
    }

    /** 原单已签收（少送）→ 用户申诉 → 运营裁决补送，登记一条待执行的补送动作。 */
    private void seed() {
        jdbc.update("INSERT INTO ws_card (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, "
                        + "CARD_NO, USER_ID, BALANCE_AMOUNT, BALANCE_ML, CARD_STATUS) "
                        + "VALUES (?,0,1,?,1,?,?,?,?,?,1)",
                CARD_ID, NOW, NOW, "VC-RESEND-BASE", USER_ID, CARD_FEN, CARD_ML);
        jdbc.update("INSERT INTO ws_order (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, "
                        + "ORDER_NO, ORDER_TYPE, USER_ID, CARD_ID, STATION_ID, ORDER_STATUS, ORDER_AMOUNT, PAY_WAY) "
                        + "VALUES (?,0,1,?,1,?,?,3,?,?,?,4,?,2)",
                ORDER_ID, NOW, NOW, ORDER_NO, USER_ID, CARD_ID, STATION_ID, 7500L);
        jdbc.update("INSERT INTO ws_delivery_task (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, "
                        + "UPDATE_TIME, TASK_NO, ORDER_ID, USER_ID, STATION_ID, WATER_TYPE_ID, WATER_TYPE, "
                        + "CONTAINER_SPEC, DELIVERY_COUNT, PLAN_RETURN_COUNT, ACTUAL_DELIVERY_COUNT, "
                        + "WATER_AMOUNT, DELIVERY_FEE, RECEIVE_ADDRESS, RECEIVE_PHONE, TASK_STATUS, VERSION) "
                        + "VALUES (?,0,1,?,1,?,?,?,?,?,1,'纯净水','20L桶',?,0,3,6500,1000,'验收专区1号','13900001111',5,4)",
                TASK_ID, NOW, NOW, "DT20260729000800", ORDER_ID, USER_ID, STATION_ID, PLANNED);
        jdbc.update("INSERT INTO ws_delivery_appeal (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, "
                        + "UPDATE_TIME, ORDER_ID, TASK_ID, USER_ID, APPEAL_REASON, APPEAL_DESC, "
                        + "RECEIVED_COUNT, APPEAL_STATUS) VALUES (?,0,1,?,1,?,?,?,?,'QUANTITY','少送2桶',3,5)",
                APPEAL_ID, NOW, NOW, ORDER_ID, TASK_ID, USER_ID);
        jdbc.update("INSERT INTO ws_after_sale_action (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, "
                        + "UPDATE_TIME, AFTER_SALE_NO, SOURCE_TYPE, SOURCE_ID, ORDER_ID, USER_ID, CARD_ID, "
                        + "ACTION_TYPE, STRATEGY_CODE, APPROVED_COUNT, ACTION_STATUS, VERSION, RETRY_COUNT) "
                        + "VALUES (?,0,1,?,1,?,?,2,?,?,?,?,4,'RESEND',?,1,1,0)",
                ACTION_ID, NOW, NOW, "AS" + "0".repeat(28) + "02", APPEAL_ID, ORDER_ID, USER_ID, CARD_ID, APPROVED);
    }

    // ==================== 场景8：只生成一张子订单和一条任务 ====================

    @Test
    void scenario8_generatesExactlyOneZeroAmountChildOrderAndTask() {
        Long childId = resendTx.generate(ACTION_ID, OP_USER, NOW);

        Map<String, Object> child = row("SELECT * FROM ws_order WHERE ID = " + childId);
        assertEquals(0L, ((Number) child.get("ORDER_AMOUNT")).longValue(), "补送子订单必须零金额");
        assertEquals(3, ((Number) child.get("ORDER_TYPE")).intValue(), "仍是配送单");
        assertEquals(2, count("ws_order"), "原单 + 补送子订单，恰两张");

        Map<String, Object> task = row("SELECT * FROM ws_delivery_task WHERE ORDER_ID = " + childId);
        assertEquals(APPROVED, ((Number) task.get("DELIVERY_COUNT")).intValue(), "补送数量取裁决批准数");
        assertEquals(0L, ((Number) task.get("WATER_AMOUNT")).longValue(), "补送不计水费");
        assertEquals(0L, ((Number) task.get("DELIVERY_FEE")).longValue(), "补送不计配送费");
        assertEquals(0, ((Number) task.get("PLAN_RETURN_COUNT")).intValue(), "补送不重复回收空桶");
        assertEquals("纯净水", task.get("WATER_TYPE"), "水种必须复用原任务，不得更换");
        assertEquals("20L桶", task.get("CONTAINER_SPEC"), "规格必须复用原任务");
        assertEquals("验收专区1号", task.get("RECEIVE_ADDRESS"), "地址必须复用原单");
        assertEquals(1, ((Number) task.get("TASK_STATUS")).intValue(), "新任务从待接单开始");
        assertEquals(2, count("ws_delivery_task"), "原任务 + 补送任务，恰两条");
    }

    /** 补送不动钱：卡余额、水量、扣款流水三项全部不变。 */
    @Test
    void scenario8_resendTouchesNoMoney() {
        resendTx.generate(ACTION_ID, OP_USER, NOW);
        Map<String, Object> card = row("SELECT * FROM ws_card WHERE ID = " + CARD_ID);
        assertEquals(CARD_FEN, ((Number) card.get("BALANCE_AMOUNT")).longValue(), "补送不得动卡余额");
        assertEquals(CARD_ML, ((Number) card.get("BALANCE_ML")).longValue(), "补送不得动卡水量");
        assertEquals(0, count("ws_wallet_flow"), "补送不得写任何钱包流水");
    }

    /** 四方关联：售后动作行同时握有原单、申诉、补送子订单、补送任务。 */
    @Test
    void scenario8_afterSaleActionCarriesAllFourLinks() {
        Long childId = resendTx.generate(ACTION_ID, OP_USER, NOW);
        WsAfterSaleAction action = actionMapper.selectByIdIncludingDeleted(ACTION_ID);
        assertEquals(ORDER_ID, action.getOrderId(), "→ 原订单");
        assertEquals(APPEAL_ID, action.getSourceId(), "→ 申诉");
        assertEquals(childId, action.getResultOrderId(), "→ 补送子订单");
        assertNotNull(action.getResultTaskId(), "→ 补送任务");
        assertEquals(AfterSaleEnum.ActionStatus.PENDING.getValue(), action.getActionStatus(),
                "生成阶段不得把动作标成完成——补送还没送到");
    }

    /** 幂等：重复生成返回同一张子订单，不新增任何行。 */
    @Test
    void scenario8_repeatedGenerationIsIdempotent() {
        Long first = resendTx.generate(ACTION_ID, OP_USER, NOW);
        Long second = resendTx.generate(ACTION_ID, OP_USER, NOW);
        assertEquals(first, second);
        assertEquals(2, count("ws_order"));
        assertEquals(2, count("ws_delivery_task"));
    }

    // ==================== 场景9：签收后才完成 ====================

    /** 生成之后、签收之前，售后动作必须仍是待执行——绝不提前显示补送完成。 */
    @Test
    void scenario9_actionStaysPendingUntilResendIsSigned() {
        resendTx.generate(ACTION_ID, OP_USER, NOW);
        assertEquals(AfterSaleEnum.ActionStatus.PENDING.getValue(),
                actionMapper.selectByIdIncludingDeleted(ACTION_ID).getActionStatus());
        assertNull(actionMapper.selectByIdIncludingDeleted(ACTION_ID).getFinishTime());
    }

    @Test
    void scenario9_signedResendTaskCompletesTheAction() {
        resendTx.generate(ACTION_ID, OP_USER, NOW);
        Long resendTaskId = actionMapper.selectByIdIncludingDeleted(ACTION_ID).getResultTaskId();

        assertTrue(resendTx.completeOnSigned(resendTaskId, OP_USER, "20260729140000"));
        WsAfterSaleAction done = actionMapper.selectByIdIncludingDeleted(ACTION_ID);
        assertEquals(AfterSaleEnum.ActionStatus.SUCCESS.getValue(), done.getActionStatus());
        assertEquals("20260729140000", done.getFinishTime(), "完成时间取签收事务的业务时间");
    }

    /**
     * 共键错位：拿<b>原任务</b>去回签，不得完成补送动作。
     * 少了 CAS 里的 RESULT_TASK_ID 谓词，任意一次签收都能把补送标成完成。
     */
    @Test
    void scenario9_signingAForeignTaskMustNotCompleteTheResend() {
        resendTx.generate(ACTION_ID, OP_USER, NOW);
        assertTrue(!resendTx.completeOnSigned(TASK_ID, OP_USER, "20260729140000"),
                "原任务不是补送任务，不得推进补送动作");
        assertEquals(AfterSaleEnum.ActionStatus.PENDING.getValue(),
                actionMapper.selectByIdIncludingDeleted(ACTION_ID).getActionStatus());
    }

    /** 重复签收：第二次不得再推进（动作已终态）。 */
    @Test
    void scenario9_repeatedSignDoesNotAdvanceTwice() {
        resendTx.generate(ACTION_ID, OP_USER, NOW);
        Long resendTaskId = actionMapper.selectByIdIncludingDeleted(ACTION_ID).getResultTaskId();
        assertTrue(resendTx.completeOnSigned(resendTaskId, OP_USER, "20260729140000"));
        assertTrue(!resendTx.completeOnSigned(resendTaskId, OP_USER, "20260729150000"),
                "已完成的补送动作不得被二次推进");
        assertEquals("20260729140000",
                actionMapper.selectByIdIncludingDeleted(ACTION_ID).getFinishTime(),
                "完成时间不得被第二次签收覆盖");
    }

    /** 与补送无关的任务签收：恒返回 false，且不产生任何写入。 */
    @Test
    void scenario9_ordinaryTaskSignIsANoOp() {
        assertTrue(!resendTx.completeOnSigned(TASK_ID, OP_USER, NOW));
    }

    // ==================== 准入闸 ====================

    @Test
    void resendCountMayNotExceedOriginalPlan() {
        jdbc.update("UPDATE ws_after_sale_action SET APPROVED_COUNT = ? WHERE ID = ?", PLANNED + 1, ACTION_ID);
        JbkException ex = assertThrows(JbkException.class, () -> resendTx.generate(ACTION_ID, OP_USER, NOW));
        assertTrue(ex.getMessage().contains("不得扩大数量"), "实际=" + ex.getMessage());
        assertEquals(1, count("ws_order"), "拒绝时必须零副作用");
        assertEquals(1, count("ws_delivery_task"));
    }

    @Test
    void nonResendActionTypeIsRejected() {
        jdbc.update("UPDATE ws_after_sale_action SET ACTION_TYPE = 2 WHERE ID = ?", ACTION_ID);
        JbkException ex = assertThrows(JbkException.class, () -> resendTx.generate(ACTION_ID, OP_USER, NOW));
        assertTrue(ex.getMessage().contains("不是补送类型"), "实际=" + ex.getMessage());
        assertEquals(1, count("ws_order"));
    }

    /** 申诉、原任务、售后动作必须指向同一张原订单，错位一律拒绝。 */
    @Test
    void mismatchedOrderKeyAcrossAppealTaskAndActionIsRejected() {
        jdbc.update("UPDATE ws_delivery_appeal SET ORDER_ID = ? WHERE ID = ?", ORDER_ID + 999, APPEAL_ID);
        JbkException ex = assertThrows(JbkException.class, () -> resendTx.generate(ACTION_ID, OP_USER, NOW));
        assertTrue(ex.getMessage().contains("共键错位"), "实际=" + ex.getMessage());
        assertEquals(1, count("ws_order"), "拒绝时必须零副作用");
    }

    @Test
    void illegalApprovedCountIsRejected() {
        for (Object v : new Object[] { null, 0 }) {
            jdbc.update("UPDATE ws_after_sale_action SET APPROVED_COUNT = ? WHERE ID = ?", v, ACTION_ID);
            JbkException ex = assertThrows(JbkException.class, () -> resendTx.generate(ACTION_ID, OP_USER, NOW));
            assertTrue(ex.getMessage().contains("批准数量非法"), "v=" + v + " 实际=" + ex.getMessage());
        }
    }

    // ==================== CAS 谓词直测（绕过 Java 前置检查） ====================
    //
    // 上面的场景用例走完整链路，而链路里的 Java 查找与短路会先把不符的情况挡下，
    // 于是 XML 里那两条 CAS 谓词从未被触达——删掉它们，13 条场景用例一条都不红。
    // 这是靠缺陷注入发现的，与包B 踩到的是同一个形状。
    // 并发下真正生效的恰恰是 SQL 谓词（Java 读的是快照），故必须直接打 Mapper。

    /** RESULT_TASK_ID 谓词：拿别的任务号去完成，必须影响 0 行。 */
    @Test
    void markResendSucceededCasRejectsForeignTaskId() {
        resendTx.generate(ACTION_ID, OP_USER, NOW);
        int rows = actionMapper.markResendSucceeded(ACTION_ID, TASK_ID, OP_USER, "20260729140000");
        assertEquals(0, rows, "共键错位时必须影响 0 行，否则任意一次签收都能把补送标成完成");
        assertEquals(AfterSaleEnum.ActionStatus.PENDING.getValue(),
                actionMapper.selectByIdIncludingDeleted(ACTION_ID).getActionStatus());
    }

    /** 已终态的补送动作不得被再次推进。 */
    @Test
    void markResendSucceededCasRejectsTerminalAction() {
        resendTx.generate(ACTION_ID, OP_USER, NOW);
        Long resendTaskId = actionMapper.selectByIdIncludingDeleted(ACTION_ID).getResultTaskId();
        assertEquals(1, actionMapper.markResendSucceeded(ACTION_ID, resendTaskId, OP_USER, "20260729140000"));
        assertEquals(0, actionMapper.markResendSucceeded(ACTION_ID, resendTaskId, OP_USER, "20260729150000"),
                "已完成的动作再推进必须影响 0 行");
    }

    /** RESULT_ORDER_ID IS NULL 前态：已回填过的动作不得被二次回填成别的单子。 */
    @Test
    void linkResendResultCasRefusesToOverwriteExistingResult() {
        Long childId = resendTx.generate(ACTION_ID, OP_USER, NOW);
        WsAfterSaleAction action = actionMapper.selectByIdIncludingDeleted(ACTION_ID);
        int rows = actionMapper.linkResendResult(ACTION_ID, action.getVersion(),
                childId + 999, action.getResultTaskId() + 999, OP_USER, NOW);
        assertEquals(0, rows, "已回填的补送结果不得被覆盖，否则同一动作可以指向两张补送单");
        assertEquals(childId, actionMapper.selectByIdIncludingDeleted(ACTION_ID).getResultOrderId());
    }

    /** ACTION_TYPE=4 前态：非补送动作不得被挂上补送结果。 */
    @Test
    void linkResendResultCasRejectsNonResendActionType() {
        jdbc.update("UPDATE ws_after_sale_action SET ACTION_TYPE = 2 WHERE ID = ?", ACTION_ID);
        WsAfterSaleAction action = actionMapper.selectByIdIncludingDeleted(ACTION_ID);
        assertEquals(0, actionMapper.linkResendResult(ACTION_ID, action.getVersion(),
                12345L, 67890L, OP_USER, NOW), "非补送动作必须影响 0 行");
    }

    /** 原任务与售后动作订单错位（申诉侧正常）也必须被拒——两个 || 分支都要各自生效。 */
    @Test
    void mismatchedOrderKeyOnOriginTaskAloneIsRejected() {
        jdbc.update("UPDATE ws_delivery_task SET ORDER_ID = ? WHERE ID = ?", ORDER_ID + 888, TASK_ID);
        JbkException ex = assertThrows(JbkException.class, () -> resendTx.generate(ACTION_ID, OP_USER, NOW));
        assertTrue(ex.getMessage().contains("共键错位"), "实际=" + ex.getMessage());
    }

    // ==================== 助手 ====================

    private int count(String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }

    private Map<String, Object> row(String sql) {
        return jdbc.queryForMap(sql);
    }
}
