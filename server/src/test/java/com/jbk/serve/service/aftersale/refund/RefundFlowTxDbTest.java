package com.jbk.serve.service.aftersale.refund;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.aftersale.WsAfterSaleActionMapper;
import com.jbk.serve.mapper.aftersale.WsRefundEventMapper;
import com.jbk.serve.mapper.aftersale.WsCardEntitlementBatchMapper;
import com.jbk.serve.mapper.aftersale.WsRefundMapper;
import com.jbk.serve.mapper.trade.TradeCardMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.service.aftersale.IAfterSaleActionTxService;
import com.jbk.serve.service.aftersale.batch.EntitlementLedger;
import com.jbk.serve.service.aftersale.impl.AfterSaleActionTxServiceImpl;
import com.jbk.serve.service.aftersale.refund.IEntitlementRefundTxService;
import com.jbk.serve.service.aftersale.refund.impl.RefundAnomalyRecorder;
import com.jbk.serve.service.aftersale.refund.impl.RefundFactServiceImpl;
import com.jbk.serve.service.aftersale.refund.impl.RefundRequestTxServiceImpl;
import com.jbk.serve.service.aftersale.refund.impl.RefundSimSourceAdapter;
import com.jbk.serve.service.delivery.impl.DeliveryDbSchema;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.aftersale.RefundEnum;
import com.jbk.tool.data.aftersale.po.WsRefund;
import com.jbk.tool.exception.JbkException;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
import org.junit.jupiter.api.extension.ExtendWith;
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
 * 外部退款全链真库测试（E2E-04 包B，真实 MySQL + 真实 Spring 事务）。
 *
 * <p>覆盖任务书场景 12/13/14 与「五类异常事实」：</p>
 * <ul>
 *   <li>场景12 已付款未入账充值单 Refund-Sim 全额退款</li>
 *   <li>场景13 同事实重复三次仍只有一张退款单</li>
 *   <li>场景14 错金额 / 错订单 / 找不到退款单的事实进入人工对账</li>
 *   <li>R0-7 已成功退款不得被迟到失败事实降级</li>
 *   <li>租约过期后事实可被重新认领（崩溃恢复）</li>
 * </ul>
 *
 * <p>用真库而非 Mock 的理由：本包的每一条安全保证最终都落在<b>数据库约束</b>上——
 * {@code uk_refund_after_sale}（一个售后动作至多一张退款单）、
 * {@code uk_refund_event_source_channel_key}（同事实只有一行）、
 * {@code markSuccess} 把金额放进 WHERE 的 CAS。这些在 Mock 里全部是无效的。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = RefundFlowTxDbTest.Ctx.class)
class RefundFlowTxDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_refund_it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static final long USER_ID = 9L;
    private static final long CARD_ID = 100L;
    private static final long ORDER_ID = 600L;
    private static final long PAYMENT_ID = 700L;
    private static final long ACTION_ID = 800L;
    private static final long OP_USER = 5L;
    private static final String ORDER_NO = "WC20260729000600";
    private static final String NOW = "20260729120000";
    /** 已付款未入账异常单的原支付金额；全额退款必须恰等于它。 */
    private static final long PAID_FEN = 10_000L;

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
        MapperFactoryBean<WsRefundMapper> wsRefundMapper(SqlSessionTemplate t) {
            return mapper(WsRefundMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsRefundEventMapper> wsRefundEventMapper(SqlSessionTemplate t) {
            return mapper(WsRefundEventMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsAfterSaleActionMapper> wsAfterSaleActionMapper(SqlSessionTemplate t) {
            return mapper(WsAfterSaleActionMapper.class, t);
        }

        @Bean
        IRefundSourceAdapter refundSourceAdapter() {
            // 测试恒用 Sim：本类要验的是管道，不是装配。装配互斥由 RefundSourceAdapterWiringTest 守。
            return new RefundSimSourceAdapter();
        }

        @Bean
        MapperFactoryBean<WsCardEntitlementBatchMapper> wsCardEntitlementBatchMapper(SqlSessionTemplate t) {
            // 包D-5：建单前要只读回查「批次是否被本动作锁定」，故本上下文必须提供该 Mapper
            return mapper(WsCardEntitlementBatchMapper.class, t);
        }

        @Bean
        IEntitlementRefundTxService entitlementRefundTxService() {
            // 本类验的是退款事实管道（重复/乱序/错金额/迟到失败），不是权益结算。
            // 结算用 Mock，但用例会断言它确实被调用——「退款成功了却没触发权益冲正」
            // 在这里必须能看出来；真实结算由 EntitlementRefundSettleDbTest 用真库钉。
            return Mockito.mock(IEntitlementRefundTxService.class);
        }

        @Bean
        com.jbk.serve.service.settlement.ISplitClawbackTxService splitClawbackTxService() {
            // 同上：分润冲减（D-420）在管道层只验「成功事实必触发冲减」的挂点，
            // 真实回退/扣回/补差由 SplitClawbackDbTest 用真库钉
            return Mockito.mock(com.jbk.serve.service.settlement.ISplitClawbackTxService.class);
        }

        @Bean
        IAfterSaleActionTxService afterSaleActionTxService(WsAfterSaleActionMapper action) {
            return new AfterSaleActionTxServiceImpl(action,
                    Mockito.mock(WsOrderMapper.class), Mockito.mock(TradeCardMapper.class),
                    Mockito.mock(WsWalletFlowMapper.class), Mockito.mock(IWsDomainEventService.class),
                    Mockito.mock(EntitlementLedger.class),
                    org.mockito.Mockito.mock(com.jbk.serve.service.settlement.ISplitClawbackTxService.class));
        }

        @Bean
        RefundRequestTxServiceImpl refundRequestTxService(WsRefundMapper r, WsAfterSaleActionMapper a,
                                                          IRefundSourceAdapter ad,
                                                          WsCardEntitlementBatchMapper b,
                                                          IAfterSaleActionTxService actionTx) {
            return new RefundRequestTxServiceImpl(r, a, ad, b, actionTx);
        }

        @Bean
        RefundAnomalyRecorder refundAnomalyRecorder(WsRefundEventMapper e) {
            return new RefundAnomalyRecorder(e);
        }

        @Bean
        RefundFactServiceImpl refundFactService(WsRefundEventMapper e, WsRefundMapper r,
                                                RefundAnomalyRecorder rec,
                                                IEntitlementRefundTxService settle,
                                                com.jbk.serve.service.settlement.ISplitClawbackTxService clawback) {
            return new RefundFactServiceImpl(e, r, rec, settle, clawback);
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
    private WsRefundMapper refundMapper;
    @Autowired
    private WsRefundEventMapper eventMapper;
    @Autowired
    private IRefundRequestTxService requestTx;
    @Autowired
    private IRefundFactService factService;
    @Autowired
    private IRefundSourceAdapter adapter;
    @Autowired
    private IEntitlementRefundTxService settleTx;

    @BeforeEach
    void reset() {
        Mockito.reset(settleTx);
        DeliveryDbSchema.createAll(jdbc);
        jdbc.execute("DELETE FROM ws_refund_event");
        jdbc.execute("DELETE FROM ws_refund");
        jdbc.execute("DELETE FROM ws_after_sale_action");
        jdbc.execute("DELETE FROM ws_card_entitlement_batch");
        jdbc.execute("DELETE FROM ws_payment");
        jdbc.execute("DELETE FROM ws_order");
        jdbc.execute("DELETE FROM ws_wallet_flow");
        jdbc.execute("DELETE FROM ws_card");
        seedUnsettledPaidOrder();
    }

    /** 造一张「已付款(2) + 订单异常待补偿(6) + 无入账流水 + 无发卡」的单——本期唯一允许外部退款的形状。 */
    private void seedUnsettledPaidOrder() {
        jdbc.update("INSERT INTO ws_order (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, "
                        + "ORDER_NO, ORDER_TYPE, USER_ID, CARD_ID, ORDER_STATUS, ORDER_AMOUNT, PAY_WAY) "
                        + "VALUES (?,0,1,?,1,?,?,2,?,?,6,?,1)",
                ORDER_ID, NOW, NOW, ORDER_NO, USER_ID, CARD_ID, PAID_FEN);
        jdbc.update("INSERT INTO ws_payment (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, "
                        + "ORDER_ID, ORDER_NO, PAY_AMOUNT, PAY_STATUS, PAY_SOURCE, CURRENCY) "
                        + "VALUES (?,0,1,?,1,?,?,?,?,2,2,'CNY')",
                PAYMENT_ID, NOW, NOW, ORDER_ID, ORDER_NO, PAID_FEN);
        jdbc.update("INSERT INTO ws_after_sale_action (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, "
                        + "UPDATE_TIME, AFTER_SALE_NO, SOURCE_TYPE, SOURCE_ID, ORDER_ID, USER_ID, CARD_ID, "
                        + "ACTION_TYPE, ACTION_STATUS, VERSION, RETRY_COUNT) "
                        + "VALUES (?,0,1,?,1,?,?,3,?,?,?,?,3,1,1,0)",
                ACTION_ID, NOW, NOW, "AS" + "0".repeat(28) + "01", ORDER_ID, ORDER_ID, USER_ID, CARD_ID);
    }

    // ==================== 场景12：已付款未入账全额退款 ====================

    /** PC 生产入口原子登记来源4动作与退款单；首次购卡尚无 CARD_ID 也必须可达。 */
    @Test
    void productionEntryCreatesActionAndRefundAtomicallyForPurchaseWithoutCard() {
        jdbc.execute("DELETE FROM ws_after_sale_action");
        jdbc.update("UPDATE ws_order SET CARD_ID=NULL WHERE ID=?", ORDER_ID);

        Long refundId = requestTx.createUnsettledRechargePending(
                ORDER_ID, "核对迟到支付且权益未入账", OP_USER, NOW);

        Map<String, Object> action = row("SELECT * FROM ws_after_sale_action WHERE SOURCE_TYPE=4 AND SOURCE_ID="
                + ORDER_ID);
        WsRefund refund = refundMapper.selectById(refundId);
        assertNull(action.get("CARD_ID"), "首次购卡未发卡时动作 CARD_ID 必须保持空");
        assertEquals(3, ((Number) action.get("ACTION_TYPE")).intValue());
        assertEquals(PAID_FEN, ((Number) action.get("REFUND_PRODUCT_FEN")).longValue());
        assertTrue(String.valueOf(action.get("CALC_SNAPSHOT")).contains("UNSETTLED_FULL"));
        assertEquals(((Number) action.get("ID")).longValue(), refund.getAfterSaleId());
        assertEquals(PAID_FEN, refund.getRefundAmount());
        assertEquals(1, count("ws_refund"));
    }

    /** Refund-Sim 不得为微信来源单先留动作或退款单。 */
    @Test
    void productionEntryRejectsWechatPaymentBeforeAnyPersistentSideEffect() {
        jdbc.execute("DELETE FROM ws_after_sale_action");
        jdbc.update("UPDATE ws_payment SET PAY_SOURCE=1 WHERE ID=?", PAYMENT_ID);

        assertThrows(JbkException.class, () -> requestTx.createUnsettledRechargePending(
                ORDER_ID, "核对迟到支付且权益未入账", OP_USER, NOW));

        assertEquals(0, count("ws_after_sale_action"));
        assertEquals(0, count("ws_refund"));
    }

    /** 请求阶段只应产出一张 1退款中 的单，金额恰为原支付额，来源恒为 Sim（不取自入参）。 */
    @Test
    void scenario12_requestCreatesPendingRefundForFullPaidAmount() {
        Long refundId = requestTx.createPending(ACTION_ID, OP_USER, NOW);
        WsRefund refund = refundMapper.selectById(refundId);

        assertEquals(PAID_FEN, refund.getRefundAmount(), "本路径只支持全额，必须等于原支付金额");
        assertEquals(RefundEnum.RefundStatus.PROCESSING.getValue(), refund.getRefundStatus(),
                "请求阶段绝不能出现 2退款成功——那是 R0-8 明令由事实推进的");
        assertEquals(RefundEnum.Source.REFUND_SIM.getValue(), refund.getRefundSource());
        assertEquals(RefundNo.derive(ACTION_ID), refund.getRefundNo(), "退款单号必须确定性派生");
        assertNull(refund.getProviderRefundId(), "尚未受理时不得有服务方单号");
        assertEquals(1, count("ws_refund"));
    }

    /** 全链：请求 → 受理回填 → SUCCESS 事实 → 退款单落成功，且成功时间取自事实。 */
    @Test
    void scenario12_successFactAdvancesRefundToSuccess() {
        Long refundId = acceptAndReturnId();
        String providerId = refundMapper.selectById(refundId).getProviderRefundId();

        Long eventId = factService.ingest(successFact("EVT-1", providerId, PAID_FEN, ORDER_NO), NOW);
        assertEquals(IRefundFactService.Outcome.PROCESSED, factService.process(eventId, NOW));

        WsRefund refund = refundMapper.selectById(refundId);
        assertEquals(RefundEnum.RefundStatus.SUCCESS.getValue(), refund.getRefundStatus());
        assertEquals("20260729130000", refund.getSuccessTime(),
                "成功时间必须取自事实而非本地时钟——对账要的是支付机构那边的时刻");
        Map<String, Object> event = row("SELECT * FROM ws_refund_event WHERE ID = " + eventId);
        assertEquals(RefundEnum.ProcessingStatus.PROCESSED, event.get("PROCESSING_STATUS"));
        assertEquals(refundId, ((Number) event.get("REFUND_ID")).longValue(), "事实必须回填共键便于双向定位");

        // 包D-5 正向断言：退款单一旦落成功，必须触发权益结算。
        // 删掉 advanceSuccess 里的 settleOnRefundSuccess 那一行，本断言立刻红——
        // 没有它，「钱退了、卡上权益还在」会一路静默到对账日。
        Mockito.verify(settleTx).settleOnRefundSuccess(refundId, NOW);
    }

    /** 幂等：同一售后动作重复请求只有一张退款单（uk_refund_after_sale 的物理保证）。 */
    @Test
    void scenario12_repeatedRequestYieldsExactlyOneRefund() {
        Long first = requestTx.createPending(ACTION_ID, OP_USER, NOW);
        Long second = requestTx.createPending(ACTION_ID, OP_USER, NOW);
        assertEquals(first, second);
        assertEquals(1, count("ws_refund"));
    }

    /** 逻辑删除的退款单仍占唯一键，既不能复用，也不能借“幂等”绕过数据完整性检查。 */
    @Test
    void logicallyDeletedExistingRefundIsRejectedInsteadOfReused() {
        Long refundId = requestTx.createPending(ACTION_ID, OP_USER, NOW);
        jdbc.update("UPDATE ws_refund SET DATA_STATUS=1 WHERE ID=?", refundId);

        JbkException ex = assertThrows(JbkException.class,
                () -> requestTx.createPending(ACTION_ID, OP_USER, NOW));

        assertTrue(ex.getMessage().contains("已删除"), "实际=" + ex.getMessage());
        assertEquals(1, count("ws_refund"), "拒绝路径不得另建退款单");
    }

    /** 同一退款单只能接受同一个服务方单号；不同单号意味着可能已向外发起两笔退款。 */
    @Test
    void conflictingProviderAcceptanceIsRejected() {
        Long refundId = requestTx.createPending(ACTION_ID, OP_USER, NOW);
        requestTx.fillAcceptance(refundId, "SIMRF-FIRST", OP_USER, NOW);
        requestTx.fillAcceptance(refundId, "SIMRF-FIRST", OP_USER, NOW);

        JbkException ex = assertThrows(JbkException.class,
                () -> requestTx.fillAcceptance(refundId, "SIMRF-SECOND", OP_USER, NOW));

        assertTrue(ex.getMessage().contains("服务方退款单号不一致"), "实际=" + ex.getMessage());
        assertEquals("SIMRF-FIRST", refundMapper.selectById(refundId).getProviderRefundId());
    }

    // ==================== 场景13：同事实重复三次 ====================

    /**
     * 同一条事实重复到达三次：事实表恒一行、退款单恒一张、退款只成功一次。
     * 第二三次是幂等命中，不得再次推进。
     */
    @Test
    void scenario13_sameFactThreeTimesYieldsOneEventAndOneRefund() {
        Long refundId = acceptAndReturnId();
        String providerId = refundMapper.selectById(refundId).getProviderRefundId();

        Long firstEvent = null;
        for (int i = 0; i < 3; i++) {
            Long eventId = factService.ingest(successFact("EVT-DUP", providerId, PAID_FEN, ORDER_NO), NOW);
            if (firstEvent == null) {
                firstEvent = eventId;
            }
            assertEquals(firstEvent, eventId, "同事实必须命中同一行（uk_refund_event_source_channel_key）");
            factService.process(eventId, NOW);
        }
        assertEquals(1, count("ws_refund_event"), "同事实重复三次仍只有一行事实");
        assertEquals(1, count("ws_refund"), "同事实重复三次仍只有一张退款单");
        assertEquals(RefundEnum.RefundStatus.SUCCESS.getValue(),
                refundMapper.selectById(refundId).getRefundStatus());
    }

    /** 同键不同正文：只可能是伪造、改写或上游串号。两份都不可信，必须转人工而不是挑一份。 */
    @Test
    void scenario13_sameKeyDifferentBodyIsRejectedAndParked() {
        Long refundId = acceptAndReturnId();
        String providerId = refundMapper.selectById(refundId).getProviderRefundId();
        Long eventId = factService.ingest(successFact("EVT-X", providerId, PAID_FEN, ORDER_NO), NOW);

        RefundFact tampered = new RefundFact(IRefundSourceAdapter.REFUND_SIM,
                RefundEnum.FactChannel.REFUND_SIM, "EVT-X", RefundNo.derive(ACTION_ID), ORDER_NO,
                RefundEnum.FactState.SUCCESS, providerId, PAID_FEN, "CNY", "20260729130000",
                "{\"tampered\":true}", RefundEnum.VerifyMethod.REFUND_SIM_HMAC);
        JbkException ex = assertThrows(JbkException.class, () -> factService.ingest(tampered, NOW));
        assertTrue(ex.getMessage().contains("同键不同正文"), "实际=" + ex.getMessage());

        Map<String, Object> event = row("SELECT * FROM ws_refund_event WHERE ID = " + eventId);
        // 既有事实的处理状态**不得**被这次可疑到达改动：允许改动就等于把
        // 「知道事实键」变成「能冻住任意一笔合法退款」的拒绝服务能力。
        assertEquals(RefundEnum.ProcessingStatus.PENDING, event.get("PROCESSING_STATUS"),
                "可疑到达不得降级既有合法事实的状态");
        assertTrue(String.valueOf(event.get("LAST_ERROR")).contains("可疑事实"),
                "但异常必须留痕供安全检索，实际=" + event.get("LAST_ERROR"));
        assertEquals(1, count("ws_refund_event"), "可疑到达不得新增行");
        // 合法事实本身仍可正常推进，不受这次攻击影响
        assertEquals(IRefundFactService.Outcome.PROCESSED, factService.process(eventId, NOW));
        assertEquals(RefundEnum.RefundStatus.SUCCESS.getValue(),
                refundMapper.selectById(refundId).getRefundStatus());
    }

    // ==================== 场景14：错误事实进人工对账 ====================

    /** 错金额：绝不用本地金额顶替后记成成功——那等于取消了核对本身。 */
    @Test
    void scenario14_wrongAmountFactGoesToReconciliationAndNeverSucceeds() {
        Long refundId = acceptAndReturnId();
        String providerId = refundMapper.selectById(refundId).getProviderRefundId();

        Long eventId = factService.ingest(
                successFact("EVT-BADAMT", providerId, PAID_FEN + 1, ORDER_NO), NOW);
        assertEquals(IRefundFactService.Outcome.RECONCILIATION, factService.process(eventId, NOW));

        assertEquals(RefundEnum.RefundStatus.PROCESSING.getValue(),
                refundMapper.selectById(refundId).getRefundStatus(), "错金额事实不得把退款记成成功");
        Map<String, Object> event = row("SELECT * FROM ws_refund_event WHERE ID = " + eventId);
        assertEquals(RefundEnum.ProcessingStatus.RECONCILIATION_REQUIRED, event.get("PROCESSING_STATUS"));
        assertTrue(String.valueOf(event.get("LAST_ERROR")).contains("金额"), "错因必须点明是金额不符");
    }

    /** 错订单：事实携带的订单号与退款单不符。 */
    @Test
    void scenario14_wrongOrderNoFactGoesToReconciliation() {
        Long refundId = acceptAndReturnId();
        String providerId = refundMapper.selectById(refundId).getProviderRefundId();

        Long eventId = factService.ingest(
                successFact("EVT-BADORDER", providerId, PAID_FEN, "WC-SOME-OTHER-ORDER"), NOW);
        assertEquals(IRefundFactService.Outcome.RECONCILIATION, factService.process(eventId, NOW));
        assertEquals(RefundEnum.RefundStatus.PROCESSING.getValue(),
                refundMapper.selectById(refundId).getRefundStatus());
    }

    /** 找不到对应退款单：绝不「顺手建一张」——那会让外部报文凭空创建出账记录。 */
    @Test
    void scenario14_factForUnknownRefundNeverCreatesRefund() {
        Long eventId = factService.ingest(new RefundFact(IRefundSourceAdapter.REFUND_SIM,
                RefundEnum.FactChannel.REFUND_SIM, "EVT-GHOST", "RF" + "0".repeat(30), ORDER_NO,
                RefundEnum.FactState.SUCCESS, "SIMRFGHOST", PAID_FEN, "CNY", "20260729130000",
                "{\"ghost\":true}", RefundEnum.VerifyMethod.REFUND_SIM_HMAC), NOW);
        assertEquals(IRefundFactService.Outcome.RECONCILIATION, factService.process(eventId, NOW));
        assertEquals(0, count("ws_refund"), "外部事实不得凭空创建退款单");
    }

    /** 服务方单号不符：受理时回填的号与事实携带的号必须一致。 */
    @Test
    void scenario14_wrongProviderRefundIdGoesToReconciliation() {
        Long refundId = acceptAndReturnId();
        Long eventId = factService.ingest(
                successFact("EVT-BADPROVIDER", "SIMRF-IMPOSTOR", PAID_FEN, ORDER_NO), NOW);
        assertEquals(IRefundFactService.Outcome.RECONCILIATION, factService.process(eventId, NOW));
        assertEquals(RefundEnum.RefundStatus.PROCESSING.getValue(),
                refundMapper.selectById(refundId).getRefundStatus());
    }

    // ==================== R0-7：已成功不得被迟到失败降级 ====================

    @Test
    void lateFailureFactMustNotDowngradeSucceededRefund() {
        Long refundId = acceptAndReturnId();
        String providerId = refundMapper.selectById(refundId).getProviderRefundId();
        Long ok = factService.ingest(successFact("EVT-OK", providerId, PAID_FEN, ORDER_NO), NOW);
        factService.process(ok, NOW);
        assertEquals(RefundEnum.RefundStatus.SUCCESS.getValue(),
                refundMapper.selectById(refundId).getRefundStatus());

        Long late = factService.ingest(new RefundFact(IRefundSourceAdapter.REFUND_SIM,
                RefundEnum.FactChannel.REFUND_SIM, "EVT-LATE-FAIL", RefundNo.derive(ACTION_ID), ORDER_NO,
                RefundEnum.FactState.CLOSED, providerId, PAID_FEN, null, null,
                "{\"late\":\"closed\"}", RefundEnum.VerifyMethod.REFUND_SIM_HMAC), NOW);
        assertEquals(IRefundFactService.Outcome.PROCESSED, factService.process(late, NOW),
                "迟到的失败事实本身不是错误，只是来晚了——应标记已处理而不是叫醒运营");

        assertEquals(RefundEnum.RefundStatus.SUCCESS.getValue(),
                refundMapper.selectById(refundId).getRefundStatus(),
                "R0-7：已成功的退款不得被迟到失败事实降级，否则账面显示钱没退而钱已出去");
    }

    // ==================== 崩溃恢复：租约 ====================

    /**
     * 处理者崩溃后事实停在 2处理中；租约到期前不可被再认领，到期后可以。
     * 没有这一支，那笔退款就永远无人推进。
     */
    @Test
    void expiredLeaseAllowsReclaimWhileLiveLeaseBlocksIt() {
        Long refundId = acceptAndReturnId();
        String providerId = refundMapper.selectById(refundId).getProviderRefundId();
        Long eventId = factService.ingest(successFact("EVT-LEASE", providerId, PAID_FEN, ORDER_NO), NOW);

        // 模拟「已认领但尚未落终态」的崩溃现场
        jdbc.update("UPDATE ws_refund_event SET PROCESSING_STATUS = 2, CLAIM_TIME = ?, LEASE_UNTIL = ? WHERE ID = ?",
                NOW, "20260729120200", eventId);
        assertEquals(IRefundFactService.Outcome.NOT_CLAIMED, factService.process(eventId, "20260729120100"),
                "租约未到期时不得被别人抢走，否则两个处理者会同时推进同一笔退款");

        assertEquals(IRefundFactService.Outcome.PROCESSED, factService.process(eventId, "20260729120300"),
                "租约到期后必须能被接手，否则崩溃一次这笔退款就永久卡死");
        assertEquals(RefundEnum.RefundStatus.SUCCESS.getValue(),
                refundMapper.selectById(refundId).getRefundStatus());
    }

    // ==================== 准入闸在真库上的行为 ====================

    /** 已有入账流水（权益已发）时拒绝——本期外部退款只服务「已付款未入账」。 */
    @Test
    void refundIsRejectedOnceRechargeFlowExists() {
        jdbc.update("INSERT INTO ws_wallet_flow (DATA_STATUS, CREATE_BY, CREATE_TIME, CARD_ID, USER_ID, "
                        + "FLOW_TYPE, AMOUNT_CHANGE, ML_CHANGE, AMOUNT_AFTER, ML_AFTER, ORDER_ID, "
                        + "BIZ_IDEMPOTENCY_KEY) VALUES (0,1,?,?,?,1,0,500000,0,500000,?,?)",
                NOW, CARD_ID, USER_ID, ORDER_ID, "RECHARGE:" + ORDER_NO);
        JbkException ex = assertThrows(JbkException.class,
                () -> requestTx.createPending(ACTION_ID, OP_USER, NOW));
        assertTrue(ex.getMessage().contains("已存在充值入账流水"), "实际=" + ex.getMessage());
        assertEquals(0, count("ws_refund"), "拒绝时必须零副作用");
    }

    /** 已发出水卡时同样拒绝——与入账流水是两道独立的闸。 */
    @Test
    void refundIsRejectedOnceCardIssuedByThisOrder() {
        jdbc.update("INSERT INTO ws_card (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, "
                        + "CARD_NO, USER_ID, BALANCE_AMOUNT, BALANCE_ML, CARD_STATUS, ISSUE_ORDER_ID) "
                        + "VALUES (?,0,1,?,1,?,?,?,0,500000,1,?)",
                CARD_ID, NOW, NOW, "VC-ISSUED-BY-ORDER", USER_ID, ORDER_ID);
        JbkException ex = assertThrows(JbkException.class,
                () -> requestTx.createPending(ACTION_ID, OP_USER, NOW));
        assertTrue(ex.getMessage().contains("已发出水卡"), "实际=" + ex.getMessage());
        assertEquals(0, count("ws_refund"), "拒绝时必须零副作用");
    }

    /** 订单不是异常待补偿时拒绝。 */
    @Test
    void refundIsRejectedWhenOrderIsNotAbnormal() {
        jdbc.update("UPDATE ws_order SET ORDER_STATUS = 4 WHERE ID = ?", ORDER_ID);
        JbkException ex = assertThrows(JbkException.class,
                () -> requestTx.createPending(ACTION_ID, OP_USER, NOW));
        assertTrue(ex.getMessage().contains("不是异常待补偿状态"), "实际=" + ex.getMessage());
        assertEquals(0, count("ws_refund"));
    }

    /** Refund-Sim 不得处理微信支付单；退款来源必须与原支付来源严格同源。 */
    @Test
    void refundSourceMustMatchOriginalPaymentSource() {
        jdbc.update("UPDATE ws_payment SET PAY_SOURCE=1 WHERE ID=?", PAYMENT_ID);

        JbkException ex = assertThrows(JbkException.class,
                () -> requestTx.createPending(ACTION_ID, OP_USER, NOW));

        assertTrue(ex.getMessage().contains("退款来源与原支付来源不一致"), "实际=" + ex.getMessage());
        assertEquals(0, count("ws_refund"));
    }

    /** 非「机构退款」类型的售后动作不得写退款单——内部卡内返还禁止伪造 ws_refund。 */
    @Test
    void cardRefundActionTypeMayNotCreateProviderRefund() {
        jdbc.update("UPDATE ws_after_sale_action SET ACTION_TYPE = 1 WHERE ID = ?", ACTION_ID);
        JbkException ex = assertThrows(JbkException.class,
                () -> requestTx.createPending(ACTION_ID, OP_USER, NOW));
        assertTrue(ex.getMessage().contains("不是机构退款类型"), "实际=" + ex.getMessage());
        assertEquals(0, count("ws_refund"));
    }

    /** 批次 PAYMENT_ID 与锁内原支付单错位时，机构退款不得建单。 */
    @Test
    void entitlementBatchPaymentMismatchIsRejectedBeforeRefundCreation() {
        jdbc.update("UPDATE ws_order SET ORDER_STATUS=4 WHERE ID=?", ORDER_ID);
        jdbc.update("UPDATE ws_after_sale_action SET SOURCE_TYPE=4, REFUND_PRODUCT_FEN=?, REFUND_AMOUNT=?, "
                        + "CALC_SNAPSHOT='{\"refundPath\":\"ENTITLEMENT\"}' "
                        + "WHERE ID=?", PAID_FEN, PAID_FEN, ACTION_ID);
        jdbc.update("INSERT INTO ws_card_entitlement_batch(DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,"
                        + "UPDATE_TIME,CARD_ID,USER_ID,SOURCE_TYPE,ORDER_ID,ORDER_NO,PAYMENT_ID,PAY_AMOUNT_FEN,"
                        + "GRANT_AMOUNT_FEN,GRANT_BONUS_FEN,GRANT_WATER_ML,REMAIN_AMOUNT_FEN,REMAIN_WATER_ML,"
                        + "BATCH_STATUS,REFUND_LOCKED_BY,REFUND_LOCK_TIME,REFUNDED_AMOUNT_FEN,VERSION) "
                        + "VALUES(0,1,?,1,?,?,?,2,?,?,?,?,0,0,0,0,0,2,?,?,0,1)",
                NOW, NOW, CARD_ID, USER_ID, ORDER_ID, ORDER_NO, PAYMENT_ID + 1, PAID_FEN,
                ACTION_ID, NOW);

        JbkException ex = assertThrows(JbkException.class,
                () -> requestTx.createPending(ACTION_ID, OP_USER, NOW));
        assertTrue(ex.getMessage().contains("原支付单共键错位"), "实际=" + ex.getMessage());
        assertEquals(0, count("ws_refund"), "支付共键错位不得创建退款单");
        assertEquals(0, count("ws_wallet_flow"), "支付共键错位不得产生资金流水");
        assertEquals(2, jdbc.queryForObject(
                "SELECT BATCH_STATUS FROM ws_card_entitlement_batch WHERE ORDER_ID=?", Integer.class, ORDER_ID));
        assertEquals(1, jdbc.queryForObject(
                "SELECT ACTION_STATUS FROM ws_after_sale_action WHERE ID=?", Integer.class, ACTION_ID));
    }

    // ==================== CAS 谓词直测（绕过 Java 前置检查） ====================
    //
    // 上面的场景用例走的是完整链路，而链路里 crossCheck 的 Java 检查会先把不符的事实拦下，
    // 于是 SQL 里那几条 CAS 谓词**从未被触达**——把它们删掉，那些用例照样全绿。
    // 这一点是靠缺陷注入发现的：删 markSuccess 的金额谓词、删 markNonSuccess 的
    // 「已成功不可降级」前态，15 条场景用例一条都不红。
    //
    // 但这些谓词不是冗余装饰：Java 检查读的是**事务开始时的快照**，并发下两个处理者
    // 可能都通过检查；真正把并发挡住的是 SQL 的原子 CAS。因此必须绕过 Java 层直接打 Mapper。

    /** 事实金额与退款单不符时，markSuccess 必须影响 0 行——绝不用本地金额顶替记成成功。 */
    @Test
    void markSuccessCasRejectsAmountMismatch() {
        Long refundId = acceptAndReturnId();
        WsRefund refund = refundMapper.selectById(refundId);
        int rows = refundMapper.markSuccess(refundId, refund.getVersion(),
                refund.getRefundAmount() + 1, refund.getProviderRefundId(), "20260729130000", OP_USER, NOW);
        assertEquals(0, rows, "金额不符必须影响 0 行");
        assertEquals(RefundEnum.RefundStatus.PROCESSING.getValue(),
                refundMapper.selectById(refundId).getRefundStatus());
    }

    /** 服务方退款单号不符时同样影响 0 行——两个号在服务方那边意味着两笔退款。 */
    @Test
    void markSuccessCasRejectsProviderRefundIdMismatch() {
        Long refundId = acceptAndReturnId();
        WsRefund refund = refundMapper.selectById(refundId);
        int rows = refundMapper.markSuccess(refundId, refund.getVersion(),
                refund.getRefundAmount(), "SIMRF-IMPOSTOR", "20260729130000", OP_USER, NOW);
        assertEquals(0, rows, "服务方单号不符必须影响 0 行");
        assertEquals(RefundEnum.RefundStatus.PROCESSING.getValue(),
                refundMapper.selectById(refundId).getRefundStatus());
    }

    /** 版本不符（并发已推进）时影响 0 行。 */
    @Test
    void markSuccessCasRejectsStaleVersion() {
        Long refundId = acceptAndReturnId();
        WsRefund refund = refundMapper.selectById(refundId);
        int rows = refundMapper.markSuccess(refundId, refund.getVersion() + 99,
                refund.getRefundAmount(), refund.getProviderRefundId(), "20260729130000", OP_USER, NOW);
        assertEquals(0, rows, "版本不符必须影响 0 行");
    }

    /**
     * R0-7 的 SQL 侧保证：已成功的退款单，markNonSuccess 必须影响 0 行。
     * 这条谓词在链路里被 Java 分支遮住，只有直接打 Mapper 才验得到。
     */
    @Test
    void markNonSuccessCasRefusesToDowngradeSucceededRefund() {
        Long refundId = acceptAndReturnId();
        String providerId = refundMapper.selectById(refundId).getProviderRefundId();
        Long ok = factService.ingest(successFact("EVT-CAS-OK", providerId, PAID_FEN, ORDER_NO), NOW);
        factService.process(ok, NOW);

        WsRefund succeeded = refundMapper.selectById(refundId);
        assertEquals(RefundEnum.RefundStatus.SUCCESS.getValue(), succeeded.getRefundStatus());
        int rows = refundMapper.markNonSuccess(refundId, succeeded.getVersion(),
                RefundEnum.RefundStatus.FAILED.getValue(), null, "迟到失败", OP_USER, NOW);
        assertEquals(0, rows, "已成功的退款单不得被降级，SQL 层必须影响 0 行");
        assertEquals(RefundEnum.RefundStatus.SUCCESS.getValue(),
                refundMapper.selectById(refundId).getRefundStatus());
    }

    /** 受理回填的 PROVIDER_REFUND_ID IS NULL 前态：重复受理不得覆盖既有服务方单号。 */
    @Test
    void fillAcceptanceCasRefusesToOverwriteExistingProviderId() {
        Long refundId = acceptAndReturnId();
        WsRefund refund = refundMapper.selectById(refundId);
        String original = refund.getProviderRefundId();
        int rows = refundMapper.fillAcceptance(refundId, refund.getVersion(),
                "SIMRF-SECOND-ATTEMPT", OP_USER, NOW);
        assertEquals(0, rows, "已回填的服务方单号不得被覆盖");
        assertEquals(original, refundMapper.selectById(refundId).getProviderRefundId());
    }

    // ==================== 助手 ====================

    private Long acceptAndReturnId() {
        Long refundId = requestTx.createPending(ACTION_ID, OP_USER, NOW);
        WsRefund refund = refundMapper.selectById(refundId);
        RefundAcceptance acceptance = adapter.acceptRefund(
                refund.getRefundNo(), refund.getOrderNo(), refund.getRefundAmount(), refund.getCurrency());
        requestTx.fillAcceptance(refundId, acceptance.providerRefundId(), OP_USER, NOW);
        return refundId;
    }

    private RefundFact successFact(String eventKey, String providerId, long amountFen, String orderNo) {
        return new RefundFact(IRefundSourceAdapter.REFUND_SIM, RefundEnum.FactChannel.REFUND_SIM,
                eventKey, RefundNo.derive(ACTION_ID), orderNo, RefundEnum.FactState.SUCCESS,
                providerId, amountFen, "CNY", "20260729130000",
                "{\"key\":\"" + eventKey + "\",\"amount\":" + amountFen + "}",
                RefundEnum.VerifyMethod.REFUND_SIM_HMAC);
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }

    private Map<String, Object> row(String sql) {
        return jdbc.queryForMap(sql);
    }
}
