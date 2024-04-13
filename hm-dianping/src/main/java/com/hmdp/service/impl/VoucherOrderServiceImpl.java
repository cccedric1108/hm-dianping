package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

    @Override
    public Result seckillVoucher(Long voucherId) {

        //查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀未开始");
        }
        //判断是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已经结束");

        }
        //判断库存
        if (voucher.getStock()<1) {
            return Result.fail("库存不足");
        }
        //扣减库存
        boolean success=seckillVoucherService.update()
                .setSql("stock =stock - 1")
                .eq("voucher_id",voucherId)
                .gt("stock",0)//确保线程安全
                .update()
                ;
        if (!success){
            //失败
            return Result.fail("库存不足");
        }
        Long userId = UserHolder.getUser().getId();
        //转为string时，生成string仍然通过new生成，使用intern返回字符串对象内容，而不是对象

        //以下代码有并发风险，使用自制分布式锁来避免
//        synchronized (userId.toString().intern()) {
//            //获取spring代理对象，对代理对象提交事物，否则会失效
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }

        //分布式锁：
        //创建锁对象,锁定用户id
        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁
        boolean isLock = lock.tryLock();
        if (!isLock){
            //失败，返回错误
            return  Result.fail("一人只可以下一单！");
        }
        try{
            //获取spring代理对象，对代理对象提交事物，否则会失效
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }finally {
            lock.unlock();
        }

    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //判断一人一单
        Long userId = UserHolder.getUser().getId();
            //1.查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            //2.判断是否存在
            if (count > 0) {
                return Result.fail("用户已经购买过优惠券");
            }

            //创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            //生成订单Id
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            //用户id
            voucherOrder.setUserId(userId);
            //代金券id
            voucherOrder.setVoucherId(voucherId);

            save(voucherOrder);

            //返回订单id
            return Result.ok(orderId);
        }

}
