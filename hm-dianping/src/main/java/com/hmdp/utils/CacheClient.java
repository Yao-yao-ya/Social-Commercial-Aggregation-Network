package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 将任意Java对象序列化为json并存入Redis
     */
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }
    /**
     * 1.从 redis查询缓存
     * 2.判断是否存在
     * 3.判断是否命中了我们之前放进去的“空纸条”
     * 4.缓存没有 查数据库
     * 5.数据库没有
     * 6。数据库有，写入redis
     *
     */
    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {

        String key = keyPrefix + id;

        String json = stringRedisTemplate.opsForValue().get(key);

        if(StrUtil.isNotBlank(json)) {
            //把一段JSON数据，按照指定类型，变成一个Java对象
            return JSONUtil.toBean(json, type);
        }

        if (json != null){
            //防止缓存穿透
            return null;
        }
        //请救兵
        R r = dbFallback.apply(id);
        //如果数据库没有
        if (r == null) {
        stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
        return null;
        }

        this.set(key, r, time, unit);

        return r;

    }
}
