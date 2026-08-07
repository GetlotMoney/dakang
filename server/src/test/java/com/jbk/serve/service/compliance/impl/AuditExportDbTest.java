package com.jbk.serve.service.compliance.impl;

import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.compliance.WsAuditExportTaskMapper;
import com.jbk.tool.data.compliance.po.WsAuditExportTask;
import com.jbk.tool.config.system.mybatis.MpMetaObjectHandler;
import com.jbk.tool.utils.DateUtils;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计导出的<b>真实 MySQL</b> 回归（B23 验收 P2-1 / P2-2）：选号口径与 ORM 写入边界。
 *
 * <p>这两件事都只有真库能证伪。{@code AuditExportContractTest} 做的是源级断言，
 * 拦得住"有人写出某种文本形式的代码"，拦不住"换个写法绕过去"；能力边界最终
 * 由数据库里那两列是不是 NULL 说了算，见 {@link #ormCannotWriteFileDigestOrExpireTime}。</p>
 *
 * <h2>选号</h2>
 *
 * <p>钉住两个失效模式，两者都会造成<b>确定性</b>故障——
 * 撞键后重试三次算出的是同一个号，当天此后每一次申请都失败：</p>
 * <ol>
 *   <li><b>逻辑删除行占号</b>：唯一键 {@code uk_audit_export_task_no} 不区分逻辑删除，
 *       选号也必须不区分。旧实现用 Wrapper 查询，被 {@code @TableLogic} 注入的
 *       {@code AND DATA_STATUS = 0} 过滤掉已删行——注入条件与调用方条件是 AND 关系，
 *       写 {@code .and(w -> eq(0).or().eq(1))} 也绕不开，看着像绕开了其实没有。</li>
 *   <li><b>序号越过 999</b>：旧实现按 {@code ORDER BY TASK_NO DESC} 取最大。
 *       定宽 3 位时字典序等于数值序，一旦出现 4 位号就断裂——
 *       {@code '...-999' > '...-1000'}，于是永远算出 1000。</li>
 * </ol>
 *
 * <p>建表 DDL 直接读迁移文件，不在测试里另抄一份：抄一份就会漂移，
 * 而漂移后的测试仍然全绿。</p>
 *
 * <p>无 Docker 环境自动跳过。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = AuditExportDbTest.Ctx.class)
class AuditExportDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_audit_export_it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    /**
     * 刻意<b>不开</b> {@code @EnableTransactionManagement}：开了以后 Spring 会为实现了
     * {@code IWsAuditExportTaskService} 的服务生成 JDK 接口代理，测试就拿不到具体类，
     * 也就调不到包级的 {@code nextTaskNo()}。本测试只做选号查询，不需要事务语义。
     */
    @Configuration
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
            // 必须用生产同一个填充器：DATA_STATUS 由它填，逻辑删除语义才与线上一致
            GlobalConfig globalConfig = new GlobalConfig();
            globalConfig.setMetaObjectHandler(new MpMetaObjectHandler());
            factory.setGlobalConfig(globalConfig);
            return new SqlSessionTemplate(factory.getObject());
        }

        @Bean
        MapperFactoryBean<WsAuditExportTaskMapper> auditExportMapper(SqlSessionTemplate t) {
            MapperFactoryBean<WsAuditExportTaskMapper> bean =
                    new MapperFactoryBean<>(WsAuditExportTaskMapper.class);
            bean.setSqlSessionTemplate(t);
            return bean;
        }

        @Bean
        WsAuditExportTaskServiceImpl auditExportTaskService() {
            return new WsAuditExportTaskServiceImpl();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private WsAuditExportTaskServiceImpl service;

    /** 当天前缀，与被测实现取自同一个时间源，跨零点也不会错位到昨天。 */
    private String prefix() {
        return "AUD-EXP-" + DateUtils.time().substring(0, 8) + "-";
    }

    @BeforeEach
    void setUp() throws IOException {
        jdbc.execute(createTableDdlFromMigration());
        jdbc.update("DELETE FROM ws_audit_export_task");
    }

    @Test
    void logicallyDeletedRowStillOccupiesItsNumber() {
        String p = prefix();
        insertTask(p + "001", 0);
        // 001 未删、002 已逻辑删除：唯一键仍占着 002，选号不得回到 002
        insertTask(p + "002", 1);

        assertEquals(p + "003", service.nextTaskNo(),
                "逻辑删除行仍占用唯一键。若选号跳过它就会算出 002，"
                        + "而 002 物理上还在——当天此后每次申请都撞键，重试三次也是同一个号");
    }

    @Test
    void sequenceKeepsIncrementingPastNineNinetyNine() {
        String p = prefix();
        insertTask(p + "999", 0);
        assertEquals(p + "1000", service.nextTaskNo(), "999 之后应为 1000");

        insertTask(p + "1000", 0);
        assertEquals(p + "1001", service.nextTaskNo(),
                "按字符串取最大值时 '...-999' > '...-1000'，会永远算出 1000 并永久撞键；"
                        + "必须按数值比较");
    }

    @Test
    void firstTaskOfDayStartsAtOne() {
        assertEquals(prefix() + "001", service.nextTaskNo(), "当天无记录时从 001 起");
    }

    @Test
    void otherDaysDoNotLeakIntoTodaySequence() {
        insertTask("AUD-EXP-19990101-777", 0);
        assertEquals(prefix() + "001", service.nextTaskNo(), "选号必须按天隔离");
    }

    @Test
    void malformedTaskNoDoesNotBreakSelection() {
        String p = prefix();
        insertTask(p + "005", 0);
        // 非数字后缀（历史脏数据或人工补录）CAST 后为 0，不得吞掉正常最大值
        insertTask(p + "ABC", 0);
        assertEquals(p + "006", service.nextTaskNo(), "畸形任务号不得干扰取号");
    }

    /**
     * 能力边界的<b>行为</b>证明：ORM 层真的写不出 FILE_DIGEST / EXPIRE_TIME。
     *
     * <p>{@code AuditExportContractTest} 只做源级断言——它拦得住"有人写出
     * {@code setFileDigest(...)}"，拦不住换个写法绕过去（Mapper 注解 SQL、XML、
     * 通用更新器）。这条测试直接把两个字段塞进 PO 再走真实 {@code save}/{@code updateById}，
     * 由数据库回答它们有没有落盘。落盘了就意味着合规页可以展示一份并不存在的导出结果，
     * 比没有导出功能更糟。</p>
     */
    @Test
    void ormCannotWriteFileDigestOrExpireTime() {
        WsAuditExportTask task = new WsAuditExportTask()
                .setTaskNo(prefix() + "900")
                .setExportScope("操作日志")
                .setFilterSummary("全量")
                .setApplyReason("能力边界回归")
                .setMaskingRule("默认脱敏")
                .setTaskStatus(1)
                .setApplyByName("测试员")
                .setFileDigest("sha256:deadbeef")
                .setExpireTime("20991231235959");
        task.setCreateBy(1L);
        task.setUpdateBy(1L);
        service.save(task);

        assertNoFileFields("insert 阶段");

        // 再走一次更新路径：insertStrategy 与 updateStrategy 是两个开关，必须分别证明
        service.updateById(new WsAuditExportTask()
                .setId(task.getId())
                .setFileDigest("sha256:cafebabe")
                .setExpireTime("20991231235959")
                .setTaskStatus(2));
        assertNoFileFields("update 阶段");
    }

    private void assertNoFileFields(String stage) {
        var row = jdbc.queryForMap(
                "SELECT FILE_DIGEST, EXPIRE_TIME, TASK_STATUS FROM ws_audit_export_task WHERE TASK_NO = ?",
                prefix() + "900");
        assertNull(row.get("FILE_DIGEST"),
                stage + "：FILE_DIGEST 落盘了。对象存储未接入时它必须恒为 NULL，"
                        + "否则平台就在伪造一份不存在的导出文件");
        assertNull(row.get("EXPIRE_TIME"), stage + "：EXPIRE_TIME 落盘了，同上");
        // 反证本测试有效：同一次写入里的普通字段确实进了库，说明写操作本身生效了，
        // 不是因为整条语句没执行才看到 NULL
        assertNotNull(row.get("TASK_STATUS"), stage + "：连普通字段都没写进去，本测试的结论不成立");
    }

    private void insertTask(String taskNo, int dataStatus) {
        jdbc.update("INSERT INTO ws_audit_export_task"
                        + "(DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME,"
                        + " TASK_NO, EXPORT_SCOPE, FILTER_SUMMARY, APPLY_REASON, MASKING_RULE,"
                        + " TASK_STATUS, APPLY_BY_NAME)"
                        + " VALUES(?, 1, ?, 1, ?, ?, '操作日志', '全量', '回归测试', '默认脱敏', 1, '测试员')",
                dataStatus, DateUtils.time(), DateUtils.time(), taskNo);
    }

    /**
     * 从迁移文件里抠出建表语句——测试库与既有库同一份 DDL，杜绝抄写漂移。
     */
    private String createTableDdlFromMigration() throws IOException {
        Path repo = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && repo != null
                && !Files.isDirectory(repo.resolve("deploy/mysql/migrations")); i++) {
            repo = repo.getParent();
        }
        assertTrue(repo != null, "无法定位仓库根");
        Path mig = repo.resolve("deploy/mysql/migrations/2026-08-06-audit-export-b23.sql");
        assertTrue(Files.exists(mig), "找不到迁移文件 " + mig);
        String sql = new String(Files.readAllBytes(mig), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile(
                "CREATE TABLE IF NOT EXISTS `ws_audit_export_task`.*?;",
                Pattern.DOTALL).matcher(sql);
        assertTrue(m.find(), "迁移文件中找不到 ws_audit_export_task 建表语句");
        // 去掉 -- 行注释：JDBC 单语句执行不接受夹在列定义之间的行注释
        return m.group().replaceAll("(?m)^\\s*--.*$", "");
    }
}
