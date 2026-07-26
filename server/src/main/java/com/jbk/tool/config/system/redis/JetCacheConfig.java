package com.jbk.tool.config.system.redis;

import com.alicp.jetcache.anno.CacheConsts;
import com.alicp.jetcache.anno.config.EnableMethodCache;
import com.alicp.jetcache.anno.support.GlobalCacheConfig;
import com.alicp.jetcache.anno.support.JetCacheBaseBeans;
import com.alicp.jetcache.embedded.EmbeddedCacheBuilder;
import com.alicp.jetcache.embedded.LinkedHashMapCacheBuilder;
import com.alicp.jetcache.redis.lettuce.RedisLettuceCacheBuilder;
import com.alicp.jetcache.support.*;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * @ClassName JetCacheConfig
 * @Author xs
 * @Date 2024/6/7 11:47
 * @Version 1.0
 */
@Configuration
@EnableMethodCache(basePackages = "com.xinyu")
//@EnableCreateCacheAnnotation // deprecated in jetcache 2.7-, 如果不用@CreateCache注解可以删除
@Import(JetCacheBaseBeans.class) //need since jetcache 2.7+
public class JetCacheConfig {

    public final static long HOUR_SIX_EXPIRE = 60 * 60 * 6L;

    @Value("${spring.redis.host}")
    private String host;

    @Value("${spring.redis.port}")
    private Integer port;

    @Value("${spring.redis.password}")
    private String password;

    @Value("${spring.redis.database}")
    private int database;

    @Bean
    public RedisClient redisClient() {
        String redisCon = "redis://:" + password + "@" + host + ":" + port + "/" + database;
        RedisClient client = RedisClient.create(redisCon);
        client.setOptions(ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build());
        return client;
    }

    @Bean
    public GlobalCacheConfig config(RedisClient redisClient) {
        Map localBuilders = new HashMap();
        // 本地缓存
        EmbeddedCacheBuilder localBuilder = LinkedHashMapCacheBuilder
                .createLinkedHashMapCacheBuilder()
                .keyConvertor(FastjsonKeyConvertor.INSTANCE)
                .expireAfterWrite(HOUR_SIX_EXPIRE, TimeUnit.SECONDS);
        localBuilders.put(CacheConsts.DEFAULT_AREA, localBuilder);

        Map remoteBuilders = new HashMap();
        RedisLettuceCacheBuilder remoteCacheBuilder = RedisLettuceCacheBuilder.createRedisLettuceCacheBuilder()
                .keyConvertor(Fastjson2KeyConvertor.INSTANCE)
                .valueEncoder(Kryo5ValueEncoder.INSTANCE)
                .valueDecoder(Kryo5ValueDecoder.INSTANCE)
                .expireAfterWrite(HOUR_SIX_EXPIRE, TimeUnit.SECONDS)
                .broadcastChannel("JetCache")
                .redisClient(redisClient);
        remoteBuilders.put(CacheConsts.DEFAULT_AREA, remoteCacheBuilder);

        GlobalCacheConfig globalCacheConfig = new GlobalCacheConfig();
        globalCacheConfig.setLocalCacheBuilders(localBuilders);
        globalCacheConfig.setRemoteCacheBuilders(remoteBuilders);
        globalCacheConfig.setStatIntervalMinutes(60);
        //globalCacheConfig.setAreaInCacheName(false); for jetcache <=2.6
        return globalCacheConfig;
    }
}
