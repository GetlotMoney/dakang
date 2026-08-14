package com.jbk.serve.service.mall.impl;

import com.jbk.serve.mapper.mall.WsMallCategoryMapper;
import com.jbk.serve.mapper.mall.WsMallProductMapper;
import com.jbk.serve.mapper.mall.WsMallSkuMapper;
import com.jbk.serve.mapper.mall.WsMallStockFlowMapper;
import com.jbk.serve.mapper.mall.WsMallStockMapper;
import com.jbk.serve.mapper.mall.WsMallWarehouseMapper;
import com.jbk.serve.mapper.ops.WsDomainEventMapper;
import com.jbk.serve.service.mall.IMallCategoryService;
import com.jbk.serve.service.mall.IMallProductService;
import com.jbk.serve.service.mall.IMallStockService;
import com.jbk.serve.service.mall.IMallWarehouseService;
import com.jbk.serve.service.mall.IMiniMallService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.serve.service.ops.impl.WsDomainEventServiceImpl;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.mall.bo.MallCategoryBo;
import com.jbk.tool.data.mall.bo.MallIdBo;
import com.jbk.tool.data.mall.bo.MallProductBo;
import com.jbk.tool.data.mall.bo.MallQueryBo;
import com.jbk.tool.data.mall.bo.MallShelfBo;
import com.jbk.tool.data.mall.bo.MallSkuItemBo;
import com.jbk.tool.data.mall.bo.MallStockAdjustBo;
import com.jbk.tool.data.mall.bo.MallWarehouseBo;
import com.jbk.tool.data.mall.bo.MiniMallHomeBo;
import com.jbk.tool.data.mall.vo.MallSkuCandidateVo;
import com.jbk.tool.data.mall.vo.MallWarehouseVo;
import com.jbk.tool.data.mall.vo.MiniMallHomeVo;
import com.jbk.tool.data.mall.vo.MiniMallProductDetailVo;
import com.jbk.tool.exception.JbkException;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E-09 S1 商城目录/上架闸/小程序白名单真库回归。
 *
 * <p>钉住：上架三闸（停用分类/无启用SKU/无可售库存）、停用仓不进可售聚合、
 * 小程序只见已上架+启用SKU+有货布尔、电话脱敏、Long ID string 化、
 * 规格与范围 JSON 的服务端唯一校验。</p>
 *
 * <p>无 Docker 环境自动跳过。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringJUnitConfig(MallCatalogDbTest.Ctx.class)
class MallCatalogDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

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
        MapperFactoryBean<WsMallCategoryMapper> wsMallCategoryMapper(SqlSessionTemplate t) {
            return mapper(WsMallCategoryMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsMallProductMapper> wsMallProductMapper(SqlSessionTemplate t) {
            return mapper(WsMallProductMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsMallSkuMapper> wsMallSkuMapper(SqlSessionTemplate t) {
            return mapper(WsMallSkuMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsMallWarehouseMapper> wsMallWarehouseMapper(SqlSessionTemplate t) {
            return mapper(WsMallWarehouseMapper.class, t);
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
        MapperFactoryBean<WsDomainEventMapper> wsDomainEventMapper(SqlSessionTemplate t) {
            return mapper(WsDomainEventMapper.class, t);
        }

        @Bean
        IWsDomainEventService domainEventService() {
            return new WsDomainEventServiceImpl();
        }

        @Bean
        IMallCategoryService mallCategoryService() {
            return new MallCategoryServiceImpl();
        }

        @Bean
        IMallProductService mallProductService() {
            return new MallProductServiceImpl();
        }

        @Bean
        IMallWarehouseService mallWarehouseService() {
            return new MallWarehouseServiceImpl();
        }

        @Bean
        IMiniMallService miniMallService() {
            return new MiniMallServiceImpl();
        }

        @Bean
        IMallStockService mallStockService() {
            // 纵向链用例需要真实库存动作（首次入库 upsert 建行）
            return new MallStockServiceImpl();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    @Autowired
    private IMallCategoryService categoryService;
    @Autowired
    private IMallProductService productService;
    @Autowired
    private IMallWarehouseService warehouseService;
    @Autowired
    private IMiniMallService miniMallService;
    @Autowired
    private IMallStockService stockService;
    @Autowired
    private JdbcTemplate jdbc;

    private static final long CAT_ON = 101L;
    private static final long CAT_OFF = 102L;
    private static final long WH_ON = 201L;
    private static final long WH_OFF = 202L;

    @BeforeEach
    void reset() {
        MallDbSchema.createAll(jdbc);
        MallDbSchema.truncateAll(jdbc);
        seedCategory(CAT_ON, "MC-T-ON", 1);
        seedCategory(CAT_OFF, "MC-T-OFF", 2);
        seedWarehouse(WH_ON, "MW-T-ON", 1);
        seedWarehouse(WH_OFF, "MW-T-OFF", 2);
    }

    private void seedCategory(long id, String code, int status) {
        jdbc.update("INSERT INTO ws_mall_category (ID, DATA_STATUS, CREATE_BY, CREATE_TIME,"
                + " UPDATE_BY, UPDATE_TIME, CATEGORY_CODE, CATEGORY_NAME, CATEGORY_SORT, CATEGORY_STATUS)"
                + " VALUES (?,0,1,'20260808120000',1,'20260808120000',?,?,1,?)", id, code, code, status);
    }

    private void seedWarehouse(long id, String no, int status) {
        jdbc.update("INSERT INTO ws_mall_warehouse (ID, DATA_STATUS, CREATE_BY, CREATE_TIME,"
                + " UPDATE_BY, UPDATE_TIME, WAREHOUSE_NO, WAREHOUSE_NAME, CONTACT_NAME, CONTACT_PHONE,"
                + " PROVINCE_CODE, CITY_CODE, DISTRICT_CODE, WAREHOUSE_ADDRESS, SERVICE_SCOPE_JSON,"
                + " WAREHOUSE_STATUS, VERSION) VALUES (?,0,1,'20260808120000',1,'20260808120000',?,"
                + " '测试仓','仓管','13511112222','420000','420100','420111','测试地址',"
                + " '{\"scopeType\":\"districts\",\"districtCodes\":[\"420111\"]}',?,1)", id, no, status);
    }

    private long seedProduct(long id, long categoryId, String no, int status, int version) {
        jdbc.update("INSERT INTO ws_mall_product (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY,"
                + " UPDATE_TIME, PRODUCT_NO, CATEGORY_ID, PRODUCT_NAME, PRODUCT_SUBTITLE, PRODUCT_STATUS,"
                + " VERSION) VALUES (?,0,1,'20260808120000',1,'20260808120000',?,?,?,'副标题',?,?)",
                id, no, categoryId, "测试商品" + id, status, version);
        return id;
    }

    private long seedSku(long id, long productId, String no, int status, long salePrice) {
        jdbc.update("INSERT INTO ws_mall_sku (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY,"
                + " UPDATE_TIME, SKU_NO, PRODUCT_ID, SKU_NAME, SPEC_SNAP, SALE_PRICE, WEIGHT_GRAM,"
                + " SKU_STATUS, VERSION) VALUES (?,0,1,'20260808120000',1,'20260808120000',?,?,?,"
                + " '{\"规格\":\"测试\"}',?,100,?,1)", id, no, productId, "SKU" + id, salePrice, status);
        return id;
    }

    private void seedStock(long wh, long sku, long qty) {
        jdbc.update("INSERT INTO ws_mall_stock (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY,"
                + " UPDATE_TIME, WAREHOUSE_ID, SKU_ID, AVAILABLE_QTY, RESERVED_QTY, VERSION)"
                + " VALUES (0,1,'20260808120000',1,'20260808120000',?,?,?,0,1)", wh, sku, qty);
    }

    private MallShelfBo shelf(long id, int version) {
        MallShelfBo bo = new MallShelfBo();
        bo.setId(id);
        bo.setVersion(version);
        return bo;
    }

    private MallIdBo idBo(long id) {
        MallIdBo bo = new MallIdBo();
        bo.setId(id);
        return bo;
    }

    /** 真实表审计列均 NOT NULL；分类与前置仓首次写必须依赖统一 FieldFill 完整落痕。 */
    @Test
    void createPathsPopulateRequiredAuditColumns() {
        MallCategoryBo category = new MallCategoryBo();
        category.setCategoryCode("MC-T-AUDIT");
        category.setCategoryName("审计字段分类");
        category.setCategorySort(9);
        Long categoryId = categoryService.save(category);

        MallWarehouseBo warehouse = new MallWarehouseBo();
        warehouse.setWarehouseNo("MW-T-AUDIT");
        warehouse.setWarehouseName("审计字段前置仓");
        warehouse.setContactName("仓管");
        warehouse.setContactPhone("13511112222");
        warehouse.setProvinceCode("420000");
        warehouse.setCityCode("420100");
        warehouse.setDistrictCode("420111");
        warehouse.setWarehouseAddress("测试地址");
        warehouse.setDistrictCodes(List.of("420111"));
        Long warehouseId = warehouseService.save(warehouse);

        for (String tableAndId : List.of(
                "ws_mall_category:" + categoryId,
                "ws_mall_warehouse:" + warehouseId)) {
            String[] parts = tableAndId.split(":");
            Map<String, Object> audit = jdbc.queryForMap("SELECT DATA_STATUS,CREATE_BY,CREATE_TIME,"
                    + "UPDATE_BY,UPDATE_TIME FROM " + parts[0] + " WHERE ID=?", Long.valueOf(parts[1]));
            assertEquals(0, ((Number) audit.get("DATA_STATUS")).intValue());
            assertNotNull(audit.get("CREATE_BY"));
            assertNotNull(audit.get("CREATE_TIME"));
            assertNotNull(audit.get("UPDATE_BY"));
            assertNotNull(audit.get("UPDATE_TIME"));
        }
    }

    /** 上架三闸①：停用分类阻断。 */
    @Test
    void disabledCategoryBlocksPublish() {
        seedProduct(301L, CAT_OFF, "MP-T-301", 1, 1);
        seedSku(401L, 301L, "MS-T-401", 1, 1000);
        seedStock(WH_ON, 401L, 10);
        JbkException blocked = assertThrows(JbkException.class,
                () -> productService.publish(shelf(301L, 1)));
        assertTrue(blocked.getMessage().contains("分类已停用"));
        assertEquals(1, statusOf(301L), "商品保持草稿");
    }

    /** 上架三闸②：无启用 SKU 阻断（只有停用 SKU 不算）。 */
    @Test
    void missingEnabledSkuBlocksPublish() {
        seedProduct(302L, CAT_ON, "MP-T-302", 1, 1);
        seedSku(402L, 302L, "MS-T-402", 2, 1000);
        seedStock(WH_ON, 402L, 10);
        JbkException blocked = assertThrows(JbkException.class,
                () -> productService.publish(shelf(302L, 1)));
        assertTrue(blocked.getMessage().contains("无启用 SKU"));
    }

    /** 上架三闸③：无可售库存阻断——停用仓里的库存不算可售。 */
    @Test
    void stockOnlyInDisabledWarehouseBlocksPublish() {
        seedProduct(303L, CAT_ON, "MP-T-303", 1, 1);
        seedSku(403L, 303L, "MS-T-403", 1, 1000);
        seedStock(WH_OFF, 403L, 99);
        JbkException blocked = assertThrows(JbkException.class,
                () -> productService.publish(shelf(303L, 1)));
        assertTrue(blocked.getMessage().contains("无可售库存"));
        // 启用仓补货后放行；重复上架被状态闸拦截
        seedStock(WH_ON, 403L, 1);
        assertTrue(productService.publish(shelf(303L, 1)));
        assertEquals(2, statusOf(303L));
        assertEquals(1L, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_domain_event WHERE EVENT_TYPE=11", Long.class),
                "上架审计与状态迁移同事务");
        assertThrows(JbkException.class, () -> productService.publish(shelf(303L, 2)));
    }

    /** 上下架 VERSION CAS：旧版本拒绝、正确版本成功且版本推进。 */
    @Test
    void shelfTransitionsUseVersionCas() {
        seedProduct(304L, CAT_ON, "MP-T-304", 1, 1);
        seedSku(404L, 304L, "MS-T-404", 1, 1000);
        seedStock(WH_ON, 404L, 5);
        assertTrue(productService.publish(shelf(304L, 1)));
        assertEquals(2, versionOf(304L), "CAS 推进版本");
        JbkException stale = assertThrows(JbkException.class,
                () -> productService.unpublish(shelf(304L, 1)));
        assertTrue(stale.getMessage().contains("已变化"));
        assertTrue(productService.unpublish(shelf(304L, 2)));
        assertEquals(3, statusOf(304L));
        // 下架不删 SKU/库存
        assertEquals(1L, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_mall_sku WHERE PRODUCT_ID=304", Long.class));
        assertEquals(1L, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_mall_stock WHERE SKU_ID=404", Long.class));
    }

    /** 小程序首页：只见已上架；停用仓库存=缺货；最低售价取启用 SKU 最小值；ID 恒 string。 */
    @Test
    void miniHomeShowsOnlyPublishedWithSellableAggregation() {
        // A：上架、双 SKU（2400/4500）、启用仓有货
        seedProduct(305L, CAT_ON, "MP-T-305", 2, 2);
        seedSku(405L, 305L, "MS-T-405", 1, 2400);
        seedSku(406L, 305L, "MS-T-406", 1, 4500);
        seedStock(WH_ON, 405L, 3);
        // B：草稿——端上不可见
        seedProduct(306L, CAT_ON, "MP-T-306", 1, 1);
        seedSku(407L, 306L, "MS-T-407", 1, 1000);
        seedStock(WH_ON, 407L, 3);
        // C：上架但只有停用仓有货——显示缺货
        seedProduct(307L, CAT_ON, "MP-T-307", 2, 2);
        seedSku(408L, 307L, "MS-T-408", 1, 1800);
        seedStock(WH_OFF, 408L, 50);
        MiniMallHomeVo home = miniMallService.home(new MiniMallHomeBo());
        assertEquals(List.of("MC-T-ON"), home.getCategories().stream()
                .map(MiniMallHomeVo.CategoryItem::getCategoryName).toList(), "只见启用分类");
        Map<String, MiniMallHomeVo.ProductCard> cards = home.getProducts().stream()
                .collect(java.util.stream.Collectors.toMap(
                        MiniMallHomeVo.ProductCard::getProductId, c -> c));
        assertEquals(2, cards.size(), "草稿商品不可见");
        MiniMallHomeVo.ProductCard cardA = cards.get("305");
        assertNotNull(cardA, "商品ID恒 string 出参");
        assertEquals(2400L, cardA.getMinSalePriceFen(), "最低售价=启用 SKU 最小值");
        assertTrue(cardA.getInStock());
        MiniMallHomeVo.ProductCard cardC = cards.get("307");
        assertNotNull(cardC);
        assertFalse(cardC.getInStock(), "停用仓库存不进可售聚合——缺货");
    }

    /** 小程序详情：非上架按不存在；只回启用 SKU；SKU 级有货布尔；规格结构化。 */
    @Test
    void miniDetailWhitelistsEnabledSkusAndBooleansOnly() {
        seedProduct(308L, CAT_ON, "MP-T-308", 2, 2);
        seedSku(409L, 308L, "MS-T-409", 1, 2400);
        seedSku(410L, 308L, "MS-T-410", 1, 4500);
        seedSku(411L, 308L, "MS-T-411", 2, 100);
        seedStock(WH_ON, 409L, 2);
        MiniMallProductDetailVo detail = miniMallService.productDetail(idBo(308L));
        assertEquals("308", detail.getProductId());
        assertTrue(detail.getInStock());
        assertEquals(2, detail.getSkus().size(), "停用 SKU 不下发");
        Map<String, MiniMallProductDetailVo.SkuItem> skus = detail.getSkus().stream()
                .collect(java.util.stream.Collectors.toMap(
                        MiniMallProductDetailVo.SkuItem::getSkuId, s -> s));
        assertTrue(skus.get("409").getInStock());
        assertFalse(skus.get("410").getInStock(), "无库存 SKU=缺货布尔");
        assertEquals("测试", skus.get("409").getSpecs().get("规格"), "规格回结构化键值");
        // 草稿/下架对端上等同不存在
        seedProduct(309L, CAT_ON, "MP-T-309", 3, 3);
        assertThrows(JbkException.class, () -> miniMallService.productDetail(idBo(309L)));
    }

    /** 前置仓出参：电话恒脱敏，原文绝不出接口；范围回结构化行政区码。 */
    @Test
    void warehousePhoneAlwaysMaskedInResponses() {
        MallWarehouseVo vo = warehouseService.detail(idBo(WH_ON));
        assertEquals("135****2222", vo.getMaskedPhone(), "PhoneMask 统一口径");
        assertEquals(List.of("420111"), vo.getDistrictCodes());
        assertEquals(String.valueOf(WH_ON), vo.getId(), "ID 恒 string");
    }

    /** 范围/规格 JSON 服务端唯一校验：非法行政区码拒绝；规格超限拒绝；划线价低于售价拒绝。 */
    @Test
    void serverSideJsonValidatorsFailClosed() {
        assertThrows(JbkException.class,
                () -> MallWarehouseServiceImpl.buildScopeJson(List.of("42011")), "五位码拒绝");
        assertThrows(JbkException.class,
                () -> MallWarehouseServiceImpl.buildScopeJson(List.of()), "空范围拒绝");
        assertEquals("{\"scopeType\":\"districts\",\"districtCodes\":[\"420111\"]}",
                MallWarehouseServiceImpl.buildScopeJson(List.of("420111", "420111")), "去重且结构恒定");
        java.util.LinkedHashMap<String, String> tooMany = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 9; i++) {
            tooMany.put("键" + i, "值");
        }
        assertThrows(JbkException.class,
                () -> MallSpecSnapshot.encode(tooMany), "规格键超过 8 个拒绝");
        assertEquals("{}", MallSpecSnapshot.encode(null), "空规格落空对象");
    }

    /**
     * 规格快照读取 fail-closed（R1-P1-3）：库里快照被改坏后，管理端详情与小程序
     * 详情都整次失败——绝不把损坏快照渲染成"无规格/假规格"的正常商品。
     */
    @Test
    void corruptSpecSnapshotFailsWholeDetailRead() {
        seedProduct(320L, CAT_ON, "MP-T-320", 2, 2);
        seedSku(430L, 320L, "MS-T-430", 1, 2400);
        seedStock(WH_ON, 430L, 5);
        // 正常读通过（基线）
        assertEquals("320", miniMallService.productDetail(idBo(320L)).getProductId());
        // 快照损坏：数字值（写入口不可能产出的形态）
        jdbc.update("UPDATE ws_mall_sku SET SPEC_SNAP='{\"规格\":18.9}' WHERE ID=430");
        assertThrows(JbkException.class, () -> miniMallService.productDetail(idBo(320L)),
                "小程序详情整次失败");
        assertThrows(JbkException.class, () -> productService.detail(idBo(320L)),
                "管理端详情同样 fail-closed");
        // 快照损坏：非 JSON 原文
        jdbc.update("UPDATE ws_mall_sku SET SPEC_SNAP='not-json' WHERE ID=430");
        assertThrows(JbkException.class, () -> miniMallService.productDetail(idBo(320L)));
    }

    /**
     * ⑩ R2-P0 纵向业务链闭环：创建分类→服务端真路径创建商品+SKU→选择前置仓→
     * SKU 候选（独立于库存行）→首次入库建行→上架三闸通过→小程序首页可读有货。
     * 每步都走服务层真实实现，不用 jdbc 捷径伪造中间态；上架先被库存闸拒绝再
     * 因首次入库放行，恰是 R2 之前"永远上不了架"断链的反证。
     */
    @Test
    void verticalChainFromCreationToMiniHomeCloses() {
        seedCategory(103L, "MC-T-VC", 1);

        MallSkuItemBo sku = new MallSkuItemBo();
        sku.setSkuNo("VC-SKU-1");
        sku.setSkuName("纵向链单品");
        sku.setSpecs(Map.of("规格", "500ml×12"));
        sku.setSalePrice(2400L);
        sku.setWeightGram(6000L);
        sku.setSkuStatus(1);
        MallProductBo product = new MallProductBo();
        product.setProductNo("VC-PROD-1");
        product.setCategoryId(103L);
        product.setProductName("纵向链整箱水");
        product.setSkus(List.of(sku));
        Long productId = productService.save(product);

        // 新 SKU 尚无库存行——候选仍可查（数据源=SKU⋈商品，独立于库存行）
        MallQueryBo query = new MallQueryBo();
        query.setKeyword("VC-SKU-1");
        PageDataVo<MallSkuCandidateVo> candidates = stockService.skuCandidates(query);
        assertEquals(1L, candidates.getTotal());
        MallSkuCandidateVo candidate = candidates.getList().get(0);
        assertEquals(String.valueOf(productId), candidate.getProductId());
        long skuId = Long.parseLong(candidate.getSkuId());

        // 尚无可售库存：上架被三闸拒绝（断链时代这里是永久终点）
        assertThrows(JbkException.class, () -> productService.publish(shelf(productId, 1)));

        // 首次入库：启用仓 WH_ON，upsert 建行
        MallStockAdjustBo adjust = new MallStockAdjustBo();
        adjust.setRequestId(java.util.UUID.randomUUID().toString());
        adjust.setWarehouseId(WH_ON);
        adjust.setSkuId(skuId);
        adjust.setFlowType(1);
        adjust.setQuantity(12L);
        adjust.setReason("纵向链首次入库");
        assertEquals(12L, stockService.adjust(adjust).getAvailableAfter());

        // 上架三闸通过
        assertTrue(productService.publish(shelf(productId, 1)));

        // 小程序首页可读：卡片在场、有货、最低价=该 SKU 售价
        MiniMallHomeVo home = miniMallService.home(new MiniMallHomeBo());
        MiniMallHomeVo.ProductCard card = home.getProducts().stream()
                .filter(c -> String.valueOf(productId).equals(c.getProductId()))
                .findFirst().orElseThrow();
        assertTrue(card.getInStock(), "首次入库后小程序端有货");
        assertEquals(2400L, card.getMinSalePriceFen());
    }

    private int statusOf(long productId) {
        return jdbc.queryForObject(
                "SELECT PRODUCT_STATUS FROM ws_mall_product WHERE ID=?", Integer.class, productId);
    }

    private int versionOf(long productId) {
        return jdbc.queryForObject(
                "SELECT VERSION FROM ws_mall_product WHERE ID=?", Integer.class, productId);
    }
}
