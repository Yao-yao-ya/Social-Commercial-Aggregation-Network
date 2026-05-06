package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient() {
        // 1. 创建安保指挥台的配置对象
        Config config = new Config();

        // 2. 告诉指挥台：我们用的是单机版的 Redis 广场（不是集群）
        // 记得加上 redis:// 前缀
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:6379");
        // 如果你的 Redis 有密码，解开下面这行的注释并填入密码
        // .setPassword("123456");

        // 3. 把指挥台（RedissonClient）交给 Spring 大管家管理
        return Redisson.create(config);
    }
}