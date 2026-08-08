package com.jbk.serve.service.aftersale.impl;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.aftersale.WsAfterSaleActionMapper;
import com.jbk.serve.mapper.ops.WsDomainEventMapper;
import com.jbk.serve.mapper.aftersale.WsCardEntitlementBatchMapper;
import com.jbk.serve.mapper.aftersale.WsEntitlementAllocationMapper;
import com.jbk.serve.mapper.trade.TradeCardMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.service.aftersale.batch.EntitlementFixture;
import com.jbk.serve.service.delivery.impl.DeliveryDbSchema;
import com.jbk.serve.service.aftersale.batch.EntitlementLedger;
import com.jbk.serve.service.aftersale.AfterSaleNo;
import com.jbk.serve.service.aftersale.AfterSaleTransitions;
import com.jbk.serve.service.aftersale.IAfterSaleActionTxService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.serve.service.ops.impl.WsDomainEventServiceImpl;
import com.jbk.tool.consts.aftersale.AfterSaleEnum.ActionStatus;
import com.jbk.tool.consts.aftersale.AfterSaleEnum.ActionType;
import com.jbk.tool.consts.aftersale.AfterSaleEnum.SourceType;
import com.jbk.tool.consts.aftersale.AfterSaleEnum.StrategyCode;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.consts.user.UserEnum;
import com.jbk.tool.data.aftersale.po.WsAfterSaleAction;
import com.jbk.tool.data.user.po.WsCard;
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E-04 包A 售后返还内核的<b>真实 MySQL + 真实 Spring 事务</b>集成测试。
 *
 * <p>这里钉住的每一条性质都无法用 Mock 证明——被测物本身就是「库层唯一键 + 行锁 + CAS 前态 +
 * 隔离级别 + Spring 回滚」的合力：</p>
 * <ol>
 *   <li><b>正向返还</b>：双支付方式下卡的两列精确变动、<b>混合返还只留一条流水</b>、
 *       AFTER 双列等于卡终值、动作落 SUCCESS、审计事件同事务落库。</li>
 *   <li><b>CAS 前态</b>：{@code refundCardAssets} 的每一条 WHERE 都是一个静默错账的堵口
 *       （余额前态 / 归属 / 注销 / 逻辑删除），而<b>过期卡必须放行</b>、
 *       <b>冻结卡放行但不得被自动解冻</b>——这两条是刻意的口径，不是遗漏。</li>
 *   <li><b>累计封顶（P0）</b>：串行超额拒绝之外，还必须在<b>真并发</b>下只返一次。
 *       这条并发用例保护的正是「RR 快照下封顶失效 ⇒ 同一订单可重复全额返还」那个 P0。</li>
 *   <li><b>幂等与回滚</b>：{@code uk_after_sale_source} 收敛重复登记，
 *       {@code uk_wallet_flow_biz_key} 让重复执行整体回滚，卡只增一次。</li>
 *   <li><b>状态机</b>：认领只认可认领态；终局落痕<b>不依赖调用方版本</b>（claim 已把版本 +1，
 *       调用方手上永远是旧版本，这是本批修的 P0）；重试耗尽升级为需人工对账。</li>
 * </ol>
 *
 * <p>无 Docker 环境自动跳过。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = AfterSaleRefundTxDbTest.Ctx.class)
class AfterSaleRefundTxDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_aftersale_it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static final long USER_ID = 9L;
    private static final long OTHER_USER = 7L;
    private static final long OP_USER = 9L;
    private static final long CARD_ID = 100L;

    /** payWay=2 订单：水费 2600（余额扣）+ 配送费 400（余额扣），原扣款 -3000 / -0 mL。 */
    private static final long ORDER_FEN = 501L;
    private static final String ORDER_NO_FEN = "WD20260729000000000000000000001";
    /** payWay=3 订单：水量 40000mL（水量扣）+ 配送费 400（余额扣），原扣款 -400 / -40000 mL。 */
    private static final long ORDER_ML = 502L;
    private static final String ORDER_NO_ML = "WD20260729000000000000000000002";

    private static final long CAP_PRODUCT_FEN = 2_600L;
    private static final long CAP_SERVICE_FEN = 400L;
    private static final long CAP_PRODUCT_ML = 40_000L;

    private static final long INIT_BALANCE = 10_000L;
    private static final long INIT_ML = 50_000L;

    private static final String NOW = "20260729120000";
    private static final String PAST = "20260728120000";
    private static final String FUTURE = "20260730120000";

    /**
     * 卡余额<b>读到的前态</b>相对库中真值的漂移量（分）。0 = 直通。
     *
     * <p>用于制造「读卡与 UPDATE 之间余额被并发改动」：真并发无法复现该窗口——
     * {@code selectByIdForUpdate} 已持行 X 锁，别的事务物理上插不进来。这里只把<b>读到的前态</b>
     * 拨歪，UPDATE 的 CAS、影响行数校验、抛出与 Spring 回滚全部是真的，被测性质一点没被 Mock 掉。</p>
     */
    private static final AtomicLong CARD_READ_DRIFT = new AtomicLong(0L);

    @Configuration
    @EnableTransactionManagement
    static class Ctx {
        @Bean
        DataSource dataSource() {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl(MYSQL.getJdbcUrl());
            ds.setUsername(MYSQL.getUsername());
            ds.setPassword(MYSQL.getPassword());
            // 并发封顶用例：2 线程 ×（资金事务 + 嵌套 REQUIRES_NEW 落痕/审计）各需独立连接
            ds.setMaximumPoolSize(12);
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
            // 生产同一份 XML：三条状态 CAS、锁定读的额度聚合、refundCardAssets 的前态 WHERE
            // 都是被测物本身，绝不在测试里复刻 SQL
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath:mapper/*/*.xml"));
            return new SqlSessionTemplate(factory.getObject());
        }

        @Bean
        MapperFactoryBean<WsAfterSaleActionMapper> wsAfterSaleActionMapper(SqlSessionTemplate t) {
            return mapper(WsAfterSaleActionMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsOrderMapper> wsOrderMapper(SqlSessionTemplate t) {
            return mapper(WsOrderMapper.class, t);
        }

        @Bean
        MapperFactoryBean<TradeCardMapper> tradeCardMapper(SqlSessionTemplate t) {
            return mapper(TradeCardMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsWalletFlowMapper> wsWalletFlowMapper(SqlSessionTemplate t) {
            return mapper(WsWalletFlowMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsDomainEventMapper> wsDomainEventMapper(SqlSessionTemplate t) {
            return mapper(WsDomainEventMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsCardEntitlementBatchMapper> wsCardEntitlementBatchMapper(SqlSessionTemplate t) {
            // 包D-4：配送创单扣减同事务写权益分摊，故本上下文必须提供这两个 Mapper
            return mapper(WsCardEntitlementBatchMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsEntitlementAllocationMapper> wsEntitlementAllocationMapper(SqlSessionTemplate t) {
            return mapper(WsEntitlementAllocationMapper.class, t);
        }

        @Bean
        EntitlementLedger entitlementLedger(WsCardEntitlementBatchMapper batchMapper,
                                            WsEntitlementAllocationMapper allocationMapper,
                                            TradeCardMapper tradeCardMapper,
                                            WsWalletFlowMapper walletFlowMapper) {
            // 真实台账而不是 Mock：分摊要摊到真表上，才验证得了「卡扣了、批次也扣了」
            return new EntitlementLedger(batchMapper, allocationMapper, tradeCardMapper, walletFlowMapper);
        }

        private static <M> MapperFactoryBean<M> mapper(Class<M> type, SqlSessionTemplate template) {
            MapperFactoryBean<M> bean = new MapperFactoryBean<>(type);
            bean.setSqlSessionTemplate(template);
            return bean;
        }

        @Bean
        IWsDomainEventService domainEventService() {
            // 真实现：审计与资金同事务是被测性质之一（返还回滚时审计不得残留），mock 证不了
            return new WsDomainEventServiceImpl();
        }

        @Bean
        IAfterSaleActionTxService afterSaleActionTxService(WsAfterSaleActionMapper actionMapper,
                                                          WsOrderMapper orderMapper,
                                                          TradeCardMapper tradeCardMapper,
                                                          WsWalletFlowMapper walletFlowMapper,
                                                          IWsDomainEventService domainEventService,
                                                          EntitlementLedger entitlementLedger) {
            // 只把「锁卡读前态」这一次读包了一层（见 CARD_READ_DRIFT），其余一律真实
            return new AfterSaleActionTxServiceImpl(actionMapper, orderMapper,
                    driftingCardReads(tradeCardMapper), walletFlowMapper, domainEventService,
                    entitlementLedger,
                    org.mockito.Mockito.mock(com.jbk.serve.service.settlement.ISplitClawbackTxService.class));
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    /** 只改写 {@code selectByIdForUpdate} 返回对象的余额前态；其余方法逐字转发。 */
    private static TradeCardMapper driftingCardReads(TradeCardMapper real) {
        return (TradeCardMapper) Proxy.newProxyInstance(
                TradeCardMapper.class.getClassLoader(), new Class<?>[]{TradeCardMapper.class},
                (proxy, method, args) -> {
                    Object result;
                    try {
                        result = method.invoke(real, args);
                    } catch (InvocationTargetException wrapped) {
                        throw wrapped.getCause();
                    }
                    long drift = CARD_READ_DRIFT.get();
                    if (drift != 0L && "selectByIdForUpdate".equals(method.getName())
                            && result instanceof WsCard card && card.getBalanceAmount() != null) {
                        card.setBalanceAmount(card.getBalanceAmount() + drift);
                    }
                    return result;
                });
    }

    @Autowired
    private IAfterSaleActionTxService afterSale;
    @Autowired
    private TradeCardMapper tradeCardMapper;
    @Autowired
    private WsAfterSaleActionMapper actionMapper;
    @Autowired
    private JdbcTemplate jdbc;

    // ------------------------------------------------------------------
    // 基座
    // ------------------------------------------------------------------

    @BeforeEach
    void reset() {
        createSchema();
        // 包D-4：返还要同事务回补权益批次，故本类的 schema 也要有这两张表（与生产同源）
        DeliveryDbSchema.createEntitlementTables(jdbc);
        for (String table : new String[]{"ws_domain_event", "ws_after_sale_action",
                "ws_entitlement_allocation", "ws_card_entitlement_batch",
                "ws_wallet_flow", "ws_order", "ws_card"}) {
            jdbc.execute("TRUNCATE TABLE " + table);
        }
        CARD_READ_DRIFT.set(0L);
        seedCard(USER_ID, UserEnum.CardStatus.NORMAL.getValue(), 0);
        seedBalanceOrder();
        seedMlOrder();
    }

    private void createSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_card (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  CARD_NO VARCHAR(32), CARD_TYPE TINYINT, USER_ID BIGINT,
                  BALANCE_AMOUNT BIGINT NOT NULL DEFAULT 0, BALANCE_ML BIGINT NOT NULL DEFAULT 0,
                  PACKAGE_ID BIGINT NULL, PACKAGE_SNAP MEDIUMTEXT NULL, SCOPE_JSON VARCHAR(2000) NULL,
                  EXPIRE_TIME VARCHAR(20) NULL, CARD_STATUS TINYINT, CARD_REMARK VARCHAR(255) NULL,
                  ISSUE_ORDER_ID BIGINT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_order (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  ORDER_NO VARCHAR(64), ORDER_TYPE TINYINT, USER_ID BIGINT,
                  STATION_ID BIGINT, DEVICE_ID BIGINT, OUTLET_ID BIGINT, CARD_ID BIGINT,
                  PACKAGE_ID BIGINT NULL, PACKAGE_SNAP MEDIUMTEXT NULL,
                  PLAN_ML BIGINT NULL, ACTUAL_ML BIGINT NULL, ORDER_AMOUNT BIGINT,
                  PAY_WAY TINYINT, ORDER_STATUS TINYINT, CMD_ID BIGINT NULL,
                  FINISH_TIME VARCHAR(20) NULL, CANCEL_REASON VARCHAR(500) NULL,
                  REFERRER_USER_ID BIGINT NULL,
                  UNIQUE KEY uk_order_no (ORDER_NO)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_wallet_flow (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  CARD_ID BIGINT, USER_ID BIGINT, FLOW_TYPE TINYINT,
                  AMOUNT_CHANGE BIGINT NOT NULL DEFAULT 0, ML_CHANGE BIGINT NOT NULL DEFAULT 0,
                  AMOUNT_AFTER BIGINT NOT NULL, ML_AFTER BIGINT NOT NULL,
                  ORDER_ID BIGINT, FLOW_REMARK VARCHAR(255), BIZ_IDEMPOTENCY_KEY VARCHAR(64) NULL,
                  UNIQUE KEY uk_wallet_flow_biz_key (BIZ_IDEMPOTENCY_KEY)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
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
        // 列、两把唯一键与两条索引严格照抄 deploy/mysql/migrations/2026-07-29-aftersale-e2e04-a.sql：
        // uk_after_sale_source 是「同来源只允许一笔售后」的物理防线且刻意不含 DATA_STATUS；
        // idx_after_sale_order(ORDER_ID, ACTION_STATUS) 正是额度聚合那条锁定读要走的索引，
        // 缺它则 FOR UPDATE 的锁面与生产不同，并发封顶用例测的就不是同一件事。
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_after_sale_action (
                  ID BIGINT NOT NULL AUTO_INCREMENT,
                  DATA_STATUS TINYINT NOT NULL DEFAULT 0,
                  CREATE_BY BIGINT NOT NULL, CREATE_TIME VARCHAR(14) NOT NULL,
                  UPDATE_BY BIGINT NOT NULL, UPDATE_TIME VARCHAR(14) NOT NULL,
                  AFTER_SALE_NO VARCHAR(32) NOT NULL, SOURCE_TYPE TINYINT NOT NULL,
                  SOURCE_ID BIGINT NOT NULL, ORDER_ID BIGINT NOT NULL,
                  USER_ID BIGINT NOT NULL, CARD_ID BIGINT NOT NULL,
                  ACTION_TYPE TINYINT NOT NULL, STRATEGY_CODE VARCHAR(20) NULL,
                  APPROVED_COUNT INT NULL,
                  REFUND_PRODUCT_FEN BIGINT NOT NULL DEFAULT 0,
                  REFUND_SERVICE_FEN BIGINT NOT NULL DEFAULT 0,
                  REFUND_PRODUCT_ML BIGINT NOT NULL DEFAULT 0,
                  REFUND_AMOUNT BIGINT NOT NULL DEFAULT 0,
                  CALC_SNAPSHOT TEXT NULL, ACTION_STATUS TINYINT NOT NULL,
                  VERSION INT NOT NULL DEFAULT 1, RETRY_COUNT INT NOT NULL DEFAULT 0,
                  NEXT_RETRY_TIME VARCHAR(14) NULL,
                  REFUND_ID BIGINT NULL, RESULT_ORDER_ID BIGINT NULL, RESULT_TASK_ID BIGINT NULL,
                  APPROVE_BY BIGINT NULL, APPROVE_TIME VARCHAR(14) NULL,
                  FINISH_TIME VARCHAR(14) NULL, LAST_ERROR VARCHAR(500) NULL,
                  PRIMARY KEY (ID),
                  UNIQUE INDEX uk_after_sale_no (AFTER_SALE_NO),
                  UNIQUE INDEX uk_after_sale_source (SOURCE_TYPE, SOURCE_ID),
                  INDEX idx_after_sale_order (ORDER_ID, ACTION_STATUS),
                  INDEX idx_after_sale_status (ACTION_STATUS, NEXT_RETRY_TIME),
                  INDEX idx_after_sale_card (CARD_ID)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
    }

    private void seedCard(long ownerUserId, int cardStatus, int dataStatus) {
        jdbc.update("DELETE FROM ws_card WHERE ID=?", CARD_ID);
        jdbc.update("INSERT INTO ws_card(ID,DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,"
                        + "CARD_NO,CARD_TYPE,USER_ID,BALANCE_AMOUNT,BALANCE_ML,CARD_STATUS) "
                        + "VALUES(?,?,1,?,1,?,'VC-AS-100',1,?,?,?,?)",
                CARD_ID, dataStatus, NOW, NOW, ownerUserId, INIT_BALANCE, INIT_ML, cardStatus);
        // 包D-4：本类的原扣款流水是手写种子（不走真实创单），故没有分摊行可回补——
        // 这正是「D-4 上线前的历史消费」形状，台账应把返还额并入不可退桶并让不变式恢复
        EntitlementFixture.seedLegacyBatch(jdbc, CARD_ID, ownerUserId, INIT_BALANCE, INIT_ML);
    }

    /** payWay=2：水费与配送费都从余额扣，原扣款流水 -3000 分 / 0 mL。 */
    private void seedBalanceOrder() {
        String snap = "{\"payWay\":2,\"waterAmountFen\":2600,\"deliveryFeeFen\":400,\"waterMl\":40000,"
                + "\"deliveryCount\":2,\"unitWaterPriceFen\":1300,\"deliveryFeePerContainerFen\":200,"
                + "\"priceWaterAmountFen\":2600}";
        seedOrder(ORDER_FEN, ORDER_NO_FEN, TradeEnum.PayWay.CARD_BALANCE.getValue(), 3_000L, snap);
        seedDeductFlow(ORDER_FEN, ORDER_NO_FEN, -3_000L, 0L);
    }

    /** payWay=3：水品走水量、配送费走余额，原扣款流水 -400 分 / -40000 mL。 */
    private void seedMlOrder() {
        String snap = "{\"payWay\":3,\"waterAmountFen\":0,\"deliveryFeeFen\":400,\"waterMl\":40000,"
                + "\"deliveryCount\":2,\"unitWaterPriceFen\":1300,\"deliveryFeePerContainerFen\":200,"
                + "\"priceWaterAmountFen\":2600}";
        seedOrder(ORDER_ML, ORDER_NO_ML, TradeEnum.PayWay.CARD_ML.getValue(), 400L, snap);
        seedDeductFlow(ORDER_ML, ORDER_NO_ML, -400L, -40_000L);
    }

    private void seedOrder(long orderId, String orderNo, int payWay, long orderAmount, String snap) {
        jdbc.update("INSERT INTO ws_order(ID,DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,"
                        + "ORDER_NO,ORDER_TYPE,USER_ID,CARD_ID,PACKAGE_SNAP,ORDER_AMOUNT,PAY_WAY,ORDER_STATUS) "
                        + "VALUES(?,0,1,?,1,?,?,?,?,?,?,?,?,7)",
                orderId, NOW, NOW, orderNo, TradeEnum.OrderType.DELIVERY.getValue(),
                USER_ID, CARD_ID, snap, orderAmount, payWay);
    }

    private void seedDeductFlow(long orderId, String orderNo, long amountChange, long mlChange) {
        jdbc.update("INSERT INTO ws_wallet_flow(DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,"
                        + "CARD_ID,USER_ID,FLOW_TYPE,AMOUNT_CHANGE,ML_CHANGE,AMOUNT_AFTER,ML_AFTER,"
                        + "ORDER_ID,FLOW_REMARK,BIZ_IDEMPOTENCY_KEY) VALUES(0,1,?,1,?,?,?,?,?,?,?,?,?,?,?)",
                NOW, NOW, CARD_ID, USER_ID, TradeEnum.FlowType.DELIVERY_CONSUME.getValue(),
                amountChange, mlChange, INIT_BALANCE, INIT_ML, orderId, "配送扣减 " + orderNo,
                "DELIVERY:" + orderNo);
    }

    /** 直接落一行售后动作（跳过编排层），返回主键；金额合计恒由三个分维求和，与生产口径一致。 */
    private long seedAction(SourceType source, long sourceId, long orderId, ActionType type,
                            String strategyCode, long productFen, long serviceFen, long productMl,
                            ActionStatus status, int version, int retryCount, String nextRetryTime) {
        String no = AfterSaleNo.derive(source.getValue(), sourceId);
        jdbc.update("INSERT INTO ws_after_sale_action(DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,"
                        + "AFTER_SALE_NO,SOURCE_TYPE,SOURCE_ID,ORDER_ID,USER_ID,CARD_ID,ACTION_TYPE,"
                        + "STRATEGY_CODE,REFUND_PRODUCT_FEN,REFUND_SERVICE_FEN,REFUND_PRODUCT_ML,REFUND_AMOUNT,"
                        + "ACTION_STATUS,VERSION,RETRY_COUNT,NEXT_RETRY_TIME) "
                        + "VALUES(0,1,?,1,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                NOW, NOW, no, source.getValue(), sourceId, orderId, USER_ID, CARD_ID, type.getValue(),
                strategyCode, productFen, serviceFen, productMl, productFen + serviceFen,
                status.getValue(), version, retryCount, nextRetryTime);
        return jdbc.queryForObject("SELECT ID FROM ws_after_sale_action WHERE AFTER_SALE_NO=?", Long.class, no);
    }

    /** 已认领待执行（ACTION_STATUS=2执行中, VERSION=2）——executeInTx 的合法入口态。 */
    private long seedProcessing(SourceType source, long sourceId, long orderId, ActionType type,
                                long productFen, long serviceFen, long productMl) {
        return seedAction(source, sourceId, orderId, type, null, productFen, serviceFen, productMl,
                ActionStatus.PROCESSING, 2, 0, null);
    }

    // ------------------------------------------------------------------
    // 读取助手
    // ------------------------------------------------------------------

    private long cardBalance() {
        return jdbc.queryForObject("SELECT BALANCE_AMOUNT FROM ws_card WHERE ID=?", Long.class, CARD_ID);
    }

    private long cardMl() {
        return jdbc.queryForObject("SELECT BALANCE_ML FROM ws_card WHERE ID=?", Long.class, CARD_ID);
    }

    private int cardStatus() {
        return jdbc.queryForObject("SELECT CARD_STATUS FROM ws_card WHERE ID=?", Integer.class, CARD_ID);
    }

    /** 售后返还流水条数（与创单扣款流水分开计，混合返还必须恰一条）。 */
    private int refundFlowCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_wallet_flow WHERE BIZ_IDEMPOTENCY_KEY LIKE 'AFTERSALE:%'", Integer.class);
    }

    private Map<String, Object> refundFlow(long actionId) {
        return jdbc.queryForMap("SELECT * FROM ws_wallet_flow WHERE BIZ_IDEMPOTENCY_KEY=?",
                "AFTERSALE:" + afterSaleNo(actionId));
    }

    private String afterSaleNo(long actionId) {
        return jdbc.queryForObject("SELECT AFTER_SALE_NO FROM ws_after_sale_action WHERE ID=?",
                String.class, actionId);
    }

    private int actionStatus(long actionId) {
        return jdbc.queryForObject("SELECT ACTION_STATUS FROM ws_after_sale_action WHERE ID=?",
                Integer.class, actionId);
    }

    private Map<String, Object> actionRow(long actionId) {
        return jdbc.queryForMap("SELECT * FROM ws_after_sale_action WHERE ID=?", actionId);
    }

    private static long num(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }

    private int doneEventCount(long actionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ws_domain_event WHERE BIZ_IDEMPOTENCY_KEY=? "
                        + "AND EVENT_TYPE=? AND EVENT_KEY=?", Integer.class,
                "AFTERSALE_DONE:" + afterSaleNo(actionId),
                OpsEnum.EventType.AFTER_SALE.getValue(), afterSaleNo(actionId));
    }

    /**
     * 编排层动作序列的复刻（与 {@code DeliveryCancelServiceImpl} 逐行一致）：
     * 认领独立提交 → 资金独立事务 → 失败经独立事务落终局态。
     * <b>刻意沿用 claim 之前的 VERSION</b>——生产的编排层手上永远只有这个旧版本，
     * 终局落痕能不能落住，靠的是内核自己重读版本（本批修的 P0）。
     */
    private boolean orchestrate(long actionId) {
        int preClaimVersion = ((Number) actionRow(actionId).get("VERSION")).intValue();
        if (!afterSale.claimIndependent(actionId, preClaimVersion, OP_USER, NOW)) {
            return false;
        }
        try {
            afterSale.executeInTx(actionId, OP_USER, NOW);
            return true;
        } catch (RuntimeException failed) {
            afterSale.markTerminalIndependent(actionId, preClaimVersion,
                    ActionStatus.RECONCILIATION_REQUIRED.getValue(), null,
                    failed.getMessage(), OP_USER, NOW);
            return false;
        }
    }

    // ==================================================================
    // A：executeInTx 正向
    // ==================================================================

    /**
     * payWay=2 申诉补偿（仅退配送费 400）：余额精确 +400、<b>水量一分不动</b>；
     * 返还流水恰一条且 AMOUNT_CHANGE=+400 / ML_CHANGE=0，AFTER 双列等于卡终值；
     * 动作落已完成并写 FINISH_TIME；审计事件与资金同事务落库。
     */
    @Test
    void balancePayWayAppealCompensationCreditsBalanceOnlyWithSingleFlow() {
        long id = seedProcessing(SourceType.DELIVERY_APPEAL, 8801L, ORDER_FEN,
                ActionType.CARD_COMPENSATE, 0L, CAP_SERVICE_FEN, 0L);

        afterSale.executeInTx(id, OP_USER, NOW);

        assertEquals(INIT_BALANCE + CAP_SERVICE_FEN, cardBalance(), "余额精确 +400");
        assertEquals(INIT_ML, cardMl(), "payWay=2 补偿不得改动水量");

        assertEquals(1, refundFlowCount(), "一笔返还恰一条流水");
        Map<String, Object> flow = refundFlow(id);
        assertEquals(CAP_SERVICE_FEN, num(flow, "AMOUNT_CHANGE"));
        assertEquals(0L, num(flow, "ML_CHANGE"));
        assertEquals(cardBalance(), num(flow, "AMOUNT_AFTER"), "AFTER 金额必须等于卡终值");
        assertEquals(cardMl(), num(flow, "ML_AFTER"), "AFTER 水量必须等于卡终值");
        assertEquals(TradeEnum.FlowType.COMPENSATE.getValue(), num(flow, "FLOW_TYPE"),
                "申诉来源记补偿入账，与取消的退款返还分账口径");
        assertEquals(ORDER_FEN, num(flow, "ORDER_ID"));

        Map<String, Object> action = actionRow(id);
        assertEquals(ActionStatus.SUCCESS.getValue(), num(action, "ACTION_STATUS"));
        assertEquals(3L, num(action, "VERSION"), "markSuccess 必须推进版本");
        assertEquals(NOW, action.get("FINISH_TIME"));
        assertNull(action.get("LAST_ERROR"));

        assertEquals(1, doneEventCount(id), "正向状态审计与资金同事务落库");
    }

    /**
     * payWay=3 申诉补偿（退一桶水品 20000mL + 半数配送费 200）：水量精确 +20000、余额精确 +200，
     * 而<b>流水仍恰一条</b>——混合返还同时记两列，绝不拆成两条（两条会让对账把一次返还看成两笔）。
     */
    @Test
    void mlPayWayAppealCompensationCreditsMlAndFeeInOneDualColumnFlow() {
        long id = seedProcessing(SourceType.DELIVERY_APPEAL, 8802L, ORDER_ML,
                ActionType.CARD_COMPENSATE, 0L, 200L, 20_000L);

        afterSale.executeInTx(id, OP_USER, NOW);

        assertEquals(INIT_ML + 20_000L, cardMl(), "水量精确 +20000");
        assertEquals(INIT_BALANCE + 200L, cardBalance(), "余额精确 +200（配送费）");

        assertEquals(1, refundFlowCount(), "双维返还仍恰一条流水");
        Map<String, Object> flow = refundFlow(id);
        assertEquals(200L, num(flow, "AMOUNT_CHANGE"));
        assertEquals(20_000L, num(flow, "ML_CHANGE"));
        assertEquals(INIT_BALANCE + 200L, num(flow, "AMOUNT_AFTER"));
        assertEquals(INIT_ML + 20_000L, num(flow, "ML_AFTER"));
        assertEquals(ActionStatus.SUCCESS.getValue(), actionStatus(id));
    }

    /**
     * payWay=3 待接单取消的满额返还：四元额度三列与 caps（0 / 400 / 40000）完全一致时放行，
     * 卡的两列各自加满，流水类型是退款返还而非补偿入账。
     */
    @Test
    void cancelFullRefundMatchesAllThreeCapsExactly() {
        long id = seedProcessing(SourceType.DELIVERY_CANCEL, ORDER_ML, ORDER_ML,
                ActionType.CARD_REFUND, 0L, CAP_SERVICE_FEN, CAP_PRODUCT_ML);

        afterSale.executeInTx(id, OP_USER, NOW);

        assertEquals(INIT_BALANCE + CAP_SERVICE_FEN, cardBalance(), "配送费满额返还");
        assertEquals(INIT_ML + CAP_PRODUCT_ML, cardMl(), "水量满额返还");

        Map<String, Object> action = actionRow(id);
        assertEquals(0L, num(action, "REFUND_PRODUCT_FEN"), "payWay=3 的余额侧水费上限恒 0");
        assertEquals(CAP_SERVICE_FEN, num(action, "REFUND_SERVICE_FEN"));
        assertEquals(CAP_PRODUCT_ML, num(action, "REFUND_PRODUCT_ML"));
        assertEquals(CAP_SERVICE_FEN, num(action, "REFUND_AMOUNT"), "合计列只含金额两维");

        Map<String, Object> flow = refundFlow(id);
        assertEquals(TradeEnum.FlowType.REFUND.getValue(), num(flow, "FLOW_TYPE"));
        assertEquals(CAP_SERVICE_FEN, num(flow, "AMOUNT_CHANGE"));
        assertEquals(CAP_PRODUCT_ML, num(flow, "ML_CHANGE"));
        assertEquals(1, refundFlowCount());
    }

    // ==================================================================
    // B：CAS 前态（每条对应 refundCardAssets 的一条 WHERE 或锁内断言）
    // ==================================================================

    /**
     * 双列余额前态是 CAS 的核心条件：前态与库中真值不符时影响必须为 0 行、卡一分不动。
     * 去掉 {@code AND BALANCE_AMOUNT = #{oldAmount}}（或 ML 那条）本用例立刻变红——
     * 那时会返回 1 行并把钱按错误前态加进去。
     */
    @Test
    void refundCardAssetsRejectsDriftedPreStateWithZeroChange() {
        assertEquals(0, tradeCardMapper.refundCardAssets(CARD_ID, 400L, 0L,
                INIT_BALANCE - 1L, INIT_ML, USER_ID, OP_USER, NOW), "金额前态不符必须 0 行");
        assertEquals(0, tradeCardMapper.refundCardAssets(CARD_ID, 400L, 0L,
                INIT_BALANCE, INIT_ML + 1L, USER_ID, OP_USER, NOW), "水量前态不符必须 0 行");
        assertEquals(0, tradeCardMapper.refundCardAssets(CARD_ID, 400L, 0L,
                INIT_BALANCE, INIT_ML, OTHER_USER, OP_USER, NOW), "归属不符必须 0 行");
        assertEquals(INIT_BALANCE, cardBalance(), "三次落空一分不得改动余额");
        assertEquals(INIT_ML, cardMl());

        // 对照：前态全对时恰 1 行且精确入账——上面的 0 行不是因为这条 SQL 恒不生效
        assertEquals(1, tradeCardMapper.refundCardAssets(CARD_ID, 400L, 0L,
                INIT_BALANCE, INIT_ML, USER_ID, OP_USER, NOW));
        assertEquals(INIT_BALANCE + 400L, cardBalance());
    }

    /**
     * 读卡与 UPDATE 之间余额被并发改动：refundCardAssets 影响 0 行 → 抛出且<b>零副作用</b>
     * （卡不动、无返还流水、无审计、动作随事务回滚仍停在执行中）。
     */
    @Test
    void driftedCardPreStateThrowsAndRollsBackEverything() {
        long id = seedProcessing(SourceType.DELIVERY_APPEAL, 8803L, ORDER_FEN,
                ActionType.CARD_COMPENSATE, 0L, CAP_SERVICE_FEN, 0L);
        CARD_READ_DRIFT.set(-1L); // 读到的前态比库里少 1 分 = 读后被并发改动

        JbkException ex = assertThrows(JbkException.class, () -> afterSale.executeInTx(id, OP_USER, NOW));
        assertTrue(ex.getMessage().contains("水卡权益返还影响行数异常"), "实际=" + ex.getMessage());

        assertEquals(INIT_BALANCE, cardBalance(), "前态漂移必须零入账");
        assertEquals(INIT_ML, cardMl());
        assertEquals(0, refundFlowCount(), "流水零新增");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ws_domain_event", Integer.class),
                "同事务审计必须一并回滚");
        assertEquals(ActionStatus.PROCESSING.getValue(), actionStatus(id), "动作不得被盖成已完成");
    }

    /** 卡归属被改（换主）：钱绝不打进现在这个人的卡里，锁内核验直接拒绝且零副作用。 */
    @Test
    void cardOwnerDriftIsRejectedWithZeroResidue() {
        long id = seedProcessing(SourceType.DELIVERY_APPEAL, 8804L, ORDER_FEN,
                ActionType.CARD_COMPENSATE, 0L, CAP_SERVICE_FEN, 0L);
        jdbc.update("UPDATE ws_card SET USER_ID=? WHERE ID=?", OTHER_USER, CARD_ID);

        JbkException ex = assertThrows(JbkException.class, () -> afterSale.executeInTx(id, OP_USER, NOW));
        assertTrue(ex.getMessage().contains("已换主"), "实际=" + ex.getMessage());
        assertEquals(INIT_BALANCE, cardBalance());
        assertEquals(0, refundFlowCount());
        assertEquals(ActionStatus.PROCESSING.getValue(), actionStatus(id));
    }

    /** 已注销卡(4)：必须以「已注销」这条精确错因拒绝，而不是退化成一次影响 0 行的假失败。 */
    @Test
    void cancelledCardIsRejectedWithPreciseReasonAndZeroResidue() {
        long id = seedProcessing(SourceType.DELIVERY_APPEAL, 8805L, ORDER_FEN,
                ActionType.CARD_COMPENSATE, 0L, CAP_SERVICE_FEN, 0L);
        jdbc.update("UPDATE ws_card SET CARD_STATUS=? WHERE ID=?",
                UserEnum.CardStatus.CANCELLED.getValue(), CARD_ID);

        JbkException ex = assertThrows(JbkException.class, () -> afterSale.executeInTx(id, OP_USER, NOW));
        assertTrue(ex.getMessage().contains("已注销"), "错因必须精确到注销，实际=" + ex.getMessage());
        assertEquals(INIT_BALANCE, cardBalance());
        assertEquals(0, refundFlowCount());
    }

    /** 逻辑删除卡：锁得到（selectByIdForUpdate 不过滤 DATA_STATUS）但必须显式拒绝。 */
    @Test
    void logicallyDeletedCardIsRejectedWithZeroResidue() {
        long id = seedProcessing(SourceType.DELIVERY_APPEAL, 8806L, ORDER_FEN,
                ActionType.CARD_COMPENSATE, 0L, CAP_SERVICE_FEN, 0L);
        jdbc.update("UPDATE ws_card SET DATA_STATUS=1 WHERE ID=?", CARD_ID);

        JbkException ex = assertThrows(JbkException.class, () -> afterSale.executeInTx(id, OP_USER, NOW));
        assertTrue(ex.getMessage().contains("逻辑删除"), "实际=" + ex.getMessage());
        assertEquals(INIT_BALANCE, cardBalance());
        assertEquals(0, refundFlowCount());
    }

    /**
     * <b>过期卡(3) 必须放行</b>——这是刻意的口径而非疏漏：退的是用户自己的钱，
     * 拒绝返还等于平台白拿。把 3 从 requireCardStatus 白名单或 SQL 的 CARD_STATUS IN (1,2,3)
     * 里移除，本用例立刻变红。
     */
    @Test
    void expiredCardMustStillReceiveRefund() {
        long id = seedProcessing(SourceType.DELIVERY_CANCEL, ORDER_FEN, ORDER_FEN,
                ActionType.CARD_REFUND, CAP_PRODUCT_FEN, CAP_SERVICE_FEN, 0L);
        jdbc.update("UPDATE ws_card SET CARD_STATUS=?, EXPIRE_TIME=? WHERE ID=?",
                UserEnum.CardStatus.EXPIRED.getValue(), PAST, CARD_ID);

        afterSale.executeInTx(id, OP_USER, NOW);

        assertEquals(INIT_BALANCE + 3_000L, cardBalance(), "过期卡照样收得到返还");
        assertEquals(1, refundFlowCount());
        assertEquals(ActionStatus.SUCCESS.getValue(), actionStatus(id));
        assertEquals(UserEnum.CardStatus.EXPIRED.getValue(), cardStatus(), "返还不得顺手改卡状态");
    }

    /** 冻结卡(2) 放行，但 CARD_STATUS <b>不得被改写</b>：允许收款绝不等于自动解冻。 */
    @Test
    void frozenCardReceivesRefundWithoutBeingUnfrozen() {
        long id = seedProcessing(SourceType.DELIVERY_APPEAL, 8807L, ORDER_FEN,
                ActionType.CARD_COMPENSATE, 0L, CAP_SERVICE_FEN, 0L);
        jdbc.update("UPDATE ws_card SET CARD_STATUS=? WHERE ID=?",
                UserEnum.CardStatus.FROZEN.getValue(), CARD_ID);

        afterSale.executeInTx(id, OP_USER, NOW);

        assertEquals(INIT_BALANCE + CAP_SERVICE_FEN, cardBalance(), "冻结卡允许收退款");
        assertEquals(UserEnum.CardStatus.FROZEN.getValue(), cardStatus(),
                "返还 SET 里绝不允许出现 CARD_STATUS，冻结卡不得被自动解冻");
        assertEquals(ActionStatus.SUCCESS.getValue(), actionStatus(id));
    }

    // ==================================================================
    // C：四元累计封顶（保护 RR 快照 P0）
    // ==================================================================

    /**
     * 串行第二笔超额：拒绝、卡余额<b>零变化</b>、动作落需人工对账（钱不动但必须有人接手）。
     */
    @Test
    void secondRefundOverCapIsRejectedAndLandsReconciliationRequired() {
        long first = seedAction(SourceType.DELIVERY_CANCEL, ORDER_FEN, ORDER_FEN,
                ActionType.CARD_REFUND, null, CAP_PRODUCT_FEN, CAP_SERVICE_FEN, 0L,
                ActionStatus.PENDING, 1, 0, null);
        assertTrue(orchestrate(first), "首笔满额返还必须成功");
        long balanceAfterFirst = cardBalance();
        assertEquals(INIT_BALANCE + 3_000L, balanceAfterFirst);

        // 同订单第二笔（申诉来源、不同 sourceId，两把唯一键都拦不住），只要 1 分配送费也超额
        long second = seedAction(SourceType.DELIVERY_APPEAL, 8808L, ORDER_FEN,
                ActionType.CARD_COMPENSATE, StrategyCode.SERVICE_FEE_ONLY.getCode(), 0L, 1L, 0L,
                ActionStatus.PENDING, 1, 0, null);
        assertFalse(orchestrate(second), "额度已用尽，第二笔必须被拒");

        assertEquals(balanceAfterFirst, cardBalance(), "被拒的返还不得改动余额");
        assertEquals(INIT_ML, cardMl());
        assertEquals(1, refundFlowCount(), "被拒的返还不得留下流水");

        Map<String, Object> row = actionRow(second);
        assertEquals(ActionStatus.RECONCILIATION_REQUIRED.getValue(), num(row, "ACTION_STATUS"));
        assertEquals(NOW, row.get("FINISH_TIME"));
        assertTrue(String.valueOf(row.get("LAST_ERROR")).contains("超出可返还额度"),
                "错因必须落到额度上，实际=" + row.get("LAST_ERROR"));
    }

    /**
     * <b>本包最重要的一条</b>：同一订单两条不同 sourceId 的动作各占 60% 额度，两线程真并发执行。
     *
     * <p>保护的是「RR 快照下封顶失效 ⇒ 同一订单可重复全额返还」那个 P0：卡行 X 锁把两笔串行化，
     * 落败者醒来后的额度聚合必须看得见赢家刚提交的已完成行。若封顶失效，卡会被加两次 1800，
     * 本用例的余额断言立刻变红。</p>
     */
    @Test
    void concurrentRefundsOnSameOrderCreditCardExactlyOnce() throws Exception {
        long each = 1_800L;   // 1560 水品 + 240 配送费，各为对应上限的 60%
        long a = seedAction(SourceType.DELIVERY_CANCEL, ORDER_FEN, ORDER_FEN,
                ActionType.CARD_REFUND, null, 1_560L, 240L, 0L, ActionStatus.PENDING, 1, 0, null);
        long b = seedAction(SourceType.DELIVERY_APPEAL, 8809L, ORDER_FEN,
                ActionType.CARD_COMPENSATE, StrategyCode.PRODUCT_AND_SERVICE.getCode(),
                1_560L, 240L, 0L, ActionStatus.PENDING, 1, 0, null);

        // 认领先各自独立提交（生产同序），并发窗口只留给资金段
        assertTrue(afterSale.claimIndependent(a, 1, OP_USER, NOW));
        assertTrue(afterSale.claimIndependent(b, 1, OP_USER, NOW));

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Callable<Boolean>> jobs = new ArrayList<>();
        for (long id : new long[]{a, b}) {
            jobs.add(() -> {
                barrier.await(15, TimeUnit.SECONDS);
                try {
                    afterSale.executeInTx(id, OP_USER, NOW);
                    return true;
                } catch (RuntimeException failed) {
                    afterSale.markTerminalIndependent(id, 1,
                            ActionStatus.RECONCILIATION_REQUIRED.getValue(), null,
                            failed.getMessage(), OP_USER, NOW);
                    return false;
                }
            });
        }
        int winners = 0;
        for (Future<Boolean> f : pool.invokeAll(jobs)) {
            if (f.get(60, TimeUnit.SECONDS)) {
                winners++;
            }
        }
        pool.shutdown();

        assertEquals(1, winners, "并发两笔各占 60% 额度，只能有一笔成功");
        assertEquals(INIT_BALANCE + each, cardBalance(), "卡余额只允许被加一次 1800");
        assertEquals(INIT_ML, cardMl());
        assertEquals(1, refundFlowCount(), "返还流水恰一条");
        assertEquals(1, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ws_after_sale_action WHERE ACTION_STATUS=?", Integer.class,
                        ActionStatus.SUCCESS.getValue()),
                "恰一条已完成");
        assertEquals(1, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ws_after_sale_action WHERE ACTION_STATUS=?", Integer.class,
                        ActionStatus.RECONCILIATION_REQUIRED.getValue()),
                "落败的那条必须落需人工对账，不得停在执行中");
    }

    // ==================================================================
    // D：幂等与回滚
    // ==================================================================

    /** 同来源二次登记：撞 uk_after_sale_source 后幂等返回同一行，绝不新建第二笔待执行。 */
    @Test
    void createPendingIsIdempotentOnSameSource() {
        WsAfterSaleAction first = afterSale.createPending(draft(SourceType.DELIVERY_APPEAL, 8810L), NOW);
        WsAfterSaleAction replay = afterSale.createPending(draft(SourceType.DELIVERY_APPEAL, 8810L), NOW);

        assertNotNull(first.getId());
        assertEquals(first.getId(), replay.getId(), "重放必须命中同一行");
        assertEquals(AfterSaleNo.derive(SourceType.DELIVERY_APPEAL.getValue(), 8810L), replay.getAfterSaleNo());
        assertEquals(ActionStatus.PENDING.getValue(), replay.getActionStatus());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ws_after_sale_action", Integer.class),
                "全表恰一行，不得新建");
        assertEquals(CAP_SERVICE_FEN, num(actionRow(first.getId()), "REFUND_AMOUNT"),
                "合计列由三个分维求和派生，不信任入参");
    }

    /** 空 CARD_ID 只属于首次购卡未入账的机构退款，内部卡内返还必须在写库前拒绝。 */
    @Test
    void internalCardReturnCannotRegisterWithoutTargetCard() {
        WsAfterSaleAction invalid = draft(SourceType.DELIVERY_APPEAL, 8812L).setCardId(null);

        JbkException ex = assertThrows(JbkException.class, () -> afterSale.createPending(invalid, NOW));

        assertTrue(ex.getMessage().contains("返还目标卡ID非法"), "实际=" + ex.getMessage());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ws_after_sale_action", Integer.class));
    }

    private WsAfterSaleAction draft(SourceType source, long sourceId) {
        return new WsAfterSaleAction()
                .setSourceType(source.getValue())
                .setSourceId(sourceId)
                .setOrderId(ORDER_FEN)
                .setUserId(USER_ID)
                .setCardId(CARD_ID)
                .setActionType(ActionType.CARD_COMPENSATE.getValue())
                .setStrategyCode(StrategyCode.SERVICE_FEE_ONLY.getCode())
                .setRefundProductFen(0L)
                .setRefundServiceFen(CAP_SERVICE_FEN)
                .setRefundProductMl(0L)
                // 故意传一个错的合计：内核必须按 Refund.totalFen() 重算覆盖
                .setRefundAmount(999_999L);
    }

    /**
     * 同一动作被重复执行（例如提交结果未知后的重放）：额度尚有余量、CAS 也放行，
     * 唯一挡住二次入账的是 uk_wallet_flow_biz_key ——撞键后<b>整事务回滚</b>，卡只增一次。
     */
    @Test
    void repeatedExecuteRollsBackEntirelyOnWalletFlowKeyConflict() {
        long id = seedProcessing(SourceType.DELIVERY_APPEAL, 8811L, ORDER_FEN,
                ActionType.CARD_COMPENSATE, 0L, 200L, 0L);
        afterSale.executeInTx(id, OP_USER, NOW);
        assertEquals(INIT_BALANCE + 200L, cardBalance());

        // 把动作拨回执行中：模拟 Worker/人工对同一笔已入账的动作再执行一次
        jdbc.update("UPDATE ws_after_sale_action SET ACTION_STATUS=?, FINISH_TIME=NULL WHERE ID=?",
                ActionStatus.PROCESSING.getValue(), id);

        assertThrows(DuplicateKeyException.class, () -> afterSale.executeInTx(id, OP_USER, NOW));

        assertEquals(INIT_BALANCE + 200L, cardBalance(), "卡只允许被加一次");
        assertEquals(INIT_ML, cardMl());
        assertEquals(1, refundFlowCount(), "返还流水仍恰一条");
        assertEquals(ActionStatus.PROCESSING.getValue(), actionStatus(id),
                "第二次执行整体回滚，不得再盖一次已完成");
    }

    /**
     * 流水插入失败（幂等键已被占用）→ 已经写进卡里的返还必须<b>一并回滚</b>：
     * 不允许出现「钱加了、流水没有」的无痕入账。
     */
    @Test
    void walletFlowInsertFailureRollsBackTheCardCredit() {
        long id = seedProcessing(SourceType.DELIVERY_APPEAL, 8812L, ORDER_FEN,
                ActionType.CARD_COMPENSATE, 0L, CAP_SERVICE_FEN, 0L);
        // 先占住这笔返还的流水幂等键
        jdbc.update("INSERT INTO ws_wallet_flow(DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,"
                        + "CARD_ID,USER_ID,FLOW_TYPE,AMOUNT_CHANGE,ML_CHANGE,AMOUNT_AFTER,ML_AFTER,"
                        + "ORDER_ID,FLOW_REMARK,BIZ_IDEMPOTENCY_KEY) VALUES(0,1,?,1,?,?,?,?,0,0,?,?,?,?,?)",
                NOW, NOW, CARD_ID, USER_ID, TradeEnum.FlowType.ADJUST.getValue(),
                INIT_BALANCE, INIT_ML, ORDER_FEN, "占位", "AFTERSALE:" + afterSaleNo(id));

        assertThrows(DuplicateKeyException.class, () -> afterSale.executeInTx(id, OP_USER, NOW));

        assertEquals(INIT_BALANCE, cardBalance(), "卡返还必须随流水失败一并回滚");
        assertEquals(INIT_ML, cardMl());
        assertEquals(1, refundFlowCount(), "只剩那条占位流水");
        assertEquals("占位", jdbc.queryForObject(
                "SELECT FLOW_REMARK FROM ws_wallet_flow WHERE BIZ_IDEMPOTENCY_KEY=?", String.class,
                "AFTERSALE:" + afterSaleNo(id)));
        assertEquals(ActionStatus.PROCESSING.getValue(), actionStatus(id));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ws_domain_event", Integer.class),
                "同事务审计一并回滚");
    }

    // ==================================================================
    // E：状态机
    // ==================================================================

    /** 认领只认待执行与<b>到点的</b>可重试；其余一律返回 false 且零写入。 */
    @Test
    void claimOnlyAcceptsPendingAndDueRetryWait() {
        long success = seedAction(SourceType.DELIVERY_APPEAL, 8813L, ORDER_FEN, ActionType.CARD_COMPENSATE,
                null, 0L, 100L, 0L, ActionStatus.SUCCESS, 3, 0, null);
        long processing = seedAction(SourceType.DELIVERY_APPEAL, 8814L, ORDER_FEN, ActionType.CARD_COMPENSATE,
                null, 0L, 100L, 0L, ActionStatus.PROCESSING, 2, 0, null);
        long notDue = seedAction(SourceType.DELIVERY_APPEAL, 8815L, ORDER_FEN, ActionType.CARD_COMPENSATE,
                null, 0L, 100L, 0L, ActionStatus.RETRY_WAIT, 3, 1, FUTURE);
        long due = seedAction(SourceType.DELIVERY_APPEAL, 8816L, ORDER_FEN, ActionType.CARD_COMPENSATE,
                null, 0L, 100L, 0L, ActionStatus.RETRY_WAIT, 3, 1, PAST);
        long pending = seedAction(SourceType.DELIVERY_APPEAL, 8817L, ORDER_FEN, ActionType.CARD_COMPENSATE,
                null, 0L, 100L, 0L, ActionStatus.PENDING, 1, 0, null);

        assertFalse(afterSale.claimIndependent(success, 3, OP_USER, NOW), "已完成不可再认领");
        assertFalse(afterSale.claimIndependent(processing, 2, OP_USER, NOW), "执行中不可被二次认领");
        assertFalse(afterSale.claimIndependent(notDue, 3, OP_USER, NOW), "未到点的可重试不可认领");
        assertFalse(afterSale.claimIndependent(pending, 2, OP_USER, NOW), "版本不符不可认领");

        assertEquals(ActionStatus.SUCCESS.getValue(), actionStatus(success), "落空的认领零写入");
        assertEquals(ActionStatus.PROCESSING.getValue(), actionStatus(processing));
        assertEquals(ActionStatus.RETRY_WAIT.getValue(), actionStatus(notDue));
        assertEquals(1L, num(actionRow(pending), "VERSION"), "落空的认领不得推进版本");

        assertTrue(afterSale.claimIndependent(due, 3, OP_USER, NOW), "到点的可重试必须能被捡起");
        assertTrue(afterSale.claimIndependent(pending, 1, OP_USER, NOW), "待执行首次认领");

        Map<String, Object> claimed = actionRow(pending);
        assertEquals(ActionStatus.PROCESSING.getValue(), num(claimed, "ACTION_STATUS"));
        assertEquals(2L, num(claimed, "VERSION"));
        assertNull(claimed.get("NEXT_RETRY_TIME"), "认领即消费掉重试排期");
        assertNull(claimed.get("LAST_ERROR"));
    }

    /**
     * 本批修的 P0：{@code claimIndependent} 独立提交时把 VERSION 加了 1，而它只返回 boolean，
     * 编排层手上永远是 claim <b>之前</b> 的版本。终局落痕必须自己在独立事务内重读版本——
     * 传旧版本、甚至<b>不传版本</b>都要能落住终态。改回沿用入参版本，本用例立刻变红
     * （动作会永久停在 2执行中、无终态无错因）。
     */
    @Test
    void markTerminalLandsWithoutRelyingOnCallerVersion() {
        long stale = seedAction(SourceType.DELIVERY_APPEAL, 8818L, ORDER_FEN, ActionType.CARD_COMPENSATE,
                null, 0L, 100L, 0L, ActionStatus.PENDING, 1, 0, null);
        long missing = seedAction(SourceType.DELIVERY_APPEAL, 8819L, ORDER_FEN, ActionType.CARD_COMPENSATE,
                null, 0L, 100L, 0L, ActionStatus.PENDING, 1, 0, null);
        assertTrue(afterSale.claimIndependent(stale, 1, OP_USER, NOW));
        assertTrue(afterSale.claimIndependent(missing, 1, OP_USER, NOW));

        // ① 传 claim 之前的旧版本（编排层的真实处境）
        afterSale.markTerminalIndependent(stale, 1, ActionStatus.RECONCILIATION_REQUIRED.getValue(),
                null, "返还失败：账实不符", OP_USER, NOW);
        // ② 完全不传版本
        afterSale.markTerminalIndependent(missing, null, ActionStatus.RECONCILIATION_REQUIRED.getValue(),
                null, "返还失败：账实不符", OP_USER, NOW);

        for (long id : new long[]{stale, missing}) {
            Map<String, Object> row = actionRow(id);
            assertEquals(ActionStatus.RECONCILIATION_REQUIRED.getValue(), num(row, "ACTION_STATUS"),
                    "id=" + id + " 必须落终态");
            assertEquals(NOW, row.get("FINISH_TIME"));
            assertEquals("返还失败：账实不符", row.get("LAST_ERROR"));
            assertEquals(3L, num(row, "VERSION"), "CAS 命中必然推进版本");
            assertEquals(0L, num(row, "RETRY_COUNT"), "终态落点不得动重试计数");
        }
    }

    /**
     * 重试耗尽：{@code RETRY_COUNT < maxRetryCount} 闸让可重试落点影响 0 行，
     * 编排层据此<b>升级为需人工对账</b>而不是继续可重试（否则失败动作被无限重放）。
     */
    @Test
    void retryExhaustedUpgradesToReconciliationRequired() {
        long exhausted = seedAction(SourceType.DELIVERY_APPEAL, 8820L, ORDER_FEN, ActionType.CARD_COMPENSATE,
                null, 0L, 100L, 0L, ActionStatus.PROCESSING, 2, 3, null);
        long retryable = seedAction(SourceType.DELIVERY_APPEAL, 8821L, ORDER_FEN, ActionType.CARD_COMPENSATE,
                null, 0L, 100L, 0L, ActionStatus.PROCESSING, 2, 2, null);

        afterSale.markTerminalIndependent(exhausted, 2, ActionStatus.RETRY_WAIT.getValue(),
                FUTURE, "锁等待超时", OP_USER, NOW);
        afterSale.markTerminalIndependent(retryable, 2, ActionStatus.RETRY_WAIT.getValue(),
                FUTURE, "锁等待超时", OP_USER, NOW);

        Map<String, Object> dead = actionRow(exhausted);
        assertEquals(ActionStatus.RECONCILIATION_REQUIRED.getValue(), num(dead, "ACTION_STATUS"),
                "第 4 次失败必须转人工对账，不得继续排期重试");
        assertEquals(3L, num(dead, "RETRY_COUNT"), "升级落点不得再递增计数");
        assertNull(dead.get("NEXT_RETRY_TIME"), "转人工后不得留下重试排期");
        assertEquals(NOW, dead.get("FINISH_TIME"));
        assertTrue(String.valueOf(dead.get("LAST_ERROR")).contains("重试已达上限(3)"),
                "实际=" + dead.get("LAST_ERROR"));

        Map<String, Object> alive = actionRow(retryable);
        assertEquals(ActionStatus.RETRY_WAIT.getValue(), num(alive, "ACTION_STATUS"),
                "未耗尽时必须正常落可重试——上限闸不是恒真");
        assertEquals(3L, num(alive, "RETRY_COUNT"), "可重试落点必须递增计数");
        assertEquals(FUTURE, alive.get("NEXT_RETRY_TIME"));
        assertNull(alive.get("FINISH_TIME"), "可重试不是终态，不写 FINISH_TIME");
    }
    // ==================================================================
    // executeInTx 的入口闸：这是全仓唯一的加钱路径，每道闸都必须有守卫
    // （审查实测：把九道闸一次性删光，既有 40 条用例仍然全绿——即零覆盖）
    // ==================================================================

    /** 逻辑删除的动作行不得执行返还：删除是"这笔售后作废"的表达，执行它等于退一笔已撤销的钱。 */
    @Test
    void logicallyDeletedActionIsRejectedWithoutMoneyMoved() {
        long id = seedProcessing(SourceType.DELIVERY_APPEAL, 9101L, ORDER_FEN,
                ActionType.CARD_COMPENSATE, 0L, 400L, 0L);
        jdbc.update("UPDATE ws_after_sale_action SET DATA_STATUS=1 WHERE ID=?", id);

        assertGuardRejects(id, "已被逻辑删除");
    }

    /** 未认领（仍待执行）就直接执行：认领是独立事务，跳过它意味着并发两个线程可同时进资金段。 */
    @Test
    void unclaimedActionIsRejectedWithoutMoneyMoved() {
        long id = seedAction(SourceType.DELIVERY_APPEAL, 9102L, ORDER_FEN, ActionType.CARD_COMPENSATE,
                null, 0L, 400L, 0L, ActionStatus.PENDING, 1, 0, null);

        assertGuardRejects(id, "不在执行中状态");
    }

    /**
     * 取水核账来源不得走卡内返还——这是"两侧对开"的另一半。
     * 核账那侧靠 WaterAbnormalReconcileTxServiceImpl 不注入 TradeCardMapper 做编译期护栏（已有反射测试守着）；
     * 本侧靠这道运行时闸。只钉一侧的话，从这边传一个 SOURCE_TYPE=3 的动作进来照样能加钱。
     */
    @Test
    void waterAbnormalSourceIsRejectedByRefundKernel() {
        long id = seedProcessing(SourceType.WATER_ABNORMAL, 9103L, ORDER_FEN,
                ActionType.CARD_REFUND, 0L, 400L, 0L);

        assertGuardRejects(id, "取水异常核账不走卡内返还事务");
    }

    /** 机构退款（包B）属于外部支付通道，不能从卡内返还路径出账，否则同一笔退款会走两条通道。 */
    @Test
    void providerRefundActionTypeIsRejectedByCardKernel() {
        long id = seedProcessing(SourceType.DELIVERY_APPEAL, 9104L, ORDER_FEN,
                ActionType.GATEWAY_REFUND, 0L, 400L, 0L);

        assertGuardRejects(id, "不属于卡内返还");
    }

    /** 三列全零的动作不该进资金事务：它会插一条零变动流水，把对账口径搅浑。 */
    @Test
    void zeroAmountActionIsRejected() {
        long id = seedProcessing(SourceType.DELIVERY_APPEAL, 9105L, ORDER_FEN,
                ActionType.CARD_COMPENSATE, 0L, 0L, 0L);

        assertGuardRejects(id, "零额度动作");
    }

    /**
     * 合计列与三个分维之和不等：说明有一侧被改写。
     * 合计供 CAS 与流水使用、分维供封顶使用，两者脱钩时"退的钱"与"判的额度"就不是同一笔。
     */
    @Test
    void refundAmountInconsistentWithDimensionsIsRejected() {
        long id = seedProcessing(SourceType.DELIVERY_APPEAL, 9106L, ORDER_FEN,
                ActionType.CARD_COMPENSATE, 0L, 400L, 0L);
        jdbc.update("UPDATE ws_after_sale_action SET REFUND_AMOUNT=? WHERE ID=?", 999L, id);

        assertGuardRejects(id, "返还金额合计");
    }

    /** 关联的不是配送订单：配送售后的快照解析与额度锚点都假定 ORDER_TYPE=3，错位即拒。 */
    @Test
    void nonDeliveryOrderIsRejected() {
        long id = seedProcessing(SourceType.DELIVERY_APPEAL, 9107L, ORDER_FEN,
                ActionType.CARD_COMPENSATE, 0L, 400L, 0L);
        jdbc.update("UPDATE ws_order SET ORDER_TYPE=? WHERE ID=?",
                TradeEnum.OrderType.WATER.getValue(), ORDER_FEN);

        assertGuardRejects(id, "不是配送订单");
    }

    /**
     * 动作与订单的归属错位 —— 九道闸里后果最严重的一道：
     * 失效即意味着按 A 的订单算额度、把钱退进 B 的卡。
     */
    @Test
    void ownershipMismatchBetweenActionAndOrderIsRejected() {
        long id = seedProcessing(SourceType.DELIVERY_APPEAL, 9108L, ORDER_FEN,
                ActionType.CARD_COMPENSATE, 0L, 400L, 0L);
        jdbc.update("UPDATE ws_after_sale_action SET USER_ID=? WHERE ID=?", USER_ID + 999L, id);

        assertGuardRejects(id, "归属错位");
    }

    // ==================================================================
    // markSuccess 的四元额度 WHERE：创建时算定的分项在执行期不得漂移
    // （审查实测：把这四条谓词从 XML 删掉，既有 40 条用例仍然全绿）
    // ==================================================================

    @Test
    void markSuccessRejectsEachDriftedRefundDimension() {
        long id = seedProcessing(SourceType.DELIVERY_APPEAL, 9201L, ORDER_FEN,
                ActionType.CARD_COMPENSATE, 100L, 400L, 700L);
        Integer to = ActionStatus.SUCCESS.getValue();
        var sources = AfterSaleTransitions.requireSources(to);

        // 四个维度逐一拨歪：任一与建行值不符都必须影响 0 行，状态原地不动
        assertEquals(0, actionMapper.markSuccess(id, 2, to, sources, 999L, 400L, 700L, 500L, OP_USER, NOW),
                "水品金额漂移必须拒绝");
        assertEquals(0, actionMapper.markSuccess(id, 2, to, sources, 100L, 999L, 700L, 500L, OP_USER, NOW),
                "配送费漂移必须拒绝");
        assertEquals(0, actionMapper.markSuccess(id, 2, to, sources, 100L, 400L, 999L, 500L, OP_USER, NOW),
                "水量漂移必须拒绝");
        assertEquals(0, actionMapper.markSuccess(id, 2, to, sources, 100L, 400L, 700L, 999L, OP_USER, NOW),
                "合计漂移必须拒绝");
        assertEquals((long) ActionStatus.PROCESSING.getValue(), num(actionRow(id), "ACTION_STATUS"),
                "四次拒绝后状态必须原地不动");

        // 正向对照：四值全对时必须成功——证明这条 SQL 不是恒不生效
        assertEquals(1, actionMapper.markSuccess(id, 2, to, sources, 100L, 400L, 700L, 500L, OP_USER, NOW));
        assertEquals(to.longValue(), num(actionRow(id), "ACTION_STATUS"));
    }

    // ==================================================================
    // 隔离级别与传播：并发用例钉的是"卡锁 + 聚合当前读"的合力，
    // 注解本身没有测试盯着 —— 有人做"这段运行时断言与注解重复，删掉"的简化时不会有红灯
    // ==================================================================

    @Test
    void executeInTxDeclaresReadCommittedAndRequiresNew() throws Exception {
        var m = AfterSaleActionTxServiceImpl.class.getMethod("executeInTx", Long.class, Long.class, String.class);
        var tx = m.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertNotNull(tx, "资金事务必须显式声明 @Transactional");
        assertEquals(org.springframework.transaction.annotation.Isolation.READ_COMMITTED, tx.isolation(),
                "RR 下额度聚合会读到锁卡前的旧视图，封顶失效即可重复全额返还");
        assertEquals(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW, tx.propagation(),
                "REQUIRED 会被编排层事务吞并、隔离级别静默退回 RR，故必须物理独立");
    }

    /**
     * 入口闸的共用断言：抛出且错因可读、且**零副作用**。
     * 九道闸的后果完全一致（不该动的钱一分都不能动），故断言集中在一处；
     * 分别复制九遍只会让"漏断言某一项"变得难以发现。
     */
    private void assertGuardRejects(long actionId, String expectedReason) {
        long balance = cardBalance();
        long ml = cardMl();
        int flows = refundFlowCount();
        Object statusBefore = actionRow(actionId).get("ACTION_STATUS");

        JbkException ex = assertThrows(JbkException.class,
                () -> afterSale.executeInTx(actionId, OP_USER, NOW));
        assertTrue(ex.getMessage().contains(expectedReason),
                "拒因应指明具体原因，实际=" + ex.getMessage());

        assertEquals(balance, cardBalance(), "被拒绝的返还不得改动卡余额");
        assertEquals(ml, cardMl(), "被拒绝的返还不得改动卡水量");
        assertEquals(flows, refundFlowCount(), "被拒绝的返还不得产生流水");
        assertEquals(statusBefore, actionRow(actionId).get("ACTION_STATUS"),
                "闸在资金段之前拦下，动作状态不应被改写");
    }

}
