package com.huashuo.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserRegisterRequest(
        @NotBlank(message = "用户名不能为空")
        @Size(max = 60, message = "用户名最长 60 字符")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(min = 6, max = 60, message = "密码长度需在 6-60 字符")
        String password,

        @Size(max = 80, message = "展示名最长 80 字符")
        String displayName
) {
}

