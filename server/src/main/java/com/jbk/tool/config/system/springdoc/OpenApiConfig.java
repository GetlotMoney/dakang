package com.jbk.tool.config.system.springdoc;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @ClassName OpenApiConfig
 * @Author xs
 * @Date 2025/8/21 10:32
 * @Version 1.0
 */
//@EnableKnife4j
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .openapi("3.0.1")  // 设置 OpenAPI 版本
                .info(new Info()
                        .title("API文档")
                        .version("1.0")
                        .description("使用SpringDoc生成的API文档"))
                .components(new Components())
                .externalDocs(new ExternalDocumentation());
    }


}


