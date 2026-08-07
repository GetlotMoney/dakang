package com.jbk.serve.consts.device;

import com.jbk.tool.consts.device.DeviceTopics;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上行主题 type 全集：代码常量 ≡ 厂家待确认清单 ≡ 内部协议文档。
 *
 * <p>这道闸补的是一次真实事故：{@code DeviceTopics} 已定义 6 个上行 type，
 * 而发给设备厂家的 {@code pending-vendor-specs.md} V-02 只列了 4 个，漏掉 {@code ack}
 * 与 {@code replay}。厂家按那份清单实现固件的后果是——缺 ack 则**每一条出水指令
 * 都会被平台判失败**（V-04 已收紧为 ackCode 必填的二元判定），缺 replay 则断网期间
 * 的数据永久丢失。这类缺口不会让任何现有测试转红，只会在真机联调时炸开。</p>
 *
 * <p>三方对齐而非两方，是因为它们各自会被独立修改：改常量的人不会想到去改对外清单，
 * 改文档的人不会去读常量。任一处新增/删除 type 都必须同步另外两处，否则本测试转红。</p>
 */
class DeviceTopicsDocParityTest {

    /** 仓库根：向上查找同时含 {@code client/} 与 {@code server/} 的祖先目录（cwd 因 runner 而异）。 */
    private static final Path REPO = locateRepoRoot();

    private static Path locateRepoRoot() {
        Path cur = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && cur != null; i++) {
            if (Files.isDirectory(cur.resolve("client")) && Files.isDirectory(cur.resolve("server"))) {
                return cur;
            }
            cur = cur.getParent();
        }
        throw new IllegalStateException("无法从 " + Path.of("").toAbsolutePath() + " 定位仓库根");
    }

    private static final Path VENDOR_DOC = REPO.resolve("docs/requirements/pending-vendor-specs.md");
    private static final Path PROTOCOL_DOC = REPO.resolve("docs/mqtt-topics.md");

    /** 上行主题形如 up/{deviceNo}/heartbeat；只取 type 段。 */
    private static final Pattern UP_TOPIC = Pattern.compile("up/\\{deviceNo}/([a-z]+)");
    /** V-02 取值表的行首反引号 type，形如 {@code | `ack` | 指令接受回执 | V-04 |}。 */
    private static final Pattern DOC_TYPE_ROW = Pattern.compile("(?m)^\\|\\s*`([a-z]+)`\\s*\\|");

    /**
     * 代码侧全集用<b>反射读常量</b>而不是扫源码字符串：常量是运行时真值，
     * 源码文本会被注释、示例、被注掉的代码污染。
     */
    private Set<String> kindsFromCode() throws IllegalAccessException {
        Set<String> kinds = new TreeSet<>();
        for (Field f : DeviceTopics.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getName().startsWith("KIND_")
                    && f.getType() == String.class) {
                kinds.add((String) f.get(null));
            }
        }
        assertFalse(kinds.isEmpty(), "DeviceTopics 未定义任何 KIND_* 常量，本测试失去意义");
        return kinds;
    }

    /** 截取 V-02 与 V-03 之间的正文——避免把别处提到的 type 误收进来。 */
    private String vendorV02Section() throws IOException {
        String all = read(VENDOR_DOC);
        int from = all.indexOf("### V-02");
        int to = all.indexOf("### V-03");
        assertTrue(from >= 0 && to > from, "pending-vendor-specs.md 的 V-02 章节结构已变，本测试需同步调整");
        return all.substring(from, to);
    }

    @Test
    void vendorSpecListsEveryUpstreamTopicType() throws Exception {
        Set<String> code = kindsFromCode();
        Set<String> doc = new TreeSet<>();
        Matcher m = DOC_TYPE_ROW.matcher(vendorV02Section());
        while (m.find()) {
            doc.add(m.group(1));
        }
        assertEquals(code, doc,
                "发给设备厂家的 V-02 上行 type 全集与 DeviceTopics 常量不一致。"
                        + "漏列会让厂家固件缺实现（缺 ack 则出水指令全判失败，缺 replay 则断网数据永久丢失）；"
                        + "多列会让厂家实现平台并不接收的主题。两处必须同增同减。");
    }

    @Test
    void protocolDocCoversEveryUpstreamTopicType() throws Exception {
        Set<String> code = kindsFromCode();
        Set<String> doc = new TreeSet<>();
        Matcher m = UP_TOPIC.matcher(read(PROTOCOL_DOC));
        while (m.find()) {
            doc.add(m.group(1));
        }
        assertEquals(code, doc,
                "docs/mqtt-topics.md 的上行主题表与 DeviceTopics 常量不一致——"
                        + "内部协议文档是接入方读的第一份材料，对不上即误导实现。");
    }

    @Test
    void downstreamTopicStaysSingle() throws Exception {
        assertEquals("down/DK-DEV-0001/cmd", DeviceTopics.cmdTopic("DK-DEV-0001"));
        // 下行只有一个主题是契约本身：新增下行主题会让已按 cmdType 分发的设备侧收不到指令。
        long downTopics = read(PROTOCOL_DOC).lines()
                .filter(l -> l.contains("`down/"))
                .count();
        assertEquals(1L, downTopics,
                "协议文档出现了多于一个下行主题；下行恒为 down/{deviceNo}/cmd，指令类型走报文体 cmdType");
    }

    private String read(Path p) throws IOException {
        assertTrue(Files.exists(p), "找不到 " + p.toAbsolutePath());
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
