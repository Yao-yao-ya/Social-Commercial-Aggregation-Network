package com.hmdp.utils;

public interface ILock {
    /**
     * 尝试获取锁（跑到广场去抢黑板）
     * @param timeoutSec 锁持有的超时时间（挥发墨水的倒计时，过期自动删除）
     * @return true代表抢到了，false代表别人正在用
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}
