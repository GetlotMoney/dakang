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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同一张表的多份 DDL 必须一致——常态守卫（E2E-04）。
 * 主环境只执行 init 不执行 migrations，而单测自建表、验收环境跑 migrations，
 * DDL 漂移在两侧都盖不到，只能靠本类静态比对。
 * 比对口径只取列名集合：类型/索引写法三轨本就不同，列缺失才是最常见且后果最直接的漂移。
 */
class SchemaParityTest {

    private static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    private static final Path INIT_SQL = REPO.resolve("deploy/mysql/init/02-ws-business.sql");
    private static final Path INIT_BASE_SQL = REPO.resolve("deploy/mysql/init/01-base.sql");
    private static final Path INIT_IDENTITY_SQL = REPO.resolve("deploy/mysql/init/04-identity-workspaces.sql");
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

    /** 每个独立 init 文件都由 entrypoint 单独执行，不能继承上一文件的 USE 语句。 */
    @Test
    void identityInitSelectsDatabaseBeforeCreatingTables() throws IOException {
        String sql = read(INIT_IDENTITY_SQL).toUpperCase();
        int useDatabase = sql.indexOf("USE DAKANG;");
        int firstTable = sql.indexOf("CREATE TABLE");
        assertTrue(useDatabase >= 0 && firstTable > useDatabase,
                "04-identity-workspaces.sql 必须在首张表之前执行 USE dakang，否则全新 MySQL 首启报 No database selected");
    }

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

    /** REQ-213 参数定义注册表：迁移与 init 逐列一致（init 只在空库首启执行，只建 init 会让既有主库缺表）。 */
    @Test
    void deviceParamDefColumnsMatchBetweenMigrationAndInit() throws IOException {
        assertColumnsEqual("ws_device_param_def", MIG_PARAM, INIT_SQL);
    }

    /** REQ-213 设备参数快照表：同上。 */
    @Test
    void deviceParamColumnsMatchBetweenMigrationAndInit() throws IOException {
        assertColumnsEqual("ws_device_param", MIG_PARAM, INIT_SQL);
    }

    /** B23 审计导出任务表：迁移与 init 逐列一致（缺表会让「安全与合规」整页 500）。 */
    @Test
    void auditExportTaskColumnsMatchBetweenMigrationAndInit() throws IOException {
        assertColumnsEqual("ws_audit_export_task", MIG_AUDIT_EXPORT, INIT_SQL);
    }

    /** 分润 V2 三表（E2E-08 S1）三轨同源：组件表唯一键是并发防重复的地基。 */
    /**
     * V1 分账记录表（审查维度1-③补位）：领域源曾缺 uk_split_order_receiver 与 REFUND_ID——
     * 按领域源初始化的环境没有分账幂等唯一键，enqueue 的撞键幂等整个失效，重放即双倍行。
     * init 与领域源必须逐列一致，且幂等唯一键两轨都在（该表建表在 settlement-e2e08-a 迁移，
     * 唯一键由同名迁移补挂，故迁移轨按唯一键断言）。
     */
    /** 微信发货同步 outbox 三轨同源（WX-ECO S4）：缺 uk_wxship_key 同一包裹会向微信重复上传发货。 */
    @Test
    void wechatShippingOutboxMatchesAcrossAllThreeTracks() throws IOException {
        Path mig = REPO.resolve("deploy/mysql/migrations/2026-08-13-wechat-shipping-outbox.sql");
        Path domain = REPO.resolve("server/sql/ws_mall.sql");
        assertColumnsEqual("ws_wechat_shipping_outbox", mig, INIT_SQL);
        assertColumnsEqual("ws_wechat_shipping_outbox", mig, domain);
        for (Path sql : new Path[] { mig, INIT_SQL, domain }) {
            assertTrue(read(sql).contains("uk_wxship_key"),
                    display(sql) + " 缺发货同步幂等唯一键 uk_wxship_key");
            // SKIP_REASON 是「Pay-Sim 单留痕未同步」与「真同步了」的唯一区分位
            assertTrue(read(sql).contains("SKIP_REASON"),
                    display(sql) + " 缺 SKIP_REASON 列");
        }
    }

