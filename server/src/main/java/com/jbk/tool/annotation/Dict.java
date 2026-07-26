package com.jbk.tool.annotation;


import com.jbk.tool.consts.ApiEnum;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 枚举翻译
 */
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Dict {
    /**
     * 数据 Type 值
     */
    ApiEnum.DictType dictType();
}


