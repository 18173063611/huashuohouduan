package com.huashuo.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.user.entity.UserFeaturePermissionEntity;
import com.huashuo.user.mapper.UserFeaturePermissionMapper;
import com.huashuo.user.service.UserFeaturePermissionService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

@Service
public class UserFeaturePermissionServiceImpl implements UserFeaturePermissionService {

    private final UserFeaturePermissionMapper permissionMapper;

    public UserFeaturePermissionServiceImpl(UserFeaturePermissionMapper permissionMapper) {
        this.permissionMapper = permissionMapper;
    }

    @Override
    public List<String> listPermissionCodes(Long userId) {
        if (userId == null) {
            return List.of();
        }
        LambdaQueryWrapper<UserFeaturePermissionEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserFeaturePermissionEntity::getUserId, userId)
                .eq(UserFeaturePermissionEntity::getEnabled, 1)
                .eq(UserFeaturePermissionEntity::getDeleted, 0)
                .orderByAsc(UserFeaturePermissionEntity::getPermissionCode);
        return permissionMapper.selectList(wrapper).stream()
                .map(UserFeaturePermissionEntity::getPermissionCode)
                .filter(StringUtils::hasText)
                .map(code -> code.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    @Override
    public boolean hasPermission(Long userId, String permissionCode) {
        if (userId == null || !StringUtils.hasText(permissionCode)) {
            return false;
        }
        String normalizedCode = permissionCode.trim().toUpperCase(Locale.ROOT);
        LambdaQueryWrapper<UserFeaturePermissionEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserFeaturePermissionEntity::getUserId, userId)
                .eq(UserFeaturePermissionEntity::getPermissionCode, normalizedCode)
                .eq(UserFeaturePermissionEntity::getEnabled, 1)
                .eq(UserFeaturePermissionEntity::getDeleted, 0)
                .last("limit 1");
        return permissionMapper.selectOne(wrapper) != null;
    }

    @Override
    public void assertPetCreationAccess(Long userId) {
        if (!hasPermission(userId, PET_CREATION_ACCESS)) {
            throw new BusinessException(40300, "PET_CREATION_ACCESS_REQUIRED");
        }
    }
}
