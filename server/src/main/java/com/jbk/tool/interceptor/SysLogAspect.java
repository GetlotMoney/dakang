package com.jbk.tool.interceptor;

import cn.dev33.satoken.stp.StpLogic;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.jbk.serve.service.api.IApiLogOperationService;
import com.jbk.tool.config.system.RequestWrapper;
import com.jbk.tool.consts.ApiEnum;
import com.jbk.tool.data.api.po.ApiLogOperation;
import com.jbk.tool.domain.R;
import com.jbk.tool.exception.ErrorMsg;
import com.jbk.tool.exception.JbkException;
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

@Component
@Aspect
@Slf4j
@Order(2)
public class SysLogAspect {

    @Autowired
    private IApiLogOperationService logOperationService;

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
            // 表单/查询串入参走同一份脱敏：此前这一路原样落库，
            // 于是 JSON 路径挡住的口令与手机号，换个 Content-Type 就能进审计表
            reqParam = RequestAspect.sanitizeRequestForLog(preHandle(joinPoint, request));
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
            apiLogOperation.setLogSuccessFlag(businessSucceeded(result)
                    ? ApiEnum.Flag.YES.value() : ApiEnum.Flag.NO.value());
            apiLogOperation.setLogConsumerTime((int) (System.currentTimeMillis() - begin));
            apiLogOperation.setLogResponseParam(respParam);
            apiLogOperation.setLogMonitorInfo(ThreadLocalToLogOperation.get());
            logOperationService.saveLogOperate(apiLogOperation);
            return result;
        } catch (Exception e) {
            apiLogOperation.setLogSuccessFlag(ApiEnum.Flag.NO.value());
            apiLogOperation.setLogConsumerTime((int) (System.currentTimeMillis() - begin));
            apiLogOperation.setLogResponseParam(postHandle(clientFacingFailure(e)));
            apiLogOperation.setLogMonitorInfo(ThreadLocalToLogOperation.get());
            logOperationService.saveLogOperate(apiLogOperation);
            throw e;
        } finally {
            ThreadLocalToLogOperation.remove();
        }
    }

    /**
     * 入参数据
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
     * 返回数据：只留 code/msg，data 一律省略——响应体可能带一次性初始口令等敏感值，整体序列化会把它写进操作日志（R-201）。
     */
    private String postHandle(Object retVal) throws JsonProcessingException {
        return RequestAspect.responseSummaryForLog(retVal);
    }

    /**
     * 业务拒绝不是成功。
     *
     * <p>此前只要方法正常返回就记 {@code LOG_SUCCESS_FLAG=是}，于是「模拟退款未启用」
     * 「物流事件验签失败，已拒绝」这类 {@code R.error(...)} 拒绝在审计里与真正执行成功
     * 长得一模一样，事后无法区分谁真的动了钱。抛异常那一路仍由 catch 分支记失败。</p>
     */
    private boolean businessSucceeded(Object retVal) {
        if (retVal instanceof R<?> response) {
            return response.getCode() == ErrorMsg.SUCCESS.getCode();
        }
        return true;
    }

    /**
     * 失败分支的响应摘要，与成功分支同源（{@link #postHandle}）。
     *
     * <p>此前落的是 {@code ExceptionUtil.stacktraceToString(e)}：完整 Java 堆栈进
     * {@code api_log_operation.LOG_RESPONSE_PARAM}，包名、类名、SQL 片段与
     * 「缺少权限【xxx】」的内部权限码一并可被任何持日志查询权的员工读到。</p>
     *
     * <p>口径与 {@code GlobalExceptionHandler} 回给调用方的一致：审计里看到的结论
     * 就是操作者当时看到的结论；诊断类异常（{@link JbkException#internal}）同样收敛成
     * 通用文案。精确原因仍由 handler 的 {@code log.error} 落应用日志，排障不受影响。</p>
     */
    private R<?> clientFacingFailure(Exception e) {
        if (e instanceof JbkException je) {
            return R.error(je.isUserFacing() ? je.getMsg() : JbkException.INTERNAL_FALLBACK_MSG,
                    je.getCode());
        }
        return R.error();
    }
}
