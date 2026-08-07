package com.jbk.serve.service.aftersale;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>同一张表的多份 DDL 必须一致</b>——常态守卫（E2E-04）。
 *
 * <h3>这条测试是为一个已经发生过的 P0 写的</h3>
 * <p>包A 把 {@code ws_after_sale_action} 只建在 {@code deploy/mysql/migrations/} 里，
 * 忘了同步 {@code deploy/mysql/init/}。而主环境<b>从不执行 migrations</b>
 * （compose 只挂 init 到 docker-entrypoint-initdb.d，build-all.sh 里没有任何迁移步骤），
 * 与此同时 {@code WsOrderMapper.xml} 的订单中心读模型里新增了一条无条件的
 * {@code EXISTS(... ws_after_sale_action ...)}。两件事一叠加，
 * 结果是<b>已验收的订单中心整体 1146 Table doesn't exist</b>，
 * 而 800 多个单测与隔离环境冒烟全绿——因为单测自建表、验收环境跑 migrations，两侧都盖不到。</p>
 *
 * <p>那次是靠人工比对发现的。人工比对不可复制，故把它固化成测试：
 * 任何一份 DDL 单独改动而另一份没跟上，这里立刻红。</p>
 *
 * <h3>比对口径：列名集合</h3>
 * <p>只比列名而不比类型与索引，是刻意的取舍。三份 DDL 的写法本就不同
 * （测试 schema 为了跑得快放宽了 NOT NULL、用 VARCHAR(20) 装 14 位时间），
 * 强行比类型会产出大量必须逐条豁免的噪音，最终没人维护。
 * 而<b>列缺失</b>恰是那次 P0 的形状，也是最容易发生、后果最直接的一种漂移。</p>
 */
class SchemaParityTest {

    private static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    private static final Path INIT_SQL = REPO.resolve("deploy/mysql/init/02-ws-business.sql");
    private static final Path INIT_BASE_SQL = REPO.resolve("deploy/mysql/init/01-base.sql");
    private static final Path MIG_A = REPO.resolve("deploy/mysql/migrations/2026-07-29-aftersale-e2e04-a.sql");
    private static final Path MIG_B = REPO.resolve("deploy/mysql/migrations/2026-07-29-aftersale-e2e04-b.sql");
    private static final Path MIG_D = REPO.resolve("deploy/mysql/migrations/2026-07-29-aftersale-e2e04-d.sql");
    private static final Path MIG_D2 = REPO.resolve(
            "deploy/mysql/migrations/2026-07-29-aftersale-e2e04-d2-legacy-backfill.sql");
    private static final Path MIG_D3 = REPO.resolve(
            "deploy/mysql/migrations/2026-07-29-aftersale-e2e04-d3-recharge-refund.sql");
    private static final Path MIG_E5A = REPO.resolve("deploy/mysql/migrations/2026-07-30-deviceops-e2e05-a.sql");
    private static final Path MIG_PARAM = REPO.resolve(
            "deploy/mysql/migrations/2026-08-04-device-param-req213.sql");
    private static final Path MIG_AUDIT_EXPORT = REPO.resolve(
            "deploy/mysql/migrations/2026-08-06-audit-export-b23.sql");
    private static final Path AFTER_SALE_RUNNER = REPO.resolve("miniapp/e2e/run-after-sale.js");
    private static final Path AFTER_SALE_MAPPER = Path.of(
            "src/main/resources/mapper/aftersale/WsAfterSaleActionMapper.xml");
    private static final Path TEST_SCHEMA = Path.of(
            "src/test/java/com/jbk/serve/service/delivery/impl/DeliveryDbSchema.java");

    /** 包A 建的表：迁移与 init 必须逐列一致。 */
    @Test
    void afterSaleActionColumnsMatchBetweenMigrationAndInit() throws IOException {
        assertColumnsEqual("ws_after_sale_action", MIG_A, INIT_SQL);
    }

    /** 包B 建的事实收件箱：同上。 */
    @Test
    void refundEventColumnsMatchBetweenMigrationAndInit() throws IOException {
        assertColumnsEqual("ws_refund_event", MIG_B, INIT_SQL);
    }

    /** 包D 权益批次：迁移与 init 必须逐列一致。 */
    @Test
    void entitlementBatchColumnsMatchBetweenMigrationAndInit() throws IOException {
        assertColumnsEqual("ws_card_entitlement_batch", MIG_D, INIT_SQL);
    }

