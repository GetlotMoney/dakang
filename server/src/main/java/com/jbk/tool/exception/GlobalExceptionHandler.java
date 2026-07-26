package com.jbk.tool.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotSafeException;
import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.jbk.tool.config.system.redis.consts.RedisExpire;
import com.jbk.tool.config.system.redis.consts.RedisKeys;
import com.jbk.tool.config.system.redis.utils.RedisUtils;
import com.jbk.tool.consts.ApiConst;
import com.jbk.tool.domain.R;
import com.jbk.tool.utils.IpUtils;
import com.jbk.tool.utils.satoken.StpKit;
import com.taptap.ratelimiter.exception.RateLimitException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.validation.BindException;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @Resource(name = "redisTemplate1")
    private RedisTemplate redis1;
    @Resource
    private HttpServletRequest request;

    @ExceptionHandler(value = JbkException.class)
    public R serviceExceptionHandler(JbkException e) {
        if (e.getCode() == ErrorMsg.REPEAT_SUBMIT.getCode()) {
            // 重复提交不计入信息
        } else {
            redisIpRateLimit(e);
        }
        log.error("=========》JbkException：{},{}", e.getMsg(), ExceptionUtil.stacktraceToString(e));
        return R.error(e.getMsg(),e.getCode());

    }

    @ExceptionHandler(value = {MethodArgumentNotValidException.class,
            ConstraintViolationException.class,
            BindException.class})
    public R handleValidatedException(Exception e) {
        redisIpRateLimit(e);
        String errorMsg = "参数校验未通过： ";
        if (e instanceof MethodArgumentNotValidException) {
            MethodArgumentNotValidException ex = (MethodArgumentNotValidException) e;
            errorMsg = errorMsg + ex.getBindingResult().getAllErrors().stream().map(ObjectError::getDefaultMessage).collect(Collectors.joining(", "));
        } else if (e instanceof ConstraintViolationException) {
            ConstraintViolationException ex = (ConstraintViolationException) e;
            errorMsg = errorMsg + ex.getConstraintViolations().stream().map(ConstraintViolation::getMessage).collect(Collectors.joining(", "));
        } else if (e instanceof BindException) {
            BindException ex = (BindException) e;
            errorMsg = errorMsg + ex.getAllErrors().stream().map(ObjectError::getDefaultMessage).collect(Collectors.joining(", "));
        } else if (e instanceof IllegalStateException) {
            ConstraintViolationException ex = (ConstraintViolationException) e.getCause().getCause();
            errorMsg = errorMsg + ex.getConstraintViolations().stream().map(ConstraintViolation::getMessage).collect(Collectors.joining(", "));
        }
        return R.error(errorMsg);
    }

    @ExceptionHandler(value = NotLoginException.class)
    public R notLoginExceptionHandler(NotLoginException e) {
        redisIpRateLimit(e);
        ErrorMsg err = ErrorMsg.TOKEN_AUTH_FAIL;
        if (e.getType().equals(NotLoginException.TOKEN_TIMEOUT)) {
            err = ErrorMsg.TOKEN_AUTH_DEATH;
        } else if (e.getType().equals(NotLoginException.BE_REPLACED)) {
            err = ErrorMsg.TOKEN_AUTH_REPLACE;
        } else if (e.getType().equals(NotLoginException.KICK_OUT)) {
            err = ErrorMsg.TOKEN_AUTH_KICK;
        } else if (e.getType().equals(NotLoginException.TOKEN_FREEZE)) {
            err = ErrorMsg.TOKEN_AUTH_FREEZE;
        }
        return R.error(err);
    }

    @ExceptionHandler(value = NotPermissionException.class)
    public R bindNotPermissionExceptionHandler(NotPermissionException e) {
        redisIpRateLimit(e);
        return R.error(StrUtil.format("权限不足，缺少权限：{}", e.getPermission()));
    }

    @ExceptionHandler(value = NotSafeException.class)
    public R bindNotSafeExceptionHandler(HttpServletRequest req, Exception e) {
        redisIpRateLimit(e);
        if (StpKit.KH_USER.isLogin()) {
            return R.error("这个隐藏功能已经关闭了哦~");
        }
        return R.error("二级认证校验失败");
    }

    @ExceptionHandler(value = ServletRequestBindingException.class)
    public R bindExceptionHandler(HttpServletRequest req, Exception e) {
        redisIpRateLimit(e);
        return R.error("请求参数绑定方式错误,请检查");
    }

    @ExceptionHandler(value = HttpMessageConversionException.class)
    public R httpMessageConversionExceptionHandler(HttpServletRequest req, Exception e) {
        redisIpRateLimit(e);
        return R.error("请求参数绑定类型错误");
    }

    @ExceptionHandler(value = HttpMediaTypeException.class)
    public R httpMediaTypeExceptionHandler(HttpServletRequest req, Exception e) {
        redisIpRateLimit(e);
        return R.error("请求参数类型错误,请检查");
    }

    @ExceptionHandler(value = HttpRequestMethodNotSupportedException.class)
    public R httpRequestMethodNotSupportedExceptionHandler(HttpServletRequest req, Exception e) {
        redisIpRateLimit(e);
        return R.error("请求方式错误,请检查");
    }

    @ExceptionHandler(value = Exception.class)
    public R exceptionHandler(Exception e) {
        redisIpRateLimit(e);
        return R.error();
    }

    @ExceptionHandler(value = RateLimitException.class)
    public R RateLimitExceptionHandler(RateLimitException e) {
        redisIpRateLimit(e);
        return R.error(ErrorMsg.RATE_LIMIT_ERROR);
    }


    private final int errorThreshold = 50;       // 错误阈值

    private final long timeWindowSeconds = RedisExpire.ONE_EXPIRE;  // 时间窗口：1分钟
    private final long tempDisableTime = RedisExpire.FIVE_EXPIRE; // 临时封禁时长
    private final long tempRecordTime = RedisExpire.THREE_DAY_EXPIRE; // ip封禁记录时长


    public void redisIpRateLimit(Exception e) {
        String ipAddr = IpUtils.getIpAddr(request);
        // 记录错误日志
        long userId = -1L;
        if (StpKit.KH_USER.isLogin()) {
            userId = StpKit.KH_USER.getLoginIdAsLong();
        }
        log.error("=========》USR:{}；IP:{}；URL：{}；异常：{},{}",
                userId,
                ipAddr,
                request.getRequestURI(),
                ExceptionUtil.stacktraceToString(e)
        );
        // 记录错误次数
        String errorCountKey = RedisKeys.Comm.EXCEPTION_IP_CNT + ipAddr;
        // 原子性地增加错误计数
        long errorCount = RedisUtils.incr(redis1, errorCountKey, 1);
        // 设置过期时间（如果是第一次记录）
        if (errorCount == 1) {
            RedisUtils.expire(redis1, errorCountKey, timeWindowSeconds);
        }
        // 检查是否达到封禁阈值
        if (errorCount >= errorThreshold) {
            // 若ip被封禁过
            if (RedisUtils.sHasKey(redis1, RedisKeys.Comm.EXCEPTION_IP_TEMP_ALL, ipAddr)) {
                // 永久封禁该ip
                RedisUtils.sSet(redis1, RedisKeys.Comm.EXCEPTION_IP_ALL, ipAddr);
            } else {
                // 临时封禁该ip
                RedisUtils.set(redis1, RedisKeys.Comm.EXCEPTION_IP_TEMP + ipAddr, ApiConst.SYS_PLACEHOLDER, tempDisableTime);
                // 记录被封禁的ip
                RedisUtils.sSet(redis1, RedisKeys.Comm.EXCEPTION_IP_TEMP_ALL, ipAddr);
                // 设置临时封禁ip的时间
                RedisUtils.expire(redis1, RedisKeys.Comm.EXCEPTION_IP_TEMP_ALL, tempRecordTime);
            }
        }
    }

}


