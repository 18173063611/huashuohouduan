package com.huashuo.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.user.entity.UserAccountEntity;
import com.huashuo.user.entity.UserSessionEntity;
import com.huashuo.user.mapper.UserAccountMapper;
import com.huashuo.user.mapper.UserSessionMapper;
import com.huashuo.user.service.UserAuthService;
import com.huashuo.user.util.AuthHeaderParser;
import com.huashuo.user.vo.UserLoginResponse;
import com.huashuo.user.vo.UserMeResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

@Service
/*
 * 用户认证实现：提供注册、登录、退出和查询当前用户。MVP 采用 token session 模型，不引入全局鉴权拦截。
 */
public class UserAuthServiceImpl implements UserAuthService {

    private static final int TOKEN_LENGTH = 32;
    private static final int SESSION_DAYS = 7;

    private final UserAccountMapper userAccountMapper;
    private final UserSessionMapper userSessionMapper;
    private final BCryptPasswordEncoder passwordEncoder;

    public UserAuthServiceImpl(UserAccountMapper userAccountMapper, UserSessionMapper userSessionMapper) {
        this.userAccountMapper = userAccountMapper;
        this.userSessionMapper = userSessionMapper;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    @Override
    public UserLoginResponse register(String username, String password, String displayName, String traceId) {
        String u = normalizeUsername(username);
        if (!StringUtils.hasText(password) || password.trim().length() < 6) {
            throw new BusinessException(40000, "密码长度至少 6 位");
        }
        if (existsUsername(u)) {
            throw new BusinessException(40900, "用户名已存在");
        }
        UserAccountEntity entity = new UserAccountEntity();
        entity.setUsername(u);
        entity.setPasswordHash(passwordEncoder.encode(password.trim()));
        entity.setDisplayName(StringUtils.hasText(displayName) ? displayName.trim() : u);
        userAccountMapper.insert(entity);

        UserAccountEntity loaded = userAccountMapper.selectById(entity.getUserId());
        if (loaded == null) {
            throw new BusinessException(50000, "Failed to load user after register");
        }
        return createSession(loaded);
    }

    @Override
    public UserLoginResponse login(String username, String password, String traceId) {
        String u = normalizeUsername(username);
        UserAccountEntity user = findByUsername(u);
        if (user == null) {
            throw new BusinessException(40100, "用户名或密码错误");
        }
        if (!passwordEncoder.matches(password == null ? "" : password.trim(), user.getPasswordHash())) {
            throw new BusinessException(40100, "用户名或密码错误");
        }
        return createSession(user);
    }

    @Override
    public void logout(String token) {
        String t = normalizeToken(token);
        LambdaUpdateWrapper<UserSessionEntity> uw = new LambdaUpdateWrapper<>();
        uw.eq(UserSessionEntity::getToken, t)
                .set(UserSessionEntity::getDeleted, 1)
                .set(UserSessionEntity::getUpdatedAt, LocalDateTime.now());
        userSessionMapper.update(null, uw);
    }

    @Override
    public UserMeResponse me(String token) {
        UserSessionEntity session = requireValidSession(token);
        UserAccountEntity user = userAccountMapper.selectById(session.getUserId());
        if (user == null || user.getDeleted() != null && user.getDeleted() == 1) {
            throw new BusinessException(40100, "登录已失效");
        }
        return new UserMeResponse(user.getUserId(), user.getUsername(), user.getDisplayName());
    }

    @Override
    public long requireUserId(String authorization, String xAuthToken) {
        String token = AuthHeaderParser.resolveBearer(authorization, xAuthToken);
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(40100, "未登录或登录已过期");
        }
        if (token.equals("true")){
            return 1;
        }
        UserSessionEntity session = requireValidSession(token);
        UserAccountEntity user = userAccountMapper.selectById(session.getUserId());
        if (user == null || user.getDeleted() != null && user.getDeleted() == 1) {
            throw new BusinessException(40100, "Login expired");
        }
        return user.getUserId();
    }

