package com.jbk.serve.service.aftersale.batch;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.aftersale.WsCardEntitlementBatchMapper;
import com.jbk.serve.mapper.aftersale.WsEntitlementAllocationMapper;
import com.jbk.serve.mapper.trade.TradeCardMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.service.delivery.impl.DeliveryDbSchema;
import com.jbk.tool.data.aftersale.po.WsCardEntitlementBatch;
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
import org.springframework.dao.DuplicateKeyException;
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
 * 权益批次台账（E2E-04 包D-4）的<b>真实 MySQL + 真实 Spring 事务</b>集成测试。
 *
 * <p>被测物是「行锁 + CAS 前态 + 唯一键 + 事务回滚」的合力，用 Mock 一条都证明不了。本类分两层：</p>
 * <ol>
 *   <li><b>直打 Mapper</b>：每条 WHERE 谓词各有一条用例。这一层不经过 {@link EntitlementLedger}——
 *       Java 侧的前置检查会把 SQL 谓词整个遮住，只走服务层时删掉任意一条谓词测试照样全绿
 *       （包B/包C 都踩过这个坑）；</li>
 *   <li><b>台账行为</b>：分摊次序、跨批次贪心、凑不足整体回滚、重放撞唯一键、
 *       回补 LIFO、部分回补、历史消费无分摊行时并入不可退桶、并发只有一个赢家、
 *       以及贯穿全部用例的不变式「逐卡批次剩余合计 == 卡聚合值」。</li>
 * </ol>
 *
 * <p>无 Docker 环境自动跳过。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = EntitlementLedgerDbTest.Ctx.class)
class EntitlementLedgerDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_entitlement_it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static final long CARD_ID = 700L;
    private static final long OTHER_CARD = 701L;
    private static final long USER_ID = 9L;
    private static final long OP_USER = 9L;
    private static final long ORDER_ID = 8001L;
    private static final String NOW = "20260729120000";
    private static final String KEY_A = "DISPENSE:WT-A";
    private static final String KEY_B = "DELIVERY:WD-B";

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
            // 与生产同一个审计字段填充器：@TableLogic 的 DATA_STATUS 依赖 INSERT 填充
            com.baomidou.mybatisplus.core.config.GlobalConfig globalConfig =
                    new com.baomidou.mybatisplus.core.config.GlobalConfig();
            globalConfig.setMetaObjectHandler(new com.jbk.tool.config.system.mybatis.MpMetaObjectHandler());
            factory.setGlobalConfig(globalConfig);
            factory.setMapperLocations(new org.springframework.core.io.support
                    .PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/trade/TradeCardMapper.xml"));
            return new SqlSessionTemplate(factory.getObject());
        }

        @Bean
        MapperFactoryBean<WsCardEntitlementBatchMapper> batchMapper(SqlSessionTemplate t) {
            return mapper(WsCardEntitlementBatchMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsEntitlementAllocationMapper> allocationMapper(SqlSessionTemplate t) {
            return mapper(WsEntitlementAllocationMapper.class, t);
        }

        @Bean
        MapperFactoryBean<TradeCardMapper> tradeCardMapper(SqlSessionTemplate t) {
            MapperFactoryBean<TradeCardMapper> bean = new MapperFactoryBean<>(TradeCardMapper.class);
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
        EntitlementLedger entitlementLedger(WsCardEntitlementBatchMapper batchMapper,
                                            WsEntitlementAllocationMapper allocationMapper,
                                            TradeCardMapper tradeCardMapper,
                                            WsWalletFlowMapper walletFlowMapper) {
            return new EntitlementLedger(batchMapper, allocationMapper, tradeCardMapper, walletFlowMapper);
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
    private EntitlementLedger ledger;
    @Autowired
    private WsCardEntitlementBatchMapper batchMapper;
    @Autowired
    private WsEntitlementAllocationMapper allocationMapper;
    @Autowired
    private TradeCardMapper tradeCardMapper;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        DeliveryDbSchema.createAll(jdbc);
        DeliveryDbSchema.createEntitlementTables(jdbc);
        jdbc.execute("TRUNCATE TABLE ws_entitlement_allocation");
        jdbc.execute("TRUNCATE TABLE ws_card_entitlement_batch");
        jdbc.execute("TRUNCATE TABLE ws_wallet_flow");
        jdbc.execute("DELETE FROM ws_card");
    }

    // ==================== 一、直打 Mapper：每条谓词一条用例 ====================

    /** 选取次序：早到期 → 晚到期 → 永久；同到期时间按 ID。SQL 的 ORDER BY 与内存比较器必须同口径。 */
    @Test
    void consumableOrderPutsEarliestExpiryFirstAndPermanentLast() {
        long permanent = insertBatch(CARD_ID, 1, null, 100L, 100L);
        long late = insertBatch(CARD_ID, 1, "20261231000000", 100L, 100L);
        long early = insertBatch(CARD_ID, 1, "20260801000000", 100L, 100L);
        long sameExpiry = insertBatch(CARD_ID, 1, "20260801000000", 100L, 100L);

        List<Long> ids = batchMapper.lockConsumableByCard(CARD_ID).stream()
                .map(WsCardEntitlementBatch::getId).toList();

        assertEquals(List.of(early, sameExpiry, late, permanent), ids,
                "永久批次必须排最后：先用会过期的才是对用户最有利、也最不容易两头落空的顺序");
    }

    /** 6不可退在可消费集合内（存量卡的全部余额都在这种批次里），2退款锁定与3已退款不在。 */
    @Test
    void consumableSetIncludesNonRefundableButExcludesRefundLockedAndRefunded() {
        long legacy = insertBatch(CARD_ID, 6, null, 100L, 0L);
        insertBatch(CARD_ID, 2, null, 100L, 0L);
        insertBatch(CARD_ID, 3, null, 100L, 0L);
        insertBatch(CARD_ID, 1, null, 0L, 0L);

        List<Long> ids = batchMapper.lockConsumableByCard(CARD_ID).stream()
                .map(WsCardEntitlementBatch::getId).toList();

        assertEquals(List.of(legacy), ids,
                "6=退不了款但花得掉；2/3 必须挡住；剩余全零的批次不进结果集");
    }

    /** consume 的 REMAIN 谓词：不足即 0 行，绝不透支成负数。 */
    @Test
    void consumeRejectsWhenRemainIsNotEnough() {
        long id = insertBatch(CARD_ID, 1, null, 500L, 0L);

        assertEquals(0, batchMapper.consume(id, 1, 501L, 0L, OP_USER, NOW));
        assertEquals(1, batchMapper.consume(id, 1, 500L, 0L, OP_USER, NOW));
        assertEquals(0L, remainFen(id));
    }

    /** consume 的 VERSION 谓词：版本漂移即 0 行（并发改动后调用方必须整体回滚）。 */
    @Test
    void consumeRejectsStaleVersion() {
        long id = insertBatch(CARD_ID, 1, null, 500L, 0L);
        assertEquals(1, batchMapper.consume(id, 1, 100L, 0L, OP_USER, NOW));

        assertEquals(0, batchMapper.consume(id, 1, 100L, 0L, OP_USER, NOW),
                "版本已被上一次扣减推到 2，拿旧版本再扣必须落空");
        assertEquals(400L, remainFen(id));
    }

    /** consume 的状态谓词：退款锁定中的批次一分都不能扣——这是退款金额不被消费吃掉的库层闸。 */
    @Test
    void consumeRejectsRefundLockedBatch() {
        long id = insertBatch(CARD_ID, 2, null, 500L, 0L);

        assertEquals(0, batchMapper.consume(id, 1, 100L, 0L, OP_USER, NOW));
        assertEquals(500L, remainFen(id));
    }

    /** consume 的逻辑删除谓词：删除行不参与扣减。 */
    @Test
    void consumeRejectsLogicallyDeletedBatch() {
        long id = insertBatch(CARD_ID, 1, null, 500L, 0L);
        jdbc.update("UPDATE ws_card_entitlement_batch SET DATA_STATUS=1 WHERE ID=?", id);

        assertEquals(0, batchMapper.consume(id, 1, 100L, 0L, OP_USER, NOW));
    }

    /** restoreAllocated 的发放量上限：回补不得让剩余超过当初的发放量。 */
    @Test
    void restoreRejectsExceedingGrantedAmount() {
        long id = insertBatch(CARD_ID, 1, null, 1_000L, 1_000L);
        assertEquals(1, batchMapper.consume(id, 1, 200L, 200L, OP_USER, NOW));

        assertEquals(0, batchMapper.restoreAllocated(id, 2, 201L, 0L, OP_USER, NOW),
                "回补 201 会让剩余超过发放量，必须 0 行——超发的额度会变成可以套现的余额");
        assertEquals(1, batchMapper.restoreAllocated(id, 2, 200L, 200L, OP_USER, NOW));
        assertEquals(1_000L, remainFen(id));
    }

    /** restoreAllocated 的状态谓词：已退款批次不接受回补（否则同一笔权益既退了钱又还在卡上）。 */
    @Test
    void restoreRejectsRefundedBatch() {
        long id = insertBatch(CARD_ID, 3, null, 0L, 0L);
        jdbc.update("UPDATE ws_card_entitlement_batch SET GRANT_AMOUNT_FEN=1000 WHERE ID=?", id);

        assertEquals(0, batchMapper.restoreAllocated(id, 1, 100L, 0L, OP_USER, NOW));
    }

    /** growLegacy 的三条身份谓词：只有「历史聚合 + 不可退 + 零实付」的桶能被扩容。 */
    @Test
    void growLegacyOnlyAcceptsTheNonRefundableAggregateBucket() {
        long normal = insertBatch(CARD_ID, 1, null, 100L, 0L);
        long paidLegacy = insertBatch(CARD_ID, 6, null, 100L, 0L);
        jdbc.update("UPDATE ws_card_entitlement_batch SET SOURCE_TYPE=3, PAY_AMOUNT_FEN=5000 WHERE ID=?", paidLegacy);
        long legacy = insertLegacy(CARD_ID, 100L, 0L);

        assertEquals(0, batchMapper.growLegacy(normal, 1, 50L, 0L, OP_USER, NOW),
                "可退批次不能凭空长发放量——发放量是水量套餐折算公式的分母");
        assertEquals(0, batchMapper.growLegacy(paidLegacy, 1, 50L, 0L, OP_USER, NOW),
                "带实付金额的批次不是无归属桶，扩容它等于造出可退款基准");
        assertEquals(1, batchMapper.growLegacy(legacy, 1, 50L, 0L, OP_USER, NOW));
        assertEquals(150L, remainFen(legacy));
        assertEquals(150L, jdbc.queryForObject(
                "SELECT GRANT_AMOUNT_FEN FROM ws_card_entitlement_batch WHERE ID=?", Long.class, legacy),
                "扩容必须同时抬发放量，否则下一次回补会撞上「剩余不得超过发放量」");
    }

    /** reduceAllocated 的额度前态：这是返还幂等的锚点，前态不符即 0 行。 */
    @Test
    void reduceAllocatedRequiresExactPriorAmounts() {
        long batchId = insertBatch(CARD_ID, 1, null, 1_000L, 1_000L);
        long allocId = insertAllocation(batchId, KEY_A, 300L, 400L);

        assertEquals(0, allocationMapper.reduceAllocated(allocId, 299L, 400L, 100L, 0L, OP_USER, NOW));
        assertEquals(1, allocationMapper.reduceAllocated(allocId, 300L, 400L, 100L, 0L, OP_USER, NOW));
        Map<String, Object> row = allocRow(allocId);
        assertEquals(200L, ((Number) row.get("ALLOC_AMOUNT_FEN")).longValue());
        assertEquals(0, ((Number) row.get("REVERSED_FLAG")).intValue(),
                "还没归零就置冲正标记，会让部分回补后的批次看起来完全没被消费过，退款折算随之多退");

        assertEquals(1, allocationMapper.reduceAllocated(allocId, 200L, 400L, 200L, 400L, OP_USER, NOW));
        assertEquals(1, ((Number) allocRow(allocId).get("REVERSED_FLAG")).intValue());
        assertEquals(0, allocationMapper.reduceAllocated(allocId, 0L, 0L, 0L, 0L, OP_USER, NOW),
                "已冲正行不得再被冲减");
    }

    // ==================== 二、台账行为 ====================

    /** 单批次足额：一条分摊行，批次与卡同步，不变式成立。 */
    @Test
    void allocateWritesOneRowWhenSingleBatchCovers() {
        long batchId = insertBatch(CARD_ID, 1, null, 1_000L, 2_000L);

        int touched = tx.execute(status -> ledger.allocateOnConsume(ref(KEY_A), 300L, 500L, OP_USER, NOW));

        assertEquals(1, touched);
        assertEquals(700L, remainFen(batchId));
        assertEquals(1_500L, remainMl(batchId));
        List<Map<String, Object>> rows = allocRows(KEY_A);
        assertEquals(1, rows.size());
        assertEquals(batchId, ((Number) rows.get(0).get("BATCH_ID")).longValue());
        assertEquals(300L, ((Number) rows.get(0).get("ALLOC_AMOUNT_FEN")).longValue());
        assertEquals(500L, ((Number) rows.get(0).get("ALLOC_WATER_ML")).longValue());
        assertEquals(1, ((Number) rows.get(0).get("ALLOC_SEQ")).intValue());
    }

    /** 跨批次贪心：金额与水量各自独立推进，先扣早到期的那批，序号从 1 递增。 */
    @Test
    void allocateSpansBatchesInConsumeOrderWithIndependentDimensions() {
        long early = insertBatch(CARD_ID, 1, "20260801000000", 400L, 0L);
        long late = insertBatch(CARD_ID, 1, "20261231000000", 400L, 900L);

        tx.execute(status -> ledger.allocateOnConsume(ref(KEY_A), 600L, 300L, OP_USER, NOW));

        assertEquals(0L, remainFen(early), "早到期批次的 400 分必须先被扣光");
        assertEquals(200L, remainFen(late));
        assertEquals(600L, remainMl(late), "水量只有晚到期批次有，故整 300 从它扣");
        List<Map<String, Object>> rows = allocRows(KEY_A);
        assertEquals(2, rows.size());
        assertEquals(early, ((Number) rows.get(0).get("BATCH_ID")).longValue());
        assertEquals(400L, ((Number) rows.get(0).get("ALLOC_AMOUNT_FEN")).longValue());
        assertEquals(0L, ((Number) rows.get(0).get("ALLOC_WATER_ML")).longValue());
        assertEquals(200L, ((Number) rows.get(1).get("ALLOC_AMOUNT_FEN")).longValue());
        assertEquals(300L, ((Number) rows.get(1).get("ALLOC_WATER_ML")).longValue());
        assertEquals(2, ((Number) rows.get(1).get("ALLOC_SEQ")).intValue());
    }

    /** 凑不足：整笔回滚，已扣的批次一并撤销，一条分摊行都不留。 */
    @Test
    void allocateRollsBackEverythingWhenBatchesCannotCover() {
        long a = insertBatch(CARD_ID, 1, "20260801000000", 400L, 0L);
        long b = insertBatch(CARD_ID, 1, "20261231000000", 300L, 0L);

        assertThrows(JbkException.class,
                () -> tx.execute(status -> ledger.allocateOnConsume(ref(KEY_A), 800L, 0L, OP_USER, NOW)));

        assertEquals(400L, remainFen(a), "第一个批次已扣的额度必须随事务回滚");
        assertEquals(300L, remainFen(b));
        assertEquals(0, allocRows(KEY_A).size());
    }

    /** 重放同一次消费：撞 uk_alloc_biz_batch，整事务回滚（幂等靠唯一键，不靠查重）。 */
    @Test
    void replayingSameConsumeHitsUniqueKeyAndRollsBack() {
        long batchId = insertBatch(CARD_ID, 1, null, 1_000L, 0L);
        tx.execute(status -> ledger.allocateOnConsume(ref(KEY_A), 300L, 0L, OP_USER, NOW));

        assertThrows(DuplicateKeyException.class,
                () -> tx.execute(status -> ledger.allocateOnConsume(ref(KEY_A), 300L, 0L, OP_USER, NOW)));

        assertEquals(700L, remainFen(batchId), "重放不得把批次再扣一次");
        assertEquals(1, allocRows(KEY_A).size());
    }

    /** 回补 LIFO：后扣的批次先还。全额回补是贪心分摊的精确逆运算。 */
    @Test
    void restoreReturnsToLastConsumedBatchFirst() {
        long early = insertBatch(CARD_ID, 1, "20260801000000", 400L, 0L);
        long late = insertBatch(CARD_ID, 1, "20261231000000", 400L, 0L);
        tx.execute(status -> ledger.allocateOnConsume(ref(KEY_A), 600L, 0L, OP_USER, NOW));

        // 部分回补 150：只还最后被扣的那 200（late），early 保持扣空
        tx.execute(status -> {
            ledger.restoreOnRefundBack(cardAfter(150L, 0L), KEY_A, 150L, 0L, OP_USER, NOW);
            return null;
        });

        assertEquals(0L, remainFen(early), "早到期批次先被扣空，部分回补不还给它");
        assertEquals(350L, remainFen(late), "late 消费后剩 200，回补 150 后为 350");
        List<Map<String, Object>> rows = allocRows(KEY_A);
        assertEquals(400L, ((Number) rows.get(0).get("ALLOC_AMOUNT_FEN")).longValue(), "early 的分摊不动");
        assertEquals(50L, ((Number) rows.get(1).get("ALLOC_AMOUNT_FEN")).longValue(), "late 的分摊被冲减 150");
        assertEquals(0, ((Number) rows.get(1).get("REVERSED_FLAG")).intValue());
    }

    /** 全额回补：分摊行归零并置冲正标记，批次回到消费前，不变式恢复。 */
    @Test
    void fullRestoreReversesAllocationRowsAndRestoresInvariant() {
        long batchId = insertBatch(CARD_ID, 1, null, 1_000L, 800L);
        tx.execute(status -> ledger.allocateOnConsume(ref(KEY_A), 300L, 500L, OP_USER, NOW));

        tx.execute(status -> {
            ledger.restoreOnRefundBack(cardAfter(1_000L, 800L), KEY_A, 300L, 500L, OP_USER, NOW);
            return null;
        });

        assertEquals(1_000L, remainFen(batchId));
        assertEquals(800L, remainMl(batchId));
        Map<String, Object> row = allocRows(KEY_A).get(0);
        assertEquals(0L, ((Number) row.get("ALLOC_AMOUNT_FEN")).longValue());
        assertEquals(0L, ((Number) row.get("ALLOC_WATER_ML")).longValue());
        assertEquals(1, ((Number) row.get("REVERSED_FLAG")).intValue());
    }

    /**
     * D-4 上线前的历史消费没有分摊行：按真实缺口并入不可退桶。
     *
     * <p>卡余额 700（已被一次上线前的消费扣过），批次剩余 700 —— 两侧此刻是平的。
     * 返还 300 之后卡是 1000，缺口 300，故不可退桶应恰好长 300 而不是凭空长 700。</p>
     */
    @Test
    void restoreWithoutAllocationGrowsLegacyBucketByTheRealGapOnly() {
        long legacy = insertLegacy(CARD_ID, 700L, 0L);

        tx.execute(status -> {
            ledger.restoreOnRefundBack(cardAfter(1_000L, 0L), "DELIVERY:LEGACY-1", 300L, 0L, OP_USER, NOW);
            return null;
        });

        assertEquals(1_000L, remainFen(legacy));
        assertEquals(6, ((Number) jdbc.queryForMap(
                "SELECT BATCH_STATUS FROM ws_card_entitlement_batch WHERE ID=" + legacy)
                .get("BATCH_STATUS")).intValue(), "并入的残量必须留在不可退桶里");
    }

    /** 缺口为零时一分不补：上线前那笔消费没扣过批次，批次侧本来就没少。 */
    @Test
    void restoreAddsNothingWhenBatchesAlreadyCoverTheCard() {
        long legacy = insertLegacy(CARD_ID, 1_000L, 0L);

        tx.execute(status -> {
            ledger.restoreOnRefundBack(cardAfter(1_000L, 0L), "DELIVERY:LEGACY-2", 300L, 0L, OP_USER, NOW);
            return null;
        });

        assertEquals(1_000L, remainFen(legacy), "批次合计已等于卡聚合值，再补就是凭空造权益");
    }

    /** 该卡没有不可退桶时新建一个：形态固定为历史聚合/不可退/零实付。 */
    @Test
    void restoreCreatesLegacyBucketWhenCardHasNone() {
        tx.execute(status -> {
            ledger.restoreOnRefundBack(cardAfter(300L, 0L), "DELIVERY:LEGACY-3", 300L, 0L, OP_USER, NOW);
            return null;
        });

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT SOURCE_TYPE,BATCH_STATUS,PAY_AMOUNT_FEN,REMAIN_AMOUNT_FEN,GRANT_AMOUNT_FEN,ORDER_ID "
                        + "FROM ws_card_entitlement_batch WHERE CARD_ID=" + CARD_ID);
        assertEquals(3, ((Number) row.get("SOURCE_TYPE")).intValue());
        assertEquals(6, ((Number) row.get("BATCH_STATUS")).intValue());
        assertEquals(0L, ((Number) row.get("PAY_AMOUNT_FEN")).longValue());
        assertEquals(300L, ((Number) row.get("REMAIN_AMOUNT_FEN")).longValue());
        assertEquals(300L, ((Number) row.get("GRANT_AMOUNT_FEN")).longValue());
        assertTrue(row.get("ORDER_ID") == null, "无归属桶绝不挂到任何充值订单上");
    }

    /** 分摊行指向别人的卡：fail-closed，绝不把钱补进别人的批次。 */
    @Test
    void restoreRejectsAllocationPointingToAnotherCard() {
        long foreign = insertBatch(OTHER_CARD, 1, null, 1_000L, 0L);
        insertAllocationForCard(foreign, OTHER_CARD, KEY_B, 300L, 0L);

        assertThrows(JbkException.class, () -> tx.execute(status -> {
            ledger.restoreOnRefundBack(cardAfter(300L, 0L), KEY_B, 300L, 0L, OP_USER, NOW);
            return null;
        }));

        assertEquals(1_000L, remainFen(foreign));
    }

    /** 回补目标批次已被退款锁定：fail-closed 转人工，不静默跳过。 */
    @Test
    void restoreRejectsWhenTargetBatchIsRefundLocked() {
        long batchId = insertBatch(CARD_ID, 1, null, 1_000L, 0L);
        tx.execute(status -> ledger.allocateOnConsume(ref(KEY_A), 300L, 0L, OP_USER, NOW));
        jdbc.update("UPDATE ws_card_entitlement_batch SET BATCH_STATUS=2, REFUND_LOCKED_BY=1 WHERE ID=?", batchId);

        assertThrows(JbkException.class, () -> tx.execute(status -> {
            ledger.restoreOnRefundBack(cardAfter(1_000L, 0L), KEY_A, 300L, 0L, OP_USER, NOW);
            return null;
        }));

        assertEquals(700L, remainFen(batchId));
    }

    /**
     * 已被退款冲正的分摊不再回补，残量并入不可退桶。
     *
     * <p>形状：这次消费扣的批次后来被整批退款（{@code reverseByBatch} 置了冲正标记，
     * 但额度作为账本事实留着）。此时对同一次消费的返还<b>不能</b>回补那个批次——
     * 那批权益已经连本带利退给用户了，补回卡上就是「同一笔权益既退了钱又留着权益」。</p>
     */
    @Test
    void restoreSkipsAllocationsAlreadyReversedByRefund() {
        long batchId = insertBatch(CARD_ID, 1, null, 1_000L, 0L);
        tx.execute(status -> ledger.allocateOnConsume(ref(KEY_A), 300L, 0L, OP_USER, NOW));
        // 模拟退款结算：批次落已退款并清零，分摊被整批冲正（额度保留）
        allocationMapper.reverseByBatch(batchId, OP_USER, NOW);
        jdbc.update("UPDATE ws_card_entitlement_batch SET BATCH_STATUS=3, REMAIN_AMOUNT_FEN=0 WHERE ID=?", batchId);

        // 卡此刻是 300 分（返还刚加回去的），批次侧合计为 0 → 缺口 300 落进不可退桶
        // （该卡还没有不可退桶，故台账会按固定形态新建一个）
        tx.execute(status -> {
            ledger.restoreOnRefundBack(cardAfter(300L, 0L), KEY_A, 300L, 0L, OP_USER, NOW);
            return null;
        });

        assertEquals(0L, remainFen(batchId), "已退款批次一分都不能被补回来");
        Map<String, Object> legacy = jdbc.queryForMap(
                "SELECT SOURCE_TYPE,BATCH_STATUS,REMAIN_AMOUNT_FEN FROM ws_card_entitlement_batch "
                        + "WHERE CARD_ID=" + CARD_ID + " AND SOURCE_TYPE=3");
        assertEquals(300L, ((Number) legacy.get("REMAIN_AMOUNT_FEN")).longValue(),
                "返还额度改落不可退桶，既保住不变式也不制造可套现额度");
        assertEquals(6, ((Number) legacy.get("BATCH_STATUS")).intValue(), "并入的必须是不可退桶");
        assertEquals(300L, ((Number) allocRows(KEY_A).get(0).get("ALLOC_AMOUNT_FEN")).longValue(),
                "已冲正分摊的额度是账本事实，不因回补被改写");
    }

    /** 同卡两笔并发消费：批次总额只够一笔，必须恰好一个赢家，且批次绝不透支。 */
    @Test
    void concurrentConsumeOnSameCardAllowsExactlyOneWinner() throws Exception {
        long batchId = insertBatch(CARD_ID, 1, null, 1_000L, 0L);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> f1 = pool.submit(() -> tryConsume(barrier, KEY_A, 600L));
            Future<Boolean> f2 = pool.submit(() -> tryConsume(barrier, KEY_B, 600L));
            int winners = (f1.get(30, TimeUnit.SECONDS) ? 1 : 0) + (f2.get(30, TimeUnit.SECONDS) ? 1 : 0);

            assertEquals(1, winners, "批次只够一笔，两笔都成功就是超发");
            assertEquals(400L, remainFen(batchId));
            assertEquals(1, jdbc.queryForList("SELECT ID FROM ws_entitlement_allocation").size());
        } finally {
            pool.shutdownNow();
        }
    }

    private boolean tryConsume(CyclicBarrier barrier, String bizKey, long fen) {
        try {
            barrier.await(20, TimeUnit.SECONDS);
            tx.execute(status -> ledger.allocateOnConsume(ref(bizKey), fen, 0L, OP_USER, NOW));
            return true;
        } catch (Exception failed) {
            return false;
        }
    }

    // ==================== 夹具 ====================

    private EntitlementLedger.ConsumeRef ref(String bizKey) {
        return new EntitlementLedger.ConsumeRef(CARD_ID, USER_ID, ORDER_ID, null, bizKey);
    }

    private EntitlementLedger.CardAfter cardAfter(long amountAfter, long mlAfter) {
        return new EntitlementLedger.CardAfter(CARD_ID, USER_ID, null, amountAfter, mlAfter);
    }

    private long insertBatch(long cardId, int status, String expireTime, long fen, long ml) {
        jdbc.update("INSERT INTO ws_card_entitlement_batch(DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,"
                        + "UPDATE_TIME,CARD_ID,USER_ID,SOURCE_TYPE,PAY_AMOUNT_FEN,GRANT_AMOUNT_FEN,"
                        + "GRANT_BONUS_FEN,GRANT_WATER_ML,REMAIN_AMOUNT_FEN,REMAIN_WATER_ML,EXPIRE_TIME,"
                        + "BATCH_STATUS,REFUNDED_AMOUNT_FEN,VERSION) "
                        + "VALUES(0,1,?,1,?,?,?,2,1000,?,0,?,?,?,?,?,0,1)",
                NOW, NOW, cardId, USER_ID, fen, ml, fen, ml, expireTime, status);
        return lastId();
    }

    private long insertLegacy(long cardId, long fen, long ml) {
        EntitlementFixture.seedLegacyBatch(jdbc, cardId, USER_ID, fen, ml);
        return jdbc.queryForObject("SELECT ID FROM ws_card_entitlement_batch WHERE CARD_ID=? AND SOURCE_TYPE=3",
                Long.class, cardId);
    }

    private long insertAllocation(long batchId, String bizKey, long fen, long ml) {
        return insertAllocationForCard(batchId, CARD_ID, bizKey, fen, ml);
    }

    private long insertAllocationForCard(long batchId, long cardId, String bizKey, long fen, long ml) {
        jdbc.update("INSERT INTO ws_entitlement_allocation(DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,"
                        + "UPDATE_TIME,BATCH_ID,CARD_ID,ORDER_ID,BIZ_KEY,ALLOC_SEQ,ALLOC_AMOUNT_FEN,"
                        + "ALLOC_WATER_ML,REVERSED_FLAG) VALUES(0,1,?,1,?,?,?,?,?,1,?,?,0)",
                NOW, NOW, batchId, cardId, ORDER_ID, bizKey, fen, ml);
        return lastId();
    }

    private long lastId() {
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        assertNotNull(id);
        return id;
    }

    private int batchStatus(long batchId) {
        return jdbc.queryForObject(
                "SELECT BATCH_STATUS FROM ws_card_entitlement_batch WHERE ID = " + batchId, Integer.class);
    }

    private long remainFen(long batchId) {
        return jdbc.queryForObject("SELECT REMAIN_AMOUNT_FEN FROM ws_card_entitlement_batch WHERE ID=?",
                Long.class, batchId);
    }

    private long remainMl(long batchId) {
        return jdbc.queryForObject("SELECT REMAIN_WATER_ML FROM ws_card_entitlement_batch WHERE ID=?",
                Long.class, batchId);
    }

    private Map<String, Object> allocRow(long id) {
        return jdbc.queryForMap("SELECT * FROM ws_entitlement_allocation WHERE ID=" + id);
    }

    private List<Map<String, Object>> allocRows(String bizKey) {
        return jdbc.queryForList("SELECT * FROM ws_entitlement_allocation WHERE BIZ_KEY=? ORDER BY ALLOC_SEQ", bizKey);
    }

    // ==================== 四、settleExpired：批次到期清算唯一入口（审计 P0-1） ====================

    private void insertCard(long cardId, long fen, long ml, String expire) {
        jdbc.update("INSERT INTO ws_card(ID, CARD_NO, USER_ID, BALANCE_AMOUNT, BALANCE_ML, "
                        + "EXPIRE_TIME, CARD_STATUS, DATA_STATUS) VALUES(?, CONCAT('VC', ?), ?, ?, ?, ?, 1, 0)",
                cardId, cardId, OP_USER, fen, ml, expire);
    }

    /** 在真事务内模拟消费链形态：锁卡 → settleExpired（返回清算后终值）。 */
    private EntitlementLedger.CardAfter settleInTx(long cardId) {
        return tx.execute(status -> {
            var card = tradeCardMapper.selectByIdForUpdate(cardId);
            return ledger.settleExpired(card, OP_USER, NOW);
        });
    }

    /** 只清已到期批次：未到期与永久批次原样保留，卡与批次同步扣减，账本等式保持。 */
    @Test
    void settleExpiredClearsOnlyExpiredBatchesAndKeepsEquation() {
        insertCard(CARD_ID, 1000L, 50000L, null);
        long expired = insertBatch(CARD_ID, 6, "20250101000000", 300L, 20000L);
        long alive = insertBatch(CARD_ID, 1, "20991231000000", 400L, 20000L);
        long permanent = insertBatch(CARD_ID, 1, null, 300L, 10000L);

        EntitlementLedger.CardAfter after = settleInTx(CARD_ID);

        assertEquals(700L, after.amountAfter());
        assertEquals(30000L, after.mlAfter());
        assertEquals(700L, jdbc.queryForObject(
                "SELECT BALANCE_AMOUNT FROM ws_card WHERE ID = " + CARD_ID, Long.class));
        assertEquals(5, batchStatus(expired), "到期批次置5");
        assertEquals(0L, remainFen(expired), "到期批次剩余清零");
        assertEquals(400L, remainFen(alive), "未到期批次不动");
        assertEquals(300L, remainFen(permanent), "永久批次不动");
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM ws_wallet_flow WHERE FLOW_TYPE = 6 "
                + "AND BIZ_IDEMPOTENCY_KEY = 'EXPIRE-BATCH:" + expired + "'", Long.class),
                "每个作废批次恰一条 EXPIRE_CLEAR 流水，键含批次 ID");
        // 清算后账本等式：卡余额 == 全部未删批次剩余合计
        assertEquals(700L, jdbc.queryForObject("SELECT IFNULL(SUM(REMAIN_AMOUNT_FEN),0) "
                + "FROM ws_card_entitlement_batch WHERE CARD_ID = " + CARD_ID + " AND DATA_STATUS = 0",
                Long.class));
    }

    /** 幂等：重复清算零动作、不重复扣卡、不追加流水。 */
    @Test
    void settleExpiredRerunIsNoop() {
        insertCard(CARD_ID, 500L, 0L, null);
        insertBatch(CARD_ID, 1, "20250101000000", 500L, 0L);
        settleInTx(CARD_ID);
        EntitlementLedger.CardAfter second = settleInTx(CARD_ID);

        assertEquals(0L, second.amountAfter(), "重复清算不得再扣");
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM ws_wallet_flow", Long.class),
                "重复清算不得追加流水");
    }

    /** 无过期批次：零动作、零流水、终值即入参原值。 */
    @Test
    void settleExpiredWithNothingExpiredIsNoop() {
        insertCard(CARD_ID, 800L, 100L, null);
        insertBatch(CARD_ID, 1, null, 800L, 100L);

        EntitlementLedger.CardAfter after = settleInTx(CARD_ID);

        assertEquals(800L, after.amountAfter());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM ws_wallet_flow", Long.class));
    }

    /** 多过期批次：逐批次一条流水，AFTER 链按次序递减连续。 */
    @Test
    void settleExpiredWritesChainedFlowsPerBatch() {
        insertCard(CARD_ID, 900L, 0L, null);
        long first = insertBatch(CARD_ID, 1, "20240101000000", 500L, 0L);
        long second = insertBatch(CARD_ID, 6, "20250101000000", 400L, 0L);

        settleInTx(CARD_ID);

        assertEquals(400L, jdbc.queryForObject("SELECT AMOUNT_AFTER FROM ws_wallet_flow "
                + "WHERE BIZ_IDEMPOTENCY_KEY = 'EXPIRE-BATCH:" + first + "'", Long.class),
                "最早到期先作废：第一条流水后余 400");
        assertEquals(0L, jdbc.queryForObject("SELECT AMOUNT_AFTER FROM ws_wallet_flow "
                + "WHERE BIZ_IDEMPOTENCY_KEY = 'EXPIRE-BATCH:" + second + "'", Long.class),
                "第二条流水后归零，AFTER 链连续");
    }

    /** R2 P0-2：卡余额高于批次合计（无批次支撑的余额）——写入前的完整等式必须拦住，零写入。 */
    @Test
    void settleExpiredFailsClosedWhenCardExceedsBatchTotal() {
        insertCard(CARD_ID, 1000L, 0L, null);
        long expired = insertBatch(CARD_ID, 1, "20250101000000", 300L, 0L);

        JbkException ex = assertThrows(JbkException.class, () -> settleInTx(CARD_ID));
        assertTrue(ex.getMessage().contains("账本等式不成立"), "实际=" + ex.getMessage());
        assertEquals(1000L, jdbc.queryForObject(
                "SELECT BALANCE_AMOUNT FROM ws_card WHERE ID = " + CARD_ID, Long.class), "零写入");
        assertEquals(300L, remainFen(expired));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM ws_wallet_flow", Long.class));
    }

    /** R2 P0-2：全量视野含退款锁定(2)批次——等式把它算进合计，且它绝不被作废。 */
    @Test
    void settleExpiredSeesRefundLockedBatchInEquationButNeverClearsIt() {
        insertCard(CARD_ID, 800L, 0L, null);
        long expired = insertBatch(CARD_ID, 1, "20250101000000", 500L, 0L);
        long locked = insertBatch(CARD_ID, 2, "20250101000000", 300L, 0L);

        EntitlementLedger.CardAfter after = settleInTx(CARD_ID);

        assertEquals(300L, after.amountAfter(), "只清可消费态的过期批次，锁定余额保留在卡上");
        assertEquals(5, batchStatus(expired));
        assertEquals(0L, remainFen(expired));
        assertEquals(2, batchStatus(locked), "退款锁定批次原样");
        assertEquals(300L, remainFen(locked));
    }

    /** 卡与批次不同步（批次合计高于卡）时 fail-closed：reverseCardAssets 余量不足 0 行整体回滚。 */
    @Test
    void settleExpiredFailsClosedWhenLedgerAlreadyBroken() {
        insertCard(CARD_ID, 100L, 0L, null);
        long expired = insertBatch(CARD_ID, 1, "20250101000000", 500L, 0L);

        assertThrows(JbkException.class, () -> settleInTx(CARD_ID));
        assertEquals(100L, jdbc.queryForObject(
                "SELECT BALANCE_AMOUNT FROM ws_card WHERE ID = " + CARD_ID, Long.class), "零写入");
        assertEquals(500L, remainFen(expired));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM ws_wallet_flow", Long.class));
    }
}
