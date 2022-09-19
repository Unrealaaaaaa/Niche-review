package com.wyn.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wyn.dto.LoginFormDTO;
import com.wyn.dto.Result;
import com.wyn.dto.UserDTO;
import com.wyn.entity.User;
import com.wyn.mapper.UserMapper;
import com.wyn.service.IUserService;
import com.wyn.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.wyn.utils.RedisConstants.*;
import static com.wyn.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1、效验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            //2、如果不符合格式，返回错误信息
            return Result.fail("手机号格式错误!");
        }
        //3、格式正确，生成验证码
        String code = RandomUtil.randomNumbers(6);

        /*//4、保存验证码到session中
        session.setAttribute("code",code);*/

        //4. 保存验证码到Redis中
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //5、发送验证码
        log.debug("发送验证码成功，验证码：{}",code);
        //返回ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1. 效验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)){
            //2. 如果不符合，则报错
            return Result.fail("手机号格式错误");
        }
        /*//3. 效验验证码
        Object cacheCode = session.getAttribute("code");
        String code = loginForm.getCode();*/

        //从Redis获取验证码并效验
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();

        if (cacheCode == null || !cacheCode.equals(code)){
            //4. 不一致，报错
            return Result.fail("验证码错误");
        }

        //5. 一致，根据手机号在数据库中查询数据 select * from tb_user where phone = ?
        User user = query().eq("phone", phone).one();

        //6. 判断用户是否存在
        if (user == null){
            //7. 不存在，则创建一个新用户并保存下来
           user = createUserWithPhone(phone);
        }

        /*//8. 保存用户信息到session中
        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));*/

        //  8. 保存用户信息带Redis中
        //8.1  随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        //8.2  将User对象转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        /*将userDao中的id属性由Long类型转化为string类型，否则userMap无法存储到Redis中*/
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                    .setIgnoreNullValue(true)
                    .setFieldValueEditor((fieldname, fieldvalue) -> fieldvalue.toString()));
        //8.3  存储
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        //8.4  设置token有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.SECONDS);

        //9. 返回token
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        //1. 创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(8));
        //2. 保存用户
        save(user);
        return user;
    }
}
