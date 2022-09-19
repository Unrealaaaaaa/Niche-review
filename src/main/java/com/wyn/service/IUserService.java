package com.wyn.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.wyn.dto.LoginFormDTO;
import com.wyn.dto.Result;
import com.wyn.entity.User;

import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 */
public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);
}
