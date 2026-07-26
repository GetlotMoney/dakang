package com.jbk.tool.annotation;

import cn.dev33.satoken.annotation.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @ClassName SaCheckOr
 * @Author xs
 * @Date 2024/7/25 10:36
 * @Version 1.0
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface MySaCheckOr {
    SaCheckLogin[] login() default {};

    SaCheckPermission[] permission() default {};

    SaCheckRole[] role() default {};

    SaCheckSafe[] safe() default {};
}


