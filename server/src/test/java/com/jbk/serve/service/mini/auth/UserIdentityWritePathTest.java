package com.jbk.serve.service.mini.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WECHAT_XCX_OPENID 写入面护栏：合法写入只有 MiniAuthBindTxImpl 一处（唯一键+CAS+
 * 影响行数+fail-closed）。整片 Bo→PO 拷贝会绕开全部保护且不留痕迹，
 * 故把合法写入点数量钉死，新增一处必须先来这里说明理由。
 */
@DisplayName("账号身份写入面护栏")
class UserIdentityWritePathTest {

    private static final Path REPO = locateRepoRoot();

    /**
     * 允许写 openid 的文件白名单。
     *
     * <p>只有绑定事务这一处。它是 D-426 游客态下"openid 首登建号"与"手机号补绑"
     * 两条链的共同落库点，唯一键 + CAS + 影响行数三重保护都在这里。</p>
     */
    private static final String BIND_TX =
            "server/src/main/java/com/jbk/serve/service/mini/impl/MiniAuthBindTxImpl.java";

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

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    /** 去掉行注释与块注释，避免被"自述为什么不这么写"的说明文字误判命中。 */
    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    private static List<Path> mainJavaFiles() throws IOException {
        try (var walk = Files.walk(REPO.resolve("server/src/main/java"))) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    @Test
    @DisplayName("setWechatXcxOpenid 的调用点恰好只在绑定事务里")
    void openidSetterHasExactlyOneWriteSite() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path p : mainJavaFiles()) {
            String rel = REPO.relativize(p).toString();
            if (rel.equals(BIND_TX)) {
                continue;
            }
            // PO 自身的 setter 定义由 Lombok 生成，源码里不存在字面量，不会误伤
            if (stripComments(read(p)).contains("setWechatXcxOpenid(")) {
                offenders.add(rel);
            }
        }
        assertTrue(offenders.isEmpty(),
                "绑定事务之外出现了 openid 写入点，这些路径没有唯一键/CAS/冲突判定保护：" + offenders);

        // 反向自检：白名单文件里必须真的有那次写入，否则本测试是在断言一件空事
        assertTrue(stripComments(read(REPO.resolve(BIND_TX))).contains("setWechatXcxOpenid("),
                "绑定事务里找不到 openid 写入——判据锚点已失效，请重新核对合法写入点在哪");
    }

    @Test
    @DisplayName("后台请求体不得携带身份密钥：Bo 上出现 openid 即是可写入的诱饵")
    void backOfficeBoCarriesNoIdentityKey() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path p : mainJavaFiles()) {
            String rel = REPO.relativize(p).toString();
            if (!rel.contains("/data/") || !rel.endsWith("Bo.java")) {
                continue;
            }
            String src = stripComments(read(p));
            if (src.contains("wechatXcxOpenid") || src.contains("sessionKey")) {
                offenders.add(rel);
            }
        }
        assertTrue(offenders.isEmpty(),
                "后台 Bo 上挂着身份密钥字段：它会随 copyProperties 整片落库，且在 Swagger 里对外可见 —— " + offenders);
    }

    /**
     * 整片 Bo→WsUser 拷贝的写入面随 Bo 字段自动膨胀。判据必须精确到同一个表达式：
     * 「文件里同时含两个词」会误伤拷出方向与查询构造，误报多了就会被加进忽略名单。
     */
    private static final java.util.regex.Pattern BLANKET_COPY_INTO_USER =
            java.util.regex.Pattern.compile("copyProperties\\s*\\([^;()]*,\\s*WsUser\\.class\\s*\\)");

    @Test
    @DisplayName("copyProperties 整片写 WsUser 的路径不得复活")
    void noBlanketCopyIntoUserEntity() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path p : mainJavaFiles()) {
            if (BLANKET_COPY_INTO_USER.matcher(stripComments(read(p))).find()) {
                offenders.add(REPO.relativize(p).toString());
            }
        }
        assertTrue(offenders.isEmpty(),
                "出现了 copyProperties(..., WsUser.class) 整片写入：请改成只写目标列的窄方法。"
                        + "整片拷贝的危险不在今天写了什么，而在于 Bo 每加一个字段，写入面就自动扩大一列："
                        + offenders);

        // 反向自检：判据必须真能命中它要拦的形状，否则改成恒绿也没人发现
        assertTrue(BLANKET_COPY_INTO_USER
                        .matcher("WsUser u = BeanUtil.copyProperties(bo, WsUser.class);").find(),
                "判据认不出它要拦的写法，本测试已失效");
        assertFalse(BLANKET_COPY_INTO_USER
                        .matcher("return BeanUtil.copyProperties(wsUser, WsUserVo.class);").find(),
                "判据把安全的「拷出去」也算成写入，会天天误报");
    }

    @Test
    @DisplayName("C 端用户控制器只读：写操作一律不属于后台 CRUD")
    void userControllerStaysReadOnly() throws IOException {
        String src = stripComments(read(REPO.resolve(
                "server/src/main/java/com/jbk/serve/controller/user/WsUserController.java")));
        List<String> mappings = new ArrayList<>();
        var m = java.util.regex.Pattern
                .compile("@PostMapping\\(\"([^\"]+)\"\\)").matcher(src);
        while (m.find()) {
            mappings.add(m.group(1));
        }
        assertEquals(List.of("/page", "/detail"), mappings,
                "WsUserController 出现了 /page /detail 之外的端点。C 端账号的任何写操作"
                        + "（停用、注销、改号、改身份）都必须走各自的窄接口并留审计，不得挂进这个只读控制器");
        assertFalse(src.contains("updateData"),
                "updateData 被复活了：它的整片 copyProperties 正是 2026-08-12 删除的那条身份改写通路");
    }
}
