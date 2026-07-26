package com.jbk.tool.config.system.swagger;

import com.github.xiaoymin.knife4j.spring.annotations.EnableKnife4j;
import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;


import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger/OpenAPI 配置
 */
@Configuration
@EnableKnife4j
public class SwaggerAutoConfiguration {

    @Value("${swagger.enable:true}")
    private boolean enableSwagger;

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("分模块 API 文档")
                        .description("按功能模块分组展示的接口文档")
                        .version("1.0")
                        .contact(new Contact().name("autoAi"))
                        .license(new License().name("Apache 2.0")))
                .externalDocs(new ExternalDocumentation().description("接口说明"));
    }

    @Bean
    public GroupedOpenApi apiGroup() {
        return group("API模块", "com.jbk.serve.controller.api");
    }



    private GroupedOpenApi group(String groupName, String... packagesToScan) {
        GroupedOpenApi.Builder builder = GroupedOpenApi.builder().group(groupName);
        if (enableSwagger) {
            builder.packagesToScan(packagesToScan);
        }
        return builder.build();
    }

}