    /** 包D 消费分摊：同上。 */
    @Test
    void entitlementAllocationColumnsMatchBetweenMigrationAndInit() throws IOException {
        assertColumnsEqual("ws_entitlement_allocation", MIG_D, INIT_SQL);
    }

    /**
     * REQ-213 参数定义注册表：迁移与 init 必须逐列一致。
     *
     * <p>这两张表的迁移是<b>补写</b>的：首版只建在 init，而 init 只在空库首启执行，
     * 既有主库永远不会再跑它。与此同时下发路径在建指令<b>之前</b>就要查 ws_device_param_def
     * 取参数定义——实测对主库直接 1146 Table doesn't exist，参数同步与价格同步整体打不出去，
     * 而全量测试因自建表全绿。形状与本类注释记录的那次 P0 完全一致，只是方向相反。</p>
     */
    @Test
    void deviceParamDefColumnsMatchBetweenMigrationAndInit() throws IOException {
        assertColumnsEqual("ws_device_param_def", MIG_PARAM, INIT_SQL);
    }

    /** REQ-213 设备参数快照表：同上。 */
    @Test
    void deviceParamColumnsMatchBetweenMigrationAndInit() throws IOException {
        assertColumnsEqual("ws_device_param", MIG_PARAM, INIT_SQL);
    }

    /**
     * B23 审计导出任务表：迁移与 init 必须逐列一致。
     *
     * <p>合规页的导出申请在此表落库前只存在于前端内存；接真后页面加载即查该表，
     * 表缺列/缺表会让「安全与合规」整页 500——与本类注释记录的那次 P0 同形。</p>
     */
    @Test
    void auditExportTaskColumnsMatchBetweenMigrationAndInit() throws IOException {
        assertColumnsEqual("ws_audit_export_task", MIG_AUDIT_EXPORT, INIT_SQL);
    }

    /**
     * 分润 V2 三表（E2E-08 S1）三轨同源：迁移 ↔ init ↔ 领域源文件。
     *
     * <p>V2 组件表的唯一键是并发防重复的地基，三轨漂移意味着评审看到的结构
     * 与真正上线的结构不同——与导出任务同一守卫形状。</p>
     */
    /**
     * V1 分账记录表（审查维度1-③补位）：领域源曾缺 uk_split_order_receiver 与 REFUND_ID——
     * 按领域源初始化的环境没有分账幂等唯一键，enqueue 的撞键幂等整个失效，重放即双倍行。
     * init 与领域源必须逐列一致，且幂等唯一键两轨都在（该表建表在 settlement-e2e08-a 迁移，
     * 唯一键由同名迁移补挂，故迁移轨按唯一键断言）。
     */
    /**
     * R1 P1-3 锁锚表：三轨（迁移/init/领域源）逐列一致——配置写入串行化的地基。
     * 不走 assertColumnsEqual（其「至少 5 列」自检对单列锚表误伤），直接列集比对。
     */
    @Test
    void splitLineLockMatchesAcrossAllThreeTracks() throws IOException {
        Path mig = REPO.resolve("deploy/mysql/migrations/2026-08-07-split-line-lock.sql");
        Path domain = REPO.resolve("server/sql/ws_trade.sql");
        Set<String> migCols = columnsOf(read(mig), "ws_split_line_lock");
        assertTrue(migCols.contains("PRODUCT_LINE"),
                "锁锚表未能从迁移解析出 PRODUCT_LINE，测试本身失效");
        assertEquals(migCols, columnsOf(read(INIT_SQL), "ws_split_line_lock"),
                "锁锚表在迁移与 init 的列集合不一致");
        assertEquals(migCols, columnsOf(read(domain), "ws_split_line_lock"),
                "锁锚表在迁移与领域源的列集合不一致");
        // 种子两行必须随建表同轨：init 直插、迁移 INSERT IGNORE（幂等），缺种子=锁面为空
        assertTrue(read(mig).contains("INSERT IGNORE INTO `ws_split_line_lock`"),
                "迁移缺锁锚种子（INSERT IGNORE）");
        assertTrue(read(INIT_SQL).contains("INSERT INTO `ws_split_line_lock`"),
                "init 缺锁锚种子");
        assertTrue(read(domain).contains("INSERT INTO `ws_split_line_lock`"),
                "领域空库源缺锁锚种子");
    }

