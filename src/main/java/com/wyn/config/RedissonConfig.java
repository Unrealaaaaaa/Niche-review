/*
package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

*/
/**
 * @author Unreal
 * @date 2022/7/31 - 9:35
 *//*

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(){
        */
/*配置*//*

        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.37.128:6379").setPassword("916306");
        */
/*创建RedissonClient对象*//*

        return Redisson.create(config);
    }
}
*/
