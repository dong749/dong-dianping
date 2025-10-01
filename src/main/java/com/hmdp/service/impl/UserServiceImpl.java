package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService
{

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session)
    {
        // 检验手机号码
        boolean isValid = RegexUtils.isPhoneInvalid(phone);
        // 如果手机号码不符合，返回错误信息
        if (isValid)
        {
            return Result.fail("手机号码格式错误");
        }
        // 如果符合生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 将生成的验证码保存到Redis中
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code
                , RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 发送验证码
        log.debug("发送成功" + code);
        // 返回
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session)
    {
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();
        // 检验手机号码
        if (RegexUtils.isPhoneInvalid(phone))
        {
            return Result.fail("手机号码格式错误");
        }
        // 检验验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        if (cacheCode == null || cacheCode.isEmpty() || !cacheCode.equals(code))
        {
            // 不一致，报错
            return Result.fail("验证码不合法");
        }
        // 一致，根据手机号查询数据库
        User user = query().eq("phone", phone).one();
        // 判断用户是否存在
        if (user == null)
        {
            // 不存在，创建数据库，向数据库中插入新用户
            user = createUserWithPhone(phone);
        }
        // 保存用户信息到Redis中方便后续使用
        // 生成随机token作为Redis中的key
        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 手动转换Map，确保所有值为String类型
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create()
                .setIgnoreNullValue(true).setFieldValueEditor((fieldName, filedValue) -> filedValue.toString()));
        // 将userDTO存储到Redis中
        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY + token, userMap);
        // 设置token有效期
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY, RedisConstants.LOGIN_USER_TTL
                , TimeUnit.MINUTES);
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone)
    {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX
                + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
