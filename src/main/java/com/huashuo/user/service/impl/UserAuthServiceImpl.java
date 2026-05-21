package com.huashuo.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.huashuo.admin.service.AdminAccessService;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.user.entity.ActivateCodeEntity;
import com.huashuo.user.entity.UserAccountEntity;
import com.huashuo.user.entity.UserCreditAccountEntity;
import com.huashuo.user.entity.UserSessionEntity;
import com.huashuo.user.mapper.ActivateCodeMapper;
import com.huashuo.user.mapper.UserAccountMapper;
import com.huashuo.user.mapper.UserCreditAccountMapper;
import com.huashuo.user.mapper.UserSessionMapper;
import com.huashuo.user.service.UserAuthService;
import com.huashuo.user.util.AuthHeaderParser;
import com.huashuo.user.vo.UserLoginResponse;
import com.huashuo.user.vo.UserMeResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
    private static final String ROLE_USER = "USER";
    private static final String STATUS_ENABLED = "ENABLED";
    private static final long REGISTER_REWARD_BALANCE = 1000L;

    private final UserAccountMapper userAccountMapper;
    private final UserSessionMapper userSessionMapper;
    private final UserCreditAccountMapper userCreditAccountMapper;
    private final ActivateCodeMapper activateCodeMapper;
    private final AdminAccessService adminAccessService;
    private final BCryptPasswordEncoder passwordEncoder;

    public UserAuthServiceImpl(UserAccountMapper userAccountMapper, UserSessionMapper userSessionMapper,
                               UserCreditAccountMapper userCreditAccountMapper,
                               ActivateCodeMapper activateCodeMapper,
                               AdminAccessService adminAccessService) {
        this.userAccountMapper = userAccountMapper;
        this.userSessionMapper = userSessionMapper;
        this.userCreditAccountMapper = userCreditAccountMapper;
        this.activateCodeMapper = activateCodeMapper;
        this.adminAccessService = adminAccessService;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    @Override
    @Transactional
    public UserLoginResponse register(String username, String password, String displayName,String key, String traceId) {
        String u = normalizeUsername(username);
        if (!StringUtils.hasText(password) || password.trim().length() < 6) {
            throw new BusinessException(40000, "密码长度至少 6 位");
        }
        if (existsUsername(u)) {
            throw new BusinessException(40900, "用户名已存在");
        }
        consumeActivateCode(key);

        LocalDateTime now = LocalDateTime.now();
        UserAccountEntity entity = new UserAccountEntity();
        entity.setUsername(u);
        entity.setPasswordHash(passwordEncoder.encode(password.trim()));
        entity.setDisplayName(StringUtils.hasText(displayName) ? displayName.trim() : u);
        entity.setRole(ROLE_USER);
        entity.setStatus(STATUS_ENABLED);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        userAccountMapper.insert(entity);

        UserAccountEntity loaded = userAccountMapper.selectById(entity.getUserId());
        if (loaded == null) {
            throw new BusinessException(50000, "Failed to load user after register");
        }
        createInitialCreditAccount(loaded.getUserId(), REGISTER_REWARD_BALANCE);
        return createSession(loaded);
    }

    private void consumeActivateCode(String key) {
        if (!StringUtils.hasText(key)) {
            throw new BusinessException(40000, "目前注册需要激活码，请联系管理员获取");
        }
        String trimmed = key.trim();
        LambdaQueryWrapper<ActivateCodeEntity> w = new LambdaQueryWrapper<>();
        w.eq(ActivateCodeEntity::getKey, trimmed)
                .eq(ActivateCodeEntity::getStatus, ActivateCodeEntity.STATUS_UNUSED)
                .last("limit 1");
        ActivateCodeEntity activateCode = activateCodeMapper.selectOne(w);
        if (activateCode == null) {
            throw new BusinessException(40000, "激活码无效或已被使用");
        }
        LambdaUpdateWrapper<ActivateCodeEntity> uw = new LambdaUpdateWrapper<>();
        uw.eq(ActivateCodeEntity::getId, activateCode.getId())
                .eq(ActivateCodeEntity::getStatus, ActivateCodeEntity.STATUS_UNUSED)
                .set(ActivateCodeEntity::getStatus, ActivateCodeEntity.STATUS_USED);
        int affected = activateCodeMapper.update(null, uw);
        if (affected == 0) {
            throw new BusinessException(40000, "激活码无效或已被使用");
        }
    }

    private UserCreditAccountEntity createInitialCreditAccount(Long userId, long initialBalance) {
        LocalDateTime now = LocalDateTime.now();
        UserCreditAccountEntity created = new UserCreditAccountEntity();
        created.setUserId(userId);
        created.setBalance(initialBalance);
        created.setFrozenBalance(0L);
        created.setTotalRecharged(initialBalance);
        created.setTotalConsumed(0L);
        created.setCreatedAt(now);
        created.setUpdatedAt(now);
        userCreditAccountMapper.insert(created);
        return created;
    }

    @Override
    public UserLoginResponse login(String username, String password, String traceId) {
        String u = normalizeUsername(username);
        UserAccountEntity user = findByUsername(u);
        if (user == null) {
            throw new BusinessException(40100, "用户名或密码错误");
        }
        assertEnabled(user);
        if (!passwordEncoder.matches(password == null ? "" : password.trim(), user.getPasswordHash())) {
            throw new BusinessException(40100, "用户名或密码错误");
        }
        user.setLastLoginAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userAccountMapper.updateById(user);
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
        assertEnabled(user);
        UserCreditAccountEntity credit = ensureCreditAccount(user.getUserId());
        return new UserMeResponse(
                user.getUserId(),
                user.getUsername(),
                user.getDisplayName(),
                adminAccessService.roleOf(user.getUserId(), user.getUsername()),
                user.getStatus(),
                credit.getBalance(),
                credit.getFrozenBalance(),
                credit.getTotalConsumed()
        );
    }

    @Override
    public long requireUserId(String authorization, String xAuthToken) {
        String token = AuthHeaderParser.resolveBearer(authorization, xAuthToken);
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(40100, "未登录或登录已过期");
        }
        UserSessionEntity session = requireValidSession(token);
        UserAccountEntity user = userAccountMapper.selectById(session.getUserId());
        if (user == null || user.getDeleted() != null && user.getDeleted() == 1) {
            throw new BusinessException(40100, "Login expired");
        }
        assertEnabled(user);
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
        if (!STATUS_ENABLED.equalsIgnoreCase(user.getStatus())) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(user.getUserId());
    }

    private UserLoginResponse createSession(UserAccountEntity user) {
        UserCreditAccountEntity creditAccount = ensureCreditAccount(user.getUserId());
        String token = generateToken();
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(SESSION_DAYS);

        UserSessionEntity session = new UserSessionEntity();
        session.setUserId(user.getUserId());
        session.setToken(token);
        session.setExpiresAt(expiresAt);
        userSessionMapper.insert(session);

        return new UserLoginResponse(
                user.getUserId(),
                user.getUsername(),
                user.getDisplayName(),
                adminAccessService.roleOf(user.getUserId(), user.getUsername()),
                user.getStatus(),
                creditAccount.getBalance(),
                token,
                expiresAt
        );
    }

    private UserSessionEntity requireValidSession(String token) {
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(40100, "未登录或登录已过期");
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

    private void assertEnabled(UserAccountEntity user) {
        if (!STATUS_ENABLED.equalsIgnoreCase(user.getStatus())) {
            throw new BusinessException(40300, "账号已被禁用或锁定");
        }
    }

    private UserCreditAccountEntity ensureCreditAccount(Long userId) {
        LambdaQueryWrapper<UserCreditAccountEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserCreditAccountEntity::getUserId, userId)
                .eq(UserCreditAccountEntity::getDeleted, 0)
                .last("limit 1");
        UserCreditAccountEntity existing = userCreditAccountMapper.selectOne(wrapper);
        if (existing != null) {
            return existing;
        }

        LocalDateTime now = LocalDateTime.now();
        UserCreditAccountEntity created = new UserCreditAccountEntity();
        created.setUserId(userId);
        created.setBalance(0L);
        created.setFrozenBalance(0L);
        created.setTotalRecharged(0L);
        created.setTotalConsumed(0L);
        created.setCreatedAt(now);
        created.setUpdatedAt(now);
        userCreditAccountMapper.insert(created);
        return created;
    }
}
