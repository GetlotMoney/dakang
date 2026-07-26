package com.jbk;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 *@ClassName ESportApplication
 *@Author xs
 *@Date 2025/9/4 15:21
 *@Version 1.0
 */
@SpringBootConfiguration
@EnableScheduling // 定时任务
@ComponentScan(
        basePackages = "com.jbk"
)
@MapperScan("com.jbk.serve.mapper")
@EnableAspectJAutoProxy(exposeProxy = true)
@EnableAutoConfiguration
public class JbkApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(JbkApplication.class, args);
    }
}
