package com.jbk.tool.interceptor;

import cn.dev33.satoken.context.SaTokenContextForThreadLocalStaff;
import cn.dev33.satoken.servlet.model.SaRequestForServlet;
import cn.dev33.satoken.servlet.model.SaResponseForServlet;
import cn.dev33.satoken.servlet.model.SaStorageForServlet;
import com.jbk.tool.annotation.RepeatSubmit;
import com.jbk.tool.exception.JbkException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 防重放切面单测。
 *
 * <p>本类只钉一件事：<b>写进 Redis 的过期时间必须来自注解声明值</b>。
 * 该参数曾长期是死参数——切面把窗口硬编码成 5 秒，注解上写什么都不生效，
 * 而读代码的人会按注解值理解行为。断言直接落在传给 Redis 的 timeout 实参上，
 * 而不是落在解析方法的返回值上：只测解析方法的话，切面即便原样传回常量，测试照样绿。</p>
 */
class RepeatSubmitAspectTest {

    /** 被测方法的宿主：注解值只能声明在真实方法上，反射取出来才是编译后的真值。 */
    @SuppressWarnings("unused")
    static class Fixture {

        @RepeatSubmit(expireTime = 2)
        public void fastClick() {
        }

        @RepeatSubmit
        public void bare() {
        }

        @RepeatSubmit(expireTime = 0)
        public void zeroWindow() {
        }

        @RepeatSubmit(expireTime = -1)
        public void negativeWindow() {
        }
    }

    private RepeatSubmitAspect aspect;
    private ValueOperations<String, Object> valueOps;
    private RedisTemplate<String, Object> redis;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() throws Exception {
        aspect = new RepeatSubmitAspect();
        redis = Mockito.mock(RedisTemplate.class);
        valueOps = Mockito.mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        // 抢锁成功：本类关心的是"用什么窗口去抢"，不是抢到与否
        when(valueOps.setIfAbsent(anyString(), any(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        Field field = RepeatSubmitAspect.class.getDeclaredField("redis1");
        field.setAccessible(true);
        field.set(aspect, redis);

        // contentType 留空 → 走 getParameterMap() 分支，避免依赖 RequestWrapper
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/dakangApi/x/y");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        // 切面开头会问 Sa-Token「当前是谁」，没有上下文会直接抛 SaTokenContextException。
        // 这里装配真实的 servlet 上下文而不是给生产代码开测试用的口子：未登录态本身
        // 就是本类要覆盖的路径（usrId=-1 参与 Redis 键），绕开它等于没测到键的组成。
        SaTokenContextForThreadLocalStaff.setModelBox(
                new SaRequestForServlet(request),
                new SaResponseForServlet(response),
                new SaStorageForServlet(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        SaTokenContextForThreadLocalStaff.clearModelBox();
    }

    private ProceedingJoinPoint jointPointOf(String methodName) throws Throwable {
        Method method = Fixture.class.getMethod(methodName);
        MethodSignature signature = Mockito.mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        ProceedingJoinPoint pjp = Mockito.mock(ProceedingJoinPoint.class);
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.proceed()).thenReturn("ok");
        return pjp;
    }

    /** 捕获真正写进 Redis 的窗口秒数。 */
    private long capturedExpireSeconds() {
        ArgumentCaptor<Long> timeout = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<TimeUnit> unit = ArgumentCaptor.forClass(TimeUnit.class);
        verify(valueOps).setIfAbsent(anyString(), any(), timeout.capture(), unit.capture());
        assertEquals(TimeUnit.SECONDS, unit.getValue(), "注解上的 expireTime 语义是秒");
        return timeout.getValue();
    }

    /**
     * 核心断言：声明 2 秒就必须以 2 秒写入。
     * 把切面改回硬编码常量，这条立刻红。
     */
    @Test
    void declaredExpireTimeReachesRedis() throws Throwable {
        aspect.around(jointPointOf("fastClick"));
        assertEquals(2L, capturedExpireSeconds());
    }

    /**
     * 未显式声明时必须仍是 5 秒。
     *
     * <p>这是行为兼容性闸而非风格偏好：全仓 40 个使用点无一声明 expireTime，
     * 默认值若退回 3，"让注解生效"就等于把每个接口的窗口静默从 5 秒缩到 3 秒，
     * 其中含配送取消退款、售后返还执行、申诉裁决与设备指令下发。</p>
     */
    @Test
    void bareAnnotationKeepsLegacyFiveSecondWindow() throws Throwable {
        aspect.around(jointPointOf("bare"));
        assertEquals(5L, capturedExpireSeconds());
    }

    /** 注解默认值本身也钉住：改了默认值而忘了改使用点，上一条才有意义。 */
    @Test
    void annotationDefaultIsFiveSeconds() throws Exception {
        Object defaultValue = RepeatSubmit.class.getMethod("expireTime").getDefaultValue();
        assertEquals(5L, defaultValue);
    }

    /**
     * 非正数窗口必须 fail-closed。
     *
     * <p>{@code RedisUtils.setIfAbsent} 在 {@code time <= 0} 时退化成无条件 set 并恒返回 true，
     * 放行即等于该接口的防重放<b>彻底失效</b>且毫无迹象。故必须抛出，且一个字节都不许写进 Redis。</p>
     */
    @Test
    void nonPositiveWindowIsRejectedAndNothingIsWritten() throws Throwable {
        for (String method : new String[] { "zeroWindow", "negativeWindow" }) {
            ProceedingJoinPoint pjp = jointPointOf(method);
            JbkException ex = assertThrows(JbkException.class, () -> aspect.around(pjp));
            assertTrue(ex.getMessage().contains("防重放窗口配置非法"), "实际=" + ex.getMessage());
            verify(pjp, never()).proceed();
        }
        verify(valueOps, never()).setIfAbsent(anyString(), any(), anyLong(), any(TimeUnit.class));
    }
}
