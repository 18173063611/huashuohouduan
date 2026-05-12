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
    private List<Long> userIds = new ArrayList<>(List.of(1L));

    private List<String> usernames = new ArrayList<>();

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
}