    /** 微信订阅通知 outbox 三轨同源（WX-ECO S2）：缺 uk_wx_notify_key 会重复下发并耗光订阅额度。 */
    @Test
    void wechatNotifyOutboxMatchesAcrossAllThreeTracks() throws IOException {
        Path mig = REPO.resolve("deploy/mysql/migrations/2026-08-12-wechat-notify-outbox.sql");
        Path domain = REPO.resolve("server/sql/ws_message.sql");
        assertColumnsEqual("ws_wechat_notify_outbox", mig, INIT_SQL);
        assertColumnsEqual("ws_wechat_notify_outbox", mig, domain);
        for (Path sql : new Path[] { mig, INIT_SQL, domain }) {
            assertTrue(read(sql).contains("uk_wx_notify_key"),
                    display(sql) + " 缺通知幂等唯一键 uk_wx_notify_key");
        }
        // SKIP_REASON 是「已处理但没发出去」与「真的发出去了」的唯一区分位。
        // 少了它，模板未配与发送成功都记成 PROCESSED，运维看到一列绿色而用户一条没收到。
        for (Path sql : new Path[] { mig, INIT_SQL, domain }) {
            assertTrue(read(sql).contains("SKIP_REASON"),
                    display(sql) + " 缺 SKIP_REASON：未发送原因将无处记录");
        }
    }

    /**
     * 身份冲突台账三轨同源（WX-ECO S1）：缺唯一键台账被重试刷屏；
     * 缺表则留痕被 catch 吞成日志，冲突从此无痕。
     */
    @Test
    void identityConflictMatchesAcrossAllThreeTracks() throws IOException {
        Path mig = REPO.resolve("deploy/mysql/migrations/2026-08-12-identity-conflict.sql");
        Path domain = REPO.resolve("server/sql/ws_user_card.sql");
        assertColumnsEqual("ws_identity_conflict", mig, INIT_SQL);
        assertColumnsEqual("ws_identity_conflict", mig, domain);
        for (Path sql : new Path[] { mig, INIT_SQL, domain }) {
            assertTrue(read(sql).contains("uk_identity_conflict_key"),
                    display(sql) + " 缺冲突幂等唯一键 uk_identity_conflict_key");
        }
    }

    /**
     * 区域服务商归属表三轨同源：缺 uk_region_agent_version 时同区域同时点并存两行，
     * 解析器 LIMIT 1 取哪行取决于物理顺序——分润付给不确定的人。
     */
    @Test
    void regionAgentMatchesAcrossAllThreeTracks() throws IOException {
        Path mig = REPO.resolve("deploy/mysql/migrations/2026-08-12-region-agent.sql");
        Path domain = REPO.resolve("server/sql/ws_trade.sql");
        assertColumnsEqual("ws_region_agent", mig, INIT_SQL);
        assertColumnsEqual("ws_region_agent", mig, domain);
        for (Path sql : new Path[] { mig, INIT_SQL, domain }) {
            assertTrue(read(sql).contains("uk_region_agent_version"),
                    display(sql) + " 缺归属版本唯一键 uk_region_agent_version");
        }
    }

    /** 水站三级区划码三轨同源：只加 init 漏迁移会让主库缺列，解析器 Unknown column。 */
    @Test
    void stationRegionCodesExistInAllThreeTracks() throws IOException {
        Path mig = REPO.resolve("deploy/mysql/migrations/2026-08-12-region-agent.sql");
        Path domain = REPO.resolve("server/sql/ws_station.sql");
        for (String col : new String[] { "PROVINCE_CODE", "CITY_CODE", "DISTRICT_CODE" }) {
            for (Path sql : new Path[] { mig, INIT_SQL, domain }) {
                assertTrue(read(sql).contains(col),
                        display(sql) + " 缺水站区划码列 " + col);
            }
        }
        Set<String> initCols = columnsOf(read(INIT_SQL), "ws_station");
        assertTrue(initCols.containsAll(Set.of("PROVINCE_CODE", "CITY_CODE", "DISTRICT_CODE")),
                "init 的 ws_station 建表未解析出三个区划码列，判据本身失效");
        assertEquals(initCols, columnsOf(read(domain), "ws_station"),
                "水站表在 init 与领域源的列集合不一致");
    }

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