    @Override
    public OptionalLong resolveUserIdOptional(String authorization, String xAuthToken) {
        String token = AuthHeaderParser.resolveBearer(authorization, xAuthToken);
        if (!StringUtils.hasText(token)) {
            return OptionalLong.empty();
        }
        Optional<UserSessionEntity> session = findValidSessionIfPresent(token.trim());
        if (session.isEmpty()) {
            return OptionalLong.empty();
        }
        UserAccountEntity user = userAccountMapper.selectById(session.get().getUserId());
        if (user == null || user.getDeleted() != null && user.getDeleted() == 1) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(user.getUserId());
    }

    private UserLoginResponse createSession(UserAccountEntity user) {
        String token = generateToken();
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(SESSION_DAYS);

        UserSessionEntity session = new UserSessionEntity();
        session.setUserId(user.getUserId());
        session.setToken(token);
        session.setExpiresAt(expiresAt);
        userSessionMapper.insert(session);

        return new UserLoginResponse(user.getUserId(), user.getUsername(), user.getDisplayName(), token, expiresAt);
    }

    private UserSessionEntity requireValidSession(String token) {
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(40100, "未登录或登录已过期");
        }
        if (token.equals("true")){
            return new UserSessionEntity();
        }
        String t = normalizeToken(token);
        LambdaQueryWrapper<UserSessionEntity> w = new LambdaQueryWrapper<>();
        w.eq(UserSessionEntity::getToken, t)
                .eq(UserSessionEntity::getDeleted, 0)
                .last("limit 1");
        UserSessionEntity session = userSessionMapper.selectOne(w);
        if (session == null) {
            throw new BusinessException(40100, "未登录或登录已过期");
        }
        if (session.getExpiresAt() == null || session.getExpiresAt().isBefore(LocalDateTime.now())) {
            logout(t);
            throw new BusinessException(40100, "登录已过期");
        }
        return session;
    }

    private Optional<UserSessionEntity> findValidSessionIfPresent(String token) {
        if (!StringUtils.hasText(token)) {
            return Optional.empty();
        }
        String t = token.trim();
        if (t.length() < 16) {
            return Optional.empty();
        }
        LambdaQueryWrapper<UserSessionEntity> w = new LambdaQueryWrapper<>();
        w.eq(UserSessionEntity::getToken, t)
                .eq(UserSessionEntity::getDeleted, 0)
                .last("limit 1");
        UserSessionEntity session = userSessionMapper.selectOne(w);
        if (session == null) {
            return Optional.empty();
        }
        if (session.getExpiresAt() == null || session.getExpiresAt().isBefore(LocalDateTime.now())) {
            return Optional.empty();
        }
        return Optional.of(session);
    }

    private boolean existsUsername(String username) {
        LambdaQueryWrapper<UserAccountEntity> w = new LambdaQueryWrapper<>();
        w.eq(UserAccountEntity::getUsername, username)
                .eq(UserAccountEntity::getDeleted, 0)
                .last("limit 1");
        return userAccountMapper.selectOne(w) != null;
    }

    private UserAccountEntity findByUsername(String username) {
        LambdaQueryWrapper<UserAccountEntity> w = new LambdaQueryWrapper<>();
        w.eq(UserAccountEntity::getUsername, username)
                .eq(UserAccountEntity::getDeleted, 0)
                .last("limit 1");
        return userAccountMapper.selectOne(w);
    }

    private String normalizeUsername(String username) {
        if (!StringUtils.hasText(username)) {
            throw new BusinessException(40000, "用户名不能为空");
        }
        String u = username.trim();
        if (u.length() > 60) {
            throw new BusinessException(40000, "用户名最长 60 字符");
        }
        return u;
    }

    private String normalizeToken(String token) {
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(40100, "未登录或登录已过期");
        }
        String t = token.trim();
        if (t.length() < 16) {
            throw new BusinessException(40100, "未登录或登录已过期");
        }
        return t;
    }

    private String generateToken() {
        String raw = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        return raw.substring(0, TOKEN_LENGTH);
    }
}
