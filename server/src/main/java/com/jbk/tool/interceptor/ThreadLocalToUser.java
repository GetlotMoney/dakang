package com.jbk.tool.interceptor;

/**
 *@ClassName ThreadLocalToUser
 *@Author xs
 *@Date 2025/11/26 14:31
 *@Version 1.0
 */
public class ThreadLocalToUser {
    private final static ThreadLocal<Long> userThreadLocal = new ThreadLocal<>();

    public static Long get() {
        return userThreadLocal.get();
    }

    public static void set(Long val) {
        userThreadLocal.set(val);
    }

    public static void remove() {
        if (null != userThreadLocal.get()) {
            userThreadLocal.remove();
        }
    }
}