    /** D-420 R1：冲减事实表三轨落档（migrations/init/领域 SQL 均含表与唯一键）。 */
    @Test
    void splitClawbackTableMatchesAcrossAllThreeTracks() throws IOException {
        Path domain = REPO.resolve("server/sql/ws_trade.sql");
        Path mig = REPO.resolve("deploy/mysql/migrations/2026-08-07-split-clawback-s1.sql");
        for (Path sql : new Path[] { INIT_SQL, domain, mig }) {
            assertTrue(read(sql).contains("ws_split_clawback"),
                    display(sql) + " 缺冲减事实表 ws_split_clawback");
            assertTrue(read(sql).contains("uk_split_clawback_action_split"),
                    display(sql) + " 缺冲减幂等唯一键 uk_split_clawback_action_split");
        }
        assertColumnsEqual("ws_split_clawback", INIT_SQL, domain);
    }

    /**
     * D-420 R2：动作级 outbox 三轨落档。登记是客户退款事务内唯一的冲减写入，
     * 这张表缺席=登记路径 1146、退款整体回滚——正是 R2-P0-1 要消灭的事故形状。
     * ACTION_ID 唯一键是重放等价核验的库层地基，三轨都必须在。
     */
    @Test
    void splitClawbackActionOutboxMatchesAcrossAllThreeTracks() throws IOException {
        Path domain = REPO.resolve("server/sql/ws_trade.sql");
        Path mig = REPO.resolve("deploy/mysql/migrations/2026-08-07-split-clawback-s1.sql");
        for (Path sql : new Path[] { INIT_SQL, domain, mig }) {
            assertTrue(read(sql).contains("ws_split_clawback_action"),
                    display(sql) + " 缺冲减动作级 outbox 表 ws_split_clawback_action");
            assertTrue(read(sql).contains("`uk_split_clawback_action` (`ACTION_ID`)"),
                    display(sql) + " 缺动作唯一键 uk_split_clawback_action(ACTION_ID)");
        }
        assertColumnsEqual("ws_split_clawback_action", INIT_SQL, domain);
        assertColumnsEqual("ws_split_clawback_action", INIT_SQL, mig);
        // 真库单测 schema 同步落表：执行段/Worker 的真库测试全依赖它
        assertTrue(read(TEST_SCHEMA).contains("ws_split_clawback_action"),
                "DeliveryDbSchema 缺 ws_split_clawback_action");
    }

    /**
     * E2E-09 S1：商城六表三轨（init↔领域源↔迁移）逐列一致 + 五把唯一键在位 +
     * 真库单测 Schema 同步落表。商城库存的并发安全与幂等全部压在这些唯一键上，
     * 任何一轨漂移都会让「评审通过的结构」不等于「实际上线的结构」。
     */
    @Test
    void mallTablesMatchAcrossAllThreeTracks() throws IOException {
        Path domain = REPO.resolve("server/sql/ws_mall.sql");
        Path mig = REPO.resolve("deploy/mysql/migrations/2026-08-08-mall-s1.sql");
        for (String table : new String[] { "ws_mall_category", "ws_mall_product", "ws_mall_sku",
                "ws_mall_warehouse", "ws_mall_stock", "ws_mall_stock_flow" }) {
            assertColumnsEqual(table, INIT_SQL, domain);
            assertColumnsEqual(table, INIT_SQL, mig);
        }
        for (String uk : new String[] { "uk_mall_category_code", "uk_mall_product_no",
                "uk_mall_sku_no", "uk_mall_warehouse_no", "uk_mall_stock_wh_sku",
                "uk_mall_stock_flow_biz_key" }) {
            for (Path sql : new Path[] { INIT_SQL, domain, mig }) {
                assertTrue(read(sql).contains(uk), display(sql) + " 缺商城唯一键 " + uk);
            }
        }
        // 真库单测 Schema 六表同步：商城库存/上架闸真库测试全依赖它
        Path mallTestSchema = Path.of("src/test/java/com/jbk/serve/service/mall/impl/MallDbSchema.java");
        String testSchema = read(mallTestSchema);
        for (String table : new String[] { "ws_mall_category", "ws_mall_product", "ws_mall_sku",
                "ws_mall_warehouse", "ws_mall_stock", "ws_mall_stock_flow" }) {
            assertTrue(testSchema.contains(table), "MallDbSchema 缺 " + table);
        }
        // R1-P2-1 负库存库层硬闸：命名 CHECK 四轨（含测试 Schema）齐备——
        // 缺任何一轨，"负库存库层物理不可达"就是过度表述
        for (String check : new String[] { "chk_mall_stock_available_nonneg",
                "chk_mall_stock_reserved_nonneg" }) {
            for (Path sql : new Path[] { INIT_SQL, domain, mig }) {
                assertTrue(read(sql).contains(check), display(sql) + " 缺库存 CHECK 约束 " + check);
            }
            assertTrue(testSchema.contains(check), "MallDbSchema 缺库存 CHECK 约束 " + check);
        }
    }