    @Test
    void splitRecordMatchesBetweenInitAndDomainWithIdempotencyKey() throws IOException {
        Path domain = REPO.resolve("server/sql/ws_trade.sql");
        Path mig = REPO.resolve("deploy/mysql/migrations/2026-07-31-settlement-e2e08-a.sql");
        assertColumnsEqual("ws_split_record", INIT_SQL, domain);
        for (Path sql : new Path[] { INIT_SQL, domain, mig }) {
            assertTrue(read(sql).contains("uk_split_order_receiver"),
                    display(sql) + " 缺分账幂等唯一键 uk_split_order_receiver");
        }
    }

    /**
     * D-421 R1-P2：钱包在途分润聚合索引三轨逐字一致。列序=查询序
     * (RECEIVER_USER_ID, SPLIT_STATUS, CREATE_TIME)——过滤两列走索引、
     * MIN(CREATE_TIME) 顺索引取首行；列序漂移即索引失效退回全表扫描。
     */
    @Test
    void splitWalletIndexMatchesAcrossAllThreeTracks() throws IOException {
        Path domain = REPO.resolve("server/sql/ws_trade.sql");
        Path mig = REPO.resolve("deploy/mysql/migrations/2026-08-07-split-record-wallet-index.sql");
        String indexDef = "`idx_split_pending_wallet` (`RECEIVER_USER_ID`, `SPLIT_STATUS`, `CREATE_TIME`)";
        for (Path sql : new Path[] { INIT_SQL, domain, mig }) {
            assertTrue(read(sql).contains(indexDef),
                    display(sql) + " 缺钱包在途聚合索引 idx_split_pending_wallet（含列序逐字一致）");
        }
    }

    @Test
    void splitV2TablesMatchAcrossAllThreeTracks() throws IOException {
        Path mig = REPO.resolve("deploy/mysql/migrations/2026-08-06-split-v2-s1.sql");
        Path domain = REPO.resolve("server/sql/ws_trade.sql");
        for (String table : new String[] { "ws_split_plan", "ws_split_plan_item", "ws_split_component" }) {
            assertColumnsEqual(table, mig, INIT_SQL);
            assertColumnsEqual(table, mig, domain);
        }
        // 唯一键三轨都在：组件幂等与计划版本唯一是 S1 的两条硬约束
        for (Path sql : new Path[] { mig, INIT_SQL, domain }) {
            String body = read(sql);
            assertTrue(body.contains("uk_split_component_key"),
                    display(sql) + " 缺组件幂等唯一键 uk_split_component_key");
            assertTrue(body.contains("uk_split_plan_version"),
                    display(sql) + " 缺计划版本唯一键 uk_split_plan_version");
            assertTrue(body.contains("uk_split_plan_item"),
                    display(sql) + " 缺计划项唯一键 uk_split_plan_item");
        }
    }

    private String display(Path p) {
        return REPO.relativize(p).toString();
    }

    /**
     * 导出任务的三份 SQL（领域源文件 / 迁移 / init）必须同源。
     *
     * <p>`server/sql/*.sql` 是领域源文件，实际执行的是迁移与 init 两轨；
     * 三者漂移时，按源文件评审通过的结构未必是真正上线的结构。</p>
     */
    @Test
    void auditExportTaskDomainSqlMatchesDeployedSchema() throws IOException {
        Path domainSql = REPO.resolve("server/sql/ws_audit_export.sql");
        assertColumnsEqual("ws_audit_export_task", domainSql, INIT_SQL);
    }

    /**
     * 包B 对 ws_refund 是 ALTER 改造而非新建，两侧写法不同（一边 ADD COLUMN、一边 CREATE TABLE），
     * 故这里改为断言「迁移新增的每一列都出现在 init 的建表语句里」。
     */
    @Test
    void refundAlteredColumnsAllPresentInInit() throws IOException {
        String mig = read(MIG_B);
        Set<String> altered = new LinkedHashSet<>();
        Matcher m = Pattern.compile("ADD COLUMN `([A-Z0-9_]+)`").matcher(mig);
        while (m.find()) {
            altered.add(m.group(1));
        }
        Matcher renamed = Pattern.compile("CHANGE COLUMN `[A-Z0-9_]+` `([A-Z0-9_]+)`").matcher(mig);
        while (renamed.find()) {
            altered.add(renamed.group(1));
        }
        assertTrue(altered.size() >= 11, "迁移里 ws_refund 的新增列不应少于 11 个，实际 " + altered);

        Set<String> initCols = columnsOf(read(INIT_SQL), "ws_refund");
        Set<String> missing = new LinkedHashSet<>(altered);
        missing.removeAll(initCols);
        assertTrue(missing.isEmpty(),
                "迁移给 ws_refund 加了这些列但 init 没有——全新环境将缺列，退款链在那里必崩：" + missing);
    }

