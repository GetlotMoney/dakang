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
        if (e.getCode() == ErrorMsg.REPEAT_SUBMIT.getCode()
                || e.getCode() == ErrorMsg.PWD_CHANGE_REQUIRED.getCode()
                || e.getCode() == ErrorMsg.PHONE_BIND_REQUIRED.getCode()) {
            // 重复提交/首改密码门/绑号闸的拒绝不计入 IP 限流：SPA 挂载批量触发 + 共用出口 IP（同楼 WiFi/CGNAT）
            // 会把正常动线打进临时乃至永久封禁
        } else {
            redisIpRateLimit(e);
        }
        // 精确原因恒进日志；诊断类异常（JbkException.internal）原文只给排障看，不弹给用户。
        log.error("=========》JbkException：{},{}", e.getMsg(), ExceptionUtil.stacktraceToString(e));
        String clientMsg = e.isUserFacing() ? e.getMsg() : JbkException.INTERNAL_FALLBACK_MSG;
        return R.error(clientMsg, e.getCode());

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

    /**
     * 二级认证未通过。必须落 {@link ErrorMsg#SAFE_FAIL}(1440) 这个可判别的码：前端据此就地弹口令框、
     * 认证成功后重放原请求，不得吞成通用错误码。C 端无二级认证入口，如实回「功能未开放」。
     */
    @ExceptionHandler(value = NotSafeException.class)
    public R bindNotSafeExceptionHandler(HttpServletRequest req, Exception e) {
        if (StpKit.KH_USER.isLogin()) {
            // C 端属探测行为，仍计入 IP 限流
            redisIpRateLimit(e);
            return R.error("这个隐藏功能已经关闭了哦~");
        }
        // 管理端不计入 IP 限流：二级认证是懒开窗设计，先撞 1440 再弹口令重放属正常流程；
        // 口令暴力破解仍在 /api/auth/openSafe 抛 JbkException 处被正常计数
        return R.error(ErrorMsg.SAFE_FAIL);
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
        String errorCountKey = RedisKeys.Comm.EXCEPTION_IP_CNT + ipAddr;
        long errorCount = RedisUtils.incr(redis1, errorCountKey, 1);
        if (errorCount == 1) {
            RedisUtils.expire(redis1, errorCountKey, timeWindowSeconds);
        }
        if (errorCount >= errorThreshold) {
            // 曾被临时封禁过的 IP 再次达阈值 → 永久封禁；否则临时封禁并记录
            if (RedisUtils.sHasKey(redis1, RedisKeys.Comm.EXCEPTION_IP_TEMP_ALL, ipAddr)) {
                RedisUtils.sSet(redis1, RedisKeys.Comm.EXCEPTION_IP_ALL, ipAddr);
            } else {
                RedisUtils.set(redis1, RedisKeys.Comm.EXCEPTION_IP_TEMP + ipAddr, ApiConst.SYS_PLACEHOLDER, tempDisableTime);
                RedisUtils.sSet(redis1, RedisKeys.Comm.EXCEPTION_IP_TEMP_ALL, ipAddr);
                RedisUtils.expire(redis1, RedisKeys.Comm.EXCEPTION_IP_TEMP_ALL, tempRecordTime);
            }
        }
    }

}


