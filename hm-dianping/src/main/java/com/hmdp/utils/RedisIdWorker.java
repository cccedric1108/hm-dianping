package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

//全局id生成器
@Component
public class RedisIdWorker {


    private StringRedisTemplate stringRedisTemplate;

    //初始时间戳
    private static final long BEGIN_TIMESTAMP =1712534400L;
    //序列号移位数
    private static final int COUNT_BITS = 32;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String keyPrefix){
        //生成时间戳
        LocalDateTime now =LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp =nowSecond - BEGIN_TIMESTAMP;

        //2生成序列号
        //2.1获取当前日期
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //2.2自增长
        Long count = stringRedisTemplate.opsForValue().increment("irc:" + keyPrefix + ":" + date);

        //拼接返回
        //3.1时间戳左移
        return timeStamp << COUNT_BITS | count;
    }

    public static void main(String[] args){
        //设置初始时间
        LocalDateTime localDateTime = LocalDateTime.of(2024,4,8,0,0,0);
        long second = localDateTime.toEpochSecond(ZoneOffset.UTC);
        System.out.println("second = " + second);
    }

}
