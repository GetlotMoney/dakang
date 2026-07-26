package com.jbk.tool.config.system.redis;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.io.IOException;

/**
 * @ClassName RedissonConfig
 * @Author xs
 * @Date 2024/6/7 11:47
 * @Version 1.0
 */
@Configuration
public class RedissonConfig {
    @Value("${spring.redis.host}")
    private String host;

    @Value("${spring.redis.port}")
    private Integer port;

    @Value("${spring.redis.password}")
    private String password;

    @Value("${spring.redis.database}")
    private int database;

    @Bean
    @Primary
    public RedissonClient redisson() throws IOException {
        Config config = new Config();
        String address = "redis://" + host + ":" + port;
        config.useSingleServer().setAddress(address)
                .setPassword(password)
                .setDatabase(database)
                .setTimeout(10000)  // 增加超时时间
                .setConnectionPoolSize(32)
                .setConnectionMinimumIdleSize(8);
        return Redisson.create(config);
    }
}