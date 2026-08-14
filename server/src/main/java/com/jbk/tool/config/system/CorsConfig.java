package com.jbk.tool.config.system;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class CorsConfig {
    private CorsConfiguration buildConfig() {
        CorsConfiguration corsConfiguration = new CorsConfiguration();
        // 允许任何域名使用
        corsConfiguration.addAllowedOrigin("https://www.xibuyj.com");
        corsConfiguration.addAllowedOrigin("https://xibuyj.com");
        // 允许任何头
        corsConfiguration.addAllowedHeader("*");
        // 允许任何方法（post、get等）
        corsConfiguration.addAllowedMethod("*");
        // 允许携带凭证：cookies:配置此时：将跨域配置为具体值
        corsConfiguration.setAllowCredentials(Boolean.TRUE);
        return corsConfiguration;
    }

    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();

        // 允许携带凭据
        config.setAllowCredentials(true);

        // 允许跨域请求的路径
        config.addAllowedOriginPattern("*"); // 使用 allowedOriginPatterns

        // 允许的方法
        config.addAllowedMethod("*");

        // 允许的头信息
        config.addAllowedHeader("*");

        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
