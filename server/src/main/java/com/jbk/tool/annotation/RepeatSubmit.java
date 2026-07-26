package com.jbk.tool.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 防止表单重复提交
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RepeatSubmit {
    /**
     * 超时时间：默认3秒
     * 快速点击：2秒、修改/删除3秒、新增5秒
     * @return
     */
    long expireTime() default 3;

    /**
     * 提示消息
     *
     * @return
     */
    String message() default "重复提交，请稍后尝试";

    /**
     * 参数校验：默认开启
     *
     * @return
     */
    boolean paramsCheck() default true;
}


