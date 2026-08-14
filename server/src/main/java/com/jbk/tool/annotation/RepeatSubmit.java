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
     * 防重放窗口，单位秒。选值建议：快速点击 2、修改/删除 3、新增 5。
     * 默认 5 是为保持既有使用点行为不变，勿改。
     *
     * @return 窗口秒数（&gt; 0；非正数会在切面被 fail-closed 拒绝，见 RepeatSubmitAspect）
     */
    long expireTime() default 5;

    /** 提示消息 */
    String message() default "重复提交，请稍后尝试";

    /** 参数校验：默认开启 */
    boolean paramsCheck() default true;
}


