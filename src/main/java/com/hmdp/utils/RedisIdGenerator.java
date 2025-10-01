package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdGenerator
{
    /**
     * 开始时间戳
     */
    private static final long BEGIN_TIMESTAMP = 1735689600L;

    /**
     * 序列号的位数
     */
    private static final int COUNT_BITS = 32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public long nextId(String keyPrefix)
    {
        // 生成时间戳
        LocalDateTime current = LocalDateTime.now();
        long currentTimeStamp = current.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = currentTimeStamp - BEGIN_TIMESTAMP;
        // 生成序列号
        // 先获取当天的日期精确到天
        String date = current.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 自增长序列
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        // 拼接返回
        return timeStamp << COUNT_BITS | count;
    }

//    public static void main(String[] args) 
//    {
//        LocalDateTime localDateTime = LocalDateTime.of(2025, 1, 1, 0, 0, 0);
//        long second = localDateTime.toEpochSecond(ZoneOffset.UTC);
//        System.out.println(second);
//    }
}
