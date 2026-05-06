package com.hmdp;

import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
class RedisIdWorkerTest {

    @Resource
    private RedisIdWorker redisIdWorker;

    // 1. 组建“跑腿小哥施工队”：创建一个拥有 500 个工人的线程池
    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    void testIdWorker() throws InterruptedException {
        // 2. 准备发令枪和打卡机：我们需要派发 300 个任务（每个任务去拿 100 个号）
        CountDownLatch latch = new CountDownLatch(300);

        // 记录演习开始的时间
        long begin = System.currentTimeMillis();

        // 3. 定义小哥的任务：跑去前台大爷那里连拿 100 个号码牌
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                // System.out.println("id = " + id); // 提示：真压测时千万别开这句打印，控制台IO极慢，会影响测试成绩！
            }
            // 这个小哥干完活了，打卡机上的倒数数字减 1
            latch.countDown();
        };

        // 4. 演习开始！把这 300 个任务扔给线程池里的工人去抢着做
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }

        // 5. 包工头在这里死等：只要打卡机（latch）还没归零，就说明还有小哥没干完，主线程就在这里等着不准走
        latch.await();

        // 记录演习结束的时间
        long end = System.currentTimeMillis();

        // 6. 汇报战果
        System.out.println("生成 3万 个唯一ID，总共耗时 = " + (end - begin) + " 毫秒");
    }
}