package com.jbk.serve.service.ops.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.api.ApiEmployeeMapper;
import com.jbk.serve.mapper.delivery.WsDeliveryMediaMapper;
import com.jbk.serve.mapper.device.WsCommandBatchMapper;
import com.jbk.serve.mapper.device.WsCommandMapper;
import com.jbk.serve.mapper.device.WsDeviceMapper;
import com.jbk.serve.mapper.ops.WsAlarmMapper;
import com.jbk.serve.mapper.ops.WsDomainEventMapper;
import com.jbk.serve.mapper.ops.WsWorkOrderMapper;
import com.jbk.serve.mapper.station.WsStationMapper;
import com.jbk.serve.service.delivery.DeliveryMediaStore;
import com.jbk.serve.service.delivery.IDeliveryMediaService;
import com.jbk.serve.service.delivery.impl.DeliveryDbSchema;
import com.jbk.serve.service.delivery.impl.DeliveryMediaServiceImpl;
import com.jbk.serve.service.ops.IWorkOrderService;
import com.jbk.serve.service.ops.IWsAlarmService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.delivery.DeliveryEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.device.po.WsCommand;
import com.jbk.tool.data.ops.bo.WorkOrderApplyBo;
import com.jbk.tool.data.ops.bo.WsWorkOrderBo;
import com.jbk.tool.data.ops.po.WsAlarm;
import com.jbk.tool.data.ops.po.WsWorkOrder;
import com.jbk.tool.data.ops.vo.WsWorkOrderVo;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E-05 包B/C 设备运营内核的<b>真实 MySQL + 真实 Spring 事务</b>集成测试。
 *
 * <p>钉住的性质全部依赖库层机制，Mock 证不了：</p>
 * <ol>
 *   <li><b>告警幂等</b>：20 并发 raise 恰好一条活动告警（uk_alarm_active_dedupe 物理裁决，
 *       不是先 COUNT 再 INSERT）；恢复清键后同型告警可再触发，历史终态共存。</li>
 *   <li><b>一告警一单</b>：并发转单由 uk_wo_alarm 收敛到同一张工单。</li>
 *   <li><b>机主申报幂等</b>：uk_wo_request 撞键读回原单，且读回必须核验申报人——
 *       他人 requestId 不泄露工单。</li>
 *   <li><b>工单状态机 CAS</b>：并发确认只有一个赢家；复核退回→再提交的审计幂等键
 *       不冲突（键掺 fromVersion，同动作两次 occurrence 是两条审计）。</li>
 *   <li><b>工单关闭释放告警活动键</b>（任务书 3.2）：关闭前同型告警被键压制，关闭后可再触发。</li>
 *   <li><b>批量聚合</b>：累加原子、计数满才置终态、settle 幂等；uk_cmd_batch_device
 *       阻止同批次同设备重复展开。</li>
 *   <li><b>证据身份域</b>：同 ID 的员工与用户登记同一张图得到不同媒体键，跨门户 claim 被拒。</li>
 * </ol>
 *
 * <p>无 Docker 环境自动跳过。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = DeviceOpsWorkOrderDbTest.Ctx.class)
class DeviceOpsWorkOrderDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_deviceops_it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static final long DEVICE_ID = 61L;
    private static final long OWNER_USER = 9L;
    private static final long OTHER_USER = 7L;
    private static final long OPERATOR = 5L;
    private static final long ASSIGNEE = 6L;
    private static final long DISABLED_EMPLOYEE = 8L;
    private static final String NOW = "20260730120000";

    @Configuration
    @EnableTransactionManagement
    static class Ctx {
        @Bean
        DataSource dataSource() {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl(MYSQL.getJdbcUrl());
            ds.setUsername(MYSQL.getUsername());
            ds.setPassword(MYSQL.getPassword());
            // 20 并发 raise + 转单并发（各自独立事务）需要富余连接
            ds.setMaximumPoolSize(24);
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
            // 分页查询（pageByApplicant/pageData）依赖分页拦截器统计 total；缺了 total 恒为 0
            com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor interceptor =
                    new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(
                    new com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor(
                            com.baomidou.mybatisplus.annotation.DbType.MYSQL));
            cfg.addInterceptor(interceptor);
            return new SqlSessionTemplate(factory.getObject());
        }

        @Bean
        MapperFactoryBean<WsAlarmMapper> wsAlarmMapper(SqlSessionTemplate t) {
            return mapper(WsAlarmMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsWorkOrderMapper> wsWorkOrderMapper(SqlSessionTemplate t) {
            return mapper(WsWorkOrderMapper.class, t);
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
        MapperFactoryBean<ApiEmployeeMapper> apiEmployeeMapper(SqlSessionTemplate t) {
            return mapper(ApiEmployeeMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsDomainEventMapper> wsDomainEventMapper(SqlSessionTemplate t) {
            return mapper(WsDomainEventMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsDeliveryMediaMapper> wsDeliveryMediaMapper(SqlSessionTemplate t) {
            return mapper(WsDeliveryMediaMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsCommandMapper> wsCommandMapper(SqlSessionTemplate t) {
            return mapper(WsCommandMapper.class, t);
        }

        @Bean
        MapperFactoryBean<WsCommandBatchMapper> wsCommandBatchMapper(SqlSessionTemplate t) {
            return mapper(WsCommandBatchMapper.class, t);
        }

        private static <M> MapperFactoryBean<M> mapper(Class<M> type, SqlSessionTemplate template) {
            MapperFactoryBean<M> bean = new MapperFactoryBean<>(type);
            bean.setSqlSessionTemplate(template);
            return bean;
        }

        @Bean
        IWsDomainEventService domainEventService() {
            // 真实现：工单审计与业务同事务（fail-closed）是被测性质，mock 证不了
            return new WsDomainEventServiceImpl();
        }

        @Bean
        IWsAlarmService alarmService() {
            return new WsAlarmServiceImpl();
        }

        @Bean
        DeliveryMediaStore mediaStore() {
            // 字节落地不在本测射程；行层归属/门户/占用校验全部真实
            return (mediaKey, content) -> { };
        }

        @Bean
        IDeliveryMediaService mediaService() {
            return new DeliveryMediaServiceImpl();
        }

        @Bean
        IWorkOrderService workOrderService() {
            return new WorkOrderServiceImpl();
        }

        @Bean
        org.mybatis.spring.mapper.MapperFactoryBean<com.jbk.serve.mapper.message.WsMessageMapper> wsMessageMapper(
                org.mybatis.spring.SqlSessionTemplate t) {
            org.mybatis.spring.mapper.MapperFactoryBean<com.jbk.serve.mapper.message.WsMessageMapper> bean =
                    new org.mybatis.spring.mapper.MapperFactoryBean<>(com.jbk.serve.mapper.message.WsMessageMapper.class);
            bean.setSqlSessionTemplate(t);
            return bean;
        }

        // E2E-07：工单迁移挂了申报人消息钩子，WorkOrderServiceImpl 新增本依赖
        @Bean
        com.jbk.serve.service.message.IWsMessageService messageService() {
            return new com.jbk.serve.service.message.impl.WsMessageServiceImpl();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    @Autowired
    private IWsAlarmService alarmService;
    @Autowired
    private IWorkOrderService workOrderService;
    @Autowired
    private IDeliveryMediaService mediaService;
    @Autowired
    private WsCommandBatchMapper batchMapper;
    @Autowired
    private WsCommandMapper commandMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        DeliveryDbSchema.createAll(jdbc);
        DeliveryDbSchema.truncateAll(jdbc);
        jdbc.update("INSERT INTO ws_station (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, "
                + "STATION_NAME, OWNER_USER_ID) VALUES (31, 0, 1, ?, 1, ?, '测试水站', ?)", NOW, NOW, OWNER_USER);
        jdbc.update("INSERT INTO ws_device (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, "
                + "DEVICE_NO, STATION_ID, OWNER_USER_ID, ONLINE_STATUS, RUN_STATUS) "
                + "VALUES (?, 0, 1, ?, 1, ?, 'IT-DEV-0061', 31, ?, 1, 1)", DEVICE_ID, NOW, NOW, OWNER_USER);
        jdbc.update("INSERT INTO api_employee (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, "
                + "EMPLOYEE_NAME, DISABLED_FLAG) VALUES (?, 0, 1, ?, 1, ?, '运维甲', 1)", ASSIGNEE, NOW, NOW);
        jdbc.update("INSERT INTO api_employee (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, "
                + "EMPLOYEE_NAME, DISABLED_FLAG) VALUES (?, 0, 1, ?, 1, ?, '已停用乙', 2)", DISABLED_EMPLOYEE, NOW, NOW);
    }

    // ------------------------------------------------------------------
    // 告警幂等（任务书 3.2 / S13 前半）
    // ------------------------------------------------------------------

    @Test
    void twentyConcurrentRaisesYieldExactlyOneActiveAlarm() throws Exception {
        runConcurrently(20, () -> {
            alarmService.raise(DEVICE_ID, OpsEnum.AlarmType.FAULT_CODE, 3, "故障码 E003", "E003");
            return null;
        });
        assertEquals(1, countAlarms(), "20 并发 raise 后只允许一行告警");
        assertEquals(1, countActiveAlarms(), "活动告警必须恰好一条");
    }

    @Test
    void recoverClearsKeySoSameAlarmCanRaiseAgainWhileHistoryCoexists() {
        alarmService.raise(DEVICE_ID, OpsEnum.AlarmType.FAULT_CODE, 3, "故障码 E003", "E003");
        assertTrue(alarmService.autoRecover(DEVICE_ID, OpsEnum.AlarmType.FAULT_CODE, "E003"));
        // 键已清空：同型故障再次触发是新的一条，历史终态行共存
        alarmService.raise(DEVICE_ID, OpsEnum.AlarmType.FAULT_CODE, 3, "故障码 E003 复发", "E003");
        assertEquals(2, countAlarms());
        assertEquals(1, countActiveAlarms());
        // 恢复只认键匹配：不同故障码的恢复动不了当前活动告警
        assertTrue(!alarmService.autoRecover(DEVICE_ID, OpsEnum.AlarmType.FAULT_CODE, "E001"));
        assertEquals(1, countActiveAlarms());
    }

    @Test
    void ignoreOnlyAcceptsPendingAndClearsKey() {
        alarmService.raise(DEVICE_ID, OpsEnum.AlarmType.DEVICE_OFFLINE, 2, "设备离线", null);
        Long alarmId = jdbc.queryForObject("SELECT ID FROM ws_alarm LIMIT 1", Long.class);
        assertTrue(alarmService.ignore(alarmId, OPERATOR, NOW));
        assertTrue(!alarmService.ignore(alarmId, OPERATOR, NOW), "重复忽略必须明确失败");
        assertNull(jdbc.queryForObject("SELECT ACTIVE_DEDUPE_KEY FROM ws_alarm WHERE ID = ?",
                String.class, alarmId));
    }

    // ------------------------------------------------------------------
    // 一告警一单（S13 后半）
    // ------------------------------------------------------------------

    @Test
    void concurrentAlarmToOrderConvergesToSingleWorkOrder() throws Exception {
        alarmService.raise(DEVICE_ID, OpsEnum.AlarmType.FAULT_CODE, 3, "故障码 E003", "E003");
        Long alarmId = jdbc.queryForObject("SELECT ID FROM ws_alarm LIMIT 1", Long.class);

        List<Object> outcomes = runConcurrently(8, () -> {
            try {
                return workOrderService.createFromAlarm(alarmId, OPERATOR);
            } catch (JbkException e) {
                return e; // 输方明确拒绝可接受；静默双单不可接受
            }
        });
        long orders = jdbc.queryForObject("SELECT COUNT(*) FROM ws_work_order WHERE ALARM_ID = ?",
                Long.class, alarmId);
        assertEquals(1L, orders, "并发转单必须收敛到一张工单");
        assertTrue(outcomes.stream().anyMatch(o -> o instanceof Long), "至少一个线程拿到工单ID");
        assertEquals(2, jdbc.queryForObject("SELECT ALARM_STATUS FROM ws_alarm WHERE ID = ?",
                Integer.class, alarmId), "告警必须回链为已转工单");
    }

    // ------------------------------------------------------------------
    // 机主申报（3.3 / S14 起点）
    // ------------------------------------------------------------------

    @Test
    void ownerApplyIsIdempotentByRequestIdAndGuardsOwnershipAndIdentity() {
        WorkOrderApplyBo bo = new WorkOrderApplyBo();
        bo.setRequestId("REQ-IT-001");
        bo.setDeviceId(DEVICE_ID);
        bo.setWorkType(OpsEnum.WorkOrderType.REPAIR.getValue());
        bo.setOrderContent("出水口漏水");

        Long first = workOrderService.ownerApply(bo, OWNER_USER);
        Long second = workOrderService.ownerApply(bo, OWNER_USER);
        assertEquals(first, second, "同 requestId 重复提交必须返回原工单");
        assertEquals(1L, (long) jdbc.queryForObject("SELECT COUNT(*) FROM ws_work_order", Long.class));

        WorkOrderApplyBo changed = new WorkOrderApplyBo();
        changed.setRequestId(bo.getRequestId());
        changed.setDeviceId(DEVICE_ID);
        changed.setWorkType(OpsEnum.WorkOrderType.PARTS.getValue());
        changed.setOrderContent("改成配件申请");
        JbkException mismatch = assertThrows(JbkException.class,
                () -> workOrderService.ownerApply(changed, OWNER_USER));
        assertEquals("同一 requestId 不可更换申报参数", mismatch.getMessage());
        assertEquals(1L, (long) jdbc.queryForObject("SELECT COUNT(*) FROM ws_work_order", Long.class),
                "幂等参数冲突不得新增工单");

        // 他人设备：归属只认 ws_device.OWNER_USER_ID，前端声明无效
        WorkOrderApplyBo foreign = new WorkOrderApplyBo();
        foreign.setRequestId("REQ-IT-002");
        foreign.setDeviceId(DEVICE_ID);
        foreign.setWorkType(OpsEnum.WorkOrderType.REPAIR.getValue());
        foreign.setOrderContent("不是我的设备");
        assertThrows(JbkException.class, () -> workOrderService.ownerApply(foreign, OTHER_USER));

        // 他人 requestId 撞键：不得读回别人的工单（存在性不泄露）
        WsWorkOrder mine = workOrderService.getById(first);
        jdbc.update("UPDATE ws_device SET OWNER_USER_ID = ? WHERE ID = ?", OTHER_USER, DEVICE_ID);
        WorkOrderApplyBo hijack = new WorkOrderApplyBo();
        hijack.setRequestId(mine.getRequestId());
        hijack.setDeviceId(DEVICE_ID);
        hijack.setWorkType(OpsEnum.WorkOrderType.REPAIR.getValue());
        hijack.setOrderContent("撞他人幂等键");
        assertThrows(JbkException.class, () -> workOrderService.ownerApply(hijack, OTHER_USER));
    }

    // ------------------------------------------------------------------
    // 状态机全链 + CAS（S14/S15 内核）
    // ------------------------------------------------------------------

    @Test
    void fullLifecycleWithReviewReturnLoopAndScopedQueries() {
        Long id = applyRepairOrder("REQ-IT-100");

        // 分配必须是有效员工：停用员工拒绝
        workOrderService.confirm(id, OPERATOR);
        assertThrows(JbkException.class, () -> workOrderService.assign(id, DISABLED_EMPLOYEE, OPERATOR));
        workOrderService.assign(id, ASSIGNEE, OPERATOR);

        workOrderService.submitResult(id, "更换滤芯完成", null, ASSIGNEE);
        // 复核退回 → 再提交（同动作第二次 occurrence，审计键不得冲突）→ 复核通过
        workOrderService.reviewReturn(id, "照片不清晰，重拍", OPERATOR);
        workOrderService.submitResult(id, "补拍证据后重新提交", null, ASSIGNEE);
        workOrderService.reviewPass(id, "验收通过", OPERATOR);

        WsWorkOrder closed = workOrderService.getById(id);
        assertEquals(OpsEnum.WorkOrderStatus.CLOSED.getValue(), closed.getOrderStatus());
        assertNotNull(closed.getCloseTime());
        // 终态不再受理任何动作
        assertThrows(JbkException.class, () -> workOrderService.reviewReturn(id, "想反悔", OPERATOR));

        // 完整轨迹：申报/确认/分配/提交/退回/再提交/关闭 = 7 条审计
        WsWorkOrderVo detail = workOrderService.getData(id);
        assertEquals(7, detail.getTrace().size(), "状态轨迹必须完整覆盖每次动作");

        // 机主视角：本人可见，他人按不存在处理
        assertEquals(1L, workOrderService.pageByApplicant(pageBo(), OWNER_USER).getTotal());
        assertEquals(0L, workOrderService.pageByApplicant(pageBo(), OTHER_USER).getTotal());
        assertThrows(JbkException.class, () -> workOrderService.getByApplicant(id, OTHER_USER));
    }

    /**
     * 列表联查的可空外键：非设备类工单无 DEVICE_ID、未分配工单无 ASSIGNEE_ID。
     * 空集分支返回 {@code Map.of()}——不可变映射 {@code get(null)} 抛 NPE，
     * 「整页没有任何工单带处理人」时接口会 500（PC 实点抓到的缺陷，自动化场景先分配后查询恰好绕过）。
     */
    @Test
    void listingWorksWhenNoWorkOrderHasAssigneeOrDevice() {
        applyRepairOrder("REQ-IT-NULLREF");
        // 无设备的后台工单：DEVICE_ID 与 ASSIGNEE_ID 同时为空
        WsWorkOrderBo bo = new WsWorkOrderBo();
        bo.setWorkType(OpsEnum.WorkOrderType.PARTS.getValue());
        bo.setOrderTitle("无设备配件工单");
        workOrderService.createInspection(bo, OPERATOR);

        PageDataVo<WsWorkOrderVo> page = workOrderService.pageData(pageBo());
        assertEquals(2L, page.getTotal(), "两张未分配工单都应正常列出");
        page.getList().forEach(vo -> assertNull(vo.getAssigneeName(), "未分配工单不应有处理人姓名"));
        assertEquals(1L, workOrderService.pageByApplicant(pageBo(), OWNER_USER).getTotal(),
                "机主视角同样不得因空联查键崩溃");
    }

    @Test
    void concurrentConfirmHasExactlyOneWinner() throws Exception {
        Long id = applyRepairOrder("REQ-IT-200");
        List<Object> outcomes = runConcurrently(2, () -> {
            try {
                workOrderService.confirm(id, OPERATOR);
                return Boolean.TRUE;
            } catch (JbkException e) {
                return e;
            }
        });
        long winners = outcomes.stream().filter(Boolean.TRUE::equals).count();
        assertEquals(1L, winners, "并发确认必须恰好一个赢家");
        assertEquals(OpsEnum.WorkOrderStatus.WAIT_ASSIGN.getValue(),
                workOrderService.getById(id).getOrderStatus());
        assertEquals(2, workOrderService.getById(id).getVersion(), "VERSION 只被赢家递增一次");
    }

    @Test
    void reviewPassReleasesAlarmActiveKeySoSameFaultCanAlarmAgain() {
        alarmService.raise(DEVICE_ID, OpsEnum.AlarmType.FAULT_CODE, 3, "故障码 E003", "E003");
        Long alarmId = jdbc.queryForObject("SELECT ID FROM ws_alarm LIMIT 1", Long.class);
        Long orderId = workOrderService.createFromAlarm(alarmId, OPERATOR);

        // 工单处理期间活动键仍在：同型故障被压制（撞键静默），不产生第二条
        alarmService.raise(DEVICE_ID, OpsEnum.AlarmType.FAULT_CODE, 3, "故障码 E003 又报", "E003");
        assertEquals(1, countAlarms());

        workOrderService.assign(orderId, ASSIGNEE, OPERATOR);
        workOrderService.submitResult(orderId, "现场修复", null, ASSIGNEE);
        workOrderService.reviewPass(orderId, null, OPERATOR);

        // 键已随工单关闭释放（状态保持已转工单）；同型故障可再报
        assertNull(jdbc.queryForObject("SELECT ACTIVE_DEDUPE_KEY FROM ws_alarm WHERE ID = ?",
                String.class, alarmId));
        assertEquals(2, jdbc.queryForObject("SELECT ALARM_STATUS FROM ws_alarm WHERE ID = ?",
                Integer.class, alarmId));
        alarmService.raise(DEVICE_ID, OpsEnum.AlarmType.FAULT_CODE, 3, "故障码 E003 复发", "E003");
        assertEquals(2, countAlarms());
    }

    // ------------------------------------------------------------------
    // 批量聚合（S11 内核）
    // ------------------------------------------------------------------

    @Test
    void batchSettlesOnlyWhenCountsCompleteAndPartialNeverShowsAllSuccess() {
        Long batchId = seedBatch(3);
        assertEquals(1, batchMapper.accumulateChildTerminal(batchId, 1, 0, 0, NOW));
        assertEquals(0, batchMapper.settleIfComplete(batchId, NOW), "计数未满不得置终态");
        assertEquals(1, batchMapper.accumulateChildTerminal(batchId, 0, 1, 0, NOW));
        assertEquals(1, batchMapper.accumulateChildTerminal(batchId, 0, 0, 1, NOW));
        assertEquals(1, batchMapper.settleIfComplete(batchId, NOW));
        assertEquals(0, batchMapper.settleIfComplete(batchId, NOW), "settle 幂等，第二次没有可推进的前态");
        assertEquals(3, jdbc.queryForObject("SELECT BATCH_STATUS FROM ws_command_batch WHERE ID = ?",
                Integer.class, batchId), "有成有败必须是部分成功，绝不显示全部成功");

        Long allSuccess = seedBatch(1);
        batchMapper.accumulateChildTerminal(allSuccess, 1, 0, 0, NOW);
        batchMapper.settleIfComplete(allSuccess, NOW);
        assertEquals(2, jdbc.queryForObject("SELECT BATCH_STATUS FROM ws_command_batch WHERE ID = ?",
                Integer.class, allSuccess));

        Long allFail = seedBatch(2);
        batchMapper.accumulateChildTerminal(allFail, 0, 1, 0, NOW);
        batchMapper.accumulateChildTerminal(allFail, 0, 0, 1, NOW);
        batchMapper.settleIfComplete(allFail, NOW);
        assertEquals(4, jdbc.queryForObject("SELECT BATCH_STATUS FROM ws_command_batch WHERE ID = ?",
                Integer.class, allFail), "零成功（含全超时）归全部失败");
    }

    @Test
    void ukCmdBatchDeviceBlocksDuplicateChildExpansion() {
        Long batchId = seedBatch(2);
        commandMapper.insert(childCommand(batchId, DEVICE_ID, "CMDIT001"));
        assertThrows(DuplicateKeyException.class,
                () -> commandMapper.insert(childCommand(batchId, DEVICE_ID, "CMDIT002")),
                "同批次同设备第二条子指令必须被唯一键拒绝");
    }

    @Test
    void interruptedExpansionSettlesMissingTargetsAsFailureAfterExistingChildrenTerminate() {
        Long batchId = seedBatch(2);
        commandMapper.insert(childCommand(batchId, DEVICE_ID, "CMDITCRASH"));
        jdbc.update("UPDATE ws_command SET CMD_STATUS = 5 WHERE BATCH_ID = ?", batchId);

        assertEquals(1, batchMapper.reconcileInterrupted(batchId, NOW));
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT BATCH_STATUS,SUCCESS_COUNT,FAIL_COUNT,TIMEOUT_COUNT FROM ws_command_batch WHERE ID = ?",
                batchId);
        assertEquals(4, ((Number) row.get("BATCH_STATUS")).intValue(), "零成功应收敛为全部失败");
        assertEquals(0, ((Number) row.get("SUCCESS_COUNT")).intValue());
        assertEquals(2, ((Number) row.get("FAIL_COUNT")).intValue(), "一条执行失败 + 一条未展开目标");
        assertEquals(0, batchMapper.reconcileInterrupted(batchId, NOW), "终态批次重复对账必须幂等");
    }

    // ------------------------------------------------------------------
    // 证据身份域（媒体门户隔离）
    // ------------------------------------------------------------------

    @Test
    void workOrderEvidenceKeysAreSeparatedByPortalAndClaimsRejectCrossPortal() {
        byte[] content = "同一张图".getBytes(StandardCharsets.UTF_8);
        // 员工 9 与用户 9：ID 数值相同，键必须不同（门户参与派生）
        String employeeKey = mediaService.registerAs(1, OWNER_USER,
                DeliveryEnum.MediaPurpose.WORK_ORDER, content, "image/png", NOW);
        String userKey = mediaService.registerAs(2, OWNER_USER,
                DeliveryEnum.MediaPurpose.WORK_ORDER, content, "image/png", NOW);
        assertNotEquals(employeeKey, userKey);

        // 跨门户 claim：用户键以员工身份占用必须整体失败
        assertThrows(JbkException.class, () -> mediaService.claimForTaskAs(
                1, List.of(userKey), 900L, OWNER_USER,
                DeliveryEnum.MediaPurpose.WORK_ORDER, "证据校验未通过"));
        // 同门户正常占用一次成功、二次（已占用）失败
        mediaService.claimForTaskAs(2, List.of(userKey), 900L, OWNER_USER,
                DeliveryEnum.MediaPurpose.WORK_ORDER, "证据校验未通过");
        assertThrows(JbkException.class, () -> mediaService.claimForTaskAs(
                2, List.of(userKey), 901L, OWNER_USER,
                DeliveryEnum.MediaPurpose.WORK_ORDER, "证据校验未通过"));
    }

    // ------------------------------------------------------------------
    // 基座工具
    // ------------------------------------------------------------------

    private Long applyRepairOrder(String requestId) {
        WorkOrderApplyBo bo = new WorkOrderApplyBo();
        bo.setRequestId(requestId);
        bo.setDeviceId(DEVICE_ID);
        bo.setWorkType(OpsEnum.WorkOrderType.REPAIR.getValue());
        bo.setOrderContent("机主申报用例");
        return workOrderService.ownerApply(bo, OWNER_USER);
    }

    private Long seedBatch(int total) {
        jdbc.update("INSERT INTO ws_command_batch (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, "
                        + "BATCH_NO, SCOPE_TYPE, CMD_TYPE, TOTAL_COUNT, SUCCESS_COUNT, FAIL_COUNT, TIMEOUT_COUNT, "
                        + "BATCH_STATUS, VERSION) VALUES (0, 1, ?, 1, ?, ?, 1, 3, ?, 0, 0, 0, 1, 0)",
                NOW, NOW, "DCBIT" + System.nanoTime(), total);
        return jdbc.queryForObject("SELECT MAX(ID) FROM ws_command_batch", Long.class);
    }

    private WsCommand childCommand(Long batchId, Long deviceId, String cmdNo) {
        return new WsCommand().setCmdNo(cmdNo).setDeviceId(deviceId).setCmdType(3)
                .setCmdPayload("{}").setCmdStatus(1).setBatchId(batchId).setRetryCount(0);
    }

    private WsWorkOrderBo pageBo() {
        WsWorkOrderBo bo = new WsWorkOrderBo();
        bo.setCurrent(1L);
        bo.setSize(10L);
        return bo;
    }

    private int countAlarms() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ws_alarm", Integer.class);
    }

    private int countActiveAlarms() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_alarm WHERE ACTIVE_DEDUPE_KEY IS NOT NULL", Integer.class);
    }

    private List<Object> runConcurrently(int threads, Callable<Object> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return task.call();
            }));
        }
        List<Object> outcomes = new ArrayList<>();
        for (Future<Object> f : futures) {
            outcomes.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        return outcomes;
    }
}
