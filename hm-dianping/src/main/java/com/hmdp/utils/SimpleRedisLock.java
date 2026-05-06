package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    //锁的名字
    private String name;
    //注入redis(声明一个用来操作Redis的工具对象)
    private StringRedisTemplate stringRedisTemplate;
    //锁的前缀 规范redis的key
    private static final String KEY_PREFIX = "lock:";

    //新增UUID防伪前缀 static确保了统一tomcat下同一大妈发送的多次请求UUID一样
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    // ==========================================
    // 🗂️ 核心改造 1：提前把锦囊（Lua脚本）加载到大楼内存里
    // ==========================================
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        // 告诉 Spring 去 resources 目录下找 unlock.lua 这个文件
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        // 告诉 Spring 这个脚本执行完返回的是什么类型（对应 Lua 里的 return 0 或 1）
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }


    @Override
    public boolean tryLock(long timeoutSec) {
        //获取当前线程ID 生成带防伪水印的专属名字
        String threadId = ID_PREFIX + Thread.currentThread().getId();

        //尝试往 Redis 里放一个 key
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        /*
        // ==========================================
        // 🧹 升级：必须“先看后擦”
        // ==========================================
        //1 获取大妈专属防伪名字
        String threadId = ID_PREFIX + Thread.currentThread().getId();

        //2 看一眼广场黑板上面写的名字是什么
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);

        //3 核对两者
        if(threadId.equals(id)){
            //相同则删除key 释放锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }

         */

        // ==========================================
        // 🚀 核心改造 2：呼叫 Redis 判官执行 Lua 锦囊
        // ==========================================
        // 调用 execute 方法，一次性把锦囊、黑板名字（KEYS）、防伪水印（ARGV）全部发射给 Redis！
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT, // 我们提前准备好的锦囊
                Collections.singletonList(KEY_PREFIX + name), // 组装成 List 的 KEYS[1]
                ID_PREFIX + Thread.currentThread().getId()    // 传过去的 ARGV[1]
        );
    }
}