    /**
     * 真库单测的 schema 是<b>第三份</b> DDL。它漏列时的表现是「单测报 Unknown column」，
     * 比缺 init 温和得多，但同样会让人以为是代码问题而不是 schema 问题。
     */
    @Test
    void testSchemaCoversEveryProductionColumnOfOwnedTables() throws IOException {
        String testSchema = read(TEST_SCHEMA);
        String init = read(INIT_SQL);
        for (String table : new String[] { "ws_after_sale_action", "ws_refund", "ws_refund_event",
                "ws_card_entitlement_batch", "ws_entitlement_allocation",
                "ws_alarm", "ws_work_order", "ws_command_batch" }) {
            Set<String> prod = columnsOf(init, table);
            assertTrue(prod.size() > 5, table + " 未能从 init 解析出列，测试本身失效");
            Set<String> inTest = columnsOf(testSchema, table);
            Set<String> missing = new LinkedHashSet<>(prod);
            missing.removeAll(inTest);
            assertTrue(missing.isEmpty(), table + " 在真库单测 schema 里缺列：" + missing);
        }
    }

    /**
     * R-201 员工口令哈希化：迁移给 api_employee 加的 PWD_CHANGE_FLAG 必须同步在 01-base init；
     * 且两侧的员工种子/重置口令都必须是 BCrypt（$2 前缀）——init 残留 RSA 密文时，
     * 新代码 fail-closed 会把全新环境的 admin 直接锁在门外。
     */
    @Test
    void employeePwdHashColumnAndSeedConsistentBetweenMigrationAndBaseInit() throws IOException {
        Path migPwd = REPO.resolve("deploy/mysql/migrations/2026-08-05-employee-pwd-hash-r201.sql");
        String migration = read(migPwd);
        assertTrue(migration.contains("`PWD_CHANGE_FLAG`"), "R-201 迁移缺少 PWD_CHANGE_FLAG 列");
        assertTrue(columnsOf(read(INIT_BASE_SQL), "api_employee").contains("PWD_CHANGE_FLAG"),
                "迁移给 api_employee 加了 PWD_CHANGE_FLAG 但 01-base init 没有——全新环境将缺列");
        Pattern bcryptSeed = Pattern.compile("'\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}'");
        assertTrue(bcryptSeed.matcher(migration).find(), "R-201 迁移的重置口令必须是 BCrypt 哈希");
        Matcher initSeed = bcryptSeed.matcher(read(INIT_BASE_SQL));
        assertTrue(initSeed.find(), "01-base 的 admin 种子口令必须是 BCrypt 哈希——RSA 密文在新代码下无法登录");
    }

    /**
     * api_employee 的第三份 DDL（真库单测共享 schema）必须覆盖 01-base 全部生产列：
     * PO 新增字段后 MyBatis-Plus 全字段 SELECT 会在缺列的测试库上报 Unknown column——
     * R-201 加 PWD_CHANGE_FLAG 时 MessageCenterDbTest/DeviceOpsWorkOrderDbTest 实际炸过一轮。
     */
    @Test
    void testSchemaCoversEveryProductionColumnOfApiEmployee() throws IOException {
        Set<String> prod = columnsOf(read(INIT_BASE_SQL), "api_employee");
        assertTrue(prod.size() > 5, "api_employee 未能从 01-base 解析出列，测试本身失效");
        Set<String> inTest = columnsOf(read(TEST_SCHEMA), "api_employee");
        Set<String> missing = new LinkedHashSet<>(prod);
        missing.removeAll(inTest);
        assertTrue(missing.isEmpty(), "api_employee 在真库单测 schema 里缺列：" + missing);
    }

