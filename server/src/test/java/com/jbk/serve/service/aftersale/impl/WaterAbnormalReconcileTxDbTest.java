package com.jbk.serve.service.aftersale.impl;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.aftersale.WsAfterSaleActionMapper;
import com.jbk.serve.mapper.device.WsCommandMapper;
import com.jbk.serve.mapper.ops.WsDomainEventMapper;
import com.jbk.serve.mapper.trade.TradeCardMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.service.aftersale.AfterSaleNo;
import com.jbk.serve.service.aftersale.IWaterAbnormalReconcileTxService;
import com.jbk.serve.service.delivery.impl.DeliveryDbSchema;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.serve.service.ops.impl.WsDomainEventServiceImpl;
import com.jbk.tool.consts.aftersale.AfterSaleEnum;
import com.jbk.tool.data.aftersale.vo.AdminWaterAbnormalPreviewVo;
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
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.dao.DuplicateKeyException;
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
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E-04 包A 取水异常核账（售后来源 3）的<b>真实 MySQL + 真实 Spring 事务</b>集成测试。
 *
 * <p><b>核心被测性质是「零资金写入」</b>：核账只核对既有账本并推进订单终态，
 * 一分钱一毫升都不许动。因此每条确认用例都精确断言卡余额、卡水量与流水条数三者不变——
 * 只断言「没抛异常」会让任何一次「顺手把没退的那笔补上」的改动继续全绿。</p>
 *
 * <p>钉住的事实：</p>
 * <ol>
 *   <li><b>来源A（已退差待复核）可确认</b>：零出水 6→7已退款、部分出水 6→4已完成；
 *       两者都零资金变化、零新增流水，台账行四元额度全 0。</li>
 *   <li><b>来源B（未退差）必须拒绝</b>：它需要真实补退差（后续包），当成"已退过"确认终态
 *       等于把用户的钱无偿吞掉，且账面显示订单正常完成。</li>
 *   <li><b>双信号一真一假一律 UNKNOWN</b>：两个方向都 fail-closed 转人工。</li>
 *   <li><b>账本断裂拒绝</b>：订单-指令共键错位、退差额与「计划-实际」不符都不可确认。</li>
 *   <li><b>拒绝证据独立提交</b>：主事务回滚正是拒绝的结果，证据必须活下来。</li>
 *   <li><b>幂等</b>：状态闸拦住重复确认；外力把状态改回 6 的重放撞
 *       {@code uk_after_sale_source} 整事务回滚，不产生第二条台账。</li>
 *   <li><b>编译期护栏的运行时佐证</b>：实现类的依赖清单里没有任何写卡能力。</li>
 * </ol>
 *
 * <p>无 Docker 环境自动跳过。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = WaterAbnormalReconcileTxDbTest.Ctx.class)
class WaterAbnormalReconcileTxDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_reconcile_it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static final long USER_ID = 9L;
    private static final long CARD_ID = 100L;
    private static final long STATION_ID = 41L;
    private static final long DEVICE_ID = 21L;
    private static final long OUTLET_ID = 31L;
    private static final long ORDER_ID = 500L;
    private static final long CMD_ID = 900L;
    private static final long OP_USER = 5L;
    private static final String ORDER_NO = "WT20260729000500";
    private static final String NOW = "20260729120000";
    private static final String REMARK = "设备回执实际出水与计划不符，已核对退差流水";

    /** 核账不得改动的两个余额基准（任何一处变化都是资金事故）。 */
    private static final long CARD_FEN = 7_000L;
    private static final long CARD_ML = 12_000L;
    private static final long PLAN_ML = 5_000L;
    /** 余额支付单的预扣金额；零出水时退差必须恰等于它。 */
    private static final long ORDER_FEN = 1_000L;

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
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath:mapper/*/*.xml"));
            return new SqlSessionTemplate(factory.getObject());
        }

        @Bean
        MapperFactoryBean<WsOrderMapper> wsOrderMapper(SqlSessionTemplate t) {
            return mapper(WsOrderMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsWalletFlowMapper> wsWalletFlowMapper(SqlSessionTemplate t) {
            return mapper(WsWalletFlowMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsCommandMapper> wsCommandMapper(SqlSessionTemplate t) {
            return mapper(WsCommandMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsAfterSaleActionMapper> wsAfterSaleActionMapper(SqlSessionTemplate t) {
            return mapper(WsAfterSaleActionMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsDomainEventMapper> wsDomainEventMapper(SqlSessionTemplate t) {
            return mapper(WsDomainEventMapper.class, t);
        }

        private static <M> MapperFactoryBean<M> mapper(Class<M> type, SqlSessionTemplate template) {
            MapperFactoryBean<M> bean = new MapperFactoryBean<>(type);
            bean.setSqlSessionTemplate(template);
            return bean;
        }

        @Bean
        IWsDomainEventService domainEventService() {
            // 真实现而非 mock：「拒绝证据 REQUIRES_NEW 独立提交、主事务回滚后仍存活」
            // 是本类要断言的事实之一，mock 证不了事务边界
            return new WsDomainEventServiceImpl();
        }

        @Bean
        com.jbk.serve.service.settlement.ISplitService splitService() {
            // E2E-08 完成挂点协作方：本类锁既有资金事实，分账行为由 SettlementDbTest 用真库锁定
            return org.mockito.Mockito.mock(com.jbk.serve.service.settlement.ISplitService.class);
        }

        @Bean
        IWaterAbnormalReconcileTxService reconcileTxService(WsOrderMapper orderMapper,
                                                           WsWalletFlowMapper walletFlowMapper,
                                                           WsCommandMapper commandMapper,
                                                           WsAfterSaleActionMapper actionMapper,
                                                           IWsDomainEventService domainEventService) {
            return new WaterAbnormalReconcileTxServiceImpl(orderMapper, walletFlowMapper,
                    commandMapper, actionMapper, domainEventService);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    @Autowired
    private IWaterAbnormalReconcileTxService reconcile;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        DeliveryDbSchema.createAll(jdbc);
        DeliveryDbSchema.truncateAll(jdbc);
        jdbc.update("INSERT INTO ws_station(ID,STATION_NAME,STATION_CODE,STATION_STATUS) VALUES(?,?,?,1)",
                STATION_ID, "测试站", "ST-41");
        jdbc.update("INSERT INTO ws_card(ID,DATA_STATUS,CARD_NO,CARD_TYPE,USER_ID,BALANCE_AMOUNT,BALANCE_ML,"
                        + "SCOPE_JSON,EXPIRE_TIME,CARD_STATUS) VALUES(?,0,?,1,?,?,?,NULL,NULL,1)",
                CARD_ID, "VC-TEST-100", USER_ID, CARD_FEN, CARD_ML);
    }

    // ==================== 种子构造 ====================

    /** 6异常待补偿 的取水订单；ACTUAL_ML 传 null 表示「结算路径从未写过它」（来源B 形态）。 */
    private void seedOrder(int payWay, Long actualMl, long orderAmount, Long cmdId) {
        jdbc.update("INSERT INTO ws_order(ID,DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,"
                        + "ORDER_NO,ORDER_TYPE,USER_ID,STATION_ID,DEVICE_ID,OUTLET_ID,CARD_ID,"
                        + "PLAN_ML,ACTUAL_ML,ORDER_AMOUNT,PAY_WAY,ORDER_STATUS,CMD_ID,"
                        + "FINISH_TIME,CANCEL_REASON) "
                        + "VALUES(?,0,?,?,?,?,?,1,?,?,?,?,?,?,?,?,?,6,?,?,?)",
                ORDER_ID, USER_ID, "20260729100000", USER_ID, "20260729100500",
                ORDER_NO, USER_ID, STATION_ID, DEVICE_ID, OUTLET_ID, CARD_ID,
                PLAN_ML, actualMl, orderAmount, payWay, cmdId,
                "20260729100500", "设备实际出水量小于计划量");
    }

    /** 与订单共键的出水指令（deviceId/orderId 双向回指）。 */
    private void seedCommand(long cmdId, long orderId, long deviceId) {
        jdbc.update("INSERT INTO ws_command(ID,DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,"
                        + "CMD_NO,DEVICE_ID,ORDER_ID,CMD_TYPE,CMD_STATUS) VALUES(?,0,?,?,?,?,?,?,?,1,7)",
                cmdId, USER_ID, "20260729100001", USER_ID, "20260729100500",
                "CMD-" + cmdId, deviceId, orderId);
    }

    /** 取水扣减流水（FLOW_TYPE=2）。 */
    private void seedConsumeFlow(long amountChange, long mlChange) {
        insertFlow(2, amountChange, mlChange, "取水扣减 " + ORDER_NO);
    }

    /** 结算路径的退差补偿流水（FLOW_TYPE=4）——来源A 的第二个判别信号。 */
    private void seedCompensateFlow(long amountChange, long mlChange) {
        insertFlow(4, amountChange, mlChange, "取水退差 " + ORDER_NO);
    }

    private void insertFlow(int flowType, long amountChange, long mlChange, String remark) {
        jdbc.update("INSERT INTO ws_wallet_flow(DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,"
                        + "CARD_ID,USER_ID,FLOW_TYPE,AMOUNT_CHANGE,ML_CHANGE,AMOUNT_AFTER,ML_AFTER,"
                        + "ORDER_ID,FLOW_REMARK,BIZ_IDEMPOTENCY_KEY) VALUES(0,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                USER_ID, "20260729100200", USER_ID, "20260729100200",
                CARD_ID, USER_ID, flowType, amountChange, mlChange, CARD_FEN, CARD_ML,
                ORDER_ID, remark, "WATER:" + ORDER_NO + ":" + flowType);
    }

    /** 来源A 标准形态：余额支付、已回填实际水量、已按差额退回卡内。 */
    private void seedRefundedBalanceOrder(long actualMl, long refundedFen) {
        seedOrder(2, actualMl, ORDER_FEN, CMD_ID);
        seedCommand(CMD_ID, ORDER_ID, DEVICE_ID);
        seedConsumeFlow(-ORDER_FEN, 0L);
        seedCompensateFlow(refundedFen, 0L);
    }

    // ==================== 读取辅助 ====================

    private int orderStatus() {
        return jdbc.queryForObject("SELECT ORDER_STATUS FROM ws_order WHERE ID=?", Integer.class, ORDER_ID);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private int countEvents(String bizKeyPrefix) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_domain_event WHERE BIZ_IDEMPOTENCY_KEY LIKE ?",
                Integer.class, bizKeyPrefix + "%");
    }

    /**
     * 零资金写入的完整断言面：卡两列精确不变 + 流水条数不变。
     * 这三条同时成立才叫「核账没加钱」——少任何一条都能被一次隐蔽的返还改动绕过。
     */
    private void assertNoMoneyMoved(int expectedFlowCount) {
        assertEquals(CARD_FEN, jdbc.queryForObject(
                "SELECT BALANCE_AMOUNT FROM ws_card WHERE ID=?", Long.class, CARD_ID), "卡余额必须精确不变");
        assertEquals(CARD_ML, jdbc.queryForObject(
                "SELECT BALANCE_ML FROM ws_card WHERE ID=?", Long.class, CARD_ID), "卡水量必须精确不变");
        assertEquals(expectedFlowCount, count("ws_wallet_flow"), "核账不得新增任何流水");
    }

    /** 台账留痕行的固定形态：来源3、卡内退款、四元额度全 0、直接 3已完成。 */
    private void assertZeroAmountLedgerRow() {
        assertEquals(1, count("ws_after_sale_action"));
        assertEquals(AfterSaleNo.derive(AfterSaleEnum.SourceType.WATER_ABNORMAL.getValue(), ORDER_ID),
                jdbc.queryForObject("SELECT AFTER_SALE_NO FROM ws_after_sale_action", String.class));
        assertEquals(AfterSaleEnum.SourceType.WATER_ABNORMAL.getValue(),
                jdbc.queryForObject("SELECT SOURCE_TYPE FROM ws_after_sale_action", Integer.class));
        assertEquals(ORDER_ID, jdbc.queryForObject("SELECT SOURCE_ID FROM ws_after_sale_action", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT REFUND_PRODUCT_FEN FROM ws_after_sale_action", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT REFUND_SERVICE_FEN FROM ws_after_sale_action", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT REFUND_PRODUCT_ML FROM ws_after_sale_action", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT REFUND_AMOUNT FROM ws_after_sale_action", Long.class),
                "四元额度全 0 才不会让按订单聚合的累计封顶少算可返额度");
        assertEquals(AfterSaleEnum.ActionStatus.SUCCESS.getValue(),
                jdbc.queryForObject("SELECT ACTION_STATUS FROM ws_after_sale_action", Integer.class));
        assertEquals(OP_USER, jdbc.queryForObject("SELECT APPROVE_BY FROM ws_after_sale_action", Long.class));
        assertEquals(NOW, jdbc.queryForObject("SELECT FINISH_TIME FROM ws_after_sale_action", String.class));
    }

    // ==================== 1：来源A 可确认，且零资金变化 ====================

    /** 零出水：6→7已退款。运营先看到的预览结论与确认时的判定必须同源。 */
    @Test
    void zeroActualMlConfirmsToRefundedWithoutTouchingMoney() {
        seedRefundedBalanceOrder(0L, ORDER_FEN);

        AdminWaterAbnormalPreviewVo preview = reconcile.preview(ORDER_ID);
        assertEquals("A", preview.getSourceVerdict());
        assertTrue(preview.getConfirmable(), "阻断原因=" + preview.getBlockReason());
        assertNull(preview.getBlockReason());
        assertEquals(7, preview.getSuggestTargetStatus());
        assertEquals(0L, preview.getActualMl());
        assertEquals(ORDER_FEN, preview.getRefundedFen());

        reconcile.confirm(ORDER_ID, REMARK, OP_USER, NOW);

        assertEquals(7, orderStatus(), "零出水等同退款完成");
        assertNoMoneyMoved(2);
        assertZeroAmountLedgerRow();
        // 结算完成时刻与原始异常原因是本次核账所依据的证据，绝不能被核账时间/备注覆盖
        assertEquals("20260729100500",
                jdbc.queryForObject("SELECT FINISH_TIME FROM ws_order WHERE ID=?", String.class, ORDER_ID));
        assertEquals("设备实际出水量小于计划量",
                jdbc.queryForObject("SELECT CANCEL_REASON FROM ws_order WHERE ID=?", String.class, ORDER_ID));
        assertEquals(NOW,
                jdbc.queryForObject("SELECT UPDATE_TIME FROM ws_order WHERE ID=?", String.class, ORDER_ID));
        assertEquals(1, countEvents("WATER_RECONCILE:"), "正向核账审计与业务同事务落库");
        assertEquals(0, countEvents("WATER_RECONCILE_REJECT:"));
    }

    /** 部分出水：6→4已完成，同样零资金变化。 */
    @Test
    void partialActualMlConfirmsToFinishedWithoutTouchingMoney() {
        seedRefundedBalanceOrder(3_000L, 400L);

        assertEquals(4, reconcile.preview(ORDER_ID).getSuggestTargetStatus());
        reconcile.confirm(ORDER_ID, REMARK, OP_USER, NOW);

        assertEquals(4, orderStatus(), "部分出水等同正常完成");
        assertNoMoneyMoved(2);
        assertZeroAmountLedgerRow();
    }

    /**
     * payWay=3 的退差水量是精确等式：必须恰等于「计划 − min(实际, 计划)」。
     * 差一毫升就是账实不符，只能转人工——这条不依赖单价，没有任何容差空间。
     */
    @Test
    void mlPayWayRequiresExactRefundedMlDifference() {
        seedOrder(3, 2_000L, 0L, CMD_ID);
        seedCommand(CMD_ID, ORDER_ID, DEVICE_ID);
        seedConsumeFlow(0L, -PLAN_ML);
        seedCompensateFlow(0L, 2_999L);

        JbkException off = assertThrows(JbkException.class,
                () -> reconcile.confirm(ORDER_ID, REMARK, OP_USER, NOW));
        assertTrue(off.getMessage().contains("退差水量"), "实际=" + off.getMessage());
        assertEquals(6, orderStatus(), "账实不符不得推进终态");
        assertEquals(0, count("ws_after_sale_action"));

        // 修正为精确差额后方可确认；确认本身依旧零资金变化
        jdbc.update("UPDATE ws_wallet_flow SET ML_CHANGE=3000 WHERE FLOW_TYPE=4");
        reconcile.confirm(ORDER_ID, REMARK, OP_USER, NOW);
        assertEquals(4, orderStatus());
        assertNoMoneyMoved(2);
        assertZeroAmountLedgerRow();
    }

    // ==================== 2：来源B 必须拒绝 ====================

    /**
     * 来源B（指令失败收敛置位但未退差）→ 拒绝，订单仍为 6。
     * 放行它等于把用户的钱无偿吞掉，还让账面显示订单正常完成。
     * 同时验证拒绝证据走 REQUIRES_NEW：主事务回滚了，证据必须还在。
     */
    @Test
    void unsettledSourceIsRejectedAndLeavesIndependentEvidence() {
        seedOrder(2, null, ORDER_FEN, CMD_ID);
        seedCommand(CMD_ID, ORDER_ID, DEVICE_ID);
        seedConsumeFlow(-ORDER_FEN, 0L);

        AdminWaterAbnormalPreviewVo preview = reconcile.preview(ORDER_ID);
        assertEquals("B", preview.getSourceVerdict());
        assertFalse(preview.getConfirmable());
        assertNull(preview.getSuggestTargetStatus(), "有阻断原因就不许再带一个建议终态");
        assertEquals(0, countEvents("WATER_RECONCILE_REJECT:"), "预览不构成一次业务判定，不留拒绝证据");

        JbkException ex = assertThrows(JbkException.class,
                () -> reconcile.confirm(ORDER_ID, REMARK, OP_USER, NOW));
        assertTrue(ex.getMessage().contains("该单尚未退差"), "实际=" + ex.getMessage());

        assertEquals(6, orderStatus(), "拒绝后订单必须仍为 6异常待补偿");
        assertNoMoneyMoved(1);
        assertEquals(0, count("ws_after_sale_action"), "拒绝不得留台账行");
        assertEquals(1, countEvents("WATER_RECONCILE_REJECT:"),
                "拒绝证据独立提交：主事务回滚不得把「有人试图确认」抹成无痕事件");
        assertEquals(0, countEvents("WATER_RECONCILE:"), "拒绝不得留正向核账审计");
    }

    // ==================== 3：双信号一真一假 → UNKNOWN ====================

    @Test
    void settledWithoutCompensationFlowIsUnknownAndRejected() {
        // 有 ACTUAL_ML 但查不到退差流水：可能是零差额，也可能是流水缺失，无法证明已退差
        seedOrder(2, 3_000L, ORDER_FEN, CMD_ID);
        seedCommand(CMD_ID, ORDER_ID, DEVICE_ID);
        seedConsumeFlow(-ORDER_FEN, 0L);

        assertEquals("UNKNOWN", reconcile.preview(ORDER_ID).getSourceVerdict());
        JbkException ex = assertThrows(JbkException.class,
                () -> reconcile.confirm(ORDER_ID, REMARK, OP_USER, NOW));
        assertTrue(ex.getMessage().contains("查不到补偿流水"), "实际=" + ex.getMessage());
        assertEquals(6, orderStatus());
        assertNoMoneyMoved(1);
        assertEquals(0, count("ws_after_sale_action"));
    }

    @Test
    void compensatedWithoutActualMlIsUnknownAndRejected() {
        // 反方向：没有 ACTUAL_ML 却已有补偿流水，结算记录与账本自相矛盾
        seedOrder(2, null, ORDER_FEN, CMD_ID);
        seedCommand(CMD_ID, ORDER_ID, DEVICE_ID);
        seedConsumeFlow(-ORDER_FEN, 0L);
        seedCompensateFlow(ORDER_FEN, 0L);

        assertEquals("UNKNOWN", reconcile.preview(ORDER_ID).getSourceVerdict());
        JbkException ex = assertThrows(JbkException.class,
                () -> reconcile.confirm(ORDER_ID, REMARK, OP_USER, NOW));
        assertTrue(ex.getMessage().contains("自相矛盾"), "实际=" + ex.getMessage());
        assertEquals(6, orderStatus());
        assertNoMoneyMoved(2);
        assertEquals(0, count("ws_after_sale_action"));
    }

    // ==================== 4：账本断裂（订单-指令共键错位） ====================

    /** 指令归属另一张订单：出水事实无从追溯到本单，必须拒绝。 */
    @Test
    void commandCoKeyMismatchIsRejected() {
        seedRefundedBalanceOrder(0L, ORDER_FEN);
        jdbc.update("UPDATE ws_command SET ORDER_ID=? WHERE ID=?", ORDER_ID + 1, CMD_ID);

        JbkException ex = assertThrows(JbkException.class,
                () -> reconcile.confirm(ORDER_ID, REMARK, OP_USER, NOW));
        assertTrue(ex.getMessage().contains("共键错位"), "实际=" + ex.getMessage());
        assertEquals(6, orderStatus());
        assertNoMoneyMoved(2);
        assertEquals(0, count("ws_after_sale_action"));

        // 设备错位同样拒绝：同一条指令必须同时对上订单与设备
        jdbc.update("UPDATE ws_command SET ORDER_ID=?, DEVICE_ID=? WHERE ID=?",
                ORDER_ID, DEVICE_ID + 1, CMD_ID);
        assertTrue(assertThrows(JbkException.class,
                () -> reconcile.confirm(ORDER_ID, REMARK, OP_USER, NOW)).getMessage().contains("共键错位"));
        assertEquals(6, orderStatus());

        // 指令行不存在：同样 fail-closed，绝不按「没绑指令也算完成」放行
        jdbc.update("DELETE FROM ws_command WHERE ID=?", CMD_ID);
        assertTrue(assertThrows(JbkException.class,
                () -> reconcile.confirm(ORDER_ID, REMARK, OP_USER, NOW)).getMessage().contains("出水指令不存在"));
        assertEquals(6, orderStatus());
        assertNoMoneyMoved(2);
        assertEquals(0, count("ws_after_sale_action"));
    }

    // ==================== 5：幂等 ====================

    /** 重复确认：第二次被 6异常待补偿 的状态闸拦下，订单终态与台账都不再变。 */
    @Test
    void repeatedConfirmIsBlockedByStatusGateAndKeepsSingleLedgerRow() {
        seedRefundedBalanceOrder(0L, ORDER_FEN);
        reconcile.confirm(ORDER_ID, REMARK, OP_USER, NOW);
        assertEquals(7, orderStatus());

        JbkException ex = assertThrows(JbkException.class,
                () -> reconcile.confirm(ORDER_ID, REMARK, OP_USER, "20260729130000"));
        assertTrue(ex.getMessage().contains("订单不在 6异常待补偿 状态"), "实际=" + ex.getMessage());

        assertEquals(7, orderStatus(), "订单状态不再变");
        assertEquals(1, count("ws_after_sale_action"), "台账恰一行");
        assertNoMoneyMoved(2);
    }

    /**
     * 外力把订单状态改回 6 后的重放：台账行仍在，撞 {@code uk_after_sale_source} 整事务回滚。
     *
     * <p>刻意不"当成已完成返回"：订单终态 CAS 已经在本事务前面独占了 6→终态这一步，
     * 能走到台账写入还撞键说明有本路径之外的写入方，属账本异常，必须炸出来。
     * 因此第二次确认后订单必须回到 6（CAS 的推进随事务回滚），台账仍是一行。</p>
     */
    @Test
    void replayAfterExternalStatusResetHitsSourceUniqueKeyAndRollsBack() {
        seedRefundedBalanceOrder(0L, ORDER_FEN);
        reconcile.confirm(ORDER_ID, REMARK, OP_USER, NOW);
        assertEquals(1, count("ws_after_sale_action"));

        jdbc.update("UPDATE ws_order SET ORDER_STATUS=6 WHERE ID=?", ORDER_ID);

        assertThrows(DuplicateKeyException.class,
                () -> reconcile.confirm(ORDER_ID, REMARK, OP_USER, "20260729130000"));

        assertEquals(6, orderStatus(), "撞唯一键必须整事务回滚，订单终态推进一并撤销");
        assertEquals(1, count("ws_after_sale_action"), "不产生第二条台账行");
        assertNoMoneyMoved(2);
    }

    // ==================== 6：编译期护栏的运行时佐证 ====================

    /**
     * 核账实现类的依赖清单里<b>没有任何写卡能力</b>。
     *
     * <p>这条把「核账绝不加钱」从注释里的约定变成可执行断言：想在核账里顺手退一笔钱，
     * 必须先给本类新增一个具备写卡能力的字段或构造参数，而那正是本用例会立刻拦下的动作。
     * 字段与构造参数两侧都查——只查字段的话，一个直接 new 出来的临时 Mapper 就能绕过去。</p>
     */
    @Test
    void reconcileServiceDeclaresNoCardWritingDependency() {
        Class<?> target = WaterAbnormalReconcileTxServiceImpl.class;
        for (Field field : target.getDeclaredFields()) {
            assertNotEquals(TradeCardMapper.class, field.getType(),
                    "核账事务不得持有写卡能力：字段 " + field.getName());
        }
        for (Constructor<?> constructor : target.getDeclaredConstructors()) {
            for (Class<?> parameter : constructor.getParameterTypes()) {
                assertNotEquals(TradeCardMapper.class, parameter,
                        "核账事务的构造参数不得出现写卡能力：" + parameter.getSimpleName());
            }
        }
        // 反向自证：断言口径本身有效——同包的返还内核确实持有 TradeCardMapper，
        // 若上面的比较写错（例如比了类名字符串常量），这一条会先红
        assertTrue(hasCardMapperField(AfterSaleActionTxServiceImpl.class),
                "返还内核应当持有写卡能力，否则本用例的比较口径是失效的");
        assertFalse(hasCardMapperField(target));
    }

    private boolean hasCardMapperField(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            if (TradeCardMapper.class.equals(field.getType())) {
                return true;
            }
        }
        return false;
    }

    // ==================== 7：结构性前提 ====================

    @Test
    void missingOrderAndNonWaterOrderAreNotConfirmable() {
        AdminWaterAbnormalPreviewVo missing = reconcile.preview(ORDER_ID);
        assertFalse(missing.getConfirmable());
        assertNotNull(missing.getBlockReason());

        seedRefundedBalanceOrder(0L, ORDER_FEN);
        jdbc.update("UPDATE ws_order SET ORDER_TYPE=3 WHERE ID=?", ORDER_ID);
        JbkException ex = assertThrows(JbkException.class,
                () -> reconcile.confirm(ORDER_ID, REMARK, OP_USER, NOW));
        assertTrue(ex.getMessage().contains("非扫码取水订单"), "实际=" + ex.getMessage());
        assertEquals(6, orderStatus());
        assertEquals(0, count("ws_after_sale_action"));

        // 入参闸：业务时钟长度非法、核账说明为空一律拒绝，且不得改动订单
        jdbc.update("UPDATE ws_order SET ORDER_TYPE=1 WHERE ID=?", ORDER_ID);
        assertThrows(JbkException.class, () -> reconcile.confirm(ORDER_ID, REMARK, OP_USER, "2026072912"));
        assertThrows(JbkException.class, () -> reconcile.confirm(ORDER_ID, "  ", OP_USER, NOW));
        assertThrows(JbkException.class, () -> reconcile.confirm(ORDER_ID, REMARK, null, NOW));
        assertEquals(6, orderStatus());
        assertNoMoneyMoved(2);
        assertEquals(0, count("ws_after_sale_action"));
    }

    /**
     * 隔离级别既是声明也是运行期断言，两侧都要钉住。
     *
     * <p>confirm 的传播级别刻意保持 REQUIRED（终态、台账与领域事件须与调用方同生共死），
     * 而 REQUIRED 一旦加入外层事务，Spring 会静默丢弃这里声明的 READ_COMMITTED——
     * 上面 javadoc 论证的「不拿旧账本核出终态」随之失效且不报错。
     * 故方法体内另有运行期断言兜底；本条只保证声明侧不被人顺手删掉。</p>
     */
    @Test
    void confirmDeclaresReadCommitted() throws Exception {
        var m = WaterAbnormalReconcileTxServiceImpl.class.getMethod(
                "confirm", Long.class, String.class, Long.class, String.class);
        var tx = m.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertNotNull(tx, "核账确认必须显式声明 @Transactional");
        assertEquals(org.springframework.transaction.annotation.Isolation.READ_COMMITTED, tx.isolation(),
                "RR 下账本聚合会读到事务首条 SELECT 时冻结的旧视图，等于拿过期账本确认终态");
    }
}
