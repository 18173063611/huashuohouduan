package com.huashuo.user.controller;

import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.user.dto.UserLoginRequest;
import com.huashuo.user.dto.UserRegisterRequest;
import com.huashuo.user.service.UserAuthService;
import com.huashuo.user.util.AuthHeaderParser;
import com.huashuo.user.vo.UserLoginResponse;
import com.huashuo.user.vo.UserMeResponse;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/auth")
/**
 * 认证接口：注册/登录/退出/当前用户。MVP 阶段使用 token session，不影响现有业务接口。
 */
public class AuthController {

    private final UserAuthService userAuthService;

    public AuthController(UserAuthService userAuthService) {
        this.userAuthService = userAuthService;
    }

    @PostMapping("/register")
    public ApiResponse<UserLoginResponse> register(@Valid @RequestBody UserRegisterRequest request) {
        return ApiResponse.success(
                userAuthService.register(
                        request.username(),
                        request.password(),
                        request.displayName(),
                        request.key(),
                        request.clientType(),
                        request.deviceId(),
                        traceId()
                ),
                traceId()
        );
    }

    @PostMapping("/login")
    public ApiResponse<UserLoginResponse> login(@Valid @RequestBody UserLoginRequest request) {
        return ApiResponse.success(
                userAuthService.login(
                        request.username(),
                        request.password(),
                        request.clientType(),
                        request.deviceId(),
                        traceId()
                ),
                traceId()
        );
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization,
                                    @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken) {
        userAuthService.logout(resolveToken(authorization, xAuthToken));
        return ApiResponse.success(null, traceId());
    }

    @GetMapping("/me")
    public ApiResponse<UserMeResponse> me(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken) {
        return ApiResponse.success(userAuthService.me(resolveToken(authorization, xAuthToken)), traceId());
    }

    private String resolveToken(String authorization, String xAuthToken) {
        String token = AuthHeaderParser.resolveBearer(authorization, xAuthToken);
        if (token == null) {
            return null;
        }
        return token;
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
