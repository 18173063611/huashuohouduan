package com.huashuo.user.service;

import com.huashuo.user.vo.UserLoginResponse;
import com.huashuo.user.vo.UserMeResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.OptionalLong;

public interface UserAuthService {

    default UserLoginResponse register(String username, String password, String displayName, String key, String traceId) {
        return register(username, password, displayName, key, "USER_WEB", null, traceId);
    }

    UserLoginResponse register(String username, String password, String displayName, String key,
                               String clientType, String deviceId, String traceId);

    default UserLoginResponse login(String username, String password, String traceId) {
        return login(username, password, "USER_WEB", null, traceId);
    }

    UserLoginResponse login(String username, String password, String clientType, String deviceId, String traceId);

    void logout(String token);

    UserMeResponse me(String token);

    UserMeResponse updateProfile(String token, String displayName, String phone, String email, String remark);

    UserMeResponse updateAvatar(String token, MultipartFile file);

    UserMeResponse clearAvatar(String token);

    void changePassword(String token, String currentPassword, String newPassword);

    long requireUserId(String authorization, String xAuthToken);

    /**
     * 解析当前请求的用户 id：token 缺失或无效时返回 empty，不抛异常（供可选登录接口使用）。
     */
    OptionalLong resolveUserIdOptional(String authorization, String xAuthToken);
}
