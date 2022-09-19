package com.wyn.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * @author Unreal
 * @date 2022/7/16 - 10:30
 */

/*
* 通过Redis实现全局自增Id
* */

@Component
public class RedisIdWorker {

    /*
    *开始时间戳
    */
    private static final long BEGIN_TIMESTAMP = 1640995200L;

    /*
    * 序列号位数
    * */
    private static final int COUNT_BITS = 32;

    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String keyPrefix){
        //1. 生成时间戳
        /*获取当前时间*/
        LocalDateTime now = LocalDateTime.now();
        /*把当前时间转化为秒*/
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        /*求得时间差*/
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        //2. 生成序列号
        /*获取当前日期，精准到天*/
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        /*自增长*/
        Long count = stringRedisTemplate.opsForValue().increment("irc:" + keyPrefix + ":" + date);


        //3. 拼接并返回
        /*通过位运算使时间戳左移COUNT_BITS(32)位，在通过或运算将序列号加到时间戳的后方*/
        return timestamp << COUNT_BITS | count;
    }
}
