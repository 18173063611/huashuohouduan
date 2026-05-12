package com.huashuo.admin.service.impl;

import com.huashuo.admin.config.AdminAccessProperties;
import com.huashuo.admin.service.AdminAccessService;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.user.entity.UserAccountEntity;
import com.huashuo.user.mapper.UserAccountMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AdminAccessServiceImpl implements AdminAccessService {

    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_USER = "USER";

    private final AdminAccessProperties properties;
    private final UserAccountMapper userAccountMapper;

    public AdminAccessServiceImpl(AdminAccessProperties properties, UserAccountMapper userAccountMapper) {
        this.properties = properties;
        this.userAccountMapper = userAccountMapper;
    }

    @Override
    public boolean isAdmin(Long userId) {
        if (userId == null) {
            return false;
        }
        UserAccountEntity user = userAccountMapper.selectById(userId);
        if (user == null || user.getDeleted() != null && user.getDeleted() == 1) {
            return false;
        }
        if (!"ENABLED".equalsIgnoreCase(user.getStatus())) {
            return false;
        }
        return ROLE_ADMIN.equalsIgnoreCase(user.getRole()) || properties.getUserIds().contains(userId)
                || isAdminUsername(user.getUsername());
    }

    @Override
    public String roleOf(Long userId, String username) {
        if (isAdminByIdentity(userId, username)) {
            return ROLE_ADMIN;
        }
        UserAccountEntity user = userId == null ? null : userAccountMapper.selectById(userId);
        if (user != null && StringUtils.hasText(user.getRole())) {
            return user.getRole().trim().toUpperCase();
        }
        return isAdminByIdentity(userId, username) ? ROLE_ADMIN : ROLE_USER;
    }

    @Override
    public void requireAdmin(Long userId) {
        if (!isAdmin(userId)) {
            throw new BusinessException(40300, "需要管理员权限");
        }
    }

    private boolean isAdminByIdentity(Long userId, String username) {
        if (userId != null && properties.getUserIds().contains(userId)) {
            return true;
        }
        return isAdminUsername(username);
    }

    private boolean isAdminUsername(String username) {
        if (!StringUtils.hasText(username)) {
            return false;
        }
        String normalized = username.trim();
        return properties.getUsernames().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .anyMatch(item -> item.equalsIgnoreCase(normalized));
    }
}
