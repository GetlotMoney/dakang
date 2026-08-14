package com.jbk.serve.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模拟入口装配合同（AGENTS.md：禁止 Demo 鉴权降级）：每个 Sim 入口必须条件装配，
 * 漏加 @ConditionalOnProperty 即生产直接可用且无任何报错。
 * 用源码扫描而非上下文——漏注解的类恰会被装配、逃过上下文检查。
 */
@DisplayName("模拟入口装配合同")
class SimulationEntryContractTest {

    private static final Path CONTROLLER_ROOT =
            Paths.get("src/main/java/com/jbk/serve/controller");
    private static final Path PROD_YML =
            Paths.get("src/main/resources/application-prod.yml");

    /** 类名含这些片段的一律视为模拟/测试入口。 */
    private static final String[] SIM_MARKERS = {"Sim", "TestLogin"};

    private static final Pattern SWITCH_NAME =
            Pattern.compile("@ConditionalOnProperty\\s*\\(\\s*name\\s*=\\s*\"([^\"]+)\"");

    private static List<Path> simControllers() throws IOException {
        try (Stream<Path> walk = Files.walk(CONTROLLER_ROOT)) {
            return walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        for (String marker : SIM_MARKERS) {
                            if (name.contains(marker)) {
                                return true;
                            }
                        }
                        return false;
                    })
                    .sorted()
                    .toList();
        }
    }

    @Test
    @DisplayName("每个模拟入口都条件装配，且开关值必须是 true 才注册")
    void everySimulationEntryIsConditionallyWired() throws IOException {
        List<Path> found = simControllers();
        // 判据失效比判据不通过更危险：扫不到任何文件时它会安静地"通过"
        assertFalse(found.isEmpty(), "未扫描到任何模拟入口控制器，判据失效");

        List<String> offenders = new ArrayList<>();
        for (Path path : found) {
            String src = Files.readString(path, StandardCharsets.UTF_8);
            if (!src.contains("@ConditionalOnProperty")) {
                offenders.add(path.getFileName() + "：缺 @ConditionalOnProperty，生产环境会直接注册");
                continue;
            }
            if (!src.contains("havingValue = \"true\"")) {
                offenders.add(path.getFileName()
                        + "：@ConditionalOnProperty 未限定 havingValue=\"true\"，"
                        + "缺省行为会让「配置项不存在」也算开启");
            }
        }
        assertTrue(offenders.isEmpty(), "模拟入口装配不合规：" + offenders);
    }

    @Test
    @DisplayName("模拟入口的开关在生产配置里必须显式为 false")
    void everySimulationSwitchDefaultsToFalseInProduction() throws IOException {
        String prod = Files.readString(PROD_YML, StandardCharsets.UTF_8);
        List<Path> found = simControllers();
        assertFalse(found.isEmpty(), "未扫描到任何模拟入口控制器，判据失效");

        List<String> checked = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        for (Path path : found) {
            String src = Files.readString(path, StandardCharsets.UTF_8);
            Matcher m = SWITCH_NAME.matcher(src);
            if (!m.find()) {
                offenders.add(path.getFileName() + "：读不出开关名");
                continue;
            }
            String key = m.group(1);
            // yml 是嵌套的，取末段键名 + 其所属段落来判断；末段恒为 enabled，
            // 故用倒数第二段（如 pay-sim / logistics-sim）定位那一小节
            String[] parts = key.split("\\.");
            String section = parts.length >= 2 ? parts[parts.length - 2] : parts[0];
            int at = prod.indexOf(section + ":");
            if (at < 0) {
                offenders.add(key + "：生产配置里找不到 " + section + " 段落");
                continue;
            }
            String tail = prod.substring(at, Math.min(prod.length(), at + 400));
            int enabledAt = tail.indexOf("enabled:");
            if (enabledAt < 0) {
                offenders.add(key + "：" + section + " 段落里没有 enabled 键");
                continue;
            }
            String line = tail.substring(enabledAt, tail.indexOf('\n', enabledAt) < 0
                    ? tail.length() : tail.indexOf('\n', enabledAt));
            // 允许两种写法：直接 false，或 ${ENV:false}（环境变量缺省 false）
            boolean safe = line.contains("false");
            if (!safe) {
                offenders.add(key + "：生产配置为 " + line.trim()
                        + "，模拟入口在生产必须默认关闭");
            }
            checked.add(key);
        }
        assertTrue(offenders.isEmpty(), "模拟开关生产默认值不合规：" + offenders);
        assertFalse(checked.isEmpty(), "一个开关都没核到，判据失效");
    }

    @Test
    @DisplayName("每个模拟入口各用各的开关：危险度不同的能力必须能分别授权")
    void simulationEntriesDoNotShareSwitches() throws IOException {
        List<Path> found = simControllers();
        assertFalse(found.isEmpty(), "未扫描到任何模拟入口控制器，判据失效");

        Map<String, List<String>> bySwitch = new LinkedHashMap<>();
        for (Path path : found) {
            Matcher m = SWITCH_NAME.matcher(Files.readString(path, StandardCharsets.UTF_8));
            if (m.find()) {
                bySwitch.computeIfAbsent(m.group(1), k -> new ArrayList<>())
                        .add(path.getFileName().toString());
            }
        }

        List<String> shared = bySwitch.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> e.getKey() + " 被共用于 " + e.getValue())
                .toList();

        // 2026-08-11 实测出的后果：测试登录曾与 Pay-Sim 共用 mini.pay-sim.enabled，
        // 于是演示环境「为跑通商城链开 Pay-Sim」把按手机号直签会话的入口一并打开，
        // 正式微信登录链旁边始终并行着一条绕过它的路，而没有任何配置项能单独关掉它。
        // 两者危险度并不同档：Pay-Sim 伪造的是带 PAY_SOURCE=2 永久标记、可追溯的收款事实；
        // 测试登录签出的会话与正式登录完全同形，事后无法分辨。
        assertTrue(shared.isEmpty(), "模拟入口共用开关，无法分别授权：" + shared);
    }
}
