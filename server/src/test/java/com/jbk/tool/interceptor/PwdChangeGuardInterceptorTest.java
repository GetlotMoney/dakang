package com.jbk.tool.interceptor;

import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 首改密码强制门（R-201）：白名单精确匹配、context-path 剥离与放行/拒绝分支。
 * StpKit.MANAGE 是静态门面无法注入，会话态经受保护方法由测试替身给定；
 * 真实标记的写入/清除时序由登录与改密的强制下线闭环保证（见拦截器类注释）。
 */
class PwdChangeGuardInterceptorTest {

    /** 测试替身：固定会话态，只验证门本身的路径判定。 */
    private static class FixedStateGuard extends PwdChangeGuardInterceptor {
        private final boolean pwdChangePending;

        FixedStateGuard(boolean pwdChangePending) {
            this.pwdChangePending = pwdChangePending;
        }

        @Override
        protected boolean isManageLoginWithPwdChange() {
            return pwdChangePending;
        }
    }

    private static MockHttpServletRequest request(String contextPath, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setContextPath(contextPath);
        return request;
    }

    @Test
    void passesEverythingWhenNoPendingChange() {
        FixedStateGuard guard = new FixedStateGuard(false);
        assertTrue(guard.preHandle(request("/dakangApi", "/dakangApi/order/order/page"),
                new MockHttpServletResponse(), new Object()));
        assertTrue(guard.preHandle(request("/dakangApi", "/dakangApi/api/employee/getPage"),
                new MockHttpServletResponse(), new Object()));
    }

    @Test
    void whitelistedEndpointsPassDuringPendingChange() {
        FixedStateGuard guard = new FixedStateGuard(true);
        for (String path : new String[] {
                "/api/auth/loginEmployee", "/api/auth/loginOut", "/api/employee/updatePassword" }) {
            assertTrue(guard.preHandle(request("/dakangApi", "/dakangApi" + path),
                    new MockHttpServletResponse(), new Object()), path + " 应放行");
        }
    }

    @Test
    void businessEndpointsRejectedDuringPendingChange() {
        FixedStateGuard guard = new FixedStateGuard(true);
        for (String path : new String[] {
                "/order/order/page", "/api/employee/getPage", "/api/employee/resetPassword",
                "/api/employee/saveData", "/device/device/page", "/api/auth/openSafe" }) {
            assertThrows(JbkException.class,
                    () -> guard.preHandle(request("/dakangApi", "/dakangApi" + path),
                            new MockHttpServletResponse(), new Object()),
                    path + " 应被拒绝");
        }
    }

    /** 前缀伪装不得放行：白名单是整路径精确匹配，不是 startsWith。 */
    @Test
    void whitelistIsExactMatchNotPrefix() {
        FixedStateGuard guard = new FixedStateGuard(true);
        for (String path : new String[] {
                "/api/employee/updatePasswordX", "/api/auth/loginEmployee/extra" }) {
            assertThrows(JbkException.class,
                    () -> guard.preHandle(request("/dakangApi", "/dakangApi" + path),
                            new MockHttpServletResponse(), new Object()),
                    path + " 应被拒绝");
        }
    }

    /**
     * 白名单必须与控制器实际映射一致——否则强改中的用户连改密和退出都够不着，账号彻底锁死。
     *
     * <p>上面几条用例里的路径都是手抄副本，控制器改了 @RequestMapping 它们照样全绿。
     * 这里直接对源码求证：三条白名单路径都必须由某个 @RequestMapping 前缀与 @PostMapping
     * 后缀拼接得到。</p>
     */
    @Test
    void whitelistMatchesRealControllerMappings() throws IOException {
        Map<String, Path> owners = Map.of(
                "/api/auth", Path.of("src/main/java/com/jbk/serve/controller/api/AuthController.java"),
                "/api/employee", Path.of("src/main/java/com/jbk/serve/controller/api/ApiEmployeeController.java"));

        Set<String> declared = new LinkedHashSet<>();
        for (Map.Entry<String, Path> owner : owners.entrySet()) {
            String src = Files.readString(owner.getValue(), StandardCharsets.UTF_8);
            assertTrue(src.contains("@RequestMapping(\"" + owner.getKey() + "\")"),
                    owner.getValue().getFileName() + " 的类级 @RequestMapping 不再是 " + owner.getKey()
                            + "，强改门白名单需同步");
            Matcher m = Pattern.compile("@PostMapping\\(\"(/[A-Za-z0-9_/-]+)\"\\)").matcher(src);
            while (m.find()) {
                declared.add(owner.getKey() + m.group(1));
            }
        }

        for (String path : PwdChangeGuardInterceptor.whitelistForTest()) {
            assertTrue(declared.contains(path),
                    "白名单路径 " + path + " 在控制器中不存在——强改用户将无法改密或退出；实际映射：" + declared);
        }
    }

    /** 无 context-path 部署形态下路径剥离同样正确。 */
    @Test
    void worksWithEmptyContextPath() {
        FixedStateGuard guard = new FixedStateGuard(true);
        assertTrue(guard.preHandle(request("", "/api/employee/updatePassword"),
                new MockHttpServletResponse(), new Object()));
        assertThrows(JbkException.class,
                () -> guard.preHandle(request("", "/order/order/page"),
                        new MockHttpServletResponse(), new Object()));
    }
}
