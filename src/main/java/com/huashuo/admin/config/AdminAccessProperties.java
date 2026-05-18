package com.huashuo.admin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "huashuo.admin")
public class AdminAccessProperties {

    /**
     * 临时管理员白名单：在 user_account 表正式增加 role 字段前，用配置兜住管理员鉴权。
     */
    private List<Long> userIds = new ArrayList<>();

    private List<String> usernames = new ArrayList<>();

    /**
     * 内置管理员用户名（与 HUASHUO_ADMIN_USERNAME 一致）；用于启动期建号及内置保护逻辑。
     */
    private String username = "admin";

    /**
     * 明文仅用于应用启动期写入 BCrypt，禁止记录到日志。
     */
    private String password = "";

    /**
     * 为 true 时，在已存在该管理员账号的情况下仍覆盖 password_hash（须配置 password）。
     */
    private boolean forceReset = false;

    public List<Long> getUserIds() {
        return userIds;
    }

    public void setUserIds(List<Long> userIds) {
        this.userIds = userIds == null ? new ArrayList<>() : userIds;
    }

    public List<String> getUsernames() {
        return usernames;
    }

    public void setUsernames(List<String> usernames) {
        this.usernames = usernames == null ? new ArrayList<>() : usernames;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password == null ? "" : password;
    }

    public boolean isForceReset() {
        return forceReset;
    }

    public void setForceReset(boolean forceReset) {
        this.forceReset = forceReset;
    }
}