    /**
     * 字典写入不得使用 {@code INSERT IGNORE}——这条规则只有测试能守住。
     *
     * <h3>为什么必须有这道守卫</h3>
     * <p>{@code api_dict_type} 与 {@code api_dict_data} 除 {@code PRIMARY KEY(ID)} 外没有任何
     * 唯一索引，而字典 INSERT 一律不写 ID（走自增）。于是 {@code IGNORE} <b>无键可撞、
     * 等同普通 INSERT</b>：init 在空库灌一套、迁移在既有库再灌一套，字典就翻倍；
     * 而字典查询是 {@code selectJoinOne}，遇重复行直接 TooManyResults，
     * {@code /api/dict/listByType} 对该编号整个返回 500。</p>
     *
     * <p>本项目已经踩过<b>两次</b>：E2E-08 的 1376~1381、B23 的 1382。两次都是照着当时
     * AGENTS.md 那句"【强制】必须使用 INSERT IGNORE"写的——文档已按约束事实改写，
     * 但只改文档挡不住第三次，故固化成测试。</p>
     *
     * <p>菜单与角色绑定不在此列：它们的 INSERT 显式写主键 ID，撞主键即被忽略，
     * {@code IGNORE} 对它们是有效的。</p>
     */
    /**
     * 破坏性 DDL 的作用域边界：迁移绝不删表，领域源文件必须自带「仅限空库」横幅。
     *
     * <p>补的是一处工作流层面的踩雷点：{@code server/sql/} 下 9 份 {@code ws_*.sql}
     * <b>全部</b>以 {@code DROP TABLE IF EXISTS} 开头（{@code ws_device} 与
     * {@code ws_delivery} 各删 6 张表），而 AGENTS.md 曾指示把
     * {@code server/sql/<模块>.sql} 直接管道给主库。照字面执行一次，该域数据全没，
     * MySQL 不会给任何警告。工作流已改为「空库走 init／既有库走 migrations」，
     * 这道闸看住两侧不再漂回去。</p>
     *
     * <p>只扫 {@code .sql}：同目录的 {@code verify-*.sh} 确有 {@code DROP DATABASE}，
     * 但它们作用于按 PID 命名的一次性容器（{@code dakang-l2db-test-$$}），
     * 是隔离验证手段，不是对既有库的操作。</p>
     */
    @Test
    void migrationsCarryNoDestructiveDdl() throws IOException {
        Pattern destructive = Pattern.compile(
                "\\b(DROP\\s+(TABLE|DATABASE|SCHEMA)|TRUNCATE\\s+TABLE?)\\b", Pattern.CASE_INSENSITIVE);
        Set<String> violations = new LinkedHashSet<>();
        List<Path> migrations = sqlFilesUnder("deploy/mysql/migrations");
        assertTrue(migrations.size() > 5, "未扫描到足够的迁移文件，守卫本身失效");
        for (Path sql : migrations) {
            if (destructive.matcher(stripSqlComments(read(sql))).find()) {
                violations.add(REPO.relativize(sql).toString());
            }
        }
        assertTrue(violations.isEmpty(),
                "deploy/mysql/migrations/ 作用于既有库（含主库 3308），禁止 DROP/TRUNCATE。"
                        + "需要重建表结构请写 ALTER，或另开一次性授权流程。违规文件：" + violations);
    }

    @Test
    void destructiveDomainSqlDeclaresEmptyDbOnly() throws IOException {
        Set<String> missing = new LinkedHashSet<>();
        List<Path> domain = sqlFilesUnder("server/sql");
        assertTrue(domain.size() > 5, "未扫描到足够的领域 SQL 文件，守卫本身失效");
        for (Path sql : domain) {
            String body = read(sql);
            if (body.toUpperCase().contains("DROP TABLE") && !body.contains("【仅限空库】")) {
                missing.add(REPO.relativize(sql).toString());
            }
        }
        assertTrue(missing.isEmpty(),
                "server/sql/ 下含 DROP TABLE 的文件必须在开头声明「【仅限空库】」横幅——"
                        + "打开文件的人第一眼就要看见「对既有库执行会静默删光本域数据」，"
                        + "而不是翻 AGENTS.md 才知道。缺横幅的文件：" + missing);
    }

    private List<Path> sqlFilesUnder(String dir) throws IOException {
        List<Path> out = new java.util.ArrayList<>();
        Path root = REPO.resolve(dir);
        if (Files.isDirectory(root)) {
            try (var paths = Files.walk(root)) {
                paths.filter(p -> p.toString().endsWith(".sql")).forEach(out::add);
            }
        }
        return out;
    }

    /** 去掉 {@code --} 行注释与块注释，避免把说明文字里的 DROP TABLE 当成可执行语句。 */
    private String stripSqlComments(String sql) {
        return sql.replaceAll("(?m)^\\s*--.*$", "")
                .replaceAll("(?s)/\\*.*?\\*/", "");
    }

