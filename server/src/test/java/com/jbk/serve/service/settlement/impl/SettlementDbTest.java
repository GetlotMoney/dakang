package com.jbk.serve.service.settlement.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.settlement.WsIncomeAccountMapper;
import com.jbk.serve.mapper.settlement.WsIncomeFlowMapper;
import com.jbk.serve.mapper.settlement.WsSplitConfigMapper;
import com.jbk.serve.mapper.settlement.WsSplitRecordMapper;
import com.jbk.serve.service.delivery.impl.DeliveryDbSchema;
import com.jbk.serve.service.settlement.IIncomeService;
import com.jbk.serve.service.settlement.ISplitService;
import com.jbk.tool.consts.settlement.SettlementEnum;
import com.jbk.tool.data.settlement.po.WsSplitRecord;
import com.jbk.tool.exception.JbkException;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E-08 分账内核的<b>真实 MySQL</b> 集成测试（包B）。
 *
 * <p>钉住的口径（任务书二.1/2/3 冻结）：分账基数=消费订单金额；比例取订单创建时点
 * 生效版本并快照；整除余数恒归平台且全行合计=基数；uk 库层幂等；推进 1→2 CAS +
 * 收益入账同事务，INCOME:&lt;splitId&gt; 幂等；比例合计超 100% fail-closed。</p>
 *
 * <p>无 Docker 环境自动跳过。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = SettlementDbTest.Ctx.class)
class SettlementDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_settle_it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    /** 接口注入的是事务代理：ReflectionTestUtils 必须打到目标 bean 的字段上。 */
    private static Object unwrap(Object proxy) {
        try {
            return org.springframework.test.util.AopTestUtils.getTargetObject(proxy);
        } catch (Exception e) {
            return proxy;
        }
    }

    private static final long OWNER = 9L;
    private static final long COURIER = 8L;

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
            return new SqlSessionTemplate(factory.getObject());
        }

        private static <M> MapperFactoryBean<M> mapper(Class<M> type, SqlSessionTemplate template) {
            MapperFactoryBean<M> bean = new MapperFactoryBean<>(type);
            bean.setSqlSessionTemplate(template);
            return bean;
        }


        @Bean
        MapperFactoryBean<WsSplitRecordMapper> wsSplitRecordMapper(SqlSessionTemplate t) {
            return mapper(WsSplitRecordMapper.class, t);
        }

        // resolveWaterOwner 的归属解析依赖（本类不触发解析，仅满足装配；行为由集成链锁定）
        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.device.WsDeviceMapper> wsDeviceMapper(SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.device.WsDeviceMapper.class, t);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.station.WsStationMapper> wsStationMapper(SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.station.WsStationMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsSplitConfigMapper> wsSplitConfigMapper(SqlSessionTemplate t) {
            return mapper(WsSplitConfigMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsIncomeAccountMapper> wsIncomeAccountMapper(SqlSessionTemplate t) {
            return mapper(WsIncomeAccountMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsIncomeFlowMapper> wsIncomeFlowMapper(SqlSessionTemplate t) {
            return mapper(WsIncomeFlowMapper.class, t);
        }

        // V2 依赖（本 Ctx 跑 V1 口径：开关默认 false，V2 路径不触发，仅满足装配）
        @Bean
        com.jbk.serve.service.settlement.ISplitPlanService splitPlanService() {
            com.jbk.serve.service.settlement.ISplitPlanService mock =
                    Mockito.mock(com.jbk.serve.service.settlement.ISplitPlanService.class);
            Mockito.when(mock.activePlanAt(Mockito.anyString()))
                    .thenReturn(java.util.Optional.empty());
            return mock;
        }

        @Bean
        com.jbk.serve.service.settlement.IOwnerAttributionService ownerAttributionService() {
            return Mockito.mock(com.jbk.serve.service.settlement.IOwnerAttributionService.class);
        }

        @Bean
        com.jbk.serve.mapper.settlement.WsSplitComponentMapper wsSplitComponentMapper() {
            return Mockito.mock(com.jbk.serve.mapper.settlement.WsSplitComponentMapper.class);
        }

        @Bean
        ISplitService splitService() {
            return new SplitServiceImpl();
        }

        @Bean
        IIncomeService incomeService() {
            return new IncomeServiceImpl();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    @Autowired
    private ISplitService splitService;
    @Autowired
    private IIncomeService incomeService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        // 基线零冻结（D-421）：测试 Ctx 无 property source，@Value 默认值 1 会冻住全部
        // 既有结算用例；冻结语义由 freezeWindowBlocksSettlementUntilExpiry 专测
        org.springframework.test.util.ReflectionTestUtils.setField(
                unwrap(splitService), "freezeDays", 0);
        DeliveryDbSchema.createAll(jdbc);
        DeliveryDbSchema.truncateAll(jdbc);
        seedConfig(1, 1, 7000, "20260101000000");
        seedConfig(1, 3, 3000, "20260101000000");
        seedConfig(2, 1, 6000, "20260101000000");
        seedConfig(2, 2, 3000, "20260101000000");
        seedConfig(2, 3, 1000, "20260101000000");
    }

    private void seedConfig(int line, int receiver, int rate, String effect) {
        jdbc.update("INSERT INTO ws_split_config (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, PRODUCT_LINE, RECEIVER_TYPE, SPLIT_RATE, EFFECT_TIME) VALUES (0,1,'20260101000000',1,'20260101000000',?,?,?,?)",
                line, receiver, rate, effect);
    }

    private List<WsSplitRecord> rowsOf(long orderId) {
        return splitService.list(Wrappers.lambdaQuery(WsSplitRecord.class)
                .eq(WsSplitRecord::getOrderId, orderId).orderByAsc(WsSplitRecord::getId));
    }

    @Test
    void waterOrderSplitsWithRemainderToPlatform() {
        // 1001 分 × 70% = 700.7 → 机主 700（向下取整），平台 = 1001-700 = 301（吃余数）
        splitService.enqueueForOrder(101L, "WO-1", 1001, SettlementEnum.ProductLine.WATER,
                OWNER, null, "20260731120000");
        List<WsSplitRecord> rows = rowsOf(101L);
        assertEquals(2, rows.size());
        assertEquals(700L, rows.get(0).getSplitAmount());
        assertEquals("7000", rows.get(0).getSplitRateSnap());
        assertEquals(301L, rows.get(1).getSplitAmount());
        assertEquals("REMAINDER", rows.get(1).getSplitRateSnap());
        assertEquals(1001L, rows.stream().mapToLong(WsSplitRecord::getSplitAmount).sum(),
                "全行合计恒等于基数（对账不变式）");
    }

    @Test
    void deliverySplitsThreeWays() {
        splitService.enqueueForOrder(102L, "DO-1", 3000, SettlementEnum.ProductLine.DELIVERY,
                OWNER, COURIER, "20260731120000");
        List<WsSplitRecord> rows = rowsOf(102L);
        assertEquals(3, rows.size());
        assertEquals(1800L, rows.get(0).getSplitAmount(), "机主 60%");
        assertEquals(900L, rows.get(1).getSplitAmount(), "配送员 30%");
        assertEquals(300L, rows.get(2).getSplitAmount(), "平台余量 10%");
    }

    /**
     * D-419（2026-08-07 甲方确认）：配送费与水费完全分开、各自为线。
     * 水费 2600 按 WATER 线机主 70% → 1820；配送费 400 按 DELIVERY 线机主 60%/配送员 30%
     * → 240/120；平台=整单 3000 − 1820 − 240 − 120 = 820。行合计恒等于整单。
     */
    @Test
    void deliverySplitsWaterAndFeeOnSeparateLines() {
        splitService.enqueueForDeliveryOrder(112L, "DO-SPLIT-1", 2600, 400,
                OWNER, COURIER, "20260731120000");
        List<WsSplitRecord> rows = rowsOf(112L);
        assertEquals(3, rows.size());
        assertEquals(1820L + 240L, rows.get(0).getSplitAmount(), "机主=水费线 70% + 配送费线 60%");
        assertEquals("W7000+D6000", rows.get(0).getSplitRateSnap(),
                "机主快照记两线比例（基数事实在任务行的 waterAmount/deliveryFee 冻结快照）");
        assertEquals("D3000", rows.get(1).getSplitRateSnap(), "配送员快照记配送费线比例");
        assertEquals(120L, rows.get(1).getSplitAmount(), "配送员只在配送费线取 30%");
        assertEquals(3000L - 2060L - 120L, rows.get(2).getSplitAmount(), "平台吃两线余数，合计=整单");
        assertEquals(3000L, rows.stream().mapToLong(WsSplitRecord::getSplitAmount).sum(),
                "行合计恒等于整单（水费+配送费）");
    }

    /** D-419 幂等：分线挂点同单重放不追加行、金额不变。 */
    @Test
    void deliverySplitEnqueueIdempotentOnReplay() {
        splitService.enqueueForDeliveryOrder(113L, "DO-SPLIT-2", 2600, 400,
                OWNER, COURIER, "20260731120000");
        splitService.enqueueForDeliveryOrder(113L, "DO-SPLIT-2", 2600, 400,
                OWNER, COURIER, "20260731120000");
        List<WsSplitRecord> rows = rowsOf(113L);
        assertEquals(3, rows.size(), "重放不追加行");
        assertEquals(3000L, rows.stream().mapToLong(WsSplitRecord::getSplitAmount).sum());
    }

    /** D-419 逐线超配校验：单线比例合计超 100% 必须拒绝，不被另一条线稀释掩盖。 */
    @Test
    void perLineOverConfigFailsClosedEvenWhenTotalLooksFine() {
        seedConfig(2, 2, 9000, "20260701000000");
        seedConfig(2, 3, 2000, "20260701000000");
        org.junit.jupiter.api.Assertions.assertThrows(com.jbk.tool.exception.JbkException.class,
                () -> splitService.enqueueForDeliveryOrder(114L, "DO-SPLIT-3", 100000, 100,
                        OWNER, COURIER, "20260731120000"),
                "配送费线 90%+30%>100%：即使整单余额仍为正也必须拒绝");
        assertEquals(0, rowsOf(114L).size(), "拒绝零写入");
    }

    @Test
    void rateVersionIsSelectedByOrderCreateTimeAndNotRetroactive() {
        // 新版本生效于 20260801：旧单（0731）仍按 70%，新单按 50%
        seedConfig(1, 1, 5000, "20260801000000");
        splitService.enqueueForOrder(103L, "WO-OLD", 1000, SettlementEnum.ProductLine.WATER,
                OWNER, null, "20260731120000");
        splitService.enqueueForOrder(104L, "WO-NEW", 1000, SettlementEnum.ProductLine.WATER,
                OWNER, null, "20260801120000");
        assertEquals("7000", rowsOf(103L).get(0).getSplitRateSnap());
        assertEquals("5000", rowsOf(104L).get(0).getSplitRateSnap());
    }

    @Test
    void enqueueIsIdempotentOnReplay() {
        splitService.enqueueForOrder(105L, "WO-2", 1000, SettlementEnum.ProductLine.WATER,
                OWNER, null, "20260731120000");
        splitService.enqueueForOrder(105L, "WO-2", 1000, SettlementEnum.ProductLine.WATER,
                OWNER, null, "20260731120000");
        assertEquals(2, rowsOf(105L).size(), "重放撞唯一键静默跳过，行数不翻倍");
    }

    @Test
    void overOneHundredPercentConfigFailsClosed() {
        // 机主新版本 120%：平台行为负 → fail-closed 拒绝（WATER 线收款方只有机主+平台，
        // 超限只能来自单方比例本身超 100%）
        seedConfig(1, 1, 12000, "20260201000000");
        assertThrows(JbkException.class, () -> splitService.enqueueForOrder(
                106L, "WO-BAD", 1000, SettlementEnum.ProductLine.WATER, OWNER, null, "20260731120000"));
    }

    /** D-421 在途分润展示：冻结期内 walletFor 返回 pendingSplitFen 与解冻时间，防被当漏发。 */
    @Test
    void walletShowsPendingSplitDuringFreeze() {
        org.springframework.test.util.ReflectionTestUtils.setField(
                unwrap(splitService), "freezeDays", 1);
        try {
            splitService.enqueueForOrder(121L, "WO-PEND", 1000, SettlementEnum.ProductLine.WATER,
                    OWNER, null, "20260731120000");
            var wallet = incomeService.walletFor(OWNER);
            assertEquals(700L, wallet.getPendingSplitFen(), "在途=机主 70% 份额（尚未入账）");
            assertTrue(wallet.getEarliestUnfreezeTime() != null
                    && wallet.getEarliestUnfreezeTime().compareTo(com.jbk.tool.utils.DateUtils.time()) > 0,
                    "解冻时间=行创建+1天，晚于当前");
            assertEquals(0L, wallet.getBalanceFen(), "冻结期内可用余额不含在途");
            // R1-P2：更早的零元 PENDING 行不计在途金额、不许把最早解冻时间提前（聚合只算 >0）
            jdbc.update("INSERT INTO ws_split_record (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY,"
                    + " UPDATE_TIME, ORDER_ID, RECEIVER_TYPE, RECEIVER_USER_ID, SPLIT_AMOUNT,"
                    + " SPLIT_RATE_SNAP, SPLIT_STATUS, SPLIT_REMARK)"
                    + " VALUES (0, 1, '20250101000000', 1, '20250101000000', 122, 1, ?, 0, '0', 1, 'WO-ZERO')",
                    OWNER);
            var walletWithZeroRow = incomeService.walletFor(OWNER);
            assertEquals(700L, walletWithZeroRow.getPendingSplitFen(), "零元行不计入在途金额");
            assertEquals(wallet.getEarliestUnfreezeTime(), walletWithZeroRow.getEarliestUnfreezeTime(),
                    "零元行不得提前最早解冻时间");
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(
                    unwrap(splitService), "freezeDays", 0);
        }
    }

    /**
     * D-421 分润冻结期：未满冻结期的行 settleOne 静默让行（零入账、状态不动），
     * 满期后照常结算。Worker 扫描阈值与 settleOne 守卫同口径（settleableCreateTimeThreshold）。
     */
    @Test
    void freezeWindowBlocksSettlementUntilExpiry() {
        org.springframework.test.util.ReflectionTestUtils.setField(
                unwrap(splitService), "freezeDays", 1);
        try {
            splitService.enqueueForOrder(120L, "WO-FRZ", 1000, SettlementEnum.ProductLine.WATER,
                    OWNER, null, "20260731120000");
            Long ownerRowId = rowsOf(120L).get(0).getId();
            assertFalse(splitService.settleOne(ownerRowId), "冻结期内不得结算");
            assertEquals(SettlementEnum.SplitStatus.PENDING.getValue(),
                    rowsOf(120L).get(0).getSplitStatus(), "行保持待分账");
            assertEquals(0L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ws_income_flow", Long.class), "冻结期内零入账");
            // 阈值口径：冻结 1 天时阈值必须早于当前时间串
            assertTrue(splitService.settleableCreateTimeThreshold()
                    .compareTo(com.jbk.tool.utils.DateUtils.time()) < 0);

            // 行创建时间回拨 2 天=已出冻结期：照常结算入账
            jdbc.update("UPDATE ws_split_record SET CREATE_TIME = '20260729000000' WHERE ORDER_ID = 120");
            assertTrue(splitService.settleOne(ownerRowId), "满冻结期后照常结算");
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(
                    unwrap(splitService), "freezeDays", 0);
        }
    }

    /**
     * D-421 R1：settleOne 数据库事实闭合。只收 ID、事务内锁定读，冻结判定与入账事实
     * 全取锁内 DB 行——新建行直调拒绝、CREATE_TIME 非法拒绝且零副作用（fail-closed，
     * 时间不可信绝不按满期放行）、行不存在让行；满期后入账事实以库为准（伪造收款人/
     * 金额/时间在签名层面已不可表达）。
     */
    @Test
    void settleOneClosesOverDbFactsFailClosed() {
        org.springframework.test.util.ReflectionTestUtils.setField(
                unwrap(splitService), "freezeDays", 1);
        try {
            splitService.enqueueForOrder(130L, "WO-FC", 1000, SettlementEnum.ProductLine.WATER,
                    OWNER, null, "20260731120000");
            Long ownerRowId = rowsOf(130L).get(0).getId();
            // ① 新建 PENDING 行直调=false：状态/流水零变化
            assertFalse(splitService.settleOne(ownerRowId), "冻结期内直调必须拒绝");
            assertEquals(SettlementEnum.SplitStatus.PENDING.getValue(),
                    rowsOf(130L).get(0).getSplitStatus());
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM ws_income_flow", Long.class));
            // ② CREATE_TIME 非法（14 位字母，R3 同款样例）：拒绝且零副作用
            jdbc.update("UPDATE ws_split_record SET CREATE_TIME = 'abcdefghijklmn' WHERE ID = ?", ownerRowId);
            assertFalse(splitService.settleOne(ownerRowId), "时间非法必须 fail-closed，不得按满期放行");
            assertEquals(SettlementEnum.SplitStatus.PENDING.getValue(),
                    rowsOf(130L).get(0).getSplitStatus(), "非法时间行保持待分账");
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM ws_income_flow", Long.class),
                    "非法时间行零入账");
            // ③ 行不存在/ID 缺失：让行
            assertFalse(splitService.settleOne(999_999L));
            assertFalse(splitService.settleOne((Long) null));
            // ④ 修数满期后恢复结算，入账事实=锁内 DB 行（金额/收款人以库为准）
            jdbc.update("UPDATE ws_split_record SET CREATE_TIME = '20260729000000' WHERE ID = ?", ownerRowId);
            assertTrue(splitService.settleOne(ownerRowId), "修数满期后照常结算");
            assertEquals(700L, (long) jdbc.queryForObject(
                    "SELECT BALANCE_FEN FROM ws_income_account WHERE USER_ID=?", Long.class, OWNER));
            assertEquals(700L, (long) jdbc.queryForObject(
                    "SELECT AMOUNT_FEN FROM ws_income_flow WHERE USER_ID=? AND SPLIT_ID=?",
                    Long.class, OWNER, ownerRowId), "入账金额=数据库行 SPLIT_AMOUNT");
            // ⑤ 重复调用不重复入账
            assertFalse(splitService.settleOne(ownerRowId), "重复调用静默让行");
            assertEquals(700L, (long) jdbc.queryForObject(
                    "SELECT BALANCE_FEN FROM ws_income_account WHERE USER_ID=?", Long.class, OWNER),
                    "重复调用余额不变");
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(
                    unwrap(splitService), "freezeDays", 0);
        }
    }

    /**
     * 证据缺口整改：真 SplitSettleWorker 装配扫描。冻结窗内扫描候选集为空（Worker 根本
     * 不会触及 settleOne）、零推进零入账；满期后同一 Worker 扫描推进并入账。
     * Worker 扫描阈值与 settleOne 守卫同口径由本用例与直调用例双向锁定。
     */
    @Test
    void workerScanRespectsFreezeWindow() {
        org.springframework.test.util.ReflectionTestUtils.setField(
                unwrap(splitService), "freezeDays", 1);
        try {
            SplitSettleWorker worker = new SplitSettleWorker();
            org.springframework.test.util.ReflectionTestUtils.setField(
                    worker, "splitService", splitService);
            splitService.enqueueForOrder(131L, "WO-WK", 1000, SettlementEnum.ProductLine.WATER,
                    OWNER, null, "20260731120000");
            // 冻结窗内：扫描候选集为空=Worker 不会对新行调用 settleOne
            long scannable = splitService.count(Wrappers.lambdaQuery(WsSplitRecord.class)
                    .eq(WsSplitRecord::getSplitStatus, SettlementEnum.SplitStatus.PENDING.getValue())
                    .le(WsSplitRecord::getCreateTime, splitService.settleableCreateTimeThreshold()));
            assertEquals(0L, scannable, "冻结窗内 Worker 扫描候选集必须为空");
            worker.settlePending();
            assertEquals(SettlementEnum.SplitStatus.PENDING.getValue(),
                    rowsOf(131L).get(0).getSplitStatus(), "Worker 扫描后行仍待分账");
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM ws_income_flow", Long.class),
                    "冻结窗内 Worker 零入账");
            // 满期后：同一 Worker 扫描推进并入账
            jdbc.update("UPDATE ws_split_record SET CREATE_TIME = '20260729000000' WHERE ORDER_ID = 131");
            worker.settlePending();
            assertEquals(SettlementEnum.SplitStatus.DONE.getValue(),
                    rowsOf(131L).get(0).getSplitStatus(), "满期后 Worker 推进为已分账");
            assertEquals(700L, (long) jdbc.queryForObject(
                    "SELECT BALANCE_FEN FROM ws_income_account WHERE USER_ID=?", Long.class, OWNER),
                    "满期后 Worker 触发入账");
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(
                    unwrap(splitService), "freezeDays", 0);
        }
    }

    @Test
    void settleOnePromotesAndCreditsIncomeExactlyOnce() {
        splitService.enqueueForOrder(107L, "WO-3", 1000, SettlementEnum.ProductLine.WATER,
                OWNER, null, "20260731120000");
        for (WsSplitRecord row : rowsOf(107L)) {
            assertTrue(splitService.settleOne(row.getId()));
            assertFalse(splitService.settleOne(row.getId()), "前态 CAS：重复推进静默让行");
        }
        long balance = jdbc.queryForObject(
                "SELECT BALANCE_FEN FROM ws_income_account WHERE USER_ID=?", Long.class, OWNER);
        assertEquals(700L, balance, "机主到账 70%；平台行不入个人钱包");
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_income_flow WHERE USER_ID=?", Integer.class, OWNER));
        // 直接重放入账：INCOME:<splitId> 幂等
        WsSplitRecord ownerRow = rowsOf(107L).get(0);
        assertFalse(incomeService.creditFromSplit(ownerRow.getId(), OWNER, 700, "WO-3"));
        assertEquals(700L, (long) jdbc.queryForObject(
                "SELECT BALANCE_FEN FROM ws_income_account WHERE USER_ID=?", Long.class, OWNER));
    }

    @Test
    void zeroAmountAndMissingOwnerProduceNoNoise() {
        splitService.enqueueForOrder(108L, "WO-4", 0, SettlementEnum.ProductLine.WATER,
                OWNER, null, "20260731120000");
        assertEquals(0, rowsOf(108L).size(), "零元单不产分账");
        splitService.enqueueForOrder(109L, "WO-5", 1000, SettlementEnum.ProductLine.WATER,
                null, null, "20260731120000");
        List<WsSplitRecord> rows = rowsOf(109L);
        assertEquals(1, rows.size(), "无机主=仅平台行");
        assertEquals(1000L, rows.get(0).getSplitAmount(), "无归属订单全额归平台");
    }

    @Test
    void withdrawSkeletonFreezesAndRejectsWithLedgerConsistency() {
        // 造 700 分可用余额
        splitService.enqueueForOrder(120L, "WO-W", 1000, SettlementEnum.ProductLine.WATER,
                OWNER, null, "20260731120000");
        rowsOf(120L).forEach(r -> splitService.settleOne(r.getId()));
        String requestId = java.util.UUID.randomUUID().toString();

        incomeService.applyWithdraw(OWNER, 500, requestId);
        assertEquals("200|500", jdbc.queryForObject(
                "SELECT CONCAT(BALANCE_FEN,'|',FROZEN_FEN) FROM ws_income_account WHERE USER_ID=?",
                String.class, OWNER), "余额转冻结");
        // 幂等重放零变化
        incomeService.applyWithdraw(OWNER, 500, requestId);
        assertEquals("200|500", jdbc.queryForObject(
                "SELECT CONCAT(BALANCE_FEN,'|',FROZEN_FEN) FROM ws_income_account WHERE USER_ID=?",
                String.class, OWNER));
        // 余额不足 fail-closed
        assertThrows(JbkException.class, () -> incomeService.applyWithdraw(
                OWNER, 10_000, java.util.UUID.randomUUID().toString()));
        // 驳回解冻 + 幂等
        incomeService.rejectWithdraw(OWNER, 500, requestId, 1L);
        incomeService.rejectWithdraw(OWNER, 500, requestId, 1L);
        assertEquals("700|0", jdbc.queryForObject(
                "SELECT CONCAT(BALANCE_FEN,'|',FROZEN_FEN) FROM ws_income_account WHERE USER_ID=?",
                String.class, OWNER), "驳回后全额解冻");
        // 账本连续性贯穿提现骨架
        long balance = jdbc.queryForObject(
                "SELECT BALANCE_FEN FROM ws_income_account WHERE USER_ID=?", Long.class, OWNER);
        long lastAfter = jdbc.queryForObject(
                "SELECT AFTER_FEN FROM ws_income_flow WHERE USER_ID=? ORDER BY ID DESC LIMIT 1", Long.class, OWNER);
        assertEquals(balance, lastAfter);
    }

    @Test
    void incomeFlowAfterIsChainConsistent() {
        splitService.enqueueForOrder(110L, "WO-6", 1000, SettlementEnum.ProductLine.WATER,
                OWNER, null, "20260731120000");
        splitService.enqueueForOrder(111L, "WO-7", 2000, SettlementEnum.ProductLine.WATER,
                OWNER, null, "20260731120000");
        rowsOf(110L).forEach(r -> splitService.settleOne(r.getId()));
        rowsOf(111L).forEach(r -> splitService.settleOne(r.getId()));
        // 账本连续性：账户余额 == 末笔流水 AFTER（对账不变式）
        long balance = jdbc.queryForObject(
                "SELECT BALANCE_FEN FROM ws_income_account WHERE USER_ID=?", Long.class, OWNER);
        long lastAfter = jdbc.queryForObject(
                "SELECT AFTER_FEN FROM ws_income_flow WHERE USER_ID=? ORDER BY ID DESC LIMIT 1", Long.class, OWNER);
        assertEquals(balance, lastAfter);
        assertEquals(700L + 1400L, balance);
    }
}
