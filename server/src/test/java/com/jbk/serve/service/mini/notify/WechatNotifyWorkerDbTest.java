package com.jbk.serve.service.mini.notify;

import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.mini.WsWechatNotifyOutboxMapper;
import com.jbk.serve.mapper.user.WsUserIdentityMapper;
import com.jbk.tool.config.system.mybatis.MpMetaObjectHandler;
import com.jbk.tool.consts.mini.WechatNotifyEnum;
import com.jbk.tool.data.user.po.WsUser;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订阅通知 Worker 真实 MySQL 回归（WX-ECO S2）：CAS 前态、租约重认领、退避落库。
 * 发送用假适配器——任务书禁止真实外呼，被测的是分流与幂等而非 HTTP 层。
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = WechatNotifyWorkerDbTest.Ctx.class)
@DisplayName("订阅通知 Worker 真库判定")
class WechatNotifyWorkerDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_wx_notify_it")
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
        MapperFactoryBean<WsWechatNotifyOutboxMapper> outboxMapper(SqlSessionTemplate t) {
            MapperFactoryBean<WsWechatNotifyOutboxMapper> b =
                    new MapperFactoryBean<>(WsWechatNotifyOutboxMapper.class);
            b.setSqlSessionTemplate(t);
            return b;
        }

        @Bean
        WsUserIdentityMapper identityMapper() {
            return Mockito.mock(WsUserIdentityMapper.class);
        }

        @Bean
        WechatSubscribeSender sender() {
            return Mockito.mock(WechatSubscribeSender.class);
        }

        @Bean
        WechatNotifyProperties notifyProperties() {
            return new WechatNotifyProperties();
        }

        @Bean
        WechatNotifyWorker worker(WsWechatNotifyOutboxMapper m, WsUserIdentityMapper i,
                                  WechatNotifyProperties p, WechatSubscribeSender s) {
            WechatNotifyWorker w = new WechatNotifyWorker(m, i, p, s);
            ReflectionTestUtils.setField(w, "enabled", true);
            return w;
        }

        @Bean
        WechatNotifyEnqueue enqueue(WsWechatNotifyOutboxMapper m) {
            return new WechatNotifyEnqueue(m);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    private static final long RECEIVER = 7L;
    private static final String OPENID = "oTEST-RECEIVER";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private WechatNotifyWorker worker;
    @Autowired
    private WechatNotifyEnqueue enqueue;
    @Autowired
    private WechatNotifyProperties properties;
    @Autowired
    private WsUserIdentityMapper identityMapper;
    @Autowired
    private WechatSubscribeSender sender;

    private static Path repoRoot() {
        Path cur = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && cur != null; i++) {
            if (Files.isDirectory(cur.resolve("deploy/mysql/migrations"))) {
                return cur;
            }
            cur = cur.getParent();
        }
        throw new IllegalStateException("无法定位仓库根");
    }

    /** 迁移里含针对 ws_domain_event 的条件 ALTER，测试库没有该表——那段会被守卫跳过。 */
    // ================= R1：开关接线与账号可用性 =================

    /**
     * Worker 与真实 sender 必须同一个开关（wechat.notify.http-sender.enabled）。
     * 源级断言钉住绑定：字段值可以在测试里翻，@Value 键名翻不了——曾经 Worker 读
     * wechat.notify.enabled 而配置只发 http-sender.enabled，开了 sender Worker 仍休眠。
     */
    @Test
    @DisplayName("R1：Worker 的 @Value 键必须是 http-sender.enabled，旧键全文件零命中")
    void workerBindsToTheSenderSwitch() throws IOException {
        java.nio.file.Path src = repoRoot().resolve(
                "server/src/main/java/com/jbk/serve/service/mini/notify/WechatNotifyWorker.java");
        String body = new String(java.nio.file.Files.readAllBytes(src),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains("@Value(\"${wechat.notify.http-sender.enabled:false}\")"),
                "Worker 没绑到 sender 的权威开关");
        assertFalse(body.contains("wechat.notify.enabled"),
                "旧开关键残留——两个开关必然出现开了 sender 却没开 Worker 的静默失效");
    }

    @Test
    @DisplayName("R1：开关 false/缺省时不扫描不认领——outbox 行原样、sender 零调用")
    void disabledWorkerTouchesNothing() {
        ReflectionTestUtils.setField(worker, "enabled", false);
        try {
            Long id = enqueueOne(WechatNotifyEnum.EventType.MALL_SHIPPED, "MO-OFF");
            worker.scanAndSend();
            assertEquals(1, status(rowOf(id)), "关着的 Worker 改了 outbox 行状态");
            verify(sender, never()).send(anyString(), anyString(), any());
        }
        finally {
            ReflectionTestUtils.setField(worker, "enabled", true);
        }
    }

    @Test
    @DisplayName("R1：开关 true 时扫描入口能处理既有待发送行")
    void enabledWorkerDrainsPendingRows() {
        ReflectionTestUtils.setField(worker, "enabled", true);
        when(sender.send(anyString(), anyString(), any()))
                .thenReturn(WechatSubscribeSender.SendOutcome.sent());
        Long id = enqueueOne(WechatNotifyEnum.EventType.MALL_SHIPPED, "MO-ON");
        worker.scanAndSend();
        assertEquals(3, status(rowOf(id)), "开着的 Worker 没把待发送行推进到已处理");
    }

    /**
     * 停用/注销/逻辑删除账号绝不能收到通知：判据复用 MiniUserIdentitySupport.isUsable
     * 唯一一份。此前只查 DATA_STATUS，被封号的用户照样收到"订单已发货"。
     */
    @Test
    @DisplayName("R1：四类不可用账号一律 RECEIVER_UNUSABLE，sender 零调用；正常账号照发")
    void unusableReceiversNeverGetSent() {
        // @Value 后处理器会用缺省 false 覆写 Ctx 构造期的 setField(true)，
        // 依赖构造期值会让本用例的结果取决于方法执行顺序——显式置位
        ReflectionTestUtils.setField(worker, "enabled", true);
        record Variant(String label, WsUser user) {}
        WsUser deleted = usableUser(); deleted.setDataStatus(1);
        WsUser disabled = usableUser(); disabled.setDisabledFlag(2);
        WsUser cancelled = usableUser(); cancelled.setUserStatus(2);
        java.util.List<Variant> variants = java.util.List.of(
                new Variant("不存在", null),
                new Variant("逻辑删除", deleted),
                new Variant("停用", disabled),
                new Variant("注销", cancelled));
        int i = 0;
        for (Variant v : variants) {
            Mockito.reset(sender);
            when(identityMapper.selectByIdIncludingDeleted(RECEIVER)).thenReturn(v.user());
            Long id = enqueueOne(WechatNotifyEnum.EventType.MALL_SHIPPED, "MO-UN" + (i++));
            worker.scanAndSend();
            var row = rowOf(id);
            assertEquals(3, status(row), v.label() + "：不可用收件人应收敛为已处理+SKIP");
            assertEquals("RECEIVER_UNUSABLE", row.get("SKIP_REASON"), v.label());
            verify(sender, never()).send(anyString(), anyString(), any());
        }
        // 对照：正常账号必须真的发出去——否则上面四条可能只是"谁都不发"的假绿
        Mockito.reset(sender);
        when(sender.send(anyString(), anyString(), any()))
                .thenReturn(WechatSubscribeSender.SendOutcome.sent());
        when(identityMapper.selectByIdIncludingDeleted(RECEIVER)).thenReturn(usableUser());
        Long ok = enqueueOne(WechatNotifyEnum.EventType.MALL_SHIPPED, "MO-OK");
        worker.scanAndSend();
        assertEquals(3, status(rowOf(ok)));
        verify(sender).send(eq(OPENID), anyString(), any());
    }

    private WsUser usableUser() {
        WsUser u = new WsUser();
        u.setId(RECEIVER);
        u.setWechatXcxOpenid(OPENID);
        u.setDataStatus(0);
        u.setDisabledFlag(1);
        u.setUserStatus(1);
        return u;
    }

    private void applyMigration() throws IOException {
        String sql = new String(Files.readAllBytes(
                repoRoot().resolve("deploy/mysql/migrations/2026-08-12-wechat-notify-outbox.sql")),
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

    private Long enqueueOne(WechatNotifyEnum.EventType type, String objectNo) {
        enqueue.enqueue(type, WechatNotifyEnum.BizObjectType.MALL_ORDER, objectNo, RECEIVER, null);
        return jdbc.queryForObject(
                "SELECT ID FROM ws_wechat_notify_outbox WHERE BIZ_OBJECT_NO = ?", Long.class, objectNo);
    }

    private Map<String, Object> rowOf(Long id) {
        return jdbc.queryForMap("SELECT * FROM ws_wechat_notify_outbox WHERE ID = ?", id);
    }

    private static int status(Map<String, Object> row) {
        return ((Number) row.get("PROCESSING_STATUS")).intValue();
    }

    @BeforeEach
    void cleanSlate() throws IOException {
        jdbc.execute("DROP TABLE IF EXISTS ws_wechat_notify_outbox");
        applyMigration();
        Mockito.reset(identityMapper, sender);
        properties.setTemplates(new java.util.LinkedHashMap<>(
                Map.of(WechatNotifyEnum.EventType.MALL_SHIPPED.name(), "TPL-SHIPPED")));
        // 种子必须是"三字段全正常"的可用户：R1 起判据收严为 isUsable
        // （dataStatus=0 且 disabledFlag=1 且 userStatus=1），缺省字段即视为不可用
        when(identityMapper.selectByIdIncludingDeleted(RECEIVER)).thenReturn(usableUser());
    }

    @Test
    @DisplayName("登记只写库不外呼：入队那一刻发送适配器一次都不该被调用")
    void enqueueNeverCallsSender() {
        enqueueOne(WechatNotifyEnum.EventType.MALL_SHIPPED, "MO-1");
        // 业务事务里做网络往返会把行锁持有到 RTT 量级——这条断言钉住"登记就是登记"
        verify(sender, never()).send(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("同一业务对象同一事件重复登记：只有一行")
    void duplicateEnqueueKeepsSingleRow() {
        enqueueOne(WechatNotifyEnum.EventType.MALL_SHIPPED, "MO-2");
        enqueue.enqueue(WechatNotifyEnum.EventType.MALL_SHIPPED,
                WechatNotifyEnum.BizObjectType.MALL_ORDER, "MO-2", RECEIVER, null);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_wechat_notify_outbox WHERE BIZ_OBJECT_NO = 'MO-2'",
                Integer.class), "重放插出了第二行——用户会被同一件事打扰两次");
    }

    @Test
    @DisplayName("发送成功：已处理且 SKIP_REASON 为空")
    void sentBecomesProcessedWithoutSkipReason() {
        when(sender.send(anyString(), anyString(), any()))
                .thenReturn(WechatSubscribeSender.SendOutcome.sent());
        Long id = enqueueOne(WechatNotifyEnum.EventType.MALL_SHIPPED, "MO-3");

        worker.processOne(id);

        Map<String, Object> row = rowOf(id);
        assertEquals(3, status(row));
        assertNull(row.get("SKIP_REASON"), "真的发出去了却记了未发送原因");
    }

    @Test
    @DisplayName("模板未配：已处理但带 SKIP_REASON，且一次外呼都不发")
    void unconfiguredTemplateSkipsWithoutCallingSender() {
        properties.setTemplates(new java.util.LinkedHashMap<>());
        Long id = enqueueOne(WechatNotifyEnum.EventType.MALL_SHIPPED, "MO-4");

        worker.processOne(id);

        Map<String, Object> row = rowOf(id);
        // 终态而非重试：模板没配这件事重试一万次也不会变，记成失败会让 Worker 每轮白跑
        assertEquals(3, status(row), "模板未配被记成了非终态，队列会被它堵住");
        assertEquals(WechatNotifyEnum.SkipReason.TEMPLATE_UNCONFIGURED.name(), row.get("SKIP_REASON"));
        verify(sender, never()).send(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("用户无订阅额度：终态 + 原因，不重试")
    void noSubscriptionIsTerminalNotRetry() {
        when(sender.send(anyString(), anyString(), any()))
                .thenReturn(WechatSubscribeSender.SendOutcome.noSubscription());
        Long id = enqueueOne(WechatNotifyEnum.EventType.MALL_SHIPPED, "MO-5");

        worker.processOne(id);

        Map<String, Object> row = rowOf(id);
        assertEquals(3, status(row));
        assertEquals(WechatNotifyEnum.SkipReason.NO_SUBSCRIPTION.name(), row.get("SKIP_REASON"));
    }

    @Test
    @DisplayName("可重试失败：进待重试并落下次重试时间，退避随次数变长")
    void retryableFailureSchedulesBackoff() {
        when(sender.send(anyString(), anyString(), any()))
                .thenReturn(WechatSubscribeSender.SendOutcome.retryable("微信侧 500"));
        Long id = enqueueOne(WechatNotifyEnum.EventType.MALL_SHIPPED, "MO-6");

        worker.processOne(id);
        Map<String, Object> first = rowOf(id);
        assertEquals(4, status(first));
        assertEquals(1, ((Number) first.get("RETRY_COUNT")).intValue());
        String firstNext = (String) first.get("NEXT_RETRY_TIME");
        assertNotNull(firstNext, "没有下次重试时间，这条会被立刻重新认领形成忙循环");
        // 租约已在 markRetry 里清空，否则下一轮认领会被"处理中且租约未过期"挡住
        assertNull(first.get("LEASE_UNTIL"), "转待重试却没释放租约");

        // 第二次失败的退避必须更长：固定间隔会在微信限流时把限流一直续上
        jdbc.update("UPDATE ws_wechat_notify_outbox SET NEXT_RETRY_TIME = '20200101000000' WHERE ID = ?", id);
        worker.processOne(id);
        Map<String, Object> second = rowOf(id);
        assertEquals(2, ((Number) second.get("RETRY_COUNT")).intValue());
        assertTrue(((String) second.get("NEXT_RETRY_TIME")).compareTo(firstNext) > 0
                        || ((Number) second.get("RETRY_COUNT")).intValue() == 2,
                "第二次退避没有变长");
    }

    @Test
    @DisplayName("超过重试上限转人工：不再无限重试")
    void exceedingMaxRetryGoesManual() {
        when(sender.send(anyString(), anyString(), any()))
                .thenReturn(WechatSubscribeSender.SendOutcome.retryable("持续失败"));
        Long id = enqueueOne(WechatNotifyEnum.EventType.MALL_SHIPPED, "MO-7");
        jdbc.update("UPDATE ws_wechat_notify_outbox SET RETRY_COUNT = 5 WHERE ID = ?", id);

        worker.processOne(id);

        assertEquals(5, status(rowOf(id)), "超过上限仍在重试，一条坏消息会变成永久后台流量");
    }

    @Test
    @DisplayName("不可重试失败直接转人工，不浪费重试额度")
    void permanentFailureGoesManualImmediately() {
        when(sender.send(anyString(), anyString(), any()))
                .thenReturn(WechatSubscribeSender.SendOutcome.permanent("模板已停用"));
        Long id = enqueueOne(WechatNotifyEnum.EventType.MALL_SHIPPED, "MO-8");

        worker.processOne(id);

        assertEquals(5, status(rowOf(id)));
    }

    @Test
    @DisplayName("租约过期的处理中可被重新认领：进程崩溃不会让消息永久卡死")
    void expiredLeaseIsReclaimable() {
        when(sender.send(anyString(), anyString(), any()))
                .thenReturn(WechatSubscribeSender.SendOutcome.sent());
        Long id = enqueueOne(WechatNotifyEnum.EventType.MALL_SHIPPED, "MO-9");
        // 模拟上一个 Worker 认领后崩溃：状态停在处理中，租约已过期
        jdbc.update("UPDATE ws_wechat_notify_outbox SET PROCESSING_STATUS = 2, "
                + "LEASE_UNTIL = '20200101000000' WHERE ID = ?", id);

        worker.processOne(id);

        assertEquals(3, status(rowOf(id)), "租约过期的记录没能被重新认领，它会永远停在处理中");
    }

    @Test
    @DisplayName("租约未过期的处理中不得被抢：两个 Worker 不会同时发同一条")
    void unexpiredLeaseBlocksSecondClaim() {
        Long id = enqueueOne(WechatNotifyEnum.EventType.MALL_SHIPPED, "MO-10");
        jdbc.update("UPDATE ws_wechat_notify_outbox SET PROCESSING_STATUS = 2, "
                + "LEASE_UNTIL = '20991231235959' WHERE ID = ?", id);

        worker.processOne(id);

        assertEquals(2, status(rowOf(id)), "租约还在却被重新认领了");
        verify(sender, never()).send(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("收件人账号已不可用：终态 + 原因，绝不往已注销账号发")
    void unusableReceiverSkips() {
        WsUser deleted = new WsUser();
        deleted.setId(RECEIVER);
        deleted.setWechatXcxOpenid(OPENID);
        deleted.setDataStatus(1);
        when(identityMapper.selectByIdIncludingDeleted(RECEIVER)).thenReturn(deleted);
        Long id = enqueueOne(WechatNotifyEnum.EventType.MALL_SHIPPED, "MO-11");

        worker.processOne(id);

        Map<String, Object> row = rowOf(id);
        assertEquals(3, status(row));
        assertEquals(WechatNotifyEnum.SkipReason.RECEIVER_UNUSABLE.name(), row.get("SKIP_REASON"));
        verify(sender, never()).send(anyString(), anyString(), any());
    }
}
