package com.jbk.tool.interceptor;

import com.jbk.tool.exception.ErrorMsg;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.satoken.StpKit;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * 首改密码强制门（R-201）：待强改标记的后台会话除登录/退出/改密三端点外一律拒绝——强制不能只靠前端跳转，否则直接调接口即可绕过。
 * 标记随登录写入 MANAGE 会话 JWT extra（{@link StpKit#EXTRA_PWD_CHANGE}），只读会话内标记不回查库（改密/重置都强制下线，重登自然刷新）。
 * 只约束 MANAGE 会话；未登录请求由各端点 {@code @SaCheckLogin} 负责。
 */
public class PwdChangeGuardInterceptor implements HandlerInterceptor {

    /** 强改期间仍可用的端点：重新登录、退出、改密（改密自证原密码，且服务端锚定会话本人）。 */
    private static final Set<String> WHITELIST = Set.of(
            "/api/auth/loginEmployee",
            "/api/auth/loginOut",
            "/api/employee/updatePassword"
    );

    /** 供测试对照控制器实际映射核验，避免白名单与路由静默失配。 */
    static Set<String> whitelistForTest() {
        return WHITELIST;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!isManageLoginWithPwdChange()) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (WHITELIST.contains(path)) {
            return true;
        }
        // 专用错误码：本拒绝是正常业务分支而非异常，不参与 IP 异常封禁计数（见 GlobalExceptionHandler），
        // 前端据此码直接跳改密页
        throw new JbkException(ErrorMsg.PWD_CHANGE_REQUIRED);
    }

    /**
     * 会话是否为「已登录后台 + 待强改」。抽为受保护方法以便测试替身覆盖：
     * StpKit.MANAGE 是静态门面，测试无法注入。
     */
    protected boolean isManageLoginWithPwdChange() {
        if (!StpKit.MANAGE.isLogin()) {
            return false;
        }
        // JWT extra 反序列化类型不做假设（Boolean/字符串均可能），按字面 true 判定
        Object extra = StpKit.MANAGE.getExtra(StpKit.EXTRA_PWD_CHANGE);
        return extra != null && "true".equalsIgnoreCase(String.valueOf(extra));
    }
}
