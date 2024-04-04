package com.hmdp.service.impl;

import cn.hutool.core.util.RadixUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.sql.ResultSet;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验
        if (RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号码错误！");
        }
        //生成验证码
        String code = RandomUtil.randomNumbers(6);
        //保存到session
        session.setAttribute("code",code);
        //发送验证码
        log.debug("发送验证码：code ="+code);

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {

        //校验手机号，验证码
        String phone=loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号码错误！");
        }
        Object cacheCode=session.getAttribute("code");
        String code =loginForm.getCode();
        if(cacheCode==null || !cacheCode.toString().equals(code)){
            return Result.fail("验证码错误！");
        }
        //查找用户 Mybatis Plus 快速单表查询，使用extends ServiceImpl<UserMapper, User>
        User user = query().eq("phone", phone).one();
        if (user == null){
            //创建并保存
            user = createUserWithPhone(phone);
        }

        //保存到session
        session.setAttribute("user",user);

        return Result.ok();
    }

    private User createUserWithPhone(String phone) {

        User user=new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        save(user);
        return user;

    }
}
