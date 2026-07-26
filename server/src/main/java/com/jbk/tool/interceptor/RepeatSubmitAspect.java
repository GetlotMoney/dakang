package com.jbk.tool.interceptor;

import cn.hutool.core.util.HashUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.jbk.tool.annotation.RepeatSubmit;
import com.jbk.tool.config.system.RequestWrapper;
import com.jbk.tool.config.system.redis.consts.RedisExpire;
import com.jbk.tool.config.system.redis.consts.RedisKeys;
import com.jbk.tool.config.system.redis.utils.RedisUtils;
import com.jbk.tool.consts.ApiConst;
import com.jbk.tool.exception.ErrorMsg;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.satoken.StpKit;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;

import java.lang.reflect.Method;

/**
 * @ClassName RepeatSubmitAspect
 * @Author xs
 * @Date 2024/6/7 14:11
 * @Version 1.0
 */
@Component
@Aspect
@Slf4j
@Order(5) // 优先级（数字值越小优先级越高）
public class RepeatSubmitAspect {
    @Resource(name = "redisTemplate1")
    private RedisTemplate<String, Object> redis1;

    @Pointcut("@annotation(com.jbk.tool.annotation.RepeatSubmit)")
    public void pointcut() {

    }

    @Around("pointcut()")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attributes.getRequest();
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        RepeatSubmit annotation = method.getAnnotation(RepeatSubmit.class);
        String url = request.getRequestURI();
        Long usrId = -1L;
        if (StpKit.KH_USER.isLogin()) {
            usrId = StpKit.KH_USER.getLoginIdAsLong();
        }
        int hash = ApiConst.Number.ZERO;
        if (annotation.paramsCheck()) {
            String reqParam;
            if (StrUtil.isNotEmpty(request.getContentType()) && request.getContentType().equals("application/json")) {
                reqParam = ((RequestWrapper) request).getRequestBody();
            } else {
                reqParam = JSON.toJSONString(request.getParameterMap());
            }
            if (StrUtil.isNotBlank(reqParam)) {
                hash = HashUtil.bkdrHash(reqParam);
            }
        }
        String redisKey = RedisKeys.Comm.getRepeatSubmit(url, usrId, hash);
        boolean flag = RedisUtils.setIfAbsent(redis1, redisKey, ApiConst.SYS_PLACEHOLDER, RedisExpire.SEC_FIVE_EXPIRE);
        if (!flag) {
            log.error("重复提交 url:{}", url);
            String message = annotation.message();
            throw new JbkException(message, ErrorMsg.REPEAT_SUBMIT.getCode());
        }
        try {
            return joinPoint.proceed();
        } catch (Throwable e) {
            redis1.delete(redisKey);
            throw e;
        }
    }
}


