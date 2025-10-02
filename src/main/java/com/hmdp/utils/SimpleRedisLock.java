package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock
{
    private StringRedisTemplate stringRedisTemplate;

    private String lockName;

    private static final String KEY_PREFIX = "lock:";

    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT; // 用于Lua脚本加载

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        // 加载Lua脚本
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        // 设置Lua脚本返回值类型
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String lockName)
    {
        this.stringRedisTemplate = stringRedisTemplate;
        this.lockName = lockName;
    }

    @Override
    public boolean tryLock(long timeoutSec)
    {
        // 获取线程表示
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + lockName, threadId
                , timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock()
    {
        // 调用Lua脚本释放锁
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + lockName),
                ID_PREFIX + Thread.currentThread().getId());
    }

//    @Override
//    public void unlock()
//    {
//        // 获取线程标识
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        // 获取Redis中的锁，判断锁中的表示是否与线程表示相同，如果相同则可以删除锁否则不做操作
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + lockName);
//        if (threadId.equals(id))
//        {
//            // 释放锁
//            stringRedisTemplate.delete(KEY_PREFIX + lockName);
//        }
//    }
}
