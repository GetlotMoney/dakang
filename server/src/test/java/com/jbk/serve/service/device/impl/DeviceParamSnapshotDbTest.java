package com.jbk.serve.service.device.impl;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.device.WsDeviceParamDefMapper;
import com.jbk.serve.mapper.device.WsDeviceParamMapper;
import com.jbk.serve.mapper.ops.WsDomainEventMapper;
import com.jbk.serve.service.device.DeviceParamPayload;
import com.jbk.serve.service.device.IDeviceParamService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.serve.service.ops.impl.WsDomainEventServiceImpl;
import com.jbk.tool.config.system.mybatis.MpMetaObjectHandler;
import com.jbk.tool.consts.device.DeviceEnum;
import com.jbk.tool.data.device.po.WsDeviceParam;
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
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-213-S1 参数快照回写的<b>真实 MySQL + 真实 Spring 事务</b>测试。
 *
 * <p>钉 Mockito 证不了的三件事：唯一键下的并发收口、重复 result 的幂等、
 * 以及"后到指令不得被静默丢弃"——最后一条正是先查后判/吞异常写法的经典塌陷点。</p>
 *
 * <p>无 Docker 环境自动跳过。注意：跳过时 Maven 仍报 BUILD SUCCESS，
 * 判断门禁必须同时看 Skipped 是否为 0。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = DeviceParamSnapshotDbTest.Ctx.class)
class DeviceParamSnapshotDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_device_param_it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static final long DEVICE_ID = 21L;
    private static final long OTHER_DEVICE = 22L;
    private static final int PARAM_SYNC = DeviceEnum.CmdType.PARAM_SYNC.getValue();
    private static final int PRICE_SYNC = DeviceEnum.CmdType.PRICE_SYNC.getValue();
    private static final int START_DISPENSE = DeviceEnum.CmdType.START_DISPENSE.getValue();
    private static final String NOW = "20260804120000";

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
            globalConfig.setMetaObjectHandler(new MpMetaObjectHandler());
            factory.setGlobalConfig(globalConfig);
            return new SqlSessionTemplate(factory.getObject());
        }

        private static <M> MapperFactoryBean<M> mapper(Class<M> type, SqlSessionTemplate template) {
            MapperFactoryBean<M> bean = new MapperFactoryBean<>(type);
            bean.setSqlSessionTemplate(template);
            return bean;
        }

        @Bean
        MapperFactoryBean<WsDeviceParamMapper> wsDeviceParamMapper(SqlSessionTemplate t) {
            return mapper(WsDeviceParamMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsDeviceParamDefMapper> wsDeviceParamDefMapper(SqlSessionTemplate t) {
            return mapper(WsDeviceParamDefMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsDomainEventMapper> wsDomainEventMapper(SqlSessionTemplate t) {
            return mapper(WsDomainEventMapper.class, t);
        }

        @Bean
        IWsDomainEventService domainEventService() {
            // 真实现：本包要证「回写被拒时不静默」，那是唯一键行为，Mock 证不了
            return new WsDomainEventServiceImpl();
        }

        @Bean
        IDeviceParamService deviceParamService() {
            return new DeviceParamServiceImpl();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    @Autowired
    private IDeviceParamService paramService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_device_param_def (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT NOT NULL DEFAULT 0, CREATE_BY BIGINT NOT NULL,
                  CREATE_TIME VARCHAR(14) NOT NULL, UPDATE_BY BIGINT NOT NULL, UPDATE_TIME VARCHAR(14) NOT NULL,
                  CMD_TYPE TINYINT NOT NULL, PARAM_KEY VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
                  PARAM_NAME VARCHAR(50) NOT NULL,
                  VALUE_TYPE TINYINT NOT NULL, VALUE_UNIT VARCHAR(16) NULL,
                  VALUE_MIN VARCHAR(32) NULL, VALUE_MAX VARCHAR(32) NULL, VALUE_ENUM VARCHAR(255) NULL,
                  DEF_STATUS TINYINT NOT NULL, DEF_REMARK VARCHAR(255) NULL,
                  UNIQUE KEY uk_param_def_key (CMD_TYPE, PARAM_KEY)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_device_param (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT NOT NULL DEFAULT 0, CREATE_BY BIGINT NOT NULL,
                  CREATE_TIME VARCHAR(14) NOT NULL, UPDATE_BY BIGINT NOT NULL, UPDATE_TIME VARCHAR(14) NOT NULL,
                  DEVICE_ID BIGINT NOT NULL, PARAM_KEY VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
                  PARAM_VALUE VARCHAR(64) NOT NULL,
                  PARAM_VERSION INT NOT NULL DEFAULT 1, SOURCE_CMD_NO VARCHAR(40) NOT NULL,
                  SOURCE_CMD_ID BIGINT NOT NULL,
                  SYNC_TIME VARCHAR(14) NOT NULL, REGISTERED_FLAG TINYINT NOT NULL DEFAULT 0,
                  UNIQUE KEY uk_device_param (DEVICE_ID, PARAM_KEY)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_domain_event (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  EVENT_TYPE TINYINT, EVENT_KEY VARCHAR(64), EVENT_PAYLOAD TEXT,
                  ACTOR_ID BIGINT NULL, ACTOR_PORTAL TINYINT, ACTOR_ROLE VARCHAR(20),
                  WHITELIST_FLAG TINYINT, CONSUMED_FLAG TINYINT, BIZ_IDEMPOTENCY_KEY VARCHAR(64) NULL,
                  UNIQUE KEY uk_domain_event_biz_key (BIZ_IDEMPOTENCY_KEY)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        for (String t : new String[]{"ws_device_param", "ws_device_param_def", "ws_domain_event"}) {
            jdbc.execute("TRUNCATE TABLE " + t);
        }
    }

    // ==================== 夹具 ====================

    private void registerDef(int cmdType, String key, int valueType, String min, String max) {
        jdbc.update("INSERT INTO ws_device_param_def(DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,"
                        + "CMD_TYPE,PARAM_KEY,PARAM_NAME,VALUE_TYPE,VALUE_UNIT,VALUE_MIN,VALUE_MAX,VALUE_ENUM,"
                        + "DEF_STATUS,DEF_REMARK) VALUES(0,1,?,1,?,?,?,?,?,NULL,?,?,NULL,1,'测试登记')",
                NOW, NOW, cmdType, key, key, valueType, min, max);
    }

    private WsDeviceParam one(String key) {
        List<WsDeviceParam> all = paramService.currentParams(DEVICE_ID);
        return all.stream().filter(p -> key.equals(p.getParamKey())).findFirst().orElse(null);
    }

    private java.util.List<WsDeviceParam> all() {
        return paramService.currentParams(DEVICE_ID);
    }

    private int rowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ws_device_param", Integer.class);
    }

    private int rejectEventCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_domain_event WHERE BIZ_IDEMPOTENCY_KEY LIKE 'PARAM_SNAPSHOT_REJECT:%'",
                Integer.class);
    }

    // ==================== 5. 成功后写入，版本 1，来源正确 ====================

    @Test
    void successfulResultWritesSnapshotWithVersionOneAndSourceCmdNo() {
        // P1-1 之后只有已登记键进快照；本例要钉的不是登记规则，故先登记再测其原意。
        registerDef(PARAM_SYNC, "limitMl", 1, "0", "99999");
        registerDef(PARAM_SYNC, "mode", 3, null, null);

        int applied = paramService.applySyncedParams(DEVICE_ID, PARAM_SYNC, 1001L, "CMD-A",
                "{\"limitMl\":5000,\"mode\":\"eco\"}", NOW);

        assertEquals(2, applied);
        assertEquals(2, rowCount());
        WsDeviceParam limit = one("limitMl");
        assertEquals("5000", limit.getParamValue());
        assertEquals(1, limit.getParamVersion());
        assertEquals("CMD-A", limit.getSourceCmdNo());
        assertEquals(NOW, limit.getSyncTime());
        // P1-1 之后 REGISTERED_FLAG 恒为 1：未登记键根本进不了快照，0 这个取值已不可达。
        // 这里把它钉成不变量——哪天又有 0 出现，说明未登记键重新漏进了「当前生效参数」。
        assertTrue(all().stream().allMatch(r -> r.getRegisteredFlag() == 1),
                "快照里不允许出现未登记键（REGISTERED_FLAG 恒为 1）");
    }

    @Test
    void registeredKeyIsFlaggedAsRegistered() {
        registerDef(PARAM_SYNC, "limitMl", 1, "100", "20000");

        paramService.applySyncedParams(DEVICE_ID, PARAM_SYNC, 1001L, "CMD-A", "{\"limitMl\":5000}", NOW);

        assertEquals(1, one("limitMl").getRegisteredFlag());
    }

    // ==================== 6. 同一 cmdNo 重复 result 幂等 ====================

    @Test
    void duplicateResultOfSameCommandNeverBumpsVersion() {
        // P1-1 之后只有已登记键进快照；本例要钉的不是登记规则，故先登记再测其原意。
        registerDef(PARAM_SYNC, "limitMl", 1, "0", "99999");

        paramService.applySyncedParams(DEVICE_ID, PARAM_SYNC, 1001L, "CMD-A", "{\"limitMl\":5000}", NOW);

        for (int i = 0; i < 4; i++) {
            // 返回值是"覆盖键数"且幂等，不是"是否首次生效"——真正的幂等不变量在数据上，逐条断言于下
            paramService.applySyncedParams(DEVICE_ID, PARAM_SYNC, 1001L, "CMD-A",
                    "{\"limitMl\":5000}", "20260804130000");
        }

        WsDeviceParam row = one("limitMl");
        assertEquals(1, row.getParamVersion(), "版本被重复 result 刷高，平台的「第几次同步」就失真了");
        assertEquals(NOW, row.getSyncTime(), "同步时间也不得被重复 result 改写");
        assertEquals(1, rowCount());
    }

    // ==================== 8. 不同 cmdNo 先后成功，版本递增取最后一次 ====================

    @Test
    void laterCommandBumpsVersionAndOverwritesValue() {
        // P1-1 之后只有已登记键进快照；本例要钉的不是登记规则，故先登记再测其原意。
        registerDef(PARAM_SYNC, "limitMl", 1, "0", "99999");

        paramService.applySyncedParams(DEVICE_ID, PARAM_SYNC, 1001L, "CMD-A", "{\"limitMl\":5000}", NOW);
        int applied = paramService.applySyncedParams(DEVICE_ID, PARAM_SYNC, 1002L, "CMD-B",
                "{\"limitMl\":8000}", "20260804130000");

        assertEquals(1, applied);
        WsDeviceParam row = one("limitMl");
        assertEquals("8000", row.getParamValue());
        assertEquals(2, row.getParamVersion());
        assertEquals("CMD-B", row.getSourceCmdNo());
        assertEquals("20260804130000", row.getSyncTime());
        assertEquals(1, rowCount(), "同一设备同一键恒一行");
    }

    // ==================== 9. 并发：恰一行，且后到的指令不得被静默丢弃 ====================

    /**
     * 并发只钉两件 Mockito 证不了的事：<b>唯一键下恒一行</b>，以及<b>不死锁</b>。
     *
     * <p>刻意不再断言「版本恰为 2」——那条断言是首版写的，它把「到达顺序即生效顺序」
     * 当成了预期，而那正是对抗审查抓出的缺陷。加上按 SOURCE_CMD_ID 的单调守卫后，
     * 若较新的 CMD-B 先落地，随后的 CMD-A 本就<b>不该</b>生效，版本停在 1 才是对的。</p>
     *
     * <p>真正变强的是结果的确定性：无论两条 result 谁先到，最终值必须是<b>更新的那条指令</b>
     * 的值。这一条在「后写者胜」的实现下会随机失败，在单调实现下恒成立。
     * 「更早指令不得覆盖」的确定性守卫另见 {@link #olderCommandArrivingLateNeverOverwritesNewerSnapshot()}。</p>
     */
    @Test
    void concurrentResultsKeepExactlyOneRowAndNewerCommandAlwaysWins() throws Exception {
        // P1-1 之后只有已登记键进快照；本例要钉的不是登记规则，故先登记再测其原意。
        registerDef(PARAM_SYNC, "limitMl", 1, "0", "99999");

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> older = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return paramService.applySyncedParams(DEVICE_ID, PARAM_SYNC, 1001L, "CMD-A",
                        "{\"limitMl\":5000}", NOW);
            };
            Callable<Integer> newer = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return paramService.applySyncedParams(DEVICE_ID, PARAM_SYNC, 1002L, "CMD-B",
                        "{\"limitMl\":8000}", NOW);
            };
            List<Future<Integer>> futures = pool.invokeAll(List.of(older, newer));
            // 两条都必须正常返回：任何一条抛异常（典型是死锁败方）都说明并发收口没做住
            futures.get(0).get(30, TimeUnit.SECONDS);
            futures.get(1).get(30, TimeUnit.SECONDS);
        }
        finally {
            pool.shutdownNow();
        }

        assertEquals(1, rowCount(), "唯一键保证恰一行");
        WsDeviceParam row = one("limitMl");
        assertEquals("8000", row.getParamValue(), "无论到达顺序，胜出的必须是更新的那条指令");
        assertEquals("CMD-B", row.getSourceCmdNo());
        assertTrue(row.getParamVersion() == 1 || row.getParamVersion() == 2,
                "版本取决于谁先落地：B 先则 1（A 本就不该生效），A 先则 2。实际 " + row.getParamVersion());
    }

    // ==================== R2-P1-1：未登记键不得进入「当前生效参数」 ====================

    /**
     * 未登记键允许下发，但绝不能冒充「设备当前生效值」。
     *
     * <p>厂家协议要求固件忽略不认识的键，而平台目前不解析逐键 result——
     * 拿不到「设备到底应用了哪些键」的事实。把下发过的未登记键写进快照，
     * 等于用「我们发过」冒充「设备生效中」：一个被固件默默丢弃的键会在平台上
     * 显示成生效值，运维据此排障必然走错方向。</p>
     */
    @Test
    void unregisteredKeysNeverEnterSnapshot() {
        // 注册表为空：三个键全未登记
        assertEquals(0, paramService.applySyncedParams(DEVICE_ID, PARAM_SYNC, 1001L, "CMD-A",
                "{\"limitMl\":5000,\"flushSec\":2,\"mode\":\"eco\"}", NOW),
                "全未登记报文必须返回 0");
        assertEquals(0, rowCount(), "快照零新增");
    }

    @Test
    void registeredKeyEntersSnapshotWithRegisteredFlag() {
        registerDef(PARAM_SYNC, "limitMl", 1, "100", "20000");

        assertEquals(1, paramService.applySyncedParams(DEVICE_ID, PARAM_SYNC, 1001L, "CMD-A",
                "{\"limitMl\":5000}", NOW));

        WsDeviceParam row = one("limitMl");
        assertEquals("5000", row.getParamValue());
        assertEquals(1, row.getRegisteredFlag(), "进入快照的键必然是已登记的");
        assertEquals(1, row.getParamVersion());
        assertEquals("CMD-A", row.getSourceCmdNo());
    }

    @Test
    void mixedPayloadWritesOnlyRegisteredKeysAndIsNotBlockedByUnregisteredOnes() {
        registerDef(PARAM_SYNC, "limitMl", 1, "100", "20000");

        // 混合报文：limitMl 已登记，另两个未登记
        assertEquals(1, paramService.applySyncedParams(DEVICE_ID, PARAM_SYNC, 1001L, "CMD-A",
                "{\"limitMl\":5000,\"unknownA\":1,\"unknownB\":\"x\"}", NOW),
                "未登记键不得阻断已登记键");

        assertEquals(1, rowCount(), "只写已登记键那一行");
        assertEquals("5000", one("limitMl").getParamValue());
        assertTrue(all().stream().noneMatch(r -> "unknownA".equals(r.getParamKey())));
        assertTrue(all().stream().noneMatch(r -> "unknownB".equals(r.getParamKey())));
    }

    @Test
    void unregisteredKeyStillPassesDispatchValidation() {
        // 证明没有打断既有能力：未登记键在**下发校验**这一层仍然通过
        DeviceParamPayload.Checked checked =
                DeviceParamPayload.require("{\"unknownA\":1}", paramService.enabledDefinitions(PARAM_SYNC));
        assertEquals(1, checked.values().size());
        assertEquals(java.util.Set.of("unknownA"), checked.unregisteredKeys());
    }

    // ==================== 单调守卫：更早的指令不得覆盖更新的快照 ====================

    /**
     * 断网补传 / 消息重投会让<b>更早指令</b>的 result 后到，这是既有能力下的正常时序。
     *
     * <p>首版把「同一 cmdNo 相等」当唯一幂等闸，对这条路径完全不设防：旧值反向覆盖新值、
     * SYNC_TIME 倒退，而 PARAM_VERSION 还 +1，让陈旧数据看起来更新——正是本需求要消灭的
     * 那种错误答案。现改为按 SOURCE_CMD_ID（平台自增即下发顺序）单调判定。</p>
     */
    @Test
    void olderCommandArrivingLateNeverOverwritesNewerSnapshot() {
        // P1-1 之后只有已登记键进快照；本例要钉的不是登记规则，故先登记再测其原意。
        registerDef(PRICE_SYNC, "priceVersion", 3, null, null);

        // CMD-B（较新，ID 更大）先到并生效
        assertEquals(1, paramService.applySyncedParams(DEVICE_ID, PRICE_SYNC, 1002L, "CMD-B",
                "{\"priceVersion\":\"PV-02\"}", "20260804130000"));
        WsDeviceParam afterB = one("priceVersion");
        assertEquals("PV-02", afterB.getParamValue());
        assertEquals(1, afterB.getParamVersion());

        // CMD-A（较旧，ID 更小）此刻才补传上来：一个字段都不许动
        paramService.applySyncedParams(DEVICE_ID, PRICE_SYNC, 1001L, "CMD-A",
                "{\"priceVersion\":\"PV-01\"}", "20260804120000");

        WsDeviceParam afterA = one("priceVersion");
        assertEquals("PV-02", afterA.getParamValue(), "更早指令绝不能把新值覆盖回旧值");
        assertEquals(1, afterA.getParamVersion(), "版本不得被更早指令抬高");
        assertEquals("CMD-B", afterA.getSourceCmdNo(), "溯源必须仍指向真正生效的那条指令");
        assertEquals("20260804130000", afterA.getSyncTime(), "同步时间不得倒退");
        assertEquals(1, rowCount());
    }

    // ==================== 大小写：是两个独立键，不得折叠 ====================

    @Test
    void caseVariantKeysAreDistinctRowsNotFoldedIntoOne() {
        // P1-1 之后只有已登记键进快照；本例要钉的不是登记规则，故先登记再测其原意。
        registerDef(PARAM_SYNC, "mode", 3, null, null);
        registerDef(PARAM_SYNC, "MODE", 3, null, null);

        // 库侧 PARAM_KEY 为列级 utf8mb4_bin。若沿用表默认 ci，唯一键会把两者折叠成一行，
        // 第二个键的值被静默丢弃，而 Java 侧仍以为写了两个键。
        assertEquals(2, paramService.applySyncedParams(DEVICE_ID, PARAM_SYNC, 1001L, "CMD-A",
                "{\"mode\":\"eco\",\"MODE\":\"boost\"}", NOW));

        assertEquals(2, rowCount(), "大小写变体必须各占一行");
        assertEquals("eco", one("mode").getParamValue());
        assertEquals("boost", one("MODE").getParamValue());
    }

    // ==================== 非参数类指令不得污染快照 ====================

    @Test
    void nonParamBearingCommandNeverWritesSnapshot() {
        // 出水指令的 payload 是 {outletNo, waterType, planMl, orderNo}，写进参数快照就是污染
        assertEquals(0, paramService.applySyncedParams(DEVICE_ID, START_DISPENSE, 1004L, "CMD-D",
                "{\"outletNo\":1,\"planMl\":10000,\"orderNo\":\"WO123\"}", NOW));
        assertEquals(0, rowCount());
    }

    @Test
    void priceSyncIsParamBearingToo() {
        // P1-1 之后只有已登记键进快照；本例要钉的不是登记规则，故先登记再测其原意。
        registerDef(PRICE_SYNC, "priceVersion", 3, null, null);

        assertEquals(1, paramService.applySyncedParams(DEVICE_ID, PRICE_SYNC, 1005L, "CMD-P",
                "{\"priceVersion\":\"PV-20260730-01\"}", NOW));
        assertEquals("PV-20260730-01", one("priceVersion").getParamValue());
        assertEquals(1, one("priceVersion").getRegisteredFlag());
    }

    // ==================== 回写前重校验：定义在下发后被收紧 ====================

    @Test
    void snapshotRejectedWhenDefinitionTightenedAfterDispatchAndLeavesEvidence() {
        // 下发时未登记 → 放行；result 回来之前运维把该键登记为 100..20000
        registerDef(PARAM_SYNC, "limitMl", 1, "100", "20000");

        // 下发的是 99999，此刻已越界：快照不得写入被判定为不合规的值
        assertEquals(0, paramService.applySyncedParams(DEVICE_ID, PARAM_SYNC, 1001L, "CMD-A",
                "{\"limitMl\":99999}", NOW));

        assertEquals(0, rowCount(), "不合规的值不得进入快照");
        assertEquals(1, rejectEventCount(), "被拒必须留痕，否则「快照为何没跟上」无从追查");
    }

    @Test
    void repeatedRejectionKeepsExactlyOneEvidence() {
        registerDef(PARAM_SYNC, "limitMl", 1, "100", "20000");
        for (int i = 0; i < 3; i++) {
            paramService.applySyncedParams(DEVICE_ID, PARAM_SYNC, 1001L, "CMD-A", "{\"limitMl\":99999}", NOW);
        }
        assertEquals(1, rejectEventCount(), "同一指令反复被拒，证据按 cmdNo 收敛为一条");
    }

    // ==================== 11/12. 只读查询 ====================

    @Test
    void currentParamsReturnsAllKnownParamsSortedByKey() {
        // P1-1 之后只有已登记键进快照；本例要钉的不是登记规则，故先登记再测其原意。
        registerDef(PARAM_SYNC, "alpha", 1, "0", "99999");
        registerDef(PARAM_SYNC, "mid", 1, "0", "99999");
        registerDef(PARAM_SYNC, "zeta", 1, "0", "99999");

        paramService.applySyncedParams(DEVICE_ID, PARAM_SYNC, 1001L, "CMD-A",
                "{\"zeta\":1,\"alpha\":2,\"mid\":3}", NOW);

        List<WsDeviceParam> params = paramService.currentParams(DEVICE_ID);
        assertEquals(List.of("alpha", "mid", "zeta"),
                params.stream().map(WsDeviceParam::getParamKey).toList(), "按键名升序，展示才稳定");
        assertTrue(params.stream().allMatch(p -> p.getParamVersion() == 1));
        assertTrue(params.stream().allMatch(p -> NOW.equals(p.getSyncTime())));
    }

    @Test
    void currentParamsOfUnsyncedDeviceReturnsEmptyNotError() {
        // P1-1 之后只有已登记键进快照；本例要钉的是设备维度隔离，故先登记。
        registerDef(PARAM_SYNC, "limitMl", 1, "0", "99999");
        paramService.applySyncedParams(DEVICE_ID, PARAM_SYNC, 1001L, "CMD-A", "{\"limitMl\":5000}", NOW);

        assertTrue(paramService.currentParams(OTHER_DEVICE).isEmpty(), "从未同步过的设备返回空集而非报错");
        assertTrue(paramService.currentParams(null).isEmpty(), "设备为空同样不得抛异常");
        // 设备维度必须隔离：别的设备的参数不能串进来
        assertEquals(1, paramService.currentParams(DEVICE_ID).size());
    }

    @Test
    void snapshotsOfDifferentDevicesAreIsolated() {
        // P1-1 之后只有已登记键进快照；本例要钉的不是登记规则，故先登记再测其原意。
        registerDef(PARAM_SYNC, "limitMl", 1, "0", "99999");

        paramService.applySyncedParams(DEVICE_ID, PARAM_SYNC, 1001L, "CMD-A", "{\"limitMl\":5000}", NOW);
        paramService.applySyncedParams(OTHER_DEVICE, PARAM_SYNC, 1002L, "CMD-B", "{\"limitMl\":8000}", NOW);

        assertEquals(2, rowCount(), "唯一键是 (设备,键)，不同设备的同名键各占一行");
        assertEquals("5000", paramService.currentParams(DEVICE_ID).get(0).getParamValue());
        assertEquals("8000", paramService.currentParams(OTHER_DEVICE).get(0).getParamValue());
    }

    // ==================== 非法入参 ====================

    @Test
    void missingDeviceOrCmdNoIsRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(com.jbk.tool.exception.JbkException.class,
                () -> paramService.applySyncedParams(null, PARAM_SYNC, 1001L, "CMD-A", "{\"a\":1}", NOW));
        org.junit.jupiter.api.Assertions.assertThrows(com.jbk.tool.exception.JbkException.class,
                () -> paramService.applySyncedParams(DEVICE_ID, PARAM_SYNC, 1000L, "  ", "{\"a\":1}", NOW));
        assertEquals(0, rowCount());
    }

    @Test
    void blankSyncTimeFallsBackToServerClockInsteadOfWritingEmpty() {
        // P1-1 之后只有已登记键进快照；本例要钉的不是登记规则，故先登记再测其原意。
        registerDef(PARAM_SYNC, "a", 1, "0", "99999");

        assertEquals(1, paramService.applySyncedParams(DEVICE_ID, PARAM_SYNC, 1001L, "CMD-A", "{\"a\":1}", null));
        // SYNC_TIME 是 NOT NULL 列：缺省必须回退到服务端时钟，不能写空串
        assertEquals(14, one("a").getSyncTime().length());
    }
}
