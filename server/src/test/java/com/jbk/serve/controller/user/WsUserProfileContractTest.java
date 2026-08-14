package com.jbk.serve.controller.user;

import com.jbk.tool.data.user.bo.WsUserBo;
import com.jbk.tool.data.user.vo.WsCourierVo;
import com.jbk.tool.validator.group.PageGroup;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户档案只读面的三条契约：分区端点只读且逐个带权限点、列表筛选的格式约束真的会执行、
 * 配送员读侧不下发证件号。
 *
 * @author dakang
 * @since 2026-08-13
 */
@DisplayName("用户档案只读面契约")
class WsUserProfileContractTest {

    private static final Path REPO = locateRepoRoot();

    private static final String PROFILE_CONTROLLER =
            "server/src/main/java/com/jbk/serve/controller/user/WsUserProfileController.java";

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

    private static String read(String relative) throws IOException {
        return new String(Files.readAllBytes(REPO.resolve(relative)), StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ");
    }

    /**
     * 源码里一处 HTTP 映射：注解种类 + 声明的路径 + 出现位置。
     *
     * <p>连注解种类一起钉住，是因为把 {@code @PostMapping} 换成 {@code @GetMapping}
     * 同样是契约变更；位置用于把权限判据切到"每个端点各自的注解窗口"。</p>
     */
    private record MappingSite(String kind, String path, int start) {
        String label() {
            return kind + "Mapping " + path;
        }
    }

    /**
     * 覆盖全部映射注解写法。
     *
     * <p>这里曾经只认 {@code @PostMapping("字面量")} 这一种形状：
     * 实测把零权限注解的 {@code @PostMapping(value = "/balanceAdjust")} 注入本控制器后，
     * 解析出的清单仍是原 6 条，端点清单与权限计数两条断言全绿——
     * 一个余额调整写端点可以挂进这个只读面而无人发觉。故改为按注解种类匹配、参数另行解析，
     * 认不出来的写法一律变成一个显式的坏路径值把断言顶红，绝不当作"没有这个端点"。</p>
     */
    private static final java.util.regex.Pattern MAPPING_SITE = java.util.regex.Pattern.compile(
            "@(Post|Get|Put|Delete|Patch|Request)Mapping\\b\\s*(?:\\(([^)]*)\\))?");

    private static final java.util.regex.Pattern NAMED_PATH =
            java.util.regex.Pattern.compile("(?:value|path)\\s*=\\s*\\{?\\s*\"([^\"]*)\"");

    private static final java.util.regex.Pattern BARE_PATH =
            java.util.regex.Pattern.compile("^\\s*\\{?\\s*\"([^\"]*)\"");

    private static List<MappingSite> mappingSites(String src) {
        List<MappingSite> sites = new ArrayList<>();
        Matcher m = MAPPING_SITE.matcher(src);
        while (m.find()) {
            sites.add(new MappingSite(m.group(1), pathOf(m.group(2)), m.start()));
        }
        return sites;
    }

    private static String pathOf(String args) {
        if (args == null || args.isBlank()) {
            return "";
        }
        Matcher named = NAMED_PATH.matcher(args);
        if (named.find()) {
            return named.group(1);
        }
        Matcher bare = BARE_PATH.matcher(args);
        if (bare.find()) {
            return bare.group(1);
        }
        return "无法解析的映射参数[" + args.trim() + "]";
    }

    @Test
    @DisplayName("档案六个分区端点逐个挂 user:user:query，且没有任何写端点")
    void everyProfileEndpointIsReadOnlyAndPermissionGated() throws IOException {
        String src = read(PROFILE_CONTROLLER);
        List<MappingSite> sites = mappingSites(src);
        List<String> labels = sites.stream().map(MappingSite::label).toList();

        // 端点清单钉死而不是"包含即可"：新增一个端点必须先回到这里说明它为什么是只读的。
        // 用前缀黑名单反而会误伤 /auditPage 这类读端点，判据一旦开始误报就会被人加白名单绕过。
        // 类级 @RequestMapping 也一并钉住：前缀改了同样是契约变更。
        assertEquals(List.of(
                        "RequestMapping /user/profile",
                        "PostMapping /identity",
                        "PostMapping /orderPage",
                        "PostMapping /flowPage",
                        "PostMapping /relation",
                        "PostMapping /inviteePage",
                        "PostMapping /auditPage"),
                labels,
                "档案控制器的映射清单变了。这是一个只读面：余额、水量、状态、关系的变更"
                        + "必须走各自领域已有的受控写入口，不得挂进这里");

        // 权限点逐个端点核，不再用"出现次数 >= 端点数"——那个计数在端点漏解析时会自洽成立。
        // 窗口取自本端点的映射注解到下一个映射注解之间，正好是这一个方法的注解簇。
        List<MappingSite> endpoints = sites.subList(1, sites.size());
        for (int i = 0; i < endpoints.size(); i++) {
            int from = endpoints.get(i).start();
            int to = i + 1 < endpoints.size() ? endpoints.get(i + 1).start() : src.length();
            assertTrue(src.substring(from, to).contains("user:user:query"),
                    endpoints.get(i).label() + " 没挂 user:user:query。"
                            + "页面可见与能翻开某个人必须分别授权，不能靠前端把入口藏起来当防线");
        }
    }

    @Test
    @DisplayName("列表筛选的约束登记进 PageGroup，否则分页路径上一条都不会执行")
    void listFilterConstraintsAreRegisteredInPageGroup() throws NoSuchFieldException {
        // 分页入口用 @Validated(PageGroup.class)，而 PageGroup 不继承 Default：
        // 不写 groups 的约束在这条路径上从来不会跑，注解看着在、其实是死的。
        assertGroupCovered(WsUserBo.class.getDeclaredField("createTimeBegin"), Pattern.class);
        assertGroupCovered(WsUserBo.class.getDeclaredField("createTimeEnd"), Pattern.class);
        assertGroupCovered(WsUserBo.class.getDeclaredField("capability"), Size.class);
    }

    private void assertGroupCovered(Field field, Class<? extends Annotation> annotationType) {
        Annotation annotation = field.getAnnotation(annotationType);
        assertTrue(annotation != null, field.getName() + " 上的 " + annotationType.getSimpleName() + " 约束不见了");
        Class<?>[] groups = annotationType == Pattern.class
                ? ((Pattern) annotation).groups()
                : ((Size) annotation).groups();
        assertTrue(Arrays.asList(groups).contains(PageGroup.class),
                field.getName() + " 的约束没有登记 PageGroup：分页查询会完全跳过它");
    }

    @Test
    @DisplayName("注册时间必须是 14 位存储格式，带分隔符的日期会静默查空")
    void registerTimeFilterRejectsSeparatorFormattedDates() {
        assertTrue("20260813000000".matches(WsUserBo.TIME_14), "标准存储格式应被接受");
        assertFalse("2026-08-13".matches(WsUserBo.TIME_14), "带分隔符的日期必须被拒绝");
        assertFalse("202608130000".matches(WsUserBo.TIME_14), "位数不足必须被拒绝");

        // 这条断言解释了上面为什么要拒绝：比较是按字符逐位进行的，
        // '-'(0x2D) 小于所有数字，于是"截止到 2026-08-13"会把 2026 年的记录整批排除，
        // 且不报任何错——页面上看是"这个区间没有人注册"。
        assertTrue("2026-08-13".compareTo("20260812235959") < 0,
                "判据前提失效：带分隔符的上界不再小于同日实际记录，本约束的理由需要重新确认");
    }

    @Test
    @DisplayName("配送员读侧不下发身份证号")
    void courierResponseCarriesNoIdCardNumber() {
        boolean hasIdCard = Arrays.stream(WsCourierVo.class.getDeclaredFields())
                .map(Field::getName)
                .anyMatch(n -> n.toLowerCase().contains("idcard"));
        assertFalse(hasIdCard,
                "WsCourierVo 又带上了证件号：完整号码一旦出接口就已经离开服务端，展示层打星也追不回来");
    }
}
