package com.jbk.serve.service.delivery.impl;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.delivery.WsDeliveryAppealMapper;
import com.jbk.serve.mapper.delivery.WsDeliveryExceptionMapper;
import com.jbk.serve.mapper.delivery.WsDeliveryMediaMapper;
import com.jbk.serve.mapper.delivery.WsDeliveryTaskMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.mapper.user.WsCourierMapper;
import com.jbk.serve.service.delivery.IAdminDeliveryService;
import com.jbk.serve.service.delivery.IDeliveryAppealTxService;
import com.jbk.serve.service.message.IWsMessageService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.delivery.bo.AdminDeliveryAppealBo;
import com.jbk.tool.data.delivery.vo.AdminDeliveryAppealEvidenceVo;
import com.jbk.tool.data.delivery.vo.AdminDeliveryAppealItemVo;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-215 管理端申诉「案件维度聚合」的<b>真实 MySQL</b> 集成测试（搭法对齐 DeliveryOrderTxDbTest）。
 *
 * <p>聚合必须发生在 SQL 层，因此只有真库能证：窗口函数选代表行、ONLY_FULL_GROUP_BY 兼容、
 * MyBatis-Plus 自动派生的 count 统计案件数而非申诉条数、跨页边界不重不漏。
 * 这些恰恰是纯单测 Mock 不了的部分。</p>
 *
 * <p>钉住的事实：</p>
 * <ol>
 *   <li>同任务多条申诉收敛为一行，统计列与代表申诉取值正确；</li>
 *   <li>无活跃申诉时代表最近一条终态，activeAppealId 为空；</li>
 *   <li>待处理案件排前，其余按最近申诉时间倒序；</li>
 *   <li>状态筛选作用于<b>当前状态</b>，历史出现过的状态不参与命中；</li>
 *   <li>分页 total = 案件数，跨页不重复不遗漏；</li>
 *   <li>证据详情下发同任务申诉往来（正序、脱敏），断链时不下发。</li>
 * </ol>
 *
 * <p>无 Docker 环境自动跳过。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = AdminDeliveryAppealAggregateDbTest.Ctx.class)
class AdminDeliveryAppealAggregateDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_appeal_it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static final long USER_ID = 6L;
    private static final long STATION_ID = 41L;
    private static final long COURIER_ID = 3L;
    private static final long EMPLOYEE_ID = 5L;
    private static final String USER_PHONE = "13900001111";

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
            // 与生产 MybatisPlusConfig 同一份分页插件：聚合分页的 total 由它自动派生 count，
            // 缺了插件本测试就退化成「查全表」，验不到分页边界
            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
            factory.setPlugins(interceptor);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath:mapper/*/*.xml"));
            return new SqlSessionTemplate(factory.getObject());
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
        MapperFactoryBean<WsDeliveryExceptionMapper> wsDeliveryExceptionMapper(SqlSessionTemplate t) {
            return mapper(WsDeliveryExceptionMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsDeliveryMediaMapper> wsDeliveryMediaMapper(SqlSessionTemplate t) {
            return mapper(WsDeliveryMediaMapper.class, t);
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
        MapperFactoryBean<WsCourierMapper> wsCourierMapper(SqlSessionTemplate t) {
            return mapper(WsCourierMapper.class, t);
        }

        private static <M> MapperFactoryBean<M> mapper(Class<M> type, SqlSessionTemplate template) {
            MapperFactoryBean<M> bean = new MapperFactoryBean<>(type);
            bean.setSqlSessionTemplate(template);
            return bean;
        }

        /** 本测试只验证查询投影：消息/审计/裁决事务不在被测路径上，用 Mock 隔离。 */
        @Bean
        IWsMessageService messageService() {
            return Mockito.mock(IWsMessageService.class);
        }

        @Bean
        IWsDomainEventService domainEventService() {
            return Mockito.mock(IWsDomainEventService.class);
        }

        @Bean
        IDeliveryAppealTxService appealTxService() {
            return Mockito.mock(IDeliveryAppealTxService.class);
        }

        @Bean
        IAdminDeliveryService adminDeliveryService(WsDeliveryTaskMapper taskMapper,
                                                   WsDeliveryAppealMapper appealMapper,
                                                   WsDeliveryExceptionMapper exceptionMapper,
                                                   WsDeliveryMediaMapper mediaMapper,
                                                   WsOrderMapper orderMapper,
                                                   WsWalletFlowMapper walletFlowMapper,
                                                   WsCourierMapper courierMapper,
                                                   IWsMessageService messageService,
                                                   IWsDomainEventService domainEventService,
                                                   IDeliveryAppealTxService appealTxService) {
            return new AdminDeliveryServiceImpl(taskMapper, appealMapper, exceptionMapper, mediaMapper,
                    orderMapper, walletFlowMapper, courierMapper, messageService, domainEventService,
                    appealTxService);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    @Autowired
    private IAdminDeliveryService adminService;
    @Autowired
    private WsDeliveryAppealMapper appealMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        DeliveryDbSchema.createAll(jdbc);
        DeliveryDbSchema.truncateAll(jdbc);
        jdbc.update("INSERT INTO ws_station(ID,STATION_NAME,STATION_CODE,STATION_STATUS) VALUES(?,?,?,1)",
                STATION_ID, "测试站", "ST-41");
        jdbc.update("INSERT INTO ws_user(ID,DATA_STATUS,USER_NAME,USER_PHONE,USER_STATUS) VALUES(?,0,?,?,1)",
                USER_ID, "张三", USER_PHONE);
        jdbc.update("INSERT INTO ws_courier(ID,DATA_STATUS,USER_ID,COURIER_NAME,COURIER_PHONE,COURIER_STATUS) "
                + "VALUES(?,0,?,?,?,2)", COURIER_ID, 21L, "李配送", "13700002222");
        jdbc.update("INSERT INTO api_employee(ID,DATA_STATUS,LOGIN_NAME,EMPLOYEE_NAME) VALUES(?,0,?,?)",
                EMPLOYEE_ID, "ops01", "运营小王");
    }

    // ==================== 种子构造 ====================

    /** 已签收形态的订单+任务（共键、金额恒等式、三照齐全均成立），taskStatus 由调用方指定。 */
    private void seedCase(long taskId, long orderId, String no, int taskStatus, int orderStatus) {
        jdbc.update("INSERT INTO ws_order(ID,DATA_STATUS,CREATE_TIME,ORDER_NO,ORDER_TYPE,USER_ID,STATION_ID,"
                        + "CARD_ID,ORDER_AMOUNT,PAY_WAY,ORDER_STATUS) VALUES(?,0,?,?,3,?,?,100,4200,2,?)",
                orderId, "20260724100000", "WD" + no, USER_ID, STATION_ID, orderStatus);
        jdbc.update("INSERT INTO ws_delivery_task(ID,DATA_STATUS,CREATE_TIME,TASK_NO,ORDER_ID,USER_ID,STATION_ID,"
                        + "COURIER_ID,WATER_TYPE,CONTAINER_SPEC,DELIVERY_COUNT,PLAN_RETURN_COUNT,"
                        + "ACTUAL_DELIVERY_COUNT,ACTUAL_RETURN_COUNT,WATER_AMOUNT,DELIVERY_FEE,"
                        + "RECEIVE_ADDRESS,RECEIVE_PHONE,TASK_STATUS,VERSION,ACCEPT_TIME,DEPART_TIME,"
                        + "ARRIVE_TIME,SIGN_TIME,SIGN_PHOTOS,LOCATION_STATUS,APPEAL_DEADLINE) "
                        + "VALUES(?,0,?,?,?,?,?,?,'纯净水','20L桶',3,1,3,1,3600,600,?,?,?,6,?,?,?,?,?,2,?)",
                taskId, "20260724100000", "DT" + no, orderId, USER_ID, STATION_ID, COURIER_ID,
                "光谷软件园 A1 栋 502", "13900001111", taskStatus,
                "20260724101000", "20260724102000", "20260724103000", "20260724104000",
                threePhotos(), "20260725104000");
    }

    private String threePhotos() {
        return "[{\"type\":1,\"mediaKey\":\"DMA\",\"time\":\"20260724104000\"},"
                + "{\"type\":2,\"mediaKey\":\"DMB\",\"time\":\"20260724104000\"},"
                + "{\"type\":3,\"mediaKey\":\"DMC\",\"time\":\"20260724104000\"}]";
    }

    /** 待处理申诉（不得预写处理人/处理时间/处理结果，否则违反包A 申诉行核验）。 */
    private void seedPendingAppeal(long id, long taskId, long orderId, String createTime, String reason) {
        jdbc.update("INSERT INTO ws_delivery_appeal(ID,DATA_STATUS,CREATE_TIME,TASK_ID,ORDER_ID,USER_ID,"
                        + "APPEAL_REASON,APPEAL_DESC,RECEIVED_COUNT,APPEAL_STATUS) VALUES(?,0,?,?,?,?,?,?,2,1)",
                id, createTime, taskId, orderId, USER_ID, reason, "少送一桶，说明 " + id);
    }

    /** 终态申诉（裁决态必须齐备处理人/时间/结果）。 */
    private void seedDecidedAppeal(long id, long taskId, long orderId, String createTime,
                                   int status, String reason) {
        jdbc.update("INSERT INTO ws_delivery_appeal(ID,DATA_STATUS,CREATE_TIME,TASK_ID,ORDER_ID,USER_ID,"
                        + "APPEAL_REASON,APPEAL_DESC,RECEIVED_COUNT,APPEAL_STATUS,HANDLE_BY,HANDLE_TIME,"
                        + "HANDLE_RESULT) VALUES(?,0,?,?,?,?,?,?,2,?,?,?,?)",
                id, createTime, taskId, orderId, USER_ID, reason, "少送一桶，说明 " + id, status,
                EMPLOYEE_ID, createTime.substring(0, 8) + "180000", "裁决说明 " + id);
    }

    private AdminDeliveryAppealBo query(Integer appealStatus, String orderNo, long current, long size) {
        AdminDeliveryAppealBo bo = new AdminDeliveryAppealBo();
        bo.setCurrent(current);
        bo.setSize(size);
        bo.setAppealStatus(appealStatus);
        bo.setOrderNo(orderNo);
        return bo;
    }

    private AdminDeliveryAppealItemVo single(PageDataVo<AdminDeliveryAppealItemVo> page) {
        assertEquals(1L, page.getTotal(), "案件维度下同任务申诉必须收敛为一行");
        assertEquals(1, page.getList().size());
        return page.getList().get(0);
    }

    // ==================== T1：同任务多条申诉聚合为一个案件 ====================

    @Test
    void aggregatesMultipleAppealsOfOneTaskIntoSingleCaseRow() {
        seedCase(701L, 901L, "-AGG-01", 7, 4);
        seedDecidedAppeal(1001L, 701L, 901L, "20260720100000", 3, "QUANTITY");
        seedDecidedAppeal(1002L, 701L, 901L, "20260721100000", 5, "DAMAGE");
        seedPendingAppeal(1003L, 701L, 901L, "20260722100000", "QUALITY");

        AdminDeliveryAppealItemVo row = single(adminService.pageAppeals(query(null, null, 1, 20)));
        assertEquals(701L, row.getTaskId());
        assertEquals(3, row.getAppealCount(), "累计申诉次数=案件内申诉条数");
        assertEquals(1003L, row.getActiveAppealId(), "唯一键保证的那条待处理申诉");
        assertEquals(1003L, row.getAppealId(), "代表申诉=活跃申诉，裁决/证据接口继续用它");
        assertEquals(1, row.getAppealStatus(), "聚合行状态即案件当前状态");
        assertEquals("QUALITY", row.getAppealReason(), "原因/描述/实收全部取代表申诉");
        assertEquals("水质问题", row.getAppealReasonLabel());
        assertEquals("20260720100000", row.getFirstAppealTime());
        assertEquals("20260722100000", row.getLastAppealTime());
        assertEquals("20260722100000", row.getCreateTime(), "申诉时间列取代表申诉");
        assertEquals("WD-AGG-01", row.getOrderNo());
        assertEquals("DT-AGG-01", row.getTaskNo());
        assertEquals("139****1111", row.getUserMaskedPhone(), "脱敏在 Service 完成，SQL 只出原值别名");
        assertNull(row.getUserPhoneRaw(), "原始手机号不得出接口");
    }

    // ==================== T2：无活跃申诉时代表最近一条终态 ====================

    @Test
    void caseWithoutActiveAppealRepresentsLatestTerminalAppeal() {
        seedCase(702L, 902L, "-AGG-02", 5, 4);
        seedDecidedAppeal(1101L, 702L, 902L, "20260719090000", 3, "QUANTITY");
        seedDecidedAppeal(1102L, 702L, 902L, "20260723080000", 2, "DAMAGE");

        AdminDeliveryAppealItemVo row = single(adminService.pageAppeals(query(null, null, 1, 20)));
        assertEquals(2, row.getAppealCount());
        assertNull(row.getActiveAppealId(), "全部终态的案件没有待裁决申诉");
        assertEquals(1102L, row.getAppealId(), "代表申诉=最近一条");
        assertEquals(2, row.getAppealStatus());
        assertEquals(EMPLOYEE_ID, row.getHandleBy());
        assertEquals("运营小王", row.getHandleByName());
        assertEquals("裁决说明 1102", row.getHandleResult());
        assertEquals("20260719090000", row.getFirstAppealTime());
        assertEquals("20260723080000", row.getLastAppealTime());
    }

    // ==================== T3：待处理案件排前，其余按最近活动倒序 ====================

    @Test
    void pendingCasesRankFirstThenByLastAppealTimeDesc() {
        // A：有待处理，但最近活动时间最早 —— 仍必须排第一
        seedCase(701L, 901L, "-ORD-A", 7, 4);
        seedDecidedAppeal(1001L, 701L, 901L, "20260720100000", 3, "QUANTITY");
        seedPendingAppeal(1002L, 701L, 901L, "20260721100000", "QUALITY");
        // B：全终态，最近活动 0722
        seedCase(702L, 902L, "-ORD-B", 5, 4);
        seedDecidedAppeal(1101L, 702L, 902L, "20260722100000", 3, "DAMAGE");
        // C：全终态，最近活动 0724（比 B 新）
        seedCase(703L, 903L, "-ORD-C", 5, 4);
        seedDecidedAppeal(1201L, 703L, 903L, "20260723100000", 2, "PLACEMENT");
        seedDecidedAppeal(1202L, 703L, 903L, "20260724100000", 3, "OTHER");

        List<AdminDeliveryAppealItemVo> rows = adminService.pageAppeals(query(null, null, 1, 20)).getList();
        assertEquals(3, rows.size());
        assertEquals(701L, rows.get(0).getTaskId(), "待处理案件优先，运营先看要裁决的");
        assertEquals(703L, rows.get(1).getTaskId(), "其余按最近申诉时间倒序");
        assertEquals(702L, rows.get(2).getTaskId());
    }

    // ==================== T4：状态筛选按当前状态命中，不看历史 ====================

    @Test
    void statusFilterMatchesCurrentStateNotHistory() {
        // A：曾被驳回(3)，现有待处理(1)
        seedCase(701L, 901L, "-FLT-A", 7, 4);
        seedDecidedAppeal(1001L, 701L, 901L, "20260720100000", 3, "QUANTITY");
        seedPendingAppeal(1002L, 701L, 901L, "20260721100000", "QUALITY");
        // B：当前就是驳回(3)
        seedCase(702L, 902L, "-FLT-B", 5, 4);
        seedDecidedAppeal(1101L, 702L, 902L, "20260722100000", 3, "DAMAGE");

        List<AdminDeliveryAppealItemVo> pending = adminService.pageAppeals(query(1, null, 1, 20)).getList();
        assertEquals(1, pending.size());
        assertEquals(701L, pending.get(0).getTaskId(), "筛待处理必须命中 A");

        List<AdminDeliveryAppealItemVo> rejected = adminService.pageAppeals(query(3, null, 1, 20)).getList();
        assertEquals(1, rejected.size(), "A 历史上出现过驳回，但当前状态是待处理，不参与命中");
        assertEquals(702L, rejected.get(0).getTaskId());

        // orderNo 模糊匹配语义不变（任务↔订单 1:1，案件内订单号唯一）
        PageDataVo<AdminDeliveryAppealItemVo> byOrder = adminService.pageAppeals(query(null, "FLT-B", 1, 20));
        assertEquals(1L, byOrder.getTotal());
        assertEquals(702L, byOrder.getList().get(0).getTaskId());
    }

    // ==================== T5：total = 案件数；跨页不重不漏 ====================

    @Test
    void paginationTotalCountsCasesNotAppeals() {
        // 7 个案件 × 每案件 2 条申诉 = 14 条申诉；分页单位必须是案件
        for (int i = 0; i < 7; i++) {
            long taskId = 710L + i;
            long orderId = 910L + i;
            seedCase(taskId, orderId, "-PG-" + i, 5, 4);
            seedDecidedAppeal(2000L + i * 2, taskId, orderId, "202607" + (10 + i) + "100000", 3, "QUANTITY");
            seedDecidedAppeal(2001L + i * 2, taskId, orderId, "202607" + (10 + i) + "120000", 2, "DAMAGE");
        }
        assertEquals(14, jdbc.queryForObject("SELECT COUNT(*) FROM ws_delivery_appeal", Integer.class));

        PageDataVo<AdminDeliveryAppealItemVo> first = adminService.pageAppeals(query(null, null, 1, 5));
        PageDataVo<AdminDeliveryAppealItemVo> second = adminService.pageAppeals(query(null, null, 2, 5));
        assertEquals(7L, first.getTotal(), "total 必须是案件数而不是申诉条数");
        assertEquals(7L, second.getTotal());
        assertEquals(5, first.getList().size());
        assertEquals(2, second.getList().size());

        Set<Long> taskIds = new LinkedHashSet<>();
        List<AdminDeliveryAppealItemVo> all = new ArrayList<>(first.getList());
        all.addAll(second.getList());
        all.forEach(row -> {
            assertEquals(2, row.getAppealCount());
            assertTrue(taskIds.add(row.getTaskId()), "跨页边界不得重复出现同一案件：" + row.getTaskId());
        });
        assertEquals(7, taskIds.size(), "跨页边界不得遗漏案件");
    }

    // ==================== T6：申诉往来时间线 ====================

    @Test
    void appealEvidenceExposesFullTaskHistoryInAscendingOrder() {
        seedCase(800L, 900L, "-EVID-01", 7, 4);
        seedDecidedAppeal(1201L, 800L, 900L, "20260724110000", 3, "QUANTITY");
        seedPendingAppeal(1202L, 800L, 900L, "20260724120000", "QUALITY");

        AdminDeliveryAppealEvidenceVo vo = adminService.appealEvidence(1202L);
        assertEquals("ok", vo.getLinkStatus(), "共键成立才下发历史，实际原因=" + vo.getLinkReason());
        List<AdminDeliveryAppealItemVo> history = vo.getAppealHistory();
        assertNotNull(history);
        assertEquals(2, history.size(), "同任务全部申诉（含当前这条）");
        assertEquals(1201L, history.get(0).getAppealId(), "正序：先驳回后再申诉的因果不得颠倒");
        assertEquals(1202L, history.get(1).getAppealId());
        assertEquals(3, history.get(0).getAppealStatus());
        assertEquals("数量不符", history.get(0).getAppealReasonLabel(), "历史同样过 Service 原因映射");
        assertEquals("运营小王", history.get(0).getHandleByName());
        assertEquals("裁决说明 1201", history.get(0).getHandleResult());
        assertEquals("20260724180000", history.get(0).getHandleTime());
        assertEquals("139****1111", history.get(1).getUserMaskedPhone(), "历史条目同样脱敏");
        assertNull(history.get(1).getUserPhoneRaw());
        // 媒体只针对当前 appealId 那条，历史不拉媒体元数据（抽屉体量与权限面不随轮次膨胀）
        assertNotNull(vo.getAppealPhotos());
    }

    @Test
    void appealEvidenceWithholdsHistoryWhenLinkMismatched() {
        // 申诉指向不存在的任务/订单：被查记录已断链，历史同样不下发，不借历史扩大暴露面
        seedDecidedAppeal(1301L, 8888L, 9999L, "20260724110000", 3, "QUANTITY");

        AdminDeliveryAppealEvidenceVo vo = adminService.appealEvidence(1301L);
        assertEquals("mismatch", vo.getLinkStatus());
        assertNotNull(vo.getLinkReason());
        assertNull(vo.getAppealHistory(), "断链记录不下发申诉往来");
        assertNull(vo.getTask());
        assertNull(vo.getAppealPhotos());
        // 申诉主体自述字段仍保留（申诉本身就是被查对象）
        assertNotNull(vo.getAppeal());
        assertEquals(1301L, vo.getAppeal().getAppealId());
    }

    // ==================== 单条查询不下发聚合列（口径边界） ====================

    @Test
    void singleAppealQueryDoesNotCarryAggregateColumns() {
        seedCase(701L, 901L, "-SGL-01", 7, 4);
        seedPendingAppeal(1001L, 701L, 901L, "20260721100000", "QUALITY");

        AdminDeliveryAppealItemVo item = appealMapper.selectAdminAppealById(1001L);
        assertNotNull(item);
        assertNull(item.getAppealCount(), "聚合列只属于案件分页，单条查询不臆造");
        assertNull(item.getActiveAppealId());
        assertNull(item.getFirstAppealTime());
        assertNull(item.getLastAppealTime());

        List<AdminDeliveryAppealItemVo> history = appealMapper.selectAdminAppealsByTaskId(701L);
        assertEquals(1, history.size());
        assertFalse(history.isEmpty());
        assertNull(history.get(0).getAppealCount());
    }
}
