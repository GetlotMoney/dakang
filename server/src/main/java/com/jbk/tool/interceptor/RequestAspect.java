package com.jbk.tool.interceptor;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.jbk.tool.config.system.RequestWrapper;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @ClassName RequestAspect
 * @Author xs
 * @Date 2024/6/7 15:15
 * @Version 1.0
 */
@Component
@Aspect
@Slf4j
@Order(50)
public class RequestAspect {

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

    /**
     * execution （【权限修饰符】【返回类型】【类全路径】【方法名称】(【参数列表】)）
     */
    @Pointcut("execution(public * com.jbk.serve.controller..*Controller.*(..))")
    public void pointcut() {
    }

    @Around("pointcut()")
    public Object handle(ProceedingJoinPoint joinPoint) throws Throwable {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attributes.getRequest();
        //IP地址
        String ipAddr = IpUtils.getIpAddr(request);
        String url = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (StrUtil.isNotEmpty(contextPath) && url.startsWith(contextPath)) {
            url = url.substring(contextPath.length());
        }
        String reqParam;
        if (StrUtil.isNotEmpty(request.getContentType()) && request.getContentType().equals("application/json")) {
            reqParam = ((RequestWrapper) request).getRequestBody();
        } else {
            reqParam = preHandle(joinPoint, request);
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
            String respParam = postHandle(result);
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

    /**
     * 入参数据
     *
     * @param joinPoint
     * @param request
     * @return
     */
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

    /**
     * 返回数据
     *
     * @param retVal
     * @return
     */
    private String postHandle(Object retVal) {
        if (null == retVal) {
            return "";
        }
        return JSON.toJSONString(retVal);
    }
}
