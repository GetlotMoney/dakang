package com.jbk.tool.interceptor;

/**
 *@ClassName ThreadLocalToLogOperation
 *@Author xs
 *@Date 2025/9/5 17:16
 *@Version 1.0
 */
public class ThreadLocalToLogOperation {
    private final static String splitStr = "\n==============================\n";
    private final static ThreadLocal<String> sysLogThreadLocal = new ThreadLocal<>();

    public static String get() {
        return sysLogThreadLocal.get();
    }

    public static void set(String val) {
        if (null == sysLogThreadLocal.get()) {
            sysLogThreadLocal.set(val);
        } else {
            sysLogThreadLocal.set(sysLogThreadLocal.get() + splitStr + val);
        }

    }

    public static void remove() {
        if (null != sysLogThreadLocal.get()) {
            sysLogThreadLocal.remove();
        }
    }
}
