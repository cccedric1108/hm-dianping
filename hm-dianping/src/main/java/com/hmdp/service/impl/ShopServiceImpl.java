package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.ssh.JschUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

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

    @Resource
    private CacheClient cacheClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop=queryWithPassThrough(id);
//        Shop shop=cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class
//                ,id2-> getById(id2),CACHE_SHOP_TTL,TimeUnit.MINUTES);

        //互斥锁解决击穿
//        Shop shop=queryWithMutex(id);


        //逻辑过期解决击穿
        //Shop shop=queryWithLogicalExpire(id);
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class,
                this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);


        if (shop == null) {
            return Result.fail("店铺不存在");
        }

        return Result.ok(shop);
    }

    //线程池
//    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //逻辑过期解决击穿
//    public Shop queryWithLogicalExpire(Long id){
//        //从redis查询
//        String key =CACHE_SHOP_KEY+id;
//        String shopJson= stringRedisTemplate.opsForValue().get(key);
//        //redis不存在
//        if (StrUtil.isBlank(shopJson)){
//            return null;
//        }
//
//        //1.存在
//        //1.1.反序列化为对象
//        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
//        JSONObject data = (JSONObject)redisData.getData();
//        Shop shop = JSONUtil.toBean(data, Shop.class);
//        LocalDateTime expireTime = redisData.getExpireTime();
//        //2.判断是否过期
//        if (expireTime.isAfter(LocalDateTime.now())){
//            //2.1未过期
//            return shop;
//        }
//
//        //2.2已过期
//        //3.缓存重建
//        //3.1获取锁
//        String lockKey= LOCK_SHOP_KEY+"id";
//        boolean isLock = tryLock(lockKey);
//
//        //3.2判断是否成功
//        if (isLock){
//            //3.3成功，开启独立线程，重建缓存
//            CACHE_REBUILD_EXECUTOR.submit(()->{
//                try {
//                    //重建缓存
//                    this.saveShop2Redis(id,20L);
//                } catch (Exception e) {
//                    throw new RuntimeException(e);
//                }finally {
//                    //释放锁
//                    unlock(lockKey);
//                }
//            });
//        }
//        //3.4不成功，返回过期信息
//        return shop;
//
//    }
//
//    //缓存击穿,互斥锁
//    public Shop queryWithMutex(Long id){
//        //从redis查询
//        String key =CACHE_SHOP_KEY+id;
//        String shopJson= stringRedisTemplate.opsForValue().get(key);
//        //redis存在
//        if (StrUtil.isNotBlank(shopJson)){
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            return shop;
//        }
//        //判断命中是否是空值(穿透)
//        if (shopJson!=null){
//            return null;
//        }
//
//        //未命中，实现缓存重建
//        //1.获取互斥锁
//        String lockKey=LOCK_SHOP_KEY + id;
//        Shop shop = null;
//        try {
//            boolean isLock = tryLock(lockKey);
//
//            //2.判断是否成功
//            if (!isLock){
//                //失败休眠重试
//                Thread.sleep(50);
//                return queryWithMutex(id);
//            }
//
//            //成功
//            shop = getById(id);
//            //模拟重建延时
//            Thread.sleep(200);
//            //不存在，报错
//            if (shop==null){
//                //将空值写入，防止缓存穿透
//                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
//                return null;
//            }
//            //存在，写回
//            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
//
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        }finally {
//            //释放胡互斥锁
//            unlock(lockKey);
//        }
//
//        return shop;
//
//    }
//
//    //缓存穿透
//    public Shop queryWithPassThrough(Long id){
//        //从redis查询
//        String key =CACHE_SHOP_KEY+id;
//        String shopJson= stringRedisTemplate.opsForValue().get(key);
//        //redis存在
//        if (StrUtil.isNotBlank(shopJson)){
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            return shop;
//        }
//        //判断命中是否是空值
//        if (shopJson!=null){
//            return null;
//        }
//        //不存在,查数据库
//        Shop shop = getById(id);
//
//        //不存在，报错
//        if (shop==null){
//            //将空值写入，防止缓存穿透
//            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
//            return null;
//        }
//        //存在，写回
//        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
//
//        return shop;
//
//    }
//
//
//    //尝试获取互斥锁
//    private boolean tryLock(String key){
//        Boolean flag=stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
//        return BooleanUtil.isTrue(flag);
//
//    }
//    //释放锁
//    private  void unlock(String key){
//        stringRedisTemplate.delete(key);
//    }

    //转换并保存热点数据
    public void saveShop2Redis(Long id, Long expireSecomds) throws InterruptedException {
        //查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);
        //封装逻辑过期
        RedisData redisData=new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSecomds));
        //写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id =shop.getId();
        if (id==null){
            return Result.fail("店铺id不可以为空");
        }
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
