package com.wyn.config;

import com.wyn.utils.LoginInterceptor;
import com.wyn.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

/**
 * @author Unreal
 * @date 2022/6/1 - 12:36
 */
@Configuration
public class MVCConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        /*存在多个拦截器时，在没有配置order()的情况下，默认按配置的顺序来执行，
            及先配先执行，也可通过设置order值来决定，值越小优先级越高*/
        /*默认拦截所有路径*/
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).order(0);

        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/shop/**",
                        "/voucher/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/blog/hot",
                        "/user/code",
                        "/user/login"
                ).order(1);
    }
}
