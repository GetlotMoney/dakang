package com.jbk.serve.service.mini.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.aftersale.WsCardEntitlementBatchMapper;
import com.jbk.serve.mapper.trade.GiftMergeMapper;
import com.jbk.serve.mapper.trade.TradeCardMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.service.mini.IMiniGiftMergeService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.serve.service.user.IWsCardService;
import com.jbk.tool.data.mini.vo.MiniCardMergeVo;
import com.jbk.tool.data.user.po.WsCard;
import com.jbk.tool.exception.JbkException;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 赠卡合并入正式水卡的<b>真实 MySQL + 真实 Spring 事务</b>集成测试（D-415）。
 *
 * <p>这里测的性质无法用单测证明：四条 CAS SQL 的 WHERE 前态在真库上真的挡得住漂移；
 * 批次改挂与两卡余额变动在同一事务里同生共死；{@code MERGE-OUT:<赠卡ID>} 唯一键
 * 让重复合并要么幂等返回、要么整体回滚；逐卡不变式「SUM(批次剩余)==卡余额」在
 * 合并前后对两张卡同时成立。</p>
 *
 * <p>锁外的主卡定位（{@code IWsCardService.list}）按接口 mock——它只产生候选 ID，
 * 结论一律由锁内重验裁决，mock 不影响被测性质。无 Docker 环境自动跳过。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = GiftMergeTxDbTest.Ctx.class)
class GiftMergeTxDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_merge_it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static final long GIFT_ID = 300L;
    private static final long MAIN_ID = 301L;
    private static final long USER_ID = 9L;
    /** 未过期赠卡到期：2027-07-10（晚于本类固定「当前」时刻）。 */
    private static final String GIFT_EXPIRE_FUTURE = "20270710120000";
    /** 已过期赠卡到期：2025-01-01。 */
    private static final String GIFT_EXPIRE_PAST = "20250101120000";
    private static final String SCOPE = "{\"scopeType\":\"all\"}";

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
            MybatisConfiguration cfg = new MybatisConfiguration();
            cfg.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(cfg);
            // TradeCardMapper 的 selectByIdForUpdate 在 XML 里
            factory.setMapperLocations(new org.springframework.core.io.support
                    .PathMatchingResourcePatternResolver().getResources("classpath*:mapper/trade/TradeCardMapper.xml"));
            return new SqlSessionTemplate(factory.getObject());
        }

        @Bean
        MapperFactoryBean<TradeCardMapper> tradeCardMapper(SqlSessionTemplate t) {
            MapperFactoryBean<TradeCardMapper> bean = new MapperFactoryBean<>(TradeCardMapper.class);
            bean.setSqlSessionTemplate(t);
            return bean;
        }

        @Bean
        MapperFactoryBean<GiftMergeMapper> giftMergeMapper(SqlSessionTemplate t) {
            MapperFactoryBean<GiftMergeMapper> bean = new MapperFactoryBean<>(GiftMergeMapper.class);
            bean.setSqlSessionTemplate(t);
            return bean;
        }

        @Bean
        MapperFactoryBean<WsCardEntitlementBatchMapper> batchMapper(SqlSessionTemplate t) {
            MapperFactoryBean<WsCardEntitlementBatchMapper> bean =
                    new MapperFactoryBean<>(WsCardEntitlementBatchMapper.class);
            bean.setSqlSessionTemplate(t);
            return bean;
        }

        @Bean
        MapperFactoryBean<WsWalletFlowMapper> walletFlowMapper(SqlSessionTemplate t) {
            MapperFactoryBean<WsWalletFlowMapper> bean = new MapperFactoryBean<>(WsWalletFlowMapper.class);
            bean.setSqlSessionTemplate(t);
            return bean;
        }

        @Bean
        IWsCardService wsCardService() {
            return Mockito.mock(IWsCardService.class);
        }

        @Bean
        IWsDomainEventService domainEventService() {
            return Mockito.mock(IWsDomainEventService.class);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.aftersale.WsEntitlementAllocationMapper> allocationMapper(
                SqlSessionTemplate t) {
            MapperFactoryBean<com.jbk.serve.mapper.aftersale.WsEntitlementAllocationMapper> bean =
                    new MapperFactoryBean<>(com.jbk.serve.mapper.aftersale.WsEntitlementAllocationMapper.class);
            bean.setSqlSessionTemplate(t);
            return bean;
        }

        @Bean
        com.jbk.serve.service.aftersale.batch.EntitlementLedger entitlementLedger(
                WsCardEntitlementBatchMapper batchMapper,
                com.jbk.serve.mapper.aftersale.WsEntitlementAllocationMapper allocationMapper,
                TradeCardMapper tradeCardMapper, WsWalletFlowMapper walletFlowMapper) {
            return new com.jbk.serve.service.aftersale.batch.EntitlementLedger(
                    batchMapper, allocationMapper, tradeCardMapper, walletFlowMapper);
        }

        @Bean
        MiniGiftMergeServiceImpl mergeService(TradeCardMapper tradeCardMapper, GiftMergeMapper giftMergeMapper,
                                              WsCardEntitlementBatchMapper batchMapper,
                                              WsWalletFlowMapper walletFlowMapper,
                                              IWsCardService wsCardService, IWsDomainEventService events,
                                              com.jbk.serve.service.aftersale.batch.EntitlementLedger ledger) {
            return new MiniGiftMergeServiceImpl(tradeCardMapper, giftMergeMapper, batchMapper,
                    walletFlowMapper, wsCardService, events, ledger);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    /** 按接口注入：@Transactional 生成 JDK 接口代理，这条注入即事务织入的证据。 */
    @Autowired
    private IMiniGiftMergeService mergeService;
    @Autowired
    private IWsCardService wsCardService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_card (
                  ID BIGINT PRIMARY KEY, CARD_NO VARCHAR(50), CARD_TYPE TINYINT DEFAULT 1, USER_ID BIGINT,
                  BALANCE_AMOUNT BIGINT NOT NULL DEFAULT 0, BALANCE_ML BIGINT NOT NULL DEFAULT 0,
                  PACKAGE_ID BIGINT NULL, PACKAGE_SNAP MEDIUMTEXT NULL, SCOPE_JSON VARCHAR(2000) NULL,
                  EXPIRE_TIME VARCHAR(20) NULL, CARD_STATUS TINYINT, ISSUE_ORDER_ID BIGINT NULL,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_card_entitlement_batch (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(14),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(14),
                  CARD_ID BIGINT NOT NULL, USER_ID BIGINT NOT NULL, SOURCE_TYPE TINYINT NOT NULL,
                  ORDER_ID BIGINT NULL, ORDER_NO VARCHAR(32) NULL, PAYMENT_ID BIGINT NULL,
                  PACKAGE_ID BIGINT NULL, PACKAGE_SNAP TEXT NULL,
                  PAY_AMOUNT_FEN BIGINT NOT NULL DEFAULT 0,
                  GRANT_AMOUNT_FEN BIGINT NOT NULL DEFAULT 0,
                  GRANT_BONUS_FEN BIGINT NOT NULL DEFAULT 0,
                  GRANT_WATER_ML BIGINT NOT NULL DEFAULT 0,
                  REMAIN_AMOUNT_FEN BIGINT NOT NULL DEFAULT 0,
                  REMAIN_WATER_ML BIGINT NOT NULL DEFAULT 0,
                  EXPIRE_TIME VARCHAR(14) NULL, SCOPE_JSON TEXT NULL,
                  BATCH_STATUS TINYINT NOT NULL, REFUND_LOCKED_BY BIGINT NULL,
                  REFUND_LOCK_TIME VARCHAR(14) NULL,
                  REFUNDED_AMOUNT_FEN BIGINT NOT NULL DEFAULT 0,
                  VERSION INT NOT NULL DEFAULT 1,
                  UNIQUE KEY uk_batch_order (ORDER_ID),
                  KEY idx_batch_pick (CARD_ID, BATCH_STATUS, EXPIRE_TIME, CREATE_TIME)
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
        jdbc.execute("TRUNCATE TABLE ws_card");
        jdbc.execute("TRUNCATE TABLE ws_card_entitlement_batch");
        jdbc.execute("TRUNCATE TABLE ws_wallet_flow");
        Mockito.reset(wsCardService);
        stubLocatorReturning(MAIN_ID);
    }

    /** 锁外主卡定位的 mock：只产出候选 ID，形态判定由锁内真实行裁决。 */
    private void stubLocatorReturning(Long... ids) {
        List<WsCard> cards = java.util.Arrays.stream(ids).map(id -> {
            WsCard c = new WsCard();
            c.setId(id);
            return c;
        }).toList();
        when(wsCardService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(cards);
    }

    private void seedGift(String expireTime, int status, long fen, long ml) {
        jdbc.update("INSERT INTO ws_card(ID, CARD_NO, USER_ID, BALANCE_AMOUNT, BALANCE_ML, "
                        + "SCOPE_JSON, EXPIRE_TIME, CARD_STATUS, ISSUE_ORDER_ID, DATA_STATUS) "
                        + "VALUES(?, 'GC-TEST-1', ?, ?, ?, ?, ?, ?, NULL, 0)",
                GIFT_ID, USER_ID, fen, ml, SCOPE, expireTime, status);
    }

    private void seedMain(String expireTime, int status, long fen, long ml) {
        jdbc.update("INSERT INTO ws_card(ID, CARD_NO, USER_ID, BALANCE_AMOUNT, BALANCE_ML, "
                        + "SCOPE_JSON, EXPIRE_TIME, CARD_STATUS, ISSUE_ORDER_ID, DATA_STATUS) "
                        + "VALUES(?, 'WC-MAIN-1', ?, ?, ?, ?, ?, ?, 555, 0)",
                MAIN_ID, USER_ID, fen, ml, SCOPE, expireTime, status);
    }

    /** 主卡合法账本种子：永久批次剩余与主卡余额相等（P1-4 双卡不变式的成功前提）。 */
    private void seedMainBatch(long remainFen, long remainMl) {
        jdbc.update("INSERT INTO ws_card_entitlement_batch(CARD_ID, USER_ID, SOURCE_TYPE, ORDER_ID, "
                        + "PAY_AMOUNT_FEN, GRANT_AMOUNT_FEN, GRANT_BONUS_FEN, GRANT_WATER_ML, "
                        + "REMAIN_AMOUNT_FEN, REMAIN_WATER_ML, EXPIRE_TIME, SCOPE_JSON, BATCH_STATUS, "
                        + "REFUNDED_AMOUNT_FEN, VERSION, DATA_STATUS, CREATE_TIME) "
                        + "VALUES(?, ?, 2, 9901, ?, ?, 0, ?, ?, ?, NULL, ?, 1, 0, 1, 0, '20260501000000')",
                MAIN_ID, USER_ID, remainFen, remainFen, remainMl, remainFen, remainMl, SCOPE);
    }

    private void seedGiftBatch(long cardId, long remainFen, long remainMl, String expireTime) {
        jdbc.update("INSERT INTO ws_card_entitlement_batch(CARD_ID, USER_ID, SOURCE_TYPE, "
                        + "PAY_AMOUNT_FEN, GRANT_AMOUNT_FEN, GRANT_BONUS_FEN, GRANT_WATER_ML, "
                        + "REMAIN_AMOUNT_FEN, REMAIN_WATER_ML, EXPIRE_TIME, SCOPE_JSON, BATCH_STATUS, "
                        + "REFUNDED_AMOUNT_FEN, VERSION, DATA_STATUS, CREATE_TIME) "
                        + "VALUES(?, ?, 4, 0, ?, ?, ?, ?, ?, ?, ?, 6, 0, 1, 0, '20260601000000')",
                cardId, USER_ID, remainFen, remainFen, remainMl, remainFen, remainMl, expireTime, SCOPE);
    }

    private long cardFen(long id) {
        return jdbc.queryForObject("SELECT BALANCE_AMOUNT FROM ws_card WHERE ID = " + id, Long.class);
    }

    private long cardMl(long id) {
        return jdbc.queryForObject("SELECT BALANCE_ML FROM ws_card WHERE ID = " + id, Long.class);
    }

    private int cardStatus(long id) {
        return jdbc.queryForObject("SELECT CARD_STATUS FROM ws_card WHERE ID = " + id, Integer.class);
    }

    private long batchRemainSum(long cardId) {
        return jdbc.queryForObject("SELECT IFNULL(SUM(REMAIN_AMOUNT_FEN),0) FROM ws_card_entitlement_batch "
                + "WHERE CARD_ID = " + cardId + " AND DATA_STATUS = 0", Long.class);
    }

    private long flowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ws_wallet_flow", Long.class);
    }

    /** 主链：有效期内合并——批次改挂、主卡入账、赠卡清零注销、两卡不变式同时成立。 */
    @Test
    void mergeMovesEntitlementAndCancelsGift() {
        seedGift(GIFT_EXPIRE_FUTURE, 1, 3000L, 200000L);
        seedMain(null, 1, 5000L, 100000L);
        seedMainBatch(5000L, 100000L);
        seedGiftBatch(GIFT_ID, 2000L, 150000L, GIFT_EXPIRE_FUTURE);
        seedGiftBatch(GIFT_ID, 1000L, 50000L, GIFT_EXPIRE_FUTURE);

        MiniCardMergeVo vo = mergeService.merge(GIFT_ID, USER_ID);

        assertEquals(3000L, vo.getMovedFen());
        assertEquals(200000L, vo.getMovedMl());
        assertEquals(GIFT_EXPIRE_FUTURE, vo.getBundleExpireTime());
        assertEquals(8000L, vo.getMainBalanceFen());
        assertEquals(8000L, cardFen(MAIN_ID));
        assertEquals(300000L, cardMl(MAIN_ID));
        assertEquals(0L, cardFen(GIFT_ID));
        assertEquals(0L, cardMl(GIFT_ID));
        assertEquals(4, cardStatus(GIFT_ID), "赠卡必须注销");
        // 批次全部改挂主卡且剩余原样携带（到期时间不变——「显示多久到期」的数据基础）
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ws_card_entitlement_batch "
                + "WHERE CARD_ID = " + MAIN_ID + " AND EXPIRE_TIME = '" + GIFT_EXPIRE_FUTURE + "'", Long.class));
        // 逐卡不变式：SUM(批次剩余) == 卡余额，两张卡同时成立
        assertEquals(0L, batchRemainSum(GIFT_ID));
        assertEquals(8000L, batchRemainSum(MAIN_ID), "主卡侧批次合计=原有批次+迁入批次，与新余额相等");
        assertEquals(2L, flowCount(), "MERGE-OUT + MERGE-IN 各一条");
    }

    /** 幂等：重复合并不二次入账，按流水锚返回既有结果。 */
    @Test
    void mergeReplayReturnsExistingResultWithoutDoubleCredit() {
        seedGift(GIFT_EXPIRE_FUTURE, 1, 3000L, 200000L);
        seedMain(null, 1, 5000L, 100000L);
        seedMainBatch(5000L, 100000L);
        seedGiftBatch(GIFT_ID, 3000L, 200000L, GIFT_EXPIRE_FUTURE);
        mergeService.merge(GIFT_ID, USER_ID);

        WsCard mainAfter = new WsCard();
        mainAfter.setId(MAIN_ID);
        mainAfter.setCardNo("WC-MAIN-1");
        mainAfter.setBalanceAmount(8000L);
        mainAfter.setBalanceMl(300000L);
        when(wsCardService.getById(MAIN_ID)).thenReturn(mainAfter);

        MiniCardMergeVo replay = mergeService.merge(GIFT_ID, USER_ID);

        assertEquals(3000L, replay.getMovedFen());
        assertEquals(200000L, replay.getMovedMl());
        assertEquals(MAIN_ID, replay.getMainCardId());
        assertEquals(8000L, cardFen(MAIN_ID), "重放不得二次入账");
        assertEquals(2L, flowCount(), "重放不得追加流水");
    }

    /** 过期作废：无权益转移，批次经唯一清算入口置过期清零，仅注销清理；主卡零变动；不下发 main 字段。 */
    @Test
    void expiredGiftClearsWithoutTransfer() {
        seedGift(GIFT_EXPIRE_PAST, 3, 800L, 30000L);
        seedMain(null, 1, 5000L, 100000L);
        seedMainBatch(5000L, 100000L);
        seedGiftBatch(GIFT_ID, 800L, 30000L, GIFT_EXPIRE_PAST);

        MiniCardMergeVo vo = mergeService.merge(GIFT_ID, USER_ID);

        assertTrue(vo.getExpiredCleared());
        assertEquals(0L, vo.getMovedFen());
        assertNull(vo.getMainCardId(), "过期清理与主卡无关：首次与重放一致地不下发 main 字段（P2-1）");
        assertEquals(5000L, cardFen(MAIN_ID), "过期权益绝不转入主卡");
        assertEquals(100000L, cardMl(MAIN_ID));
        assertEquals(0L, cardFen(GIFT_ID));
        assertEquals(4, cardStatus(GIFT_ID));
        assertEquals(5, jdbc.queryForObject("SELECT BATCH_STATUS FROM ws_card_entitlement_batch "
                + "WHERE CARD_ID = " + GIFT_ID, Integer.class), "批次置过期");
        assertEquals(0L, batchRemainSum(GIFT_ID), "过期批次剩余必须清零（对账线）");
        // settleExpired 写 EXPIRE_CLEAR（键 EXPIRE-BATCH:<批次ID>）+ 合并锚零变动 MERGE-OUT
        assertEquals(2L, flowCount(), "EXPIRE_CLEAR + 零变动 MERGE-OUT 各一条");
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM ws_wallet_flow WHERE FLOW_TYPE = 6 "
                + "AND BIZ_IDEMPOTENCY_KEY LIKE 'EXPIRE-BATCH:%'", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT AMOUNT_CHANGE FROM ws_wallet_flow "
                + "WHERE BIZ_IDEMPOTENCY_KEY = 'MERGE-OUT:" + GIFT_ID + "'", Long.class),
                "MERGE-OUT 为零变动幂等锚，作废金额留在 EXPIRE_CLEAR 流水");
    }

    /** 过期清理的重放：与首次同样不下发 main 字段（P2-1 一致性）。 */
    @Test
    void expiredClearReplayMatchesFirstResponse() {
        seedGift(GIFT_EXPIRE_PAST, 3, 800L, 30000L);
        seedMain(null, 1, 5000L, 100000L);
        seedMainBatch(5000L, 100000L);
        seedGiftBatch(GIFT_ID, 800L, 30000L, GIFT_EXPIRE_PAST);
        mergeService.merge(GIFT_ID, USER_ID);

        MiniCardMergeVo replay = mergeService.merge(GIFT_ID, USER_ID);
        assertTrue(replay.getExpiredCleared());
        assertEquals(0L, replay.getMovedFen());
        assertNull(replay.getMainCardId());
        assertEquals(2L, flowCount(), "重放零追加");
    }

    /** P1-4：主卡账本断裂（批次缺失/少额/退款锁定占额）必须在任何写入前拒绝。 */
    @Test
    void mainCardLedgerMismatchRejectedBeforeAnyWrite() {
        seedGift(GIFT_EXPIRE_FUTURE, 1, 3000L, 200000L);
        seedGiftBatch(GIFT_ID, 3000L, 200000L, GIFT_EXPIRE_FUTURE);
        seedMain(null, 1, 5000L, 100000L);
        // 形态一：主卡有余额但零批次
        JbkException missing = assertThrows(JbkException.class, () -> mergeService.merge(GIFT_ID, USER_ID));
        assertTrue(missing.getMessage().contains("正式水卡权益账本不一致"), "实际=" + missing.getMessage());
        // 形态二：批次合计少 1 分
        seedMainBatch(4999L, 100000L);
        JbkException offByFen = assertThrows(JbkException.class, () -> mergeService.merge(GIFT_ID, USER_ID));
        assertTrue(offByFen.getMessage().contains("正式水卡权益账本不一致"));
        // 形态三：批次合计少 1 mL
        jdbc.update("UPDATE ws_card_entitlement_batch SET REMAIN_AMOUNT_FEN = 5000, REMAIN_WATER_ML = 99999 "
                + "WHERE CARD_ID = " + MAIN_ID);
        JbkException offByMl = assertThrows(JbkException.class, () -> mergeService.merge(GIFT_ID, USER_ID));
        assertTrue(offByMl.getMessage().contains("正式水卡权益账本不一致"));
        // 形态四（R2 后语义细化）：额度对但批次被退款锁定——显式按「退款处理中」拒绝
        jdbc.update("UPDATE ws_card_entitlement_batch SET REMAIN_WATER_ML = 100000, BATCH_STATUS = 2 "
                + "WHERE CARD_ID = " + MAIN_ID);
        JbkException locked = assertThrows(JbkException.class, () -> mergeService.merge(GIFT_ID, USER_ID));
        assertTrue(locked.getMessage().contains("退款处理中"), "实际=" + locked.getMessage());
        // 全部拒绝零写入
        assertEquals(3000L, cardFen(GIFT_ID));
        assertEquals(1, cardStatus(GIFT_ID));
        assertEquals(5000L, cardFen(MAIN_ID));
        assertEquals(0L, flowCount());
    }

    /** R2 P1-1：卡级范围相同但赠卡批次范围更窄——逐批次范围验证必须拦住，零副作用。 */
    @Test
    void giftBatchScopeNarrowerThanCardRejected() {
        seedGift(GIFT_EXPIRE_FUTURE, 1, 1000L, 50000L);
        seedMain(null, 1, 5000L, 100000L);
        seedMainBatch(5000L, 100000L);
        jdbc.update("INSERT INTO ws_card_entitlement_batch(CARD_ID, USER_ID, SOURCE_TYPE, "
                + "PAY_AMOUNT_FEN, GRANT_AMOUNT_FEN, GRANT_BONUS_FEN, GRANT_WATER_ML, "
                + "REMAIN_AMOUNT_FEN, REMAIN_WATER_ML, EXPIRE_TIME, SCOPE_JSON, BATCH_STATUS, "
                + "REFUNDED_AMOUNT_FEN, VERSION, DATA_STATUS, CREATE_TIME) "
                + "VALUES(" + GIFT_ID + ", " + USER_ID + ", 4, 0, 1000, 1000, 50000, 1000, 50000, "
                + "'" + GIFT_EXPIRE_FUTURE + "', "
                + "'{\"scopeType\":\"specified\",\"stationIds\":[\"1\"]}', 6, 0, 1, 0, '20260601000000')");
        JbkException ex = assertThrows(JbkException.class, () -> mergeService.merge(GIFT_ID, USER_ID));
        assertTrue(ex.getMessage().contains("与卡范围不一致的权益批次"), "实际=" + ex.getMessage());
        assertEquals(1, cardStatus(GIFT_ID), "拒绝零副作用");
        assertEquals(5000L, cardFen(MAIN_ID));
        assertEquals(0L, flowCount());
    }

    /** R2 P1-1：赠卡批次范围为空（NULL）——normalize 按未配置默认拒绝，合并必须拦住。 */
    @Test
    void giftBatchScopeMissingRejected() {
        seedGift(GIFT_EXPIRE_FUTURE, 1, 1000L, 50000L);
        seedMain(null, 1, 5000L, 100000L);
        seedMainBatch(5000L, 100000L);
        seedGiftBatch(GIFT_ID, 1000L, 50000L, GIFT_EXPIRE_FUTURE);
        jdbc.update("UPDATE ws_card_entitlement_batch SET SCOPE_JSON = NULL WHERE CARD_ID = " + GIFT_ID);
        JbkException ex = assertThrows(JbkException.class, () -> mergeService.merge(GIFT_ID, USER_ID));
        assertTrue(ex.getMessage().contains("可用范围"), "实际=" + ex.getMessage());
        assertEquals(0L, flowCount());
    }

    /** R2 P1-1：主卡批次范围与主卡不一致——同样在任何写入前拒绝。 */
    @Test
    void mainBatchScopeMismatchRejected() {
        seedGift(GIFT_EXPIRE_FUTURE, 1, 1000L, 50000L);
        seedGiftBatch(GIFT_ID, 1000L, 50000L, GIFT_EXPIRE_FUTURE);
        seedMain(null, 1, 5000L, 100000L);
        jdbc.update("INSERT INTO ws_card_entitlement_batch(CARD_ID, USER_ID, SOURCE_TYPE, ORDER_ID, "
                + "PAY_AMOUNT_FEN, GRANT_AMOUNT_FEN, GRANT_BONUS_FEN, GRANT_WATER_ML, "
                + "REMAIN_AMOUNT_FEN, REMAIN_WATER_ML, EXPIRE_TIME, SCOPE_JSON, BATCH_STATUS, "
                + "REFUNDED_AMOUNT_FEN, VERSION, DATA_STATUS, CREATE_TIME) "
                + "VALUES(" + MAIN_ID + ", " + USER_ID + ", 2, 9902, 5000, 5000, 0, 100000, 5000, 100000, "
                + "NULL, '{\"scopeType\":\"specified\",\"deviceIds\":[\"9\"]}', 1, 0, 1, 0, '20260501000000')");
        JbkException ex = assertThrows(JbkException.class, () -> mergeService.merge(GIFT_ID, USER_ID));
        assertTrue(ex.getMessage().contains("与卡范围不一致的权益批次"), "实际=" + ex.getMessage());
        assertEquals(1, cardStatus(GIFT_ID));
        assertEquals(0L, flowCount());
    }

    /** P1-5：赠卡与主卡范围语义不一致时禁止合并（fail-closed，未拍板前不放行跨范围）。 */
    @Test
    void scopeMismatchRejected() {
        jdbc.update("INSERT INTO ws_card(ID, CARD_NO, USER_ID, BALANCE_AMOUNT, BALANCE_ML, "
                        + "SCOPE_JSON, EXPIRE_TIME, CARD_STATUS, ISSUE_ORDER_ID, DATA_STATUS) "
                        + "VALUES(?, 'GC-TEST-1', ?, 1000, 50000, ?, ?, 1, NULL, 0)",
                GIFT_ID, USER_ID, "{\"scopeType\":\"specified\",\"stationIds\":[\"1\"]}", GIFT_EXPIRE_FUTURE);
        seedMain(null, 1, 5000L, 100000L);
        seedMainBatch(5000L, 100000L);
        seedGiftBatch(GIFT_ID, 1000L, 50000L, GIFT_EXPIRE_FUTURE);
        JbkException ex = assertThrows(JbkException.class, () -> mergeService.merge(GIFT_ID, USER_ID));
        assertTrue(ex.getMessage().contains("可用范围不一致"), "实际=" + ex.getMessage());
        assertEquals(1, cardStatus(GIFT_ID), "拒绝零写入");
        assertEquals(0L, flowCount());
    }

    /** P1-2：实体带期卡（CARD_TYPE=2）不是赠卡，不享受合并出路。 */
    @Test
    void physicalFiniteCardRejectedAsSource() {
        jdbc.update("INSERT INTO ws_card(ID, CARD_NO, CARD_TYPE, USER_ID, BALANCE_AMOUNT, BALANCE_ML, "
                        + "SCOPE_JSON, EXPIRE_TIME, CARD_STATUS, ISSUE_ORDER_ID, DATA_STATUS) "
                        + "VALUES(?, 'PC-ENT-1', 2, ?, 1000, 50000, ?, ?, 1, NULL, 0)",
                GIFT_ID, USER_ID, SCOPE, GIFT_EXPIRE_FUTURE);
        seedMain(null, 1, 5000L, 100000L);
        seedMainBatch(5000L, 100000L);
        JbkException ex = assertThrows(JbkException.class, () -> mergeService.merge(GIFT_ID, USER_ID));
        assertTrue(ex.getMessage().contains("只有活动赠卡"), "实际=" + ex.getMessage());
    }

    /** P1-2：注销付费卡占名额但收不了权益——锁内明确拒绝并指向人工。 */
    @Test
    void cancelledMainCardRejectedWithGuidance() {
        seedGift(GIFT_EXPIRE_FUTURE, 1, 1000L, 50000L);
        seedGiftBatch(GIFT_ID, 1000L, 50000L, GIFT_EXPIRE_FUTURE);
        seedMain(null, 4, 0L, 0L);
        JbkException ex = assertThrows(JbkException.class, () -> mergeService.merge(GIFT_ID, USER_ID));
        assertTrue(ex.getMessage().contains("已注销仍占用名额"), "实际=" + ex.getMessage());
    }

    /** 账本断裂 fail-closed：批次合计 ≠ 卡余额（含存在退款锁定批次的情况）→ 拒绝且零写入。 */
    @Test
    void ledgerMismatchRejectedWithoutAnyWrite() {
        seedGift(GIFT_EXPIRE_FUTURE, 1, 3000L, 200000L);
        seedMain(null, 1, 5000L, 100000L);
        seedGiftBatch(GIFT_ID, 2000L, 200000L, GIFT_EXPIRE_FUTURE);

        JbkException ex = assertThrows(JbkException.class, () -> mergeService.merge(GIFT_ID, USER_ID));
        assertTrue(ex.getMessage().contains("账本不一致"), "实际=" + ex.getMessage());
        assertEquals(3000L, cardFen(GIFT_ID), "拒绝时零写入");
        assertEquals(5000L, cardFen(MAIN_ID));
        assertEquals(1, cardStatus(GIFT_ID));
        assertEquals(0L, flowCount());
    }

    /** 目标形态防线：冻结主卡、有限期存量付费主卡都不接收合并。 */
    @Test
    void frozenOrFiniteMainCardRejected() {
        seedGift(GIFT_EXPIRE_FUTURE, 1, 3000L, 200000L);
        seedMain(null, 2, 5000L, 100000L);
        seedGiftBatch(GIFT_ID, 3000L, 200000L, GIFT_EXPIRE_FUTURE);
        JbkException frozen = assertThrows(JbkException.class, () -> mergeService.merge(GIFT_ID, USER_ID));
        assertTrue(frozen.getMessage().contains("不可入账"), "实际=" + frozen.getMessage());

        jdbc.update("UPDATE ws_card SET CARD_STATUS = 1, EXPIRE_TIME = '20281231235959' WHERE ID = ?", MAIN_ID);
        JbkException finite = assertThrows(JbkException.class, () -> mergeService.merge(GIFT_ID, USER_ID));
        assertTrue(finite.getMessage().contains("历史有限期卡"), "实际=" + finite.getMessage());
        assertEquals(1, cardStatus(GIFT_ID), "两次拒绝都零写入");
        assertEquals(0L, flowCount());
    }

    /** 赠卡形态防线：付费卡（带订单锚）走合并接口一律拒绝。 */
    @Test
    void paidCardAsSourceRejected() {
        jdbc.update("INSERT INTO ws_card(ID, CARD_NO, USER_ID, BALANCE_AMOUNT, BALANCE_ML, "
                        + "SCOPE_JSON, EXPIRE_TIME, CARD_STATUS, ISSUE_ORDER_ID, DATA_STATUS) "
                        + "VALUES(?, 'WC-PAID-2', ?, 100, 100, ?, '20281231235959', 1, 666, 0)",
                GIFT_ID, USER_ID, SCOPE);
        seedMain(null, 1, 5000L, 100000L);
        JbkException ex = assertThrows(JbkException.class, () -> mergeService.merge(GIFT_ID, USER_ID));
        assertTrue(ex.getMessage().contains("只有活动赠卡"), "实际=" + ex.getMessage());
    }

    /** 定位分支：无主卡与多主卡都明确拒绝（不自动挑卡）。 */
    @Test
    void locatorZeroOrMultiplePaidCardsRejected() {
        seedGift(GIFT_EXPIRE_FUTURE, 1, 0L, 0L);
        stubLocatorReturning();
        JbkException none = assertThrows(JbkException.class, () -> mergeService.merge(GIFT_ID, USER_ID));
        assertTrue(none.getMessage().contains("暂无正式水卡"), "实际=" + none.getMessage());

        stubLocatorReturning(MAIN_ID, 302L);
        JbkException multi = assertThrows(JbkException.class, () -> mergeService.merge(GIFT_ID, USER_ID));
        assertTrue(multi.getMessage().contains("多张正式水卡"), "实际=" + multi.getMessage());
    }

    /** 非合并途径注销的赠卡：重放分支查不到 MERGE-OUT 锚，明确拒绝而非编造结果。 */
    @Test
    void cancelledByOtherPathRejected() {
        seedGift(GIFT_EXPIRE_FUTURE, 4, 0L, 0L);
        seedMain(null, 1, 5000L, 100000L);
        JbkException ex = assertThrows(JbkException.class, () -> mergeService.merge(GIFT_ID, USER_ID));
        assertTrue(ex.getMessage().contains("已注销"), "实际=" + ex.getMessage());
    }
}
