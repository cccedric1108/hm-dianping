package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
@Slf4j
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient (StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate=stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time,unit);

    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){

        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写如redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));

    }

    //缓存穿透
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id,
                                         Class<R> type, Function<ID,R> dbFallBack, Long time, TimeUnit unit){
        //从redis查询
        String key =keyPrefix + id;
        String json= stringRedisTemplate.opsForValue().get(key);
        //redis存在
        if (StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json,type);
        }
        //判断命中是否是空值
        if (json!=null){
            return null;
        }
        //不存在,查数据库
        R r = dbFallBack.apply(id);

        //不存在
        if (r==null){
            //将空值写入，防止缓存穿透
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //存在，写回
        this.setWithLogicalExpire(key,r,time,unit);

        return r;

    }


    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R,ID> R queryWithLogicalExpire(String keyPrefix,ID id, Class<R> type,
                                        Function<ID,R> dbFallbcak, Long time, TimeUnit unit){
        //从redis查询
        String key = keyPrefix + id;
        String json= stringRedisTemplate.opsForValue().get(key);
        //redis不存在
        if (StrUtil.isBlank(json)){
            return null;
        }

        //1.存在
        //1.1.反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject)redisData.getData();
        R r = JSONUtil.toBean(data, type);

        LocalDateTime expireTime = redisData.getExpireTime();

        //2.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            //2.1未过期
            return r;
        }

        //2.2已过期
        //3.缓存重建
        //3.1获取锁
        String lockKey= LOCK_SHOP_KEY+"id";
        boolean isLock = tryLock(lockKey);

        //3.2判断是否成功
        if (isLock){
            //3.3成功，开启独立线程，重建缓存
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //查询数据库
                    R r1=dbFallbcak.apply(id);
                    //写入redis
                    //重建缓存
                    this.setWithLogicalExpire(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        //3.4不成功，返回过期信息
        return r;

    }

    //尝试获取互斥锁
    private boolean tryLock(String key){
        Boolean flag=stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);

    }
    //释放锁
    private  void unlock(String key){
        stringRedisTemplate.delete(key);
    }
}
