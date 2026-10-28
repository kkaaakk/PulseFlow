package com.pulseflow.boot.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 创建业务链路共享的 Redisson 客户端。 */
@Configuration
public class RedisConfig {

    @Value("${spring.redis.host}")
    private String host;

    @Value("${spring.redis.port}")
    private int port;

    @Value("${spring.redis.password:}")
    private String password;

    @Value("${spring.redis.database}")
    private int database;

    /** 按 Spring Redis 配置创建 Redisson 客户端，供原子脚本和缓存操作复用。 */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        String address = "redis://" + host + ":" + port;
        config.useSingleServer()
                .setAddress(address)
                .setDatabase(database)
                .setConnectionMinimumIdleSize(4)
                .setConnectionPoolSize(16);
        if (password != null && !password.isEmpty()) {
            config.useSingleServer().setPassword(password);
        }
        return Redisson.create(config);
    }
}
