package com.huashuo.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_account")
/**
 * 用户账号实体：用于注册登录与用户中心展示（MVP：仅 username + passwordHash）。
 */
public class UserAccountEntity {

    @TableId(value = "user_id", type = IdType.AUTO)
    private Long userId;

    private String username;

    private String passwordHash;

    private String displayName;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}

