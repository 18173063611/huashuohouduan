package com.huashuo.user.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.huashuo.admin.service.AdminAccessService;
import com.huashuo.user.entity.UserAccountEntity;
import com.huashuo.user.mapper.UserAccountMapper;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserAccountMapper userAccountMapper;
    private final AdminAccessService adminAccessService;

    public CustomUserDetailsService(UserAccountMapper userAccountMapper,
                                    AdminAccessService adminAccessService) {
        this.userAccountMapper = userAccountMapper;
        this.adminAccessService = adminAccessService;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        if (!StringUtils.hasText(username)) {
            throw new UsernameNotFoundException("User not found");
        }
        LambdaQueryWrapper<UserAccountEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserAccountEntity::getUsername, username.trim())
                .eq(UserAccountEntity::getDeleted, 0)
                .last("limit 1");
        UserAccountEntity user = userAccountMapper.selectOne(wrapper);
        if (user == null) {
            throw new UsernameNotFoundException("User not found");
        }
        return toDetails(user);
    }

    public CustomUserDetails loadById(Long userId) {
        if (userId == null) {
            throw new UsernameNotFoundException("User not found");
        }
        UserAccountEntity user = userAccountMapper.selectById(userId);
        if (user == null || user.getDeleted() != null && user.getDeleted() == 1) {
            throw new UsernameNotFoundException("User not found");
        }
        return toDetails(user);
    }

    private CustomUserDetails toDetails(UserAccountEntity user) {
        String role = adminAccessService.roleOf(user.getUserId(), user.getUsername());
        return new CustomUserDetails(
                user.getUserId(),
                user.getUsername(),
                user.getPasswordHash(),
                user.getDisplayName(),
                role,
                user.getStatus()
        );
    }
}
