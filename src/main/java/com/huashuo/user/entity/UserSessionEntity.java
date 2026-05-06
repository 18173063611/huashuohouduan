package com.huashuo.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_session")
/**
 * 用户会话实体：token + 过期时间。MVP 阶段用于演示登录态，不引入 Spring Security。
 */
public class UserSessionEntity {

    @TableId(value = "session_id", type = IdType.AUTO)
    private Long sessionId;

    private Long userId;

    private String token;

    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}

