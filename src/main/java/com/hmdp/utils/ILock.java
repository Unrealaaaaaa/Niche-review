package com.hmdp.utils;

/**
 * @author Unreal
 * @date 2022/7/25 - 9:43
 */
public interface ILock {

    /*
    * 尝试获取锁
    *@param timeoutSec 锁持有的超时时间，超时自动释放
    * @return true代表获取锁成功：false表示获取锁失败
    * */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unLock();
}
