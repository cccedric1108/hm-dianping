package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.*;

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

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//
//        //查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀未开始");
//        }
//        //判断是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("秒杀已经结束");
//
//        }
//        //判断库存
//        if (voucher.getStock()<1) {
//            return Result.fail("库存不足");
//        }
//        //扣减库存
//        boolean success=seckillVoucherService.update()
//                .setSql("stock =stock - 1")
//                .eq("voucher_id",voucherId)
//                .gt("stock",0)//确保线程安全
//                .update()
//                ;
//        if (!success){
//            //失败
//            return Result.fail("库存不足");
//        }
//        Long userId = UserHolder.getUser().getId();
//        //转为string时，生成string仍然通过new生成，使用intern返回字符串对象内容，而不是对象
//
//        //以下代码有并发风险，使用自制分布式锁来避免
////        synchronized (userId.toString().intern()) {
////            //获取spring代理对象，对代理对象提交事物，否则会失效
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId);
////        }
//
//        //分布式锁：
//        //创建锁对象,锁定用户id
//        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        //获取锁
//        boolean isLock = lock.tryLock();
//        if (!isLock){
//            //失败，返回错误
//            return  Result.fail("一人只可以下一单！");
//        }
//        try{
//            //获取spring代理对象，对代理对象提交事物，否则会失效
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }finally {
//            lock.unlock();
//        }
//
//    }

    //使用静态代码块加载lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //创建阻塞队列
    private BlockingQueue<VoucherOrder> ordersTask = new ArrayBlockingQueue<>(1024*1024);
    //创建线程池
    private final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct//类加载时就执行
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    //创建线程任务
    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while (true){
                try {
                    //从阻塞队列获取任务
                    VoucherOrder voucherOrder = ordersTask.take();
                    //创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                }
            }

        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //获取用户
        Long userId = voucherOrder.getUserId();
        //创建锁对象,锁定用户id
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁
        boolean isLock = lock.tryLock();
        if (!isLock){
            //失败，返回错误
            log.error("不允许重复下单");
            return;
        }
        try{
            //子线程可以直接获取成员变量中的代理对象
            proxy.createVoucherOrder(voucherOrder);
        }finally {
            lock.unlock();
        }
    }

    //将当前线程作为成员变量
    private IVoucherOrderService proxy;

    //将代码改造为异步秒杀，使用lua脚本在redis中进行
    public Result seckillVoucher(Long voucherId) {

        Long userId = UserHolder.getUser().getId();
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );

        //2.判断是否是0
        int r =result.intValue();
        //2.1 不是0-无资格
        if (r != 0){
            return Result.fail(r==1 ? "库存不足" : "不能重复下单");
        }
        //2.2有资格,将下单信息保存到异步队列
        long orderId = redisIdWorker.nextId("order");
        //TODO 保存阻塞队列
        //2.3创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        //用户id
        voucherOrder.setUserId(userId);
        //代金券id
        voucherOrder.setVoucherId(voucherId);
        //2.4添加阻塞队列
        ordersTask.add(voucherOrder);

        //获取spring代理对象，对代理对象提交事物，否则会失效
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        //3.返回订单id
        return Result.ok(orderId);

    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //判断一人一单
        Long userId = voucherOrder.getUserId();
        //1.查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        //2.判断是否存在
        if (count > 0) {
            log.error("用户已经购买过优惠券");
            return;
        }

        boolean success=seckillVoucherService.update()
                .setSql("stock =stock - 1")
                .eq("voucher_id",voucherOrder.getVoucherId())
                .gt("stock",0)//确保线程安全
                .update()
                ;
        if (!success){
            //失败
            log.error("库存不足");
            return;
        }
        save(voucherOrder);


        }

}
