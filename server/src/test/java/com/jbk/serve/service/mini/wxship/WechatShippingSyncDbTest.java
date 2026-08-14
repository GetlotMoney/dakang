package com.jbk.serve.service.mini.wxship;

import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.mall.WsMallPaymentMapper;
import com.jbk.serve.mapper.mall.WsWechatShippingOutboxMapper;
import com.jbk.serve.mapper.user.WsUserIdentityMapper;
import com.jbk.tool.config.system.mybatis.MpMetaObjectHandler;
import com.jbk.tool.consts.mini.WechatShippingEnum;
import com.jbk.tool.data.user.po.WsUser;
import com.jbk.tool.exception.JbkException;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发货同步 outbox 真库判定（WX-ECO S4）：登记分流（微信/Pay-Sim/无支付单）、
 * Worker 认领与终态、退避上限。建表直接执行真实迁移文件（抄本会漂移）。
 */
@Testcontainers
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = WechatShippingSyncDbTest.Ctx.class)
@DisplayName("发货同步 outbox 真库判定")
class WechatShippingSyncDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_wxship_it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    @Configuration
    static class Ctx {
        @Bean
        DataSource dataSource() {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl(MYSQL.getJdbcUrl());
            ds.setUsername(MYSQL.getUsername());
            ds.setPassword(MYSQL.getPassword());
            ds.setMaximumPoolSize(6);
            return ds;
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(DataSource ds) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(ds);
            com.baomidou.mybatisplus.core.MybatisConfiguration cfg =
                    new com.baomidou.mybatisplus.core.MybatisConfiguration();
            cfg.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(cfg);
            GlobalConfig globalConfig = new GlobalConfig();
            globalConfig.setMetaObjectHandler(new MpMetaObjectHandler());
            factory.setGlobalConfig(globalConfig);
            return new SqlSessionTemplate(factory.getObject());
        }

        @Bean
        MapperFactoryBean<WsWechatShippingOutboxMapper> outboxMapper(SqlSessionTemplate t) {
            MapperFactoryBean<WsWechatShippingOutboxMapper> b =
                    new MapperFactoryBean<>(WsWechatShippingOutboxMapper.class);
            b.setSqlSessionTemplate(t);
            return b;
        }

        @Bean
        MapperFactoryBean<WsMallPaymentMapper> paymentMapper(SqlSessionTemplate t) {
            MapperFactoryBean<WsMallPaymentMapper> b = new MapperFactoryBean<>(WsMallPaymentMapper.class);
            b.setSqlSessionTemplate(t);
            return b;
        }

        @Bean
        WsUserIdentityMapper identityMapper() {
            return Mockito.mock(WsUserIdentityMapper.class);
        }

        @Bean
        IWechatShippingClient shippingClient() {
            return Mockito.mock(IWechatShippingClient.class);
        }

        @Bean
        WechatShippingEnqueue enqueue(WsWechatShippingOutboxMapper m, WsMallPaymentMapper p) {
            return new WechatShippingEnqueue(m, p);
        }

        @Bean
        WechatShippingSyncWorker worker(WsWechatShippingOutboxMapper m, WsUserIdentityMapper i,
                                        IWechatShippingClient c) {
            WechatShippingSyncWorker w = new WechatShippingSyncWorker(m, i, c);
            ReflectionTestUtils.setField(w, "clientEnabled", true);
            return w;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    private static final long ORDER_ID = 60L;
    private static final String ORDER_NO = "MO0000000000000000000000000000AA";
    private static final long USER_ID = 7L;
    private static final long SHIPMENT_ID = 90L;
    private static final String OPENID = "oSHIP-PAYER";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private WechatShippingEnqueue enqueue;
    @Autowired
    private WechatShippingSyncWorker worker;
    @Autowired
    private WsUserIdentityMapper identityMapper;
    @Autowired
    private IWechatShippingClient shippingClient;

    @BeforeEach
    void cleanSlate() throws IOException {
        jdbc.execute("DROP TABLE IF EXISTS ws_wechat_shipping_outbox");
        applyMigration();
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_mall_payment (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT NOT NULL DEFAULT 0, CREATE_BY BIGINT NOT NULL,
                  CREATE_TIME VARCHAR(14) NOT NULL, UPDATE_BY BIGINT NOT NULL, UPDATE_TIME VARCHAR(14) NOT NULL,
                  ORDER_ID BIGINT NOT NULL, ORDER_NO VARCHAR(32) NOT NULL,
                  TRANSACTION_ID VARCHAR(64) NULL, PAY_AMOUNT_FEN BIGINT NOT NULL,
                  PAY_STATUS TINYINT NOT NULL, PAY_SOURCE TINYINT NOT NULL,
                  CURRENCY VARCHAR(16) NOT NULL DEFAULT 'CNY',
                  PAY_EXPIRE_TIME VARCHAR(14) NOT NULL,
                  PAY_SUCCESS_TIME VARCHAR(14) NULL, CLOSE_TIME VARCHAR(14) NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("TRUNCATE TABLE ws_mall_payment");
        Mockito.reset(identityMapper, shippingClient);
        WsUser user = new WsUser();
        user.setId(USER_ID);
        user.setWechatXcxOpenid(OPENID);
        user.setDataStatus(0);
        when(identityMapper.selectByIdIncludingDeleted(USER_ID)).thenReturn(user);
    }

    private void seedPayment(int paySource, String transactionId) {
        jdbc.update("INSERT INTO ws_mall_payment(DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,"
                        + "ORDER_ID,ORDER_NO,TRANSACTION_ID,PAY_AMOUNT_FEN,PAY_STATUS,PAY_SOURCE,"
                        + "PAY_EXPIRE_TIME,PAY_SUCCESS_TIME) VALUES(0,0,'20260813100000',0,'20260813100000',"
                        + "?,?,?,5500,2,?,'20260813103000','20260813100500')",
                ORDER_ID, ORDER_NO, transactionId, paySource);
    }

    private void enqueueDefault() {
        enqueue.enqueue(ORDER_ID, ORDER_NO, USER_ID, SHIPMENT_ID, 1, 1,
                WechatShippingEnum.LogisticsType.SAME_CITY, "SELF", null, "桶装水 x2");
    }

    private Map<String, Object> soleRow() {
        return jdbc.queryForMap("SELECT * FROM ws_wechat_shipping_outbox");
    }

    private int status(Map<String, Object> row) {
        return ((Number) row.get("PROCESSING_STATUS")).intValue();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("微信收款单：登记为待处理，交易号取自支付单")
    void wechatPaidOrderEnqueuesPending() {
        seedPayment(1, "4200000000000000001");
        enqueueDefault();
        Map<String, Object> row = soleRow();
        assertEquals(1, status(row));
        assertEquals("4200000000000000001", row.get("TRANSACTION_ID"));
        assertEquals("WXSHIP:" + ORDER_NO + ":1:1", row.get("BIZ_SYNC_KEY"));
    }

    @Test
    @DisplayName("Pay-Sim 单：登记即已处理+SKIP，Worker 扫不到、一次外呼都没有")
    void paySimOrderIsSkippedTerminally() {
        seedPayment(2, "SIMTX-9");
        enqueueDefault();
        Map<String, Object> row = soleRow();
        assertEquals(3, status(row), "Pay-Sim 单进了外呼循环——模拟单会被同步进真实微信发货台账");
        assertEquals("NOT_WECHAT_PAY", row.get("SKIP_REASON"));

        assertEquals(0, worker.runOnce("20260813110000"), "终态行被 Worker 认领了");
        // never() 必须用 any()：anyString 不匹配 null，带 null 参数的违规外呼会被放过
        verify(shippingClient, never()).uploadShippingInfo(Mockito.any(), anyInt(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("发货时查不到成功支付单：登记为需人工，不静默丢")
    void missingPaymentGoesManual() {
        enqueueDefault();
        assertEquals(5, status(soleRow()));
    }

    @Test
    @DisplayName("重复登记同一包裹：唯一键兜住，恒一行")
    void duplicateEnqueueKeepsSingleRow() {
        seedPayment(1, "4200000000000000001");
        enqueueDefault();
        enqueueDefault();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ws_wechat_shipping_outbox",
                Integer.class));
    }

    @Test
    @DisplayName("Worker 成功：外呼带 openid 与共键，行收敛已处理、SKIP 为空")
    void workerSyncsAndMarksProcessed() {
        seedPayment(1, "4200000000000000001");
        enqueueDefault();
        assertEquals(1, worker.runOnce("20260813110000"));
        verify(shippingClient).uploadShippingInfo(eq("4200000000000000001"),
                eq(2), eq("SELF"), Mockito.isNull(), eq("桶装水 x2"), eq(OPENID));
        Map<String, Object> row = soleRow();
        assertEquals(3, status(row));
        assertNull(row.get("SKIP_REASON"), "同步成功不得带 SKIP——与「没同步」必须可区分");
    }

    @Test
    @DisplayName("外呼失败走退避重试；连续失败到上限收敛需人工")
    void failuresBackOffThenGoManual() {
        seedPayment(1, "4200000000000000001");
        enqueueDefault();
        doThrow(new JbkException("上游拒绝")).when(shippingClient).uploadShippingInfo(
                anyString(), anyInt(), anyString(), Mockito.isNull(), anyString(), anyString());

        worker.runOnce("20260813110000");
        Map<String, Object> row = soleRow();
        assertEquals(4, status(row), "首败应转待重试");
        assertEquals(1, ((Number) row.get("RETRY_COUNT")).intValue());

        // 连续压到上限：每轮把 NEXT_RETRY_TIME 拨回，模拟退避已到点
        for (int i = 0; i < 5; i++) {
            jdbc.update("UPDATE ws_wechat_shipping_outbox SET NEXT_RETRY_TIME='20200101000000'");
            worker.runOnce("20260813110000");
        }
        assertEquals(5, status(soleRow()), "超上限必须收敛需人工，不能无限重试");
    }

    @Test
    @DisplayName("付款人无微信身份：需人工（确定性问题重试不自愈）")
    void missingOpenidGoesManual() {
        seedPayment(1, "4200000000000000001");
        enqueueDefault();
        when(identityMapper.selectByIdIncludingDeleted(USER_ID)).thenReturn(null);
        worker.runOnce("20260813110000");
        assertEquals(5, status(soleRow()));
        // never() 必须用 any()：anyString 不匹配 null，带 null 参数的违规外呼会被放过
        verify(shippingClient, never()).uploadShippingInfo(Mockito.any(), anyInt(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("任务书对抗⑨：自营与第三方两单并行同步不串包裹——键、运单、模式、交易号逐项各归各")
    void selfAndThirdPartyShipmentsNeverCrossContaminate() {
        seedPayment(1, "4200000000000000001");
        jdbc.update("INSERT INTO ws_mall_payment(DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,"
                        + "ORDER_ID,ORDER_NO,TRANSACTION_ID,PAY_AMOUNT_FEN,PAY_STATUS,PAY_SOURCE,"
                        + "PAY_EXPIRE_TIME,PAY_SUCCESS_TIME) VALUES(0,0,'20260813100000',0,'20260813100000',"
                        + "61,'MO0000000000000000000000000000BB','4200000000000000002',8800,2,1,"
                        + "'20260813103000','20260813100600')");
        // 自营：同城无运单；第三方：快递带承运商与运单
        enqueue.enqueue(ORDER_ID, ORDER_NO, USER_ID, SHIPMENT_ID, 1, 1,
                WechatShippingEnum.LogisticsType.SAME_CITY, "SELF", null, "桶装水 x2");
        enqueue.enqueue(61L, "MO0000000000000000000000000000BB", USER_ID, 91L, 1, 1,
                WechatShippingEnum.LogisticsType.EXPRESS, "SIM", "SF123456789", "净水器滤芯");

        assertEquals(2, worker.runOnce("20260813110000"));

        // 逐单捕获外呼参数：串包裹的形状是"B 单带着 A 单的运单/交易号出去"
        org.mockito.ArgumentCaptor<String> tx = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<Integer> lt = org.mockito.ArgumentCaptor.forClass(Integer.class);
        org.mockito.ArgumentCaptor<String> waybill = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(shippingClient, org.mockito.Mockito.times(2)).uploadShippingInfo(
                tx.capture(), lt.capture(), Mockito.any(), waybill.capture(), Mockito.any(), eq(OPENID));
        java.util.Map<String, Integer> ltByTx = new java.util.HashMap<>();
        java.util.Map<String, String> wbByTx = new java.util.HashMap<>();
        for (int i = 0; i < 2; i++) {
            ltByTx.put(tx.getAllValues().get(i), lt.getAllValues().get(i));
            wbByTx.put(tx.getAllValues().get(i), waybill.getAllValues().get(i));
        }
        assertEquals(2, ltByTx.size(), "两单共用了同一个交易号——串单");
        assertEquals(Integer.valueOf(2), ltByTx.get("4200000000000000001"), "自营单没按同城模式同步");
        assertEquals(Integer.valueOf(1), ltByTx.get("4200000000000000002"), "第三方单没按快递模式同步");
        assertNull(wbByTx.get("4200000000000000001"), "自营单带上了别人的运单号");
        assertEquals("SF123456789", wbByTx.get("4200000000000000002"), "第三方运单号错位");
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(DISTINCT BIZ_SYNC_KEY) FROM ws_wechat_shipping_outbox", Integer.class),
                "两单幂等键撞在一起");
    }

    // ------------------------------------------------------------------

    private void applyMigration() throws IOException {
        String sql = new String(Files.readAllBytes(
                repoRoot().resolve("deploy/mysql/migrations/2026-08-13-wechat-shipping-outbox.sql")),
                StandardCharsets.UTF_8);
        List<String> statements = new ArrayList<>();
        for (String stmt : sql.replaceAll("(?m)^\\s*--.*$", "").split(";")) {
            if (!stmt.isBlank()) {
                statements.add(stmt.trim());
            }
        }
        jdbc.execute((java.sql.Connection conn) -> {
            try (java.sql.Statement st = conn.createStatement()) {
                for (String stmt : statements) {
                    st.execute(stmt);
                }
            }
            return null;
        });
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("deploy/mysql/migrations"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("未找到仓库根");
        }
        return dir;
    }
}
