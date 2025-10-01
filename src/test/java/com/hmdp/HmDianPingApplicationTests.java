package com.hmdp;

import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private RedisIdGenerator redisIdGenerator;

    private ExecutorService executorService = Executors.newFixedThreadPool(500);

    @Test
    void contextLoads() throws InterruptedException {
        shopService.saveShopToRedis(1L, 10L);
    }

    @Test
    void testIdWork() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Runnable task = () -> {
          for (int i = 0; i < 100; i++)
          {
              long id = redisIdGenerator.nextId("order");
              System.out.println("id: " + id);
          }
          countDownLatch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++)
        {
            executorService.submit(task);
        }
        countDownLatch.await();
        long end = System.currentTimeMillis();
        System.out.println("time: " + (end - begin));
    }
}
