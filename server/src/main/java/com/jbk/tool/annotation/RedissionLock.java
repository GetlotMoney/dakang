package com.jbk.tool.annotation;


import com.jbk.tool.config.system.redis.consts.RedisExpire;
import com.jbk.tool.exception.ErrorMsg;

import java.lang.annotation.*;

// 加锁时间：redission自动的看门狗机制
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RedissionLock {
    // key的前缀名称 支持SPEL表达式和普通的key
    String name();

    // 支持SPEL表达式和普通的key
    String[] keys();

    // 最大等待获取锁时间
    long timeout() default RedisExpire.NOT_EXPIRE;

    // 获取锁失败抛出异常
    ErrorMsg errorMsg() default ErrorMsg.BUSY_WORK;
}