    @Test
    void dictionaryInsertsNeverUseInsertIgnore() throws IOException {
        // 只扫「能对非空库重复执行」的两类文件。
        // init/*.sql 不在其列：它们由 docker-entrypoint-initdb.d 仅在空数据卷首启执行一次，
        // 跑一次不会翻倍；把它们一并纳入会逼着改写整个底座 SQL，收益与风险不成比例。
        List<Path> sqlFiles = new java.util.ArrayList<>();
        for (String dir : new String[] { "deploy/mysql/migrations", "server/sql" }) {
            Path root = REPO.resolve(dir);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var paths = Files.walk(root)) {
                paths.filter(p -> p.toString().endsWith(".sql")).forEach(sqlFiles::add);
            }
        }
        assertTrue(sqlFiles.size() > 10, "未扫描到足够的 SQL 文件，守卫本身失效");

        Pattern offender = Pattern.compile(
                "INSERT\\s+IGNORE\\s+INTO\\s+`?api_dict_(type|data)`?", Pattern.CASE_INSENSITIVE);
        Set<String> violations = new LinkedHashSet<>();
        for (Path sql : sqlFiles) {
            String name = sql.getFileName().toString();
            if (GRANDFATHERED_DICT_IGNORE.contains(name)) {
                continue;
            }
            if (offender.matcher(read(sql)).find()) {
                violations.add(REPO.relativize(sql).toString());
            }
        }
        assertTrue(violations.isEmpty(),
                "字典表禁用 INSERT IGNORE（api_dict_type/api_dict_data 无唯一键可撞，"
                        + "重复执行会翻倍并让 /api/dict/listByType 对该编号返回 500）；"
                        + "请改用 INSERT ... SELECT ... WHERE NOT EXISTS，"
                        + "范式见 2026-07-29-aftersale-e2e04-a.sql 或 2026-08-06-audit-export-b23.sql。违规文件：" + violations);
    }

    /**
     * 存量欠账：这些文件早于本守卫，且多数已在主库执行过，改写它们需要配套去重脚本与独立授权，
     * 故先登记不清零——守卫的目的是<b>不再新增</b>第三次，而不是一次性重构历史 SQL。
     *
     * <p>其中 {@code 2026-07-31-settlement-e2e08-a.sql} 是已确认正在造成实际故障的一处
     * （字典 1376~1381 在验收库实测各 2 行，对应编号的字典接口 500），已登记为 R-206。
     * 清理该项时必须连带主库去重，不能只改 SQL 文件。</p>
     *
     * <p><b>本清单只减不增</b>：新增文件一律走 NOT EXISTS。</p>
     */
    private static final Set<String> GRANDFATHERED_DICT_IGNORE = Set.of(
            "2026-07-20-closure-repair.sql",
            "2026-07-31-settlement-e2e08-a.sql",
            "ws_command.sql", "ws_delivery.sql", "ws_device.sql", "ws_message.sql",
            "ws_ops.sql", "ws_trade.sql", "ws_user_card.sql");

    /** E2E-05 包A 新表：迁移与 init 必须逐列一致（同一守卫扩展到设备运营域）。 */
    @Test
    void commandBatchColumnsMatchBetweenMigrationAndInit() throws IOException {
        assertColumnsEqual("ws_command_batch", MIG_E5A, INIT_SQL);
    }

    /** E2E-05 迁移对既有表的 ADD COLUMN：每个新列 init 权威结构里必须存在，否则新装环境缺列。 */
    @Test
    void deviceOpsAlteredColumnsAllPresentInInit() throws IOException {
        String migration = read(MIG_E5A);
        String init = read(INIT_SQL);
        java.util.Map<String, String> owner = java.util.Map.ofEntries(
                java.util.Map.entry("ACTIVE_DEDUPE_KEY", "ws_alarm"),
                java.util.Map.entry("HANDLE_BY", "ws_alarm"),
                java.util.Map.entry("HANDLE_TIME", "ws_alarm"),
                java.util.Map.entry("WORK_TYPE", "ws_work_order"),
                java.util.Map.entry("REQUEST_ID", "ws_work_order"),
                java.util.Map.entry("VERSION", "ws_work_order"),
                java.util.Map.entry("BATCH_ID", "ws_command"),
                java.util.Map.entry("SIM_STATUS", "ws_device"),
                java.util.Map.entry("SIM_EXPIRE_TIME", "ws_device"),
                java.util.Map.entry("LAST_STATUS_DEVICE_TIME", "ws_device"),
                java.util.Map.entry("OWNER_PORTAL", "ws_delivery_media"));
        for (var e : owner.entrySet()) {
            assertTrue(migration.contains("`" + e.getKey() + "`"),
                    "迁移缺少 " + e.getValue() + "." + e.getKey() + "，守卫清单需同步");
            assertTrue(columnsOf(init, e.getValue()).contains(e.getKey()),
                    "迁移给 " + e.getValue() + " 加了 " + e.getKey() + " 但 init 没有——全新环境将缺列");
        }
    }

    /** E2E-06 包A：站轨聚合索引必须同时在迁移与 init（缺 init 则新装环境站轨查询全表扫描）。 */
    @Test
    void ownerAnalyticsStationIndexPresentInBothMigrationAndInit() throws IOException {
        Path migE6 = REPO.resolve("deploy/mysql/migrations/2026-07-31-owner-analytics-e2e06-a.sql");
        assertTrue(read(migE6).contains("idx_order_station_time"), "E2E-06 迁移缺少站轨索引");
        assertTrue(read(INIT_SQL).contains("idx_order_station_time"), "init 权威结构缺少站轨索引");
    }

    /** 包A 在旧库升级时早于 d3 执行，不能把 d3 才追加的1370#4当成自己的后置条件。 */
    @Test
    void packageADictionaryGuardDependsOnlyOnItsOwnThirteenRows() throws IOException {
        String migration = read(MIG_A);
        assertTrue(migration.contains("@ok_dict_owned = 13"),
                "包A必须按自己写入的13项做精确后置检查");
        assertTrue(!migration.contains("@ok_dict = 14"),
                "包A早于d3执行，要求14项会让真实旧库升级必然中止");
    }

    /** 包B 已上线并产生退款单后仍必须可重跑；只有来源列尚不存在的脏数据需要阻断。 */
    @Test
    void refundMigrationDistinguishesUnmigratedRowsFromValidRerunData() throws IOException {
        String migration = read(MIG_B);
        assertTrue(migration.contains("@c_refund_source = 1 OR @r_rows = 0"),
                "已存在REFUND_SOURCE时不得因ws_refund非空拒绝幂等重跑");
        assertTrue(migration.contains("@invalid_refund_source"),
                "已迁移数据仍须阻断NULL或未知退款来源");
    }

    /** d3 的重复字典、权限宿主和角色绑定必须全部在首条写入前检查，并由事务包住全部DML。 */
    @Test
    void refundPermissionMigrationPreflightsPollutionBeforeWriting() throws IOException {
        String migration = read(MIG_D3);
        int firstWrite = migration.indexOf("INSERT INTO `api_dict_data`");
        assertTrue(firstWrite > 0, "未找到d3首条写入");
        for (String guard : new String[] { "@src4_rows", "@perm_host_conflict", "@role_bind_count" }) {
            int position = migration.indexOf(guard);
            assertTrue(position > 0 && position < firstWrite, guard + "必须在首条写入前完成");
        }
        int begin = migration.indexOf("START TRANSACTION;");
        int commit = migration.indexOf("COMMIT;");
        assertTrue(begin > 0 && begin < firstWrite && commit > firstWrite,
                "d3全部字典/权限DML必须处于同一事务");
    }

    /** 历史权益回填的写入与账本后置断言必须原子，失败时不能留下部分批次。 */
    @Test
    void legacyEntitlementBackfillChecksBeforeCommit() throws IOException {
        String migration = read(MIG_D2);
        int insert = migration.indexOf("INSERT INTO `ws_card_entitlement_batch`");
        int begin = migration.indexOf("START TRANSACTION;");
        int lastInvariant = migration.indexOf("@ok_legacy");
        int commit = migration.indexOf("COMMIT;");
        assertTrue(begin > 0 && begin < insert,
                "历史权益回填必须在首条写入前开启事务");
        assertTrue(lastInvariant > insert && commit > lastInvariant,
                "全部账本后置断言通过后才允许提交历史权益回填");
    }

    /** 验收产物必须扫描本轮真实动态密钥，固定Token头名不能替代Token值。 */
    @Test
    void afterSaleRunnerScansRuntimeSecretsBeforeWritingReport() throws IOException {
        String runner = read(AFTER_SALE_RUNNER);
        for (String secret : new String[] { "ctx.owner && ctx.owner.tokenValue",
                "ctx.courier && ctx.courier.tokenValue", "ctx.ops && ctx.ops.tokenValue",
                "value: OPS_PWD", "value: DB.password" }) {
            assertTrue(runner.contains(secret), "验收出口缺少动态秘密扫描：" + secret);
        }
        assertTrue(runner.indexOf("runtimeSecrets.filter") < runner.indexOf("fs.writeFileSync"),
                "动态秘密扫描必须发生在result.json写盘之前");
    }

    /** 充值售后必须显示支付记录来源，不能把订单 PAY_WAY=1 直接翻译成微信收款。 */
    @Test
    void afterSaleProjectionIncludesUnambiguousRechargePaymentSource() throws IOException {
        String mapper = read(AFTER_SALE_MAPPER);
        assertTrue(mapper.contains("p.PAY_SOURCE         AS paySource"),
                "售后读模型必须下发充值支付来源");
        assertTrue(mapper.contains("LEFT JOIN ws_payment p ON p.ORDER_ID = o.ID"),
                "售后读模型必须从支付记录获取来源");
        assertTrue(read(INIT_SQL).contains("UNIQUE KEY `uk_payment_order_id` (`ORDER_ID`)"),
                "一单一支付单唯一键缺失时 LEFT JOIN 可能放大售后台账行");
    }

    // ==================== 解析 ====================

    /** 索引/约束子句的行首词，绝不是列名。不排除的话「UNIQUE KEY uk_x (...)」会被算成一列 UNIQUE。 */
    private static final Set<String> CLAUSE_KEYWORDS =
            Set.of("PRIMARY", "UNIQUE", "KEY", "INDEX", "CONSTRAINT", "FOREIGN", "FULLTEXT", "CHECK");

    private void assertColumnsEqual(String table, Path left, Path right) throws IOException {
        Set<String> a = columnsOf(read(left), table);
        Set<String> b = columnsOf(read(right), table);
        assertTrue(a.size() > 5, table + " 未能从 " + left.getFileName() + " 解析出列，测试本身失效");
        assertEquals(a, b, table + " 在 " + left.getFileName() + " 与 " + right.getFileName() + " 的列集合不一致");
    }

    /**
     * 从 SQL 文本里取某张表 CREATE TABLE 语句的列名集合。
     *
     * <p>三份 DDL 的排版差异不小：生产 DDL 每列一行且带反引号，真库单测 schema
     * 为了紧凑把多列写在同一行且不带反引号，收尾的 {@code )} 缩进也不同。
     * 因此不按行解析，而是<b>按括号深度为 0 的逗号切分</b>——
     * 这样 {@code VARCHAR(20)} 与 {@code KEY idx_x (A, B)} 内部的逗号都不会切错。</p>
     */
    private Set<String> columnsOf(String sql, String table) {
        Matcher block = Pattern.compile(
                "CREATE TABLE (?:IF NOT EXISTS )?`?" + table + "`?\\s*\\((.*?)\\n\\s*\\)\\s*ENGINE",
                Pattern.DOTALL).matcher(sql);
        Set<String> cols = new LinkedHashSet<>();
        if (!block.find()) {
            return cols;
        }
        String body = block.group(1);
        StringBuilder frag = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
            }
            if (ch == ',' && depth == 0) {
                addColumn(cols, frag.toString());
                frag.setLength(0);
            } else {
                frag.append(ch);
            }
        }
        addColumn(cols, frag.toString());
        return cols;
    }

    /**
     * 片段形如「`NAME` type ...」或「NAME type ...」；索引/约束子句一律丢弃。
     *
     * <p>字符类必须含数字：最初写的是 {@code [A-Z_]+}，于是 RAW_BODY_SHA256 这类
     * 带数字的列<b>在两侧都被漏掉</b>——两边同时漏掉的列，比对时当然一致，
     * 守卫对它形同虚设。这个盲区是靠「删掉该列后测试仍绿」的注入验证发现的，
     * 单看测试全绿完全看不出来。</p>
     */
    private void addColumn(Set<String> cols, String fragment) {
        Matcher col = Pattern.compile("^\\s*`?([A-Z0-9_]+)`?\\s+[a-zA-Z]").matcher(fragment.replace("\n", " "));
        if (col.find() && !CLAUSE_KEYWORDS.contains(col.group(1))) {
            cols.add(col.group(1));
        }
    }

    private String read(Path p) throws IOException {
        assertTrue(Files.exists(p), "找不到 " + p);
        return Files.readString(p, StandardCharsets.UTF_8);
    }
}
