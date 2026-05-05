package com.huashuo.avatar.dto;

import jakarta.validation.constraints.Size;

public record AvatarUpdateRequest(
        @Size(max = 80) String avatarName,
        Boolean defaultAvatar
) {
}
