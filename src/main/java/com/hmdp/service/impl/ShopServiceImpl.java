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
import com.hmdp.utils.CaCheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /*引入工具类*/
    @Resource
    private CaCheClient caCheClient;

    @Override
    public Result queryById(Long id) {
       //缓存穿透
       //Shop shop = queryWithPassThrough(id);
        Shop shop = caCheClient.
                queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //互斥锁解决缓存击穿
        /*Shop shop = queryWithMutex(id);*/

        //逻辑过期解决缓存击穿
        /*Shop shop = queryWithLogicalExpire(id);*/

        if (shop == null){
            return Result.fail("店铺不存在！");
        }

        return Result.ok(shop);
    }

    /*创建线程池*/
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //逻辑过期解决缓存击穿
    public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY + id;
        //1. 从Redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2. 判断是否命中
        if (StrUtil.isBlank(shopJson)){
            //3. 未命中，直接返回
            return null;
        }
        //4. 命中，需要先把Json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5. 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1 未过期，则直接返回店铺信息
            return shop;
        }
        //5.2 已过期，则需要缓存重建
        //6. 缓存重建
        //6.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //6.2 判断互斥锁是否获取成功
        if (isLock){
            //6.3 成功，再次检测redis中缓存是否过期，跟解决缓存击穿时同理
            RedisData redisData2 = JSONUtil.toBean(shopJson, RedisData.class);
            Shop shop2 = JSONUtil.toBean((JSONObject) redisData2.getData(), Shop.class);
            LocalDateTime expireTime2 = redisData2.getExpireTime();
            if (expireTime2.isAfter(LocalDateTime.now())){
                //未过期，则直接返回店铺信息
                return shop2;
            }else {
                //过期，开启独立线程，实现缓存重建
                CACHE_REBUILD_EXECUTOR.submit(() -> {
                    try {
                        //重建缓存
                        this.saveShopToRedis(id, 20L);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }finally {
                        //释放锁
                        unLock(lockKey);
                    }
                });
            }
        }
        /*if (isLock){
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存
                    this.saveShopToRedis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }*/
        //6.4 失败，返回过期的店铺信息
        return shop;
    }

    //互斥锁解决缓存击穿
    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1. 从Redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2. 判断是否存在
        if (StrUtil.isNotBlank(shopJson)){
            //3. 存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断是空值还是null
        if (shopJson != null){
            //如果为空值，则返回一个错误信息
            return null;
        }
        //4. 实现缓存重建
        //4.1 获取互斥锁
        String lockKey = "lpck:shop:" + id;
        boolean isLock = tryLock(lockKey);
        //4.2 判断互斥锁是否获取成功
        if (!isLock){
            //4.3 失败，则休眠并重试
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            queryWithMutex(id);
        }

        //4.4 成功获取互斥锁
        String shopJson2 = stringRedisTemplate.opsForValue().get(key);
        //再次检测redis缓存是否存在，如果不存在则需重建缓存
        if (StrUtil.isBlank(shopJson2)){
            /*try {
                //模拟延迟
                *//*Thread.sleep(200);*//*
            } catch (InterruptedException e) {
                e.printStackTrace();
            }*/
            //查询数据库
            Shop shop = getById(id);
            //5. 数据库中不存在，返回错误信息
            if (shop == null){
                //将空值写入带Redis中
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6. 数据库中存在，写入到Redis中
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
            //7. 释放互斥锁
            unLock(lockKey);
            //8. 返回
            return shop;
        //如果存在则无需重建缓存
        }else {
            return JSONUtil.toBean(shopJson2, Shop.class);
        }
    }

    /*缓存穿透问题*/
    public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;
        //1. 从Redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2. 判断是否存在
        if (StrUtil.isNotBlank(shopJson)){
            //3. 存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断是空值还是null
        if (shopJson != null){
            //如果为空值，则返回一个错误信息
            return null;
        }
        //4. 不存在，根据id查询数据库
        Shop shop = getById(id);
        //5. 数据库中不存在，返回错误信息
        if (shop == null){
            //将空值写入带Redis中
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6. 数据库中存在，写入到Redis中
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //7. 返回
        return shop;
    }

    /*尝试获取互斥锁*/
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /*释放互斥锁*/
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    /*手动设置shop缓存*/
    public void saveShopToRedis(Long id, Long expireSeconds) throws InterruptedException {
        //1. 查询店铺信息
        Shop shop = getById(id);
        //手动设置延迟
        Thread.sleep(200);
        //2. 设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3. 写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null){
            return Result.fail("店铺id不能为空！");
        }
        //1. 更新数据库
        updateById(shop);
        //2. 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
