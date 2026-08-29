package com.jbk.serve.service.settlement.split;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分润 V2 的运行边界护栏（E2E-08 S1 测试矩阵 15 + 任务书第八节）。
 *
 * <p>S2（D-428）起 V2 已接线，但接线的形态本身就是护栏：分流只存在于
 * SplitServiceImpl 单入口，三个完成事务类仍不得引用计算器；开关必须两环境
 * 显式同值；种子文件永远不得插计划（比例只能经管理端整版发布）；公式仍唯一。</p>
 */
class SplitV2GuardrailTest {

    private static final Path REPO = locateRepoRoot();

    private static Path locateRepoRoot() {
        Path cur = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && cur != null; i++) {
            if (Files.isDirectory(cur.resolve("client")) && Files.isDirectory(cur.resolve("server"))) {
                return cur;
            }
            cur = cur.getParent();
        }
        throw new IllegalStateException("无法定位仓库根");
    }

    private String read(String rel) throws IOException {
        Path p = REPO.resolve(rel);
        assertTrue(Files.exists(p), "找不到 " + p);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    /**
     * 矩阵 15a（D-428 改判据）：开关必须在两份环境配置里<b>显式声明且同值</b>——
     * 缺省继承、漏配、或 dev/prod 分叉（同一份代码两处分账口径不同且无告警）都不接受。
     */
    @Test
    void switchExplicitlyDeclaredAndConsistentAcrossEnvironments() throws IOException {
        Pattern declared = Pattern.compile("split-v2:\\s*\\n\\s*enabled:\\s*(true|false)");
        String devValue = null;
        String prodValue = null;
        for (String yml : new String[] {
                "server/src/main/resources/application-dev.yml",
                "server/src/main/resources/application-prod.yml" }) {
            Matcher m = declared.matcher(read(yml));
            assertTrue(m.find(), yml + " 缺少显式的 settlement.split-v2.enabled 声明");
            if (yml.contains("dev")) {
                devValue = m.group(1);
            }
            else {
                prodValue = m.group(1);
            }
        }
        assertTrue(devValue != null && devValue.equals(prodValue),
                "settlement.split-v2.enabled 在 dev/prod 分叉：dev=" + devValue + " prod=" + prodValue);
    }

    /**
     * 矩阵 15b（S2 后语义收紧为单入口原则）：三个完成事务类永远不得直接引用 V2
     * 计算器/组件——V2 分流只存在于 SplitServiceImpl 一处，挂点类对 V1/V2 无感知。
     * 挂点类里出现第二个入口，开关与回落语义就会散成多处、各自漂移。</p>
     */
    @Test
    void orderCompletionPathsDoNotTouchV2Calculator() throws IOException {
        for (String path : new String[] {
                "server/src/main/java/com/jbk/serve/service/delivery/impl/DeliveryTaskTxServiceImpl.java",
                "server/src/main/java/com/jbk/serve/service/trade/impl/TradeOrderTxServiceImpl.java",
                "server/src/main/java/com/jbk/serve/service/aftersale/impl/WaterAbnormalReconcileTxServiceImpl.java" }) {
            String src = read(path);
            assertFalse(src.contains("SplitPlanCalculator") || src.contains("WsSplitComponent"),
                    path + " 引用了 V2 计算器/组件——S1 不接线，接线是 S2 经甲方参数确认后的事");
        }
    }

    /**
     * D-406：区域服务商领地登记表<b>不得</b>成为分润归属的来源。
     *
     * <p>归属按推荐血缘冻结、不按地缘重算，{@code SplitCalcInput.regionChain} 直接收用户 ID，
     * 计算器根本不接收行政区字段。{@code ws_region_agent} / {@code RegionAgentResolver}
     * 只是 D-407 人工分配用的领地台账。</p>
     *
     * <p><b>这条护栏拦的是一个真实发生过的错误</b>：2026-08-12 建该表时，作者看到
     * {@code RegionLevel.PROVINCE/CITY/COUNTY} 就默认它按行政区划匹配，把解析器写成
     * "给定水站区划码 → 谁是服务商 → 分润入账走他的收益账户"，直到复查 decisions 才发现
     * 那正是 D-406 排除的路径。错误本身不会报错、不会有测试变红——它只会在某天让
     * 一笔钱付给"这个区现在的服务商"，而不是当初把这个机主招进来的人。</p>
     */
    @Test
    void regionAgentResolverStaysOutOfSplitCalculation() throws IOException {
        for (String path : new String[] {
                "server/src/main/java/com/jbk/serve/service/settlement/split/SplitPlanCalculator.java",
                "server/src/main/java/com/jbk/serve/service/settlement/split/SplitCalcInput.java",
                "server/src/main/java/com/jbk/serve/service/settlement/split/SplitPlanSnapshot.java" }) {
            String src = read(path);
            assertFalse(src.contains("RegionAgentResolver") || src.contains("WsRegionAgent"),
                    path + " 引用了区域领地登记——D-406 明令归属不按地缘重算，"
                            + "计算器不得拿到任何行政区来源的服务商");
        }
        // 反向自检：护栏要有意义，被查的文件必须真的是计算链本身。
        // 若哪天 SplitCalcInput 改名而这里没同步，上面三条 assertFalse 会对着不存在的
        // 内容恒真——read() 已断言文件存在，这里再钉住"它确实是那条链"。
        assertTrue(read("server/src/main/java/com/jbk/serve/service/settlement/split/SplitCalcInput.java")
                        .contains("regionChain"),
                "SplitCalcInput 不再含 regionChain，本护栏的前提已变，需重新评审 D-406 落码方式");
    }

    /** D-428：正式比例已确认，初始化、领域 SQL 与在库迁移必须同为一套整版计划。 */
    @Test
    void d428SeedPlanMatchesAcrossAllTracks() throws IOException {
        String oldMigration = read("deploy/mysql/migrations/2026-08-06-split-v2-s1.sql").toUpperCase();
        assertFalse(oldMigration.contains("INSERT INTO `WS_SPLIT_PLAN")
                        || oldMigration.contains("INSERT IGNORE INTO `WS_SPLIT_PLAN"),
                "S1 历史迁移只建模型，不得被回写正式计划；现有库走新的 D-428 幂等迁移");
        for (String sql : new String[] {
                "deploy/mysql/init/02-ws-business.sql",
                "server/sql/ws_trade.sql",
                "deploy/mysql/migrations/2026-08-28-demo-full-mode-repair.sql" }) {
            String body = read(sql);
            assertTrue(body.contains("'DEMO-D428-V1'"), sql + " 缺少 D-428 整版计划版本锚");
            assertTrue(body.contains("'WATER_OWNER' ROLE_CODE,'NONE' REGION_LEVEL,5000 RATE_BP"),
                    sql + " 水站比例不是50%");
            assertTrue(body.contains("'WATER_DIRECT_REFERRER','NONE',500,'FIXED'"),
                    sql + " 商务推广比例不是5%");
            assertTrue(body.contains("'REGION_PROVINCE','PROVINCE',500,'REGIONAL_CUMULATIVE'"),
                    sql + " 省级累计比例不是5%");
            assertTrue(body.contains("'REGION_CITY','CITY',300,'REGIONAL_CUMULATIVE'"),
                    sql + " 市级累计比例不是3%");
            assertTrue(body.contains("'REGION_COUNTY','COUNTY',200,'REGIONAL_CUMULATIVE'"),
                    sql + " 区县累计比例不是2%");
            assertTrue(body.contains("'DELIVERY_FEE','DELIVERY_COURIER','NONE',9000,'FIXED'"),
                    sql + " 配送员比例不是90%");
        }
    }

    /**
     * 回溯边界源级钉（D-428）：三个完成挂点调用分账 enqueue 时必须传
     * {@code order.getCreateTime()}。这里是全仓最容易一字改错且没有任何测试会变红的
     * 地方——改传 now() 后，一次迟到的核账确认就会按新比例重算旧单。
     */
    @Test
    void enqueueHooksPinOrderCreateTimeAsVersionAnchor() throws IOException {
        for (String path : new String[] {
                "server/src/main/java/com/jbk/serve/service/delivery/impl/DeliveryTaskTxServiceImpl.java",
                "server/src/main/java/com/jbk/serve/service/trade/impl/TradeOrderTxServiceImpl.java",
                "server/src/main/java/com/jbk/serve/service/aftersale/impl/WaterAbnormalReconcileTxServiceImpl.java" }) {
            String src = read(path);
            Matcher call = Pattern.compile(
                    "splitService\\.enqueueFor\\w*\\(([^;]*?)\\);", Pattern.DOTALL).matcher(src);
            boolean found = false;
            while (call.find()) {
                found = true;
                assertTrue(call.group(1).contains("order.getCreateTime()"),
                        path + " 的分账挂点没有传 order.getCreateTime() 作为版本锚：" + call.group(1));
            }
            assertTrue(found, path + " 找不到分账挂点调用，本护栏的前提已变");
        }
    }

    /** 公式唯一：级差实现只存在于计算器一处，Service/Controller 不得复制第二份。 */
    @Test
    void ladderFormulaExistsOnlyInCalculator() throws IOException {
        Path main = REPO.resolve("server/src/main/java");
        Pattern ladder = Pattern.compile("Cum\\s*-\\s*\\w*Cum");
        try (var paths = Files.walk(main)) {
            for (Path p : paths.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (p.getFileName().toString().equals("SplitPlanCalculator.java")) {
                    continue;
                }
                String src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                assertFalse(ladder.matcher(src).find(),
                        p + " 疑似复制了级差公式——公式只允许存在于 SplitPlanCalculator");
            }
        }
    }
}
