package com.hmdp.utils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component//注入Service层
public class RedisIdWorker {

    /**
     * 1. 设定一个“创世时间”（基准时间）
     * 为什么不直接用1970年？因为31位秒级时间戳最多只能存69年。
     * 如果从1970年算，现在都快用光了！所以我们定一个离现在近的时间，比如2022年1月1日。
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;

    /**
     * 2. 序列号的位数
     */
    private  static  final  int COUNT_BITS = 32;

    // 注入我们最爱的 Redis 跑腿小哥
    private final StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 核心发号方法
     * @param keyPrefix 业务前缀（比如 "order"，区分不同业务的ID）
     * @return 全局唯一ID
     */
    public long nextId(String keyPrefix){
        //1 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        //相对时间戳
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        //2 生成序列号
        //获取当前日期
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //找redis要一个自增号
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        //3 拼接
        return timestamp << COUNT_BITS | count;
    }

}
