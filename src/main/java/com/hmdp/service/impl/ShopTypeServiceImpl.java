package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService
{
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result getShopTypeWithList()
    {
        List<String> list = stringRedisTemplate.opsForList().range(RedisConstants.CACHE_SHOP_TYPE, 0, -1);
        List<ShopType> typeList = new ArrayList<>();
        if (list != null && !list.isEmpty())
        {
            for (int i = 0; i < list.size(); i++)
            {
                typeList.add(JSONUtil.toBean(list.get(i), ShopType.class));
            }
            return Result.ok(typeList);
        }
        List<ShopType> shopTypes = list();
        if (shopTypes == null || shopTypes.isEmpty())
        {
            return Result.fail("无法查询商铺类型");
        }
        List<String> jsonShopTypes = shopTypes.stream().map(JSONUtil::toJsonStr).collect(Collectors.toList());
        stringRedisTemplate.opsForList().rightPushAll(RedisConstants.CACHE_SHOP_TYPE, jsonShopTypes);
        return Result.ok(shopTypes);
    }
}
