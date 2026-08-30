package com.jbk.serve.service.mall.impl;

import com.jbk.serve.mapper.mall.WsMallCartItemMapper;
import com.jbk.serve.mapper.mall.WsMallCategoryMapper;
import com.jbk.serve.mapper.mall.WsMallOrderItemMapper;
import com.jbk.serve.mapper.mall.WsMallOrderMapper;
import com.jbk.serve.mapper.mall.WsMallPaymentFactMapper;
import com.jbk.serve.mapper.mall.WsMallPaymentMapper;
import com.jbk.serve.mapper.mall.WsMallProductMapper;
import com.jbk.serve.mapper.mall.WsMallSkuMapper;
import com.jbk.serve.mapper.mall.WsMallStockFlowMapper;
import com.jbk.serve.mapper.mall.WsMallStockMapper;
import com.jbk.serve.mapper.mall.WsMallWarehouseMapper;
import com.jbk.serve.mapper.ops.WsDomainEventMapper;
import com.jbk.serve.mapper.user.WsUserAddressMapper;
import com.jbk.serve.service.mall.IMallCartService;
import com.jbk.serve.service.mall.IMallOrderService;
import com.jbk.serve.service.mall.IMallPayApplyTx;
import com.jbk.serve.service.mall.IMallShipmentService;
import com.jbk.serve.service.mall.IMallPayFactService;
import com.jbk.serve.service.mall.IMallPayQueryAdapter;
import com.jbk.serve.service.mall.IMallPaySimService;
import com.jbk.serve.service.mini.IMiniFamilyService;
import com.jbk.serve.service.mini.impl.MiniFamilyServiceImpl;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.serve.service.ops.impl.WsDomainEventServiceImpl;
import com.jbk.tool.consts.mall.MallEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.mall.bo.MallCheckoutBo;
import com.jbk.tool.data.mall.bo.MallOrderActionBo;
import com.jbk.tool.data.mall.vo.MallOrderVo;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E-09 S2 商城交易<b>真实 MySQL + 真实 Spring 事务</b>回归（任务书最低验收 12 条）。
 *
 * <p>钉住的性质 Mock 证明不了：多 SKU 原子预占的全有全无、20 线程真并发不超卖、
 * 创单幂等的参数等价裁决、支付两段式的"事实留存 + 推进可重放"、以及
 * 支付成功与取消并发时终态唯一。</p>
 *
 * <p>无 Docker 环境自动跳过。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringJUnitConfig(MallTradeTxDbTest.Ctx.class)
class MallTradeTxDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static final long USER = 1001L;
    private static final long OTHER_USER = 1002L;
    /** 仅 WH1 覆盖：用于并发抢最后库存（不会被别的仓分流）。 */
    private static final String DISTRICT_ONLY_WH1 = "420111";
    /** WH1 与 WH2 都覆盖：用于验证多仓可履约时按仓库 ID 升序取第一仓。 */
    private static final String DISTRICT_BOTH = "420114";
    private static final long WH1 = 201L;
    private static final long WH2 = 202L;
    private static final long SKU1 = 401L;
    private static final long SKU2 = 402L;
    private static final long PRODUCT = 301L;
    /** 归属 WH1 的仓库操作员。 */
    /** 站内消息故障注入开关：证明「消息写失败则动作、轨迹、审计全部回滚」。 */
    private static volatile boolean FAIL_MESSAGE = false;
    private static final long WH_OPERATOR = 9001L;
    private static final long WH_OPERATOR2 = 9002L;
    /** 只归属 WH2：跨仓操作必须被拒。 */
    private static final long OTHER_WH_OPERATOR = 9003L;
    private static final long COURIER_USER = 1003L;
    private static final long COURIER_OUT_USER = 1004L;
    /** 覆盖 WH1 的配送员。 */
    private static final long COURIER_IN = 601L;
    /** 未绑定 WH1 的配送员：用于证明「范围外」是可判定拒绝而不是文本猜测。 */
    private static final long COURIER_OUT = 602L;
    /** 已停用的配送员（绑定了 WH1，但准入状态为停用）。 */
    private static final long COURIER_DISABLED = 603L;
    /** 下单人本人也注册成了配送员：禁止自己给自己配送。 */
    private static final long COURIER_SELF = 604L;
    private static final long ADDR_WH1_ONLY = 501L;
    private static final long ADDR_BOTH = 502L;
    private static final long ADDR_NO_DISTRICT = 503L;
    private static final long ADDR_OF_OTHER = 504L;

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
            ds.setMaximumPoolSize(24);
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
            // 与生产 MybatisPlusConfig 同构：缺分页拦截器时 selectPage 无 LIMIT、total 恒 0
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
        MapperFactoryBean<com.jbk.serve.mapper.mall.WsMallShipmentMapper> shipmentMapper(SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.mall.WsMallShipmentMapper.class, t);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.mall.WsMallShipmentItemMapper> shipmentItemMapper(SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.mall.WsMallShipmentItemMapper.class, t);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.mall.WsMallLogisticsEventMapper> logisticsEventMapper(SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.mall.WsMallLogisticsEventMapper.class, t);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.mall.WsMallLogisticsOutboxMapper> logisticsOutboxMapper(SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.mall.WsMallLogisticsOutboxMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsMallCartItemMapper> cartMapper(SqlSessionTemplate t) {
            return mapper(WsMallCartItemMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsMallOrderMapper> orderMapper(SqlSessionTemplate t) {
            return mapper(WsMallOrderMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsMallOrderItemMapper> orderItemMapper(SqlSessionTemplate t) {
            return mapper(WsMallOrderItemMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsMallPaymentMapper> paymentMapper(SqlSessionTemplate t) {
            return mapper(WsMallPaymentMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsMallPaymentFactMapper> paymentFactMapper(SqlSessionTemplate t) {
            return mapper(WsMallPaymentFactMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsMallStockMapper> stockMapper(SqlSessionTemplate t) {
            return mapper(WsMallStockMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsMallStockFlowMapper> stockFlowMapper(SqlSessionTemplate t) {
            return mapper(WsMallStockFlowMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsMallSkuMapper> skuMapper(SqlSessionTemplate t) {
            return mapper(WsMallSkuMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsMallProductMapper> productMapper(SqlSessionTemplate t) {
            return mapper(WsMallProductMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsMallCategoryMapper> categoryMapper(SqlSessionTemplate t) {
            return mapper(WsMallCategoryMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsMallWarehouseMapper> warehouseMapper(SqlSessionTemplate t) {
            return mapper(WsMallWarehouseMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsUserAddressMapper> addressMapper(SqlSessionTemplate t) {
            return mapper(WsUserAddressMapper.class, t);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.user.WsFamilyProfileMapper> familyMapper(
                SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.user.WsFamilyProfileMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsDomainEventMapper> domainEventMapper(SqlSessionTemplate t) {
            return mapper(WsDomainEventMapper.class, t);
        }

        @Bean
        IWsDomainEventService domainEventService() {
            return new WsDomainEventServiceImpl();
        }

        /** 用真实实现：地址归属校验是越权用例的被测对象，不能用桩替代。 */
        @Bean
        IMiniFamilyService miniFamilyService(WsUserAddressMapper addressMapper,
                                             com.jbk.serve.mapper.user.WsFamilyProfileMapper familyMapper) {
            return new MiniFamilyServiceImpl(addressMapper, familyMapper);
        }

        @Bean
        IMallCartService mallCartService() {
            return new MallCartServiceImpl();
        }

        @Bean
        IMallOrderService mallOrderService() {
            return new MallOrderServiceImpl();
        }

        @Bean
        IMallPayApplyTx mallPayApplyTx() {
            return new MallPayApplyTxImpl();
        }

        @Bean
        IMallPayFactService mallPayFactService() {
            return new MallPayFactServiceImpl();
        }

        @Bean
        IMallPayQueryAdapter mallPayQueryAdapter() {
            return new MallPaySimQueryAdapter();
        }

        @Bean
        IMallPaySimService mallPaySimService() {
            return new MallPaySimServiceImpl();
        }

        @Bean
        com.jbk.serve.service.identity.DemoScenarioService demoScenarioService() {
            // 本类验证商城交易状态机，演示结果分支由 DemoScenarioServiceTest 单独覆盖。
            return Mockito.mock(com.jbk.serve.service.identity.DemoScenarioService.class);
        }

        @Bean
        MallPayFactRetryWorker payFactRetryWorker(WsMallPaymentFactMapper factMapper,
                                                  IMallPayFactService payFactService) {
            return new MallPayFactRetryWorker(factMapper, payFactService);
        }

        @Bean
        MallOrderExpireWorker orderExpireWorker() {
            return new MallOrderExpireWorker();
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.mall.WsMallFulfillmentMapper> fulfillMapper(
                SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.mall.WsMallFulfillmentMapper.class, t);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.mall.WsMallFulfillmentTraceMapper> ftraceMapper(
                SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.mall.WsMallFulfillmentTraceMapper.class, t);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.mall.WsMallCourierScopeMapper> cscopeMapper(
                SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.mall.WsMallCourierScopeMapper.class, t);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.mall.WsMallWarehouseOperatorMapper> whOperatorMapper(
                SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.mall.WsMallWarehouseOperatorMapper.class, t);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.user.WsCourierMapper> courierMapper(
                SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.user.WsCourierMapper.class, t);
        }

        @Bean
        com.jbk.serve.service.mall.IMallFulfillmentService mallFulfillmentService() {
            return new MallFulfillmentServiceImpl();
        }

        /** 渠道无关状态机原语：自营链与第三方链共用同一个实例。 */
        @Bean
        MallFulfillCore mallFulfillCore() {
            return new MallFulfillCore();
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.mall.WsMallAfterSaleMapper> afterSaleMapper(
                SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.mall.WsMallAfterSaleMapper.class, t);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.mall.WsMallAfterSaleItemMapper> afterSaleItemMapper(
                SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.mall.WsMallAfterSaleItemMapper.class, t);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.mall.WsMallAfterSaleTraceMapper> afterSaleTraceMapper(
                SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.mall.WsMallAfterSaleTraceMapper.class, t);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.mall.WsMallRefundMapper> refundMapper(
                SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.mall.WsMallRefundMapper.class, t);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.mall.WsMallRefundFactMapper> refundFactMapper(
                SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.mall.WsMallRefundFactMapper.class, t);
        }

        @Bean
        MallAfterSaleTraceWriter mallAfterSaleTraceWriter() {
            return new MallAfterSaleTraceWriter();
        }

        @Bean
        MallRefundSimServiceImpl mallRefundSimService() {
            // 生产按 mall.refund-sim.enabled 条件装配；这里显式 new 出来是为了验它的归属闸
            return new MallRefundSimServiceImpl();
        }

        @Bean
        MallShipmentServiceImpl mallShipmentService() {
            return new MallShipmentServiceImpl();
        }

        @Bean
        MallSelfDeliveryServiceImpl mallSelfDeliveryService() {
            return new MallSelfDeliveryServiceImpl();
        }

        @Bean
        MallLogisticsServiceImpl mallLogisticsService() {
            return new MallLogisticsServiceImpl();
        }

        @Bean
        MallLogisticsFactServiceImpl mallLogisticsFactService() {
            return new MallLogisticsFactServiceImpl();
        }

        @Bean
        MallLogisticsApplyTxImpl mallLogisticsApplyTx() {
            return new MallLogisticsApplyTxImpl();
        }

        @Bean
        MallLogisticsOutboxWorker mallLogisticsOutboxWorker() {
            return new MallLogisticsOutboxWorker();
        }

        @Bean
        MallLogisticsEventRetryWorker mallLogisticsEventRetryWorker() {
            return new MallLogisticsEventRetryWorker();
        }

        @Bean
        LogisticsSimAdapter logisticsSimAdapter() {
            // 生产按 mall.logistics-sim.enabled 条件装配；测试显式 new 出来跑第三方链
            return new LogisticsSimAdapter();
        }

        @Bean
        MallAfterSaleStock mallAfterSaleStock() {
            return new MallAfterSaleStock();
        }

        @Bean
        MallExchangeSettlement mallExchangeSettlement() {
            return new MallExchangeSettlement();
        }

        @Bean
        com.jbk.serve.service.mall.IMallExchangeTx mallExchangeTx() {
            return new MallExchangeTxImpl();
        }

        @Bean
        com.jbk.serve.service.mall.IMallAfterSaleService mallAfterSaleService() {
            return new MallAfterSaleServiceImpl();
        }

        @Bean
        com.jbk.serve.service.mall.IMallRefundApplyTx mallRefundApplyTx() {
            return new MallRefundApplyTxImpl();
        }

        @Bean
        com.jbk.serve.service.mall.IMallRefundFactService mallRefundFactService() {
            return new MallRefundFactServiceImpl();
        }

        @Bean
        MallRefundFactRetryWorker refundFactRetryWorker(
                com.jbk.serve.mapper.mall.WsMallRefundFactMapper factMapper,
                com.jbk.serve.service.mall.IMallRefundFactService refundFactService) {
            return new MallRefundFactRetryWorker(factMapper, refundFactService);
        }

        @Bean
        MallFulfillmentDispatchWorker fulfillDispatchWorker() {
            return new MallFulfillmentDispatchWorker();
        }

        /**
         * 站内消息桩：走同一个 JdbcTemplate 与同一个事务管理器落 ws_message，
         * 因此「动作回滚则消息一并回滚」这条性质是真的被验证的，而不是记在内存 list 里。
         */

        /** 通知登记用真实实现：要验「回滚后库里没有那一行」，mock 证不了。 */
        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.mini.WsWechatNotifyOutboxMapper>
                wechatNotifyOutboxMapper(SqlSessionTemplate t) {
            MapperFactoryBean<com.jbk.serve.mapper.mini.WsWechatNotifyOutboxMapper> bean =
                    new MapperFactoryBean<>(com.jbk.serve.mapper.mini.WsWechatNotifyOutboxMapper.class);
            bean.setSqlSessionTemplate(t);
            return bean;
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.mall.WsWechatShippingOutboxMapper>
                wechatShippingOutboxMapper(SqlSessionTemplate t) {
            MapperFactoryBean<com.jbk.serve.mapper.mall.WsWechatShippingOutboxMapper> bean =
                    new MapperFactoryBean<>(com.jbk.serve.mapper.mall.WsWechatShippingOutboxMapper.class);
            bean.setSqlSessionTemplate(t);
            return bean;
        }

        /** 发货同步登记：真实实现。行为由 WechatShippingSyncDbTest 锁定，这里验挂点接线与分流。 */
        @Bean
        com.jbk.serve.service.mini.wxship.WechatShippingEnqueue shippingEnqueue(
                com.jbk.serve.mapper.mall.WsWechatShippingOutboxMapper m,
                com.jbk.serve.mapper.mall.WsMallPaymentMapper p) {
            return new com.jbk.serve.service.mini.wxship.WechatShippingEnqueue(m, p);
        }

        @Bean
        com.jbk.serve.service.mini.notify.WechatNotifyEnqueue notifyEnqueue(
                com.jbk.serve.mapper.mini.WsWechatNotifyOutboxMapper m) {
            return new com.jbk.serve.service.mini.notify.WechatNotifyEnqueue(m);
        }

        @Bean
        com.jbk.serve.service.message.IWsMessageService messageService(JdbcTemplate jdbc) {
            return (com.jbk.serve.service.message.IWsMessageService) java.lang.reflect.Proxy
                    .newProxyInstance(
                        com.jbk.serve.service.message.IWsMessageService.class.getClassLoader(),
                        new Class<?>[]{com.jbk.serve.service.message.IWsMessageService.class},
                        (proxy, method, args) -> {
                            if (!"sendInApp".equals(method.getName())) {
                                throw new UnsupportedOperationException(method.getName());
                            }
                            if (FAIL_MESSAGE) {
                                throw new RuntimeException("站内消息写入失败（故障注入）");
                            }
                            jdbc.update("INSERT INTO ws_message (DATA_STATUS, CREATE_BY,"
                                    + " CREATE_TIME, UPDATE_BY, UPDATE_TIME, USER_ID, MSG_DOMAIN,"
                                    + " MSG_TITLE, MSG_CONTENT, OBJECT_TYPE, OBJECT_ID, SEND_TIME)"
                                    + " VALUES (0,1,?,1,?,?,?,?,?,?,?,?)",
                                    args[6], args[6], args[0],
                                    ((com.jbk.tool.consts.message.MessageEnum.MsgDomain) args[1])
                                            .getValue(),
                                    args[2], args[3], args[4], args[5], args[6]);
                            return null;
                        });
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }

        /**
         * 解析 @Value 占位符：缺了它，MallOrderServiceImpl 的付款窗注入会直接让上下文起不来。
         * 测试上下文无属性源，各处取默认值（付款窗 30 分钟）。
         */
        @Bean
        static org.springframework.context.support.PropertySourcesPlaceholderConfigurer placeholders() {
            return new org.springframework.context.support.PropertySourcesPlaceholderConfigurer();
        }
    }

    @Autowired
    private IMiniFamilyService familyService;
    @Autowired
    private IMallOrderService orderService;
    @Autowired
    private IMallCartService cartService;
    @Autowired
    private IMallPayFactService payFactService;
    @Autowired
    private IMallPaySimService paySimService;
    @Autowired
    private MallPayFactRetryWorker payFactRetryWorker;
    @Autowired
    private MallOrderExpireWorker orderExpireWorker;
    @Autowired
    private com.jbk.serve.service.mall.IMallFulfillmentService fulfillmentService;
    @Autowired
    private MallFulfillmentDispatchWorker fulfillDispatchWorker;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        FAIL_MESSAGE = false;
        MallDbSchema.createAll(jdbc);
        MallDbSchema.truncateAll(jdbc);
        seedCatalog();
        seedAddresses();
    }

    private void seedCatalog() {
        jdbc.update("INSERT INTO ws_mall_category (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY,"
                + " UPDATE_TIME, CATEGORY_CODE, CATEGORY_NAME, CATEGORY_SORT, CATEGORY_STATUS)"
                + " VALUES (101,0,1,'20260808120000',1,'20260808120000','MC-T','测试分类',1,1)");
        jdbc.update("INSERT INTO ws_mall_product (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY,"
                + " UPDATE_TIME, PRODUCT_NO, CATEGORY_ID, PRODUCT_NAME, PRODUCT_STATUS, VERSION)"
                + " VALUES (?,0,1,'20260808120000',1,'20260808120000','MP-T-301',101,'测试整箱水',2,2)",
                PRODUCT);
        seedSku(SKU1, "MS-T-401", 1200L);
        seedSku(SKU2, "MS-T-402", 800L);
        // WH1 覆盖两个区县；WH2 只覆盖 DISTRICT_BOTH——并发用例因此不会被 WH2 分流
        seedWarehouse(WH1, "MW-T-201", List.of(DISTRICT_ONLY_WH1, DISTRICT_BOTH));
        seedWarehouse(WH2, "MW-T-202", List.of(DISTRICT_BOTH));
        seedStock(WH1, SKU1, 10L);
        seedStock(WH1, SKU2, 5L);
        seedStock(WH2, SKU1, 100L);
        seedStock(WH2, SKU2, 100L);
        seedCouriers();
    }

    /** 四个配送员覆盖分配的四种判据：合法、范围外、已停用、下单本人。 */
    private void seedCouriers() {
        seedCourier(COURIER_IN, COURIER_USER, "配送员甲",
                com.jbk.tool.consts.user.UserEnum.CourierStatus.ENABLED.getValue());
        seedCourier(COURIER_OUT, COURIER_OUT_USER, "配送员乙",
                com.jbk.tool.consts.user.UserEnum.CourierStatus.ENABLED.getValue());
        seedCourier(COURIER_DISABLED, 1005L, "配送员丙",
                com.jbk.tool.consts.user.UserEnum.CourierStatus.DISABLED.getValue());
        seedCourier(COURIER_SELF, USER, "下单人本人",
                com.jbk.tool.consts.user.UserEnum.CourierStatus.ENABLED.getValue());
        bindScope(WH1, COURIER_IN);
        bindScope(WH1, COURIER_DISABLED);
        bindScope(WH1, COURIER_SELF);
        // COURIER_OUT 刻意不绑定 WH1
        bindOperator(WH1, WH_OPERATOR);
        bindOperator(WH1, WH_OPERATOR2);
        bindOperator(WH2, OTHER_WH_OPERATOR);
        // OTHER_WH_OPERATOR 只归属 WH2：用于证明跨仓操作被拒
    }

    private void bindOperator(long warehouseId, long operatorId) {
        jdbc.update("INSERT INTO ws_mall_warehouse_operator (DATA_STATUS, CREATE_BY, CREATE_TIME,"
                + " UPDATE_BY, UPDATE_TIME, WAREHOUSE_ID, OPERATOR_ID)"
                + " VALUES (0,1,'20260809120000',1,'20260809120000',?,?)", warehouseId, operatorId);
    }

    private void seedCourier(long id, long userId, String name, int status) {
        jdbc.update("INSERT INTO ws_courier (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY,"
                + " UPDATE_TIME, USER_ID, COURIER_NAME, COURIER_PHONE, COURIER_STATUS)"
                + " VALUES (?,0,1,'20260809120000',1,'20260809120000',?,?,'13800000000',?)",
                id, userId, name, status);
    }

    private void bindScope(long warehouseId, long courierId) {
        jdbc.update("INSERT INTO ws_mall_courier_scope (DATA_STATUS, CREATE_BY, CREATE_TIME,"
                + " UPDATE_BY, UPDATE_TIME, WAREHOUSE_ID, COURIER_ID)"
                + " VALUES (0,1,'20260809120000',1,'20260809120000',?,?)", warehouseId, courierId);
    }

    private void seedSku(long id, String no, long price) {
        jdbc.update("INSERT INTO ws_mall_sku (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY,"
                + " UPDATE_TIME, SKU_NO, PRODUCT_ID, SKU_NAME, SPEC_SNAP, SALE_PRICE, WEIGHT_GRAM,"
                + " SKU_STATUS, VERSION) VALUES (?,0,1,'20260808120000',1,'20260808120000',?,?,"
                + " ?,'{\"规格\":\"测试\"}',?,500,1,1)", id, no, PRODUCT, "规格" + id, price);
    }

    private void seedWarehouse(long id, String no, List<String> districts) {
        String scope = "{\"scopeType\":\"districts\",\"districtCodes\":[\""
                + String.join("\",\"", districts) + "\"]}";
        jdbc.update("INSERT INTO ws_mall_warehouse (ID, DATA_STATUS, CREATE_BY, CREATE_TIME,"
                + " UPDATE_BY, UPDATE_TIME, WAREHOUSE_NO, WAREHOUSE_NAME, CONTACT_NAME, CONTACT_PHONE,"
                + " PROVINCE_CODE, CITY_CODE, DISTRICT_CODE, WAREHOUSE_ADDRESS, SERVICE_SCOPE_JSON,"
                + " WAREHOUSE_STATUS, VERSION) VALUES (?,0,1,'20260808120000',1,'20260808120000',?,"
                + " ?,'仓管','13500000000','420000','420100','420111','测试地址',?,1,1)",
                id, no, "测试仓" + id, scope);
    }

    private void seedStock(long warehouseId, long skuId, long available) {
        jdbc.update("INSERT INTO ws_mall_stock (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY,"
                + " UPDATE_TIME, WAREHOUSE_ID, SKU_ID, AVAILABLE_QTY, RESERVED_QTY, VERSION)"
                + " VALUES (0,1,'20260808120000',1,'20260808120000',?,?,?,0,1)",
                warehouseId, skuId, available);
    }

    private void seedAddresses() {
        seedAddress(ADDR_WH1_ONLY, USER, DISTRICT_ONLY_WH1);
        seedAddress(ADDR_BOTH, USER, DISTRICT_BOTH);
        seedAddress(ADDR_NO_DISTRICT, USER, null);
        seedAddress(ADDR_OF_OTHER, OTHER_USER, DISTRICT_ONLY_WH1);
    }

    private void seedAddress(long id, long userId, String districtCode) {
        jdbc.update("INSERT INTO ws_user_address (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY,"
                + " UPDATE_TIME, USER_ID, CONTACT_NAME, CONTACT_PHONE, REGION, DISTRICT_CODE,"
                + " ADDRESS_DETAIL, IS_DEFAULT, LOCATION_AUTHORIZED)"
                + " VALUES (?,0,1,'20260808120000',1,'20260808120000',?,'张三','13500002222',"
                + " '湖北省武汉市洪山区',?,'测试路1号',1,0)", id, userId, districtCode);
    }

    // ==================== 工具 ====================

    private MallCheckoutBo checkout(long addressId, String requestId, Object... skuQtyPairs) {
        List<MallCheckoutBo.Line> lines = new ArrayList<>();
        for (int i = 0; i < skuQtyPairs.length; i += 2) {
            lines.add(new MallCheckoutBo.Line()
                    .setSkuId(((Number) skuQtyPairs[i]).longValue())
                    .setQuantity(((Number) skuQtyPairs[i + 1]).intValue()));
        }
        return new MallCheckoutBo().setAddressId(addressId).setLines(lines).setRequestId(requestId);
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    /** 地址保存入参：MiniAddressSaveBo 是非链式 @Data，只能逐句赋值。 */
    private static com.jbk.tool.data.mini.bo.MiniAddressSaveBo addressBo(
            String name, String phone, String districtCode, String detail) {
        var bo = new com.jbk.tool.data.mini.bo.MiniAddressSaveBo();
        bo.setContactName(name);
        bo.setPhone(phone);
        bo.setRegion("湖北省武汉市洪山区");
        bo.setDistrictCode(districtCode);
        bo.setDetail(detail);
        bo.setIsDefault(false);
        bo.setLocationAuthorized(false);
        return bo;
    }

    private long availableOf(long warehouseId, long skuId) {
        return jdbc.queryForObject("SELECT AVAILABLE_QTY FROM ws_mall_stock"
                + " WHERE WAREHOUSE_ID=? AND SKU_ID=?", Long.class, warehouseId, skuId);
    }

    private long reservedOf(long warehouseId, long skuId) {
        return jdbc.queryForObject("SELECT RESERVED_QTY FROM ws_mall_stock"
                + " WHERE WAREHOUSE_ID=? AND SKU_ID=?", Long.class, warehouseId, skuId);
    }

    private long countOf(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private long flowCountOfType(int flowType) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ws_mall_stock_flow WHERE FLOW_TYPE=?",
                Long.class, flowType);
    }

    /** 该订单付款窗的下沿：真实链路上支付必然发生在建单之后。 */
    private String payTimeOf(MallOrderVo order) {
        return jdbc.queryForObject("SELECT CREATE_TIME FROM ws_mall_order WHERE ORDER_NO=?",
                String.class, order.getOrderNo());
    }

    /** 该订单付款窗的上沿。 */
    private String payDeadlineOf(MallOrderVo order) {
        return jdbc.queryForObject("SELECT PAY_EXPIRE_TIME FROM ws_mall_order WHERE ORDER_NO=?",
                String.class, order.getOrderNo());
    }

    private int orderStatusOf(String orderNo) {
        return jdbc.queryForObject("SELECT ORDER_STATUS FROM ws_mall_order WHERE ORDER_NO=?",
                Integer.class, orderNo);
    }

    private int payStatusOf(String orderNo) {
        return jdbc.queryForObject("SELECT PAY_STATUS FROM ws_mall_payment WHERE ORDER_NO=?",
                Integer.class, orderNo);
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("① 多SKU同仓预占成功：可售减、预占增、流水与金额自洽")
    void multiSkuReserveInOneWarehouse() {
        MallOrderVo order = orderService.create(USER,
                checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2, SKU2, 1));

        assertEquals(8L, availableOf(WH1, SKU1));
        assertEquals(2L, reservedOf(WH1, SKU1));
        assertEquals(4L, availableOf(WH1, SKU2));
        assertEquals(1L, reservedOf(WH1, SKU2));
        assertEquals(2L, flowCountOfType(MallEnum.StockFlowType.ORDER_RESERVE.getValue()));
        assertEquals(1L, countOf("ws_mall_order"));
        assertEquals(2L, countOf("ws_mall_order_item"));
        assertEquals(1L, countOf("ws_mall_payment"));
        // 金额服务端重算：1200×2 + 800×1
        assertEquals(3200L, order.getProductAmountFen());
        assertEquals(0L, order.getDeliveryFeeFen());
        assertEquals(3200L, order.getOrderAmountFen());
        assertEquals(MallEnum.OrderStatus.PENDING_PAY.getValue(), order.getOrderStatus());
        assertEquals("测试仓" + WH1, order.getWarehouseName());
        // 流水 AFTER 与库存终值一致
        assertEquals(8L, jdbc.queryForObject("SELECT AVAILABLE_AFTER FROM ws_mall_stock_flow"
                + " WHERE SKU_ID=? AND FLOW_TYPE=5", Long.class, SKU1));
    }

    @Test
    @DisplayName("② 任一SKU不足：整单零写入，不留部分预占")
    void insufficientAnySkuRollsBackWholeOrder() {
        assertThrows(JbkException.class, () -> orderService.create(USER,
                checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2, SKU2, 999)));

        assertEquals(10L, availableOf(WH1, SKU1), "第一个 SKU 的预占必须一起回滚");
        assertEquals(0L, reservedOf(WH1, SKU1));
        assertEquals(5L, availableOf(WH1, SKU2));
        assertEquals(0L, countOf("ws_mall_order"));
        assertEquals(0L, countOf("ws_mall_order_item"));
        assertEquals(0L, countOf("ws_mall_stock_flow"));
        assertEquals(0L, countOf("ws_mall_payment"));
    }

    @Test
    @DisplayName("③ 20线程争抢最后10件：恰10单成功，可售归零，绝不超卖")
    void twentyThreadsNeverOversell() throws Exception {
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
                    orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 1));
                    success.incrementAndGet();
                }
                catch (RuntimeException e) {
                    rejected.incrementAndGet();
                }
                return null;
            });
        }
        for (Callable<Void> job : jobs) {
            pool.submit(job);
        }
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发任务未在限时内结束");

        assertEquals(10, success.get(), "恰好 10 单成功");
        assertEquals(10, rejected.get(), "其余 10 单被库存条件拒绝");
        assertEquals(0L, availableOf(WH1, SKU1), "可售归零");
        assertEquals(10L, reservedOf(WH1, SKU1), "预占等于成功单数");
        assertEquals(10L, countOf("ws_mall_order"));
        assertEquals(10L, flowCountOfType(MallEnum.StockFlowType.ORDER_RESERVE.getValue()));
    }

    @Test
    @DisplayName("④ 同requestId同参重放：返回原订单，不重复预占")
    void sameRequestIdReplayReturnsSameOrder() {
        String requestId = uuid();
        MallOrderVo first = orderService.create(USER, checkout(ADDR_WH1_ONLY, requestId, SKU1, 2));
        for (int i = 0; i < 3; i++) {
            MallOrderVo replay = orderService.create(USER,
                    checkout(ADDR_WH1_ONLY, requestId, SKU1, 2));
            assertEquals(first.getOrderNo(), replay.getOrderNo());
            assertEquals(first.getOrderAmountFen(), replay.getOrderAmountFen());
        }
        assertEquals(1L, countOf("ws_mall_order"));
        assertEquals(8L, availableOf(WH1, SKU1), "重放不叠加预占");
        assertEquals(1L, flowCountOfType(MallEnum.StockFlowType.ORDER_RESERVE.getValue()));
    }

    @Test
    @DisplayName("⑤ 同requestId改 SKU/数量/地址：一律拒绝且零副作用")
    void sameRequestIdWithChangedParamsRejected() {
        String requestId = uuid();
        orderService.create(USER, checkout(ADDR_WH1_ONLY, requestId, SKU1, 2));

        assertThrows(JbkException.class, () -> orderService.create(USER,
                checkout(ADDR_WH1_ONLY, requestId, SKU2, 2)), "改 SKU 拒绝");
        assertThrows(JbkException.class, () -> orderService.create(USER,
                checkout(ADDR_WH1_ONLY, requestId, SKU1, 3)), "改数量拒绝");
        assertThrows(JbkException.class, () -> orderService.create(USER,
                checkout(ADDR_BOTH, requestId, SKU1, 2)), "改地址拒绝");
        assertThrows(JbkException.class, () -> orderService.create(USER,
                checkout(ADDR_WH1_ONLY, requestId, SKU1, 2, SKU2, 1)), "加行拒绝");

        assertEquals(1L, countOf("ws_mall_order"));
        assertEquals(8L, availableOf(WH1, SKU1));
        assertEquals(1L, flowCountOfType(MallEnum.StockFlowType.ORDER_RESERVE.getValue()));
    }

    @Test
    @DisplayName("⑥ 重复Pay-Sim：只实销一次，预占核销后可售不变")
    void duplicatePaySimSellsOnce() {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        for (int i = 0; i < 3; i++) {
            paySimService.pay(USER, order.getOrderNo());
        }
        assertEquals(MallEnum.OrderStatus.PAID.getValue(), orderStatusOf(order.getOrderNo()));
        assertEquals(8L, availableOf(WH1, SKU1), "实销不动可售");
        assertEquals(0L, reservedOf(WH1, SKU1), "预占被核销");
        assertEquals(1L, flowCountOfType(MallEnum.StockFlowType.ORDER_SELL.getValue()), "实销流水恰一条");
        assertEquals(1L, countOf("ws_mall_payment_fact"), "同订单事实恰一条");
        assertEquals(MallEnum.PayStatus.SUCCESS.getValue(), (int) jdbc.queryForObject(
                "SELECT PAY_STATUS FROM ws_mall_payment WHERE ORDER_NO=?", Integer.class,
                order.getOrderNo()));
    }

    @Test
    @DisplayName("⑦ 事实已落但未推进：Worker 重放补齐，且只实销一次")
    void workerReplaysUnprocessedFactExactlyOnce() {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        // 只走事务A（模拟事务B 中断：事实已收下，订单还停在待支付）
        payFactService.recordFact(MallEnum.PaySource.PAY_SIM.getValue(),
                MallEnum.FactChannel.PAY_SIM.getValue(),
                MallPaySimServiceImpl.SIM_EVENT_KEY_PREFIX + order.getOrderNo(),
                order.getOrderNo(), MallEnum.TradeState.SUCCESS,
                MallPaySimServiceImpl.SIM_TRANSACTION_PREFIX + order.getOrderNo(),
                order.getOrderAmountFen(), payTimeOf(order),
                MallEnum.VerifyMethod.PAY_SIM_INTERNAL, "{}");
        assertEquals(MallEnum.OrderStatus.PENDING_PAY.getValue(), orderStatusOf(order.getOrderNo()));
        assertEquals(0L, flowCountOfType(MallEnum.StockFlowType.ORDER_SELL.getValue()));

        assertEquals(1, payFactRetryWorker.runOnce("20260808140000"), "本轮处理一条事实");
        assertEquals(MallEnum.OrderStatus.PAID.getValue(), orderStatusOf(order.getOrderNo()));
        assertEquals(1L, flowCountOfType(MallEnum.StockFlowType.ORDER_SELL.getValue()));

        // 再跑一轮：已处理的事实不再被捞取，零副作用
        assertEquals(0, payFactRetryWorker.runOnce("20260808150000"));
        assertEquals(1L, flowCountOfType(MallEnum.StockFlowType.ORDER_SELL.getValue()));
        assertEquals(0L, reservedOf(WH1, SKU1));
    }

    @Test
    @DisplayName("⑧ 未支付取消：释放全部预占，重复取消零副作用")
    void cancelReleasesAllReservations() {
        MallOrderVo order = orderService.create(USER,
                checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2, SKU2, 1));
        orderService.cancelByUser(USER, new MallOrderActionBo().setOrderNo(order.getOrderNo()));

        assertEquals(MallEnum.OrderStatus.CANCELLED.getValue(), orderStatusOf(order.getOrderNo()));
        assertEquals(10L, availableOf(WH1, SKU1), "可售复原");
        assertEquals(0L, reservedOf(WH1, SKU1));
        assertEquals(5L, availableOf(WH1, SKU2));
        assertEquals(2L, flowCountOfType(MallEnum.StockFlowType.ORDER_RELEASE.getValue()));

        assertThrows(JbkException.class, () -> orderService.cancelByUser(USER,
                new MallOrderActionBo().setOrderNo(order.getOrderNo())), "重复取消被拒");
        assertEquals(10L, availableOf(WH1, SKU1), "重复取消不再加库存");
        assertEquals(2L, flowCountOfType(MallEnum.StockFlowType.ORDER_RELEASE.getValue()));
    }

    @Test
    @DisplayName("⑨ 超时关单：先取得权威 CLOSED 才关；未到期不动")
    void expiredOrderClosedOnlyByAuthority() {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        // 未到期：Worker 扫不到，库存不动
        assertEquals(0, orderExpireWorker.runOnce("20260808120000"));
        assertEquals(8L, availableOf(WH1, SKU1));

        // 把付款截止推到过去，模拟超时
        jdbc.update("UPDATE ws_mall_order SET PAY_EXPIRE_TIME='20260808110000' WHERE ORDER_NO=?",
                order.getOrderNo());
        assertEquals(1, orderExpireWorker.runOnce("20260808130000"));

        assertEquals(MallEnum.OrderStatus.CANCELLED.getValue(), orderStatusOf(order.getOrderNo()));
        assertEquals(10L, availableOf(WH1, SKU1), "关单释放预占");
        assertEquals(0L, reservedOf(WH1, SKU1));
        assertEquals(1L, flowCountOfType(MallEnum.StockFlowType.ORDER_RELEASE.getValue()));
        // 关单依据留痕：查单渠道的 CLOSED 事实在库
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM ws_mall_payment_fact"
                + " WHERE TRADE_STATE='CLOSED' AND FACT_CHANNEL=2", Long.class));

        // 再扫一轮：已终态订单不再被捞
        assertEquals(0, orderExpireWorker.runOnce("20260808140000"));
        assertEquals(10L, availableOf(WH1, SKU1));
    }

    @Test
    @DisplayName("⑩ 支付成功与取消并发：只有一个合法终态，库存不双动")
    void payAndCancelConcurrentSingleTerminalState() throws Exception {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger paid = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();
        pool.submit(() -> {
            go.await(5, TimeUnit.SECONDS);
            try {
                paySimService.pay(USER, order.getOrderNo());
                if (orderStatusOf(order.getOrderNo()) == MallEnum.OrderStatus.PAID.getValue()) {
                    paid.incrementAndGet();
                }
            }
            catch (RuntimeException ignored) {
                // 并发落败方
            }
            return null;
        });
        pool.submit(() -> {
            go.await(5, TimeUnit.SECONDS);
            try {
                orderService.cancelByUser(USER, new MallOrderActionBo().setOrderNo(order.getOrderNo()));
                cancelled.incrementAndGet();
            }
            catch (RuntimeException ignored) {
                // 并发落败方
            }
            return null;
        });
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        int status = orderStatusOf(order.getOrderNo());
        assertTrue(status == MallEnum.OrderStatus.PAID.getValue()
                        || status == MallEnum.OrderStatus.CANCELLED.getValue(),
                "终态必须是已支付或已取消之一，实际=" + status);
        long sell = flowCountOfType(MallEnum.StockFlowType.ORDER_SELL.getValue());
        long release = flowCountOfType(MallEnum.StockFlowType.ORDER_RELEASE.getValue());
        assertEquals(1L, sell + release, "实销与释放二者恰发生一次，不可能都发生");
        if (status == MallEnum.OrderStatus.PAID.getValue()) {
            assertEquals(8L, availableOf(WH1, SKU1));
            assertEquals(0L, reservedOf(WH1, SKU1));
        }
        else {
            assertEquals(10L, availableOf(WH1, SKU1));
            assertEquals(0L, reservedOf(WH1, SKU1));
        }
    }

    @Test
    @DisplayName("⑪ 越权：他人不能读取、取消或支付本单")
    void crossUserAccessRejected() {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 1));
        assertThrows(JbkException.class,
                () -> orderService.detailForUser(OTHER_USER, order.getOrderNo()), "他人读取被拒");
        assertThrows(JbkException.class, () -> orderService.cancelByUser(OTHER_USER,
                new MallOrderActionBo().setOrderNo(order.getOrderNo())), "他人取消被拒");
        assertThrows(JbkException.class,
                () -> paySimService.pay(OTHER_USER, order.getOrderNo()), "他人支付被拒");
        // 他人地址同样不可用于下单
        assertThrows(JbkException.class, () -> orderService.create(USER,
                checkout(ADDR_OF_OTHER, uuid(), SKU1, 1)), "他人地址被拒");
        assertEquals(MallEnum.OrderStatus.PENDING_PAY.getValue(), orderStatusOf(order.getOrderNo()));
        assertEquals(9L, availableOf(WH1, SKU1), "越权尝试零副作用");
    }

    @Test
    @DisplayName("⑫ 选仓：多仓可履约取仓库ID升序第一仓；单仓不足改选可履约仓；均不可履约则拒绝")
    void warehousePickIsDeterministic() {
        // WH1 与 WH2 都覆盖 DISTRICT_BOTH 且都够：取 ID 较小的 WH1
        MallOrderVo first = orderService.create(USER, checkout(ADDR_BOTH, uuid(), SKU1, 2));
        assertEquals("测试仓" + WH1, first.getWarehouseName());

        // 50 件超出 WH1 可售（剩 8），但 WH2 够：改选 WH2
        MallOrderVo second = orderService.create(USER, checkout(ADDR_BOTH, uuid(), SKU1, 50));
        assertEquals("测试仓" + WH2, second.getWarehouseName());
        assertEquals(50L, reservedOf(WH2, SKU1));

        // 仅 WH1 覆盖的区县，要 100 件：无仓可整单履约
        JbkException blocked = assertThrows(JbkException.class, () -> orderService.create(USER,
                checkout(ADDR_WH1_ONLY, uuid(), SKU1, 100)));
        assertTrue(blocked.getMessage().contains("暂无可履约库存"), blocked.getMessage());
    }

    @Test
    @DisplayName("⑬ 地址未选区县码：结算与创单都 fail-closed，不猜测区县")
    void addressWithoutDistrictCodeIsBlocked() {
        assertThrows(JbkException.class, () -> orderService.create(USER,
                checkout(ADDR_NO_DISTRICT, uuid(), SKU1, 1)));
        assertEquals(0L, countOf("ws_mall_order"));
        assertEquals(10L, availableOf(WH1, SKU1));

        var preview = orderService.preview(USER, checkout(ADDR_NO_DISTRICT, null, SKU1, 1));
        assertEquals(Boolean.FALSE, preview.getSubmittable());
        assertTrue(preview.getBlockReason().contains("区县"), preview.getBlockReason());
    }

    @Test
    @DisplayName("⑭ 已取消订单收到成功事实：转人工对账，不重扣库存不改订单")
    void successFactOnCancelledOrderGoesToReconcile() {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        orderService.cancelByUser(USER, new MallOrderActionBo().setOrderNo(order.getOrderNo()));
        assertEquals(10L, availableOf(WH1, SKU1));

        var fact = payFactService.recordFact(MallEnum.PaySource.PAY_SIM.getValue(),
                MallEnum.FactChannel.NOTIFY.getValue(),
                "LATE-" + order.getOrderNo(), order.getOrderNo(), MallEnum.TradeState.SUCCESS,
                "LATETX-" + order.getOrderNo(), order.getOrderAmountFen(), payTimeOf(order),
                MallEnum.VerifyMethod.PAY_SIM_INTERNAL, "{}");
        var outcome = payFactService.process(fact.getId());

        assertEquals(IMallPayApplyTx.Code.RECONCILE, outcome.code());
        assertEquals(MallEnum.PayFactStatus.NEED_RECONCILE.getValue(), (int) jdbc.queryForObject(
                "SELECT PROCESSING_STATUS FROM ws_mall_payment_fact WHERE ID=?", Integer.class,
                fact.getId()));
        assertEquals(MallEnum.OrderStatus.CANCELLED.getValue(), orderStatusOf(order.getOrderNo()),
                "订单不得被拉回已支付");
        assertEquals(10L, availableOf(WH1, SKU1), "库存不得被重新扣减");
        assertEquals(0L, flowCountOfType(MallEnum.StockFlowType.ORDER_SELL.getValue()));
    }

    @Test
    @DisplayName("⑮ 金额不符的事实：转人工对账，订单与库存均不动")
    void amountMismatchFactGoesToReconcile() {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        var fact = payFactService.recordFact(MallEnum.PaySource.PAY_SIM.getValue(),
                MallEnum.FactChannel.NOTIFY.getValue(),
                "BAD-" + order.getOrderNo(), order.getOrderNo(), MallEnum.TradeState.SUCCESS,
                "BADTX-" + order.getOrderNo(), 1L, payTimeOf(order),
                MallEnum.VerifyMethod.PAY_SIM_INTERNAL, "{}");
        var outcome = payFactService.process(fact.getId());

        assertEquals(IMallPayApplyTx.Code.RECONCILE, outcome.code());
        assertEquals(MallEnum.OrderStatus.PENDING_PAY.getValue(), orderStatusOf(order.getOrderNo()));
        assertEquals(0L, flowCountOfType(MallEnum.StockFlowType.ORDER_SELL.getValue()));
        assertEquals(2L, reservedOf(WH1, SKU1), "预占原样保留");
    }

    /**
     * ⑰ 地址保存链必须能写进区县码：入参漏该字段时每层单看都对、下单全被
     * 「暂无可履约库存」挡住——用真实地址服务写一遍再下单才能发现。
     */
    @Test
    @DisplayName("⑰ 地址保存写入区县码后可下单；留空的地址被拒并提示补选")
    void addressSaveCarriesDistrictCodeSoOrderCanBePlaced() {
        // 用真实地址服务新建一条带区县码的地址（走用户实际会走的保存路径）
        var saved = familyService.saveAddress(USER,
                addressBo("李四", "13500003333", DISTRICT_ONLY_WH1, "新地址 88 号"));
        assertEquals(DISTRICT_ONLY_WH1, saved.getDistrictCode(), "保存链必须回吐区县码");
        long newAddressId = Long.parseLong(saved.getAddressId());
        assertEquals(DISTRICT_ONLY_WH1, jdbc.queryForObject(
                "SELECT DISTRICT_CODE FROM ws_user_address WHERE ID=?", String.class, newAddressId),
                "区县码必须真的落库");

        MallOrderVo order = orderService.create(USER, checkout(newAddressId, uuid(), SKU1, 1));
        assertEquals("测试仓" + WH1, order.getWarehouseName());
        assertEquals(9L, availableOf(WH1, SKU1));

        // 留空区县码的地址：保存成功（一期配送不需要），但商城下单被拒且提示补选
        var blank = familyService.saveAddress(USER,
                addressBo("王五", "13500004444", "", "无区县地址 9 号"));
        assertEquals(null, blank.getDistrictCode(), "留空存 NULL，不猜测回填");
        JbkException rejected = assertThrows(JbkException.class, () -> orderService.create(USER,
                checkout(Long.parseLong(blank.getAddressId()), uuid(), SKU1, 1)));
        assertTrue(rejected.getMessage().contains("区县"), rejected.getMessage());
    }

    @Test
    @DisplayName("⑱ 超量释放被条件UPDATE挡下：整事务回滚，不凭空造货")
    void releaseBeyondReservedIsRejectedWithZeroWrite() {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 3));
        assertEquals(3L, reservedOf(WH1, SKU1));
        // 篡改：预占被外部抹掉一部分，此时订单仍要求释放 3
        jdbc.update("UPDATE ws_mall_stock SET RESERVED_QTY=1 WHERE WAREHOUSE_ID=? AND SKU_ID=?",
                WH1, SKU1);

        assertThrows(JbkException.class, () -> orderService.cancelByUser(USER,
                new MallOrderActionBo().setOrderNo(order.getOrderNo())));

        assertEquals(1L, reservedOf(WH1, SKU1), "释放未发生，预占保持被篡改后的值");
        assertEquals(7L, availableOf(WH1, SKU1), "可售没有被凭空加回");
        assertEquals(0L, flowCountOfType(MallEnum.StockFlowType.ORDER_RELEASE.getValue()));
        assertEquals(MallEnum.OrderStatus.PENDING_PAY.getValue(), orderStatusOf(order.getOrderNo()),
                "订单状态随事务一并回滚");
    }

    @Test
    @DisplayName("⑲ 两个Worker并发认领同一事实：只有一个推进，实销恰一次")
    void twoWorkersClaimingSameFactSellExactlyOnce() throws Exception {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        var fact = payFactService.recordFact(MallEnum.PaySource.PAY_SIM.getValue(),
                MallEnum.FactChannel.PAY_SIM.getValue(),
                MallPaySimServiceImpl.SIM_EVENT_KEY_PREFIX + order.getOrderNo(),
                order.getOrderNo(), MallEnum.TradeState.SUCCESS,
                MallPaySimServiceImpl.SIM_TRANSACTION_PREFIX + order.getOrderNo(),
                order.getOrderAmountFen(), payTimeOf(order),
                MallEnum.VerifyMethod.PAY_SIM_INTERNAL, "{}");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger applied = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                go.await(5, TimeUnit.SECONDS);
                var outcome = payFactService.process(fact.getId());
                if (outcome.code() == IMallPayApplyTx.Code.APPLIED) {
                    applied.incrementAndGet();
                }
                else {
                    skipped.incrementAndGet();
                }
                return null;
            });
        }
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        assertEquals(1, applied.get(), "恰一个 Worker 真正推进");
        assertEquals(1, skipped.get(), "另一个认领失败或看到已推进");
        assertEquals(1L, flowCountOfType(MallEnum.StockFlowType.ORDER_SELL.getValue()), "实销恰一条");
        assertEquals(0L, reservedOf(WH1, SKU1));
        assertEquals(8L, availableOf(WH1, SKU1));
        assertEquals(MallEnum.PayFactStatus.PROCESSED.getValue(), (int) jdbc.queryForObject(
                "SELECT PROCESSING_STATUS FROM ws_mall_payment_fact WHERE ID=?", Integer.class,
                fact.getId()));
    }

    @Test
    @DisplayName("⑳ 损坏的规格快照：创单即被拒，不让坏快照进订单明细")
    void corruptSkuSpecSnapshotBlocksOrderCreation() {
        jdbc.update("UPDATE ws_mall_sku SET SPEC_SNAP='{\"规格\":{\"嵌套\":\"x\"}}' WHERE ID=?", SKU1);

        assertThrows(JbkException.class, () -> orderService.create(USER,
                checkout(ADDR_WH1_ONLY, uuid(), SKU1, 1)));

        assertEquals(0L, countOf("ws_mall_order"));
        assertEquals(0L, countOf("ws_mall_order_item"));
        assertEquals(10L, availableOf(WH1, SKU1), "被拒路径零预占");
        assertEquals(0L, countOf("ws_mall_stock_flow"));
    }

    @Test
    @DisplayName("㉑ 币种不符的成功事实：转人工对账，订单与库存均不动")
    void currencyMismatchFactGoesToReconcile() {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        var fact = payFactService.recordFact(MallEnum.PaySource.PAY_SIM.getValue(),
                MallEnum.FactChannel.NOTIFY.getValue(),
                "CUR-" + order.getOrderNo(), order.getOrderNo(), MallEnum.TradeState.SUCCESS,
                "CURTX-" + order.getOrderNo(), order.getOrderAmountFen(), payTimeOf(order),
                MallEnum.VerifyMethod.PAY_SIM_INTERNAL, "{}");
        // 事实币种被改成外币：金额数字相同，但收的不是同一种钱
        jdbc.update("UPDATE ws_mall_payment_fact SET CURRENCY='USD' WHERE ID=?", fact.getId());

        var outcome = payFactService.process(fact.getId());
        assertEquals(IMallPayApplyTx.Code.RECONCILE, outcome.code());
        assertEquals(MallEnum.OrderStatus.PENDING_PAY.getValue(), orderStatusOf(order.getOrderNo()));
        assertEquals(0L, flowCountOfType(MallEnum.StockFlowType.ORDER_SELL.getValue()));
        assertEquals(2L, reservedOf(WH1, SKU1));
    }

    // ==================== R1 复验固定整改用例 ====================

    @Test
    @DisplayName("R1① 金额不符的成功事实：支付单不得被改成成功，订单库存零变化")
    void amountMismatchFactMustNotMarkPaymentSuccess() {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        var fact = payFactService.recordFact(MallEnum.PaySource.PAY_SIM.getValue(),
                MallEnum.FactChannel.NOTIFY.getValue(),
                "BADAMT-" + order.getOrderNo(), order.getOrderNo(), MallEnum.TradeState.SUCCESS,
                "BADAMTTX-" + order.getOrderNo(), 1L, payTimeOf(order),
                MallEnum.VerifyMethod.PAY_SIM_INTERNAL, "{}");

        // 事实落库即被判定不可推进，支付单必须仍是待支付
        assertEquals(MallEnum.PayFactStatus.NEED_RECONCILE.getValue(),
                (int) fact.getProcessingStatus());
        assertEquals(MallEnum.PayStatus.PENDING.getValue(), payStatusOf(order.getOrderNo()),
                "不合法事实绝不能把支付单写成成功");
        assertEquals(MallEnum.OrderStatus.PENDING_PAY.getValue(), orderStatusOf(order.getOrderNo()));
        assertEquals(2L, reservedOf(WH1, SKU1));
        assertEquals(0L, flowCountOfType(MallEnum.StockFlowType.ORDER_SELL.getValue()));

        // 再驱动一次推进：仍是对账终态，不因重放而放行
        assertEquals(IMallPayApplyTx.Code.RECONCILE, payFactService.process(fact.getId()).code());
        assertEquals(MallEnum.PayStatus.PENDING.getValue(), payStatusOf(order.getOrderNo()));
    }

    @Test
    @DisplayName("R1② 同事实键携带被篡改内容：一律拒绝且零副作用")
    void sameEventKeyWithTamperedBodyIsRejected() {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        // 用另一张真实订单做"改订单号"的篡改值：传不存在的单号时，"被拒"也可能只是
        // 因为查无此单，证不到"同键内容不可变"这条判据
        MallOrderVo other = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 1));
        String key = "TAMPER-" + order.getOrderNo();
        String time = payTimeOf(order);
        String body = "{\"channel\":\"notify\"}";
        payFactService.recordFact(MallEnum.PaySource.PAY_SIM.getValue(),
                MallEnum.FactChannel.NOTIFY.getValue(), key, order.getOrderNo(),
                MallEnum.TradeState.SUCCESS, "TX-A", order.getOrderAmountFen(), time,
                MallEnum.VerifyMethod.PAY_SIM_INTERNAL, body);
        long factsAfterFirst = countOf("ws_mall_payment_fact");

        // 逐个字段篡改：金额、交易号、订单号、时间、正文
        assertThrows(JbkException.class, () -> payFactService.recordFact(
                MallEnum.PaySource.PAY_SIM.getValue(), MallEnum.FactChannel.NOTIFY.getValue(),
                key, order.getOrderNo(), MallEnum.TradeState.SUCCESS, "TX-A", 999L, time,
                MallEnum.VerifyMethod.PAY_SIM_INTERNAL, body), "改金额被拒");
        assertThrows(JbkException.class, () -> payFactService.recordFact(
                MallEnum.PaySource.PAY_SIM.getValue(), MallEnum.FactChannel.NOTIFY.getValue(),
                key, order.getOrderNo(), MallEnum.TradeState.SUCCESS, "TX-B",
                order.getOrderAmountFen(), time,
                MallEnum.VerifyMethod.PAY_SIM_INTERNAL, body), "改交易号被拒");
        assertThrows(JbkException.class, () -> payFactService.recordFact(
                MallEnum.PaySource.PAY_SIM.getValue(), MallEnum.FactChannel.NOTIFY.getValue(),
                key, other.getOrderNo(), MallEnum.TradeState.SUCCESS, "TX-A",
                order.getOrderAmountFen(), time,
                MallEnum.VerifyMethod.PAY_SIM_INTERNAL, body), "改订单号被拒");
        assertThrows(JbkException.class, () -> payFactService.recordFact(
                MallEnum.PaySource.PAY_SIM.getValue(), MallEnum.FactChannel.NOTIFY.getValue(),
                key, order.getOrderNo(), MallEnum.TradeState.SUCCESS, "TX-A",
                order.getOrderAmountFen(), DateUtils.plusSeconds(time, 60),
                MallEnum.VerifyMethod.PAY_SIM_INTERNAL, body), "改成功时间被拒");
        assertThrows(JbkException.class, () -> payFactService.recordFact(
                MallEnum.PaySource.PAY_SIM.getValue(), MallEnum.FactChannel.NOTIFY.getValue(),
                key, order.getOrderNo(), MallEnum.TradeState.SUCCESS, "TX-A",
                order.getOrderAmountFen(), time,
                MallEnum.VerifyMethod.PAY_SIM_INTERNAL, "{\"channel\":\"forged\"}"), "改正文被拒");

        assertEquals(factsAfterFirst, countOf("ws_mall_payment_fact"), "不得新增事实行");
        assertEquals("TX-A", jdbc.queryForObject(
                "SELECT TRANSACTION_ID FROM ws_mall_payment_fact WHERE PROVIDER_EVENT_KEY=?",
                String.class, key), "原事实内容不得被覆盖");
        assertEquals(0L, flowCountOfType(MallEnum.StockFlowType.ORDER_SELL.getValue()));
    }

    @Test
    @DisplayName("R1③ 支付单已由交易A成功后收到交易B：B 转人工，不推进订单")
    void secondTransactionOnAlreadyPaidOrderGoesToReconcile() {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        paySimService.pay(USER, order.getOrderNo());
        assertEquals(MallEnum.PayStatus.SUCCESS.getValue(), payStatusOf(order.getOrderNo()));
        String txA = jdbc.queryForObject(
                "SELECT TRANSACTION_ID FROM ws_mall_payment WHERE ORDER_NO=?", String.class,
                order.getOrderNo());
        long sellAfterA = flowCountOfType(MallEnum.StockFlowType.ORDER_SELL.getValue());

        var factB = payFactService.recordFact(MallEnum.PaySource.PAY_SIM.getValue(),
                MallEnum.FactChannel.NOTIFY.getValue(),
                "TXB-" + order.getOrderNo(), order.getOrderNo(), MallEnum.TradeState.SUCCESS,
                "TX-B-" + order.getOrderNo(), order.getOrderAmountFen(), payTimeOf(order),
                MallEnum.VerifyMethod.PAY_SIM_INTERNAL, "{}");

        assertEquals(MallEnum.PayFactStatus.NEED_RECONCILE.getValue(),
                (int) factB.getProcessingStatus(), "第二笔交易号必须转人工");
        assertEquals(txA, jdbc.queryForObject(
                "SELECT TRANSACTION_ID FROM ws_mall_payment WHERE ORDER_NO=?", String.class,
                order.getOrderNo()), "支付单交易号不得被后到的交易覆盖");
        assertEquals(sellAfterA, flowCountOfType(MallEnum.StockFlowType.ORDER_SELL.getValue()),
                "不得再次实销");
    }

    @Test
    @DisplayName("R1④ 两线程同 requestId 同参创单：同一订单号，预占与流水恰一次")
    void concurrentSameRequestIdReturnsSameOrder() throws Exception {
        String requestId = uuid();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<String> orderNos = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicInteger failed = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                go.await(5, TimeUnit.SECONDS);
                try {
                    orderNos.add(orderService.create(USER,
                            checkout(ADDR_WH1_ONLY, requestId, SKU1, 2)).getOrderNo());
                }
                catch (RuntimeException e) {
                    failed.incrementAndGet();
                }
                return null;
            });
        }
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        assertEquals(0, failed.get(), "同参并发不得有一方失败：合同是双方都拿到原订单");
        assertEquals(2, orderNos.size());
        assertEquals(orderNos.get(0), orderNos.get(1), "两方必须拿到同一订单号");
        assertEquals(1L, countOf("ws_mall_order"), "恰一订单");
        assertEquals(8L, availableOf(WH1, SKU1), "恰一次预占");
        assertEquals(1L, flowCountOfType(MallEnum.StockFlowType.ORDER_RESERVE.getValue()));
    }

    @Test
    @DisplayName("R1⑤ 两线程同 requestId 改参：恰一成功，另一明确拒绝")
    void concurrentSameRequestIdDifferentParamsRejectsOne() throws Exception {
        String requestId = uuid();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int qty : new int[]{2, 3}) {
            pool.submit(() -> {
                go.await(5, TimeUnit.SECONDS);
                try {
                    orderService.create(USER, checkout(ADDR_WH1_ONLY, requestId, SKU1, qty));
                    ok.incrementAndGet();
                }
                catch (JbkException rejectedOne) {
                    rejected.incrementAndGet();
                }
                return null;
            });
        }
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        assertEquals(1, ok.get(), "恰一方成功");
        assertEquals(1, rejected.get(), "另一方因参数不一致被拒");
        assertEquals(1L, countOf("ws_mall_order"));
        assertEquals(1L, flowCountOfType(MallEnum.StockFlowType.ORDER_RESERVE.getValue()));
    }

    @Test
    @DisplayName("R1⑥ 预占证据被篡改后处理成功事实：终态需对账，不进入无限重试")
    void deterministicStockEvidenceFailureGoesToReconcileNotRetry() {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 3));
        // 外部抹掉预占：实销将因 RESERVED 不足而失败，且重试多少次都不会自愈
        jdbc.update("UPDATE ws_mall_stock SET RESERVED_QTY=0 WHERE WAREHOUSE_ID=? AND SKU_ID=?",
                WH1, SKU1);
        var fact = payFactService.recordFact(MallEnum.PaySource.PAY_SIM.getValue(),
                MallEnum.FactChannel.PAY_SIM.getValue(),
                MallPaySimServiceImpl.SIM_EVENT_KEY_PREFIX + order.getOrderNo(),
                order.getOrderNo(), MallEnum.TradeState.SUCCESS,
                MallPaySimServiceImpl.SIM_TRANSACTION_PREFIX + order.getOrderNo(),
                order.getOrderAmountFen(), payTimeOf(order),
                MallEnum.VerifyMethod.PAY_SIM_INTERNAL, "{}");

        var outcome = payFactService.process(fact.getId());
        assertEquals(IMallPayApplyTx.Code.RECONCILE, outcome.code());
        assertEquals(MallEnum.PayFactStatus.NEED_RECONCILE.getValue(), (int) jdbc.queryForObject(
                "SELECT PROCESSING_STATUS FROM ws_mall_payment_fact WHERE ID=?", Integer.class,
                fact.getId()), "确定性失败必须直接转人工，不得停在待重试");
        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT RETRY_COUNT FROM ws_mall_payment_fact WHERE ID=?", Integer.class,
                fact.getId()), "不得计入重试");
        // Worker 不再捞它：终态不参与扫描
        assertEquals(0, payFactRetryWorker.runOnce("20260809140000"));
        assertEquals(0L, flowCountOfType(MallEnum.StockFlowType.ORDER_SELL.getValue()));
        assertEquals(MallEnum.OrderStatus.PENDING_PAY.getValue(), orderStatusOf(order.getOrderNo()));
    }

    @Test
    @DisplayName("R1⑦ 购物车 600+600：第二次被拒，最终仍是 600")
    void cartAccumulationCannotExceedLineCap() {
        var bo = new com.jbk.tool.data.mall.bo.MallCartSaveBo()
                .setSkuId(SKU1).setQuantity(600).setIncrement(true);
        cartService.save(USER, bo);
        assertEquals(600, cartService.list(USER).getLines().get(0).getQuantity());

        assertThrows(JbkException.class, () -> cartService.save(USER,
                new com.jbk.tool.data.mall.bo.MallCartSaveBo()
                        .setSkuId(SKU1).setQuantity(600).setIncrement(true)),
                "累加越界必须被拒");
        assertEquals(600, cartService.list(USER).getLines().get(0).getQuantity(),
                "被拒后数量不得被改动");
        assertEquals(600L, jdbc.queryForObject(
                "SELECT QUANTITY FROM ws_mall_cart_item WHERE USER_ID=? AND SKU_ID=?",
                Long.class, USER, SKU1));
    }

    @Test
    @DisplayName("R1⑧ 单价×数量溢出：预览与创单都明确拒绝，零订单零库存变化")
    void amountOverflowIsRejectedOnPreviewAndCreate() {
        jdbc.update("UPDATE ws_mall_sku SET SALE_PRICE=? WHERE ID=?", Long.MAX_VALUE, SKU1);

        var preview = orderService.preview(USER, checkout(ADDR_WH1_ONLY, null, SKU1, 2));
        assertEquals(Boolean.FALSE, preview.getSubmittable(), "预览必须给出不可提交");
        assertNotNull(preview.getBlockReason());

        assertThrows(JbkException.class, () -> orderService.create(USER,
                checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2)));
        assertEquals(0L, countOf("ws_mall_order"));
        assertEquals(0L, countOf("ws_mall_order_item"));
        assertEquals(10L, availableOf(WH1, SKU1), "零预占");
        assertEquals(0L, countOf("ws_mall_stock_flow"));
    }

    // ==================== 自查（五维对抗审查）确认缺陷的回归 ====================

    @Test
    @DisplayName("自查① 支付单已成功但订单尚未推进时取消：必须拒绝，绝不释放预占")
    void cancelIsRejectedWhenPaymentAlreadySucceeded() {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        // 复刻两段式窗口：事务A 已把支付单置成功，事务B 尚未推进订单
        payFactService.recordFact(MallEnum.PaySource.PAY_SIM.getValue(),
                MallEnum.FactChannel.PAY_SIM.getValue(),
                MallPaySimServiceImpl.SIM_EVENT_KEY_PREFIX + order.getOrderNo(),
                order.getOrderNo(), MallEnum.TradeState.SUCCESS,
                MallPaySimServiceImpl.SIM_TRANSACTION_PREFIX + order.getOrderNo(),
                order.getOrderAmountFen(), payTimeOf(order),
                MallEnum.VerifyMethod.PAY_SIM_INTERNAL, "{}");
        assertEquals(MallEnum.PayStatus.SUCCESS.getValue(), payStatusOf(order.getOrderNo()));
        assertEquals(MallEnum.OrderStatus.PENDING_PAY.getValue(), orderStatusOf(order.getOrderNo()),
                "前置：订单此刻仍是待支付");

        assertThrows(JbkException.class, () -> orderService.cancelByUser(USER,
                new MallOrderActionBo().setOrderNo(order.getOrderNo())),
                "钱已收，取消必须被拒绝");

        // 关键：不得出现"钱收了+单取消了+货放回去"的裸奔终态
        assertEquals(MallEnum.OrderStatus.PENDING_PAY.getValue(), orderStatusOf(order.getOrderNo()));
        assertEquals(MallEnum.PayStatus.SUCCESS.getValue(), payStatusOf(order.getOrderNo()));
        assertEquals(8L, availableOf(WH1, SKU1), "预占不得被释放");
        assertEquals(2L, reservedOf(WH1, SKU1));
        assertEquals(0L, flowCountOfType(MallEnum.StockFlowType.ORDER_RELEASE.getValue()));

        // 事务B 随后仍能正常把订单推成已支付并实销
        var fact = payFactService.findFact(MallEnum.PaySource.PAY_SIM.getValue(),
                MallEnum.FactChannel.PAY_SIM.getValue(),
                MallPaySimServiceImpl.SIM_EVENT_KEY_PREFIX + order.getOrderNo());
        payFactService.process(fact.getId());
        assertEquals(MallEnum.OrderStatus.PAID.getValue(), orderStatusOf(order.getOrderNo()));
        assertEquals(1L, flowCountOfType(MallEnum.StockFlowType.ORDER_SELL.getValue()));
    }

    @Test
    @DisplayName("自查② 取消成功时支付单必须同步关闭：两者终态相容")
    void cancelClosesPaymentSoTerminalStatesAgree() {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        orderService.cancelByUser(USER, new MallOrderActionBo().setOrderNo(order.getOrderNo()));
        assertEquals(MallEnum.OrderStatus.CANCELLED.getValue(), orderStatusOf(order.getOrderNo()));
        assertEquals(MallEnum.PayStatus.CLOSED.getValue(), payStatusOf(order.getOrderNo()),
                "订单取消而支付单仍待支付=账实不符");
        assertEquals(10L, availableOf(WH1, SKU1));
    }

    @Test
    @DisplayName("自查③ 购物车行数上限与 increment 无关：覆盖语义同样受限")
    void cartLineCapAppliesRegardlessOfIncrementFlag() {
        // 用覆盖语义（increment=false）逐个建行，直到触顶
        for (int i = 0; i < 50; i++) {
            long skuId = 700L + i;
            seedSku(skuId, "MS-CAP2-" + i, 100L);
            cartService.save(USER, new com.jbk.tool.data.mall.bo.MallCartSaveBo()
                    .setSkuId(skuId).setQuantity(1).setIncrement(false));
        }
        seedSku(999L, "MS-CAP2-OVER", 100L);
        assertThrows(JbkException.class, () -> cartService.save(USER,
                new com.jbk.tool.data.mall.bo.MallCartSaveBo()
                        .setSkuId(999L).setQuantity(1).setIncrement(false)),
                "端上传 increment=false 不得绕过行数上限");
        assertEquals(50L, countOf("ws_mall_cart_item"));
    }

    @Test
    @DisplayName("自查④ Pay-Sim 重放跨秒不得被判成篡改：自签事实的时间不是外部证据")
    void paySimReplayAcrossSecondBoundaryIsNotTreatedAsTampering() {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        String key = MallPaySimServiceImpl.SIM_EVENT_KEY_PREFIX + order.getOrderNo();
        String tx = MallPaySimServiceImpl.SIM_TRANSACTION_PREFIX + order.getOrderNo();
        String simTime = payTimeOf(order);
        payFactService.recordFact(MallEnum.PaySource.PAY_SIM.getValue(),
                MallEnum.FactChannel.PAY_SIM.getValue(), key, order.getOrderNo(),
                MallEnum.TradeState.SUCCESS, tx, order.getOrderAmountFen(), simTime,
                MallEnum.VerifyMethod.PAY_SIM_INTERNAL,
                "{\"channel\":\"pay-sim\",\"orderNo\":\"" + order.getOrderNo() + "\"}");

        // 同键重放，仅成功时间差一秒（并发跨整秒的真实形态）：应复用原事实而不是报"请人工核查"
        var replay = payFactService.recordFact(MallEnum.PaySource.PAY_SIM.getValue(),
                MallEnum.FactChannel.PAY_SIM.getValue(), key, order.getOrderNo(),
                MallEnum.TradeState.SUCCESS, tx, order.getOrderAmountFen(),
                DateUtils.plusSeconds(simTime, 1),
                MallEnum.VerifyMethod.PAY_SIM_INTERNAL,
                "{\"channel\":\"pay-sim\",\"orderNo\":\"" + order.getOrderNo() + "\"}");
        assertEquals(simTime, replay.getPaySuccessTime(), "以先落库的时间为准，不被覆盖");
        assertEquals(1L, countOf("ws_mall_payment_fact"));

        // 但外部渠道（通知）仍然逐字比对，一秒都不能差
        String extKey = "EXT-" + order.getOrderNo();
        // 两次都带非空时间且只差一秒：比"空对非空"更接近"一秒都不能差"这句话本身
        payFactService.recordFact(MallEnum.PaySource.PAY_SIM.getValue(),
                MallEnum.FactChannel.NOTIFY.getValue(), extKey, order.getOrderNo(),
                MallEnum.TradeState.NOTPAY, null, null, simTime,
                MallEnum.VerifyMethod.PAY_SIM_INTERNAL, "{}");
        assertThrows(JbkException.class, () -> payFactService.recordFact(
                MallEnum.PaySource.PAY_SIM.getValue(), MallEnum.FactChannel.NOTIFY.getValue(),
                extKey, order.getOrderNo(), MallEnum.TradeState.NOTPAY, null, null,
                DateUtils.plusSeconds(simTime, 1), MallEnum.VerifyMethod.PAY_SIM_INTERNAL, "{}"),
                "外部渠道的时间仍是不可变证据");
    }

    @Test
    @DisplayName("自查⑤ 订单列表首图不再是死字段")
    void orderListCarriesFirstCoverUrl() {
        jdbc.update("UPDATE ws_mall_product SET COVER_URL='/img/mall/p301.png' WHERE ID=?", PRODUCT);
        MallOrderVo created = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 1));
        assertEquals("/img/mall/p301.png", created.getFirstCoverUrl(), "创单返回即带首图");

        var page = orderService.pageForUser(USER, new com.jbk.tool.data.mall.bo.MallOrderQueryBo());
        assertEquals("/img/mall/p301.png", page.getList().get(0).getFirstCoverUrl(), "列表同样带首图");
        assertEquals(created.getOrderNo(), page.getList().get(0).getOrderNo());
    }

    @Test
    @DisplayName("⑯ 购物车：加购累加/覆盖、失效行保留、下单后仅清理已购行")
    void cartLifecycleAndCleanupAfterOrder() {
        cartService.save(USER, new com.jbk.tool.data.mall.bo.MallCartSaveBo()
                .setSkuId(SKU1).setQuantity(2).setIncrement(true));
        cartService.save(USER, new com.jbk.tool.data.mall.bo.MallCartSaveBo()
                .setSkuId(SKU1).setQuantity(3).setIncrement(true));
        var cart = cartService.list(USER);
        assertEquals(1, cart.getLines().size());
        assertEquals(5, cart.getLines().get(0).getQuantity(), "加购累加");
        assertEquals(6000L, cart.getTotalAmountFen(), "1200×5");

        cartService.save(USER, new com.jbk.tool.data.mall.bo.MallCartSaveBo()
                .setSkuId(SKU1).setQuantity(2).setIncrement(false));
        assertEquals(2, cartService.list(USER).getLines().get(0).getQuantity(), "覆盖设置");

        cartService.save(USER, new com.jbk.tool.data.mall.bo.MallCartSaveBo()
                .setSkuId(SKU2).setQuantity(1).setIncrement(true));
        assertEquals(2, cartService.list(USER).getLines().size());

        // 只下单 SKU1：SKU2 仍留在购物车
        orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        var after = cartService.list(USER);
        assertEquals(1, after.getLines().size());
        assertEquals(String.valueOf(SKU2), after.getLines().get(0).getSkuId());

        // SKU2 停售后仍保留在车里并标注原因，不计入合计
        jdbc.update("UPDATE ws_mall_sku SET SKU_STATUS=2 WHERE ID=?", SKU2);
        var withInvalid = cartService.list(USER);
        assertEquals(1, withInvalid.getLines().size());
        assertEquals(Boolean.FALSE, withInvalid.getLines().get(0).getPurchasable());
        assertEquals(0L, withInvalid.getTotalAmountFen());
        assertNotNull(withInvalid.getLines().get(0).getUnavailableReason());
    }

    // ==================== R2 支付事实严格共键 ====================

    /** 落一条成功事实（Pay-Sim 形态），事件键可自定以模拟多次外部投递。 */
    /** 默认落在该订单付款窗内，与真实 Pay-Sim 链路同形。 */
    private com.jbk.tool.data.mall.po.WsMallPaymentFact successFact(
            MallOrderVo order, String eventKey, String transactionId) {
        return successFact(order, eventKey, transactionId, payTimeOf(order));
    }

    private com.jbk.tool.data.mall.po.WsMallPaymentFact successFact(
            MallOrderVo order, String eventKey, String transactionId, String paySuccessTime) {
        return payFactService.recordFact(MallEnum.PaySource.PAY_SIM.getValue(),
                MallEnum.FactChannel.PAY_SIM.getValue(), eventKey, order.getOrderNo(),
                MallEnum.TradeState.SUCCESS, transactionId, order.getOrderAmountFen(),
                paySuccessTime, MallEnum.VerifyMethod.PAY_SIM_INTERNAL, "{}");
    }

    private int factStatusOf(Long factId) {
        return jdbc.queryForObject(
                "SELECT PROCESSING_STATUS FROM ws_mall_payment_fact WHERE ID=?", Integer.class,
                factId);
    }

    @Test
    @DisplayName("R2① 逻辑删除的订单收到成功事实：事实转人工，支付单不得变成功，零实销")
    void logicallyDeletedOrderNeverAdvancesPayment() {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        jdbc.update("UPDATE ws_mall_order SET DATA_STATUS=1 WHERE ORDER_NO=?", order.getOrderNo());

        var fact = successFact(order, "R2-DELORD-" + order.getOrderNo(), "TXR2A");

        assertEquals(MallEnum.PayFactStatus.NEED_RECONCILE.getValue(), factStatusOf(fact.getId()),
                "订单已删除，事实只能留证转人工");
        assertEquals(MallEnum.PayStatus.PENDING.getValue(), payStatusOf(order.getOrderNo()),
                "支付单绝不能在订单已删除时被推成成功");
        assertEquals(0L, flowCountOfType(MallEnum.StockFlowType.ORDER_SELL.getValue()));
        assertEquals(2L, reservedOf(WH1, SKU1), "预占既不释放也不实销");
        // 终态事实不可再被 claim，推进链路整体静止
        assertNotEquals(IMallPayApplyTx.Code.APPLIED, payFactService.process(fact.getId()).code());
        assertEquals(0L, flowCountOfType(MallEnum.StockFlowType.ORDER_SELL.getValue()));
    }

    @Test
    @DisplayName("R2② 逻辑删除的支付单收到成功事实：事实转人工，零资金零库存动作")
    void logicallyDeletedPaymentNeverAdvances() {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        jdbc.update("UPDATE ws_mall_payment SET DATA_STATUS=1 WHERE ORDER_NO=?",
                order.getOrderNo());

        var fact = successFact(order, "R2-DELPAY-" + order.getOrderNo(), "TXR2B");

        assertEquals(MallEnum.PayFactStatus.NEED_RECONCILE.getValue(), factStatusOf(fact.getId()));
        assertEquals(MallEnum.PayStatus.PENDING.getValue(), payStatusOf(order.getOrderNo()),
                "已删除的支付单不得被改写");
        assertEquals(MallEnum.OrderStatus.PENDING_PAY.getValue(), orderStatusOf(order.getOrderNo()));
        assertEquals(0L, flowCountOfType(MallEnum.StockFlowType.ORDER_SELL.getValue()));
        assertEquals(0L, flowCountOfType(MallEnum.StockFlowType.ORDER_RELEASE.getValue()));
        assertEquals(8L, availableOf(WH1, SKU1));
        assertEquals(2L, reservedOf(WH1, SKU1), "预占既不释放也不实销");
        assertNotEquals(IMallPayApplyTx.Code.APPLIED, payFactService.process(fact.getId()).code());
    }

    @Test
    @DisplayName("R2③ 伪造业务时间（含 20260230/00000000000000）一律拒绝推进，合法时间照常通过")
    void fabricatedPaySuccessTimeIsRefused() {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        // 20260229：2026 不是闰年，这一天不存在，与 0230 同属"日历上没有的日子"
        String[] fabricated = {"20260230000000", "00000000000000", "99999999999999",
                "20261301000000", "20260809256000", "20260229120000"};

        for (int i = 0; i < fabricated.length; i++) {
            var bad = successFact(order, "R2-BADTIME-" + i + "-" + order.getOrderNo(),
                    "TXR2C" + i, fabricated[i]);
            assertEquals(MallEnum.PayFactStatus.NEED_RECONCILE.getValue(), factStatusOf(bad.getId()),
                    "伪造时间 " + fabricated[i] + " 必须留证转人工");
            assertEquals(MallEnum.PayStatus.PENDING.getValue(), payStatusOf(order.getOrderNo()),
                    "伪造时间 " + fabricated[i] + " 不得把支付单推成成功");
        }
        assertEquals(MallEnum.OrderStatus.PENDING_PAY.getValue(), orderStatusOf(order.getOrderNo()));
        assertEquals(0L, flowCountOfType(MallEnum.StockFlowType.ORDER_SELL.getValue()));
        assertEquals(2L, reservedOf(WH1, SKU1));

        // 反向证明判据不是"一律拒绝"：真实时间照常通过并推进支付单
        var good = successFact(order, "R2-GOODTIME-" + order.getOrderNo(), "TXR2CO");
        assertEquals(MallEnum.PayFactStatus.PENDING.getValue(), factStatusOf(good.getId()));
        assertEquals(MallEnum.PayStatus.SUCCESS.getValue(), payStatusOf(order.getOrderNo()));
        assertEquals(IMallPayApplyTx.Code.APPLIED, payFactService.process(good.getId()).code());
        assertEquals(1L, flowCountOfType(MallEnum.StockFlowType.ORDER_SELL.getValue()));
        assertEquals(MallEnum.OrderStatus.PAID.getValue(), orderStatusOf(order.getOrderNo()));
        assertEquals(0L, reservedOf(WH1, SKU1), "合法路径确实把预占转成了实销");
    }

    @Test
    @DisplayName("R2④ 两段之间篡改支付单交易号：事务B 转人工，订单与库存零变化")
    void tamperedTransactionIdBlocksSettlement() {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        var fact = successFact(order, "R2-TXID-" + order.getOrderNo(), "TXR2D");
        assertEquals(MallEnum.PayStatus.SUCCESS.getValue(), payStatusOf(order.getOrderNo()),
                "前置：事务A 已完成");

        // 事务A 与事务B 之间，支付单被另一笔交易改写
        jdbc.update("UPDATE ws_mall_payment SET TRANSACTION_ID='TX-SOMEONE-ELSE' WHERE ORDER_NO=?",
                order.getOrderNo());

        var outcome = payFactService.process(fact.getId());
        assertEquals(IMallPayApplyTx.Code.RECONCILE, outcome.code(),
                "事务B 不得信任事务A 的旧结论");
        assertEquals(MallEnum.PayFactStatus.NEED_RECONCILE.getValue(), factStatusOf(fact.getId()));
        assertEquals(MallEnum.OrderStatus.PENDING_PAY.getValue(), orderStatusOf(order.getOrderNo()));
        assertEquals(0L, flowCountOfType(MallEnum.StockFlowType.ORDER_SELL.getValue()));
        assertEquals(8L, availableOf(WH1, SKU1));
        assertEquals(2L, reservedOf(WH1, SKU1));
    }

    @Test
    @DisplayName("R2⑤ 两段之间篡改支付单成功时间或支付来源：同样转人工，货不出门")
    void tamperedPayTimeOrSourceBlocksSettlement() {
        MallOrderVo timeCase = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        var timeFact = successFact(timeCase, "R2-TIME-" + timeCase.getOrderNo(), "TXR2E",
                payTimeOf(timeCase));
        // 篡改值必须由真实时间派生：写死字面量在"恰好跑在该时刻"时会与事实时间相等，
        // 判据没坏而测试先绿了
        jdbc.update("UPDATE ws_mall_payment SET PAY_SUCCESS_TIME=? WHERE ORDER_NO=?",
                DateUtils.plusSeconds(payTimeOf(timeCase), 900), timeCase.getOrderNo());
        assertEquals(IMallPayApplyTx.Code.RECONCILE,
                payFactService.process(timeFact.getId()).code());
        assertEquals(MallEnum.OrderStatus.PENDING_PAY.getValue(),
                orderStatusOf(timeCase.getOrderNo()));

        MallOrderVo srcCase = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 3));
        var srcFact = successFact(srcCase, "R2-SRC-" + srcCase.getOrderNo(), "TXR2F",
                payTimeOf(srcCase));
        jdbc.update("UPDATE ws_mall_payment SET PAY_SOURCE=? WHERE ORDER_NO=?",
                MallEnum.PaySource.WECHAT.getValue(), srcCase.getOrderNo());
        assertEquals(IMallPayApplyTx.Code.RECONCILE, payFactService.process(srcFact.getId()).code());
        assertEquals(MallEnum.OrderStatus.PENDING_PAY.getValue(),
                orderStatusOf(srcCase.getOrderNo()));

        assertEquals(MallEnum.PayFactStatus.NEED_RECONCILE.getValue(), factStatusOf(timeFact.getId()),
                "拦下来还不够，事实必须留证转人工");
        assertEquals(MallEnum.PayFactStatus.NEED_RECONCILE.getValue(), factStatusOf(srcFact.getId()));
        assertEquals(MallEnum.PayStatus.SUCCESS.getValue(), payStatusOf(timeCase.getOrderNo()),
                "钱已收的事实不因拦截而回滚");
        assertEquals(0L, flowCountOfType(MallEnum.StockFlowType.ORDER_SELL.getValue()),
                "两例都不得产生实销流水");
        assertEquals(5L, availableOf(WH1, SKU1), "10 - 2 - 3 预占后不变");
        assertEquals(5L, reservedOf(WH1, SKU1));
    }

    @Test
    @DisplayName("R2⑥ 完全合法的事实：推进实销恰好一次，重复投递不再动库存")
    void legitimateFactSellsExactlyOnce() {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        String eventKey = "R2-OK-" + order.getOrderNo();
        var fact = successFact(order, eventKey, "TXR2G");

        assertEquals(IMallPayApplyTx.Code.APPLIED, payFactService.process(fact.getId()).code());
        assertEquals(MallEnum.OrderStatus.PAID.getValue(), orderStatusOf(order.getOrderNo()));
        assertEquals(MallEnum.PayStatus.SUCCESS.getValue(), payStatusOf(order.getOrderNo()));
        assertEquals(1L, flowCountOfType(MallEnum.StockFlowType.ORDER_SELL.getValue()));
        assertEquals(8L, availableOf(WH1, SKU1));
        assertEquals(0L, reservedOf(WH1, SKU1), "预占已转实销");

        // 同键重放：复用原事实，终态不可再 claim
        var replay = successFact(order, eventKey, "TXR2G");
        assertEquals(fact.getId(), replay.getId());
        assertEquals(MallEnum.PayFactStatus.PROCESSED.getValue(), factStatusOf(fact.getId()));
        assertEquals(1L, countOf("ws_mall_payment_fact"), "同键重放不得新增事实行");
        assertNotEquals(IMallPayApplyTx.Code.APPLIED, payFactService.process(replay.getId()).code());

        // 换事件键的重复投递：订单已推进且实销证据齐全，判定幂等完成而不是再卖一次
        var second = successFact(order, eventKey + "-DUP", "TXR2G");
        assertEquals(IMallPayApplyTx.Code.ALREADY, payFactService.process(second.getId()).code());
        assertEquals(2L, countOf("ws_mall_payment_fact"), "换键投递是另一条事实");
        assertEquals(MallEnum.PayFactStatus.PROCESSED.getValue(), factStatusOf(second.getId()));
        assertEquals(1L, flowCountOfType(MallEnum.StockFlowType.ORDER_SELL.getValue()));
        assertEquals(8L, availableOf(WH1, SKU1));
        assertEquals(0L, reservedOf(WH1, SKU1));
    }

    @Test
    @DisplayName("R2⑦ 订单已推进但实销流水缺失：不得判成幂等完成，必须转人工")
    void advancedOrderWithoutSellEvidenceGoesToReconcile() {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        var fact = successFact(order, "R2-EVID-" + order.getOrderNo(), "TXR2H");
        assertEquals(IMallPayApplyTx.Code.APPLIED, payFactService.process(fact.getId()).code());

        // 抹掉实销证据：订单状态仍是已支付，但"卖过"这件事已无从证明
        jdbc.update("DELETE FROM ws_mall_stock_flow WHERE BIZ_IDEMPOTENCY_KEY=?",
                MallPayApplyTxImpl.SELL_KEY_PREFIX + order.getOrderNo() + ":" + SKU1);

        var again = successFact(order, "R2-EVID-DUP-" + order.getOrderNo(), "TXR2H");
        assertEquals(IMallPayApplyTx.Code.RECONCILE, payFactService.process(again.getId()).code(),
                "状态对但证据缺失，正是最需要人工介入的形态");
        assertEquals(MallEnum.PayFactStatus.NEED_RECONCILE.getValue(), factStatusOf(again.getId()));
        assertEquals(0L, flowCountOfType(MallEnum.StockFlowType.ORDER_SELL.getValue()),
                "转人工不得顺手补一笔实销");
        assertEquals(MallEnum.OrderStatus.PAID.getValue(), orderStatusOf(order.getOrderNo()),
                "转人工不得回滚已推进的订单");
        assertEquals(MallEnum.PayStatus.SUCCESS.getValue(), payStatusOf(order.getOrderNo()));
        assertEquals(0L, reservedOf(WH1, SKU1), "不得二次扣减预占");
    }

    @Test
    @DisplayName("R2⑧ 实销流水被逻辑删除后不再是证据：判人工而不是幂等完成")
    void logicallyDeletedSellFlowIsNotEvidence() {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        var fact = successFact(order, "R2-FLOWDEL-" + order.getOrderNo(), "TXR2I");
        assertEquals(IMallPayApplyTx.Code.APPLIED, payFactService.process(fact.getId()).code());

        // 幂等键不含 DATA_STATUS：这一行被逻辑删除后仍挡着重复插入，但已不能证明货卖出去过
        jdbc.update("UPDATE ws_mall_stock_flow SET DATA_STATUS=1 WHERE BIZ_IDEMPOTENCY_KEY=?",
                MallPayApplyTxImpl.SELL_KEY_PREFIX + order.getOrderNo() + ":" + SKU1);

        var again = successFact(order, "R2-FLOWDEL-DUP-" + order.getOrderNo(), "TXR2I");
        assertEquals(IMallPayApplyTx.Code.RECONCILE, payFactService.process(again.getId()).code(),
                "被删除的流水不得充当实销证据");
        assertEquals(MallEnum.PayFactStatus.NEED_RECONCILE.getValue(), factStatusOf(again.getId()));
        assertEquals(MallEnum.OrderStatus.PAID.getValue(), orderStatusOf(order.getOrderNo()));
        assertEquals(0L, reservedOf(WH1, SKU1), "不得二次扣减预占");
    }

    // ==================== R3 付款窗资格与交易状态白名单 ====================

    /** 以给定成功时间落一条 Pay-Sim 成功事实，返回其处理状态。 */
    private int factStatusOfSuccessAt(MallOrderVo order, String key, String paySuccessTime) {
        var fact = successFact(order, key + order.getOrderNo(), "TXR3-" + key, paySuccessTime);
        return factStatusOf(fact.getId());
    }

    @Test
    @DisplayName("R3① 支付时间早于订单创建时间：事实转人工，支付单/订单/库存零变化")
    void payTimeBeforeOrderCreationIsRefused() {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        String tooEarly = DateUtils.plusSeconds(payTimeOf(order), -1);

        assertEquals(MallEnum.PayFactStatus.NEED_RECONCILE.getValue(),
                factStatusOfSuccessAt(order, "EARLY-", tooEarly),
                "早于建单的支付不可能属于这张单");
        assertEquals(MallEnum.PayStatus.PENDING.getValue(), payStatusOf(order.getOrderNo()));
        assertEquals(MallEnum.OrderStatus.PENDING_PAY.getValue(), orderStatusOf(order.getOrderNo()));
        assertEquals(8L, availableOf(WH1, SKU1));
        assertEquals(2L, reservedOf(WH1, SKU1));
        assertEquals(0L, flowCountOfType(MallEnum.StockFlowType.ORDER_SELL.getValue()));
    }

    @Test
    @DisplayName("R3② 支付时间正好等于付款截止时间：允许成功并实销")
    void payTimeExactlyAtDeadlineIsAccepted() {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        var fact = successFact(order, "R3-EDGE-" + order.getOrderNo(), "TXR3B",
                payDeadlineOf(order));

        assertEquals(MallEnum.PayFactStatus.PENDING.getValue(), factStatusOf(fact.getId()),
                "边界值属于窗内，不得被拒");
        assertEquals(MallEnum.PayStatus.SUCCESS.getValue(), payStatusOf(order.getOrderNo()));
        assertEquals(IMallPayApplyTx.Code.APPLIED, payFactService.process(fact.getId()).code());
        assertEquals(MallEnum.OrderStatus.PAID.getValue(), orderStatusOf(order.getOrderNo()));
        assertEquals(1L, flowCountOfType(MallEnum.StockFlowType.ORDER_SELL.getValue()));
        assertEquals(0L, reservedOf(WH1, SKU1));
    }

    @Test
    @DisplayName("R3③ 支付时间晚于付款截止时间一秒：事实转人工，零实销")
    void payTimeAfterDeadlineIsRefused() {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        String tooLate = DateUtils.plusSeconds(payDeadlineOf(order), 1);

        assertEquals(MallEnum.PayFactStatus.NEED_RECONCILE.getValue(),
                factStatusOfSuccessAt(order, "LATE-", tooLate),
                "超时到账该由人工决定退款还是补发，不能自动扣库存发货");
        assertEquals(MallEnum.PayStatus.PENDING.getValue(), payStatusOf(order.getOrderNo()));
        assertEquals(MallEnum.OrderStatus.PENDING_PAY.getValue(), orderStatusOf(order.getOrderNo()));
        assertEquals(0L, flowCountOfType(MallEnum.StockFlowType.ORDER_SELL.getValue()));
        assertEquals(2L, reservedOf(WH1, SKU1));
    }

    @Test
    @DisplayName("R3④ 订单与支付单付款截止时间不一致：转人工，零推进")
    void mismatchedPayDeadlinesGoToReconcile() {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        // 两处截止时间本由同一个变量写入，不一致即证明有一侧被改过
        jdbc.update("UPDATE ws_mall_payment SET PAY_EXPIRE_TIME=? WHERE ORDER_NO=?",
                DateUtils.plusSeconds(payDeadlineOf(order), 60), order.getOrderNo());

        assertEquals(MallEnum.PayFactStatus.NEED_RECONCILE.getValue(),
                factStatusOfSuccessAt(order, "DEADLINE-", payTimeOf(order)));
        assertEquals(MallEnum.PayStatus.PENDING.getValue(), payStatusOf(order.getOrderNo()));
        assertEquals(MallEnum.OrderStatus.PENDING_PAY.getValue(), orderStatusOf(order.getOrderNo()));
        assertEquals(0L, flowCountOfType(MallEnum.StockFlowType.ORDER_SELL.getValue()));
    }

    @Test
    @DisplayName("R3⑤ Pay-Sim 点在已过期但尚未关闭的订单上：拒绝，零事实零实销")
    void paySimRefusesExpiredOrderBeforeWorkerCloses() {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        // 关单 Worker 每分钟才扫一轮：这段"已过期但还挂在待支付"的窗口是常态
        jdbc.update("UPDATE ws_mall_order SET PAY_EXPIRE_TIME='20260101000000' WHERE ORDER_NO=?",
                order.getOrderNo());
        jdbc.update("UPDATE ws_mall_payment SET PAY_EXPIRE_TIME='20260101000000' WHERE ORDER_NO=?",
                order.getOrderNo());

        assertThrows(JbkException.class, () -> paySimService.pay(USER, order.getOrderNo()),
                "不得在过期窗口里模拟出一笔新的成功事实");
        assertEquals(0L, countOf("ws_mall_payment_fact"), "零事实");
        assertEquals(MallEnum.PayStatus.PENDING.getValue(), payStatusOf(order.getOrderNo()));
        assertEquals(MallEnum.OrderStatus.PENDING_PAY.getValue(), orderStatusOf(order.getOrderNo()));
        assertEquals(0L, flowCountOfType(MallEnum.StockFlowType.ORDER_SELL.getValue()));
        assertEquals(2L, reservedOf(WH1, SKU1), "既不实销也不释放，等 Worker 走权威关单");
    }

    @Test
    @DisplayName("R3⑥ 未过期时 Pay-Sim 与关单 Worker 并发：支付实销，且权威关单事后也推不翻")
    void payAndExpireWorkerConcurrentWithinWindow() throws Exception {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        String futureScan = DateUtils.plusSeconds(payDeadlineOf(order), 60);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            var payTask = pool.submit(() -> {
                go.await();
                paySimService.pay(USER, order.getOrderNo());
                return null;
            });
            var closeTask = pool.submit(() -> {
                go.await();
                // 扫描按传入时间挑单，但是否关闭仍由支付方查单说了算
                return orderExpireWorker.runOnce(futureScan);
            });
            go.countDown();
            payTask.get();
            assertEquals(0, closeTask.get(), "查单答复 NOTPAY，关单链路不得动手");
        }
        finally {
            pool.shutdownNow();
        }

        assertEquals(MallEnum.OrderStatus.PAID.getValue(), orderStatusOf(order.getOrderNo()));
        assertEquals(MallEnum.PayStatus.SUCCESS.getValue(), payStatusOf(order.getOrderNo()));
        assertEquals(1L, flowCountOfType(MallEnum.StockFlowType.ORDER_SELL.getValue()));
        assertEquals(0L, flowCountOfType(MallEnum.StockFlowType.ORDER_RELEASE.getValue()),
                "实销与释放互斥，不得同时发生");
        // 钱已收之后，即便权威关单链路再来一次也必须被拒
        assertThrows(JbkException.class,
                () -> orderService.closeByAuthority(order.getOrderNo(), "迟到的超时关闭"));
        assertEquals(MallEnum.OrderStatus.PAID.getValue(), orderStatusOf(order.getOrderNo()));
        assertEquals(0L, reservedOf(WH1, SKU1));
    }

    @Test
    @DisplayName("R3⑦ 已过期时 Pay-Sim 与关单 Worker 并发：关闭释放，且不产生任何实销")
    void payAndExpireWorkerConcurrentAfterDeadline() throws Exception {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        jdbc.update("UPDATE ws_mall_order SET PAY_EXPIRE_TIME='20260101000000' WHERE ORDER_NO=?",
                order.getOrderNo());
        jdbc.update("UPDATE ws_mall_payment SET PAY_EXPIRE_TIME='20260101000000' WHERE ORDER_NO=?",
                order.getOrderNo());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger payRejected = new AtomicInteger();
        try {
            var payTask = pool.submit(() -> {
                go.await();
                try {
                    paySimService.pay(USER, order.getOrderNo());
                }
                catch (JbkException refused) {
                    payRejected.incrementAndGet();
                }
                return null;
            });
            var closeTask = pool.submit(() -> {
                go.await();
                return orderExpireWorker.runOnce(DateUtils.time());
            });
            go.countDown();
            // 两个都必须等到跑完再断言：只等关单那条会让 shutdownNow 把支付线程打断，
            // 计数器停在 0，测试变成偶发红
            payTask.get();
            assertEquals(1, closeTask.get().intValue(), "查单答复 CLOSED，关单成立");
        }
        finally {
            pool.shutdownNow();
        }

        assertEquals(1, payRejected.get(), "过期后支付必须被入口拒绝");
        assertEquals(MallEnum.OrderStatus.CANCELLED.getValue(), orderStatusOf(order.getOrderNo()));
        assertEquals(MallEnum.PayStatus.CLOSED.getValue(), payStatusOf(order.getOrderNo()));
        assertEquals(1L, flowCountOfType(MallEnum.StockFlowType.ORDER_RELEASE.getValue()));
        assertEquals(0L, flowCountOfType(MallEnum.StockFlowType.ORDER_SELL.getValue()),
                "支付实销与关闭释放只能二选一");
        assertEquals(10L, availableOf(WH1, SKU1));
        assertEquals(0L, reservedOf(WH1, SKU1));
    }

    @Test
    @DisplayName("R3⑧ 未知交易状态留证转人工，绝不盖成已处理")
    void unknownTradeStateGoesToReconcileNotProcessed() {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        // 事实键在库层排序规则下大小写不敏感，故按序号取键，避免 success/SUCCESS 自撞
        String[] unknowns = {"UNKNOWN", "REFUND", "success", "SUCCESS "};
        for (int i = 0; i < unknowns.length; i++) {
            String unknown = unknowns[i];
            var fact = payFactService.recordFact(MallEnum.PaySource.PAY_SIM.getValue(),
                    MallEnum.FactChannel.NOTIFY.getValue(),
                    "R3-STATE-" + i + "-" + order.getOrderNo(), order.getOrderNo(),
                    unknown, "TXR3-" + i, order.getOrderAmountFen(), payTimeOf(order),
                    MallEnum.VerifyMethod.PAY_SIM_INTERNAL, "{}");
            assertEquals(MallEnum.PayFactStatus.NEED_RECONCILE.getValue(),
                    factStatusOf(fact.getId()), "未知状态 " + unknown + " 必须留证转人工");
            assertNotEquals(IMallPayApplyTx.Code.APPLIED,
                    payFactService.process(fact.getId()).code());
            assertNotEquals(MallEnum.PayFactStatus.PROCESSED.getValue(), factStatusOf(fact.getId()),
                    "未知状态 " + unknown + " 绝不能被盖成已处理");
        }
        assertEquals(MallEnum.PayStatus.PENDING.getValue(), payStatusOf(order.getOrderNo()));
        assertEquals(MallEnum.OrderStatus.PENDING_PAY.getValue(), orderStatusOf(order.getOrderNo()));
        assertEquals(0L, flowCountOfType(MallEnum.StockFlowType.ORDER_SELL.getValue()));
        assertEquals(0L, flowCountOfType(MallEnum.StockFlowType.ORDER_RELEASE.getValue()));
        assertEquals(2L, reservedOf(WH1, SKU1));
    }

    @Test
    @DisplayName("R3⑨ 事务B 第二道白名单：准入后被改成未知状态的事实必须转人工")
    void unknownTradeStateIsBlockedAgainInApplyStage() {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        var fact = payFactService.recordFact(MallEnum.PaySource.PAY_SIM.getValue(),
                MallEnum.FactChannel.NOTIFY.getValue(), "R3-APPLY-" + order.getOrderNo(),
                order.getOrderNo(), MallEnum.TradeState.NOTPAY, null, null, null,
                MallEnum.VerifyMethod.PAY_SIM_INTERNAL, "{}");
        assertEquals(MallEnum.PayFactStatus.PENDING.getValue(), factStatusOf(fact.getId()));

        // 准入之后事实被改写：推进段不得因为"反正不是 SUCCESS"就当作无需处理
        jdbc.update("UPDATE ws_mall_payment_fact SET TRADE_STATE='REFUND' WHERE ID=?",
                fact.getId());
        assertEquals(IMallPayApplyTx.Code.RECONCILE, payFactService.process(fact.getId()).code());
        assertEquals(MallEnum.PayFactStatus.NEED_RECONCILE.getValue(), factStatusOf(fact.getId()));
        assertEquals(MallEnum.PayStatus.PENDING.getValue(), payStatusOf(order.getOrderNo()));
        assertEquals(0L, flowCountOfType(MallEnum.StockFlowType.ORDER_SELL.getValue()));
    }

    @Test
    @DisplayName("R3⑩ 空白交易状态 fail-closed：整笔拒绝，连事实都不落")
    void blankTradeStateIsRefusedOutright() {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        for (String blank : new String[]{null, "", "   ", "\t"}) {
            assertThrows(JbkException.class, () -> payFactService.recordFact(
                    MallEnum.PaySource.PAY_SIM.getValue(), MallEnum.FactChannel.NOTIFY.getValue(),
                    "R3-BLANK-" + order.getOrderNo(), order.getOrderNo(), blank,
                    "TXR3BLANK", order.getOrderAmountFen(), payTimeOf(order),
                    MallEnum.VerifyMethod.PAY_SIM_INTERNAL, "{}"),
                    "没有状态的事实存下去也只会被下游盖成已处理");
        }
        assertEquals(0L, countOf("ws_mall_payment_fact"));
        assertEquals(MallEnum.PayStatus.PENDING.getValue(), payStatusOf(order.getOrderNo()));
        assertEquals(MallEnum.OrderStatus.PENDING_PAY.getValue(), orderStatusOf(order.getOrderNo()));
        assertEquals(0L, flowCountOfType(MallEnum.StockFlowType.ORDER_SELL.getValue()));
    }

    @Test
    @DisplayName("R3⑪ NOTPAY 与 CLOSED 的既有合法路径保持通过")
    void knownNonSuccessStatesStillPass() {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        var notpay = payFactService.recordFact(MallEnum.PaySource.PAY_SIM.getValue(),
                MallEnum.FactChannel.NOTIFY.getValue(), "R3-NOTPAY-" + order.getOrderNo(),
                order.getOrderNo(), MallEnum.TradeState.NOTPAY, null, null, null,
                MallEnum.VerifyMethod.PAY_SIM_INTERNAL, "{}");
        assertEquals(MallEnum.PayFactStatus.PENDING.getValue(), factStatusOf(notpay.getId()));
        assertEquals(IMallPayApplyTx.Code.ALREADY, payFactService.process(notpay.getId()).code());
        assertEquals(MallEnum.PayFactStatus.PROCESSED.getValue(), factStatusOf(notpay.getId()));

        var closed = payFactService.recordFact(MallEnum.PaySource.PAY_SIM.getValue(),
                MallEnum.FactChannel.QUERY.getValue(),
                MallOrderExpireWorker.QUERY_EVENT_KEY_PREFIX + order.getOrderNo(),
                order.getOrderNo(), MallEnum.TradeState.CLOSED, null, null, null,
                MallEnum.VerifyMethod.PAY_SIM_INTERNAL, "{}");
        assertEquals(MallEnum.PayFactStatus.PENDING.getValue(), factStatusOf(closed.getId()));
        assertEquals(IMallPayApplyTx.Code.ALREADY, payFactService.process(closed.getId()).code());
        assertEquals(MallEnum.PayFactStatus.PROCESSED.getValue(), factStatusOf(closed.getId()));

        assertEquals(MallEnum.PayStatus.PENDING.getValue(), payStatusOf(order.getOrderNo()),
                "查询类事实不动资金");
        assertEquals(MallEnum.OrderStatus.PENDING_PAY.getValue(), orderStatusOf(order.getOrderNo()));
        assertEquals(0L, flowCountOfType(MallEnum.StockFlowType.ORDER_SELL.getValue()));
        assertEquals(2L, reservedOf(WH1, SKU1));
    }

    @Test
    @DisplayName("R3⑫ 支付推进属系统动作：支付单 UPDATE_BY 记 0，不是订单主键")
    void paymentAuditOperatorIsSystemNotOrderId() {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        paySimService.pay(USER, order.getOrderNo());

        assertEquals(MallEnum.PayStatus.SUCCESS.getValue(), payStatusOf(order.getOrderNo()));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT UPDATE_BY FROM ws_mall_payment WHERE ORDER_NO=?", Long.class,
                order.getOrderNo()), "ORDER_ID 不是操作人");
    }

    // ==================== S3 前置仓履约与配送签收 ====================

    private com.jbk.tool.data.mall.vo.MallFulfillVo paidOrderWithTask(String... unusedNo) {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        paySimService.pay(USER, order.getOrderNo());
        assertEquals(MallEnum.OrderStatus.PAID.getValue(), orderStatusOf(order.getOrderNo()));
        return fulfillmentService.ensureTask(order.getOrderNo());
    }

    private int fulfillStatusOf(String orderNo) {
        return jdbc.queryForObject(
                "SELECT FULFILL_STATUS FROM ws_mall_fulfillment WHERE ORDER_NO=?", Integer.class,
                orderNo);
    }

    private long traceCountOf(String orderNo) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_mall_fulfillment_trace WHERE ORDER_NO=?", Long.class,
                orderNo);
    }

    /** 把任务推到「待分配」：拣货 + 打包。 */
    private String readyToAssign() {
        var task = paidOrderWithTask();
        var bo = new com.jbk.tool.data.mall.bo.MallFulfillActionBo().setOrderNo(task.getOrderNo());
        fulfillmentService.pick(WH_OPERATOR, bo);
        fulfillmentService.pack(WH_OPERATOR, bo);
        return task.getOrderNo();
    }

    /** 推到「已送达待签收」。 */
    private String readyToSign() {
        String orderNo = readyToAssign();
        var bo = new com.jbk.tool.data.mall.bo.MallFulfillActionBo().setOrderNo(orderNo);
        selfDeliveryService.assign(WH_OPERATOR, new com.jbk.tool.data.mall.bo.MallFulfillAssignBo()
                .setOrderNo(orderNo).setCourierId(COURIER_IN));
        selfDeliveryService.fetch(COURIER_USER, bo);
        selfDeliveryService.arrive(COURIER_USER, bo);
        return orderNo;
    }

    // ================= R1 P1-4：微信签名通知按语义核验重投 =================

    /** 造一笔已支付微信单，返回订单号（paySource=1 微信、带真实交易号与成功时间）。 */
    private String wechatPaidOrder() {
        String orderNo = paidOrderWithTask().getOrderNo();
        jdbc.update("UPDATE ws_mall_payment SET PAY_SOURCE=1, TRANSACTION_ID='4200000000000000009', "
                + "PAY_SUCCESS_TIME='20260813103456' WHERE ORDER_NO=?", orderNo);
        return orderNo;
    }

    /**
     * 微信重投同一通知会重新加密：同 id、同语义、密文与整个信封字节全变。
     * 按 RAW_BODY_SHA256 判同一性会把合法重投判成篡改并持续 500——微信重试三天后放弃，
     * 一笔已收的款永远进不了账。语义字段才是"同一事实"的定义。
     */
    @Test
    @DisplayName("R1 P1-4：微信通知同id同语义不同正文——复用原事实，一条事实不重复推进")
    void wechatNotifyReencryptedRetryReusesFact() {
        String orderNo = wechatPaidOrder();
        int wechat = MallEnum.PaySource.WECHAT.getValue();
        int notify = MallEnum.FactChannel.NOTIFY.getValue();
        int sig = MallEnum.VerifyMethod.WECHAT_SIGNATURE;

        var first = payFactService.recordFact(wechat, notify, "EV-MALL-R1", orderNo, "SUCCESS",
                "4200000000000000009", 5500L, "20260813103456", sig, "{cipher-attempt-1}");
        var retry = payFactService.recordFact(wechat, notify, "EV-MALL-R1", orderNo, "SUCCESS",
                "4200000000000000009", 5500L, "20260813103456", sig, "{cipher-attempt-2-DIFFERENT}");

        assertEquals(first.getId(), retry.getId(), "语义一致的重投必须复用原事实");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ws_mall_payment_fact "
                + "WHERE PROVIDER_EVENT_KEY='EV-MALL-R1'", Integer.class));
        // 首次落库的 RAW_BODY 不可变：重投正文不得覆盖已存档证据
        assertEquals("{cipher-attempt-1}", jdbc.queryForObject(
                "SELECT RAW_BODY FROM ws_mall_payment_fact WHERE PROVIDER_EVENT_KEY='EV-MALL-R1'",
                String.class), "重投正文覆盖了首份存档——事后无法用平台公钥复核原始证据");
    }

    @Test
    @DisplayName("R1 P1-4：同id改金额/订单号/交易号/成功时间——四路全部拒绝转人工")
    void wechatNotifySameKeyMutationsAreRefused() {
        String orderNo = wechatPaidOrder();
        int wechat = MallEnum.PaySource.WECHAT.getValue();
        int notify = MallEnum.FactChannel.NOTIFY.getValue();
        int sig = MallEnum.VerifyMethod.WECHAT_SIGNATURE;
        payFactService.recordFact(wechat, notify, "EV-MALL-R2", orderNo, "SUCCESS",
                "4200000000000000009", 5500L, "20260813103456", sig, "{cipher}");

        record Mutation(String label, String order, String tx, long fen, String time) {}
        java.util.List<Mutation> mutations = java.util.List.of(
                new Mutation("改金额", orderNo, "4200000000000000009", 9999L, "20260813103456"),
                new Mutation("改订单号", "MO000000000000000000000000FAKE1", "4200000000000000009", 5500L, "20260813103456"),
                new Mutation("改交易号", orderNo, "4200000000000000FAKE", 5500L, "20260813103456"),
                new Mutation("改成功时间", orderNo, "4200000000000000009", 5500L, "20260813999999"));
        for (Mutation m : mutations) {
            assertThrows(JbkException.class, () -> payFactService.recordFact(
                            wechat, notify, "EV-MALL-R2", m.order(), "SUCCESS",
                            m.tx(), m.fen(), m.time(), sig, "{whatever}"),
                    m.label() + "：同键异语义必须拒绝——静默复用等于替篡改盖章");
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ws_mall_payment_fact "
                + "WHERE PROVIDER_EVENT_KEY='EV-MALL-R2'", Integer.class), "拒绝路径不得新增事实");
    }

    @Test
    @DisplayName("WX-ECO S4：取货即登记发货同步；Pay-Sim 单落终态 SKIP，绝不进微信外呼循环")
    void fetchEnqueuesShippingSyncWithPaySimSkipped() {
        String orderNo = readyToAssign();
        var bo = new com.jbk.tool.data.mall.bo.MallFulfillActionBo().setOrderNo(orderNo);
        selfDeliveryService.assign(WH_OPERATOR, new com.jbk.tool.data.mall.bo.MallFulfillAssignBo()
                .setOrderNo(orderNo).setCourierId(COURIER_IN));
        selfDeliveryService.fetch(COURIER_USER, bo);

        // 挂点必须在取货事务里真的登记了一行；本套件订单全是 Pay-Sim，
        // 分流判据要求它是「已处理+NOT_WECHAT_PAY」——留痕可审计但零外呼
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_wechat_shipping_outbox WHERE ORDER_NO = ?",
                Integer.class, orderNo), "取货没登记发货同步——微信收款单上线后资金不结算");
        assertEquals("NOT_WECHAT_PAY", jdbc.queryForObject(
                "SELECT SKIP_REASON FROM ws_wechat_shipping_outbox WHERE ORDER_NO = ?",
                String.class, orderNo), "Pay-Sim 单没落 SKIP——模拟单会进真实微信发货台账");
    }

    @Test
    @DisplayName("S3① 已支付订单生成且只生成一个履约任务，重复触发返回原任务")
    void paidOrderGetsExactlyOneFulfillTask() {
        var task = paidOrderWithTask();
        assertEquals(MallEnum.FulfillStatus.PENDING_PICK.getValue(), task.getFulfillStatus());
        assertEquals(1L, countOf("ws_mall_fulfillment"));

        var again = fulfillmentService.ensureTask(task.getOrderNo());
        assertEquals(task.getOrderNo(), again.getOrderNo());
        assertEquals(1L, countOf("ws_mall_fulfillment"), "重复触发不得生成第二个任务");
        assertEquals(1L, traceCountOf(task.getOrderNo()), "轨迹同样不得重复写");
        // Worker 再扫也不会重复生成
        assertEquals(0, fulfillDispatchWorker.runOnce(), "已有任务的订单不再进入扫描面");
        assertEquals(1L, countOf("ws_mall_fulfillment"));
    }

    @Test
    @DisplayName("S3② 未支付或已取消的订单一律不生成履约任务")
    void onlyPaidOrderCanStartFulfillment() {
        MallOrderVo pending = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 1));
        assertThrows(JbkException.class, () -> fulfillmentService.ensureTask(pending.getOrderNo()));

        orderService.cancelByUser(USER, new MallOrderActionBo().setOrderNo(pending.getOrderNo()));
        assertThrows(JbkException.class, () -> fulfillmentService.ensureTask(pending.getOrderNo()));
        assertEquals(0L, countOf("ws_mall_fulfillment"));
        assertEquals(0L, countOf("ws_mall_fulfillment_trace"));

        assertThrows(JbkException.class, () -> fulfillmentService.ensureTask("MO-NOT-EXIST"));
        assertEquals(0L, countOf("ws_mall_fulfillment"));
    }

    @Test
    @DisplayName("S3③ 拣货把订单推进到履约中；重复拣货被拒且不重复写轨迹")
    void pickAdvancesOrderAndIsNotRepeatable() {
        var task = paidOrderWithTask();
        var bo = new com.jbk.tool.data.mall.bo.MallFulfillActionBo().setOrderNo(task.getOrderNo());
        fulfillmentService.pick(WH_OPERATOR, bo);

        assertEquals(MallEnum.FulfillStatus.PENDING_PACK.getValue(),
                fulfillStatusOf(task.getOrderNo()));
        assertEquals(MallEnum.OrderStatus.FULFILLING.getValue(),
                orderStatusOf(task.getOrderNo()), "履约开始，订单转 3");
        long traces = traceCountOf(task.getOrderNo());

        assertThrows(JbkException.class, () -> fulfillmentService.pick(WH_OPERATOR, bo), "重复拣货必须被拒");
        assertEquals(MallEnum.FulfillStatus.PENDING_PACK.getValue(),
                fulfillStatusOf(task.getOrderNo()));
        assertEquals(traces, traceCountOf(task.getOrderNo()), "被拒后不得追加轨迹");
        assertEquals(MallEnum.OrderStatus.FULFILLING.getValue(), orderStatusOf(task.getOrderNo()));
    }

    @Test
    @DisplayName("S3④ 分配四类非法配送员全部被拒，且拒绝路径零副作用")
    void illegalCourierAssignmentIsRefusedWithZeroSideEffect() {
        String orderNo = readyToAssign();
        long traces = traceCountOf(orderNo);
        long messages = countOf("ws_message");

        for (long bad : new long[]{COURIER_OUT, COURIER_DISABLED, COURIER_SELF, 999999L}) {
            assertThrows(JbkException.class, () -> selfDeliveryService.assign(WH_OPERATOR,
                    new com.jbk.tool.data.mall.bo.MallFulfillAssignBo()
                            .setOrderNo(orderNo).setCourierId(bad)),
                    "配送员 " + bad + " 不该被允许");
        }
        assertEquals(MallEnum.FulfillStatus.PENDING_ASSIGN.getValue(), fulfillStatusOf(orderNo));
        assertNull(jdbc.queryForObject(
                "SELECT COURIER_ID FROM ws_mall_fulfillment WHERE ORDER_NO=?", Long.class, orderNo));
        assertEquals(traces, traceCountOf(orderNo), "拒绝不得写轨迹");
        assertEquals(messages, countOf("ws_message"), "拒绝不得发消息");

        // 候选列表里也不该出现这三类
        var candidates = selfDeliveryService.courierCandidates(WH_OPERATOR, orderNo);
        assertEquals(1, candidates.size());
        assertEquals(Long.valueOf(COURIER_IN), candidates.get(0).getCourierId());

        // 合法配送员照常分配
        selfDeliveryService.assign(WH_OPERATOR, new com.jbk.tool.data.mall.bo.MallFulfillAssignBo()
                .setOrderNo(orderNo).setCourierId(COURIER_IN));
        assertEquals(MallEnum.FulfillStatus.PENDING_FETCH.getValue(), fulfillStatusOf(orderNo));
        assertEquals(messages + 1, countOf("ws_message"), "分配成功才发一条消息");
    }

    @Test
    @DisplayName("S3⑤ 并发分配只有一个成功，配送员不被覆盖")
    void concurrentAssignHasSingleWinner() throws Exception {
        String orderNo = readyToAssign();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        try {
            var f1 = pool.submit(() -> {
                go.await();
                try {
                    selfDeliveryService.assign(WH_OPERATOR,
                            new com.jbk.tool.data.mall.bo.MallFulfillAssignBo()
                                    .setOrderNo(orderNo).setCourierId(COURIER_IN));
                    ok.incrementAndGet();
                }
                catch (JbkException ignored) {
                    // 输方
                }
                return null;
            });
            var f2 = pool.submit(() -> {
                go.await();
                try {
                    selfDeliveryService.assign(WH_OPERATOR2,
                            new com.jbk.tool.data.mall.bo.MallFulfillAssignBo()
                                    .setOrderNo(orderNo).setCourierId(COURIER_IN));
                    ok.incrementAndGet();
                }
                catch (JbkException ignored) {
                    // 输方
                }
                return null;
            });
            go.countDown();
            f1.get();
            f2.get();
        }
        finally {
            pool.shutdownNow();
        }
        assertEquals(1, ok.get(), "两个操作员同时分配只能有一个成功");
        assertEquals(MallEnum.FulfillStatus.PENDING_FETCH.getValue(), fulfillStatusOf(orderNo));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_mall_fulfillment_trace WHERE ORDER_NO=? AND TRACE_NODE=?",
                Long.class, orderNo, MallEnum.FulfillStatus.PENDING_FETCH.getValue()),
                "分配节点轨迹恰一条");
        // R2⑤：任务 COURIER_ID 与分配轨迹 SUBJECT_ID 精确一致，输家不留任何痕迹
        assertEquals(jdbc.queryForObject(
                "SELECT COURIER_ID FROM ws_mall_fulfillment WHERE ORDER_NO=?", Long.class, orderNo),
                jdbc.queryForObject("SELECT SUBJECT_ID FROM ws_mall_fulfillment_trace"
                        + " WHERE ORDER_NO=? AND TRACE_NODE=?", Long.class, orderNo,
                        MallEnum.FulfillStatus.PENDING_FETCH.getValue()));
        assertEquals(1L, countOf("ws_message"), "只有赢家发消息");
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM ws_domain_event"
                + " WHERE BIZ_IDEMPOTENCY_KEY=?", Long.class,
                MallFulfillmentServiceImpl.AUDIT_KEY_PREFIX + orderNo + ":"
                        + MallEnum.FulfillStatus.PENDING_FETCH.getValue()),
                "只有赢家留审计");
    }

    @Test
    @DisplayName("S3⑥ 配送员只能操作与查看分配给本人的任务")
    void courierCanOnlyTouchOwnTask() {
        String orderNo = readyToAssign();
        selfDeliveryService.assign(WH_OPERATOR, new com.jbk.tool.data.mall.bo.MallFulfillAssignBo()
                .setOrderNo(orderNo).setCourierId(COURIER_IN));
        var bo = new com.jbk.tool.data.mall.bo.MallFulfillActionBo().setOrderNo(orderNo);

        assertThrows(JbkException.class, () -> selfDeliveryService.fetch(COURIER_OUT_USER, bo),
                "他人任务不得推进");
        assertThrows(JbkException.class,
                () -> selfDeliveryService.detailForCourier(COURIER_OUT_USER, orderNo),
                "他人任务连详情都不给，收货地址与电话不外泄");
        assertEquals(0, selfDeliveryService.listForCourier(COURIER_OUT_USER).size());
        assertEquals(MallEnum.FulfillStatus.PENDING_FETCH.getValue(), fulfillStatusOf(orderNo));

        assertEquals(1, selfDeliveryService.listForCourier(COURIER_USER).size());
        selfDeliveryService.fetch(COURIER_USER, bo);
        assertEquals(MallEnum.FulfillStatus.DELIVERING.getValue(), fulfillStatusOf(orderNo));
    }

    @Test
    @DisplayName("S3⑦ 状态顺序不可跳：未取货不得送达，未送达不得签收")
    void statusMustAdvanceInOrder() {
        String orderNo = readyToAssign();
        selfDeliveryService.assign(WH_OPERATOR, new com.jbk.tool.data.mall.bo.MallFulfillAssignBo()
                .setOrderNo(orderNo).setCourierId(COURIER_IN));
        var bo = new com.jbk.tool.data.mall.bo.MallFulfillActionBo().setOrderNo(orderNo);

        assertThrows(JbkException.class, () -> selfDeliveryService.arrive(COURIER_USER, bo),
                "还没取货就说送达");
        assertThrows(JbkException.class, () -> fulfillmentService.signByUser(USER,
                new com.jbk.tool.data.mall.bo.MallFulfillSignBo().setOrderNo(orderNo)
                        .setSignMethod(MallEnum.SignMethod.SELF.getValue())),
                "还没送达就签收");
        assertEquals(MallEnum.FulfillStatus.PENDING_FETCH.getValue(), fulfillStatusOf(orderNo));
        assertEquals(MallEnum.OrderStatus.FULFILLING.getValue(), orderStatusOf(orderNo));
    }

    @Test
    @DisplayName("S3⑧ 用户只能签收本人订单；未知签收方式一律拒绝")
    void onlyOwnerCanSignAndMethodIsWhitelisted() {
        String orderNo = readyToSign();
        assertThrows(JbkException.class, () -> fulfillmentService.signByUser(OTHER_USER,
                new com.jbk.tool.data.mall.bo.MallFulfillSignBo().setOrderNo(orderNo)
                        .setSignMethod(MallEnum.SignMethod.SELF.getValue())),
                "他人订单不得签收");
        assertThrows(JbkException.class, () -> fulfillmentService.signByUser(USER,
                new com.jbk.tool.data.mall.bo.MallFulfillSignBo().setOrderNo(orderNo)
                        .setSignMethod(99)),
                "未知签收方式不得默认成本人签收");
        assertEquals(MallEnum.FulfillStatus.ARRIVED.getValue(), fulfillStatusOf(orderNo));
        assertEquals(MallEnum.OrderStatus.FULFILLING.getValue(), orderStatusOf(orderNo));
    }

    @Test
    @DisplayName("S3⑨ 签收把订单精确推到已完成，且任务/订单/轨迹/消息五个时间同源")
    void signCompletesOrderWithOneSharedTimestamp() {
        String orderNo = readyToSign();
        fulfillmentService.signByUser(USER, new com.jbk.tool.data.mall.bo.MallFulfillSignBo()
                .setOrderNo(orderNo).setSignMethod(MallEnum.SignMethod.SELF.getValue())
                .setSignRemark("已当面签收"));

        assertEquals(MallEnum.FulfillStatus.SIGNED.getValue(), fulfillStatusOf(orderNo));
        assertEquals(MallEnum.OrderStatus.COMPLETED.getValue(), orderStatusOf(orderNo));

        String signTime = jdbc.queryForObject(
                "SELECT SIGN_TIME FROM ws_mall_fulfillment WHERE ORDER_NO=?", String.class, orderNo);
        assertNotNull(signTime);
        assertEquals(signTime, jdbc.queryForObject(
                "SELECT UPDATE_TIME FROM ws_mall_order WHERE ORDER_NO=?", String.class, orderNo),
                "订单完成时间必须与签收同源");
        assertEquals(signTime, jdbc.queryForObject(
                "SELECT TRACE_TIME FROM ws_mall_fulfillment_trace WHERE ORDER_NO=? AND TRACE_NODE=?",
                String.class, orderNo, MallEnum.FulfillStatus.SIGNED.getValue()));
        // 按标题定位签收那条：取"最后一条"会在签收根本没发消息时回落到"已送达"那条而假通过
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_message WHERE OBJECT_ID=? AND MSG_TITLE=?",
                Long.class, orderNo, "商城订单已完成"), "签收必须发且只发一条完成消息");
        assertEquals(signTime, jdbc.queryForObject(
                "SELECT SEND_TIME FROM ws_message WHERE OBJECT_ID=? AND MSG_TITLE=?",
                String.class, orderNo, "商城订单已完成"));
        assertEquals(signTime, jdbc.queryForObject(
                "SELECT UPDATE_TIME FROM ws_mall_fulfillment WHERE ORDER_NO=?", String.class,
                orderNo), "任务自身的更新时间同源");
        assertEquals(signTime, jdbc.queryForObject(
                "SELECT CREATE_TIME FROM ws_mall_fulfillment_trace WHERE ORDER_NO=? AND TRACE_NODE=?",
                String.class, orderNo, MallEnum.FulfillStatus.SIGNED.getValue()));
        assertEquals("已当面签收", jdbc.queryForObject(
                "SELECT SIGN_REMARK FROM ws_mall_fulfillment WHERE ORDER_NO=?", String.class,
                orderNo));
        // 三端同一个订单号核对完整时间线
        var detail = fulfillmentService.detailForManage(WH_OPERATOR, orderNo);
        assertEquals(orderNo, detail.getOrderNo());
        assertEquals(7, detail.getTimeline().size(), "七个节点各一条");
        assertEquals(orderNo, fulfillmentService.detailForUser(USER, orderNo).getOrderNo());
    }

    @Test
    @DisplayName("S3⑩ 重复签收不重复完成订单、不重复发消息")
    void repeatedSignIsRefused() {
        String orderNo = readyToSign();
        var signBo = new com.jbk.tool.data.mall.bo.MallFulfillSignBo().setOrderNo(orderNo)
                .setSignMethod(MallEnum.SignMethod.SELF.getValue());
        fulfillmentService.signByUser(USER, signBo);
        long messages = countOf("ws_message");
        long traces = traceCountOf(orderNo);

        assertThrows(JbkException.class, () -> fulfillmentService.signByUser(USER, signBo));
        assertEquals(MallEnum.OrderStatus.COMPLETED.getValue(), orderStatusOf(orderNo));
        assertEquals(messages, countOf("ws_message"), "不得重复发消息");
        assertEquals(traces, traceCountOf(orderNo), "不得重复写轨迹");
    }

    @Test
    @DisplayName("S3⑪ 履约全程零库存动作：与支付实销后的基线逐列相同")
    void fulfillmentNeverTouchesStock() {
        // 基线必须取在支付实销之后、履约开始之前：取在 readyToSign 之后的话，
        // 生成/拣货/打包/分配/取货/送达六步的库存改动都会被基线一起吸收，"全程"名不副实
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        paySimService.pay(USER, order.getOrderNo());
        long availableAfterSell = availableOf(WH1, SKU1);
        long reservedAfterSell = reservedOf(WH1, SKU1);
        long flows = countOf("ws_mall_stock_flow");

        String orderNo = order.getOrderNo();
        var bo = new com.jbk.tool.data.mall.bo.MallFulfillActionBo().setOrderNo(orderNo);
        fulfillmentService.ensureTask(orderNo);
        fulfillmentService.pick(WH_OPERATOR, bo);
        fulfillmentService.pack(WH_OPERATOR, bo);
        selfDeliveryService.assign(WH_OPERATOR, new com.jbk.tool.data.mall.bo.MallFulfillAssignBo()
                .setOrderNo(orderNo).setCourierId(COURIER_IN));
        selfDeliveryService.fetch(COURIER_USER, bo);
        selfDeliveryService.arrive(COURIER_USER, bo);
        fulfillmentService.signByUser(USER, new com.jbk.tool.data.mall.bo.MallFulfillSignBo()
                .setOrderNo(orderNo).setSignMethod(MallEnum.SignMethod.SELF.getValue()));

        assertEquals(availableAfterSell, availableOf(WH1, SKU1));
        assertEquals(reservedAfterSell, reservedOf(WH1, SKU1));
        assertEquals(flows, countOf("ws_mall_stock_flow"), "S3 不得新增任何库存流水");
        assertEquals(0L, flowCountOfType(MallEnum.StockFlowType.ORDER_RELEASE.getValue()));
        assertEquals(0L, flowCountOfType(MallEnum.StockFlowType.RETURN_RESTOCK.getValue()),
                "回库属 S4，S3 不得提前发生");
        assertEquals(1L, flowCountOfType(MallEnum.StockFlowType.ORDER_SELL.getValue()));
    }

    @Test
    @DisplayName("S3⑬ 跨仓操作员一律被拒：拣货/打包/分配/候选/详情五处同闸")
    void crossWarehouseOperatorIsRefusedEverywhere() {
        var task = paidOrderWithTask();
        String orderNo = task.getOrderNo();
        var bo = new com.jbk.tool.data.mall.bo.MallFulfillActionBo().setOrderNo(orderNo);

        assertThrows(JbkException.class, () -> fulfillmentService.pick(OTHER_WH_OPERATOR, bo));
        assertThrows(JbkException.class, () -> fulfillmentService.pack(OTHER_WH_OPERATOR, bo));
        assertThrows(JbkException.class, () -> selfDeliveryService.assign(OTHER_WH_OPERATOR,
                new com.jbk.tool.data.mall.bo.MallFulfillAssignBo()
                        .setOrderNo(orderNo).setCourierId(COURIER_IN)));
        assertThrows(JbkException.class,
                () -> selfDeliveryService.courierCandidates(OTHER_WH_OPERATOR, orderNo));
        assertThrows(JbkException.class,
                () -> fulfillmentService.detailForManage(OTHER_WH_OPERATOR, orderNo),
                "跨仓连详情都不给，收货姓名与详址不外泄");
        assertThrows(JbkException.class, () -> fulfillmentService.pick(null, bo),
                "无操作员身份同样拒绝");

        assertEquals(MallEnum.FulfillStatus.PENDING_PICK.getValue(), fulfillStatusOf(orderNo));
        assertEquals(MallEnum.OrderStatus.PAID.getValue(), orderStatusOf(orderNo));
        assertEquals(1L, traceCountOf(orderNo), "拒绝不得追加轨迹");
        assertEquals(0L, countOf("ws_message"), "拒绝不得发消息");

        // 归属本仓的操作员照常可以推进
        fulfillmentService.pick(WH_OPERATOR, bo);
        assertEquals(MallEnum.FulfillStatus.PENDING_PACK.getValue(), fulfillStatusOf(orderNo));
    }

    @Test
    @DisplayName("S3⑫ 生成 Worker 幂等：连跑两轮仍只有一个任务")
    void dispatchWorkerIsIdempotent() {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        paySimService.pay(USER, order.getOrderNo());
        assertEquals(0L, countOf("ws_mall_fulfillment"), "前置：支付事务不建履约任务");

        assertEquals(1, fulfillDispatchWorker.runOnce());
        assertEquals(0, fulfillDispatchWorker.runOnce(), "第二轮扫不到");
        assertEquals(1L, countOf("ws_mall_fulfillment"));
        assertEquals(MallEnum.FulfillStatus.PENDING_PICK.getValue(),
                fulfillStatusOf(order.getOrderNo()));
    }

    // ==================== S3 R1 对抗用例 ====================

    private long auditCountOf(String orderNo) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ws_domain_event WHERE BIZ_IDEMPOTENCY_KEY"
                + " LIKE ?", Long.class,
                MallFulfillmentServiceImpl.AUDIT_KEY_PREFIX + orderNo + ":%");
    }

    private long liveTraceCountOf(String orderNo) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ws_mall_fulfillment_trace"
                + " WHERE ORDER_NO=? AND DATA_STATUS=0", Long.class, orderNo);
    }

    @Test
    @DisplayName("R1① 收货快照被改写：动作与三端详情全部拒绝，状态/轨迹/消息/审计零变化")
    void tamperedReceiverSnapshotBlocksEverything() {
        var task = paidOrderWithTask();
        String orderNo = task.getOrderNo();
        var bo = new com.jbk.tool.data.mall.bo.MallFulfillActionBo().setOrderNo(orderNo);
        long traces = liveTraceCountOf(orderNo);
        long audits = auditCountOf(orderNo);

        for (String col : new String[]{"RECEIVER_ADDRESS", "RECEIVER_PHONE",
                "RECEIVER_DISTRICT_CODE", "RECEIVER_NAME", "RECEIVER_REGION"}) {
            String origin = jdbc.queryForObject(
                    "SELECT " + col + " FROM ws_mall_fulfillment WHERE ORDER_NO=?", String.class,
                    orderNo);
            // 各列长度不同（电话 11 位、区县码 6 位），改写值必须仍能写进列里，
            // 否则测的是列长约束而不是关联校验器
            String tampered = switch (col) {
                case "RECEIVER_PHONE" -> "13900000000";
                case "RECEIVER_DISTRICT_CODE" -> "420199";
                default -> "被改写的" + col;
            };
            jdbc.update("UPDATE ws_mall_fulfillment SET " + col + "=? WHERE ORDER_NO=?",
                    tampered, orderNo);

            assertThrows(JbkException.class, () -> fulfillmentService.pick(WH_OPERATOR, bo),
                    col + " 被改写后仍能拣货");
            assertThrows(JbkException.class,
                    () -> fulfillmentService.detailForManage(WH_OPERATOR, orderNo));
            assertThrows(JbkException.class, () -> fulfillmentService.detailForUser(USER, orderNo));
            assertThrows(JbkException.class,
                    () -> selfDeliveryService.courierCandidates(WH_OPERATOR, orderNo));
            assertThrows(JbkException.class, () -> fulfillmentService.ensureTask(orderNo),
                    "错位任务不得被当成幂等成功返回");

            jdbc.update("UPDATE ws_mall_fulfillment SET " + col + "=? WHERE ORDER_NO=?",
                    origin, orderNo);
        }
        assertEquals(MallEnum.FulfillStatus.PENDING_PICK.getValue(), fulfillStatusOf(orderNo));
        assertEquals(MallEnum.OrderStatus.PAID.getValue(), orderStatusOf(orderNo));
        assertEquals(traces, liveTraceCountOf(orderNo));
        assertEquals(audits, auditCountOf(orderNo));
        assertEquals(0L, countOf("ws_message"));
        // 复原后照常可用
        fulfillmentService.pick(WH_OPERATOR, bo);
        assertEquals(MallEnum.FulfillStatus.PENDING_PACK.getValue(), fulfillStatusOf(orderNo));
    }

    @Test
    @DisplayName("R1② 任务共键被改写：错误主体读不到收货信息")
    void tamperedTaskKeysBlockWrongSubjects() {
        String orderNo = readyToAssign();
        selfDeliveryService.assign(WH_OPERATOR, new com.jbk.tool.data.mall.bo.MallFulfillAssignBo()
                .setOrderNo(orderNo).setCourierId(COURIER_IN));

        jdbc.update("UPDATE ws_mall_fulfillment SET USER_ID=? WHERE ORDER_NO=?",
                OTHER_USER, orderNo);
        assertThrows(JbkException.class, () -> fulfillmentService.detailForUser(OTHER_USER, orderNo),
                "改 userId 后新用户不得读到原订单地址");
        jdbc.update("UPDATE ws_mall_fulfillment SET USER_ID=? WHERE ORDER_NO=?", USER, orderNo);

        // COURIER_ID 被改指向另一个配送员时 A 与 B 都必须拒绝，完整断言见 R2①
        assertEquals(orderNo, selfDeliveryService.detailForCourier(COURIER_USER, orderNo)
                .getOrderNo(), "前置：本人可读");
        jdbc.update("UPDATE ws_mall_fulfillment SET COURIER_ID=? WHERE ORDER_NO=?",
                COURIER_OUT, orderNo);
        assertThrows(JbkException.class,
                () -> selfDeliveryService.detailForCourier(COURIER_USER, orderNo),
                "改 courierId 后原配送员必须立刻读不到");
        assertThrows(JbkException.class,
                () -> selfDeliveryService.detailForCourier(COURIER_OUT_USER, orderNo),
                "被改指向的配送员同样不得接管");
        jdbc.update("UPDATE ws_mall_fulfillment SET COURIER_ID=? WHERE ORDER_NO=?",
                COURIER_IN, orderNo);

        jdbc.update("UPDATE ws_mall_fulfillment SET WAREHOUSE_ID=? WHERE ORDER_NO=?", WH2, orderNo);
        assertThrows(JbkException.class,
                () -> fulfillmentService.detailForManage(OTHER_WH_OPERATOR, orderNo),
                "改 warehouseId 后 WH2 操作员仍不得读到（订单侧仓库未变，共键不一致）");
        assertThrows(JbkException.class,
                () -> fulfillmentService.detailForManage(WH_OPERATOR, orderNo));
    }

    @Test
    @DisplayName("R1③ 轨迹键被占用一律 fail-closed：伪造/逻辑删除/错位三种都拒绝且零副作用")
    void occupiedTraceKeyFailsClosed() {
        for (int variant = 0; variant < 3; variant++) {
            var task = paidOrderWithTask();
            String orderNo = task.getOrderNo();
            var bo = new com.jbk.tool.data.mall.bo.MallFulfillActionBo().setOrderNo(orderNo);
            String key = MallFulfillmentServiceImpl.TRACE_KEY_PREFIX + orderNo + ":"
                    + MallEnum.FulfillStatus.PENDING_PACK.getValue();
            int dataStatus = variant == 1 ? 1 : 0;
            long otherFulfillId = variant == 2 ? 999999L : 1L;
            String traceOrderNo = variant == 2 ? "MO-OTHER-ORDER" : orderNo;
            jdbc.update("INSERT INTO ws_mall_fulfillment_trace (DATA_STATUS, CREATE_BY, CREATE_TIME,"
                    + " UPDATE_BY, UPDATE_TIME, FULFILL_ID, ORDER_NO, TRACE_NODE, ACTOR_TYPE,"
                    + " ACTOR_ID, TRACE_TIME, TRACE_TEXT, BIZ_IDEMPOTENCY_KEY)"
                    + " VALUES (?,1,'20260809120000',1,'20260809120000',?,?,?,2,1,"
                    + "'20260809120000','伪造节点',?)",
                    dataStatus, otherFulfillId, traceOrderNo,
                    MallEnum.FulfillStatus.PENDING_PACK.getValue(), key);
            long audits = auditCountOf(orderNo);
            long messages = countOf("ws_message");

            assertThrows(JbkException.class, () -> fulfillmentService.pick(WH_OPERATOR, bo),
                    "variant " + variant + " 未被拒绝");

            assertEquals(MallEnum.FulfillStatus.PENDING_PICK.getValue(), fulfillStatusOf(orderNo));
            assertEquals(MallEnum.OrderStatus.PAID.getValue(), orderStatusOf(orderNo),
                    "订单必须随轨迹冲突一起回滚");
            assertEquals(audits, auditCountOf(orderNo), "拒绝不得追加审计");
            assertEquals(messages, countOf("ws_message"), "拒绝不得发消息");
            MallDbSchema.truncateAll(jdbc);
            seedCatalog();
            seedAddresses();
        }
    }

    @Test
    @DisplayName("R1④ 七个履约节点各一条可靠审计，且身份端口逐节点正确")
    void everyFulfillNodeHasReliableAuditWithExplicitPortal() {
        String orderNo = readyToSign();
        fulfillmentService.signByUser(USER, new com.jbk.tool.data.mall.bo.MallFulfillSignBo()
                .setOrderNo(orderNo).setSignMethod(MallEnum.SignMethod.SELF.getValue()));

        assertEquals(7L, auditCountOf(orderNo), "生成+六个推进节点各一条");
        for (Object[] expect : new Object[][]{
                {MallEnum.FulfillStatus.PENDING_PICK, OpsEnum.ActorPortal.SYSTEM},
                {MallEnum.FulfillStatus.PENDING_PACK, OpsEnum.ActorPortal.MANAGE},
                {MallEnum.FulfillStatus.PENDING_ASSIGN, OpsEnum.ActorPortal.MANAGE},
                {MallEnum.FulfillStatus.PENDING_FETCH, OpsEnum.ActorPortal.MANAGE},
                {MallEnum.FulfillStatus.DELIVERING, OpsEnum.ActorPortal.COURIER},
                {MallEnum.FulfillStatus.ARRIVED, OpsEnum.ActorPortal.COURIER},
                {MallEnum.FulfillStatus.SIGNED, OpsEnum.ActorPortal.USER}}) {
            var node = (MallEnum.FulfillStatus) expect[0];
            var portal = (OpsEnum.ActorPortal) expect[1];
            assertEquals(portal.getValue(), (int) jdbc.queryForObject(
                    "SELECT ACTOR_PORTAL FROM ws_domain_event WHERE BIZ_IDEMPOTENCY_KEY=?",
                    Integer.class, MallFulfillmentServiceImpl.AUDIT_KEY_PREFIX + orderNo + ":"
                            + node.getValue()),
                    node.getDesc() + " 的身份端口不对——配送员与用户共用会话，不显式声明就会被记成用户");
        }
    }

    @Test
    @DisplayName("R1⑤ 审计写入失败：任务、订单、轨迹、消息全部零变化")
    void auditFailureRollsBackEverything() {
        var task = paidOrderWithTask();
        String orderNo = task.getOrderNo();
        // 预置同键但内容不同的事件：recordReliableOnceAs 撞键读回核验必然拒绝
        jdbc.update("INSERT INTO ws_domain_event (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY,"
                + " UPDATE_TIME, EVENT_TYPE, EVENT_KEY, EVENT_PAYLOAD, ACTOR_ID, ACTOR_PORTAL,"
                + " ACTOR_ROLE, WHITELIST_FLAG, CONSUMED_FLAG, BIZ_IDEMPOTENCY_KEY)"
                + " VALUES (0,1,'20260809120000',1,'20260809120000',11,'OTHER-KEY','{}',1,1,"
                + "'x',0,0,?)",
                MallFulfillmentServiceImpl.AUDIT_KEY_PREFIX + orderNo + ":"
                        + MallEnum.FulfillStatus.PENDING_PACK.getValue());
        long traces = liveTraceCountOf(orderNo);

        assertThrows(RuntimeException.class, () -> fulfillmentService.pick(WH_OPERATOR,
                new com.jbk.tool.data.mall.bo.MallFulfillActionBo().setOrderNo(orderNo)));

        assertEquals(MallEnum.FulfillStatus.PENDING_PICK.getValue(), fulfillStatusOf(orderNo));
        assertEquals(MallEnum.OrderStatus.PAID.getValue(), orderStatusOf(orderNo));
        assertEquals(traces, liveTraceCountOf(orderNo), "审计失败必须让轨迹一起回滚");
        assertEquals(0L, countOf("ws_message"));
    }

    @Test
    @DisplayName("R1⑥ 消息写入失败：任务、订单、轨迹、审计全部零变化")
    void messageFailureRollsBackEverything() {
        String orderNo = readyToSign();
        long traces = liveTraceCountOf(orderNo);
        long audits = auditCountOf(orderNo);
        long messages = countOf("ws_message");

        FAIL_MESSAGE = true;
        try {
            assertThrows(RuntimeException.class, () -> fulfillmentService.signByUser(USER,
                    new com.jbk.tool.data.mall.bo.MallFulfillSignBo().setOrderNo(orderNo)
                            .setSignMethod(MallEnum.SignMethod.SELF.getValue())));
        }
        finally {
            FAIL_MESSAGE = false;
        }

        assertEquals(MallEnum.FulfillStatus.ARRIVED.getValue(), fulfillStatusOf(orderNo));
        assertEquals(MallEnum.OrderStatus.FULFILLING.getValue(), orderStatusOf(orderNo),
                "消息失败必须让订单完成一起回滚");
        assertEquals(traces, liveTraceCountOf(orderNo));
        assertEquals(audits, auditCountOf(orderNo));
        assertEquals(messages, countOf("ws_message"));
    }

    // ==================== S3-A R2 配送归属结构化证据 ====================

    /** 分配到 A 并返回订单号（任务停在待取货）。 */
    private String assignedToCourierIn() {
        String orderNo = readyToAssign();
        selfDeliveryService.assign(WH_OPERATOR, new com.jbk.tool.data.mall.bo.MallFulfillAssignBo()
                .setOrderNo(orderNo).setCourierId(COURIER_IN));
        return orderNo;
    }

    /** 断言 A 与 B 的四个配送端出口全部拒绝，且业务零副作用。 */
    private void assertBothCouriersBlocked(String orderNo, long traces, long messages,
                                           long audits, int fulfillStatus) {
        var bo = new com.jbk.tool.data.mall.bo.MallFulfillActionBo().setOrderNo(orderNo);
        for (long user : new long[]{COURIER_USER, COURIER_OUT_USER}) {
            assertThrows(JbkException.class,
                    () -> selfDeliveryService.detailForCourier(user, orderNo),
                    "配送员 " + user + " 不该读到详情");
            // 列表两种结果都算合格：整份拒绝，或返回但不含这一单——关键是不得泄露它
            try {
                assertTrue(selfDeliveryService.listForCourier(user).stream()
                                .noneMatch(v -> orderNo.equals(v.getOrderNo())),
                        "配送员 " + user + " 的列表里不得出现这一单");
            }
            catch (JbkException refusedWholeList) {
                // 证据异常时直接拒绝整份列表同样合格
            }
            assertThrows(JbkException.class, () -> selfDeliveryService.fetch(user, bo));
            assertThrows(JbkException.class, () -> selfDeliveryService.arrive(user, bo));
        }
        assertEquals(fulfillStatus, fulfillStatusOf(orderNo));
        assertEquals(MallEnum.OrderStatus.FULFILLING.getValue(), orderStatusOf(orderNo));
        assertEquals(traces, liveTraceCountOf(orderNo), "证据异常不得追加轨迹");
        assertEquals(messages, countOf("ws_message"), "证据异常不得发消息");
        assertEquals(audits, auditCountOf(orderNo), "证据异常不得记审计");
    }

    @Test
    @DisplayName("R2① COURIER_ID 被改指向其他配送员：原配送员与被改指向者双双拒绝")
    void tamperedCourierIdBlocksBothCouriers() {
        String orderNo = assignedToCourierIn();
        long traces = liveTraceCountOf(orderNo);
        long messages = countOf("ws_message");
        long audits = auditCountOf(orderNo);

        // COURIER_OUT 已启用，只是没绑 WH1——若只比当前 COURIER_ID，它就能凭这一改接管
        jdbc.update("UPDATE ws_mall_fulfillment SET COURIER_ID=? WHERE ORDER_NO=?",
                COURIER_OUT, orderNo);
        assertBothCouriersBlocked(orderNo, traces, messages, audits,
                MallEnum.FulfillStatus.PENDING_FETCH.getValue());

        // 复原后 A 照常履约
        jdbc.update("UPDATE ws_mall_fulfillment SET COURIER_ID=? WHERE ORDER_NO=?",
                COURIER_IN, orderNo);
        var bo = new com.jbk.tool.data.mall.bo.MallFulfillActionBo().setOrderNo(orderNo);
        assertEquals(orderNo, selfDeliveryService.detailForCourier(COURIER_USER, orderNo)
                .getOrderNo());
        selfDeliveryService.fetch(COURIER_USER, bo);
        selfDeliveryService.arrive(COURIER_USER, bo);
        assertEquals(MallEnum.FulfillStatus.ARRIVED.getValue(), fulfillStatusOf(orderNo));
    }

    @Test
    @DisplayName("R2② 分配轨迹 SUBJECT_ID 被改位：两个配送员都读不到也推不动")
    void tamperedSubjectIdBlocksBothCouriers() {
        String orderNo = assignedToCourierIn();
        jdbc.update("UPDATE ws_mall_fulfillment_trace SET SUBJECT_ID=?"
                + " WHERE ORDER_NO=? AND TRACE_NODE=?", COURIER_OUT, orderNo,
                MallEnum.FulfillStatus.PENDING_FETCH.getValue());
        assertBothCouriersBlocked(orderNo, liveTraceCountOf(orderNo), countOf("ws_message"),
                auditCountOf(orderNo), MallEnum.FulfillStatus.PENDING_FETCH.getValue());
    }

    @Test
    @DisplayName("R2③ 分配证据缺失/删除/错任务/错订单/空 SUBJECT_ID：五种全部拒绝且零副作用")
    void brokenAssignEvidenceIsAlwaysRefused() {
        String[] sqls = {
            "DELETE FROM ws_mall_fulfillment_trace WHERE ORDER_NO=? AND TRACE_NODE=?",
            "UPDATE ws_mall_fulfillment_trace SET DATA_STATUS=1 WHERE ORDER_NO=? AND TRACE_NODE=?",
            "UPDATE ws_mall_fulfillment_trace SET FULFILL_ID=999999 WHERE ORDER_NO=? AND TRACE_NODE=?",
            "UPDATE ws_mall_fulfillment_trace SET SUBJECT_ID=NULL WHERE ORDER_NO=? AND TRACE_NODE=?"};
        for (String sql : sqls) {
            String orderNo = assignedToCourierIn();
            jdbc.update(sql, orderNo, MallEnum.FulfillStatus.PENDING_FETCH.getValue());
            assertBothCouriersBlocked(orderNo, liveTraceCountOf(orderNo), countOf("ws_message"),
                    auditCountOf(orderNo), MallEnum.FulfillStatus.PENDING_FETCH.getValue());
            MallDbSchema.truncateAll(jdbc);
            seedCatalog();
            seedAddresses();
        }
        // ORDER_NO 错位：轨迹指向别的订单，本单查不到证据
        String orderNo = assignedToCourierIn();
        jdbc.update("UPDATE ws_mall_fulfillment_trace SET ORDER_NO='MO-OTHER'"
                + " WHERE ORDER_NO=? AND TRACE_NODE=?", orderNo,
                MallEnum.FulfillStatus.PENDING_FETCH.getValue());
        assertBothCouriersBlocked(orderNo, liveTraceCountOf(orderNo), countOf("ws_message"),
                auditCountOf(orderNo), MallEnum.FulfillStatus.PENDING_FETCH.getValue());
    }

    @Test
    @DisplayName("R2④ 正常分配证据：ACTOR_ID 是仓库操作员、SUBJECT_ID 是配送员，履约照常走完")
    void healthyAssignEvidenceKeepsFulfillmentWorking() {
        String orderNo = assignedToCourierIn();
        assertEquals(Long.valueOf(WH_OPERATOR), jdbc.queryForObject(
                "SELECT ACTOR_ID FROM ws_mall_fulfillment_trace WHERE ORDER_NO=? AND TRACE_NODE=?",
                Long.class, orderNo, MallEnum.FulfillStatus.PENDING_FETCH.getValue()),
                "ACTOR_ID 记「谁分配的」，不得被改成配送员ID");
        assertEquals(Long.valueOf(COURIER_IN), jdbc.queryForObject(
                "SELECT SUBJECT_ID FROM ws_mall_fulfillment_trace WHERE ORDER_NO=? AND TRACE_NODE=?",
                Long.class, orderNo, MallEnum.FulfillStatus.PENDING_FETCH.getValue()),
                "SUBJECT_ID 记「分给谁」");

        var bo = new com.jbk.tool.data.mall.bo.MallFulfillActionBo().setOrderNo(orderNo);
        assertEquals(1, selfDeliveryService.listForCourier(COURIER_USER).size());
        selfDeliveryService.fetch(COURIER_USER, bo);
        selfDeliveryService.arrive(COURIER_USER, bo);
        fulfillmentService.signByUser(USER, new com.jbk.tool.data.mall.bo.MallFulfillSignBo()
                .setOrderNo(orderNo).setSignMethod(MallEnum.SignMethod.SELF.getValue()));

        assertEquals(MallEnum.OrderStatus.COMPLETED.getValue(), orderStatusOf(orderNo));
        assertEquals(7, fulfillmentService.detailForManage(WH_OPERATOR, orderNo)
                .getTimeline().size(), "七节点时间线不回退");
        assertEquals(7L, auditCountOf(orderNo));
    }

    // ==================== S4 退货退款 / 库存回库 / 换货补发 ====================

    @Autowired
    private com.jbk.serve.service.mall.IMallAfterSaleService afterSaleService;
    @Autowired
    private com.jbk.serve.service.mall.IMallRefundFactService refundFactService;
    @Autowired
    private MallRefundFactRetryWorker refundFactRetryWorker;
    @Autowired
    private com.jbk.serve.service.mall.IMallExchangeTx exchangeTx;
    @Autowired
    private MallAfterSaleStock afterSaleStock;
    @Autowired
    private com.jbk.serve.service.mall.IMallSelfDeliveryService selfDeliveryService;
    @Autowired
    private MallRefundSimServiceImpl refundSimService;

    /** 把订单一路推到已签收（订单4/履约7），返回订单号。 */
    private String signedOrder(int quantity) {
        MallOrderVo order = orderService.create(USER,
                checkout(ADDR_WH1_ONLY, uuid(), SKU1, quantity));
        paySimService.pay(USER, order.getOrderNo());
        String orderNo = order.getOrderNo();
        // 履约任务由 Worker 幂等补齐（刻意不挂在支付事务里），测试显式调一次同一个入口
        fulfillmentService.ensureTask(orderNo);
        var bo = new com.jbk.tool.data.mall.bo.MallFulfillActionBo().setOrderNo(orderNo);
        fulfillmentService.pick(WH_OPERATOR, bo);
        fulfillmentService.pack(WH_OPERATOR, bo);
        selfDeliveryService.assign(WH_OPERATOR, new com.jbk.tool.data.mall.bo.MallFulfillAssignBo()
                .setOrderNo(orderNo).setCourierId(COURIER_IN));
        selfDeliveryService.fetch(COURIER_USER, bo);
        selfDeliveryService.arrive(COURIER_USER, bo);
        fulfillmentService.signByUser(USER, new com.jbk.tool.data.mall.bo.MallFulfillSignBo()
                .setOrderNo(orderNo).setSignMethod(MallEnum.SignMethod.SELF.getValue()));
        assertEquals(MallEnum.OrderStatus.COMPLETED.getValue(), orderStatusOf(orderNo));
        return orderNo;
    }

    private Long orderItemIdOf(String orderNo, long skuId) {
        return jdbc.queryForObject("SELECT i.ID FROM ws_mall_order_item i"
                + " JOIN ws_mall_order o ON o.ID = i.ORDER_ID"
                + " WHERE o.ORDER_NO=? AND i.SKU_ID=?", Long.class, orderNo, skuId);
    }

    private com.jbk.tool.data.mall.bo.MallAfterSaleApplyBo applyBo(
            String orderNo, int type, long skuId, Integer quantity) {
        var bo = new com.jbk.tool.data.mall.bo.MallAfterSaleApplyBo()
                .setRequestId(uuid()).setOrderNo(orderNo).setAfterSaleType(type)
                .setApplyReason("测试申请");
        if (quantity != null) {
            bo.setLines(java.util.List.of(new com.jbk.tool.data.mall.bo.MallAfterSaleApplyBo.Line()
                    .setOrderItemId(orderItemIdOf(orderNo, skuId)).setQuantity(quantity)));
        }
        return bo;
    }

    private int afterSaleStatusOf(String afterSaleNo) {
        return jdbc.queryForObject(
                "SELECT AFTER_SALE_STATUS FROM ws_mall_after_sale WHERE AFTER_SALE_NO=?",
                Integer.class, afterSaleNo);
    }

    private Integer refundStatusOf(String afterSaleNo) {
        return jdbc.queryForObject("SELECT r.REFUND_STATUS FROM ws_mall_refund r"
                + " JOIN ws_mall_after_sale a ON a.ID = r.AFTER_SALE_ID"
                + " WHERE a.AFTER_SALE_NO=?", Integer.class, afterSaleNo);
    }

    private String refundNoOf(String afterSaleNo) {
        return jdbc.queryForObject("SELECT r.REFUND_NO FROM ws_mall_refund r"
                + " JOIN ws_mall_after_sale a ON a.ID = r.AFTER_SALE_ID"
                + " WHERE a.AFTER_SALE_NO=?", String.class, afterSaleNo);
    }

    /** 走完 申请→审核→收货→质检，返回售后单号。 */
    private String returnUpToInspect(String orderNo, int quantity, int inspectResult) {
        return returnUpToInspect(orderNo, quantity, inspectResult,
                MallEnum.AfterSaleType.RETURN_REFUND.getValue());
    }

    private String returnUpToInspect(String orderNo, int quantity, int inspectResult, int type) {
        var applied = afterSaleService.apply(USER, applyBo(orderNo, type, SKU1, quantity));
        String no = applied.getAfterSaleNo();
        var noBo = new com.jbk.tool.data.mall.bo.MallAfterSaleNoBo().setAfterSaleNo(no);
        afterSaleService.audit(WH_OPERATOR, new com.jbk.tool.data.mall.bo.MallAfterSaleAuditBo()
                .setAfterSaleNo(no).setApproved(true).setRemark("同意"));
        afterSaleService.confirmReceive(WH_OPERATOR, noBo);
        afterSaleService.inspect(WH_OPERATOR,
                new com.jbk.tool.data.mall.bo.MallAfterSaleInspectBo().setAfterSaleNo(no)
                        .setInspectResult(inspectResult).setInspectRemark("质检说明"));
        return no;
    }

    /** 只落一条成功退款事实，不推进——留给调用方决定何时/以什么并发推进。 */
    private Long recordSuccessFact(String afterSaleNo) {
        String refundNo = refundNoOf(afterSaleNo);
        long amount = jdbc.queryForObject(
                "SELECT REFUND_AMOUNT_FEN FROM ws_mall_refund WHERE REFUND_NO=?", Long.class,
                refundNo);
        String orderNo = jdbc.queryForObject(
                "SELECT ORDER_NO FROM ws_mall_refund WHERE REFUND_NO=?", String.class, refundNo);
        return refundFactService.recordFact(MallEnum.PaySource.PAY_SIM.getValue(),
                MallEnum.RefundFactChannel.REFUND_SIM.getValue(), "RSIM-" + refundNo,
                refundNo, orderNo, MallEnum.RefundState.SUCCESS, "RTX-" + refundNo,
                amount, DateUtils.time(), MallEnum.VerifyMethod.PAY_SIM_INTERNAL, "{}").getId();
    }

    /** 补发单走完七态到用户签收（换货出库在签收事务里发生）。 */
    private void signReshipment(String orderNo) {
        var bo = new com.jbk.tool.data.mall.bo.MallFulfillActionBo().setOrderNo(orderNo);
        fulfillmentService.ensureTask(orderNo);
        fulfillmentService.pick(WH_OPERATOR, bo);
        fulfillmentService.pack(WH_OPERATOR, bo);
        selfDeliveryService.assign(WH_OPERATOR, new com.jbk.tool.data.mall.bo.MallFulfillAssignBo()
                .setOrderNo(orderNo).setCourierId(COURIER_IN));
        selfDeliveryService.fetch(COURIER_USER, bo);
        selfDeliveryService.arrive(COURIER_USER, bo);
        fulfillmentService.signByUser(USER, new com.jbk.tool.data.mall.bo.MallFulfillSignBo()
                .setOrderNo(orderNo).setSignMethod(MallEnum.SignMethod.SELF.getValue()));
    }

    /** 用 Refund-Sim 同形的事实推进退款（测试上下文不注册 Sim Bean，直接落事实）。 */
    private void succeedRefund(String afterSaleNo) {
        String refundNo = refundNoOf(afterSaleNo);
        long amount = jdbc.queryForObject(
                "SELECT REFUND_AMOUNT_FEN FROM ws_mall_refund WHERE REFUND_NO=?", Long.class,
                refundNo);
        String orderNo = jdbc.queryForObject(
                "SELECT ORDER_NO FROM ws_mall_refund WHERE REFUND_NO=?", String.class, refundNo);
        var fact = refundFactService.recordFact(MallEnum.PaySource.PAY_SIM.getValue(),
                MallEnum.RefundFactChannel.REFUND_SIM.getValue(), "RSIM-" + refundNo,
                refundNo, orderNo, MallEnum.RefundState.SUCCESS, "RTX-" + refundNo,
                amount, DateUtils.time(), MallEnum.VerifyMethod.PAY_SIM_INTERNAL, "{}");
        refundFactService.process(fact.getId());
    }

    @Test
    @DisplayName("S4① 部分退货退款：可售回库恰一条类型8流水，订单保持已完成")
    void partialReturnRefundRestocksWhenResellable() {
        String orderNo = signedOrder(3);
        long availableAfterSign = availableOf(WH1, SKU1);
        String no = returnUpToInspect(orderNo, 1,
                MallEnum.InspectResult.PASS_RESELLABLE.getValue());

        assertEquals(availableAfterSign + 1, availableOf(WH1, SKU1), "可售退货必须回库");
        assertEquals(1L, flowCountOfType(MallEnum.StockFlowType.RETURN_RESTOCK.getValue()));
        assertEquals(MallEnum.AfterSaleStatus.REFUNDING.getValue(), afterSaleStatusOf(no));
        assertEquals(MallEnum.RefundStatus.PENDING.getValue(), refundStatusOf(no));

        succeedRefund(no);
        assertEquals(MallEnum.AfterSaleStatus.COMPLETED.getValue(), afterSaleStatusOf(no));
        assertEquals(MallEnum.RefundStatus.SUCCESS.getValue(), refundStatusOf(no));
        assertEquals(MallEnum.OrderStatus.COMPLETED.getValue(), orderStatusOf(orderNo),
                "部分退款不改主订单终态");
    }

    @Test
    @DisplayName("S4② 不可重新销售：照退钱但库存不增，绝不把坏货放回可售")
    void notResellableRefundsWithoutRestock() {
        String orderNo = signedOrder(2);
        long available = availableOf(WH1, SKU1);
        String no = returnUpToInspect(orderNo, 1,
                MallEnum.InspectResult.PASS_NOT_RESELLABLE.getValue());

        assertEquals(available, availableOf(WH1, SKU1), "不可销售退货不得回库");
        assertEquals(0L, flowCountOfType(MallEnum.StockFlowType.RETURN_RESTOCK.getValue()));
        succeedRefund(no);
        assertEquals(MallEnum.AfterSaleStatus.COMPLETED.getValue(), afterSaleStatusOf(no));
        assertEquals(MallEnum.RefundStatus.SUCCESS.getValue(), refundStatusOf(no));
    }

    @Test
    @DisplayName("S4③ 质检不通过：零退款、零回库、售后转驳回")
    void inspectRejectedMeansNoRefundNoRestock() {
        String orderNo = signedOrder(2);
        long available = availableOf(WH1, SKU1);
        String no = returnUpToInspect(orderNo, 1,
                MallEnum.InspectResult.REJECTED.getValue());

        assertEquals(MallEnum.AfterSaleStatus.REJECTED.getValue(), afterSaleStatusOf(no));
        assertEquals(available, availableOf(WH1, SKU1));
        assertEquals(0L, flowCountOfType(MallEnum.StockFlowType.RETURN_RESTOCK.getValue()));
        assertEquals(0L, countOf("ws_mall_refund"), "驳回不得生成退款单");
    }

    @Test
    @DisplayName("S4④ 全额退款：订单精确进入已全额退款(6)")
    void fullRefundMovesOrderToRefunded() {
        String orderNo = signedOrder(2);
        String no = returnUpToInspect(orderNo, 2,
                MallEnum.InspectResult.PASS_RESELLABLE.getValue());
        succeedRefund(no);
        assertEquals(MallEnum.OrderStatus.REFUNDED.getValue(), orderStatusOf(orderNo));
    }

    @Test
    @DisplayName("S4⑤ 同 requestId 重放返回原单；改订单或改类型一律拒绝")
    void applyIsIdempotentAndRejectsDrift() {
        String orderNo = signedOrder(2);
        var bo = applyBo(orderNo, MallEnum.AfterSaleType.RETURN_REFUND.getValue(), SKU1, 1);
        var first = afterSaleService.apply(USER, bo);
        var replay = afterSaleService.apply(USER, bo);
        assertEquals(first.getAfterSaleNo(), replay.getAfterSaleNo());
        assertEquals(1L, countOf("ws_mall_after_sale"), "重放不得新增售后单");

        String otherOrder = signedOrder(1);
        var drift = applyBo(otherOrder, MallEnum.AfterSaleType.RETURN_REFUND.getValue(), SKU1, 1);
        drift.setRequestId(bo.getRequestId());
        assertThrows(JbkException.class, () -> afterSaleService.apply(USER, drift), "改订单被拒");

        var typeDrift = applyBo(orderNo, MallEnum.AfterSaleType.EXCHANGE.getValue(), SKU1, 1);
        typeDrift.setRequestId(bo.getRequestId());
        assertThrows(JbkException.class, () -> afterSaleService.apply(USER, typeDrift), "改类型被拒");
        assertEquals(1L, countOf("ws_mall_after_sale"), "两次改参都不得落新单");
    }

    @Test
    @DisplayName("S4⑥ 数量累计上限：处理中的申请占住额度，第二次超额被拒")
    void quantityCapCountsInFlightApplications() {
        String orderNo = signedOrder(2);
        afterSaleService.apply(USER,
                applyBo(orderNo, MallEnum.AfterSaleType.RETURN_REFUND.getValue(), SKU1, 2));
        assertThrows(JbkException.class, () -> afterSaleService.apply(USER,
                applyBo(orderNo, MallEnum.AfterSaleType.RETURN_REFUND.getValue(), SKU1, 1)),
                "已占满额度，第二次必须被拒");
        assertEquals(1L, countOf("ws_mall_after_sale"));
    }

    @Test
    @DisplayName("S4⑦ 重复退款事实：不重复退款、不重复回库")
    void duplicateRefundFactIsIdempotent() {
        String orderNo = signedOrder(2);
        String no = returnUpToInspect(orderNo, 1,
                MallEnum.InspectResult.PASS_RESELLABLE.getValue());
        succeedRefund(no);
        long restockFlows = flowCountOfType(MallEnum.StockFlowType.RETURN_RESTOCK.getValue());

        succeedRefund(no);
        assertEquals(MallEnum.RefundStatus.SUCCESS.getValue(), refundStatusOf(no));
        assertEquals(restockFlows,
                flowCountOfType(MallEnum.StockFlowType.RETURN_RESTOCK.getValue()));
        assertEquals(1L, countOf("ws_mall_refund_fact"), "同键事实不得新增");
    }

    @Test
    @DisplayName("S4⑧ 未拣货整单取消：审核通过即回库并退款；已拣货一律拒绝")
    void cancelRefundOnlyBeforePick() {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        paySimService.pay(USER, order.getOrderNo());
        long available = availableOf(WH1, SKU1);
        var applied = afterSaleService.apply(USER,
                applyBo(order.getOrderNo(), MallEnum.AfterSaleType.CANCEL_REFUND.getValue(),
                        SKU1, null));
        String no = applied.getAfterSaleNo();
        afterSaleService.audit(WH_OPERATOR, new com.jbk.tool.data.mall.bo.MallAfterSaleAuditBo()
                .setAfterSaleNo(no).setApproved(true).setRemark("同意取消"));
        assertEquals(available + 2, availableOf(WH1, SKU1), "货未离仓，审核通过即回库");
        succeedRefund(no);
        assertEquals(MallEnum.OrderStatus.REFUNDED.getValue(), orderStatusOf(order.getOrderNo()));

        // 已开始拣货的订单不得再走整单取消
        MallOrderVo picked = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 1));
        paySimService.pay(USER, picked.getOrderNo());
        fulfillmentService.ensureTask(picked.getOrderNo());
        fulfillmentService.pick(WH_OPERATOR,
                new com.jbk.tool.data.mall.bo.MallFulfillActionBo().setOrderNo(picked.getOrderNo()));
        assertThrows(JbkException.class, () -> afterSaleService.apply(USER,
                applyBo(picked.getOrderNo(), MallEnum.AfterSaleType.CANCEL_REFUND.getValue(),
                        SKU1, null)));
    }

    @Test
    @DisplayName("S4⑨ 同 SKU 换货：补发单零价、复用履约链、签收时换货出库恰一次")
    void exchangeReshipmentReusesFulfillmentChain() {
        String orderNo = signedOrder(2);
        var applied = afterSaleService.apply(USER,
                applyBo(orderNo, MallEnum.AfterSaleType.EXCHANGE.getValue(), SKU1, 1));
        String no = applied.getAfterSaleNo();
        var noBo = new com.jbk.tool.data.mall.bo.MallAfterSaleNoBo().setAfterSaleNo(no);
        afterSaleService.audit(WH_OPERATOR, new com.jbk.tool.data.mall.bo.MallAfterSaleAuditBo()
                .setAfterSaleNo(no).setApproved(true).setRemark("同意换货"));
        afterSaleService.confirmReceive(WH_OPERATOR, noBo);
        afterSaleService.inspect(WH_OPERATOR,
                new com.jbk.tool.data.mall.bo.MallAfterSaleInspectBo().setAfterSaleNo(no)
                        .setInspectResult(MallEnum.InspectResult.PASS_RESELLABLE.getValue())
                        .setInspectRemark("可换"));

        assertEquals(MallEnum.AfterSaleStatus.EXCHANGING.getValue(), afterSaleStatusOf(no));
        String reshipNo = jdbc.queryForObject("SELECT o.ORDER_NO FROM ws_mall_order o"
                + " JOIN ws_mall_after_sale a ON a.ID = o.SOURCE_AFTER_SALE_ID"
                + " WHERE a.AFTER_SALE_NO=?", String.class, no);
        assertNotNull(reshipNo);
        assertEquals(0L, jdbc.queryForObject(
                "SELECT ORDER_AMOUNT_FEN FROM ws_mall_order WHERE ORDER_NO=?", Long.class,
                reshipNo), "补发单必须零价，不计新销售收入");
        assertEquals(1L, flowCountOfType(MallEnum.StockFlowType.EXCHANGE_RESERVE.getValue()));
        assertEquals(0L, flowCountOfType(MallEnum.StockFlowType.EXCHANGE_OUT.getValue()),
                "出库要等签收");

        // 补发单走同一套 S3 履约链
        var rBo = new com.jbk.tool.data.mall.bo.MallFulfillActionBo().setOrderNo(reshipNo);
        fulfillmentService.pick(WH_OPERATOR, rBo);
        fulfillmentService.pack(WH_OPERATOR, rBo);
        selfDeliveryService.assign(WH_OPERATOR, new com.jbk.tool.data.mall.bo.MallFulfillAssignBo()
                .setOrderNo(reshipNo).setCourierId(COURIER_IN));
        selfDeliveryService.fetch(COURIER_USER, rBo);
        selfDeliveryService.arrive(COURIER_USER, rBo);
        fulfillmentService.signByUser(USER, new com.jbk.tool.data.mall.bo.MallFulfillSignBo()
                .setOrderNo(reshipNo).setSignMethod(MallEnum.SignMethod.SELF.getValue()));

        assertEquals(1L, flowCountOfType(MallEnum.StockFlowType.EXCHANGE_OUT.getValue()),
                "签收时换货出库恰一次");
        assertEquals(MallEnum.AfterSaleStatus.COMPLETED.getValue(), afterSaleStatusOf(no));
        assertEquals(0L, countOf("ws_mall_refund"), "换货不产生退款单");
    }

    @Test
    @DisplayName("S4⑩ 越权三类：他人售后、跨仓操作员、非本人撤销全部拒绝")
    void afterSaleScopeIsEnforced() {
        String orderNo = signedOrder(2);
        var applied = afterSaleService.apply(USER,
                applyBo(orderNo, MallEnum.AfterSaleType.RETURN_REFUND.getValue(), SKU1, 1));
        String no = applied.getAfterSaleNo();
        var noBo = new com.jbk.tool.data.mall.bo.MallAfterSaleNoBo().setAfterSaleNo(no);

        assertThrows(JbkException.class, () -> afterSaleService.detailForUser(OTHER_USER, no),
                "他人售后不得读取");
        assertThrows(JbkException.class, () -> afterSaleService.cancelByUser(OTHER_USER, noBo),
                "他人售后不得撤销");
        assertThrows(JbkException.class,
                () -> afterSaleService.detailForManage(OTHER_WH_OPERATOR, no),
                "跨仓操作员不得读取");
        assertThrows(JbkException.class, () -> afterSaleService.audit(OTHER_WH_OPERATOR,
                new com.jbk.tool.data.mall.bo.MallAfterSaleAuditBo().setAfterSaleNo(no)
                        .setApproved(true).setRemark("越权")),
                "跨仓操作员不得审核");
        assertEquals(MallEnum.AfterSaleStatus.PENDING_AUDIT.getValue(), afterSaleStatusOf(no));
    }

    @Test
    @DisplayName("S4⑪ 售后窗双侧：窗内允许，超一秒拒绝；签收时间缺失一律拒绝")
    void afterSaleWindowIsBounded() {
        String orderNo = signedOrder(1);
        String signTime = jdbc.queryForObject(
                "SELECT SIGN_TIME FROM ws_mall_fulfillment WHERE ORDER_NO=?", String.class, orderNo);

        // 把签收时间推到 7 天零 1 秒之前：窗口刚好过界
        jdbc.update("UPDATE ws_mall_fulfillment SET SIGN_TIME=? WHERE ORDER_NO=?",
                DateUtils.plusSeconds(DateUtils.time(), -(7L * 24 * 3600 + 1)), orderNo);
        assertThrows(JbkException.class, () -> afterSaleService.apply(USER,
                applyBo(orderNo, MallEnum.AfterSaleType.RETURN_REFUND.getValue(), SKU1, 1)),
                "超窗一秒必须拒绝");

        // 非法签收时间：不得按当前时间猜测
        jdbc.update("UPDATE ws_mall_fulfillment SET SIGN_TIME='20260230000000' WHERE ORDER_NO=?",
                orderNo);
        assertThrows(JbkException.class, () -> afterSaleService.apply(USER,
                applyBo(orderNo, MallEnum.AfterSaleType.RETURN_REFUND.getValue(), SKU1, 1)));

        // 复原后窗内正常受理
        jdbc.update("UPDATE ws_mall_fulfillment SET SIGN_TIME=? WHERE ORDER_NO=?",
                signTime, orderNo);
        assertNotNull(afterSaleService.apply(USER,
                applyBo(orderNo, MallEnum.AfterSaleType.RETURN_REFUND.getValue(), SKU1, 1))
                .getAfterSaleNo());
    }

    @Test
    @DisplayName("S4⑫ 退款事实已落但推进失败：Worker 可补齐，且只推进一次")
    void refundWorkerCompletesUnprocessedFact() {
        String orderNo = signedOrder(2);
        String no = returnUpToInspect(orderNo, 1,
                MallEnum.InspectResult.PASS_RESELLABLE.getValue());
        String refundNo = refundNoOf(no);
        long amount = jdbc.queryForObject(
                "SELECT REFUND_AMOUNT_FEN FROM ws_mall_refund WHERE REFUND_NO=?", Long.class,
                refundNo);
        // 只落事实不推进：复刻「事务A 成功、事务B 未跑」
        refundFactService.recordFact(MallEnum.PaySource.PAY_SIM.getValue(),
                MallEnum.RefundFactChannel.REFUND_SIM.getValue(), "RSIM-" + refundNo,
                refundNo, orderNo, MallEnum.RefundState.SUCCESS, "RTX-" + refundNo,
                amount, DateUtils.time(), MallEnum.VerifyMethod.PAY_SIM_INTERNAL, "{}");
        assertEquals(MallEnum.RefundStatus.PENDING.getValue(), refundStatusOf(no));

        assertEquals(1, refundFactRetryWorker.runOnce(DateUtils.time()), "Worker 应补齐一条");
        assertEquals(MallEnum.RefundStatus.SUCCESS.getValue(), refundStatusOf(no));
        assertEquals(MallEnum.AfterSaleStatus.COMPLETED.getValue(), afterSaleStatusOf(no));
        assertEquals(0, refundFactRetryWorker.runOnce(DateUtils.time()), "终态事实不再被捞");
    }

    @Test
    @DisplayName("S4⑬ 退款事实共键错位与未知状态：一律转人工，零资金零库存动作")
    void brokenRefundFactGoesToManual() {
        String orderNo = signedOrder(2);
        String no = returnUpToInspect(orderNo, 1,
                MallEnum.InspectResult.PASS_RESELLABLE.getValue());
        String refundNo = refundNoOf(no);
        long amount = jdbc.queryForObject(
                "SELECT REFUND_AMOUNT_FEN FROM ws_mall_refund WHERE REFUND_NO=?", Long.class,
                refundNo);
        long restockFlows = flowCountOfType(MallEnum.StockFlowType.RETURN_RESTOCK.getValue());

        // 金额被篡改
        var bad = refundFactService.recordFact(MallEnum.PaySource.PAY_SIM.getValue(),
                MallEnum.RefundFactChannel.NOTIFY.getValue(), "BADAMT-" + refundNo,
                refundNo, orderNo, MallEnum.RefundState.SUCCESS, "RTX-BAD",
                amount + 1, DateUtils.time(), MallEnum.VerifyMethod.PAY_SIM_INTERNAL, "{}");
        assertEquals(MallEnum.RefundFactStatus.NEED_RECONCILE.getValue(), (int) jdbc.queryForObject(
                "SELECT PROCESSING_STATUS FROM ws_mall_refund_fact WHERE ID=?", Integer.class,
                bad.getId()));

        // 未知退款状态
        var unknown = refundFactService.recordFact(MallEnum.PaySource.PAY_SIM.getValue(),
                MallEnum.RefundFactChannel.NOTIFY.getValue(), "UNKNOWN-" + refundNo,
                refundNo, orderNo, "PARTIAL_REFUND", "RTX-UNKNOWN",
                amount, DateUtils.time(), MallEnum.VerifyMethod.PAY_SIM_INTERNAL, "{}");
        assertEquals(MallEnum.RefundFactStatus.NEED_RECONCILE.getValue(), (int) jdbc.queryForObject(
                "SELECT PROCESSING_STATUS FROM ws_mall_refund_fact WHERE ID=?", Integer.class,
                unknown.getId()));

        assertEquals(MallEnum.RefundStatus.PENDING.getValue(), refundStatusOf(no));
        assertEquals(MallEnum.AfterSaleStatus.REFUNDING.getValue(), afterSaleStatusOf(no));
        assertEquals(restockFlows,
                flowCountOfType(MallEnum.StockFlowType.RETURN_RESTOCK.getValue()));
    }

    @Test
    @DisplayName("S4⑭ 并发抢最后可退数量：恰一个成功")
    void concurrentApplyForLastQuantityHasSingleWinner() throws Exception {
        String orderNo = signedOrder(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        try {
            var f1 = pool.submit(() -> {
                go.await();
                try {
                    afterSaleService.apply(USER, applyBo(orderNo,
                            MallEnum.AfterSaleType.RETURN_REFUND.getValue(), SKU1, 1));
                    ok.incrementAndGet();
                }
                catch (RuntimeException ignored) {
                    // 输方
                }
                return null;
            });
            var f2 = pool.submit(() -> {
                go.await();
                try {
                    afterSaleService.apply(USER, applyBo(orderNo,
                            MallEnum.AfterSaleType.RETURN_REFUND.getValue(), SKU1, 1));
                    ok.incrementAndGet();
                }
                catch (RuntimeException ignored) {
                    // 输方
                }
                return null;
            });
            go.countDown();
            f1.get();
            f2.get();
        }
        finally {
            pool.shutdownNow();
        }
        assertEquals(1, ok.get(), "最后一件只能被一个申请占住");
        assertEquals(1L, countOf("ws_mall_after_sale"));
    }

    @Test
    @DisplayName("S4⑮ 普通商城订单履约全程零库存动作：S4 上线后无回归")
    void normalFulfillmentStillTouchesNoStock() {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        paySimService.pay(USER, order.getOrderNo());
        long available = availableOf(WH1, SKU1);
        long reserved = reservedOf(WH1, SKU1);
        long flows = countOf("ws_mall_stock_flow");

        String orderNo = order.getOrderNo();
        fulfillmentService.ensureTask(orderNo);
        var bo = new com.jbk.tool.data.mall.bo.MallFulfillActionBo().setOrderNo(orderNo);
        fulfillmentService.pick(WH_OPERATOR, bo);
        fulfillmentService.pack(WH_OPERATOR, bo);
        selfDeliveryService.assign(WH_OPERATOR, new com.jbk.tool.data.mall.bo.MallFulfillAssignBo()
                .setOrderNo(orderNo).setCourierId(COURIER_IN));
        selfDeliveryService.fetch(COURIER_USER, bo);
        selfDeliveryService.arrive(COURIER_USER, bo);
        fulfillmentService.signByUser(USER, new com.jbk.tool.data.mall.bo.MallFulfillSignBo()
                .setOrderNo(orderNo).setSignMethod(MallEnum.SignMethod.SELF.getValue()));

        assertEquals(available, availableOf(WH1, SKU1));
        assertEquals(reserved, reservedOf(WH1, SKU1));
        assertEquals(flows, countOf("ws_mall_stock_flow"), "普通订单履约不得新增任何库存流水");
        assertEquals(0L, flowCountOfType(MallEnum.StockFlowType.EXCHANGE_OUT.getValue()));
    }

    @Test
    @DisplayName("S4⑯ 逻辑删除的订单/支付单/售后单：售后链全程 fail-closed")
    void logicallyDeletedEntitiesFailClosed() {
        String orderNo = signedOrder(2);
        jdbc.update("UPDATE ws_mall_order SET DATA_STATUS=1 WHERE ORDER_NO=?", orderNo);
        assertThrows(JbkException.class, () -> afterSaleService.apply(USER,
                applyBo(orderNo, MallEnum.AfterSaleType.RETURN_REFUND.getValue(), SKU1, 1)),
                "订单已删除不得申请售后");
        jdbc.update("UPDATE ws_mall_order SET DATA_STATUS=0 WHERE ORDER_NO=?", orderNo);

        var applied = afterSaleService.apply(USER,
                applyBo(orderNo, MallEnum.AfterSaleType.RETURN_REFUND.getValue(), SKU1, 1));
        String no = applied.getAfterSaleNo();
        jdbc.update("UPDATE ws_mall_after_sale SET DATA_STATUS=1 WHERE AFTER_SALE_NO=?", no);
        assertThrows(JbkException.class, () -> afterSaleService.audit(WH_OPERATOR,
                new com.jbk.tool.data.mall.bo.MallAfterSaleAuditBo().setAfterSaleNo(no)
                        .setApproved(true).setRemark("越过删除")),
                "已删除售后单不得推进");
        assertThrows(JbkException.class, () -> afterSaleService.detailForUser(USER, no));
        jdbc.update("UPDATE ws_mall_after_sale SET DATA_STATUS=0 WHERE AFTER_SALE_NO=?", no);

        jdbc.update("UPDATE ws_mall_payment SET DATA_STATUS=1 WHERE ORDER_NO=?", orderNo);
        assertThrows(JbkException.class, () -> afterSaleService.audit(WH_OPERATOR,
                new com.jbk.tool.data.mall.bo.MallAfterSaleAuditBo().setAfterSaleNo(no)
                        .setApproved(true).setRemark("支付单已删")),
                "支付单已删除不得进入退款");
        assertEquals(0L, countOf("ws_mall_refund"));
    }

    @Test
    @DisplayName("S4⑰ 换货补发库存不足：整事务回滚，绝不留半张补发单")
    void exchangeWithoutStockLeavesNoHalfOrder() {
        String orderNo = signedOrder(2);
        var applied = afterSaleService.apply(USER,
                applyBo(orderNo, MallEnum.AfterSaleType.EXCHANGE.getValue(), SKU1, 1));
        String no = applied.getAfterSaleNo();
        var noBo = new com.jbk.tool.data.mall.bo.MallAfterSaleNoBo().setAfterSaleNo(no);
        afterSaleService.audit(WH_OPERATOR, new com.jbk.tool.data.mall.bo.MallAfterSaleAuditBo()
                .setAfterSaleNo(no).setApproved(true).setRemark("同意"));
        afterSaleService.confirmReceive(WH_OPERATOR, noBo);
        // 抽干可售：换货预占必然失败
        jdbc.update("UPDATE ws_mall_stock SET AVAILABLE_QTY=0 WHERE WAREHOUSE_ID=? AND SKU_ID=?",
                WH1, SKU1);
        long orders = countOf("ws_mall_order");

        assertThrows(JbkException.class, () -> afterSaleService.inspect(WH_OPERATOR,
                new com.jbk.tool.data.mall.bo.MallAfterSaleInspectBo().setAfterSaleNo(no)
                        .setInspectResult(MallEnum.InspectResult.PASS_NOT_RESELLABLE.getValue())
                        .setInspectRemark("库存不足")));

        assertEquals(orders, countOf("ws_mall_order"), "不得留下半张补发单");
        assertEquals(0L, flowCountOfType(MallEnum.StockFlowType.EXCHANGE_RESERVE.getValue()));
        assertEquals(MallEnum.AfterSaleStatus.PENDING_INSPECT.getValue(), afterSaleStatusOf(no),
                "质检未落定，售后停在原态");
    }

    @Test
    @DisplayName("S4⑱ 重复创建换货补发：只生成一个来源与一条履约链")
    void duplicateReshipmentCreatesSingleSource() {
        String orderNo = signedOrder(2);
        var applied = afterSaleService.apply(USER,
                applyBo(orderNo, MallEnum.AfterSaleType.EXCHANGE.getValue(), SKU1, 1));
        String no = applied.getAfterSaleNo();
        var noBo = new com.jbk.tool.data.mall.bo.MallAfterSaleNoBo().setAfterSaleNo(no);
        afterSaleService.audit(WH_OPERATOR, new com.jbk.tool.data.mall.bo.MallAfterSaleAuditBo()
                .setAfterSaleNo(no).setApproved(true).setRemark("同意"));
        afterSaleService.confirmReceive(WH_OPERATOR, noBo);
        afterSaleService.inspect(WH_OPERATOR,
                new com.jbk.tool.data.mall.bo.MallAfterSaleInspectBo().setAfterSaleNo(no)
                        .setInspectResult(MallEnum.InspectResult.PASS_RESELLABLE.getValue())
                        .setInspectRemark("可换"));
        long reserveFlows = flowCountOfType(MallEnum.StockFlowType.EXCHANGE_RESERVE.getValue());
        Long afterSaleId = jdbc.queryForObject(
                "SELECT ID FROM ws_mall_after_sale WHERE AFTER_SALE_NO=?", Long.class, no);

        String again = exchangeTx.createReshipment(afterSaleId, WH_OPERATOR);
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_mall_order WHERE SOURCE_AFTER_SALE_ID=?", Long.class,
                afterSaleId), "一张售后单只补发一次");
        assertNotNull(again);
        assertEquals(reserveFlows,
                flowCountOfType(MallEnum.StockFlowType.EXCHANGE_RESERVE.getValue()),
                "重复创建不得二次预占");
    }

    @Test
    @DisplayName("S4⑲ 并发退款 Worker：只推进一次，完成节点轨迹恰一条")
    void concurrentRefundWorkersAdvanceOnce() throws Exception {
        String orderNo = signedOrder(2);
        String no = returnUpToInspect(orderNo, 1,
                MallEnum.InspectResult.PASS_RESELLABLE.getValue());
        String refundNo = refundNoOf(no);
        long amount = jdbc.queryForObject(
                "SELECT REFUND_AMOUNT_FEN FROM ws_mall_refund WHERE REFUND_NO=?", Long.class,
                refundNo);
        var fact = refundFactService.recordFact(MallEnum.PaySource.PAY_SIM.getValue(),
                MallEnum.RefundFactChannel.REFUND_SIM.getValue(), "RSIM-" + refundNo,
                refundNo, orderNo, MallEnum.RefundState.SUCCESS, "RTX-" + refundNo,
                amount, DateUtils.time(), MallEnum.VerifyMethod.PAY_SIM_INTERNAL, "{}");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger applied = new AtomicInteger();
        try {
            var f1 = pool.submit(() -> {
                go.await();
                if (refundFactService.process(fact.getId()).code()
                        == IMallPayApplyTx.Code.APPLIED) {
                    applied.incrementAndGet();
                }
                return null;
            });
            var f2 = pool.submit(() -> {
                go.await();
                if (refundFactService.process(fact.getId()).code()
                        == IMallPayApplyTx.Code.APPLIED) {
                    applied.incrementAndGet();
                }
                return null;
            });
            go.countDown();
            f1.get();
            f2.get();
        }
        finally {
            pool.shutdownNow();
        }
        assertEquals(1, applied.get(), "两个 Worker 只能有一个真正推进");
        assertEquals(MallEnum.RefundStatus.SUCCESS.getValue(), refundStatusOf(no));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM ws_mall_after_sale_trace"
                + " WHERE AFTER_SALE_NO=? AND TRACE_NODE=? AND DATA_STATUS=0", Long.class, no,
                MallEnum.AfterSaleStatus.COMPLETED.getValue()), "完成节点轨迹恰一条");
    }

    @Test
    @DisplayName("S4⑳ 审计写入失败：售后状态、轨迹、消息全部零变化")
    void afterSaleAuditFailureRollsBackEverything() {
        String orderNo = signedOrder(2);
        var applied = afterSaleService.apply(USER,
                applyBo(orderNo, MallEnum.AfterSaleType.RETURN_REFUND.getValue(), SKU1, 1));
        String no = applied.getAfterSaleNo();
        jdbc.update("INSERT INTO ws_domain_event (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY,"
                + " UPDATE_TIME, EVENT_TYPE, EVENT_KEY, EVENT_PAYLOAD, ACTOR_ID, ACTOR_PORTAL,"
                + " ACTOR_ROLE, WHITELIST_FLAG, CONSUMED_FLAG, BIZ_IDEMPOTENCY_KEY)"
                + " VALUES (0,1,'20260810120000',1,'20260810120000',11,'OTHER','{}',1,1,'x',0,0,?)",
                MallAfterSaleServiceImpl.AUDIT_KEY_PREFIX + no + ":"
                        + MallEnum.AfterSaleStatus.PENDING_RETURN.getValue());
        long traces = jdbc.queryForObject("SELECT COUNT(*) FROM ws_mall_after_sale_trace"
                + " WHERE AFTER_SALE_NO=? AND DATA_STATUS=0", Long.class, no);
        long messages = countOf("ws_message");

        assertThrows(RuntimeException.class, () -> afterSaleService.audit(WH_OPERATOR,
                new com.jbk.tool.data.mall.bo.MallAfterSaleAuditBo().setAfterSaleNo(no)
                        .setApproved(true).setRemark("同意")));

        assertEquals(MallEnum.AfterSaleStatus.PENDING_AUDIT.getValue(), afterSaleStatusOf(no));
        assertEquals(traces, jdbc.queryForObject("SELECT COUNT(*) FROM ws_mall_after_sale_trace"
                + " WHERE AFTER_SALE_NO=? AND DATA_STATUS=0", Long.class, no));
        assertEquals(messages, countOf("ws_message"));
    }

    @Test
    @DisplayName("S4㉑ 消息写入失败：售后状态、轨迹、审计全部零变化")
    void afterSaleMessageFailureRollsBackEverything() {
        String orderNo = signedOrder(2);
        var applied = afterSaleService.apply(USER,
                applyBo(orderNo, MallEnum.AfterSaleType.RETURN_REFUND.getValue(), SKU1, 1));
        String no = applied.getAfterSaleNo();
        long audits = jdbc.queryForObject("SELECT COUNT(*) FROM ws_domain_event"
                + " WHERE BIZ_IDEMPOTENCY_KEY LIKE ?", Long.class,
                MallAfterSaleServiceImpl.AUDIT_KEY_PREFIX + no + ":%");

        FAIL_MESSAGE = true;
        try {
            assertThrows(RuntimeException.class, () -> afterSaleService.audit(WH_OPERATOR,
                    new com.jbk.tool.data.mall.bo.MallAfterSaleAuditBo().setAfterSaleNo(no)
                            .setApproved(true).setRemark("同意")));
        }
        finally {
            FAIL_MESSAGE = false;
        }
        assertEquals(MallEnum.AfterSaleStatus.PENDING_AUDIT.getValue(), afterSaleStatusOf(no));
        assertEquals(audits, jdbc.queryForObject("SELECT COUNT(*) FROM ws_domain_event"
                + " WHERE BIZ_IDEMPOTENCY_KEY LIKE ?", Long.class,
                MallAfterSaleServiceImpl.AUDIT_KEY_PREFIX + no + ":%"));
        assertEquals(0L, countOf("ws_mall_refund"));
    }

    @Test
    @DisplayName("S4㉒ 售后轨迹键被占用：一律 fail-closed，状态与资金零变化")
    void occupiedAfterSaleTraceFailsClosed() {
        String orderNo = signedOrder(2);
        var applied = afterSaleService.apply(USER,
                applyBo(orderNo, MallEnum.AfterSaleType.RETURN_REFUND.getValue(), SKU1, 1));
        String no = applied.getAfterSaleNo();
        Long afterSaleId = jdbc.queryForObject(
                "SELECT ID FROM ws_mall_after_sale WHERE AFTER_SALE_NO=?", Long.class, no);
        jdbc.update("INSERT INTO ws_mall_after_sale_trace (DATA_STATUS, CREATE_BY, CREATE_TIME,"
                + " UPDATE_BY, UPDATE_TIME, AFTER_SALE_ID, AFTER_SALE_NO, TRACE_NODE, ACTOR_TYPE,"
                + " ACTOR_ID, TRACE_TIME, TRACE_TEXT, BIZ_IDEMPOTENCY_KEY)"
                + " VALUES (0,1,'20260810120000',1,'20260810120000',?,?,?,2,1,"
                + "'20260810120000','伪造节点',?)",
                afterSaleId, no, MallEnum.AfterSaleStatus.PENDING_RETURN.getValue(),
                MallAfterSaleTraceWriter.TRACE_KEY_PREFIX + no + ":"
                        + MallEnum.AfterSaleStatus.PENDING_RETURN.getValue());

        assertThrows(JbkException.class, () -> afterSaleService.audit(WH_OPERATOR,
                new com.jbk.tool.data.mall.bo.MallAfterSaleAuditBo().setAfterSaleNo(no)
                        .setApproved(true).setRemark("同意")));
        assertEquals(MallEnum.AfterSaleStatus.PENDING_AUDIT.getValue(), afterSaleStatusOf(no));
        assertEquals(0L, countOf("ws_mall_refund"));
    }

    @Test
    @DisplayName("S4㉓ 回库幂等键：同一售后单重复回库只加一次可售、只落一条流水")
    void restockIsIdempotentByBizKey() {
        String orderNo = signedOrder(2);
        String no = returnUpToInspect(orderNo, 1,
                MallEnum.InspectResult.PASS_RESELLABLE.getValue());
        long available = availableOf(WH1, SKU1);
        long flows = flowCountOfType(MallEnum.StockFlowType.RETURN_RESTOCK.getValue());

        // 直接再调一次回库：状态机层面不可达，但幂等键必须自己兜住
        // （运维重放、Worker 补偿都可能把同一个动作再发一次）
        afterSaleStock.restock(WH1, SKU1, 1L, no, WH_OPERATOR, DateUtils.time());

        assertEquals(available, availableOf(WH1, SKU1), "重复回库不得再加可售");
        assertEquals(flows, flowCountOfType(MallEnum.StockFlowType.RETURN_RESTOCK.getValue()),
                "重复回库不得再落流水");
    }

    @Test
    @DisplayName("S4㉔ 事务B 独立复核：事实落库后被篡改金额，推进段必须转人工")
    void refundApplyRejectsTamperedFactAfterRecord() {
        String orderNo = signedOrder(2);
        String no = returnUpToInspect(orderNo, 1,
                MallEnum.InspectResult.PASS_RESELLABLE.getValue());
        String refundNo = refundNoOf(no);
        long amount = jdbc.queryForObject(
                "SELECT REFUND_AMOUNT_FEN FROM ws_mall_refund WHERE REFUND_NO=?", Long.class,
                refundNo);
        var fact = refundFactService.recordFact(MallEnum.PaySource.PAY_SIM.getValue(),
                MallEnum.RefundFactChannel.REFUND_SIM.getValue(), "RSIM-" + refundNo,
                refundNo, orderNo, MallEnum.RefundState.SUCCESS, "RTX-" + refundNo,
                amount, DateUtils.time(), MallEnum.VerifyMethod.PAY_SIM_INTERNAL, "{}");
        assertEquals(MallEnum.RefundFactStatus.PENDING.getValue(), (int) jdbc.queryForObject(
                "SELECT PROCESSING_STATUS FROM ws_mall_refund_fact WHERE ID=?", Integer.class,
                fact.getId()), "前置：事务A 已放行");

        // 事务A 与事务B 之间事实被改：事务B 不得信任上一段的结论
        jdbc.update("UPDATE ws_mall_refund_fact SET REFUND_AMOUNT_FEN=? WHERE ID=?",
                amount + 1, fact.getId());
        assertEquals(IMallPayApplyTx.Code.RECONCILE,
                refundFactService.process(fact.getId()).code());
        assertEquals(MallEnum.RefundStatus.PENDING.getValue(), refundStatusOf(no),
                "退款单不得被推成成功");
        assertEquals(MallEnum.AfterSaleStatus.REFUNDING.getValue(), afterSaleStatusOf(no));
    }

    @Test
    @DisplayName("S4㉕ 三端契约：订单明细下发 orderItemId，换货补发单在履约上标记，原单不标记")
    void reshipmentIsMarkedAndOrderItemIdIsExposed() {
        String orderNo = signedOrder(2);
        // 申请售后要按明细 ID 定位原行；订单详情不下发它，前端就只能拿 skuId 猜，一单两行同 SKU 时必错
        var detail = orderService.detailForUser(USER, orderNo);
        assertEquals(String.valueOf(orderItemIdOf(orderNo, SKU1)),
                detail.getItems().get(0).getOrderItemId());
        assertNotEquals(Boolean.TRUE, fulfillmentService.detailForUser(USER, orderNo)
                .getExchangeReshipment(), "原单不是换货补发");

        String no = returnUpToInspect(orderNo, 1, MallEnum.InspectResult.PASS_RESELLABLE.getValue(),
                MallEnum.AfterSaleType.EXCHANGE.getValue());
        String exchangeNo = jdbc.queryForObject(
                "SELECT o.ORDER_NO FROM ws_mall_order o JOIN ws_mall_after_sale a"
                        + " ON a.ID = o.SOURCE_AFTER_SALE_ID WHERE a.AFTER_SALE_NO=?",
                String.class, no);
        // 配送员据此判断「不再收款」：标记必须来自订单的来源售后单，而不是金额为零的猜测
        assertEquals(Boolean.TRUE,
                fulfillmentService.detailForUser(USER, exchangeNo).getExchangeReshipment());
        assertEquals(Boolean.TRUE, fulfillmentService.detailForManage(WH_OPERATOR, exchangeNo)
                .getExchangeReshipment());
    }

    @Test
    @DisplayName("R1-P0 整单取消审核：申请后订单被拣走，审核必须拒绝且零资金零库存副作用")
    void cancelRefundAuditRejectsWhenOrderAlreadyPicked() {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        String orderNo = order.getOrderNo();
        paySimService.pay(USER, orderNo);
        fulfillmentService.ensureTask(orderNo);
        var applied = afterSaleService.apply(USER,
                applyBo(orderNo, MallEnum.AfterSaleType.CANCEL_REFUND.getValue(), SKU1, null));
        String no = applied.getAfterSaleNo();

        // 申请与审核之间，前置仓把这张单正常拣走——申请时那把订单行锁早已随事务提交释放
        fulfillmentService.pick(WH_OPERATOR,
                new com.jbk.tool.data.mall.bo.MallFulfillActionBo().setOrderNo(orderNo));
        long flows = countOf("ws_mall_stock_flow");
        long refunds = countOf("ws_mall_refund");

        assertThrows(JbkException.class, () -> afterSaleService.audit(WH_OPERATOR,
                new com.jbk.tool.data.mall.bo.MallAfterSaleAuditBo()
                        .setAfterSaleNo(no).setApproved(true).setRemark("按整单取消通过")),
                "货已开始履约，整单取消不得再通过");
        assertEquals(MallEnum.AfterSaleStatus.PENDING_AUDIT.getValue(), afterSaleStatusOf(no),
                "拒绝后售后停在原态");
        assertEquals(flows, countOf("ws_mall_stock_flow"), "拒绝不得留下回库流水");
        assertEquals(refunds, countOf("ws_mall_refund"), "拒绝不得留下退款单");
    }

    @Test
    @DisplayName("R1-P0 整单取消审核通过后：订单立即离开待履约，拣货不再可能")
    void cancelRefundAuditClosesFulfillmentWindow() {
        MallOrderVo order = orderService.create(USER, checkout(ADDR_WH1_ONLY, uuid(), SKU1, 2));
        String orderNo = order.getOrderNo();
        paySimService.pay(USER, orderNo);
        fulfillmentService.ensureTask(orderNo);
        var applied = afterSaleService.apply(USER,
                applyBo(orderNo, MallEnum.AfterSaleType.CANCEL_REFUND.getValue(), SKU1, null));
        afterSaleService.audit(WH_OPERATOR, new com.jbk.tool.data.mall.bo.MallAfterSaleAuditBo()
                .setAfterSaleNo(applied.getAfterSaleNo()).setApproved(true).setRemark("同意取消"));

        assertEquals(MallEnum.OrderStatus.CANCELLED.getValue(), orderStatusOf(orderNo),
                "审核通过即把订单推出已支付待履约");
        // 拣货的唯一前置是订单精确处于 2；窗口没关上，钱退了货还能照常发出
        assertThrows(JbkException.class, () -> fulfillmentService.pick(WH_OPERATOR,
                new com.jbk.tool.data.mall.bo.MallFulfillActionBo().setOrderNo(orderNo)),
                "取消通过后不得再拣货");
        succeedRefund(applied.getAfterSaleNo());
        assertEquals(MallEnum.OrderStatus.REFUNDED.getValue(), orderStatusOf(orderNo),
                "退款成功后订单转已全额退款");
    }

    @Test
    @DisplayName("R1-P1 两笔部分退款并发推进：订单最终必须转已全额退款")
    void concurrentPartialRefundsStillReachFullyRefunded() throws Exception {
        String orderNo = signedOrder(2);
        String noA = returnUpToInspect(orderNo, 1,
                MallEnum.InspectResult.PASS_RESELLABLE.getValue());
        var appliedB = afterSaleService.apply(USER,
                applyBo(orderNo, MallEnum.AfterSaleType.RETURN_REFUND.getValue(), SKU1, 1));
        String noB = appliedB.getAfterSaleNo();
        var boB = new com.jbk.tool.data.mall.bo.MallAfterSaleNoBo().setAfterSaleNo(noB);
        afterSaleService.audit(WH_OPERATOR, new com.jbk.tool.data.mall.bo.MallAfterSaleAuditBo()
                .setAfterSaleNo(noB).setApproved(true).setRemark("同意"));
        afterSaleService.confirmReceive(WH_OPERATOR, boB);
        afterSaleService.inspect(WH_OPERATOR,
                new com.jbk.tool.data.mall.bo.MallAfterSaleInspectBo().setAfterSaleNo(noB)
                        .setInspectResult(MallEnum.InspectResult.PASS_RESELLABLE.getValue())
                        .setInspectRemark("可售"));

        Long factA = recordSuccessFact(noA);
        Long factB = recordSuccessFact(noB);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        for (Long factId : List.of(factA, factB)) {
            pool.submit(() -> {
                go.await();
                return refundFactService.process(factId).code();
            });
        }
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        assertEquals(MallEnum.RefundStatus.SUCCESS.getValue(), refundStatusOf(noA));
        assertEquals(MallEnum.RefundStatus.SUCCESS.getValue(), refundStatusOf(noB));
        // 两笔各半的退款合计等于商品实付；非锁定读会让双方都只看见自己那一半
        assertEquals(MallEnum.OrderStatus.REFUNDED.getValue(), orderStatusOf(orderNo),
                "累计退满必须转已全额退款");
    }

    @Test
    @DisplayName("R1-P0 Refund-Sim 越权：非本仓操作员不得触发出账，且零资金副作用")
    void refundSimRejectsForeignWarehouseOperator() {
        String orderNo = signedOrder(2);
        String no = returnUpToInspect(orderNo, 1,
                MallEnum.InspectResult.PASS_RESELLABLE.getValue());
        assertThrows(JbkException.class,
                () -> refundSimService.refund(OTHER_WH_OPERATOR, no),
                "非本仓操作员不得发起模拟退款");
        assertEquals(MallEnum.RefundStatus.PENDING.getValue(), refundStatusOf(no),
                "越权被拒后退款单必须仍是待退款");
        assertEquals(MallEnum.AfterSaleStatus.REFUNDING.getValue(), afterSaleStatusOf(no));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_mall_refund_fact", Long.class),
                "越权被拒不得留下任何退款事实");
    }

    @Test
    @DisplayName("R1-P1 换货完成必须落完成节点轨迹，时间线不得停在换货补发中")
    void exchangeCompletionWritesCompletedTrace() {
        String orderNo = signedOrder(2);
        String no = returnUpToInspect(orderNo, 1, MallEnum.InspectResult.PASS_RESELLABLE.getValue(),
                MallEnum.AfterSaleType.EXCHANGE.getValue());
        String exchangeNo = jdbc.queryForObject(
                "SELECT o.ORDER_NO FROM ws_mall_order o JOIN ws_mall_after_sale a"
                        + " ON a.ID = o.SOURCE_AFTER_SALE_ID WHERE a.AFTER_SALE_NO=?",
                String.class, no);
        signReshipment(exchangeNo);

        assertEquals(MallEnum.AfterSaleStatus.COMPLETED.getValue(), afterSaleStatusOf(no));
        Long traces = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_mall_after_sale_trace WHERE BIZ_IDEMPOTENCY_KEY=?",
                Long.class, "MAT:" + no + ":" + MallEnum.AfterSaleStatus.COMPLETED.getValue());
        assertEquals(1L, traces, "换货完成节点轨迹恰一条");
    }

    @Test
    @DisplayName("R1-P1 中止换货补发：释放预占、取消补发单、售后转待人工")
    void abortExchangeReleasesReservationAndParksForManual() {
        String orderNo = signedOrder(2);
        String no = returnUpToInspect(orderNo, 1, MallEnum.InspectResult.PASS_RESELLABLE.getValue(),
                MallEnum.AfterSaleType.EXCHANGE.getValue());
        String exchangeNo = jdbc.queryForObject(
                "SELECT o.ORDER_NO FROM ws_mall_order o JOIN ws_mall_after_sale a"
                        + " ON a.ID = o.SOURCE_AFTER_SALE_ID WHERE a.AFTER_SALE_NO=?",
                String.class, no);
        long reservedBefore = reservedOf(WH1, SKU1);

        afterSaleService.abortExchange(WH_OPERATOR,
                new com.jbk.tool.data.mall.bo.MallAfterSaleAbortBo()
                        .setAfterSaleNo(no).setAbortReason("配送员已停用，补发无法送出"));

        assertEquals(MallEnum.AfterSaleStatus.NEED_MANUAL.getValue(), afterSaleStatusOf(no));
        assertEquals(MallEnum.OrderStatus.CANCELLED.getValue(), orderStatusOf(exchangeNo));
        assertEquals(reservedBefore - 1, reservedOf(WH1, SKU1), "换货预占必须被释放");
        assertEquals(1L, flowCountOfType(MallEnum.StockFlowType.EXCHANGE_RELEASE.getValue()));
        // 已签收的补发单不是「送不出去」，中止必须被拒
        String second = returnUpToInspect(signedOrder(2), 1,
                MallEnum.InspectResult.PASS_RESELLABLE.getValue(),
                MallEnum.AfterSaleType.EXCHANGE.getValue());
        String signedExchange = jdbc.queryForObject(
                "SELECT o.ORDER_NO FROM ws_mall_order o JOIN ws_mall_after_sale a"
                        + " ON a.ID = o.SOURCE_AFTER_SALE_ID WHERE a.AFTER_SALE_NO=?",
                String.class, second);
        signReshipment(signedExchange);
        assertThrows(JbkException.class, () -> afterSaleService.abortExchange(WH_OPERATOR,
                new com.jbk.tool.data.mall.bo.MallAfterSaleAbortBo()
                        .setAfterSaleNo(second).setAbortReason("试图中止已完成的换货")));
    }

    // ==================== L1 多渠道物流履约与 Logistics-Sim ====================

    @Autowired
    private com.jbk.serve.service.mall.IMallLogisticsService logisticsService;
    @Autowired
    private com.jbk.serve.service.mall.IMallLogisticsFactService logisticsFactService;
    @Autowired
    private com.jbk.serve.service.mall.IMallShipmentService shipmentService;
    @Autowired
    private com.jbk.serve.mapper.mall.WsMallFulfillmentMapper fulfillMapper;
    @Autowired
    private com.jbk.serve.mapper.mall.WsMallShipmentMapper shipmentMapper;
    @Autowired
    private com.jbk.serve.mapper.mall.WsMallLogisticsEventMapper eventMapper;
    @Autowired
    private MallLogisticsOutboxWorker logisticsOutboxWorker;
    @Autowired
    private MallLogisticsEventRetryWorker logisticsEventRetryWorker;

    /** Logistics-Sim 承运商编码：与自营的 SELF 分开。 */
    private static final String SIM = LogisticsSimAdapter.PROVIDER_CODE;

    /** 待安排发运 → 已提交第三方运单请求（outbox 已登记，运单号尚未取得）。 */
    private String readyThirdParty() {
        String orderNo = readyToAssign();
        logisticsService.createShipment(WH_OPERATOR,
                new com.jbk.tool.data.mall.bo.MallShipmentCreateBo()
                        .setOrderNo(orderNo).setProviderCode(SIM));
        return orderNo;
    }

    /** 跑一轮 outbox：运单号写回包裹后返回它。 */
    private String withWaybill(String orderNo) {
        logisticsOutboxWorker.runOnce();
        return jdbc.queryForObject(
                "SELECT WAYBILL_NO FROM ws_mall_shipment WHERE ORDER_NO=? AND DIRECTION=1",
                String.class, orderNo);
    }

    private int fulfillModeOf(String orderNo) {
        return jdbc.queryForObject(
                "SELECT FULFILL_MODE FROM ws_mall_fulfillment WHERE ORDER_NO=?", Integer.class,
                orderNo);
    }

    private int shipmentStatusOf(String orderNo) {
        return jdbc.queryForObject(
                "SELECT SHIPMENT_STATUS FROM ws_mall_shipment WHERE ORDER_NO=? AND DIRECTION=1",
                Integer.class, orderNo);
    }

    /** 投递一条模拟承运方事件（已验签，直接落事实并推进）。 */
    private IMallPayApplyTx.Outcome simEvent(String waybill, String eventKey, String state,
                                             String eventTime) {
        var event = logisticsFactService.recordFact(SIM, 3, eventKey, waybill, state, eventTime,
                "模拟事件", 1, "{\"k\":\"" + eventKey + "\"}");
        return logisticsFactService.process(event.getId());
    }

    @Test
    @DisplayName("L1① 自营分配后不得再创建第三方运单，且不留任何包裹/出站残渣")
    void selfAssignedOrderRefusesThirdPartyShipment() {
        String orderNo = readyToAssign();
        selfDeliveryService.assign(WH_OPERATOR, new com.jbk.tool.data.mall.bo.MallFulfillAssignBo()
                .setOrderNo(orderNo).setCourierId(COURIER_IN));
        assertEquals(MallEnum.FulfillMode.SELF_DELIVERY.getValue(), fulfillModeOf(orderNo));
        long shipmentsBefore = countOf("ws_mall_shipment");

        assertThrows(JbkException.class, () -> logisticsService.createShipment(WH_OPERATOR,
                new com.jbk.tool.data.mall.bo.MallShipmentCreateBo()
                        .setOrderNo(orderNo).setProviderCode(SIM)));

        assertEquals(MallEnum.FulfillMode.SELF_DELIVERY.getValue(), fulfillModeOf(orderNo),
                "拒绝路径不得改渠道");
        assertEquals(shipmentsBefore, countOf("ws_mall_shipment"), "拒绝路径不得建第二个包裹");
        assertEquals(0L, countOf("ws_mall_logistics_outbox"), "拒绝路径不得登记出站动作");
    }

    @Test
    @DisplayName("L1② 已创建第三方运单后不得再分配自营配送员")
    void thirdPartyOrderRefusesSelfCourier() {
        String orderNo = readyThirdParty();
        assertEquals(MallEnum.FulfillMode.THIRD_PARTY.getValue(), fulfillModeOf(orderNo));

        assertThrows(JbkException.class, () -> selfDeliveryService.assign(WH_OPERATOR,
                new com.jbk.tool.data.mall.bo.MallFulfillAssignBo()
                        .setOrderNo(orderNo).setCourierId(COURIER_IN)));

        assertEquals(MallEnum.FulfillMode.THIRD_PARTY.getValue(), fulfillModeOf(orderNo));
        assertNull(jdbc.queryForObject(
                "SELECT COURIER_ID FROM ws_mall_fulfillment WHERE ORDER_NO=?", Long.class, orderNo),
                "第三方单不得被写入配送员");
        assertEquals(1L, countOf("ws_mall_shipment"), "拒绝路径不得建第二个包裹");
    }

    @Test
    @DisplayName("L1③ 并发同时走两条渠道，只有一条成功，渠道值唯一")
    void concurrentChannelChoiceHasSingleWinner() throws Exception {
        String orderNo = readyToAssign();
        var ready = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var ok = new AtomicInteger();
        java.util.concurrent.Callable<Void> self = () -> {
            ready.await();
            try {
                selfDeliveryService.assign(WH_OPERATOR,
                        new com.jbk.tool.data.mall.bo.MallFulfillAssignBo()
                                .setOrderNo(orderNo).setCourierId(COURIER_IN));
                ok.incrementAndGet();
            }
            catch (RuntimeException ignored) {
                // 输方必然抛：渠道已被另一条链冻结
            }
            return null;
        };
        java.util.concurrent.Callable<Void> third = () -> {
            ready.await();
            try {
                logisticsService.createShipment(WH_OPERATOR,
                        new com.jbk.tool.data.mall.bo.MallShipmentCreateBo()
                                .setOrderNo(orderNo).setProviderCode(SIM));
                ok.incrementAndGet();
            }
            catch (RuntimeException ignored) {
                // 同上
            }
            return null;
        };
        var f1 = pool.submit(self);
        var f2 = pool.submit(third);
        ready.countDown();
        f1.get();
        f2.get();
        pool.shutdownNow();

        assertEquals(1, ok.get(), "两条渠道并发时必须恰好一方成功");
        int mode = fulfillModeOf(orderNo);
        assertTrue(mode == MallEnum.FulfillMode.SELF_DELIVERY.getValue()
                || mode == MallEnum.FulfillMode.THIRD_PARTY.getValue());
        assertEquals(1L, countOf("ws_mall_shipment"), "无论谁赢都只有一个包裹");
    }

    @Test
    @DisplayName("L1④ 出站动作重放只产生一个运单号，包裹不被第二次覆盖")
    void outboxReplayYieldsExactlyOneWaybill() {
        String orderNo = readyThirdParty();
        assertEquals(1L, countOf("ws_mall_logistics_outbox"));
        assertNull(withWaybillBeforeWorker(orderNo), "Worker 跑之前不得有运单号");

        String first = withWaybill(orderNo);
        assertNotNull(first);
        assertEquals(MallEnum.ShipmentStatus.ACCEPTED.getValue(), shipmentStatusOf(orderNo));

        // 人为把动作打回待处理，模拟「回执已到但标记处理失败后被重投」
        jdbc.update("UPDATE ws_mall_logistics_outbox SET PROCESSING_STATUS=1, NEXT_RETRY_TIME=NULL,"
                + " LEASE_UNTIL=NULL WHERE SHIPMENT_ID=(SELECT ID FROM ws_mall_shipment"
                + " WHERE ORDER_NO=? AND DIRECTION=1)", orderNo);
        logisticsOutboxWorker.runOnce();

        assertEquals(first, withWaybillBeforeWorker(orderNo), "重放不得写出第二个运单号");
        assertEquals(1L, countOf("ws_mall_shipment"));
        assertEquals(1L, countOf("ws_mall_logistics_outbox"));
    }

    private String withWaybillBeforeWorker(String orderNo) {
        return jdbc.queryForObject(
                "SELECT WAYBILL_NO FROM ws_mall_shipment WHERE ORDER_NO=? AND DIRECTION=1",
                String.class, orderNo);
    }

    @Test
    @DisplayName("L1⑤ 承运方回执与已写入运单号不一致时拒绝覆盖并转人工")
    void mismatchedWaybillIsRefusedAndParked() {
        String orderNo = readyThirdParty();
        String first = withWaybill(orderNo);
        assertNotNull(first);

        // 篡改：把包裹上的运单号改成别的，再把动作打回重投
        jdbc.update("UPDATE ws_mall_shipment SET WAYBILL_NO='SIMWB-TAMPERED'"
                + " WHERE ORDER_NO=? AND DIRECTION=1", orderNo);
        jdbc.update("UPDATE ws_mall_logistics_outbox SET PROCESSING_STATUS=1, NEXT_RETRY_TIME=NULL,"
                + " LEASE_UNTIL=NULL");
        logisticsOutboxWorker.runOnce();

        assertEquals("SIMWB-TAMPERED", withWaybillBeforeWorker(orderNo), "不得覆盖已写入的运单号");
        assertEquals(MallEnum.LogisticsProcessing.NEED_MANUAL.getValue(),
                (int) jdbc.queryForObject(
                        "SELECT PROCESSING_STATUS FROM ws_mall_logistics_outbox", Integer.class),
                "运单号分叉是确定性失败，必须转人工而不是无限重试");
    }

    @Test
    @DisplayName("L1⑥ 乱序与迟到事件不把包裹推回更早的状态")
    void outOfOrderEventsNeverMoveBackwards() {
        String orderNo = readyThirdParty();
        String waybill = withWaybill(orderNo);

        simEvent(waybill, "EV-PICK", MallEnum.LogisticsEventState.PICKED_UP, "20260811100000");
        simEvent(waybill, "EV-DELIV", MallEnum.LogisticsEventState.DELIVERED, "20260811180000");
        assertEquals(MallEnum.ShipmentStatus.DELIVERED.getValue(), shipmentStatusOf(orderNo));
        assertEquals(MallEnum.FulfillStatus.ARRIVED.getValue(), fulfillStatusOf(orderNo));

        // 迟到的运输中事件：事实要留证，状态一步也不许退
        var late = simEvent(waybill, "EV-LATE", MallEnum.LogisticsEventState.IN_TRANSIT,
                "20260811120000");
        assertEquals(IMallPayApplyTx.Code.ALREADY, late.code());
        assertEquals(MallEnum.ShipmentStatus.DELIVERED.getValue(), shipmentStatusOf(orderNo),
                "迟到事件不得把已送达推回运输中");
        assertEquals(MallEnum.FulfillStatus.ARRIVED.getValue(), fulfillStatusOf(orderNo));
        assertEquals(3L, countOf("ws_mall_logistics_event"), "迟到事件仍要留证");
    }

    @Test
    @DisplayName("L1⑦ 同一承运方事件键重复投递只落一条事实，改参一律拒绝")
    void duplicateProviderEventKeyIsIdempotentAndRejectsDrift() {
        String orderNo = readyThirdParty();
        String waybill = withWaybill(orderNo);

        simEvent(waybill, "EV-1", MallEnum.LogisticsEventState.PICKED_UP, "20260811100000");
        simEvent(waybill, "EV-1", MallEnum.LogisticsEventState.PICKED_UP, "20260811100000");
        assertEquals(1L, countOf("ws_mall_logistics_event"), "同键重投不得落第二条事实");

        // 同键换正文：静默返回旧行等于替篡改盖章
        assertThrows(JbkException.class, () -> simEvent(waybill, "EV-1",
                MallEnum.LogisticsEventState.DELIVERED, "20260811100000"));
        assertEquals(1L, countOf("ws_mall_logistics_event"));
        assertEquals(MallEnum.ShipmentStatus.PICKED_UP.getValue(), shipmentStatusOf(orderNo));
    }

    @Test
    @DisplayName("L1⑧ 未知事件状态不推进任何状态，落库即转人工")
    void unknownEventStateNeverAdvances() {
        String orderNo = readyThirdParty();
        String waybill = withWaybill(orderNo);
        int before = shipmentStatusOf(orderNo);

        var outcome = simEvent(waybill, "EV-X", "TELEPORTED", "20260811100000");
        assertEquals(IMallPayApplyTx.Code.RECONCILE, outcome.code());
        assertEquals(before, shipmentStatusOf(orderNo), "读不懂的状态不得推进包裹");
        assertEquals(MallEnum.FulfillStatus.PENDING_FETCH.getValue(), fulfillStatusOf(orderNo));
        assertEquals(MallEnum.LogisticsProcessing.NEED_MANUAL.getValue(),
                (int) jdbc.queryForObject(
                        "SELECT PROCESSING_STATUS FROM ws_mall_logistics_event WHERE"
                                + " PROVIDER_EVENT_KEY='EV-X'", Integer.class));
    }

    @Test
    @DisplayName("L1⑨ 承运方说已签收也只推到「已送达待确认」，7 恒由用户确认")
    void carrierSignedNeverCompletesTheOrder() {
        String orderNo = readyThirdParty();
        String waybill = withWaybill(orderNo);

        simEvent(waybill, "EV-SIGNED", MallEnum.LogisticsEventState.SIGNED, "20260811180000");

        assertEquals(MallEnum.FulfillStatus.ARRIVED.getValue(), fulfillStatusOf(orderNo),
                "承运方签收只到 6，绝不写 7");
        assertEquals(MallEnum.OrderStatus.FULFILLING.getValue(), orderStatusOf(orderNo),
                "订单不得被承运方事件推成已完成");
        assertNull(jdbc.queryForObject(
                "SELECT SIGN_TIME FROM ws_mall_fulfillment WHERE ORDER_NO=?", String.class,
                orderNo), "签收时间只能由用户确认时落");

        // 用户确认后才是 7
        fulfillmentService.signByUser(USER, new com.jbk.tool.data.mall.bo.MallFulfillSignBo()
                .setOrderNo(orderNo).setSignMethod(1));
        assertEquals(MallEnum.FulfillStatus.SIGNED.getValue(), fulfillStatusOf(orderNo));
        assertEquals(MallEnum.OrderStatus.COMPLETED.getValue(), orderStatusOf(orderNo));
    }

    @Test
    @DisplayName("L1⑩ 物流异常只转人工留证，不自动改包裹与履约状态")
    void logisticsExceptionParksForManualWithoutStateChange() {
        String orderNo = readyThirdParty();
        String waybill = withWaybill(orderNo);
        simEvent(waybill, "EV-TRANSIT", MallEnum.LogisticsEventState.IN_TRANSIT, "20260811120000");
        int fulfillBefore = fulfillStatusOf(orderNo);

        var outcome = simEvent(waybill, "EV-ERR", MallEnum.LogisticsEventState.EXCEPTION,
                "20260811130000");
        assertEquals(IMallPayApplyTx.Code.ALREADY, outcome.code());
        assertEquals(MallEnum.ShipmentStatus.NEED_MANUAL.getValue(), shipmentStatusOf(orderNo));
        assertEquals(fulfillBefore, fulfillStatusOf(orderNo), "异常不得顺带改履约状态");
        assertTrue(countOf("ws_domain_event") > 0, "异常必须留下审计");
    }

    @Test
    @DisplayName("L1⑪ 运单号匹配不到有效包裹的事实一律留证转人工，不推进任何状态")
    void factWithoutMatchingShipmentIsParked() {
        String orderNo = readyThirdParty();
        String waybill = withWaybill(orderNo);
        int before = shipmentStatusOf(orderNo);

        var event = logisticsFactService.recordFact(SIM, 3, "EV-GHOST", "SIMWB-GHOST",
                MallEnum.LogisticsEventState.DELIVERED, "20260811180000", "幽灵单", 1, "{}");
        assertEquals(MallEnum.LogisticsProcessing.NEED_MANUAL.getValue(),
                event.getProcessingStatus(), "落库时就该被判为不可推进");
        var outcome = logisticsFactService.process(event.getId());
        assertEquals(IMallPayApplyTx.Code.RECONCILE, outcome.code());

        assertEquals(before, shipmentStatusOf(orderNo), "幽灵事实不得碰到真包裹");
        assertNotNull(waybill);
    }

    @Test
    @DisplayName("L1⑫ 第三方任务对配送端完全不可见：列表与详情都当作不存在")
    void thirdPartyTaskIsInvisibleToCouriers() {
        String selfNo = readyToAssign();
        selfDeliveryService.assign(WH_OPERATOR, new com.jbk.tool.data.mall.bo.MallFulfillAssignBo()
                .setOrderNo(selfNo).setCourierId(COURIER_IN));
        String thirdNo = readyThirdParty();

        var list = selfDeliveryService.listForCourier(COURIER_USER);
        assertEquals(1, list.size(), "配送端只应看到自营任务");
        assertEquals(selfNo, list.get(0).getOrderNo());

        // 详情也必须拒：能按单号直查等于列表过滤形同虚设
        assertThrows(JbkException.class,
                () -> selfDeliveryService.detailForCourier(COURIER_USER, thirdNo));
        assertThrows(JbkException.class, () -> selfDeliveryService.fetch(COURIER_USER,
                new com.jbk.tool.data.mall.bo.MallFulfillActionBo().setOrderNo(thirdNo)));
        assertEquals(MallEnum.FulfillStatus.PENDING_FETCH.getValue(), fulfillStatusOf(thirdNo),
                "被拒的取货不得推进第三方单");
    }

    @Test
    @DisplayName("L1⑬ 未注册承运商拒绝建单，且不留包裹与出站动作")
    void unknownProviderIsRefusedWithZeroSideEffect() {
        String orderNo = readyToAssign();

        assertThrows(JbkException.class, () -> logisticsService.createShipment(WH_OPERATOR,
                new com.jbk.tool.data.mall.bo.MallShipmentCreateBo()
                        .setOrderNo(orderNo).setProviderCode("NOT-REGISTERED")));

        assertEquals(MallEnum.FulfillMode.UNDECIDED.getValue(), fulfillModeOf(orderNo),
                "拒绝路径不得冻结渠道");
        assertEquals(0L, countOf("ws_mall_shipment"));
        assertEquals(0L, countOf("ws_mall_logistics_outbox"));
        assertEquals(MallEnum.FulfillStatus.PENDING_ASSIGN.getValue(), fulfillStatusOf(orderNo));
    }

    @Test
    @DisplayName("L1⑭ 重复提交同一第三方运单请求是幂等的，不产生第二个包裹或动作")
    void repeatedShipmentCreateIsIdempotent() {
        String orderNo = readyThirdParty();
        long traces = traceCountOf(orderNo);

        logisticsService.createShipment(WH_OPERATOR,
                new com.jbk.tool.data.mall.bo.MallShipmentCreateBo()
                        .setOrderNo(orderNo).setProviderCode(SIM));

        assertEquals(1L, countOf("ws_mall_shipment"));
        assertEquals(1L, countOf("ws_mall_logistics_outbox"));
        assertEquals(traces, traceCountOf(orderNo), "重复提交不得写第二条轨迹");
        assertEquals(MallEnum.FulfillStatus.PENDING_FETCH.getValue(), fulfillStatusOf(orderNo));
    }

    @Test
    @DisplayName("L1⑮ 渠道冻结的互斥判据在数据库层：第二次冻结必须影响 0 行")
    void channelFreezeIsMutuallyExclusiveAtTheDatabase() {
        String orderNo = readyToAssign();
        Long taskId = jdbc.queryForObject("SELECT ID FROM ws_mall_fulfillment WHERE ORDER_NO=?",
                Long.class, orderNo);
        assertNotNull(taskId);

        // 应用层那两句 if 只是提前给出好错误信息；真正的互斥必须由这条 UPDATE 的前态兜住。
        // 不直接观测它，去掉 SQL 里的 FULFILL_MODE = 0 时整套用例仍然全绿——
        // 而线上两个请求各自查到 0、各自冻结成不同渠道时，库里再也判不出哪条算数。
        assertEquals(1, fulfillMapper.casFreezeMode(taskId,
                MallEnum.FulfillMode.SELF_DELIVERY.getValue(), WH_OPERATOR, "20260811120000"),
                "首次冻结必须命中");
        assertEquals(0, fulfillMapper.casFreezeMode(taskId,
                MallEnum.FulfillMode.THIRD_PARTY.getValue(), WH_OPERATOR, "20260811120100"),
                "渠道已冻结后第二次冻结必须影响 0 行");
        assertEquals(MallEnum.FulfillMode.SELF_DELIVERY.getValue(), fulfillModeOf(orderNo),
                "输方不得改写已冻结的渠道");

        // 同渠道重复冻结同样必须 0 行：幂等由调用方的前置判断给出，不靠这条语句放行
        assertEquals(0, fulfillMapper.casFreezeMode(taskId,
                MallEnum.FulfillMode.SELF_DELIVERY.getValue(), WH_OPERATOR, "20260811120200"),
                "同渠道重复冻结也必须影响 0 行");
    }

    @Test
    @DisplayName("L1⑯ 逆向包裹的物流事实不得推进正向履约状态")
    void reverseShipmentNeverDrivesForwardFulfillment() {
        String orderNo = readyThirdParty();
        String forwardWaybill = withWaybill(orderNo);
        assertNotNull(forwardWaybill);
        com.jbk.tool.data.mall.po.WsMallFulfillment task = fulfillMapper.selectByOrderNoIncludingDeleted(orderNo);

        // 逆向包裹（退货回仓）：走同一批状态值，但它的「已送达」是到仓，不是到用户手上
        com.jbk.tool.data.mall.po.WsMallShipment back = shipmentService.ensureShipment(task,
                MallEnum.ShipmentDirection.RETURN.getValue(), 1, null, SIM,
                WH_OPERATOR, "20260811120000");
        jdbc.update("UPDATE ws_mall_shipment SET WAYBILL_NO='SIMWB-REVERSE', SHIPMENT_STATUS=2"
                + " WHERE ID=?", back.getId());
        int fulfillBefore = fulfillStatusOf(orderNo);
        assertEquals(MallEnum.FulfillStatus.PENDING_FETCH.getValue(), fulfillBefore);

        var outcome = simEvent("SIMWB-REVERSE", "EV-BACK",
                MallEnum.LogisticsEventState.DELIVERED, "20260811180000");

        // 包裹自己该动照动——逆向链的证据不能丢
        assertEquals(MallEnum.ShipmentStatus.DELIVERED.getValue(),
                (int) jdbc.queryForObject("SELECT SHIPMENT_STATUS FROM ws_mall_shipment WHERE ID=?",
                        Integer.class, back.getId()));
        assertEquals(IMallPayApplyTx.Code.APPLIED, outcome.code());
        // 但正向履约一步也不许动：退货到仓被读成「您的订单已送达」是最难被发现的那类错
        assertEquals(fulfillBefore, fulfillStatusOf(orderNo),
                "逆向包裹的送达事件不得推进正向履约状态");
        assertEquals(MallEnum.OrderStatus.FULFILLING.getValue(), orderStatusOf(orderNo));
    }

    @Test
    @DisplayName("L1⑰ 已逻辑删除的出站动作不得被认领（与扫描面同判据）")
    void logicallyDeletedOutboxActionIsNotClaimable() {
        String orderNo = readyThirdParty();
        Long actionId = jdbc.queryForObject(
                "SELECT o.ID FROM ws_mall_logistics_outbox o JOIN ws_mall_shipment s"
                        + " ON s.ID=o.SHIPMENT_ID WHERE s.ORDER_NO=?", Long.class, orderNo);
        assertNotNull(actionId);
        jdbc.update("UPDATE ws_mall_logistics_outbox SET DATA_STATUS=1 WHERE ID=?", actionId);

        int statusBefore = jdbc.queryForObject(
                "SELECT PROCESSING_STATUS FROM ws_mall_logistics_outbox WHERE ID=?",
                Integer.class, actionId);

        // 扫描面看不见它
        assertEquals(0, logisticsOutboxWorker.runOnce(), "已删除的动作不得进入扫描面");
        // 单独按 ID 处理也必须拒
        assertFalse(logisticsOutboxWorker.processOne(actionId),
                "已删除的动作不得被单独认领");

        // 只断言「没发出去」不够：认领语句缺 DATA_STATUS 时，它照样会把这行改成「处理中」
        // 并占住租约，只是随后被 selectById 的逻辑删除过滤挡下而不外呼。留下的是一行
        // 看着正在处理、实际永远不会被处理的动作——而扫描面上它已经看不见了。
        assertEquals(statusBefore, (int) jdbc.queryForObject(
                "SELECT PROCESSING_STATUS FROM ws_mall_logistics_outbox WHERE ID=?",
                Integer.class, actionId), "被拒的认领不得改动处理状态");
        assertNull(jdbc.queryForObject(
                "SELECT LEASE_UNTIL FROM ws_mall_logistics_outbox WHERE ID=?",
                String.class, actionId), "被拒的认领不得占住租约");
        assertNull(jdbc.queryForObject(
                "SELECT WAYBILL_NO FROM ws_mall_shipment WHERE ORDER_NO=? AND DIRECTION=1",
                String.class, orderNo), "被拒的认领不得产生运单号");
    }

    // ========== L1 实现后四维审查的整改回归（⑱~㉒） ==========

    @Test
    @DisplayName("L1⑱ 未打包的单不得创建运单，拒绝路径零副作用")
    void unpackedOrderCannotBeHandedToCarrier() {
        var task = paidOrderWithTask();
        String orderNo = task.getOrderNo();
        fulfillmentService.pick(WH_OPERATOR,
                new com.jbk.tool.data.mall.bo.MallFulfillActionBo().setOrderNo(orderNo));
        assertEquals(MallEnum.FulfillStatus.PENDING_PACK.getValue(), fulfillStatusOf(orderNo));

        JbkException denied = assertThrows(JbkException.class,
                () -> logisticsService.createShipment(WH_OPERATOR,
                        new com.jbk.tool.data.mall.bo.MallShipmentCreateBo()
                                .setOrderNo(orderNo).setProviderCode(SIM)));
        assertTrue(denied.getMessage().contains("拣货与打包"), "实际=" + denied.getMessage());

        // 少这道闸时：渠道被冻结、包裹与 outbox 全建出，而推进那句 if 判不中于是静默跳过，
        // 接口照常返回成功；等承运方事件回来把履约从 2 顶到 4，打包节点凭空消失，
        // 仓库此后点「打包完成」CAS 前态恒对不上，这一单再也打不了包
        assertEquals(MallEnum.FulfillMode.UNDECIDED.getValue(), fulfillModeOf(orderNo),
                "拒绝路径不得冻结渠道");
        assertEquals(0L, countOf("ws_mall_shipment"));
        assertEquals(0L, countOf("ws_mall_logistics_outbox"));
        assertEquals(MallEnum.FulfillStatus.PENDING_PACK.getValue(), fulfillStatusOf(orderNo));

        // 正向对照：打包后同一动作成功
        fulfillmentService.pack(WH_OPERATOR,
                new com.jbk.tool.data.mall.bo.MallFulfillActionBo().setOrderNo(orderNo));
        logisticsService.createShipment(WH_OPERATOR,
                new com.jbk.tool.data.mall.bo.MallShipmentCreateBo()
                        .setOrderNo(orderNo).setProviderCode(SIM));
        assertEquals(MallEnum.FulfillStatus.PENDING_FETCH.getValue(), fulfillStatusOf(orderNo));
    }

    @Test
    @DisplayName("L1⑲ 履约尚未安排发运却收到承运方事件：转人工，且包裹一步不动")
    void carrierEventOnUnarrangedTaskIsParked() {
        String orderNo = readyThirdParty();
        String waybill = withWaybill(orderNo);
        // 构造「绕过创建入口」的错位：把履约打回待打包，包裹留在已受理
        jdbc.update("UPDATE ws_mall_fulfillment SET FULFILL_STATUS=2 WHERE ORDER_NO=?", orderNo);
        int shipBefore = shipmentStatusOf(orderNo);

        var outcome = simEvent(waybill, "EV-EARLY", MallEnum.LogisticsEventState.PICKED_UP,
                "20260811100000");

        assertEquals(IMallPayApplyTx.Code.RECONCILE, outcome.code());
        // 闸必须在任何写之前：事务B 用 return 收尾而不是抛异常，写完再 return 是不回滚的
        assertEquals(shipBefore, shipmentStatusOf(orderNo), "转人工前不得已经推进包裹");
        assertEquals(2, fulfillStatusOf(orderNo), "履约状态不得被顶走");
    }

    @Test
    @DisplayName("L1⑳ 配送端直查第三方单只答「不存在」，不泄露它已到哪个阶段")
    void thirdPartyTaskLeaksNothingToCouriers() {
        String thirdNo = readyThirdParty();

        // 弱断言（只断言抛异常）会放过这条：第三方单在状态 4 时 courierId 为 null，
        // 分配证据校验先抛「已达分配阶段却无配送员，请人工核查」——那句话同时告诉了
        // 提问者「这个单号存在」和「它已经走到了分配之后」
        for (String message : new String[] {
                assertThrows(JbkException.class,
                        () -> selfDeliveryService.detailForCourier(COURIER_USER, thirdNo))
                        .getMessage(),
                assertThrows(JbkException.class, () -> selfDeliveryService.fetch(COURIER_USER,
                        new com.jbk.tool.data.mall.bo.MallFulfillActionBo().setOrderNo(thirdNo)))
                        .getMessage(),
                assertThrows(JbkException.class, () -> selfDeliveryService.arrive(COURIER_USER,
                        new com.jbk.tool.data.mall.bo.MallFulfillActionBo().setOrderNo(thirdNo)))
                        .getMessage() }) {
            assertEquals("配送任务不存在", message, "配送端出口不得泄露第三方单的存在与阶段");
        }
    }

    @Test
    @DisplayName("L1㉑ 租约被接管后，旧持有者的结论不得落库覆盖新结论")
    void staleHolderCannotOverwriteAnotherHoldersVerdict() {
        String orderNo = readyThirdParty();
        String waybill = withWaybill(orderNo);
        var event = logisticsFactService.recordFact(SIM, 3, "EV-LEASE", waybill,
                MallEnum.LogisticsEventState.PICKED_UP, "20260811100000", "租约测试", 1, "{}");

        // 模拟：W2 已接管并把它标成需人工（写下自己的结论），随后 W1 苏醒
        jdbc.update("UPDATE ws_mall_logistics_event SET PROCESSING_STATUS=5,"
                + " LAST_ERROR='W2 判定的错位' WHERE ID=?", event.getId());

        // 必须调 mapper 本身。自己拼一条带 AND PROCESSING_STATUS=2 的 SQL 只是在测我手写的
        // 那句话——被测代码有没有这个前态，那样的用例一个字都证明不了。
        assertEquals(0, eventMapper.markProcessed(event.getId(), "20260811120000"),
                "旧持有者的 markProcessed 不得落库");
        assertEquals(0, eventMapper.markRetry(event.getId(), "作废的重试", "20260811120000"),
                "旧持有者的 markRetry 不得落库");

        assertEquals(MallEnum.LogisticsProcessing.NEED_MANUAL.getValue(),
                (int) jdbc.queryForObject(
                        "SELECT PROCESSING_STATUS FROM ws_mall_logistics_event WHERE ID=?",
                        Integer.class, event.getId()),
                "需人工标记不得被抹掉");
        assertNotNull(jdbc.queryForObject(
                "SELECT LAST_ERROR FROM ws_mall_logistics_event WHERE ID=?", String.class,
                event.getId()), "错误原因不得被置空——置空后它从人工对账面也消失了");
    }

    @Test
    @DisplayName("L1㉒ 包裹推进被并发抢占时判为 LOST 并重投，绝不标成已处理")
    void concurrentlyLostAdvanceIsRetriedNotSwallowed() {
        String orderNo = readyThirdParty();
        String waybill = withWaybill(orderNo);
        var ship = shipmentMapper.selectByOrderNo(orderNo).get(0);

        // 手里这份是旧副本（版本号已被另一条并发事实顶走），但前态仍朝前
        int staleVersion = ship.getVersion();
        jdbc.update("UPDATE ws_mall_shipment SET VERSION = VERSION + 1 WHERE ID=?", ship.getId());

        var moved = shipmentService.advance(ship, MallEnum.ShipmentStatus.PICKED_UP.getValue(),
                "PICKUP_TIME", WH_OPERATOR, "20260811100000");
        assertEquals(IMallShipmentService.Advance.LOST, moved,
                "前态仍朝前却没推动 = 被并发抢占，必须与「重放」区分开");

        // 对照：真正的重放（包裹已在更后的状态）判 STALE
        jdbc.update("UPDATE ws_mall_shipment SET SHIPMENT_STATUS=5 WHERE ID=?", ship.getId());
        var replay = shipmentService.advance(
                shipmentMapper.selectById(ship.getId()),
                MallEnum.ShipmentStatus.PICKED_UP.getValue(), "PICKUP_TIME", WH_OPERATOR,
                "20260811100000");
        assertEquals(IMallShipmentService.Advance.STALE, replay, "重放仍应判 STALE");
        assertTrue(staleVersion > 0);
    }
}
