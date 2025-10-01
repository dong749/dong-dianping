package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class CacheClient
{
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public void setWithExpireTime(String key, Object value, Long expire, TimeUnit timeUnit)
    {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), expire, timeUnit);
    }

    public void setWithLogicalExpireTime(String key, Object value, Long expire, TimeUnit timeUnit)
    {
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(expire)));
        redisData.setData(value);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id
            , Class<R> type, Function<ID, R> dbFallBack, Long expire, TimeUnit timeUnit)
    {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json))
        {
            return JSONUtil.toBean(json, type);
        }
        if (json != null)
        {
            return null;
        }
        R r = dbFallBack.apply(id);
        if (r == null)
        {
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        this.setWithExpireTime(key, r, expire, timeUnit);
        return r;
    }

    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallBack
            , String lockPrefix, Long expire, TimeUnit unit)
    {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json))
        {
            return null;
        }
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        if (expireTime.isAfter(LocalDateTime.now()))
        {
            return r;
        }
        String lockKey = lockPrefix + id;
        boolean isLock = tryLock(lockKey);
        if (isLock)
        {
            try {
                CACHE_REBUILD_EXECUTOR.submit(() -> {
                    R rFromDB = dbFallBack.apply(id);
                    this.setWithLogicalExpireTime(key, rFromDB, expire, unit);
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                unLock(lockKey);
            }
        }
        return r;
    }

    private boolean tryLock(String key)
    {
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key)
    {
        stringRedisTemplate.delete(key);
    }
}
