package com.jbk.tool.config.system;

import cn.dev33.satoken.interceptor.SaInterceptor;
import com.jbk.tool.interceptor.PwdChangeGuardInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * @ClassName WebConfig
 * @Author xs
 * @Date 2024/6/7 15:25
 * @Version 1.0
 */
@Configuration
public class MyWebMvcConfigurer implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor())
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/error/**",
                        "/static/**",
                        "/statics/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/v3/api-docs/**",
                        "/webjars/**",
                        "/doc.html",
                        "/doc.html/**",
                        "/swagger-resources/**",
                        "/springdoc/**",
                        "/knife4j-openapi3-jakarta/**",
                        "/csrf"
                );
        // 首改密码强制门：注册在 SaInterceptor 之后，仅约束携带强改标记的 MANAGE 会话（R-201）
        registry.addInterceptor(new PwdChangeGuardInterceptor())
                .addPathPatterns("/**");
    }
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // SpringDoc 的资源映射
        registry.addResourceHandler("/swagger-ui/**")
                .addResourceLocations("classpath:/META-INF/resources/webjars/springdoc-openapi-ui/")
                .resourceChain(false);

        registry.addResourceHandler("/webjars/**")
                .addResourceLocations("classpath:/META-INF/resources/webjars/");

        // 静态资源
        registry.addResourceHandler("/statics/**")
                .addResourceLocations("classpath:/statics/");
    }



}
