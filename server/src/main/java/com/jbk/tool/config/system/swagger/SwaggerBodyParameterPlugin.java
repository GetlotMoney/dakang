package com.jbk.tool.config.system.swagger;

import org.springframework.context.annotation.Configuration;

/**
 * Springdoc 迁移后，旧的 springfox 参数插件已停用。
 * 如需按 `SwaggerApiInclude` / `SwaggerApiExclude` 精细控制请求体字段，后续可再单独迁移到 Springdoc 的自定义实现。
 */
@Configuration
public class SwaggerBodyParameterPlugin {
}

