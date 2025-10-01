package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService
{
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Override
    public Result queryById(Long id)
    {
        // 解决了缓存穿透的查询
        // Shop shop = queryWithPassThrough(id);
        Shop shop = cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY, id, Shop.class
                , this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);
//        if (shop == null)
//        {
//            return Result.fail("店铺不存在");
//        }

        // 逻辑过期解决缓存击穿
//        Shop shop = queryWithLogicalExpire(id);
//        if (shop == null)
//        {
//            return Result.fail("店铺不存在");
//        }


        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result update(Shop shop)
    {
        Long id = shop.getId();
        if (id == null)
        {
            return Result.fail("无法查找到店铺");
        }
        // 更新数据库
        updateById(shop);
        // 删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
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

    public Shop queryWithPassThrough(Long id)
    {
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        if (StrUtil.isNotBlank(shopJson))
        {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if (shopJson != null)
        {
            return null;
        }
        Shop shop = getById(id);
        if (shop == null)
        {
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, ""
                    , RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop)
                , RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    public Shop queryWithMutex(Long id)
    {
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        if (StrUtil.isNotBlank(shopJson))
        {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if (shopJson != null)
        {
            return null;
        }
        // 实现缓存重建
        // 获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            // 判断是否获取成功
            if (!isLock)
            {
                // 失败则休眠并尝试重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 成功则根据ID查数据库
            shop = getById(id);
            Thread.sleep(200);
            if (shop == null)
            {
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, ""
                        , RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop)
                    , RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放互斥锁
            unLock(lockKey);
        }
        return shop;
    }

    public void saveShopToRedis(Long id, Long expireSeconds)
            throws InterruptedException
    {
        Shop shop = getById(id);
        Thread.sleep(200);
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        redisData.setData(shop);
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    public Shop queryWithLogicalExpire(Long id)
    {
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        if (StrUtil.isBlank(shopJson))
        {
            // 在逻辑过期时间解决缓存击穿问题时，如果未命中缓存直接返回null
            // 因为在逻辑时间中，是不会给Key设置Expire时间的，所以如果缓存
            // 中没有，则一定是查询不到的
            return null;
        }
        // 命中缓存需要判断逻辑过期时间，需要先把Redis取出的Json转换成RedisData对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        // 判断是否逻辑时间过期
        if (expireTime.isAfter(LocalDateTime.now()))
        {
            // 未过期，直接返回信息
            return shop;
        }
        // 已过期，需要缓存重建
        // 获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 判断是否获取锁成功
        if (isLock)
        {
            // 成功，开启一个新的独立线程，进行缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.saveShopToRedis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey);
                }
            });
        }
        // 获取锁失败，直接返回过期的旧数据
        return shop;
    }


}