    /**
     * 菜单基线不得有重复 ID：INSERT IGNORE + 显式主键下撞号被静默丢弃、新菜单不出现；
     * 号段按模块交错分配，不能用「最后一个 ID+1」推空闲号。
     */
    @Test
    void demoBaselineMenuIdsAreUnique() throws IOException {
        String baseline = read(REPO.resolve("deploy/mysql/init/03-demo-baseline.sql"));
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)^\\((\\d{3,4}),'").matcher(baseline);
        while (m.find()) {
            counts.merge(m.group(1), 1, Integer::sum);
        }
        assertTrue(counts.size() > 20, "未解析到菜单行，测试与基线结构已脱节");
        java.util.List<String> duplicated = counts.entrySet().stream()
                .filter(e -> e.getValue() > 1).map(java.util.Map.Entry::getKey).toList();
        assertTrue(duplicated.isEmpty(),
                "03-demo-baseline.sql 存在重复菜单 ID（INSERT IGNORE 会静默丢弃后来者）：" + duplicated);
    }

    /**
     * 库存流水类型字典（1391）与 MallEnum.StockFlowType 标签逐条一致：
     * 字典是 NOT EXISTS 幂等写入，错标签上线后改源文件不会更新既有行。
     */
    @Test
    void mallStockFlowDictLabelsMatchEnumDescriptions() throws IOException {
        String enumSource = read(Path.of(
                "src/main/java/com/jbk/tool/consts/mall/MallEnum.java"));
        int begin = enumSource.indexOf("enum StockFlowType");
        assertTrue(begin > 0, "未定位到 StockFlowType，测试与枚举结构脱节");
        String block = enumSource.substring(begin, enumSource.indexOf(';', begin));
        java.util.Map<String, String> fromEnum = new java.util.LinkedHashMap<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\w+\\((\\d+),\\s*\"([^\"]+)\"\\)").matcher(block);
        while (m.find()) {
            fromEnum.put(m.group(1), m.group(2));
        }
        assertEquals(11, fromEnum.size(),
                "StockFlowType 应有 11 个取值（1~4 人工，5~8 订单流转，9~11 换货补发）");

        for (Path sql : new Path[] { INIT_SQL, REPO.resolve("server/sql/ws_mall.sql") }) {
            String text = read(sql);
            java.util.Map<String, String> fromSql = new java.util.LinkedHashMap<>();
            java.util.regex.Matcher a = java.util.regex.Pattern.compile(
                    "SELECT '1391' AS DICT_TYPE, \\d+ AS DICT_SORT, (\\d+) AS DICT_VALUE, '([^']+)'")
                    .matcher(text);
            while (a.find()) {
                fromSql.put(a.group(1), a.group(2));
            }
            java.util.regex.Matcher b = java.util.regex.Pattern
                    .compile("SELECT '1391', \\d+, (\\d+), '([^']+)'").matcher(text);
            while (b.find()) {
                fromSql.put(b.group(1), b.group(2));
            }
            assertEquals(fromEnum, fromSql,
                    display(sql) + " 的 1391 字典标签与 MallEnum.StockFlowType 不一致");
        }
    }

