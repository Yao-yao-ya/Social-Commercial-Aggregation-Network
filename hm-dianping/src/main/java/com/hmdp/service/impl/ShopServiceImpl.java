package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {


    private final StringRedisTemplate stringRedisTemplate;

    public ShopServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {

        Shop shop = cacheClient.queryWithPassThrough(
                RedisConstants.CACHE_SHOP_KEY,
                id,
                Shop.class,
                this::getById,
                RedisConstants.CACHE_SHOP_TTL,
                TimeUnit.MINUTES
        );
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }

    @Override
    @Transactional //开启事务
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);

        return Result.ok();
    }


    public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;

        //1.redis查缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //存在 直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if (shopJson != null){
            return null;
        }
        //不存在 根据id查询数据库
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Shop shop = null;
        try{
            boolean isLock = tryLock(lockKey);
            if (!isLock){
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            shopJson =  stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJson)) {
                return JSONUtil.toBean(shopJson, Shop.class);
            }

            shop = getById(id);

        //不存在 返回错误
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.
                    CACHE_NULL_TTL, TimeUnit.MINUTES);

            return null;
        }
        //存在 写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
    } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(lockKey);
        }
        return shop;
        }

    //尝试获得锁
    private boolean tryLock (String key){
        //互斥锁操作并且使用setifabenst设置过期时间防止死锁
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL,
                TimeUnit.SECONDS);
        //拆箱处理，防止空指针
    return BooleanUtil.isTrue(flag);
    }

    //释放锁 （撤销警戒线）
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
