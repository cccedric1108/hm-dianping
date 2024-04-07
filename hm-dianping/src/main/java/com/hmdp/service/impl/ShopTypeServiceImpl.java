package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_SORT;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result querySort() {
        //查询redis
        String sort = stringRedisTemplate.opsForValue().get(CACHE_SHOP_SORT);
        //存在：返回
        if (sort!=null) {

            return Result.ok(JSONUtil.toList(sort, ShopType.class));
        }
        //不存在：查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();

        //数据库不存在：报错
        if (typeList==null){
            return Result.fail("无分类数据");
        }
        //存在，写回redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_SORT,JSONUtil.toJsonStr(typeList));

        //返回数据
        return Result.ok(typeList);
    }
}
