package com.huashuo.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserLoginRequest(
        @NotBlank(message = "用户名不能为空")
        @Size(max = 60, message = "用户名最长 60 字符")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(max = 60, message = "密码最长 60 字符")
        String password,

        String clientType,

        @Size(max = 120, message = "设备标识最长 120 字符")
        String deviceId
) {
}
