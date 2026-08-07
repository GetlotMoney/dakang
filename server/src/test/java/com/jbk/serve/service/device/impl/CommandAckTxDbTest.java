package com.jbk.serve.service.device.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.device.WsCommandMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.service.device.IWaterCommandFailureTxService;
import com.jbk.serve.service.device.IWsCommandAckTxService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.device.DeviceEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.data.device.po.WsCommand;
import com.jbk.tool.exception.JbkException;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;

/**
 * ACK 原子性的<b>真实 MySQL + 真实 Spring 事务</b>测试（B18，R1-P1-3）。
 *
 * <p>Mock 证明不了这里的事：指令与订单是不是真的一起提交、一起回滚。ACK 入口在
 * {@code WsCommandServiceImpl}，事务边界被刻意拆到独立 Bean——同类自调用不过代理，
 * {@code @Transactional} 会静默失效，两张表又会各自独立提交，留下
 * 「command=FAILED、order=PAID」这种超时扫描也够不着的悬挂态。</p>
 *
 * <p>无 Docker 环境自动跳过。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = CommandAckTxDbTest.Ctx.class)
class CommandAckTxDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_ack_it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static final long COMMAND_ID = 501L;
    private static final long ORDER_ID = 601L;
    private static final long DEVICE_ID = 21L;
    private static final String CMD_NO = "CMD-ACK-TX";
    private static final String ACK_TIME = "20260803101500";

    @Configuration
    @EnableTransactionManagement
    static class Ctx {
        @Bean
        DataSource dataSource() {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl(MYSQL.getJdbcUrl());
            ds.setUsername(MYSQL.getUsername());
            ds.setPassword(MYSQL.getPassword());
            ds.setMaximumPoolSize(4);
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
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath:mapper/trade/*.xml"));
            return new SqlSessionTemplate(factory.getObject());
        }

        @Bean
        MapperFactoryBean<WsCommandMapper> wsCommandMapper(SqlSessionTemplate t) {
            MapperFactoryBean<WsCommandMapper> bean = new MapperFactoryBean<>(WsCommandMapper.class);
            bean.setSqlSessionTemplate(t);
            return bean;
        }

        @Bean
        MapperFactoryBean<WsOrderMapper> rawWsOrderMapper(SqlSessionTemplate t) {
            MapperFactoryBean<WsOrderMapper> bean = new MapperFactoryBean<>(WsOrderMapper.class);
            bean.setSqlSessionTemplate(t);
            return bean;
        }

        /**
         * spy 真实 Mapper：默认走真库，个别用例按需让订单 UPDATE 抛异常，
         * 以此制造「指令已改、订单写失败」的现场——这正是原子性要挡住的那一幕。
         */
        @Bean
        @Primary
        WsOrderMapper wsOrderMapper(@Qualifier("rawWsOrderMapper") WsOrderMapper raw) {
            return Mockito.spy(raw);
        }

        @Bean
        IWsDomainEventService domainEventService() {
            return Mockito.mock(IWsDomainEventService.class);
        }

        @Bean
        IWsCommandAckTxService ackTxService(WsCommandMapper c, WsOrderMapper o, IWsDomainEventService e) {
            return new WsCommandAckTxServiceImpl(c, o, e);
        }

        @Bean
        IWaterCommandFailureTxService waterCommandFailureTxService(
                WsCommandMapper c, WsOrderMapper o, IWsDomainEventService e) {
            return new WaterCommandFailureTxServiceImpl(c, o, e);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    @Autowired
    private IWsCommandAckTxService ackTxService;
    @Autowired
    private IWaterCommandFailureTxService waterCommandFailureTxService;
    @Autowired
    private WsOrderMapper orderMapperSpy;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private IWsDomainEventService eventServiceMock;

    @BeforeEach
    void reset() {
        Mockito.reset(orderMapperSpy, eventServiceMock);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_command (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  CMD_NO VARCHAR(64), DEVICE_ID BIGINT, ORDER_ID BIGINT, BATCH_ID BIGINT NULL,
                  CMD_TYPE TINYINT, CMD_PAYLOAD TEXT, CMD_STATUS TINYINT, RETRY_COUNT INT,
                  SENT_TIME VARCHAR(20) NULL, ACK_TIME VARCHAR(20) NULL, FINISH_TIME VARCHAR(20) NULL,
                  RESULT_PAYLOAD TEXT NULL, FAIL_REASON VARCHAR(500) NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_order (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  ORDER_NO VARCHAR(64), ORDER_TYPE TINYINT, USER_ID BIGINT,
                  STATION_ID BIGINT, DEVICE_ID BIGINT, OUTLET_ID BIGINT, CARD_ID BIGINT,
                  PACKAGE_ID BIGINT NULL, PACKAGE_SNAP MEDIUMTEXT NULL,
                  PLAN_ML BIGINT NULL, ACTUAL_ML BIGINT NULL, ORDER_AMOUNT BIGINT,
                  PAY_WAY TINYINT, ORDER_STATUS TINYINT, CMD_ID BIGINT NULL,
                  FINISH_TIME VARCHAR(20) NULL, CANCEL_REASON VARCHAR(500) NULL,
                  REFERRER_USER_ID BIGINT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("TRUNCATE TABLE ws_command");
        jdbc.execute("TRUNCATE TABLE ws_order");
        seedOrder(TradeEnum.OrderStatus.PAID.getValue());
        jdbc.update("INSERT INTO ws_command(ID,DATA_STATUS,CMD_NO,DEVICE_ID,ORDER_ID,CMD_TYPE,CMD_STATUS,"
                        + "RETRY_COUNT,SENT_TIME) VALUES(?,0,?,?,?,1,?,0,?)",
                COMMAND_ID, CMD_NO, DEVICE_ID, ORDER_ID,
                DeviceEnum.CmdStatus.SENT.getValue(), "20260803101400");
    }

    private void seedOrder(int status) {
        jdbc.update("DELETE FROM ws_order WHERE ID=?", ORDER_ID);
        jdbc.update("INSERT INTO ws_order(ID,DATA_STATUS,ORDER_NO,ORDER_TYPE,USER_ID,STATION_ID,DEVICE_ID,"
                        + "OUTLET_ID,CARD_ID,PLAN_ML,ORDER_AMOUNT,PAY_WAY,ORDER_STATUS,CMD_ID) "
                        + "VALUES(?,0,?,1,9,41,?,31,100,5000,100,2,?,?)",
                ORDER_ID, "WOACKTX", DEVICE_ID, status, COMMAND_ID);
    }

    private int commandStatus() {
        return jdbc.queryForObject("SELECT CMD_STATUS FROM ws_command WHERE ID=?", Integer.class, COMMAND_ID);
    }

    private int orderStatus() {
        return jdbc.queryForObject("SELECT ORDER_STATUS FROM ws_order WHERE ID=?", Integer.class, ORDER_ID);
    }

    // ==================== R3-P1：失败/超时指令、订单与可靠事件统一事务 ====================

    @Test
    void dispatchFailureCommitsCommandAndOrderTogether() {
        assertTrue(waterCommandFailureTxService.converge(CMD_NO,
                DeviceEnum.CmdStatus.SENT.getValue(), DeviceEnum.CmdStatus.FAILED,
                ACK_TIME, "下发失败：MQTT不可用", "出水指令下发失败，待补偿处理", "下发失败"));

        assertEquals(DeviceEnum.CmdStatus.FAILED.getValue(), commandStatus());
        assertEquals(TradeEnum.OrderStatus.ABNORMAL.getValue(), orderStatus());
        Mockito.verify(eventServiceMock).recordReliableOnceAs(OpsEnum.ActorPortal.SYSTEM, null,
                OpsEnum.EventType.COMMAND_STATUS, CMD_NO, "CMD_FAIL:" + COMMAND_ID,
                DeviceEnum.CmdStatus.SENT.getDesc(), DeviceEnum.CmdStatus.FAILED.getDesc());
        Mockito.verify(eventServiceMock).recordReliableOnceAs(OpsEnum.ActorPortal.SYSTEM, null,
                OpsEnum.EventType.ORDER_STATUS, "WOACKTX", "WATER_FALLBACK:" + COMMAND_ID,
                TradeEnum.OrderStatus.PAID.getDesc(), TradeEnum.OrderStatus.ABNORMAL.getDesc() + "（下发失败）");
    }

    @Test
    void resultTimeoutCommitsCommandAndOrderTogether() {
        jdbc.update("UPDATE ws_command SET CMD_STATUS=? WHERE ID=?",
                DeviceEnum.CmdStatus.ACKED.getValue(), COMMAND_ID);
        seedOrder(TradeEnum.OrderStatus.DISPENSING.getValue());

        assertTrue(waterCommandFailureTxService.converge(CMD_NO,
                DeviceEnum.CmdStatus.ACKED.getValue(), DeviceEnum.CmdStatus.TIMEOUT,
                ACK_TIME, "回执后未收到执行结果", "出水指令超时，待补偿处理", "结果超时"));

        assertEquals(DeviceEnum.CmdStatus.TIMEOUT.getValue(), commandStatus());
        assertEquals(TradeEnum.OrderStatus.ABNORMAL.getValue(), orderStatus());
    }

    @Test
    void reliableFailureEventErrorRollsBackCommandAndOrder() {
        Mockito.doThrow(new IllegalStateException("模拟可靠事件写入失败"))
                .when(eventServiceMock).recordReliableOnceAs(any(), any(), any(), any(), any(), any(), any());

        assertThrows(IllegalStateException.class, () -> waterCommandFailureTxService.converge(CMD_NO,
                DeviceEnum.CmdStatus.SENT.getValue(), DeviceEnum.CmdStatus.FAILED,
                ACK_TIME, "下发失败", "出水指令下发失败，待补偿处理", "下发失败"));

        assertEquals(DeviceEnum.CmdStatus.SENT.getValue(), commandStatus(), "事件失败时指令必须保持可重试前态");
        assertEquals(TradeEnum.OrderStatus.PAID.getValue(), orderStatus(), "订单不得形成已变6但无事件的组合");
    }

    @Test
    void terminalOrderIsNotDowngradedButGetsReliableNoopEvent() {
        seedOrder(TradeEnum.OrderStatus.FINISHED.getValue());

        assertTrue(waterCommandFailureTxService.converge(CMD_NO,
                DeviceEnum.CmdStatus.SENT.getValue(), DeviceEnum.CmdStatus.FAILED,
                ACK_TIME, "下发失败", "出水指令下发失败，待补偿处理", "下发失败"));

        assertEquals(DeviceEnum.CmdStatus.FAILED.getValue(), commandStatus());
        assertEquals(TradeEnum.OrderStatus.FINISHED.getValue(), orderStatus(), "已完成订单不得降级为异常");
        Mockito.verify(eventServiceMock).recordReliableOnceAs(OpsEnum.ActorPortal.SYSTEM, null,
                OpsEnum.EventType.ORDER_STATUS, "WOACKTX", "WATER_FALLBACK:" + COMMAND_ID,
                TradeEnum.OrderStatus.FINISHED.getDesc(),
                "兜底未改单：订单已处于" + TradeEnum.OrderStatus.FINISHED.getDesc() + "（下发失败）");
    }

    @Test
    void failureConvergenceRejectsBrokenOrderLinkWithoutAnyStateChange() {
        jdbc.update("UPDATE ws_order SET CMD_ID=? WHERE ID=?", COMMAND_ID + 1, ORDER_ID);

        assertThrows(JbkException.class, () -> waterCommandFailureTxService.converge(CMD_NO,
                DeviceEnum.CmdStatus.SENT.getValue(), DeviceEnum.CmdStatus.FAILED,
                ACK_TIME, "下发失败", "出水指令下发失败，待补偿处理", "下发失败"));

        assertEquals(DeviceEnum.CmdStatus.SENT.getValue(), commandStatus());
        assertEquals(TradeEnum.OrderStatus.PAID.getValue(), orderStatus());
        Mockito.verifyNoInteractions(eventServiceMock);
    }

    @Test
    void concurrentTerminalTransitionIsAnIdempotentMiss() {
        jdbc.update("UPDATE ws_command SET CMD_STATUS=? WHERE ID=?",
                DeviceEnum.CmdStatus.SUCCESS.getValue(), COMMAND_ID);

        assertFalse(waterCommandFailureTxService.converge(CMD_NO,
                DeviceEnum.CmdStatus.SENT.getValue(), DeviceEnum.CmdStatus.FAILED,
                ACK_TIME, "下发失败", "出水指令下发失败，待补偿处理", "下发失败"));

        assertEquals(DeviceEnum.CmdStatus.SUCCESS.getValue(), commandStatus());
        assertEquals(TradeEnum.OrderStatus.PAID.getValue(), orderStatus());
        Mockito.verifyNoInteractions(eventServiceMock);
    }

    // 订单写入炸掉 → 指令那一步也必须一起回滚，绝不留「指令已终态、订单还停在已支付」
    @Test
    void orderUpdateFailureRollsBackCommandTransition() {
        Mockito.doThrow(new IllegalStateException("模拟订单写入失败"))
                .when(orderMapperSpy).update(isNull(), any(Wrapper.class));

        assertThrows(IllegalStateException.class,
                () -> ackTxService.applyAck(DEVICE_ID, CMD_NO, "", ACK_TIME));

        assertEquals(DeviceEnum.CmdStatus.SENT.getValue(), commandStatus(), "指令必须回滚回已下发");
        assertEquals(TradeEnum.OrderStatus.PAID.getValue(), orderStatus(), "订单必须保持原状态");
    }

    // 空 ACK 正常路径：指令 5失败 与订单 6异常待补偿 同时提交
    @Test
    void blankAckCommitsCommandFailedAndOrderAbnormalTogether() {
        assertNotNull(ackTxService.applyAck(DEVICE_ID, CMD_NO, "", ACK_TIME));

        assertEquals(DeviceEnum.CmdStatus.FAILED.getValue(), commandStatus());
        assertEquals(TradeEnum.OrderStatus.ABNORMAL.getValue(), orderStatus());
        assertEquals("ACK 缺少 ackCode",
                jdbc.queryForObject("SELECT FAIL_REASON FROM ws_command WHERE ID=?", String.class, COMMAND_ID));
    }

    // accepted 正常路径：指令 3已回执 与订单 3出水中 同时提交
    @Test
    void acceptedAckCommitsCommandAckedAndOrderDispensingTogether() {
        assertNotNull(ackTxService.applyAck(DEVICE_ID, CMD_NO, "accepted", ACK_TIME));

        assertEquals(DeviceEnum.CmdStatus.ACKED.getValue(), commandStatus());
        assertEquals(TradeEnum.OrderStatus.DISPENSING.getValue(), orderStatus());
        assertEquals(ACK_TIME,
                jdbc.queryForObject("SELECT ACK_TIME FROM ws_command WHERE ID=?", String.class, COMMAND_ID));
    }

    // 订单影响 0 行但仍停在出水链活动态：fail-closed 回滚，不得报成功
    @Test
    void failClosedWhenOrderStaysActiveAfterZeroRowUpdate() {
        // 订单已被推进到 3出水中：accepted 分支的 CAS（WHERE ORDER_STATUS=2）影响 0 行，
        // 但 3 正是本次要推进到的目标状态，属合法幂等——用 6 之外的活动态构造真正的异常现场
        Mockito.doReturn(0).when(orderMapperSpy).update(isNull(), any(Wrapper.class));

        JbkException denied = assertThrows(JbkException.class,
                () -> ackTxService.applyAck(DEVICE_ID, CMD_NO, "accepted", ACK_TIME));

        assertTrue(denied.getMsg().contains("影响 0 行"), "拒因必须点明订单没被推动，实际=" + denied.getMsg());
        assertEquals(DeviceEnum.CmdStatus.SENT.getValue(), commandStatus(), "指令必须一并回滚");
        assertEquals(TradeEnum.OrderStatus.PAID.getValue(), orderStatus());
    }

    // 订单已进入合法的更后状态：影响 0 行属幂等命中，指令仍推进、只留审计
    @Test
    void zeroRowUpdateIsIdempotentWhenOrderAlreadyAdvanced() {
        seedOrder(TradeEnum.OrderStatus.FINISHED.getValue());

        assertNotNull(ackTxService.applyAck(DEVICE_ID, CMD_NO, "accepted", ACK_TIME));

        assertEquals(DeviceEnum.CmdStatus.ACKED.getValue(), commandStatus());
        assertEquals(TradeEnum.OrderStatus.FINISHED.getValue(), orderStatus(), "已完成订单不得被 ACK 拉回");
    }

    // ==================== R2-P1-3：订单白名单与共键闭合 ====================

    // 状态 1待支付：不在任何白名单内，fail-closed 并回滚指令
    @Test
    void unpaidOrderStatusFailsClosed() {
        seedOrder(TradeEnum.OrderStatus.UNPAID.getValue());

        assertThrows(JbkException.class, () -> ackTxService.applyAck(DEVICE_ID, CMD_NO, "accepted", ACK_TIME));

        assertEquals(DeviceEnum.CmdStatus.SENT.getValue(), commandStatus(), "指令必须一并回滚");
        assertEquals(TradeEnum.OrderStatus.UNPAID.getValue(), orderStatus());
    }

    // 状态 NULL：显式枚举判定不得把它当成「更后状态」放过
    @Test
    void nullOrderStatusFailsClosed() {
        jdbc.update("UPDATE ws_order SET ORDER_STATUS=NULL WHERE ID=?", ORDER_ID);

        assertThrows(JbkException.class, () -> ackTxService.applyAck(DEVICE_ID, CMD_NO, "accepted", ACK_TIME));

        assertEquals(DeviceEnum.CmdStatus.SENT.getValue(), commandStatus());
    }

    // 未登记状态值：同样 fail-closed，不做数值大小推断
    @Test
    void unknownOrderStatusFailsClosed() {
        jdbc.update("UPDATE ws_order SET ORDER_STATUS=? WHERE ID=?", 99, ORDER_ID);

        assertThrows(JbkException.class, () -> ackTxService.applyAck(DEVICE_ID, CMD_NO, "", ACK_TIME));

        assertEquals(DeviceEnum.CmdStatus.SENT.getValue(), commandStatus());
    }

    // 充值/配送订单错挂到出水指令上：拒绝
    @Test
    void nonWaterOrderTypeFailsClosed() {
        jdbc.update("UPDATE ws_order SET ORDER_TYPE=? WHERE ID=?", 2, ORDER_ID);

        assertThrows(JbkException.class, () -> ackTxService.applyAck(DEVICE_ID, CMD_NO, "accepted", ACK_TIME));

        assertEquals(DeviceEnum.CmdStatus.SENT.getValue(), commandStatus());
    }

    // 订单绑定的是别的指令：一条被作废的旧指令的迟到 ACK 不得推进订单
    @Test
    void orderBoundToAnotherCommandFailsClosed() {
        jdbc.update("UPDATE ws_order SET CMD_ID=? WHERE ID=?", COMMAND_ID + 999, ORDER_ID);

        assertThrows(JbkException.class, () -> ackTxService.applyAck(DEVICE_ID, CMD_NO, "accepted", ACK_TIME));

        assertEquals(DeviceEnum.CmdStatus.SENT.getValue(), commandStatus());
        assertEquals(TradeEnum.OrderStatus.PAID.getValue(), orderStatus());
    }

    // 订单与指令设备共键错位：拒绝
    @Test
    void orderDeviceMismatchFailsClosed() {
        jdbc.update("UPDATE ws_order SET DEVICE_ID=? WHERE ID=?", DEVICE_ID + 5, ORDER_ID);

        assertThrows(JbkException.class, () -> ackTxService.applyAck(DEVICE_ID, CMD_NO, "accepted", ACK_TIME));

        assertEquals(DeviceEnum.CmdStatus.SENT.getValue(), commandStatus());
    }

    // 订单已逻辑删除：当前读取不到即拒绝
    @Test
    void logicallyDeletedOrderFailsClosed() {
        jdbc.update("UPDATE ws_order SET DATA_STATUS=1 WHERE ID=?", ORDER_ID);

        assertThrows(JbkException.class, () -> ackTxService.applyAck(DEVICE_ID, CMD_NO, "accepted", ACK_TIME));

        assertEquals(DeviceEnum.CmdStatus.SENT.getValue(), commandStatus());
    }

    // 错设备 ACK：目标指令一字不改
    @Test
    void foreignDeviceAckLeavesCommandUntouched() {
        assertNull(ackTxService.applyAck(DEVICE_ID + 1, CMD_NO, "accepted", ACK_TIME));

        assertEquals(DeviceEnum.CmdStatus.SENT.getValue(), commandStatus());
        assertEquals(TradeEnum.OrderStatus.PAID.getValue(), orderStatus());
    }

    // 可靠事件写入失败 → 指令与订单两表状态全部回滚
    @Test
    void reliableEventFailureRollsBackBothTables() {
        Mockito.doThrow(new IllegalStateException("模拟审计写入失败"))
                .when(eventServiceMock).recordReliableOnceAs(any(), any(), any(), any(), any(), any(), any());

        assertThrows(IllegalStateException.class,
                () -> ackTxService.applyAck(DEVICE_ID, CMD_NO, "accepted", ACK_TIME));

        assertEquals(DeviceEnum.CmdStatus.SENT.getValue(), commandStatus(), "状态成功、审计缺失是不允许的组合");
        assertEquals(TradeEnum.OrderStatus.PAID.getValue(), orderStatus());
    }

    // 拒绝证据必须独立于主事务存活：用同事务的静默写入记拒绝，回滚会把证据一起抹掉，
    // 而上游只在 ws_device_msg.HANDLE_REMARK 留一行文本，那张表没有任何读路径
    @Test
    void failClosedRejectionLeavesIndependentAudit() {
        jdbc.update("UPDATE ws_order SET CMD_ID=? WHERE ID=?", COMMAND_ID + 999, ORDER_ID);

        assertThrows(JbkException.class, () -> ackTxService.applyAck(DEVICE_ID, CMD_NO, "accepted", ACK_TIME));

        // recordReliable = REQUIRES_NEW 独立提交；用 recordByDevice 记就会随回滚消失
        Mockito.verify(eventServiceMock).recordReliable(
                eq(OpsEnum.EventType.COMMAND_STATUS), eq(CMD_NO), isNull(), any());
        assertEquals(DeviceEnum.CmdStatus.SENT.getValue(), commandStatus(), "拒绝仍必须整体回滚");
    }

    // 停止出水指令（cmdType=2）：按自身语义只推指令，不要求 order.cmdId 指向它，也不联动取水订单
    @Test
    void stopDispenseAckDoesNotTouchWaterOrder() {
        jdbc.update("UPDATE ws_command SET CMD_TYPE=? WHERE ID=?",
                DeviceEnum.CmdType.STOP_DISPENSE.getValue(), COMMAND_ID);
        jdbc.update("UPDATE ws_order SET CMD_ID=? WHERE ID=?", COMMAND_ID + 999, ORDER_ID);

        assertNotNull(ackTxService.applyAck(DEVICE_ID, CMD_NO, "accepted", ACK_TIME));

        assertEquals(DeviceEnum.CmdStatus.ACKED.getValue(), commandStatus());
        assertEquals(TradeEnum.OrderStatus.PAID.getValue(), orderStatus(), "停止 ACK 不得被当成开始出水 ACK");
    }

    // 重放：指令已终态，前态 CAS 落空 → 指令与订单都不动
    @Test
    void replayedAckChangesNeitherCommandNorOrder() {
        jdbc.update("UPDATE ws_command SET CMD_STATUS=? WHERE ID=?",
                DeviceEnum.CmdStatus.FAILED.getValue(), COMMAND_ID);

        assertNull(ackTxService.applyAck(DEVICE_ID, CMD_NO, "", ACK_TIME));

        assertEquals(DeviceEnum.CmdStatus.FAILED.getValue(), commandStatus());
        assertEquals(TradeEnum.OrderStatus.PAID.getValue(), orderStatus(), "重放不得二次改单");
    }
}
