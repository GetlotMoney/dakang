package com.jbk.tool.interceptor;

import cn.dev33.satoken.stp.StpLogic;
import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.jbk.serve.service.api.IApiLogOperationService;
import com.jbk.tool.config.system.RequestWrapper;
import com.jbk.tool.consts.ApiEnum;
import com.jbk.tool.data.api.po.ApiLogOperation;
import com.jbk.tool.utils.DateUtils;
import com.jbk.tool.utils.IpUtils;
import com.jbk.tool.utils.satoken.StpKit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

/**
 *@ClassName SysLogAspect
 *@Author xs
 *@Date 2025/9/5 16:51
 *@Version 1.0
 */
@Component
@Aspect
@Slf4j
@Order(2)
public class SysLogAspect {

    @Autowired
    private IApiLogOperationService logOperationService;

    /**
     * execution （【权限修饰符】【返回类型】【类全路径】【方法名称】(【参数列表】)）
     */
    @Pointcut("@annotation(com.jbk.tool.annotation.LogOperation)")
    public void pointcut() {

    }

    @Around("pointcut()")
    public Object handle(ProceedingJoinPoint joinPoint) throws Throwable {
        StpLogic stpLogic;
        try {
            stpLogic = StpKit.getStp();
        } catch (Exception e) {
            Object result = joinPoint.proceed();
            return result;
        }
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
        Operation operation = getApiOperation(joinPoint);
        Tag tag = getApi(joinPoint);
        // 获取信息
        String className = joinPoint.getTarget().getClass().getName();
        String methodName = joinPoint.getSignature().getName();
        String method = className + "." + methodName;
        String reqParam;
        if (StrUtil.isNotEmpty(request.getContentType()) && request.getContentType().contains("application/json")) {
            // 与 RequestAspect 同一脱敏实现：口令、令牌、手机号等一律不入操作日志表。
            // 传输层 RSA 无一次性挑战，密文等价于口令——原样落库即可被重放到登录接口（R-201）
            reqParam = RequestAspect.sanitizeRequestForLog(((RequestWrapper) request).getRequestBody());
        } else {
            reqParam = preHandle(joinPoint, request);
        }
        // 组装信息
        ApiLogOperation apiLogOperation = ApiLogOperation.builder()
                .logUserId(stpLogic.getLoginIdAsLong())
                .logUserType(StpKit.getUserType(stpLogic).getValue())
                .logUserName(String.valueOf(stpLogic.getExtra("name")))
                .logModule(tag.name())
                .logContent(operation.summary())
                .logMethod(method)
                .logExecuteTime(DateUtils.time())
                .logIp(IpUtils.getIpAddr(request))
                .logUserAgent(request.getHeader("User-Agent"))
                .logUrl(request.getRequestURI())
                .logRequestParam(reqParam)
                .build();
        long begin = System.currentTimeMillis();
        try {
            Object result = joinPoint.proceed();
            String respParam = postHandle(result);
            apiLogOperation.setLogSuccessFlag(ApiEnum.Flag.YES.value());
            apiLogOperation.setLogConsumerTime((int) (System.currentTimeMillis() - begin));
            apiLogOperation.setLogResponseParam(respParam);
            apiLogOperation.setLogMonitorInfo(ThreadLocalToLogOperation.get());
            logOperationService.saveLogOperate(apiLogOperation);
            return result;
        } catch (Exception e) {
            apiLogOperation.setLogSuccessFlag(ApiEnum.Flag.NO.value());
            apiLogOperation.setLogConsumerTime((int) (System.currentTimeMillis() - begin));
            apiLogOperation.setLogResponseParam(ExceptionUtil.stacktraceToString(e));
            apiLogOperation.setLogMonitorInfo(ThreadLocalToLogOperation.get());
            logOperationService.saveLogOperate(apiLogOperation);
            throw e;
        } finally {
            ThreadLocalToLogOperation.remove();
        }
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

    private Operation getApiOperation(JoinPoint joinPoint) {
        Signature signature = joinPoint.getSignature();
        MethodSignature methodSignature = (MethodSignature) signature;
        Method method = methodSignature.getMethod();

        if (method != null) {
            return method.getAnnotation(Operation.class);
        }
        return null;
    }

    private Tag getApi(JoinPoint joinPoint) {
        Signature signature = joinPoint.getSignature();
        MethodSignature methodSignature = (MethodSignature) signature;
        Method method = methodSignature.getMethod();
        Tag classAnnotation = AnnotationUtils.findAnnotation(method.getDeclaringClass(), Tag.class);
        if (method != null) {
            return classAnnotation;
        }
        return null;
    }

    /**
     * 返回数据：只留 code/msg，data 一律省略。
     *
     * <p>此前是整体 {@code JSON.toJSONString(retVal)}，于是建号/重置密码响应里的一次性初始口令
     * 明文被写进 {@code api_log_operation.LOG_RESPONSE_PARAM}，并可经 {@code /api/logOperation/pageData}
     * 被任何持日志查询权的员工读到——足以抢在本人首登前接管账号（R-201）。</p>
     */
    private String postHandle(Object retVal) throws JsonProcessingException {
        return RequestAspect.responseSummaryForLog(retVal);
    }
}
