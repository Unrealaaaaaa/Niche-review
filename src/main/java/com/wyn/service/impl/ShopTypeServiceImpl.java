package com.wyn.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.wyn.dto.Result;
import com.wyn.entity.ShopType;
import com.wyn.mapper.ShopTypeMapper;
import com.wyn.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;

import static com.wyn.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryByList() {
        String key = CACHE_SHOPTYPE_KEY ;
        //1. 从Redis中查询商铺缓存
        String shopTypeJson = stringRedisTemplate.opsForValue().get(key);
        //2. 判断是否存在
        if (StrUtil.isNotBlank(shopTypeJson)){
            //3. 存在，直接返回
            List<ShopType> shopTypes = JSONUtil.toList(shopTypeJson, ShopType.class);
            return Result.ok(shopTypes);
        }
        //4. 不存在，根据id查询数据库
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        System.out.println(shopTypeJson);
        //5. 数据库中不存在，返回错误信息
        if (shopTypes == null){
            return Result.fail("分类不存在！");
        }
        //6. 数据库中存在，写入到Redis中
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shopTypes));
        /*设置有效期*/
        /*stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shopTypes), CACHE_SHOPTYPE_TTL, TimeUnit.MINUTES);*/
        //7. 返回
        return Result.ok(shopTypes);
    }
}
