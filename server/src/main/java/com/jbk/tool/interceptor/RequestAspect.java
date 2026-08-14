package com.jbk.tool.interceptor;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.jbk.tool.utils.MaskUtils;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.jbk.tool.config.system.RequestWrapper;
import com.jbk.tool.domain.R;
import com.jbk.tool.utils.IpUtils;
import com.jbk.tool.utils.satoken.StpKit;
import com.wujiuye.flow.FlowHelper;
import com.wujiuye.flow.FlowType;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 控制器请求日志与流量统计切面（入参/出参脱敏后打印）。 */
@Component
@Aspect
@Slf4j
@Order(50)
public class RequestAspect {

    private static final String REDACTED = "***";
    private static final int MAX_REQUEST_LOG_LENGTH = 4096;

    public ConcurrentHashMap<String, FlowHelper> concurrentHashMap = new ConcurrentHashMap<>();

    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    @PostConstruct
    public void init() {
        concurrentHashMap.put("total", new FlowHelper(FlowType.Hour, FlowType.Minute));
        Set<RequestMappingInfo> requestMappingInfos = requestMappingHandlerMapping.getHandlerMethods().keySet();
        for (RequestMappingInfo mappingInfo : requestMappingInfos) {
            String url = mappingInfo.getPatternsCondition().toString();
            url = url.substring(1);
            url = url.substring(0, url.length() - 1);
            if (url.contains("{")) {
                int idx = url.indexOf("{");
                url = url.substring(0, idx - 1);
            }
            concurrentHashMap.put(url,
                    new FlowHelper(FlowType.Hour));
        }
    }

    @Pointcut("execution(public * com.jbk.serve.controller..*Controller.*(..))")
    public void pointcut() {
    }

    @Around("pointcut()")
    public Object handle(ProceedingJoinPoint joinPoint) throws Throwable {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attributes.getRequest();
        String ipAddr = IpUtils.getIpAddr(request);
        String url = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (StrUtil.isNotEmpty(contextPath) && url.startsWith(contextPath)) {
            url = url.substring(contextPath.length());
        }
        String reqParam;
        if (StrUtil.startWithIgnoreCase(request.getContentType(), "application/json")) {
            reqParam = sanitizeRequestForLog(((RequestWrapper) request).getRequestBody());
        } else {
            reqParam = sanitizeRequestForLog(preHandle(joinPoint, request));
        }
        long userId = -1L;
        if (StpKit.KH_USER.isLogin()) {
            userId = StpKit.KH_USER.getLoginIdAsLong();
        }
        long begin = System.currentTimeMillis();
        Object result = null;
        FlowHelper totalFlowHelper = concurrentHashMap.get("total");
        FlowHelper flowHelper = concurrentHashMap.get(url);
        if (ObjectUtil.isNull(flowHelper)) {
            String tempUrl = url;
            while (tempUrl.lastIndexOf("/") != -1) {
                int idx = tempUrl.lastIndexOf("/");
                tempUrl = url.substring(0, idx);
                flowHelper = concurrentHashMap.get(tempUrl);
                if (ObjectUtil.isNotNull(flowHelper)) {
                    break;
                }
            }
        }
        try {
            result = joinPoint.proceed();
            String respParam = responseSummaryForLog(result);
            long rt = System.currentTimeMillis() - begin;
            log.info("用户：【{}】, 请求IP:【{}】, 耗时：【{}ms】,请求URL:【{}】,请求参数:【{}】,返回参数:【{}】", userId, ipAddr, rt, url, reqParam, respParam);
            if (ObjectUtil.isNotNull(totalFlowHelper)) {
                totalFlowHelper.incrSuccess(rt);
            }
            if (ObjectUtil.isNotNull(flowHelper)) {
                flowHelper.incrSuccess(rt);
            }
        } catch (Throwable e) {
            if (ObjectUtil.isNotNull(totalFlowHelper)) {
                totalFlowHelper.incrException();
            }
            if (ObjectUtil.isNotNull(flowHelper)) {
                flowHelper.incrException();
            }
            throw e;
        }
        return result;
    }

