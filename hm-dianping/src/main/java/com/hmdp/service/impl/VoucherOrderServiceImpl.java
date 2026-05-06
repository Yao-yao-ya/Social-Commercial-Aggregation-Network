package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 优惠券订单 服务实现类 (异步秒杀重构版)
 * </p>
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    // 🔥 必须注入！执行 Lua 脚本的“对讲机”
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // ==========================================
    // 🗂️ 1. 准备大厅经理的判决锦囊（Lua脚本）
    // ==========================================
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // ==========================================
    // 📦 2. 准备装号码牌的箱子 和 后台勤杂工
    // ==========================================

    // 创建一个单线程池， 排队逐步操作数据库
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    // 全局保存代理对象，防止子线程拿不到
    private IVoucherOrderService proxy;

    // 大楼一启动，勤杂工立刻上班
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    // 勤杂工的工作手册
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
////                    // 从箱子里拿号码牌
////                    VoucherOrder voucherOrder = orderTasks.take();
////                    // 拿去地下室入库
////                    handleVoucherOrder(voucherOrder);
//
//                    // 🔥 核心魔法指令：BRPOP (右侧阻塞弹出)
//                    // 参数0代表如果没有消息，就死等（阻塞挂起），不消耗 CPU
//                    String msg = stringRedisTemplate.opsForList()
//                            .rightPop("queue:seckill:order", 0, TimeUnit.SECONDS);
//
//                    if (msg != null) {
//                        // 1. 将 Redis 里的字符串拆解出来
//                        String[] parts = msg.split(",");
//                        VoucherOrder voucherOrder = new VoucherOrder();
//                        voucherOrder.setId(Long.parseLong(parts[0]));
//                        voucherOrder.setUserId(Long.parseLong(parts[1]));
//                        voucherOrder.setVoucherId(Long.parseLong(parts[2]));
//
//                        // 2. 拿着代理对象去地下室干活
//                        handleVoucherOrder(voucherOrder);
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
                    );

                    //如果没拿到消息，继续循环下一次等
                    if (list == null || list.isEmpty()) {
                        continue; //跳过本次循环剩余代码，直接进入下一轮循环
                    }
                    //解析消息
                    MapRecord<String, Object, Object> record = list.get(0);//从消息队列里拿到一条消息
                    Map<Object, Object> values = record.getValue();//→ 取出里面真正的数据（订单信息）
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //去地下室调用MySql写库
                    handleVoucherOrder(voucherOrder);
                    //发送ACK
                    stringRedisTemplate.opsForStream().acknowledge("stream.orders", "g1", record.getId());


                } catch (Exception e) {
                    log.error("处理订单异常，消息进入 PEL，准备触发兜底重试", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try{
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create("stream.orders", ReadOffset.from("0"))
                    );

                    // 2. 如果 PEL 是空的，说明所有的坏账都补救完了，太棒了！
                    if (list == null || list.isEmpty()) {
                        break; // 结束兜底循环，回到“人格 A”继续等新订单
                    }

                    // 3. 还有坏账，继续解析
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    // 4. 再次尝试去 MySQL 写库
                    handleVoucherOrder(voucherOrder);

                    // 🔥 5. 补发 ACK！终于把这笔坏账搞定了！
                    stringRedisTemplate.opsForStream().acknowledge("stream.orders", "g1", record.getId());

                } catch (Exception e) {
                    log.error("处理 PEL 订单再次异常，休息一下继续死磕", e);
                    // 如果兜底的时候又报错了，千万不能 break，稍微睡 20 毫秒，避免把 CPU 跑爆，然后继续下一轮重试
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }
    }

    // 勤杂工拿着代理对象去入库
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        proxy.createVoucherOrder(voucherOrder);
    }

    // ==========================================
    // ⚡  主流程：大厅经理光速发号
    // ==========================================
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取当前大妈的身份证号
        Long userId = UserHolder.getUser().getId();

        long orderId = redisIdWorker.nextId("order");
        // 1. 呼叫大厅经理，执行 Lua 脚本（瞬间判断库存和一人一单）
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );

        // 2. 判断大厅经理给出的结果
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "太火爆了，库存不足！" : "不允许重复下单！");
        }

//        // 3. 校验通过！生成一张号码牌（但还没存进数据库）
//        VoucherOrder voucherOrder = new VoucherOrder();
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(voucherId);
//
//        // 4. 把号码牌扔进箱子
//        orderTasks.add(voucherOrder);

        // 5. 将带着事务的代理对象存起来，留给地下的勤杂工用
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 6. 光速打发大妈回家
        return Result.ok(orderId);
    }

    // ==========================================
    // 🗄️ 4. 地下室流程：数据库兜底写入
    // ==========================================
    // 🔥 注意：方法的参数从 Long voucherId 变成了 VoucherOrder 对象
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder){
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

        // 数据库再次兜底检查一人一单（虽然前台 Lua 已经防住了，但写库时加一层双保险是好习惯）
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0){
            log.error("该用户已经购买过一次了");
            return;
        }

        // 兜底扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足");
            return;
        }

        // 最终把号码牌锁进档案柜
        save(voucherOrder);
    }
}