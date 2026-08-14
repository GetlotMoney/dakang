package com.jbk.serve.service.mini.notify;

import com.jbk.tool.consts.mini.WechatNotifyEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 每个通知事件都必须有生产触发点：漏挂无行为可测，只能靠「枚举 ↔ 调用点」静态比对。
 * 匹配前必须剥注释，否则注释里的事件名会让门禁恒真。
 */
class WechatNotifyEventCoverageTest {

    private static final Path REPO = repoRoot();

    /** 只有这里算触发点：登记入队的两个方法。 */
    private static final List<String> ENQUEUE_CALLS =
            List.of("notifyEnqueue.enqueue(", "notifyEnqueue.enqueueIndependent(");

    @Test
    @DisplayName("九类订阅事件每一类都有生产触发点，没有只存在于枚举里的事件")
    void everyEventTypeHasAProductionTrigger() throws IOException {
        String main = allMainSourceStripped();
        List<String> orphans = new ArrayList<>();
        for (WechatNotifyEnum.EventType type : WechatNotifyEnum.EventType.values()) {
            if (!main.contains("EventType." + type.name())) {
                orphans.add(type.name());
            }
        }
        assertTrue(orphans.isEmpty(),
                "这些事件只存在于枚举里，没有任何地方会触发它们——用户在等一条永远不会来的通知："
                        + orphans);
    }

    /** 自检：拿绝不可能存在的事件名去查，命中即说明匹配方式恒真。 */
    @Test
    @DisplayName("自检：不存在的事件名必须查不到")
    void coverageJudgementIsNotVacuous() throws IOException {
        String main = allMainSourceStripped();
        assertFalse(main.isBlank(), "主源码读成了空串，上面那条断言会恒假地通过整套枚举");
        assertFalse(main.contains("EventType.NO_SUCH_EVENT_FOR_SELF_CHECK"),
                "连不存在的事件名都能命中，说明匹配方式有问题");
    }

    @Test
    @DisplayName("登记入口只有 WechatNotifyEnqueue 一处，没有绕开它直插 outbox 表的路径")
    void outboxIsWrittenOnlyThroughTheEnqueueService() throws IOException {
        List<String> offenders = new ArrayList<>();
        Set<String> allowed = Set.of(
                // 定义处：接口文件必然含有自己的类型名
                "server/src/main/java/com/jbk/serve/mapper/mini/WsWechatNotifyOutboxMapper.java",
                "server/src/main/java/com/jbk/serve/service/mini/notify/WechatNotifyEnqueue.java",
                "server/src/main/java/com/jbk/serve/service/mini/notify/WechatNotifyWorker.java");
        for (Path p : mainJavaFiles()) {
            String rel = REPO.relativize(p).toString();
            if (allowed.contains(rel)) {
                continue;
            }
            // 绕开登记服务直插表会绕开幂等键与"业务事务内只写表"两条约束。
            // 按类型名判而非字段名：仓里别的 outbox 字段也叫 outboxMapper，按字段名会误伤
            if (stripComments(read(p)).contains("WsWechatNotifyOutboxMapper")) {
                offenders.add(rel);
            }
        }
        assertTrue(offenders.isEmpty(), "这些文件绕开 WechatNotifyEnqueue 直接写通知表：" + offenders);
    }

    private static String allMainSourceStripped() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Path p : mainJavaFiles()) {
            String body = stripComments(read(p));
            // 只把带登记调用的文件计入：否则枚举定义文件自己就让每个事件"有触发点"
            if (ENQUEUE_CALLS.stream().anyMatch(body::contains)) {
                sb.append(body).append('\n');
            }
        }
        return sb.toString();
    }

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    private static List<Path> mainJavaFiles() throws IOException {
        try (var walk = Files.walk(REPO.resolve("server/src/main/java"))) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static Path repoRoot() {
        Path cur = Path.of("").toAbsolutePath();
        while (cur != null && !Files.exists(cur.resolve("server/src/main/java"))) {
            cur = cur.getParent();
        }
        if (cur == null) {
            throw new IllegalStateException("未找到仓库根");
        }
        return cur;
    }
}