    /**
     * E2E-09 S2 交易五表三轨一致。这五张表承载资金与库存预占，列漂移的代价是
     * 主库上线后首写即 500 或金额恒等式失效——故列清单、唯一键、CHECK 三项逐条钉住。
     * 唯一键刻意不含 DATA_STATUS：创单幂等锚与支付事实键都不能因误删而解锁重放。
     */
    @Test
    void mallS2TradeTablesMatchAcrossAllTracks() throws IOException {
        Path domain = REPO.resolve("server/sql/ws_mall.sql");
        Path mig = REPO.resolve("deploy/mysql/migrations/2026-08-08-mall-s2.sql");
        String[] tables = { "ws_mall_cart_item", "ws_mall_order", "ws_mall_order_item",
                "ws_mall_payment", "ws_mall_payment_fact" };
        for (String table : tables) {
            assertColumnsEqual(table, INIT_SQL, domain);
            if ("ws_mall_order".equals(table)) {
                // SOURCE_AFTER_SALE_ID 由 S4 迁移 ALTER 补挂：S2 迁移轨天然没有它，这不是漂移。
                // 空库靠 init/领域源直接建出该列，既有库靠 S4 迁移拿到；两条路径的终态
                // 由下方 S4 段的 ADD COLUMN 与唯一键断言各自钉住。
                Set<String> initCols = new LinkedHashSet<>(columnsOf(read(INIT_SQL), table));
                assertTrue(initCols.remove("SOURCE_AFTER_SALE_ID"),
                        "init 的 ws_mall_order 缺换货来源列，S4 三轨已漂移");
                assertEquals(initCols, columnsOf(read(mig), table),
                        "ws_mall_order 在 init 与 S2 迁移的列集合不一致（已排除 S4 补挂列）");
                continue;
            }
            assertColumnsEqual(table, INIT_SQL, mig);
        }
        for (String uk : new String[] { "uk_mall_cart_user_sku", "uk_mall_order_no",
                "uk_mall_order_user_request", "uk_mall_order_item_order_sku",
                "uk_mall_payment_transaction", "uk_mall_payment_order_no",
                "uk_mall_payment_order_id", "uk_mall_payment_fact_key" }) {
            for (Path sql : new Path[] { INIT_SQL, domain, mig }) {
                assertTrue(read(sql).contains(uk), display(sql) + " 缺 S2 唯一键 " + uk);
            }
        }
        // 金额与数量恒等式的库层兜底：篡改任一列都写不进去
        for (String check : new String[] { "chk_mall_cart_qty_positive",
                "chk_mall_order_amount_nonneg", "chk_mall_order_amount_sum",
                "chk_mall_order_item_qty_positive", "chk_mall_order_item_price_nonneg",
                "chk_mall_order_item_amount", "chk_mall_payment_amount_nonneg" }) {
            for (Path sql : new Path[] { INIT_SQL, domain, mig }) {
                assertTrue(read(sql).contains(check), display(sql) + " 缺 S2 CHECK 约束 " + check);
            }
        }
        Path mallTestSchema = Path.of("src/test/java/com/jbk/serve/service/mall/impl/MallDbSchema.java");
        String testSchema = read(mallTestSchema);
        for (String table : tables) {
            assertTrue(testSchema.contains(table), "MallDbSchema 缺 S2 表 " + table);
        }
        // S3 履约三轨：列逐字一致 + 一单一任务/轨迹幂等/配送范围三把唯一键在位
        Path migS3 = REPO.resolve("deploy/mysql/migrations/2026-08-09-mall-s3.sql");
        for (String table : new String[] { "ws_mall_fulfillment", "ws_mall_fulfillment_trace",
                "ws_mall_courier_scope", "ws_mall_warehouse_operator" }) {
            assertColumnsEqual(table, INIT_SQL, domain);
            if ("ws_mall_fulfillment".equals(table)) {
                // FULFILL_MODE 由 L1 迁移 ALTER 补挂：S3 迁移轨天然没有它，这不是漂移
                // （与 ws_mall_order 的 SOURCE_AFTER_SALE_ID 同一先例）。空库靠 init/领域源
                // 直接建出该列，既有库靠 L1 迁移拿到；两条路径的终态由下方 L1 段的
                // ADD COLUMN 断言钉住。
                Set<String> fulfillCols = new LinkedHashSet<>(columnsOf(read(INIT_SQL), table));
                assertTrue(fulfillCols.remove("FULFILL_MODE"),
                        "init 的 ws_mall_fulfillment 缺履约渠道列，L1 四轨已漂移");
                assertEquals(fulfillCols, columnsOf(read(migS3), table),
                        "ws_mall_fulfillment 在 init 与 S3 迁移的列集合不一致（已排除 L1 补挂列）");
            }
            else {
                assertColumnsEqual(table, INIT_SQL, migS3);
            }
            assertTrue(testSchema.contains(table), "MallDbSchema 缺 S3 表 " + table);
        }
        // 分配证据列：四轨缺一即配送归属核验在该环境失效
        for (Path sql : new Path[] { INIT_SQL, domain, migS3 }) {
            assertTrue(read(sql).contains("`SUBJECT_ID`"), display(sql) + " 缺轨迹 SUBJECT_ID 列");
        }
        assertTrue(testSchema.contains("SUBJECT_ID"), "MallDbSchema 缺轨迹 SUBJECT_ID 列");
        // S4 售后四轨：五表列一致 + 幂等/资金唯一键 + 换货来源列与唯一键
        Path migS4 = REPO.resolve("deploy/mysql/migrations/2026-08-10-mall-s4.sql");
        for (String table : new String[] { "ws_mall_after_sale", "ws_mall_after_sale_item",
                "ws_mall_after_sale_trace", "ws_mall_refund", "ws_mall_refund_fact" }) {
            assertColumnsEqual(table, INIT_SQL, domain);
            assertColumnsEqual(table, INIT_SQL, migS4);
            assertTrue(testSchema.contains(table), "MallDbSchema 缺 S4 表 " + table);
        }
        assertTrue(read(migS4).contains("ADD COLUMN `SOURCE_AFTER_SALE_ID`"),
                "S4 迁移缺换货来源列的幂等 ALTER——既有库拿不到该列，换货补发整链不可达");
        assertTrue(read(migS4).contains("ADD UNIQUE KEY `uk_mall_order_source_after_sale`"),
                "S4 迁移缺换货来源唯一键——一张售后单能补发多次");
        for (String uk : new String[] { "uk_mall_as_no", "uk_mall_as_user_request",
                "uk_mall_as_item", "uk_mall_as_trace_key", "uk_mall_refund_no",
                "uk_mall_refund_after_sale", "uk_mall_refund_transaction",
                "uk_mall_refund_fact_key", "uk_mall_order_source_after_sale" }) {
            for (Path sql : new Path[] { INIT_SQL, domain, migS4 }) {
                assertTrue(read(sql).contains(uk), display(sql) + " 缺 S4 唯一键 " + uk);
            }
            assertTrue(testSchema.contains(uk), "MallDbSchema 缺 S4 唯一键 " + uk);
        }
        for (String check : new String[] { "chk_mall_as_refund_nonneg",
                "chk_mall_as_item_qty_positive", "chk_mall_as_item_amount",
                "chk_mall_refund_amount_positive" }) {
            for (Path sql : new Path[] { INIT_SQL, domain, migS4 }) {
                assertTrue(read(sql).contains(check), display(sql) + " 缺 S4 CHECK 约束 " + check);
            }
            assertTrue(testSchema.contains(check), "MallDbSchema 缺 S4 CHECK 约束 " + check);
        }
        for (String uk : new String[] { "uk_mall_fulfill_order", "uk_mall_ftrace_key",
                "uk_mall_courier_scope", "uk_mall_wh_operator" }) {
            for (Path sql : new Path[] { INIT_SQL, domain, migS3 }) {
                assertTrue(read(sql).contains(uk), display(sql) + " 缺 S3 唯一键 " + uk);
            }
            assertTrue(testSchema.contains(uk), "MallDbSchema 缺 S3 唯一键 " + uk);
        }
        assertTrue(testSchema.contains("chk_mall_order_amount_sum"),
                "MallDbSchema 缺订单金额恒等式 CHECK——测试轨挡不住的缺陷会在主库首现");

        // L1 多渠道物流四轨：四表列一致 + 六把唯一键 + 补挂列 + 历史回填幂等
        Path migL1 = REPO.resolve("deploy/mysql/migrations/2026-08-11-mall-l1-logistics.sql");
        for (String table : new String[] { "ws_mall_shipment", "ws_mall_shipment_item",
                "ws_mall_logistics_event", "ws_mall_logistics_outbox" }) {
            assertColumnsEqual(table, INIT_SQL, domain);
            assertColumnsEqual(table, INIT_SQL, migL1);
            assertTrue(testSchema.contains(table), "MallDbSchema 缺 L1 表 " + table);
        }
        for (String uk : new String[] { "uk_mall_ship_key", "uk_mall_ship_seq",
                "uk_mall_ship_waybill", "uk_mall_ship_item",
                "uk_mall_logi_event_key", "uk_mall_logi_outbox_key" }) {
            for (Path sql : new Path[] { INIT_SQL, domain, migL1 }) {
                assertTrue(read(sql).contains(uk), display(sql) + " 缺 L1 唯一键 " + uk);
            }
            assertTrue(testSchema.contains(uk), "MallDbSchema 缺 L1 唯一键 " + uk);
        }
        assertTrue(read(migL1).contains("ADD COLUMN `FULFILL_MODE`"),
                "L1 迁移缺履约渠道列的幂等 ALTER——既有库拿不到该列，多渠道整链不可达");
        for (Path sql : new Path[] { INIT_SQL, domain }) {
            assertTrue(read(sql).contains("`FULFILL_MODE`"),
                    display(sql) + " 缺 ws_mall_fulfillment.FULFILL_MODE");
        }
        assertTrue(testSchema.contains("FULFILL_MODE"), "MallDbSchema 缺 FULFILL_MODE 列");
        // 回填必须确定性且可重复：靠幂等键 NOT EXISTS，不靠「跑一次就别再跑」
        String l1 = read(migL1);
        assertTrue(l1.contains("CONCAT('MSHIP:', f.`ORDER_NO`, ':1:1')"),
                "L1 迁移的历史回填必须用确定性幂等键派生，不得用自增或时间戳");
        assertTrue(l1.contains("WHERE NOT EXISTS"),
                "L1 迁移的历史回填缺 NOT EXISTS 守卫，重复执行会造出重复包裹");
        assertFalse(l1.contains("NOW()") || l1.contains("SYSDATE()"),
                "回填时间必须来自履约任务自己的时间列，取当前时间等于给历史包裹编造一个发生时刻");
        // 渠道中立标签：三轨的 1396 值 4 都必须是新文案，留着「待取货」会让第三方运单显示成等配送员来取
        for (Path sql : new Path[] { INIT_SQL, domain }) {
            assertTrue(read(sql).contains("'待承运方揽收'"),
                    display(sql) + " 的字典 1396 仍是自营专用文案");
        }
        assertTrue(l1.contains("'待承运方揽收'"), "L1 迁移缺 1396 标签的渠道中立化 UPDATE");
        for (String dict : new String[] { "'1404'", "'1405'", "'1406'", "'1407'", "'1408'", "'1409'" }) {
            for (Path sql : new Path[] { INIT_SQL, domain, migL1 }) {
                assertTrue(read(sql).contains(dict), display(sql) + " 缺 L1 字典 " + dict);
            }
        }
        // 地址区县码：init 建表带列、迁移带幂等 ALTER、测试轨同置（可空是刻意的，存量地址不猜测回填）
        assertTrue(read(INIT_SQL).contains("`DISTRICT_CODE`       varchar(6)   NULL"),
                "init 的 ws_user_address 缺 DISTRICT_CODE 列");
        assertTrue(read(mig).contains("ADD COLUMN `DISTRICT_CODE`"),
                "S2 迁移缺 ws_user_address 的 DISTRICT_CODE 加列语句");
        assertTrue(testSchema.contains("DISTRICT_CODE VARCHAR(6) NULL"),
                "MallDbSchema 的 ws_user_address 缺 DISTRICT_CODE 列");
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
     * 字典写入不得使用 INSERT IGNORE：字典表无唯一索引且不写 ID，IGNORE 无键可撞
     * 等同普通 INSERT，init+迁移各灌一套即翻倍，selectJoinOne 遇重复行 500。
     * 菜单与角色绑定显式写主键，IGNORE 对它们有效，不在此列。
     */
    /**
     * 破坏性 DDL 边界：迁移绝不删表；server/sql 的 ws_*.sql 以 DROP TABLE 开头，
     * 必须自带「仅限空库」横幅。只扫 .sql——verify-*.sh 的 DROP DATABASE 作用于一次性容器。
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
        // 只扫「能对非空库重复执行」的文件；init/*.sql 仅空数据卷首启执行一次，不在其列
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
     * 存量欠账登记不清零，本清单只减不增（新增文件一律走 NOT EXISTS）。
     * settlement-e2e08-a 已确认造成字典重复（R-206），清理时必须连带主库去重。
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
     * 取 CREATE TABLE 的列名集合：三份 DDL 排版不同，故不按行解析，
     * 按括号深度为 0 的逗号切分——VARCHAR(20)/KEY(A,B) 内部逗号不会切错。
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
     * 片段形如「`NAME` type ...」；索引/约束子句丢弃。字符类必须含数字：
     * 否则 RAW_BODY_SHA256 这类列在两侧同时被漏掉，比对恒一致、守卫形同虚设。
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
