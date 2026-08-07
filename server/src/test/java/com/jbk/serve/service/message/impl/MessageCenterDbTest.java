package com.jbk.serve.service.message.impl;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.message.WsMessageMapper;
import com.jbk.serve.service.delivery.IDeliveryMediaService;
import com.jbk.serve.service.delivery.impl.DeliveryDbSchema;
import com.jbk.serve.service.message.IWsMessageService;
import com.jbk.serve.service.ops.IWorkOrderService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.serve.service.ops.impl.WorkOrderServiceImpl;
import com.jbk.serve.service.ops.impl.WsDomainEventServiceImpl;
import com.jbk.tool.consts.message.MessageEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.mini.bo.MiniMessageBo;
import com.jbk.tool.data.mini.vo.MiniMessageVo;
import com.jbk.tool.data.ops.bo.WorkOrderApplyBo;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E-07 消息中心的<b>真实 MySQL</b> 集成测试（任务书包E）。
 *
 * <p>钉住的口径全部来自任务书第二节冻结项：</p>
 * <ol>
 *   <li><b>铁律6</b>：page/detail/read 一律按会话 userId 过滤；越权与不存在同文案。</li>
 *   <li><b>排序</b>：SEND_TIME 倒序、无发送时间排尾、同秒按 ID 倒序稳定。</li>
 *   <li><b>已读</b>：0→1 条件更新幂等，重复标记零变化仍成功。</li>
 *   <li><b>工单钩子</b>：受理/驳回/复核关闭三节点触达申报人；告警转入（无申报人）零消息；
 *       迁移失败消息同灭（同事务）。</li>
 *   <li><b>骨架</b>：重试 3→2→4/3、降级 3→站内已送达，全部条件更新幂等。</li>
 *   <li><b>白名单</b>：可订阅类型落库即置位；非白名单类型筛选拒绝（存在性不泄露）。</li>
 *   <li><b>模板</b>：文案锁定、长度收口、不含手机号。</li>
 * </ol>
 *
 * <p>无 Docker 环境自动跳过。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = MessageCenterDbTest.Ctx.class)
class MessageCenterDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_message_it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static final long OWNER = 9L;
    private static final long STRANGER = 8L;
    private static final long OPERATOR = 100L;
    private static final long DEVICE = 61L;
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
        MapperFactoryBean<WsMessageMapper> wsMessageMapper(SqlSessionTemplate t) {
            return mapper(WsMessageMapper.class, t);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.device.WsDeviceMapper> wsDeviceMapper(SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.device.WsDeviceMapper.class, t);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.station.WsStationMapper> wsStationMapper(SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.station.WsStationMapper.class, t);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.ops.WsWorkOrderMapper> wsWorkOrderMapper(SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.ops.WsWorkOrderMapper.class, t);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.ops.WsAlarmMapper> wsAlarmMapper(SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.ops.WsAlarmMapper.class, t);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.ops.WsDomainEventMapper> wsDomainEventMapper(SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.ops.WsDomainEventMapper.class, t);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.api.ApiEmployeeMapper> apiEmployeeMapper(SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.api.ApiEmployeeMapper.class, t);
        }

        @Bean
        MapperFactoryBean<com.jbk.serve.mapper.delivery.WsDeliveryMediaMapper> wsDeliveryMediaMapper(SqlSessionTemplate t) {
            return mapper(com.jbk.serve.mapper.delivery.WsDeliveryMediaMapper.class, t);
        }

        @Bean
        IWsMessageService messageService() {
            return new WsMessageServiceImpl();
        }

        @Bean
        IWsDomainEventService domainEventService() {
            return new WsDomainEventServiceImpl();
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
        IDeliveryMediaService mediaService() {
            return new com.jbk.serve.service.delivery.impl.DeliveryMediaServiceImpl();
        }

        @Bean
        com.jbk.serve.service.delivery.DeliveryMediaStore mediaStore() {
            return (mediaKey, content) -> { };
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    @Autowired
    private IWsMessageService messageService;
    @Autowired
    private IWorkOrderService workOrderService;
    @Autowired
    private IWsDomainEventService domainEventService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        DeliveryDbSchema.createAll(jdbc);
        DeliveryDbSchema.truncateAll(jdbc);
        jdbc.update("INSERT INTO ws_device (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, DEVICE_NO, STATION_ID, OWNER_USER_ID, ONLINE_STATUS, RUN_STATUS) VALUES (?,0,1,?,1,?,?,NULL,?,1,1)",
                DEVICE, NOW, NOW, "IT-DEV-0061", OWNER);
    }

    private void seedMessage(long userId, MessageEnum.MsgDomain domain, String title, String sendTime) {
        messageService.sendInApp(userId, domain, title, title + "正文", "order", "ORD-" + title, sendTime);
    }

    private Long ownerOrderId() {
        WorkOrderApplyBo bo = new WorkOrderApplyBo();
        bo.setRequestId("REQ-IT-" + System.nanoTime());
        bo.setDeviceId(DEVICE);
        bo.setWorkType(OpsEnum.WorkOrderType.REPAIR.getValue());
        bo.setOrderContent("出水口漏水");
        return workOrderService.ownerApply(bo, OWNER);
    }

    // ------------------------------------------------------------------
    // 读端：铁律6 / 排序 / 分页防护
    // ------------------------------------------------------------------

    @Test
    void pageIsScopedByUserAndSortedBySendTimeDescNullLast() {
        seedMessage(OWNER, MessageEnum.MsgDomain.WATER, "早", "20260101090000");
        seedMessage(OWNER, MessageEnum.MsgDomain.SYSTEM, "晚", "20260601090000");
        seedMessage(STRANGER, MessageEnum.MsgDomain.SYSTEM, "别人的", "20260701090000");
        // 无发送时间行（骨架态样本，直接落库构造）
        jdbc.update("INSERT INTO ws_message (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, USER_ID, MSG_DOMAIN, MSG_TITLE, MSG_CONTENT, MSG_CHANNEL, SEND_STATUS, SEND_TIME, READ_FLAG) VALUES (0,1,?,1,?,?,5,'无时','',2,1,NULL,0)",
                NOW, NOW, OWNER);

        PageDataVo<MiniMessageVo> page = messageService.pageForUser(new MiniMessageBo(), OWNER);
        assertEquals(3, page.getTotal(), "只见本人消息");
        List<String> titles = page.getList().stream().map(MiniMessageVo::getMsgTitle).toList();
        assertEquals(List.of("晚", "早", "无时"), titles, "倒序且无发送时间排尾");
        assertTrue(page.getList().stream().noneMatch(v -> "别人的".equals(v.getMsgTitle())));
    }

    @Test
    void domainFilterAndPagingGuards() {
        seedMessage(OWNER, MessageEnum.MsgDomain.WATER, "取水消息", NOW);
        seedMessage(OWNER, MessageEnum.MsgDomain.SYSTEM, "系统消息", NOW);
        MiniMessageBo domainBo = new MiniMessageBo().setMsgDomain(MessageEnum.MsgDomain.WATER.getValue());
        assertEquals(1, messageService.pageForUser(domainBo, OWNER).getTotal());

        assertThrows(JbkException.class, () -> messageService.pageForUser(new MiniMessageBo().setMsgDomain(9), OWNER),
                "未知领域拒绝");
        assertThrows(JbkException.class, () -> messageService.pageForUser(new MiniMessageBo().setCurrent(0L), OWNER));
        assertThrows(JbkException.class, () -> messageService.pageForUser(new MiniMessageBo().setSize(101L), OWNER));
        assertThrows(JbkException.class,
                () -> messageService.pageForUser(new MiniMessageBo().setCurrent(Long.MAX_VALUE), OWNER),
                "页码溢出拒绝");
    }

    @Test
    void detailAndReadDenyForeignWithSameMessage() {
        seedMessage(OWNER, MessageEnum.MsgDomain.WATER, "本人消息", NOW);
        long id = jdbc.queryForObject("SELECT ID FROM ws_message WHERE MSG_TITLE='本人消息'", Long.class);

        JbkException foreignDetail = assertThrows(JbkException.class,
                () -> messageService.detailForUser(id, STRANGER));
        JbkException missingDetail = assertThrows(JbkException.class,
                () -> messageService.detailForUser(999999L, STRANGER));
        assertEquals(foreignDetail.getMessage(), missingDetail.getMessage(), "越权与不存在必须同文案");

        JbkException foreignRead = assertThrows(JbkException.class, () -> messageService.markRead(id, STRANGER));
        assertEquals(foreignDetail.getMessage(), foreignRead.getMessage(), "已读越权同文案");
        assertEquals(0, (int) jdbc.queryForObject("SELECT READ_FLAG FROM ws_message WHERE ID=?", Integer.class, id),
                "越权标记不得生效");
    }

    @Test
    void markReadIsIdempotent() {
        seedMessage(OWNER, MessageEnum.MsgDomain.WATER, "待读", NOW);
        long id = jdbc.queryForObject("SELECT ID FROM ws_message WHERE MSG_TITLE='待读'", Long.class);
        messageService.markRead(id, OWNER);
        assertEquals(1, (int) jdbc.queryForObject("SELECT READ_FLAG FROM ws_message WHERE ID=?", Integer.class, id));
        String updateTime = jdbc.queryForObject("SELECT UPDATE_TIME FROM ws_message WHERE ID=?", String.class, id);
        // 幂等：重复标记零变化仍成功（不抛错、不改行）
        messageService.markRead(id, OWNER);
        assertEquals(updateTime, jdbc.queryForObject("SELECT UPDATE_TIME FROM ws_message WHERE ID=?", String.class, id));
    }

    // ------------------------------------------------------------------
    // 工单钩子：三节点触达申报人 / 无申报人零消息 / 同事务
    // ------------------------------------------------------------------

    @Test
    void workOrderConfirmRejectCloseNotifyApplicant() {
        Long confirmed = ownerOrderId();
        workOrderService.confirm(confirmed, OPERATOR);
        Long rejected = ownerOrderId();
        workOrderService.reject(rejected, "非质保范围", OPERATOR);

        // 走完整生命周期到复核关闭
        jdbc.update("INSERT INTO api_employee (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, LOGIN_NAME, LOGIN_PWD, EMPLOYEE_NAME, DISABLED_FLAG) VALUES (?,0,1,?,1,?,?,'x','运维甲',1)",
                OPERATOR, NOW, NOW, "it-ops");
        workOrderService.assign(confirmed, OPERATOR, OPERATOR);
        workOrderService.submitResult(confirmed, "已更换密封圈", null, OPERATOR);
        workOrderService.reviewPass(confirmed, "验收通过", OPERATOR);

        List<String> titles = jdbc.queryForList(
                "SELECT MSG_TITLE FROM ws_message WHERE USER_ID=? ORDER BY ID", String.class, OWNER);
        assertEquals(List.of("报修申请已受理", "报修申请未通过", "报修处理完成"), titles,
                "受理/驳回/关闭三节点各一条，分配与提交结果不打扰申报人");
        // 域=机主，OBJECT_TYPE=service，OBJECT_ID=申报凭据（C03 按它回跳 O05）
        assertEquals(MessageEnum.MsgDomain.OWNER.getValue(), (int) jdbc.queryForObject(
                "SELECT MSG_DOMAIN FROM ws_message WHERE MSG_TITLE='报修申请已受理'", Integer.class));
        String objectId = jdbc.queryForObject(
                "SELECT OBJECT_ID FROM ws_message WHERE MSG_TITLE='报修申请已受理'", String.class);
        assertTrue(objectId.startsWith("REQ-IT-"), "OBJECT_ID 用申报 requestId");
        // 驳回原因如实带给申报人
        String rejectContent = jdbc.queryForObject(
                "SELECT MSG_CONTENT FROM ws_message WHERE MSG_TITLE='报修申请未通过'", String.class);
        assertTrue(rejectContent.contains("非质保范围"));
    }

    @Test
    void alarmSourcedOrderProducesNoMessageAndNoNpe() {
        jdbc.update("INSERT INTO ws_alarm (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, DEVICE_ID, ALARM_TYPE, ALARM_LEVEL, ALARM_CONTENT, ALARM_STATUS, ACTIVE_DEDUPE_KEY) VALUES (1,0,1,?,1,?,?,1,2,'设备离线',1,'1:1')",
                NOW, NOW, DEVICE);
        Long orderId = workOrderService.createFromAlarm(1L, OPERATOR);
        // 告警转入 → 待分配 → 只能走 assign 起步；确认动作不适用，直接断言无消息产生
        assertEquals(0, (int) jdbc.queryForObject("SELECT COUNT(*) FROM ws_message", Integer.class),
                "无申报人来源全程零消息");
        assertNull(jdbc.queryForObject("SELECT APPLICANT_USER_ID FROM ws_work_order WHERE ID=?",
                Long.class, orderId));
    }

    @Test
    void failedTransitionLeavesNoMessage() {
        Long orderId = ownerOrderId();
        workOrderService.confirm(orderId, OPERATOR);
        // 已是待分配，重复确认非法：迁移拒绝，本次动作不得追加消息（同事务同灭）
        assertThrows(JbkException.class, () -> workOrderService.confirm(orderId, OPERATOR));
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_message WHERE USER_ID=?", Integer.class, OWNER),
                "只有首次受理那一条");
    }

    // ------------------------------------------------------------------
    // 发送状态机骨架：重试 / 降级
    // ------------------------------------------------------------------

    @Test
    void retryAndDegradeSkeletonWalksAllStates() {
        jdbc.update("INSERT INTO ws_message (ID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, USER_ID, MSG_DOMAIN, MSG_TITLE, MSG_CONTENT, MSG_CHANNEL, SEND_STATUS, SEND_TIME, READ_FLAG) VALUES (701,0,1,?,1,?,?,2,'失败样本','',2,3,?,0)",
                NOW, NOW, OWNER, NOW);

        assertTrue(messageService.markRetrySending(701L), "3→2");
        assertFalse(messageService.markRetrySending(701L), "非失败态不可再进重试（幂等拒绝）");
        assertTrue(messageService.completeRetry(701L, false), "2→3 重试失败回落");
        assertTrue(messageService.markRetrySending(701L));
        assertTrue(messageService.completeRetry(701L, true), "2→4 送达");
        assertFalse(messageService.completeRetry(701L, true), "已送达不可再收尾");

        jdbc.update("UPDATE ws_message SET SEND_STATUS=3, MSG_CHANNEL=2 WHERE ID=701");
        assertTrue(messageService.degradeToInApp(701L), "失败态降级为站内");
        assertEquals(MessageEnum.MsgChannel.IN_APP.getValue(), (int) jdbc.queryForObject(
                "SELECT MSG_CHANNEL FROM ws_message WHERE ID=701", Integer.class));
        assertEquals(MessageEnum.SendStatus.DELIVERED.getValue(), (int) jdbc.queryForObject(
                "SELECT SEND_STATUS FROM ws_message WHERE ID=701", Integer.class));
        assertFalse(messageService.degradeToInApp(701L), "重复降级零变化");
    }

    // ------------------------------------------------------------------
    // 事件白名单
    // ------------------------------------------------------------------

    @Test
    void whitelistFlagIsSetAtRecordTimeAndQueryIsScoped() {
        domainEventService.record(OpsEnum.EventType.WORK_ORDER_STATUS, "WO-IT-1", "a", "b");
        domainEventService.record(OpsEnum.EventType.PAYMENT_RESULT, "PAY-IT-1", "a", "b");

        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_domain_event WHERE EVENT_KEY='WO-IT-1' AND WHITELIST_FLAG=2", Integer.class),
                "可订阅类型落库即置位（2=是）");
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_domain_event WHERE EVENT_KEY='PAY-IT-1' AND WHITELIST_FLAG=1", Integer.class),
                "资金类不置位（1=否）");

        PageDataVo<com.jbk.tool.data.ops.po.WsDomainEvent> page =
                domainEventService.pageWhitelist(1L, 20L, null);
        assertTrue(page.getList().stream().allMatch(e -> e.getWhitelistFlag() == 2), "只吐白名单事件");
        assertTrue(page.getList().stream().noneMatch(e -> "PAY-IT-1".equals(e.getEventKey())));
        assertThrows(JbkException.class, () -> domainEventService.pageWhitelist(1L, 20L,
                OpsEnum.EventType.PAYMENT_RESULT.getValue()), "非白名单类型筛选直接拒绝");
    }

    // 模板纯函数锁定在 MessageTemplatesTest（无 Docker 门控，避免无 Docker 环境静默跳过）
}
