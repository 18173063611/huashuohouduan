package com.huashuo.user.service;

import com.huashuo.user.vo.UserLoginResponse;
import com.huashuo.user.vo.UserMeResponse;

import java.util.OptionalLong;

public interface UserAuthService {

    UserLoginResponse register(String username, String password, String displayName, String traceId);

    UserLoginResponse login(String username, String password, String traceId);

    void logout(String token);

    UserMeResponse me(String token);

    /**
     * 解析当前请求的用户 id：token 缺失或无效时返回 empty，不抛异常（供可选登录接口使用）。
     */
    OptionalLong resolveUserIdOptional(String authorization, String xAuthToken);
}

