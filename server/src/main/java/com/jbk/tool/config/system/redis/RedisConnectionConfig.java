package com.jbk.tool.config.system.redis;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;

import java.time.Duration;

/**
 *@ClassName RedisConnectionConfig
 *@Author xs
 *@Date 2025/10/17 10:40
 *@Version 1.0
 */
@Configuration
public class RedisConnectionConfig {

    @Value("${spring.redis.host}")
    private String host;

    @Value("${spring.redis.port}")
    private Integer port;

    @Value("${spring.redis.password}")
    private String password;

    @Value("${spring.redis.database}")
    private int database;

    @Value("${spring.redis.lettuce.pool.max-active}")
    private int maxActive;
    @Value("${spring.redis.lettuce.pool.max-idle}")
    private int maxIdle;
    @Value("${spring.redis.lettuce.pool.min-idle}")
    private int minIdle;
    @Value("${spring.redis.lettuce.pool.max-wait}")
    private int maxWait;

    @Bean("redisShareConnection1")
    public LettuceConnectionFactory redisShareConnection1() {
        GenericObjectPoolConfig<Object> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxIdle(maxIdle);
        poolConfig.setMaxTotal(maxActive);
        poolConfig.setMinIdle(minIdle);
        poolConfig.setMaxWait(Duration.ofSeconds(maxWait));

        LettucePoolingClientConfiguration lettucePoolingClientConfiguration = LettucePoolingClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(3000))
                .poolConfig((GenericObjectPoolConfig)poolConfig)
                .build();


        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration();
        server.setHostName(host);
        server.setPassword(password);
        server.setDatabase(1); // 指定数据库！
        server.setPort(port);
        return new LettuceConnectionFactory(server, lettucePoolingClientConfiguration);
    }

    @Bean("redisConnection1")
    @Primary
    public LettuceConnectionFactory redisConnection1() {
        GenericObjectPoolConfig<Object> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxIdle(maxIdle);
        poolConfig.setMaxTotal(maxActive);
        poolConfig.setMinIdle(minIdle);
        poolConfig.setMaxWait(Duration.ofSeconds(maxWait));

        LettucePoolingClientConfiguration lettucePoolingClientConfiguration = LettucePoolingClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(3000))
                .poolConfig((GenericObjectPoolConfig)poolConfig)
                .build();


        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration();
        server.setHostName(host);
        server.setPassword(password);
        server.setDatabase(database); // 指定数据库！
        server.setPort(port);
        return new LettuceConnectionFactory(server, lettucePoolingClientConfiguration);
    }
}
