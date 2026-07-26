package com.jbk.tool.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @ClassName SwaggerApiExclude
 * @Author xs
 * @Date 2024/6/11 11:18
 * @Version 1.0
 */
@Target({ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface SwaggerApiExclude {
    /**
     * 忽略的属性值
     */
    String[] value() default {};
}


