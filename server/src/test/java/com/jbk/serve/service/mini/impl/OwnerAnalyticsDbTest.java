package com.jbk.serve.service.mini.impl;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.device.WsDeviceMapper;
import com.jbk.serve.mapper.device.WsDeviceOutletMapper;
import com.jbk.serve.mapper.device.WsDeviceTelemetryMapper;
import com.jbk.serve.mapper.ops.WsDomainEventMapper;
import com.jbk.serve.mapper.ops.WsWorkOrderMapper;
import com.jbk.serve.mapper.station.WsStationMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.user.WsCourierMapper;
import com.jbk.serve.mapper.user.WsUserMapper;
import com.jbk.serve.service.delivery.IDeliveryMediaService;
import com.jbk.serve.service.delivery.impl.DeliveryDbSchema;
import com.jbk.serve.service.mini.IMiniCapabilityService;
import com.jbk.serve.service.mini.IMiniOwnerService;
import com.jbk.serve.service.ops.IWorkOrderService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.serve.service.ops.impl.WorkOrderServiceImpl;
import com.jbk.serve.service.ops.impl.WsDomainEventServiceImpl;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.mini.bo.MiniOwnerTransactionBo;
import com.jbk.tool.data.mini.vo.MiniOwnerOverviewVo;
import com.jbk.tool.data.mini.vo.MiniOwnerScopeVo;
import com.jbk.tool.data.mini.vo.MiniOwnerTransactionVo;
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
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E-06 机主经营聚合的<b>真实 MySQL</b> 集成测试（任务书包D）。
 *
 * <p>钉住的口径全部来自任务书第三节冻结项：</p>
 * <ol>
 *   <li><b>双轨去重</b>：同单既命中站轨又命中设备轨只计一次；站轨机主可见配送单
 *      （DEVICE_ID=NULL），设备轨机主可见取水单。</li>
 *   <li><b>计入口径</b>：充值单永不计入；待支付/已取消不计入；全额退款单按毛额计入
 *       且明细状态如实。</li>
 *   <li><b>越权</b>：无归属拒绝且不回空数据冒充；deviceNo 越权拒绝且不泄露存在性；
 *       分页非法/超限拒绝。</li>
 *   <li><b>周期边界</b>：varchar(14) 闭区间字符串比较，恰好命中/差一秒不命中。</li>
 *   <li><b>脱敏</b>：明细 Vo 反射断言无任何用户身份字段（D-212）。</li>
 *   <li><b>范围投影</b>：ownerScopeOf 与聚合同源；无归属返回 null。</li>
 * </ol>
 *
 * <p>无 Docker 环境自动跳过。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = OwnerAnalyticsDbTest.Ctx.class)
class OwnerAnalyticsDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_owner_it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    /** 双轨机主：名下站 31（挂设备 62 属别人）+ 名下设备 61（挂站 31——双轨重叠实例） */
    private static final long OWNER = 9L;
    /** 纯设备轨机主：只有设备 63（挂无主站 32） */
    private static final long DEVICE_ONLY_OWNER = 8L;
    private static final long NOBODY = 7L;
    private static final long STATION_MINE = 31L;
    private static final long STATION_FREE = 32L;
    private static final long DEV_MINE_ON_MY_STATION = 61L;
    private static final long DEV_OTHERS_ON_MY_STATION = 62L;
    private static final long DEV_ONLY_TRACK = 63L;
    /** 播种时间跟随真实时钟：overview 缺省周期=近7日（真实时钟推算），固定值会随日期漂出窗外 */
    private static final String NOW = new java.text.SimpleDateFormat("yyyyMMddHHmmss")
            .format(new java.util.Date());

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
            com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor interceptor =
                    new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(
                    new com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor(
                            com.baomidou.mybatisplus.annotation.DbType.MYSQL));
            cfg.addInterceptor(interceptor);
            // 生产同一份 XML：ws_order 既有查询走 XML，聚合走 wrapper——都吃同一配置
            factory.setMapperLocations(new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                    .getResources("classpath:mapper/*/*.xml"));
            return new SqlSessionTemplate(factory.getObject());
        }

        @Bean
        MapperFactoryBean<WsOrderMapper> wsOrderMapper(SqlSessionTemplate t) {
            return mapper(WsOrderMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsDeviceMapper> wsDeviceMapper(SqlSessionTemplate t) {
            return mapper(WsDeviceMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsStationMapper> wsStationMapper(SqlSessionTemplate t) {
            return mapper(WsStationMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsDeviceOutletMapper> wsDeviceOutletMapper(SqlSessionTemplate t) {
            return mapper(WsDeviceOutletMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsDeviceTelemetryMapper> wsDeviceTelemetryMapper(SqlSessionTemplate t) {
            return mapper(WsDeviceTelemetryMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsUserMapper> wsUserMapper(SqlSessionTemplate t) {
            return mapper(WsUserMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsCourierMapper> wsCourierMapper(SqlSessionTemplate t) {
            return mapper(WsCourierMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsWorkOrderMapper> wsWorkOrderMapper(SqlSessionTemplate t) {
            return mapper(WsWorkOrderMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsDomainEventMapper> wsDomainEventMapper(SqlSessionTemplate t) {
            return mapper(WsDomainEventMapper.class, t);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.api.ApiEmployeeMapper> apiEmployeeMapper(SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.api.ApiEmployeeMapper.class, t);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.delivery.WsDeliveryMediaMapper> wsDeliveryMediaMapper(SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.delivery.WsDeliveryMediaMapper.class, t);
        }

        private static <M> MapperFactoryBean<M> mapper(Class<M> type, SqlSessionTemplate template) {
            MapperFactoryBean<M> bean = new MapperFactoryBean<>(type);
            bean.setSqlSessionTemplate(template);
            return bean;
        }

        @Bean
        IWsDomainEventService domainEventService() {
            return new WsDomainEventServiceImpl();
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.ops.WsAlarmMapper> wsAlarmMapper(SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.ops.WsAlarmMapper.class, t);
        }

        @Bean
        com.jbk.serve.service.ops.IWsAlarmService alarmService() {
            return new com.jbk.serve.service.ops.impl.WsAlarmServiceImpl();
        }

        @Bean
        IWorkOrderService workOrderService() {
            return new WorkOrderServiceImpl();
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.message.WsMessageMapper> wsMessageMapper(SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.message.WsMessageMapper.class, t);
        }

        // E2E-07：工单迁移挂了申报人消息钩子，WorkOrderServiceImpl 新增本依赖
        @Bean
        com.jbk.serve.service.message.IWsMessageService messageService() {
            return new com.jbk.serve.service.message.impl.WsMessageServiceImpl();
        }

        @Bean
        IDeliveryMediaService mediaService() {
            return new com.jbk.serve.service.delivery.impl.DeliveryMediaServiceImpl();
        }

        @Bean
        com.jbk.serve.service.delivery.DeliveryMediaStore mediaStore() {
            return (mediaKey, content) -> { };
        }

        @Bean
        IMiniCapabilityService capabilityService(WsCourierMapper courierMapper,
                                                 WsDeviceMapper deviceMapper,
                                                 WsStationMapper stationMapper) {
            return new MiniCapabilityServiceImpl(courierMapper, deviceMapper, stationMapper);
        }

        @Bean
        IMiniOwnerService ownerService(WsDeviceMapper deviceMapper, WsStationMapper stationMapper,
                                       WsDeviceOutletMapper outletMapper, WsDeviceTelemetryMapper telemetryMapper,
                                       WsUserMapper userMapper, WsOrderMapper orderMapper,
                                       IWorkOrderService workOrderService, IWsDomainEventService eventService) {
            // 构造注入真实依赖：聚合 SQL、范围过滤、分页拦截器全部走生产代码
            return new MiniOwnerServiceImpl(deviceMapper, stationMapper, outletMapper, telemetryMapper,
                    userMapper, orderMapper, workOrderService, eventService);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    @Autowired
    private IMiniOwnerService ownerService;
    @Autowired
    private IMiniCapabilityService capabilityService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        DeliveryDbSchema.createAll(jdbc);
        DeliveryDbSchema.truncateAll(jdbc);
        // 站：31 归 OWNER，32 无主
        jdbc.update("INSERT INTO ws_station (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, STATION_NAME, OWNER_USER_ID) VALUES (?,0,1,?,1,?,?,?)",
                STATION_MINE, NOW, NOW, "我的水站", OWNER);
        jdbc.update("INSERT INTO ws_station (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, STATION_NAME, OWNER_USER_ID) VALUES (?,0,1,?,1,?,?,NULL)",
                STATION_FREE, NOW, NOW, "无主水站");
        // 设备：61 归 OWNER 挂我的站（双轨重叠）；62 归别人挂我的站（站轨命中）；63 归 DEVICE_ONLY_OWNER 挂无主站（纯设备轨）
        seedDevice(DEV_MINE_ON_MY_STATION, "IT-DEV-0061", STATION_MINE, OWNER, 1, 1);
        seedDevice(DEV_OTHERS_ON_MY_STATION, "IT-DEV-0062", STATION_MINE, 999L, 2, 1);
        seedDevice(DEV_ONLY_TRACK, "IT-DEV-0063", STATION_FREE, DEVICE_ONLY_OWNER, 1, 3);
    }

    private void seedDevice(long id, String no, long stationId, long owner, int online, int run) {
        jdbc.update("INSERT INTO ws_device (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, DEVICE_NO, STATION_ID, OWNER_USER_ID, ONLINE_STATUS, RUN_STATUS) VALUES (?,0,1,?,1,?,?,?,?,?,?)",
                id, NOW, NOW, no, stationId, owner, online, run);
    }

    /** 订单播种：type 1取水/2充值/3配送；stationId/deviceId 可空；缺省支付方式=2水卡余额 */
    private void seedOrder(String orderNo, int type, Long stationId, Long deviceId, int status,
                           long amountFen, Long actualMl, String createTime) {
        seedOrderWithPayWay(orderNo, type, stationId, deviceId, status, amountFen, actualMl, 2, createTime);
    }

    private void seedOrderWithPayWay(String orderNo, int type, Long stationId, Long deviceId, int status,
                                     long amountFen, Long actualMl, int payWay, String createTime) {
        jdbc.update("INSERT INTO ws_order (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, ORDER_NO, ORDER_TYPE, USER_ID, STATION_ID, DEVICE_ID, ORDER_AMOUNT, PAY_WAY, ORDER_STATUS, ACTUAL_ML) VALUES (0,1,?,1,?,?,?,1001,?,?,?,?,?,?)",
                createTime, createTime, orderNo, type, stationId, deviceId, amountFen, payWay, status, actualMl);
    }

    // ------------------------------------------------------------------
    // 双轨与计入口径
    // ------------------------------------------------------------------

    @Test
    void dualTrackDedupAndNullDimensionsFollowFrozenCaliber() {
        // 双轨重叠单：站31+设备61 都命中——只计一次
        seedOrder("WO-DUAL", 1, STATION_MINE, DEV_MINE_ON_MY_STATION, 4, 1000, 5000L, NOW);
        // 站轨单：站31 上别人的设备 62——站轨命中
        seedOrder("WO-STATION", 1, STATION_MINE, DEV_OTHERS_ON_MY_STATION, 4, 300, 1500L, NOW);
        // 配送单：DEVICE_ID=NULL，站轨命中
        seedOrder("WO-DELIVERY", 3, STATION_MINE, null, 2, 700, null, NOW);
        // 充值单：即便未来带站也永不计入（显式 TYPE 排除）
        seedOrder("WO-RECHARGE", 2, STATION_MINE, null, 4, 9999, null, NOW);
        // 待支付/已取消不计入
        seedOrder("WO-UNPAID", 1, STATION_MINE, DEV_MINE_ON_MY_STATION, 1, 500, null, NOW);
        seedOrder("WO-CANCELLED", 1, STATION_MINE, DEV_MINE_ON_MY_STATION, 5, 500, null, NOW);
        // 全额退款单：毛额计入，状态如实
        seedOrder("WO-REFUNDED", 1, STATION_MINE, DEV_MINE_ON_MY_STATION, 7, 400, 2000L, NOW);
        // 范围外：无主站上的取水单（不属于 OWNER 任何轨）
        seedOrder("WO-FOREIGN", 1, STATION_FREE, DEV_ONLY_TRACK, 4, 800, 4000L, NOW);

        MiniOwnerOverviewVo overview = ownerService.overview(OWNER);
        assertEquals(4, overview.getOrderCount(), "计入=双轨重叠1+站轨1+配送1+退款1（各只计一次）");
        assertEquals(1000L + 300L + 700L + 400L, overview.getOrderAmountFen(), "毛额合计，退款不冲减");
        assertEquals(5000L + 1500L + 2000L, overview.getActualVolumeMl(), "实际水量合计（配送单无水量）");
        assertEquals(1, overview.getStationCount(), "名下站∪设备挂靠站去重=1（设备61挂的就是名下站31）");
        assertEquals(1, overview.getDeviceCount(), "名下设备只有 61（62 是别人的）");
        assertEquals("real", overview.getEvidenceMode());

        // 明细同口径：4 行且含退款状态原值
        PageDataVo<MiniOwnerTransactionVo> page = ownerService.transactionPage(pageBo(null), OWNER);
        assertEquals(4L, page.getTotal());
        List<Integer> statuses = page.getList().stream().map(MiniOwnerTransactionVo::getOrderStatus)
                .collect(Collectors.toList());
        assertTrue(statuses.contains(7), "全额退款状态必须如实出现在明细");
        assertTrue(page.getList().stream().anyMatch(row -> row.getDeviceNo() == null),
                "配送单 deviceNo 为空也能正常列出");
    }

    /**
     * 水卡水量支付的取水单 ORDER_AMOUNT 恒为 0（水费在充值环节已结算，而充值单不归属机主）。
     * 若不把这部分出水量单列，机主看到「出水很多、金额很少」会当成漏记——概览必须能自证。
     */
    @Test
    void prepaidVolumeIsDisclosedSeparatelyFromGrossAmount() {
        // payWay=3 水卡水量：出水 5000ml，金额 0
        seedOrderWithPayWay("WO-PREPAID", 1, STATION_MINE, DEV_MINE_ON_MY_STATION, 4, 0, 5000L, 3, NOW);
        // payWay=2 水卡余额：出水 3000ml，金额 300
        seedOrderWithPayWay("WO-BALANCE", 1, STATION_MINE, DEV_MINE_ON_MY_STATION, 4, 300, 3000L, 2, NOW);

        MiniOwnerOverviewVo overview = ownerService.overview(OWNER);
        assertEquals(8000L, overview.getActualVolumeMl(), "总出水量含两种支付方式");
        assertEquals(300L, overview.getOrderAmountFen(), "金额只来自非水量支付部分");
        assertEquals(5000L, overview.getPrepaidVolumeMl(), "水量支付部分必须单列，供页面解释金额差");
    }

    @Test
    void deviceOnlyOwnerSeesDeviceTrackOrdersOnly() {
        seedOrder("WO-D63", 1, STATION_FREE, DEV_ONLY_TRACK, 4, 600, 3000L, NOW);
        seedOrder("WO-OTHER", 1, STATION_MINE, DEV_MINE_ON_MY_STATION, 4, 1000, 5000L, NOW);

        MiniOwnerOverviewVo overview = ownerService.overview(DEVICE_ONLY_OWNER);
        assertEquals(1, overview.getOrderCount(), "纯设备轨机主只看到自己设备上的单");
        assertEquals(600L, overview.getOrderAmountFen());
        assertEquals(1, overview.getStationCount(), "stationCount 含设备挂靠的无主站");
        // 越权对照：他的明细里绝无别人的订单
        PageDataVo<MiniOwnerTransactionVo> page = ownerService.transactionPage(pageBo(null), DEVICE_ONLY_OWNER);
        assertEquals(1L, page.getTotal());
        assertEquals("WO-D63", page.getList().get(0).getOrderNo());
    }

    // ------------------------------------------------------------------
    // 越权与防护
    // ------------------------------------------------------------------

    @Test
    void noScopeUserIsRejectedNotServedEmptyData() {
        assertThrows(JbkException.class, () -> ownerService.overview(NOBODY),
                "无归属必须明确拒绝，不返回零值冒充没生意");
        assertThrows(JbkException.class, () -> ownerService.transactionPage(pageBo(null), NOBODY));
        assertNull(capabilityService.ownerScopeOf(NOBODY), "无归属不投影范围");
    }

    @Test
    void deviceNoFilterEnforcesOwnershipWithoutLeakingExistence() {
        seedOrder("WO-1", 1, STATION_MINE, DEV_MINE_ON_MY_STATION, 4, 100, 500L, NOW);
        // 名下设备筛选成功
        PageDataVo<MiniOwnerTransactionVo> mine = ownerService.transactionPage(pageBo("IT-DEV-0061"), OWNER);
        assertEquals(1L, mine.getTotal());
        // 站上别人的设备（存在但非名下）与压根不存在的设备：同一拒绝口径
        JbkException foreign = assertThrows(JbkException.class,
                () -> ownerService.transactionPage(pageBo("IT-DEV-0062"), OWNER));
        JbkException missing = assertThrows(JbkException.class,
                () -> ownerService.transactionPage(pageBo("IT-DEV-9999"), OWNER));
        assertEquals(foreign.getMessage(), missing.getMessage(), "越权与不存在必须同文案（存在性不泄露）");
    }

    @Test
    void paginationGuardsRejectIllegalAndOversize() {
        MiniOwnerTransactionBo zero = pageBo(null);
        zero.setCurrent(0L);
        assertThrows(JbkException.class, () -> ownerService.transactionPage(zero, OWNER));
        MiniOwnerTransactionBo oversize = pageBo(null);
        oversize.setSize(101L);
        assertThrows(JbkException.class, () -> ownerService.transactionPage(oversize, OWNER));
        // 页码无上限时 (current-1)*size 可 long 溢出为负 LIMIT，以 SQLException 而非参数拒绝收场。
        MiniOwnerTransactionBo overflowPage = pageBo(null);
        overflowPage.setCurrent(Long.MAX_VALUE);
        assertThrows(JbkException.class, () -> ownerService.transactionPage(overflowPage, OWNER));
        MiniOwnerTransactionBo badTime = pageBo(null);
        badTime.setPeriodStart("2026-07-31");
        assertThrows(JbkException.class, () -> ownerService.transactionPage(badTime, OWNER));
    }

    // ------------------------------------------------------------------
    // 周期边界与脱敏
    // ------------------------------------------------------------------

    @Test
    void periodBoundaryIsClosedIntervalOnVarcharTime() {
        seedOrder("WO-EDGE", 1, STATION_MINE, DEV_MINE_ON_MY_STATION, 4, 100, 500L, "20260725000000");
        MiniOwnerTransactionBo exact = pageBo(null);
        exact.setPeriodStart("20260725000000");
        exact.setPeriodEnd("20260725000000");
        assertEquals(1L, ownerService.transactionPage(exact, OWNER).getTotal(), "闭区间恰好命中");
        MiniOwnerTransactionBo miss = pageBo(null);
        miss.setPeriodStart("20260725000001");
        miss.setPeriodEnd("20260726000000");
        assertEquals(0L, ownerService.transactionPage(miss, OWNER).getTotal(), "差一秒不命中");
    }

    /**
     * 明细的周期是「筛选条件」不是「统计口径」：两端全空即不限时间。
     * 曾经把空值补成近7日缺省，页面「全部」档因此静默变成「近7日」，超窗订单无路可查。
     */
    @Test
    void blankPeriodMeansUnbounded() {
        seedOrder("WO-ANCIENT", 1, STATION_MINE, DEV_MINE_ON_MY_STATION, 4, 100, 500L, "20200101000000");
        MiniOwnerTransactionBo all = new MiniOwnerTransactionBo();
        all.setCurrent(1L);
        all.setSize(20L);
        assertEquals(1L, ownerService.transactionPage(all, OWNER).getTotal(),
                "不传周期必须返回全量（含远早于近7日窗口的历史单）");
        // 只给上界同样成立（半开区间）
        MiniOwnerTransactionBo endOnly = new MiniOwnerTransactionBo();
        endOnly.setCurrent(1L);
        endOnly.setSize(20L);
        endOnly.setPeriodEnd("20200101000000");
        assertEquals(1L, ownerService.transactionPage(endOnly, OWNER).getTotal(), "只给上界按 <= 过滤");
    }

    @Test
    void transactionVoNeverCarriesUserIdentityFields() {
        // D-212：机主看经营不看客户——Vo 类结构层面就不允许身份字段存在
        List<String> fields = Arrays.stream(MiniOwnerTransactionVo.class.getDeclaredFields())
                .map(Field::getName).map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toList());
        for (String banned : List.of("userid", "userphone", "openid", "username", "phone")) {
            assertFalse(fields.stream().anyMatch(f -> f.contains(banned)),
                    "明细 Vo 不得含用户身份字段：" + banned);
        }
    }

    @Test
    void ownerScopeProjectionMatchesAnalyticsScope() {
        MiniOwnerScopeVo scope = capabilityService.ownerScopeOf(OWNER);
        assertEquals(List.of(String.valueOf(STATION_MINE)), scope.getStationIds());
        assertEquals(List.of("IT-DEV-0061"), scope.getDeviceNos());
    }

    private MiniOwnerTransactionBo pageBo(String deviceNo) {
        MiniOwnerTransactionBo bo = new MiniOwnerTransactionBo();
        bo.setCurrent(1L);
        bo.setSize(20L);
        bo.setDeviceNo(deviceNo);
        // 播种时间固定 NOW；显式给宽窗防真实时钟跨月导致缺省7日窗漏单
        bo.setPeriodStart("20260701000000");
        bo.setPeriodEnd("20261231235959");
        return bo;
    }
}
