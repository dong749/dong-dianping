package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdGenerator;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdGenerator redisIdGenerator;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Override
    public Result seckillVoucher(Long voucherId)
    {
        // 查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //判断秒杀是否已经开始或者已经结束
        LocalDateTime beginTime = voucher.getBeginTime();
        LocalDateTime endTime = voucher.getEndTime();
        LocalDateTime now = LocalDateTime.now();
        if (beginTime.isAfter(now))
        {
            // 活动尚未开始
            return Result.fail("秒杀尚未开始");
        }
        if (endTime.isBefore(now))
        {
            return Result.fail("秒杀已经结束");
        }
        // 判断库存是否充足
        Integer stock = voucher.getStock();
        if (stock < 1)
        {
            return Result.fail("库存不足");
        }
        // 每个用户同一张优惠券只能下单一次
        Long userId = UserHolder.getUser().getId();
        // 在此处上锁，实现了在事务提交之后在释放锁，防止在事务未提交的时候
        // 释放锁，导致并发安全问题，一个用户多次下单
        // 创建锁对象
        // SimpleRedisLock simpleRedisLock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean success = lock.tryLock();
        // boolean success = simpleRedisLock.tryLock(5L);
        if (!success)
        {
            // 获取锁失败
            return Result.fail("该用户已经下单一次");
        }
        // 获取锁成功
        try {
            // 获取代理对象（事务），否则会出现事务失效的问题，事务是由代理实现的
            // 如果直接在这里调用createVoucherOrder会出现事务失效，所以需要先
            // 获取VoucherOrderServiceImpl的代理对象，利用代理对象调用createOrderOrder
            // 同时需要在IVoucherOrderService中创建createOrderOrder的抽象方法
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            // simpleRedisLock.unlock();
            lock.unlock();
        }
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId)
    {
        Long userId = UserHolder.getUser().getId();
        int count = query().eq("voucher_id", voucherId).eq("user_id", userId).count();
        if  (count > 0)
        {
            return Result.fail("该用户已经下单过一次，无法再次下单");
        }
        // 如果充足扣减库存（乐观锁一定程度上解决超买超卖）
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
                .update();
        if (!success)
        {
            return Result.fail("库存不足");
        }
        // 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdGenerator.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        return Result.ok(orderId);
    }
}
