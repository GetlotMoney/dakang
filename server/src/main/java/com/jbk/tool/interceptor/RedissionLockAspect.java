package com.jbk.tool.interceptor;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.jbk.tool.annotation.RedissionLock;
import com.jbk.tool.config.system.redis.consts.RedisExpire;
import com.jbk.tool.exception.ErrorMsg;
import com.jbk.tool.exception.JbkException;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.concurrent.TimeUnit;

/**
 * @ClassName RedissionLockAspect
 * @Author xs
 * @Date 2024/6/7 15:16
 * @Version 1.0
 */
@Component
@Order(10)
@Aspect
@Slf4j
public class RedissionLockAspect {
    @Autowired
    private RedissonClient redissonClient;
    private static final ExpressionParser PARSER = new SpelExpressionParser();

    @Pointcut("@annotation(com.jbk.spcard.tool.annotation.RedissionLock)")
    public void pointCut() {
    }

    @Around("pointCut()")
    public Object around(ProceedingJoinPoint point) throws Throwable {
        MethodSignature signature = (MethodSignature) point.getSignature();
        Method method = signature.getMethod();
        RedissionLock redissionLock = method.getAnnotation(RedissionLock.class);
        String name = redissionLock.name();
        String[] keys = redissionLock.keys();
        long timeout = redissionLock.timeout();
        ErrorMsg errorMsg = redissionLock.errorMsg();
        RLock lock = null;
        try {
            // 获取 key
            String redisKey = getKey(name, keys, method, point.getArgs());
            lock = redissonClient.getLock(redisKey);
            // 加锁
            if (timeout == RedisExpire.NOT_EXPIRE) {
                lock.lock();
            } else {
                boolean tryLock = lock.tryLock(timeout, TimeUnit.SECONDS);
                if (!tryLock) {
                    throw new JbkException(errorMsg);
                }
            }
            return point.proceed();
        } finally {
            // 释放锁
            if (ObjectUtil.isNotNull(lock) && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private String getKey(String name, String[] keys, Method method, Object[] args) {
        if (StrUtil.isBlank(name)) {
            name = method.toString();
        }
        if (ObjectUtil.isEmpty(keys)) {
            return name;
        }
        StringBuilder sb = new StringBuilder();
        if (name.startsWith("#") || name.startsWith("T")) {
            sb.append(spelKey(name, method, args)).append(":");
        } else {
            sb.append(name).append(":");
        }
        for (String key : keys) {
            if (StrUtil.isBlank(key)) {
                continue;
            }
            if (key.startsWith("#") || key.startsWith("T")) {
                sb.append(spelKey(key, method, args)).append("-");
            } else {
                sb.append(key).append("-");
            }
        }
        return sb.substring(0, sb.length() - 1);
    }

    private String spelKey(String key, Method method, Object[] args) {
        Parameter[] parameters = method.getParameters();
        StandardEvaluationContext context = new StandardEvaluationContext();
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            context.setVariable("p" + i, arg);
            context.setVariable("a" + i, arg);
            context.setVariable("arg" + i, arg);
            if (parameters != null && i < parameters.length) {
                context.setVariable(parameters[i].getName(), arg);
            }
        }
        Object value = PARSER.parseExpression(key).getValue(context);
        return ObjectUtil.isNotEmpty(value) ? value.toString() : key;
    }
}
