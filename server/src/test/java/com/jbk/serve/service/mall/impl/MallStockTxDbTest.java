package com.jbk.serve.service.mall.impl;

import com.jbk.serve.mapper.mall.WsMallCategoryMapper;
import com.jbk.serve.mapper.mall.WsMallProductMapper;
import com.jbk.serve.mapper.mall.WsMallSkuMapper;
import com.jbk.serve.mapper.mall.WsMallStockFlowMapper;
import com.jbk.serve.mapper.mall.WsMallStockMapper;
import com.jbk.serve.mapper.mall.WsMallWarehouseMapper;
import com.jbk.serve.mapper.ops.WsDomainEventMapper;
import com.jbk.serve.service.mall.IMallStockService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.serve.service.ops.impl.WsDomainEventServiceImpl;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.mall.bo.MallQueryBo;
import com.jbk.tool.data.mall.bo.MallStockAdjustBo;
import com.jbk.tool.data.mall.po.WsMallSku;
import com.jbk.tool.data.mall.po.WsMallStock;
import com.jbk.tool.data.mall.po.WsMallStockFlow;
import com.jbk.tool.data.mall.vo.MallSkuCandidateVo;
import com.jbk.tool.data.mall.vo.MallStockAdjustResultVo;
import com.jbk.tool.exception.JbkException;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E-09 S1 商城库存<b>真实 MySQL + 真实 Spring 事务</b>回归（任务书十条）。
 *
 * <p>钉住的性质无法用 Mock 证明：原子条件 UPDATE 的负库存不可达、流水唯一键的
 * 幂等裁决、库存与流水的同事务共生共死、以及 20 线程真并发下的数量守恒。</p>
 *
 * <p>无 Docker 环境自动跳过。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringJUnitConfig(MallStockTxDbTest.Ctx.class)
class MallStockTxDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static final long WH1 = 201L;
    private static final long WH2 = 202L;
    private static final long SKU1 = 401L;
    private static final long SKU2 = 402L;

    @Configuration
    @EnableTransactionManagement
    static class Ctx {
        @Bean
        DataSource dataSource() {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl(MYSQL.getJdbcUrl());
            ds.setUsername(MYSQL.getUsername());
            ds.setPassword(MYSQL.getPassword());
            // 20 线程并发出库用例：每线程独立事务连接
            ds.setMaximumPoolSize(24);
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
            // 与生产 MybatisPlusConfig 同构：selectPage 依赖分页拦截器（缺失则无 LIMIT、total 恒 0）
            com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor plusInterceptor =
                    new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();
            plusInterceptor.addInnerInterceptor(
                    new com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor(
                            com.baomidou.mybatisplus.annotation.DbType.MYSQL));
            factory.setPlugins(plusInterceptor);
            factory.setMapperLocations(new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                    .getResources("classpath:mapper/*/*.xml"));
            return new SqlSessionTemplate(factory.getObject());
        }

        private static <M> MapperFactoryBean<M> mapper(Class<M> type, SqlSessionTemplate template) {
            MapperFactoryBean<M> bean = new MapperFactoryBean<>(type);
            bean.setSqlSessionTemplate(template);
            return bean;
        }

        @Bean
        MapperFactoryBean<WsMallStockMapper> wsMallStockMapper(SqlSessionTemplate t) {
            return mapper(WsMallStockMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsMallStockFlowMapper> wsMallStockFlowMapper(SqlSessionTemplate t) {
            return mapper(WsMallStockFlowMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsMallWarehouseMapper> wsMallWarehouseMapper(SqlSessionTemplate t) {
            return mapper(WsMallWarehouseMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsMallSkuMapper> wsMallSkuMapper(SqlSessionTemplate t) {
            return mapper(WsMallSkuMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsMallProductMapper> wsMallProductMapper(SqlSessionTemplate t) {
            return mapper(WsMallProductMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsMallCategoryMapper> wsMallCategoryMapper(SqlSessionTemplate t) {
            return mapper(WsMallCategoryMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsDomainEventMapper> wsDomainEventMapper(SqlSessionTemplate t) {
            return mapper(WsDomainEventMapper.class, t);
        }

        @Bean
        IWsDomainEventService domainEventService() {
            // 真实现：审计与库存动作同事务是被测性质之一
            return new WsDomainEventServiceImpl();
        }

        @Bean
        IMallStockService mallStockService() {
            return new MallStockServiceImpl();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    @Autowired
    private IMallStockService stockService;
    @Autowired
    private WsMallStockMapper stockMapper;
    @Autowired
    private WsMallSkuMapper skuMapper;
    @Autowired
    private WsMallStockFlowMapper flowMapper;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        MallDbSchema.createAll(jdbc);
        MallDbSchema.truncateAll(jdbc);
        seedWarehouse(WH1, "MW-T-001");
        seedWarehouse(WH2, "MW-T-002");
        seedSku(SKU1, "MS-T-001");
        seedSku(SKU2, "MS-T-002");
    }

    private void seedWarehouse(long id, String no) {
        jdbc.update("INSERT INTO ws_mall_warehouse (ID, DATA_STATUS, CREATE_BY, CREATE_TIME,"
                + " UPDATE_BY, UPDATE_TIME, WAREHOUSE_NO, WAREHOUSE_NAME, CONTACT_NAME, CONTACT_PHONE,"
                + " PROVINCE_CODE, CITY_CODE, DISTRICT_CODE, WAREHOUSE_ADDRESS, SERVICE_SCOPE_JSON,"
                + " WAREHOUSE_STATUS, VERSION) VALUES (?,0,1,'20260808120000',1,'20260808120000',?,"
                + " '测试仓','仓管','13500000000','420000','420100','420111','测试地址',"
                + " '{\"scopeType\":\"districts\",\"districtCodes\":[\"420111\"]}',1,1)", id, no);
    }

    private void seedSku(long id, String no) {
        seedSku(id, no, "测试SKU");
    }

    private void seedSku(long id, String no, String name) {
        jdbc.update("INSERT INTO ws_mall_sku (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY,"
                + " UPDATE_TIME, SKU_NO, PRODUCT_ID, SKU_NAME, SPEC_SNAP, SALE_PRICE, WEIGHT_GRAM,"
                + " SKU_STATUS, VERSION) VALUES (?,0,1,'20260808120000',1,'20260808120000',?,301,"
                + " ?,'{}',1000,100,1,1)", id, no, name);
    }

    private void seedProduct(long id, String name) {
        jdbc.update("INSERT INTO ws_mall_product (ID, DATA_STATUS, CREATE_BY, CREATE_TIME,"
                + " UPDATE_BY, UPDATE_TIME, PRODUCT_NO, CATEGORY_ID, PRODUCT_NAME, PRODUCT_STATUS,"
                + " VERSION) VALUES (?,0,1,'20260808120000',1,'20260808120000',?,101,?,1,1)",
                id, "MP-T-" + id, name);
    }

    private int stockRowCount(long wh, long sku) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_mall_stock WHERE WAREHOUSE_ID=? AND SKU_ID=?",
                Integer.class, wh, sku);
    }

    private long flowCountFor(long wh, long sku) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_mall_stock_flow WHERE WAREHOUSE_ID=? AND SKU_ID=?",
                Long.class, wh, sku);
    }

    private MallStockAdjustBo bo(String requestId, long wh, long sku, int type, long qty) {
        MallStockAdjustBo bo = new MallStockAdjustBo();
        bo.setRequestId(requestId);
        bo.setWarehouseId(wh);
        bo.setSkuId(sku);
        bo.setFlowType(type);
        bo.setQuantity(qty);
        bo.setReason("测试动作");
        return bo;
    }

    private long availableOf(long wh, long sku) {
        // 行可能不存在（回滚用例）：容空按 0 计
        List<Long> values = jdbc.queryForList(
                "SELECT AVAILABLE_QTY FROM ws_mall_stock WHERE WAREHOUSE_ID=? AND SKU_ID=?",
                Long.class, wh, sku);
        return values.isEmpty() ? 0L : values.get(0);
    }

    private long flowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ws_mall_stock_flow", Long.class);
    }

    /** ① 正常入库：库存与流水同事务落库，AFTER 与终值一致，审计同事务在场。 */
    @Test
    void manualInWritesStockAndFlowInOneTx() {
        stockService.adjust(bo(UUID.randomUUID().toString(), WH1, SKU1, 1, 50));
        assertEquals(50L, availableOf(WH1, SKU1));
        Map<String, Object> flow = jdbc.queryForMap(
                "SELECT * FROM ws_mall_stock_flow WHERE WAREHOUSE_ID=? AND SKU_ID=?", WH1, SKU1);
        assertEquals(1L, ((Number) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_mall_stock_flow", Long.class)).longValue());
        assertEquals(50L, ((Number) flow.get("AVAILABLE_CHANGE")).longValue());
        assertEquals(50L, ((Number) flow.get("AVAILABLE_AFTER")).longValue());
        assertEquals(0L, ((Number) flow.get("RESERVED_AFTER")).longValue());
        assertEquals(1L, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_domain_event WHERE EVENT_TYPE=11", Long.class),
                "商城动作审计与库存同事务");
    }

    /** ② 正常出库。 */
    @Test
    void manualOutDecreasesAtomically() {
        stockService.adjust(bo(UUID.randomUUID().toString(), WH1, SKU1, 1, 50));
        stockService.adjust(bo(UUID.randomUUID().toString(), WH1, SKU1, 2, 20));
        assertEquals(30L, availableOf(WH1, SKU1));
        Map<String, Object> out = jdbc.queryForMap(
                "SELECT * FROM ws_mall_stock_flow WHERE FLOW_TYPE=2");
        assertEquals(-20L, ((Number) out.get("AVAILABLE_CHANGE")).longValue());
        assertEquals(30L, ((Number) out.get("AVAILABLE_AFTER")).longValue());
    }

    /** ③ 库存不足：拒绝且零流水零库存变化（原子条件 UPDATE 0 行→整事务回滚）。 */
    @Test
    void insufficientStockRejectsWithZeroFlow() {
        stockService.adjust(bo(UUID.randomUUID().toString(), WH1, SKU1, 1, 10));
        long flowsBefore = flowCount();
        JbkException rejected = assertThrows(JbkException.class,
                () -> stockService.adjust(bo(UUID.randomUUID().toString(), WH1, SKU1, 2, 11)));
        assertTrue(rejected.getMessage().contains("可售库存不足"));
        assertEquals(10L, availableOf(WH1, SKU1), "库存分毫不动");
        assertEquals(flowsBefore, flowCount(), "拒绝路径零流水");
    }

    /**
     * ⑫ R2-P0 新 SKU 首次入库闭环：候选查询数据源=SKU⋈商品、独立于库存行——
     * 新 SKU 无库存行也能查到并选中；首次入库经 upsert 恰建一行
     * （AVAILABLE=qty、RESERVED=0）、流水恰一条且 AFTER=库存终值；同号重放
     * 零副作用；另一无行新 SKU 直接出库仍拒绝且零副作用。
     */
    @Test
    void skuCandidatesAndFirstInboundCloseTheVerticalChain() {
        seedProduct(301L, "新品整箱水");
        seedSku(403L, "MS-T-403", "新品单箱");
        assertEquals(0, stockRowCount(WH1, 403L), "前置：新 SKU 无库存行");

        // 候选独立于库存行：关键字即可命中，身份 ID 恒 string，商品名与状态在场
        MallQueryBo query = new MallQueryBo();
        query.setKeyword("MS-T-403");
        PageDataVo<MallSkuCandidateVo> candidates = stockService.skuCandidates(query);
        assertEquals(1L, candidates.getTotal());
        MallSkuCandidateVo candidate = candidates.getList().get(0);
        assertEquals("403", candidate.getSkuId());
        assertEquals("MS-T-403", candidate.getSkuNo());
        assertEquals("新品单箱", candidate.getSkuName());
        assertEquals("301", candidate.getProductId());
        assertEquals("新品整箱水", candidate.getProductName());
        assertEquals(1, candidate.getSkuStatus());

        // 首次入库：upsert 建行
        String requestId = UUID.randomUUID().toString();
        MallStockAdjustResultVo first = stockService.adjust(bo(requestId, WH1, 403L, 1, 30));
        assertEquals(30L, first.getAvailableAfter());
        assertEquals(1, stockRowCount(WH1, 403L), "恰建一条仓×SKU库存行");
        assertEquals(30L, availableOf(WH1, 403L), "AVAILABLE=入库数量");
        assertEquals(0L, jdbc.queryForObject(
                "SELECT RESERVED_QTY FROM ws_mall_stock WHERE WAREHOUSE_ID=? AND SKU_ID=?",
                Long.class, WH1, 403L), "RESERVED 恒 0");
        assertEquals(1L, flowCountFor(WH1, 403L), "流水恰一条");
        assertEquals(30L, jdbc.queryForObject(
                "SELECT AVAILABLE_AFTER FROM ws_mall_stock_flow WHERE WAREHOUSE_ID=? AND SKU_ID=?",
                Long.class, WH1, 403L), "AFTER 与库存终值一致");

        // 同号重放：不重复建行、不重复流水、返回冻结值
        MallStockAdjustResultVo replay = stockService.adjust(bo(requestId, WH1, 403L, 1, 30));
        assertEquals(30L, replay.getAvailableAfter());
        assertEquals(1, stockRowCount(WH1, 403L));
        assertEquals(1L, flowCountFor(WH1, 403L));
        assertEquals(30L, availableOf(WH1, 403L));

        // 另一无库存行新 SKU：直接出库拒绝且零副作用
        seedSku(404L, "MS-T-404", "新品套装");
        assertThrows(JbkException.class,
                () -> stockService.adjust(bo(UUID.randomUUID().toString(), WH1, 404L, 2, 1)));
        assertEquals(0, stockRowCount(WH1, 404L), "拒绝路径零建行");
        assertEquals(0L, flowCountFor(WH1, 404L), "拒绝路径零流水");
    }

    /** ⑬ R2-P0 候选分页硬上限：请求 size=999 也只回 50 行，total 如实不裁。 */
    @Test
    void skuCandidatesPageSizeHardCapped() {
        for (int i = 0; i < 60; i++) {
            seedSku(500L + i, "MS-CAP-" + i, "上限SKU" + i);
        }
        MallQueryBo query = new MallQueryBo();
        query.setSize(999L);
        PageDataVo<MallSkuCandidateVo> page = stockService.skuCandidates(query);
        assertEquals(50, page.getList().size(), "单页硬上限 50");
        assertEquals(62L, page.getTotal(), "total 如实（60 新种 + 2 基础种子）");
    }

    /**
     * ③b 负库存库层硬闸（R1-P2-1）：绕过应用层原子条件 UPDATE 的原始写入同样
     * 写不进负值——命名 CHECK（chk_mall_stock_*_nonneg）在库层拒绝。
     */
    @Test
    void rawNegativeWriteRejectedByCheckConstraint() {
        stockService.adjust(bo(UUID.randomUUID().toString(), WH1, SKU1, 1, 5));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE ws_mall_stock SET AVAILABLE_QTY=-1 WHERE WAREHOUSE_ID=? AND SKU_ID=?",
                WH1, SKU1), "可售负值被 CHECK 拒绝");
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE ws_mall_stock SET RESERVED_QTY=-1 WHERE WAREHOUSE_ID=? AND SKU_ID=?",
                WH1, SKU1), "预占负值被 CHECK 拒绝");
        assertEquals(5L, availableOf(WH1, SKU1), "拒绝后值分毫未动");
    }

    /** ④ 同 requestId 同参数重放：每次返回同一冻结结果，不重复调整。 */
    @Test
    void sameRequestIdReplayIsIdempotent() {
        String requestId = UUID.randomUUID().toString();
        MallStockAdjustResultVo first = stockService.adjust(bo(requestId, WH1, SKU1, 1, 50));
        assertEquals(50L, first.getAvailableAfter());
        assertEquals(requestId, first.getRequestId());
        for (int i = 0; i < 4; i++) {
            MallStockAdjustResultVo replay = stockService.adjust(bo(requestId, WH1, SKU1, 1, 50));
            assertEquals(first.getAvailableAfter(), replay.getAvailableAfter(), "重放同源同值");
            assertEquals(first.getRequestId(), replay.getRequestId());
        }
        assertEquals(50L, availableOf(WH1, SKU1), "重放不叠加");
        assertEquals(1L, flowCount(), "恒一条流水");
    }

    /**
     * ④b 重放返回冻结原结果（R1-P1-2）：动作A→动作B→重放A 必须返回 A 时刻的
     * 后置值（能与"重放时刻当前库存"明确区分），库存=A+B、流水恰两条、零副作用。
     */
    @Test
    void replayReturnsFrozenOriginalResultNotCurrentStock() {
        String requestA = UUID.randomUUID().toString();
        MallStockAdjustResultVo first = stockService.adjust(bo(requestA, WH1, SKU1, 1, 50));
        assertEquals(50L, first.getAvailableAfter());

        stockService.adjust(bo(UUID.randomUUID().toString(), WH1, SKU1, 1, 20));
        assertEquals(70L, availableOf(WH1, SKU1), "动作B已生效，当前库存 70");

        MallStockAdjustResultVo replay = stockService.adjust(bo(requestA, WH1, SKU1, 1, 50));
        assertEquals(requestA, replay.getRequestId());
        assertEquals(String.valueOf(WH1), replay.getWarehouseId());
        assertEquals(String.valueOf(SKU1), replay.getSkuId());
        assertEquals(1, replay.getFlowType());
        assertEquals(50L, replay.getAvailableChange());
        assertEquals(50L, replay.getAvailableAfter(),
                "重放必须返回 A 流水的冻结后置值 50，而非当前库存 70");
        assertEquals(0L, replay.getReservedAfter());

        assertEquals(70L, availableOf(WH1, SKU1), "重放零副作用：库存仍是 A+B");
        assertEquals(2L, flowCount(), "流水恰两条（A、B），重放不加行");
        assertThrows(JbkException.class,
                () -> stockService.adjust(bo(requestA, WH1, SKU1, 1, 60)),
                "有中间动作后，同号改参依旧拒绝");
    }

    /** ⑤ 同 requestId 改参数（仓/SKU/动作/数量任一）：明确拒绝。 */
    @Test
    void sameRequestIdWithChangedParamsIsRejected() {
        String requestId = UUID.randomUUID().toString();
        stockService.adjust(bo(requestId, WH1, SKU1, 1, 50));
        assertThrows(JbkException.class,
                () -> stockService.adjust(bo(requestId, WH2, SKU1, 1, 50)), "改仓库拒绝");
        assertThrows(JbkException.class,
                () -> stockService.adjust(bo(requestId, WH1, SKU2, 1, 50)), "改SKU拒绝");
        assertThrows(JbkException.class,
                () -> stockService.adjust(bo(requestId, WH1, SKU1, 2, 50)), "改动作拒绝");
        assertThrows(JbkException.class,
                () -> stockService.adjust(bo(requestId, WH1, SKU1, 1, 60)), "改数量拒绝");
        assertEquals(50L, availableOf(WH1, SKU1));
        assertEquals(1L, flowCount(), "拒绝路径零新流水");
    }

    /** ⑥ 20 线程并发出库：恰 10 成功 10 拒绝，终值恰 0，绝无负库存。 */
    @Test
    void twentyThreadsConcurrentOutNeverGoNegative() throws Exception {
        stockService.adjust(bo(UUID.randomUUID().toString(), WH1, SKU1, 1, 10));
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Callable<Void>> jobs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            jobs.add(() -> {
                ready.countDown();
                go.await(10, TimeUnit.SECONDS);
                try {
                    stockService.adjust(bo(UUID.randomUUID().toString(), WH1, SKU1, 2, 1));
                    success.incrementAndGet();
                }
                catch (JbkException e) {
                    rejected.incrementAndGet();
                }
                return null;
            });
        }
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (Callable<Void> job : jobs) {
                futures.add(pool.submit(job));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            for (Future<Void> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }
        finally {
            pool.shutdownNow();
        }
        assertEquals(10, success.get(), "恰好 10 次出库成功");
        assertEquals(10, rejected.get(), "恰好 10 次库存不足拒绝");
        assertEquals(0L, availableOf(WH1, SKU1), "终值恰 0——负库存物理不可达");
        assertEquals(11L, flowCount(), "1 入库 + 10 成功出库；拒绝零流水");
    }

    /** ⑦ 同仓同 SKU：终值与全部流水 AFTER 连续一致（每条 prev+change==after）。 */
    @Test
    void flowAfterValuesFormContinuousChain() {
        stockService.adjust(bo(UUID.randomUUID().toString(), WH1, SKU1, 1, 50));
        stockService.adjust(bo(UUID.randomUUID().toString(), WH1, SKU1, 2, 20));
        stockService.adjust(bo(UUID.randomUUID().toString(), WH1, SKU1, 3, 5));
        stockService.adjust(bo(UUID.randomUUID().toString(), WH1, SKU1, 4, 35));
        List<WsMallStockFlow> flows = flowMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(WsMallStockFlow.class)
                        .eq(WsMallStockFlow::getWarehouseId, WH1)
                        .eq(WsMallStockFlow::getSkuId, SKU1)
                        .orderByAsc(WsMallStockFlow::getId));
        long running = 0;
        for (WsMallStockFlow flow : flows) {
            running += flow.getAvailableChange();
            assertEquals(running, flow.getAvailableAfter(),
                    "流水链断裂：flow=" + flow.getBizIdempotencyKey());
        }
        assertEquals(running, availableOf(WH1, SKU1), "终值与流水链一致");
        assertEquals(0L, availableOf(WH1, SKU1), "50-20+5-35=0");
    }

    /** ⑧ 不同仓库并发互不阻塞：双仓并发动作全部成功且各自正确。 */
    @Test
    void differentWarehousesDoNotBlockEachOther() throws Exception {
        stockService.adjust(bo(UUID.randomUUID().toString(), WH1, SKU1, 1, 30));
        stockService.adjust(bo(UUID.randomUUID().toString(), WH2, SKU1, 1, 30));
        int perWarehouse = 5;
        ExecutorService pool = Executors.newFixedThreadPool(perWarehouse * 2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < perWarehouse; i++) {
            for (long wh : new long[]{WH1, WH2}) {
                futures.add(pool.submit(() -> {
                    go.await(10, TimeUnit.SECONDS);
                    stockService.adjust(bo(UUID.randomUUID().toString(), wh, SKU1, 2, 2));
                    return null;
                }));
            }
        }
        try {
            go.countDown();
            for (Future<Void> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }
        finally {
            pool.shutdownNow();
        }
        assertEquals(20L, availableOf(WH1, SKU1), "30-5×2=20");
        assertEquals(20L, availableOf(WH2, SKU1), "双仓独立守恒");
    }

    /** ⑨ 业务编号与库存共键唯一约束物理有效。 */
    @Test
    void uniqueConstraintsAreEnforced() {
        assertThrows(DuplicateKeyException.class, () -> skuMapper.insert(new WsMallSku()
                        .setSkuNo("MS-T-001").setProductId(301L).setSkuName("重复编号")
                        .setSpecSnap("{}").setSalePrice(100L).setWeightGram(0L)
                        .setSkuStatus(1).setVersion(1)),
                "SKU_NO 唯一键必须拦截复用");
        stockService.adjust(bo(UUID.randomUUID().toString(), WH1, SKU1, 1, 1));
        assertThrows(DuplicateKeyException.class, () -> stockMapper.insert(new WsMallStock()
                        .setWarehouseId(WH1).setSkuId(SKU1)
                        .setAvailableQty(0L).setReservedQty(0L).setVersion(1)),
                "uk(WAREHOUSE_ID,SKU_ID) 必须拦截重复库存行");
        WsMallStockFlow existing = flowMapper.selectList(null).get(0);
        assertThrows(DuplicateKeyException.class, () -> flowMapper.insert(new WsMallStockFlow()
                        .setBizIdempotencyKey(existing.getBizIdempotencyKey())
                        .setWarehouseId(WH1).setSkuId(SKU1).setFlowType(1)
                        .setAvailableChange(1L).setReservedChange(0L)
                        .setAvailableAfter(1L).setReservedAfter(0L)
                        .setFlowReason("重复键").setOperatorId(0L)),
                "流水幂等键必须拦截重放");
    }

    /** ⑩ 事务中途失败：库存与流水共同回滚（外层回滚裹挟 REQUIRED 内层）。 */
    @Test
    void midTransactionFailureRollsBackStockAndFlowTogether() {
        String requestId = UUID.randomUUID().toString();
        tx.execute(status -> {
            stockService.adjust(bo(requestId, WH1, SKU1, 1, 50));
            status.setRollbackOnly();
            return null;
        });
        assertEquals(0L, availableOf(WH1, SKU1), "库存随外层回滚归零");
        assertEquals(0L, flowCount(), "流水随外层回滚清空——两者同生共死");
        assertEquals(0L, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_domain_event", Long.class), "审计一并回滚");
        // 回滚后同请求号可重新执行（键未被占用）
        stockService.adjust(bo(requestId, WH1, SKU1, 1, 50));
        assertEquals(50L, availableOf(WH1, SKU1));
    }
}
