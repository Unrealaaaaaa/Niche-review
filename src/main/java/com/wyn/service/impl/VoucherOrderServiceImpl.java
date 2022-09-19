package com.wyn.service.impl;

import com.wyn.dto.Result;
import com.wyn.entity.VoucherOrder;
import com.wyn.mapper.VoucherOrderMapper;
import com.wyn.service.ISeckillVoucherService;
import com.wyn.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wyn.utils.RedisIdWorker;
import com.wyn.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;


    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //添加阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    //创建单线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    /*在类初始化的时候执行线程池*/
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run(){
            while (true){
                try {
                    //1. 获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2. 创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("订单处理异常",e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder){
        //1. 获取用户
        Long userId = voucherOrder.getUserId();
        //2. 创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //3. 获取锁
        boolean isLock = lock.tryLock();
        //4. 判断是否获取锁成功
        if (!isLock){
            //获取锁失败，返回错误或重试
            log.error("不允许重复下单");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            //释放锁
            lock.unlock();
        }
    }

    private IVoucherOrderService proxy;

    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //1. 执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        //2. 判断结果为0
        int r = result.intValue();
        if (r != 0){
            //2.1 不为0,代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //2.2 为0，有购买资格，把下单信息保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        /*订单Id*/
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        /*用户Id*/
        voucherOrder.setUserId(userId);
        /*代金券Id*/
        voucherOrder.setVoucherId(voucherId);
        //放入阻塞队列
        orderTasks.add(voucherOrder);

        //3. 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //4. 返回订单id
        return Result.ok(orderId);
    }

    /*@Override
    public Result seckillVoucher(Long voucherId) {
        //1. 查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2. 判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())){
            //秒杀尚未开始
            return Result.fail("秒杀尚未开始");
        }
        //3. 判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())){
            //秒杀已经结束
            return Result.fail("秒杀已经结束");
        }
        //4. 判断库存是否充足
        if (voucher.getStock() < 1){
            //库存不足
            return Result.fail("库存不足");
        }

        Long userId = UserHolder.getUser().getId();
        *//*利用自己写好的方法创建锁对象*//*
        *//*SimpleRedisLock lock = new SimpleRedisLock("lock;" + userId, stringRedisTemplate);*//*
        *//*利用Redisson提供的方法创建锁对象*//*
        RLock lock = redissonClient.getLock("lock;" + userId);

        //自己写好的方法获取锁
        *//*boolean isLock = lock.tryLock(12000);*//*
        *//*Redisson提供的方法获取锁，可按需求来填写tryLock参数(获取锁的最大时间(期间会重试)，锁自动释放时间， 时间单位)*//*
        boolean isLock = lock.tryLock();

        //判断是否获取成功
        if (!isLock){
            //获取锁失败，返回错误或重试
            return Result.fail("不允许重复下单!");
        }
        try {
            *//*获取代理对象（事务）*//*
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            lock.unlock();
        }
    }*/

    /*添加事务，在出现错误是回滚*/
    @Transactional
    public Result createVoucherOrder(VoucherOrder voucherOrder) {
        //5. 一人一单
        Long userId = voucherOrder.getUserId();
        /*查询订单*/
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        /*判断订单是否存在*/
        if (count > 0){
            /*用户已经购买过了*/
            log.error("每人最多购买一次！");
            return Result.fail("用户已经购买过一次！");
        }

        //6. 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1 ")  //set stock = stock - 1
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock",0) //where voucher_id = ? and stock > 0
                .update();
        if (!success){
            //扣减失败
           return Result.fail("库存不足！");
        }

        //7. 创建订单
        save(voucherOrder);
        return Result.ok("下单成功");
    }
}
