package com.wyn.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.wyn.utils.RedisConstants.*;

/**
 * @author Unreal
 * @date 2022/7/4 - 12:09
 */

@Slf4j
@Component
public class CaCheClient {

    private final StringRedisTemplate stringRedisTemplate;


    public CaCheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        //设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value));
    }

    /*缓存穿透问题*/
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        //1. 从Redis中查询商铺缓存
        String Json = stringRedisTemplate.opsForValue().get(key);
        //2. 判断是否存在
        if (StrUtil.isNotBlank(Json)){
            //3. 存在，直接返回
            return JSONUtil.toBean(Json, type);
        }
        //判断是空值还是null
        if (Json != null){
            //如果为空值，则返回一个错误信息
            return null;
        }
        //4. 不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        //5. 数据库中不存在，返回错误信息
        if (r == null){
            //将空值写入带Redis中
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6. 数据库中存在，写入到Redis中
        this.set(key, r, time, unit);
        //7. 返回
        return r;
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

    /*创建线程池*/
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //逻辑过期解决缓存击穿
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        //1. 从Redis中查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2. 判断是否命中
        if (StrUtil.isBlank(json)){
            //3. 未命中，直接返回
            return null;
        }
        //4. 命中，需要先把Json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5. 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1 未过期，则直接返回店铺信息
            return r;
        }
        //5.2 已过期，则需要缓存重建
        //6. 缓存重建
        //6.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //6.2 判断互斥锁是否获取成功
        if (isLock){
            //6.3 成功，再次检测redis中缓存是否过期，跟解决缓存击穿时同理
            RedisData redisData2 = JSONUtil.toBean(json, RedisData.class);
            R r2 = JSONUtil.toBean((JSONObject) redisData2.getData(), type);
            LocalDateTime expireTime2 = redisData2.getExpireTime();
            if (expireTime2.isAfter(LocalDateTime.now())){
                //未过期，则直接返回店铺信息
                return r2;
            }else {
                //过期，开启独立线程，实现缓存重建
                CACHE_REBUILD_EXECUTOR.submit(() -> {
                    try {
                        //查询数据库
                        R r3 = dbFallback.apply(id);
                        //写入redis
                        this.setWithLogicalExpire(key, r3, time, unit);
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
        return r;
    }
}
