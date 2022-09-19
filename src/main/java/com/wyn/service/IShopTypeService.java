package com.wyn.service;

import com.wyn.dto.Result;
import com.wyn.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 */
public interface IShopTypeService extends IService<ShopType> {

    Result queryByList();
}
