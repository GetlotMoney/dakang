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
 * <p>S1 只交付计算内核，<b>不接线</b>。这组源级断言看住三件事：
 * 开关在两份环境配置里都显式 false；订单完成路径不引用 V2 计算器；
 * 生产种子里没有任何生效计划。任何一条被破坏，"本轮不得启用新分润规则"就成了空话。</p>
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

    /** 矩阵 15a：两环境开关都显式 false——缺省继承或漏配都不接受。 */
    @Test
    void switchExplicitlyOffInBothEnvironments() throws IOException {
        Pattern off = Pattern.compile("split-v2:\\s*\\n\\s*enabled:\\s*false");
        for (String yml : new String[] {
                "server/src/main/resources/application-dev.yml",
                "server/src/main/resources/application-prod.yml" }) {
            Matcher m = off.matcher(read(yml));
            assertTrue(m.find(), yml + " 缺少显式的 settlement.split-v2.enabled: false");
        }
    }

    /**
     * 矩阵 15b：订单完成路径不调用 V2 计算器。
     *
     * <p>三个完成事务类是既有分账的全部挂点（S1-0 现状盘点核实）。它们连 import
     * 都不许出现——「引用了但开关挡住」和「根本没引用」在演示与审计里是两种可信度。</p>
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

    /** 任务书 5.1：正式比例未确认前，任何种子文件不得插入计划行。 */
    @Test
    void noSeedPlanAnywhere() throws IOException {
        for (String sql : new String[] {
                "deploy/mysql/migrations/2026-08-06-split-v2-s1.sql",
                "deploy/mysql/init/02-ws-business.sql",
                "server/sql/ws_trade.sql" }) {
            String body = read(sql).toUpperCase();
            assertFalse(body.contains("INSERT INTO `WS_SPLIT_PLAN")
                            || body.contains("INSERT IGNORE INTO `WS_SPLIT_PLAN"),
                    sql + " 出现计划种子——会议中的比例全部是讨论示例，正式参数待甲方书面确认");
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
