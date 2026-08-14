package com.jbk.tool.config.system.redis;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import jakarta.annotation.Resource;

@Configuration
//@EnableAutoConfiguration(exclude = {RedisAutoConfiguration.class, RedisReactiveAutoConfiguration.class})
public class RedisConfig {


    @Resource(name = "redisShareConnection1")
    private LettuceConnectionFactory redisShareConnection1;


    /**
     * 公共配置缓存库
     */
    @Bean("redisShareTemplate1")
    public RedisTemplate<String, Object> redisShareTemplate1() {
        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        Jackson2JsonRedisSerializer valueRedisSerializer = new Jackson2JsonRedisSerializer(Object.class);
        //设置Redis的value为json格式,并存储对象信息的序列化类型
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        objectMapper.activateDefaultTyping(LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
        valueRedisSerializer.setObjectMapper(objectMapper);

        RedisSerializer keyRedisSerializer = new StringRedisSerializer();
        redisTemplate.setKeySerializer(keyRedisSerializer);
        redisTemplate.setValueSerializer(valueRedisSerializer);
        redisTemplate.setHashKeySerializer(keyRedisSerializer);
        redisTemplate.setHashValueSerializer(valueRedisSerializer);

        redisTemplate.setConnectionFactory(redisShareConnection1);
        redisTemplate.afterPropertiesSet();

        return redisTemplate;
    }



    @Resource(name = "redisConnection1")
    private LettuceConnectionFactory redisConnection1;

    /**
     * 公共配置缓存库
     */
    @Bean("redisTemplate1")
    @Primary
    public RedisTemplate<String, Object> redisTemplate1() {
        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        Jackson2JsonRedisSerializer valueRedisSerializer = new Jackson2JsonRedisSerializer(Object.class);
        //设置Redis的value为json格式,并存储对象信息的序列化类型
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        objectMapper.activateDefaultTyping(LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
        valueRedisSerializer.setObjectMapper(objectMapper);

        RedisSerializer keyRedisSerializer = new StringRedisSerializer();
        redisTemplate.setKeySerializer(keyRedisSerializer);
        redisTemplate.setValueSerializer(valueRedisSerializer);
        redisTemplate.setHashKeySerializer(keyRedisSerializer);
        redisTemplate.setHashValueSerializer(valueRedisSerializer);

        redisTemplate.setConnectionFactory(redisConnection1);
        redisTemplate.afterPropertiesSet();

        return redisTemplate;
    }
}