    /** 非 JSON 请求的入参日志串。 */
    private String preHandle(ProceedingJoinPoint joinPoint, HttpServletRequest request) {
        String reqParam = "";
        Signature signature = joinPoint.getSignature();
        MethodSignature methodSignature = (MethodSignature) signature;
        Method targetMethod = methodSignature.getMethod();
        Annotation[] annotations = targetMethod.getAnnotations();
        for (Annotation annotation : annotations) {
            if (annotation.annotationType().equals(RequestMapping.class) ||
                    annotation.annotationType().equals(GetMapping.class) ||
                    annotation.annotationType().equals(PostMapping.class) ||
                    annotation.annotationType().equals(PutMapping.class) ||
                    annotation.annotationType().equals(DeleteMapping.class)) {

                reqParam = JSON.toJSONString(request.getParameterMap());
                break;
            }
        }
        return reqParam;
    }

    /** 返回值日志摘要（data 一律省略）。 */
    static String responseSummaryForLog(Object retVal) {
        if (null == retVal) {
            return "";
        }
        if (retVal instanceof R<?> response) {
            JSONObject summary = new JSONObject(true);
            summary.put("code", response.getCode());
            summary.put("msg", response.getMsg());
            summary.put("data", "<omitted>");
            return summary.toJSONString();
        }
        return "{\"type\":\"" + retVal.getClass().getSimpleName() + "\",\"data\":\"<omitted>\"}";
    }

    /**
     * 请求日志只保留排障所需的非敏感业务键。登录凭据、会话票据、手机号、地址与原始报文
     * 无论位于哪一层都统一脱敏；无法解析的正文直接省略，禁止在异常分支回落打印原文。
     */
    static String sanitizeRequestForLog(String raw) {
        if (StrUtil.isBlank(raw)) {
            return "";
        }
        try {
            Object parsed = JSON.parse(raw);
            redactSensitiveNode(parsed);
            String sanitized = JSON.toJSONString(parsed);
            if (sanitized.length() <= MAX_REQUEST_LOG_LENGTH) {
                return sanitized;
            }
            return sanitized.substring(0, MAX_REQUEST_LOG_LENGTH) + "...<truncated>";
        } catch (RuntimeException ignored) {
            return "<unparseable request body omitted>";
        }
    }

    private static void redactSensitiveNode(Object node) {
        if (node instanceof JSONObject object) {
            for (String key : object.keySet()) {
                if (isSensitiveLogKey(key)) {
                    object.put(key, REDACTED);
                } else {
                    Object child = object.get(key);
                    // 值级兜底：键名白名单对新增字段天然滞后，凡长得像手机号的值一律脱敏
                    if (child instanceof String text) {
                        String masked = MaskUtils.maskPhoneLike(text);
                        if (!masked.equals(text)) {
                            object.put(key, masked);
                        }
                    } else {
                        redactSensitiveNode(child);
                    }
                }
            }
            return;
        }
        if (node instanceof JSONArray array) {
            for (int i = 0; i < array.size(); i++) {
                Object item = array.get(i);
                if (item instanceof String text) {
                    array.set(i, MaskUtils.maskPhoneLike(text));
                } else {
                    redactSensitiveNode(item);
                }
            }
        }
    }

    private static boolean isSensitiveLogKey(String key) {
        String normalized = key == null ? ""
                : key.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        return normalized.equals("code")
                || normalized.contains("password")
                || normalized.contains("pwd")
                || normalized.contains("token")
                || normalized.contains("sessionkey")
                || normalized.contains("openid")
                || normalized.contains("unionid")
                || normalized.contains("phone")
                || normalized.contains("mobile")
                || normalized.contains("address")
                || normalized.contains("secret")
                || normalized.contains("signature")
                || normalized.contains("rawbody")
                || normalized.contains("ticket")
                || normalized.contains("authorization");
    }
}
