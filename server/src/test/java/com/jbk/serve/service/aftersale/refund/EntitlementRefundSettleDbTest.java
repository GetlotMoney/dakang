package com.jbk.serve.service.aftersale.refund;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.aftersale.WsAfterSaleActionMapper;
import com.jbk.serve.mapper.aftersale.WsCardEntitlementBatchMapper;
import com.jbk.serve.mapper.aftersale.WsEntitlementAllocationMapper;
import com.jbk.serve.mapper.aftersale.WsRefundMapper;
import com.jbk.serve.mapper.trade.TradeCardMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.mapper.user.WsCardMemberMapper;
import com.jbk.serve.service.aftersale.IAfterSaleActionTxService;
import com.jbk.serve.service.aftersale.batch.EntitlementLedger;
import com.jbk.serve.service.aftersale.impl.AfterSaleActionTxServiceImpl;
import com.jbk.serve.service.aftersale.refund.impl.EntitlementRefundTxServiceImpl;
import com.jbk.serve.service.aftersale.refund.impl.RefundSimSourceAdapter;
import com.jbk.serve.service.delivery.impl.DeliveryDbSchema;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.aftersale.AfterSaleEnum.ActionStatus;
import com.jbk.tool.consts.aftersale.RefundEnum;
import com.jbk.tool.data.aftersale.vo.AdminRechargeRefundPreviewVo;
import com.jbk.tool.exception.JbkException;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 充值/购卡退款结算（E2E-04 包D-5，REQ-061）的<b>真实 MySQL + 真实 Spring 事务</b>集成测试。
 *
 * <p>覆盖任务书最低场景 15/16/17/21：</p>
 * <ol>
 *   <li><b>15 未使用充值批次全额退款</b>：可退金额 = 实付，卡上权益全额冲减，订单落 7已退款；</li>
 *   <li><b>16 部分使用后按快照折算</b>：ceil 口径下退款额向下走，订单落 8部分退款；</li>
 *   <li><b>17 首购退款后的卡状态</b>：无其他批次/成员/在途单 ⇒ 注销；有成员授权 ⇒ 保留；</li>
 *   <li><b>21 退款处理中并发消费不得穿透批次锁</b>：受理后批次即 2退款锁定，消费一分都扣不动。</li>
 * </ol>
 *
 * <p>另外钉住三条本包特有的安全性质：账本断裂（批次合计与卡聚合值不符）拒绝受理、
 * 结算幂等（同一笔退款结算两次只冲减一次）、以及退款失败后批次<b>保持锁定</b>不自动解除。</p>
 *
 * <p>无 Docker 环境自动跳过。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = EntitlementRefundSettleDbTest.Ctx.class)
class EntitlementRefundSettleDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_refund_settle_it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static final long USER_ID = 61L;
    private static final long OP_USER = 9L;
    private static final long CARD_ID = 610L;
    private static final long ORDER_ID = 6100L;
    private static final long PAYMENT_ID = 6101L;
    private static final String ORDER_NO = "WC20260729000000000000000000061";
    /** 纯水量套餐：实付 10000 分，发放 500000 mL，永久有效。 */
    private static final long PAY_FEN = 10_000L;
    private static final long GRANT_ML = 500_000L;
    private static final String NOW = "20260729120000";
    private static final String REMARK = "单测：受理充值退款";

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
            // 卡的原子扣减/冲减/注销/有效期重算 SQL 全在生产 XML 里，必须加载同一份
            factory.setMapperLocations(new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                    .getResources("classpath:mapper/**/*.xml"));
            return new SqlSessionTemplate(factory.getObject());
        }

        @Bean
        MapperFactoryBean<WsRefundMapper> wsRefundMapper(SqlSessionTemplate t) {
            return mapper(WsRefundMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsAfterSaleActionMapper> wsAfterSaleActionMapper(SqlSessionTemplate t) {
            return mapper(WsAfterSaleActionMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsCardEntitlementBatchMapper> wsCardEntitlementBatchMapper(SqlSessionTemplate t) {
            return mapper(WsCardEntitlementBatchMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsEntitlementAllocationMapper> wsEntitlementAllocationMapper(SqlSessionTemplate t) {
            return mapper(WsEntitlementAllocationMapper.class, t);
        }

        @Bean
        MapperFactoryBean<TradeCardMapper> tradeCardMapper(SqlSessionTemplate t) {
            return mapper(TradeCardMapper.class, t);
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
        MapperFactoryBean<WsCardMemberMapper> wsCardMemberMapper(SqlSessionTemplate t) {
            return mapper(WsCardMemberMapper.class, t);
        }

        @Bean
        IWsDomainEventService domainEventService() {
            // 本类验资金与状态事实；可靠审计的撞键读回语义由 WsDomainEventTxDbTest 钉
            return Mockito.mock(IWsDomainEventService.class);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.settlement.WsSplitRecordMapper> wsSplitRecordMapper(
                SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.settlement.WsSplitRecordMapper.class, t);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.settlement.WsSplitClawbackMapper> wsSplitClawbackMapper(
                SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.settlement.WsSplitClawbackMapper.class, t);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.settlement.WsSplitClawbackActionMapper> wsSplitClawbackActionMapper(
                SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.settlement.WsSplitClawbackActionMapper.class, t);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.settlement.WsIncomeAccountMapper> wsIncomeAccountMapper(
                SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.settlement.WsIncomeAccountMapper.class, t);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.settlement.WsIncomeFlowMapper> wsIncomeFlowMapper(
                SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.settlement.WsIncomeFlowMapper.class, t);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.delivery.WsDeliveryTaskMapper> wsDeliveryTaskMapper(
                SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.delivery.WsDeliveryTaskMapper.class, t);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.delivery.WsDeliveryAppealMapper> wsDeliveryAppealMapper(
                SqlSessionTemplate t) {
            // R4-P1-1：冲减执行段来源共键校验器需要申诉实体当前读
            return mapper(com.jbk.serve.mapper.delivery.WsDeliveryAppealMapper.class, t);
        }

        @Bean
        com.jbk.serve.service.settlement.ISplitClawbackTxService splitClawbackTxService() {
            // R2-P1-2：真冲减服务替换 Mock——机构退款真实入口的同事务登记是被测性质
            return new com.jbk.serve.service.settlement.impl.SplitClawbackTxServiceImpl();
        }

        @Bean
        EntitlementLedger entitlementLedger(WsCardEntitlementBatchMapper b, WsEntitlementAllocationMapper a,
                                            TradeCardMapper card, WsWalletFlowMapper flow) {
            return new EntitlementLedger(b, a, card, flow);
        }

        @Bean
        IAfterSaleActionTxService afterSaleActionTxService(WsAfterSaleActionMapper action, WsOrderMapper order,
                                                          TradeCardMapper card, WsWalletFlowMapper flow,
                                                          IWsDomainEventService event, EntitlementLedger ledger,
                                                          com.jbk.serve.service.settlement.ISplitClawbackTxService clawback) {
            // 真实现：售后号派生、uk_after_sale_source 幂等与状态机起点都要真的落库
            return new AfterSaleActionTxServiceImpl(action, order, card, flow, event, ledger, clawback);
        }

        @Bean
        IEntitlementRefundTxService entitlementRefundTxService(
                WsRefundMapper refund, WsAfterSaleActionMapper action, WsCardEntitlementBatchMapper batch,
                WsEntitlementAllocationMapper alloc, TradeCardMapper card, WsOrderMapper order,
                WsWalletFlowMapper flow, WsCardMemberMapper member, IWsDomainEventService event,
                com.jbk.serve.service.settlement.ISplitClawbackTxService clawback,
                IAfterSaleActionTxService actionTx, IRefundSourceAdapter refundSourceAdapter) {
            // R2-P1-2：真冲减登记随退款成功同事务（不再 Mock）
            return new EntitlementRefundTxServiceImpl(refund, action, batch, alloc, card, order,
                    flow, member, event, clawback, actionTx, refundSourceAdapter);
        }

        @Bean
        IRefundSourceAdapter refundSourceAdapter() {
            return new RefundSimSourceAdapter();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }

        private static <M> MapperFactoryBean<M> mapper(Class<M> type, SqlSessionTemplate template) {
            MapperFactoryBean<M> bean = new MapperFactoryBean<>(type);
            bean.setSqlSessionTemplate(template);
            return bean;
        }
    }

    @Autowired
    private IEntitlementRefundTxService refundTx;
    @Autowired
    private EntitlementLedger ledger;
    @Autowired
    private WsCardEntitlementBatchMapper batchMapper;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private IWsDomainEventService domainEventService;
    @Autowired
    private com.jbk.serve.service.settlement.ISplitClawbackTxService clawbackTxService;

    @BeforeEach
    void reset() {
        DeliveryDbSchema.createAll(jdbc);
        DeliveryDbSchema.truncateAll(jdbc);
        jdbc.execute("DELETE FROM ws_payment");
        jdbc.execute("TRUNCATE TABLE ws_refund");
        seedCard(GRANT_ML, 1);
        seedRechargeOrder();
        seedBatch(GRANT_ML);
    }

    // ==================== 场景15：未使用批次全额退款 ====================

    @Test
    void scenario15_unusedBatchRefundsFullAmountAndClosesOrderAsRefunded() {
        Long actionId = refundTx.prepare(ORDER_ID, REMARK, OP_USER, NOW);
        assertNotNull(actionId);
        assertEquals(2, batchStatus(), "受理后批次必须立刻转 2退款锁定，否则退款处理中还能继续消费");
        assertEquals(PAY_FEN, actionAmount(actionId), "未使用 ⇒ 可退金额恰等于实付");

        Long refundId = seedSucceededRefund(actionId, PAY_FEN);
        refundTx.settleOnRefundSuccess(refundId, NOW);

        assertEquals(3, batchStatus(), "批次落 3已退款");
        assertEquals(0L, batchRemainMl(), "剩余权益清零");
        assertEquals(0L, cardMl(), "卡上水量随之冲减");
        assertEquals(7, orderStatus(), "整批分文未动 ⇒ 订单 7已退款");
        assertEquals(ActionStatus.SUCCESS.getValue(), actionStatus(actionId));
        // 唯一负向流水：AFTERSALE:<售后号> 幂等键，双列变动方向必须为负
        Map<String, Object> flow = jdbc.queryForMap(
                "SELECT * FROM ws_wallet_flow WHERE BIZ_IDEMPOTENCY_KEY LIKE 'AFTERSALE:%'");
        assertEquals(-GRANT_ML, ((Number) flow.get("ML_CHANGE")).longValue());
        assertEquals(0L, ((Number) flow.get("AMOUNT_CHANGE")).longValue());
        assertEquals(0L, ((Number) flow.get("ML_AFTER")).longValue());
    }

    /** 结算幂等：同一笔退款结算两次，只冲减一次，第二次直接返回。 */
    @Test
    void settlementIsIdempotentAcrossRepeatedFacts() {
        Long actionId = refundTx.prepare(ORDER_ID, REMARK, OP_USER, NOW);
        Long refundId = seedSucceededRefund(actionId, PAY_FEN);
        refundTx.settleOnRefundSuccess(refundId, NOW);
        refundTx.settleOnRefundSuccess(refundId, NOW);

        assertEquals(0L, cardMl());
        assertEquals(1, count("ws_wallet_flow"), "第二次结算不得再写一条冲减流水");
        assertEquals(ActionStatus.SUCCESS.getValue(), actionStatus(actionId));
    }

    /**
     * D-420 R2-P1-2：GATEWAY_REFUND <b>真实生产入口</b>——退款结算成功的同一事务内
     * 登记恰一条动作级冲减 outbox（冻结 orderId/actionType/refundProductFen，零明细、
     * 零分账读）；执行段对无分账的充值订单以「合法零冲减」完成留痕。
     */
    @Test
    void gatewayRefundRealEntryRegistersActionOutboxInSameTx() {
        Long actionId = refundTx.prepare(ORDER_ID, REMARK, OP_USER, NOW);
        Long refundId = seedSucceededRefund(actionId, PAY_FEN);
        refundTx.settleOnRefundSuccess(refundId, NOW);

        Map<String, Object> outbox = jdbc.queryForMap(
                "SELECT * FROM ws_split_clawback_action WHERE ACTION_ID=?", actionId);
        assertEquals(ORDER_ID, ((Number) outbox.get("ORDER_ID")).longValue(), "orderId 登记时冻结");
        assertEquals(3, ((Number) outbox.get("ACTION_TYPE")).intValue(), "类型冻结=机构退款");
        assertEquals(PAY_FEN, ((Number) outbox.get("REFUND_PRODUCT_FEN")).longValue(), "基数=本次实退额");
        assertEquals(1, ((Number) outbox.get("OUTBOX_STATUS")).intValue(), "登记态待处理");
        assertEquals(0, count("ws_split_clawback"), "登记段零明细——分摊全在执行段（R2-P0-1）");

        clawbackTxService.processAction(actionId, NOW);
        assertEquals(2, jdbc.queryForObject(
                "SELECT OUTBOX_STATUS FROM ws_split_clawback_action WHERE ACTION_ID=?",
                Integer.class, actionId), "充值订单无分账行：合法零冲减完成留痕");
    }

    // ==================== 场景16：部分使用按快照折算 ====================

    /**
     * 用掉 1/5 水量（100000 mL）后退款：
     * usedPrincipal = ceil(10000 × 100000 ÷ 500000) = 2000，可退 8000；订单落 8部分退款。
     */
    @Test
    void scenario16_partiallyUsedBatchRefundsByFrozenFormula() {
        consume(100_000L);
        assertEquals(400_000L, batchRemainMl());

        Long actionId = refundTx.prepare(ORDER_ID, REMARK, OP_USER, NOW);
        assertEquals(8_000L, actionAmount(actionId), "ceil 让已用本金向上走、退款额向下走，差额不超过 1 分");

        Long refundId = seedSucceededRefund(actionId, 8_000L);
        refundTx.settleOnRefundSuccess(refundId, NOW);

        assertEquals(0L, cardMl(), "剩余的 400000 mL 随退款一并冲减");
        assertEquals(8, orderStatus(), "被用过一部分 ⇒ 订单 8部分退款");
        assertEquals(-400_000L, jdbc.queryForObject(
                "SELECT ML_CHANGE FROM ws_wallet_flow WHERE BIZ_IDEMPOTENCY_KEY LIKE 'AFTERSALE:%'", Long.class),
                "冲减量是批次剩余，不是可退金额——两者是不同维度");
    }

    /** 权益已用尽：可退金额为 0，不建退款单也不受理。 */
    @Test
    void fullyConsumedBatchIsRejectedInsteadOfIssuingZeroRefund() {
        consume(GRANT_ML);
        JbkException ex = assertThrows(JbkException.class, () -> refundTx.prepare(ORDER_ID, REMARK, OP_USER, NOW));
        assertTrue(ex.getMessage().contains("可退金额为 0"), "实际=" + ex.getMessage());
        assertEquals(1, batchStatus(), "受理失败不得留下退款锁定");
    }

    // ==================== 场景17：首购退款后的卡状态 ====================

    @Test
    void scenario17_firstPurchaseRefundClosesCardWhenNothingElseRemains() {
        Long actionId = refundTx.prepare(ORDER_ID, REMARK, OP_USER, NOW);
        refundTx.settleOnRefundSuccess(seedSucceededRefund(actionId, PAY_FEN), NOW);

        assertEquals(4, cardStatus(), "无其他批次/成员/在途单且权益归零 ⇒ 卡转注销");
        assertEquals(1, count("ws_card"), "注销只改状态，绝不删卡");
    }

    @Test
    void scenario17_cardIsKeptWhenAnActiveMemberStillExists() {
        jdbc.update("INSERT INTO ws_card_member(DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,"
                + "CARD_ID,MEMBER_USER_ID,MEMBER_NAME,MEMBER_STATUS) VALUES(0,1,?,1,?,?,?,'家属',1)",
                NOW, NOW, CARD_ID, 62L);

        Long actionId = refundTx.prepare(ORDER_ID, REMARK, OP_USER, NOW);
        refundTx.settleOnRefundSuccess(seedSucceededRefund(actionId, PAY_FEN), NOW);

        assertEquals(1, cardStatus(), "成员还挂在这张卡上，注销会让他们无声失去用水能力");
        assertEquals(0L, cardMl(), "但权益仍必须冲减干净");
    }

    /** 异常待补偿订单仍可能向卡回补权益，因此首购退款后必须保留这张卡。 */
    @Test
    void scenario17_cardIsKeptWhenAbnormalOrderStillNeedsCompensation() {
        long abnormalOrderId = ORDER_ID + 1;
        jdbc.update("INSERT INTO ws_order(ID,DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,"
                        + "ORDER_NO,ORDER_TYPE,USER_ID,CARD_ID,ORDER_AMOUNT,PAY_WAY,ORDER_STATUS) "
                        + "VALUES(?,0,1,?,1,?,?,1,?,?,0,3,6)",
                abnormalOrderId, NOW, NOW, "WT-ABNORMAL-610", USER_ID, CARD_ID);

        AdminRechargeRefundPreviewVo preview = refundTx.preview(ORDER_ID);
        assertEquals(Boolean.FALSE, preview.getCardWillClose(),
                "异常待补偿单仍在时，预览不得承诺注销水卡");

        Long actionId = refundTx.prepare(ORDER_ID, REMARK, OP_USER, NOW);
        refundTx.settleOnRefundSuccess(seedSucceededRefund(actionId, PAY_FEN), NOW);

        assertEquals(1, cardStatus(), "异常单后续还需退差入卡，水卡必须保持可用");
        assertEquals(0L, cardMl(), "当前退款批次权益仍应冲减干净");
        assertEquals(6, jdbc.queryForObject("SELECT ORDER_STATUS FROM ws_order WHERE ID=?",
                Integer.class, abnormalOrderId), "保留水卡不得顺带推进其他异常订单");
    }

    // ==================== 场景21：退款处理中并发消费不得穿透批次锁 ====================

    @Test
    void scenario21_consumeCannotPierceRefundLock() {
        refundTx.prepare(ORDER_ID, REMARK, OP_USER, NOW);
        assertEquals(2, batchStatus());

        JbkException ex = assertThrows(JbkException.class, () -> consume(1_000L));
        assertTrue(ex.getMessage().contains("批次剩余不足"), "实际=" + ex.getMessage());
        assertEquals(GRANT_ML, batchRemainMl(), "锁定期间批次一分都不能被扣");
    }

    /**
     * 审计幂等键长度守卫：BIZ_IDEMPOTENCY_KEY 是 varchar(64)，前缀+32 位售后号超长会
     * data truncation 整体回滚受理；事件服务被 Mock 时长度无人检查，故在此显式断言。
     */
    @Test
    void auditIdempotencyKeysFitTheColumn() {
        refundTx.prepare(ORDER_ID, REMARK, OP_USER, NOW);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        Mockito.verify(domainEventService, Mockito.atLeastOnce()).recordReliableOnce(
                Mockito.any(), Mockito.anyString(), keys.capture(), Mockito.any(), Mockito.anyString());
        for (String key : keys.getAllValues()) {
            assertTrue(key.length() <= 64, "审计幂等键超出 varchar(64)：" + key.length() + " 位 " + key);
        }
    }

    // ==================== 只读预览：运营点下去之前唯一的依据 ====================

    /** 预览与受理必须给出同一个金额：不一致意味着「看到的」和「退掉的」是两回事。 */
    @Test
    void previewAgreesWithAcceptanceOnEveryAmount() {
        consume(100_000L);
        AdminRechargeRefundPreviewVo pre = refundTx.preview(ORDER_ID);

        assertEquals(Boolean.TRUE, pre.getRefundable());
        assertEquals(8_000L, pre.getRefundableFen(), "ceil 折算：10000 − ceil(10000×100000÷500000)");
        assertEquals(400_000L, pre.getReverseMl(), "冲减量是批次剩余，不是可退金额");
        assertEquals(Boolean.TRUE, pre.getWaterPackage());
        assertEquals(100_000L, pre.getUsedWaterMl());
        assertEquals(Boolean.TRUE, pre.getCardWillClose(),
                "首购且卡上无其他批次/成员/在途单 ⇒ 应预告注销，运营点之前就该看到");

        Long actionId = refundTx.prepare(ORDER_ID, REMARK, OP_USER, NOW);
        assertEquals(pre.getRefundableFen(), actionAmount(actionId), "受理额度必须等于预览额度");
    }

    /** 预览不加锁、不写库：看一眼不该把消费路径堵住，也不该留下任何痕迹。 */
    @Test
    void previewNeitherLocksBatchNorWritesAnything() {
        refundTx.preview(ORDER_ID);

        assertEquals(1, batchStatus(), "预览不得锁定批次");
        assertEquals(0, count("ws_after_sale_action"), "预览不得登记动作");
        assertEquals(0, count("ws_wallet_flow"), "预览不得写流水");
    }

    /** 不可退的原因必须原样下发而不是抛异常：四类原因各自对应不同的人工动作。 */
    @Test
    void previewReportsBlockReasonInsteadOfThrowing() {
        // ① 历史聚合权益：无法归属到充值订单，只能人工
        jdbc.update("UPDATE ws_card_entitlement_batch SET SOURCE_TYPE=3, PAY_AMOUNT_FEN=0 WHERE ORDER_ID=?", ORDER_ID);
        AdminRechargeRefundPreviewVo legacy = refundTx.preview(ORDER_ID);
        assertEquals(Boolean.FALSE, legacy.getRefundable());
        assertTrue(legacy.getBlockReason().contains("无法归属"), "实际=" + legacy.getBlockReason());

        // ② 账本断裂：批次合计与卡聚合值不符
        jdbc.update("UPDATE ws_card_entitlement_batch SET SOURCE_TYPE=1, PAY_AMOUNT_FEN=? WHERE ORDER_ID=?",
                PAY_FEN, ORDER_ID);
        jdbc.update("UPDATE ws_card SET BALANCE_ML = BALANCE_ML + 1 WHERE ID=?", CARD_ID);
        AdminRechargeRefundPreviewVo broken = refundTx.preview(ORDER_ID);
        assertEquals(Boolean.FALSE, broken.getRefundable());
        assertTrue(broken.getBlockReason().contains("账本断裂"), "实际=" + broken.getBlockReason());

        // ③ 订单不存在：同样是对象 + 原因，不是异常
        AdminRechargeRefundPreviewVo missing = refundTx.preview(999_999L);
        assertEquals(Boolean.FALSE, missing.getRefundable());
        assertTrue(missing.getBlockReason().contains("订单不存在"), "实际=" + missing.getBlockReason());
    }

    // ==================== 场景18：同一结算 20 线程并发只执行一次 ====================

    /**
     * 20 线程并发结算同一笔退款只冲减一次（任务书场景18）：串行幂等证明不了并发，
     * 真正的闸是 claimForExecute 状态+版本 CAS、settleRefunded 的 REFUND_LOCKED_BY 前态、
     * uk_wallet_flow_biz_key 三道库层防线。
     */
    @Test
    void scenario18_twentyThreadSettlementRunsExactlyOnce() throws Exception {
        Long actionId = refundTx.prepare(ORDER_ID, REMARK, OP_USER, NOW);
        Long refundId = seedSucceededRefund(actionId, PAY_FEN);

        int threads = 20;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    try {
                        refundTx.settleOnRefundSuccess(refundId, NOW);
                        return true;
                    } catch (RuntimeException rejected) {
                        return false;
                    }
                }));
            }
            int winners = 0;
            for (Future<Boolean> f : futures) {
                if (f.get(60, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertTrue(winners >= 1, "至少要有一个线程真正完成结算");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0L, cardMl(), "卡只被冲减一次");
        assertEquals(1, count("ws_wallet_flow"), "冲减流水恰一条（uk_wallet_flow_biz_key 的物理保证）");
        assertEquals(3, batchStatus());
        assertEquals(ActionStatus.SUCCESS.getValue(), actionStatus(actionId));
        assertEquals(7, orderStatus());
    }

    // ==================== 本包特有的安全性质 ====================

    /** 账本断裂（批次合计 ≠ 卡聚合值）时拒绝受理：这是钱流出系统前的最后一道对账闸。 */
    @Test
    void brokenLedgerBlocksAcceptance() {
        jdbc.update("UPDATE ws_card SET BALANCE_ML = BALANCE_ML + 1 WHERE ID=?", CARD_ID);
        JbkException ex = assertThrows(JbkException.class, () -> refundTx.prepare(ORDER_ID, REMARK, OP_USER, NOW));
        assertTrue(ex.getMessage().contains("账本断裂"), "实际=" + ex.getMessage());
        assertEquals(1, batchStatus(), "拒绝受理不得留下退款锁定");
    }

    /** 批次卡主错位时必须在登记动作和锁定批次之前拒绝。 */
    @Test
    void mismatchedBatchUserIsRejectedWithZeroSideEffect() {
        jdbc.update("UPDATE ws_card_entitlement_batch SET USER_ID=? WHERE ORDER_ID=?", USER_ID + 1, ORDER_ID);

        assertLinkMismatchRejected();
    }

    /** 批次订单号错位不能只凭 ORDER_ID 命中后继续退款。 */
    @Test
    void mismatchedBatchOrderNoIsRejectedWithZeroSideEffect() {
        jdbc.update("UPDATE ws_card_entitlement_batch SET ORDER_NO='RC-OTHER' WHERE ORDER_ID=?", ORDER_ID);

        assertLinkMismatchRejected();
    }

    /**
     * 跨卡批次即使通过另一条 legacy 批次维持了「目标卡批次合计=卡余额」，也必须按共键拒绝。
     * 该形状证明卡级合计不变式不能替代订单/批次的逐字段关联校验。
     */
    @Test
    void crossCardBatchCannotHideBehindIntactAggregateLedger() {
        long otherCardId = CARD_ID + 1;
        jdbc.update("UPDATE ws_card_entitlement_batch SET CARD_ID=? WHERE ORDER_ID=?", otherCardId, ORDER_ID);
        jdbc.update("INSERT INTO ws_card_entitlement_batch(DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,"
                        + "UPDATE_TIME,CARD_ID,USER_ID,SOURCE_TYPE,PAY_AMOUNT_FEN,GRANT_AMOUNT_FEN,"
                        + "GRANT_BONUS_FEN,GRANT_WATER_ML,REMAIN_AMOUNT_FEN,REMAIN_WATER_ML,"
                        + "BATCH_STATUS,REFUNDED_AMOUNT_FEN,VERSION) "
                        + "VALUES(0,1,?,1,?,?,?,3,0,0,0,?,?,?,6,0,1)",
                NOW, NOW, CARD_ID, USER_ID, GRANT_ML, 0L, GRANT_ML);
        assertEquals(GRANT_ML, batchMapper.sumRemainMlByCard(CARD_ID),
                "对抗前提：目标卡的聚合不变式仍成立，拒绝必须来自共键守卫");

        assertLinkMismatchRejected();
    }

    /** 逻辑删除的资金批次仍占唯一键，但绝不能成为退款依据。 */
    @Test
    void logicallyDeletedBatchIsRejectedWithZeroSideEffect() {
        jdbc.update("UPDATE ws_card_entitlement_batch SET DATA_STATUS=1 WHERE ORDER_ID=?", ORDER_ID);

        JbkException ex = assertThrows(JbkException.class,
                () -> refundTx.prepare(ORDER_ID, REMARK, OP_USER, NOW));
        assertTrue(ex.getMessage().contains("已删除"), "实际=" + ex.getMessage());
        assertRefundGuardLeftNoWrite();
    }

    /** 受理后若批次支付锚点被污染，结算必须回滚且不得冲减任何权益。 */
    @Test
    void settlementRejectsBatchPaymentMismatchWithZeroSideEffect() {
        Long actionId = refundTx.prepare(ORDER_ID, REMARK, OP_USER, NOW);
        Long refundId = seedSucceededRefund(actionId, PAY_FEN);
        jdbc.update("UPDATE ws_card_entitlement_batch SET PAYMENT_ID=? WHERE ORDER_ID=?",
                PAYMENT_ID + 1, ORDER_ID);

        JbkException ex = assertThrows(JbkException.class,
                () -> refundTx.settleOnRefundSuccess(refundId, NOW));
        assertTrue(ex.getMessage().contains("原支付单共键错位"), "实际=" + ex.getMessage());
        assertEquals(GRANT_ML, cardMl(), "支付锚点错位不得冲减卡权益");
        assertEquals(GRANT_ML, batchRemainMl(), "支付锚点错位不得冲正权益批次");
        assertEquals(2, batchStatus(), "批次必须保持受理后的退款锁定态");
        assertEquals(ActionStatus.PENDING.getValue(), actionStatus(actionId),
                "售后动作不得被错位批次推进");
        assertEquals(4, orderStatus(), "订单不得被错位批次推进为退款终态");
        assertEquals(0, count("ws_wallet_flow"), "支付锚点错位不得产生资金流水");
    }

    /** 退款失败：动作转需人工对账，批次<b>保持</b>退款锁定（不得自动恢复为未退款）。 */
    @Test
    void failedRefundParksActionAndKeepsBatchLocked() {
        Long actionId = refundTx.prepare(ORDER_ID, REMARK, OP_USER, NOW);
        Long refundId = seedRefund(actionId, PAY_FEN, RefundEnum.RefundStatus.FAILED.getValue());

        refundTx.parkOnRefundFailure(refundId, "服务方退款事实：CLOSED", NOW);

        assertEquals(ActionStatus.RECONCILIATION_REQUIRED.getValue(), actionStatus(actionId));
        assertEquals(2, batchStatus(), "失败不自动解锁：可能已在服务方侧出款，解锁就是双花窗口");
        assertEquals(GRANT_ML, cardMl(), "失败路径零资金写入");
    }

    /** 未成功的退款单不得结算：资格只来自 ws_refund 的成功状态。 */
    @Test
    void settlementRefusesRefundThatIsNotSucceeded() {
        Long actionId = refundTx.prepare(ORDER_ID, REMARK, OP_USER, NOW);
        Long refundId = seedRefund(actionId, PAY_FEN, RefundEnum.RefundStatus.PROCESSING.getValue());

        JbkException ex = assertThrows(JbkException.class, () -> refundTx.settleOnRefundSuccess(refundId, NOW));
        assertTrue(ex.getMessage().contains("不是成功状态"), "实际=" + ex.getMessage());
        assertEquals(GRANT_ML, cardMl());
    }

    /** 真实微信支付单不能在 Refund-Sim 通道中锁批次或登记退款动作。 */
    @Test
    void paymentAndRefundSourceMismatchIsRejectedBeforeEntitlementLock() {
        jdbc.update("UPDATE ws_payment SET PAY_SOURCE=1 WHERE ID=?", PAYMENT_ID);

        JbkException ex = assertThrows(JbkException.class,
                () -> refundTx.prepare(ORDER_ID, REMARK, OP_USER, NOW));

        assertTrue(ex.getMessage().contains("退款来源与原支付来源不一致"), "实际=" + ex.getMessage());
        assertRefundGuardLeftNoWrite();
        assertEquals(1, batchStatus(), "来源错位不得锁定权益批次");
    }

    // ==================== 夹具 ====================

    /** 走真实台账扣减且卡同步扣：只扣批次不扣卡会造出账本断裂的库，红的是夹具而非被测代码。 */
    private void consume(long ml) {
        tx.execute(status -> {
            jdbc.update("UPDATE ws_card SET BALANCE_ML = BALANCE_ML - ? WHERE ID = ?", ml, CARD_ID);
            return ledger.allocateOnConsume(
                    new EntitlementLedger.ConsumeRef(CARD_ID, USER_ID, ORDER_ID + 1, null,
                            "DISPENSE:WT-" + ml), 0L, ml, USER_ID, NOW);
        });
    }

    private void seedCard(long ml, int cardStatus) {
        jdbc.update("DELETE FROM ws_card WHERE ID=?", CARD_ID);
        jdbc.update("INSERT INTO ws_card(ID,DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,"
                        + "CARD_NO,CARD_TYPE,USER_ID,BALANCE_AMOUNT,BALANCE_ML,CARD_STATUS,ISSUE_ORDER_ID) "
                        + "VALUES(?,0,1,?,1,?,'VC-RF-610',1,?,0,?,?,?)",
                CARD_ID, NOW, NOW, USER_ID, ml, cardStatus, ORDER_ID);
    }

    private void seedRechargeOrder() {
        jdbc.update("INSERT INTO ws_order(ID,DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,"
                        + "ORDER_NO,ORDER_TYPE,USER_ID,CARD_ID,ORDER_AMOUNT,PAY_WAY,ORDER_STATUS) "
                        + "VALUES(?,0,1,?,1,?,?,2,?,?,?,1,4)",
                ORDER_ID, NOW, NOW, ORDER_NO, USER_ID, CARD_ID, PAY_FEN);
        jdbc.update("INSERT INTO ws_payment(ID,DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,"
                        + "ORDER_ID,ORDER_NO,PAY_AMOUNT,PAY_STATUS,PAY_SOURCE,CURRENCY) "
                        + "VALUES(?,0,1,?,1,?,?,?,?,2,2,'CNY')",
                PAYMENT_ID, NOW, NOW, ORDER_ID, ORDER_NO, PAY_FEN);
    }

    /** 首次购卡来源的水量套餐批次：实付 10000 分、发放 500000 mL、永久有效。 */
    private void seedBatch(long remainMl) {
        jdbc.update("INSERT INTO ws_card_entitlement_batch(DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,"
                        + "UPDATE_TIME,CARD_ID,USER_ID,SOURCE_TYPE,ORDER_ID,ORDER_NO,PAYMENT_ID,PAY_AMOUNT_FEN,"
                        + "GRANT_AMOUNT_FEN,GRANT_BONUS_FEN,GRANT_WATER_ML,REMAIN_AMOUNT_FEN,REMAIN_WATER_ML,"
                        + "BATCH_STATUS,REFUNDED_AMOUNT_FEN,VERSION) "
                        + "VALUES(0,1,?,1,?,?,?,1,?,?,?,?,0,0,?,0,?,1,0,1)",
                NOW, NOW, CARD_ID, USER_ID, ORDER_ID, ORDER_NO, PAYMENT_ID, PAY_FEN, GRANT_ML, remainMl);
    }

    private void assertLinkMismatchRejected() {
        JbkException ex = assertThrows(JbkException.class,
                () -> refundTx.prepare(ORDER_ID, REMARK, OP_USER, NOW));
        assertTrue(ex.getMessage().contains("共键错位"), "实际=" + ex.getMessage());
        assertRefundGuardLeftNoWrite();
        assertEquals(1, batchStatus(), "错位批次不得被锁定或推进");
    }

    private void assertRefundGuardLeftNoWrite() {
        assertEquals(GRANT_ML, cardMl(), "拒绝路径不得改动卡权益");
        assertEquals(0, count("ws_after_sale_action"), "拒绝路径不得登记售后动作");
        assertEquals(0, count("ws_refund"), "拒绝路径不得创建退款单");
        assertEquals(0, count("ws_wallet_flow"), "拒绝路径不得写资金流水");
    }

    private Long seedSucceededRefund(Long actionId, long amountFen) {
        return seedRefund(actionId, amountFen, RefundEnum.RefundStatus.SUCCESS.getValue());
    }

    private Long seedRefund(Long actionId, long amountFen, int status) {
        jdbc.update("INSERT INTO ws_refund(DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,"
                        + "REFUND_NO,ORDER_ID,ORDER_NO,PAYMENT_ID,AFTER_SALE_ID,REFUND_AMOUNT,"
                        + "REFUND_SOURCE,CURRENCY,REFUND_REASON,REFUND_STATUS,VERSION,RETRY_COUNT) "
                        + "VALUES(0,1,?,1,?,?,?,?,?,?,?,?,'CNY','已入账充值按权益批次折算退款',?,1,0)",
                NOW, NOW, "RF-" + actionId + "-" + status, ORDER_ID, ORDER_NO, PAYMENT_ID, actionId,
                amountFen, RefundEnum.Source.REFUND_SIM.getValue(), status);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long actionAmount(Long actionId) {
        return jdbc.queryForObject("SELECT REFUND_AMOUNT FROM ws_after_sale_action WHERE ID=?",
                Long.class, actionId);
    }

    private int actionStatus(Long actionId) {
        return jdbc.queryForObject("SELECT ACTION_STATUS FROM ws_after_sale_action WHERE ID=?",
                Integer.class, actionId);
    }

    private int batchStatus() {
        return jdbc.queryForObject("SELECT BATCH_STATUS FROM ws_card_entitlement_batch WHERE ORDER_ID=?",
                Integer.class, ORDER_ID);
    }

    private long batchRemainMl() {
        return jdbc.queryForObject("SELECT REMAIN_WATER_ML FROM ws_card_entitlement_batch WHERE ORDER_ID=?",
                Long.class, ORDER_ID);
    }

    private long cardMl() {
        return jdbc.queryForObject("SELECT BALANCE_ML FROM ws_card WHERE ID=?", Long.class, CARD_ID);
    }

    private int cardStatus() {
        return jdbc.queryForObject("SELECT CARD_STATUS FROM ws_card WHERE ID=?", Integer.class, CARD_ID);
    }

    private int orderStatus() {
        return jdbc.queryForObject("SELECT ORDER_STATUS FROM ws_order WHERE ID=?", Integer.class, ORDER_ID);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
