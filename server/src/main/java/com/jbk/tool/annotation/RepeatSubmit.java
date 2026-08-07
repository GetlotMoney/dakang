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
     * 防重放窗口，单位<b>秒</b>，必须为正数。
     *
     * <p>选值建议：快速点击 2 秒、修改/删除 3 秒、新增 5 秒。</p>
     *
     * <p><b>默认值为何是 5 而不是 3</b>：本参数曾长期是死参数——切面把窗口硬编码成 5 秒，
     * 从不读取此处的声明值。修复时全仓 40 个使用点<b>无一</b>显式声明 expireTime，
     * 若把默认值留在 3，则"让注解生效"这一步会把每个接口的窗口从既有的 5 秒静默缩到 3 秒，
     * 其中包含配送取消退款、售后返还执行、取水核账、申诉裁决、水卡状态变更和设备指令下发。
     * 故默认值取 5 以保持既有行为逐位不变；需要更短窗口的接口显式声明即可，
     * 声明值现在是真的生效的。</p>
     *
     * @return 窗口秒数（&gt; 0；非正数会在切面被 fail-closed 拒绝，见 RepeatSubmitAspect）
     */
    long expireTime() default 5;

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


