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
import com.huashuo.user.security.AuthClientType;
import com.huashuo.user.security.AuthSessionService;
import com.huashuo.user.security.CustomUserDetails;
import com.huashuo.user.service.UserAuthService;
import com.huashuo.user.util.AuthHeaderParser;
import com.huashuo.user.util.CurrentUser;
import com.huashuo.user.vo.UserLoginResponse;
import com.huashuo.user.vo.UserMeResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.OptionalLong;

@Service
public class UserAuthServiceImpl implements UserAuthService {

    private static final String ROLE_USER = "USER";
    private static final String ROLE_ADMIN = "ADMIN";
    private static final String STATUS_ENABLED = "ENABLED";
    private static final long REGISTER_REWARD_BALANCE = 1000L;

    private final UserAccountMapper userAccountMapper;
    private final UserSessionMapper userSessionMapper;
    private final UserCreditAccountMapper userCreditAccountMapper;
    private final ActivateCodeMapper activateCodeMapper;
    private final AdminAccessService adminAccessService;
    private final AuthSessionService authSessionService;
    private final PasswordEncoder passwordEncoder;

    public UserAuthServiceImpl(UserAccountMapper userAccountMapper,
                               UserSessionMapper userSessionMapper,
                               UserCreditAccountMapper userCreditAccountMapper,
                               ActivateCodeMapper activateCodeMapper,
                               AdminAccessService adminAccessService,
                               AuthSessionService authSessionService,
                               PasswordEncoder passwordEncoder) {
        this.userAccountMapper = userAccountMapper;
        this.userSessionMapper = userSessionMapper;
        this.userCreditAccountMapper = userCreditAccountMapper;
        this.activateCodeMapper = activateCodeMapper;
        this.adminAccessService = adminAccessService;
        this.authSessionService = authSessionService;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public UserLoginResponse register(String username, String password, String displayName, String key,
                                      String clientType, String deviceId, String traceId) {
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
        return createSession(loaded, AuthClientType.USER_WEB, deviceId);
    }

    @Override
    public UserLoginResponse login(String username, String password, String clientType, String deviceId, String traceId) {
        String u = normalizeUsername(username);
        UserAccountEntity user = findByUsername(u);
        if (user == null) {
            throw new BusinessException(40100, "TOKEN_INVALID");
        }
        assertEnabled(user);
        if (!passwordEncoder.matches(password == null ? "" : password.trim(), user.getPasswordHash())) {
            throw new BusinessException(40100, "TOKEN_INVALID");
        }
        AuthClientType resolvedClientType = AuthClientType.from(clientType);
        String role = adminAccessService.roleOf(user.getUserId(), user.getUsername());
        if (resolvedClientType == AuthClientType.ADMIN_WEB && !ROLE_ADMIN.equalsIgnoreCase(role)) {
            throw new BusinessException(40300, "PERMISSION_DENIED");
        }
        user.setLastLoginAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userAccountMapper.updateById(user);
        return createSession(user, resolvedClientType, deviceId);
    }

    @Override
    public void logout(String token) {
        String resolved = normalizeTokenOrNull(token);
        if (!StringUtils.hasText(resolved)) {
            return;
        }
        try {
            AuthSessionService.ValidatedSession session = authSessionService.validateAccessToken(resolved);
            markAuditSessionDeleted(session.sessionId());
        } catch (RuntimeException ignored) {
            // Redis session may already be gone; logout should still be idempotent.
        }
        authSessionService.logout(resolved);
    }

    @Override
    public UserMeResponse me(String token) {
        Long userId = currentUserIdFromSecurity();
        if (userId == null) {
            AuthSessionService.ValidatedSession session = authSessionService.validateAccessToken(normalizeToken(token));
            userId = session.userId();
        }
        return buildMeResponse(userId);
    }

    @Override
    public long requireUserId(String authorization, String xAuthToken) {
        OptionalLong current = CurrentUser.optionalUserId();
        if (current.isPresent()) {
            return current.getAsLong();
        }
        String token = AuthHeaderParser.resolveBearer(authorization, xAuthToken);
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(40100, "TOKEN_INVALID");
        }
        AuthSessionService.ValidatedSession session = authSessionService.validateAccessToken(token);
        UserAccountEntity user = userAccountMapper.selectById(session.userId());
        if (user == null || user.getDeleted() != null && user.getDeleted() == 1) {
            throw new BusinessException(40100, "TOKEN_INVALID");
        }
        assertEnabled(user);
        return user.getUserId();
    }

    @Override
    public OptionalLong resolveUserIdOptional(String authorization, String xAuthToken) {
        OptionalLong current = CurrentUser.optionalUserId();
        if (current.isPresent()) {
            return current;
        }
        String token = AuthHeaderParser.resolveBearer(authorization, xAuthToken);
        if (!StringUtils.hasText(token)) {
            return OptionalLong.empty();
        }
        try {
            AuthSessionService.ValidatedSession session = authSessionService.validateAccessToken(token);
            UserAccountEntity user = userAccountMapper.selectById(session.userId());
            if (user == null || user.getDeleted() != null && user.getDeleted() == 1) {
                return OptionalLong.empty();
            }
            if (!STATUS_ENABLED.equalsIgnoreCase(user.getStatus())) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(user.getUserId());
        } catch (RuntimeException ignored) {
            return OptionalLong.empty();
        }
    }

    private UserLoginResponse createSession(UserAccountEntity user, AuthClientType clientType, String deviceId) {
        UserCreditAccountEntity creditAccount = ensureCreditAccount(user.getUserId());
        String role = adminAccessService.roleOf(user.getUserId(), user.getUsername());
        AuthSessionService.CreatedSession created =
                authSessionService.createSession(user, role, clientType, deviceId);
        writeAuditSession(user.getUserId(), created.sessionId(), created.expiresAtLocalDateTime());
        return new UserLoginResponse(
                user.getUserId(),
                user.getUsername(),
                user.getDisplayName(),
                role,
                user.getStatus(),
                creditAccount.getBalance(),
                created.accessToken(),
                created.accessToken(),
                clientType.name(),
                created.sessionId(),
                created.expiresAtLocalDateTime()
        );
    }

    private void writeAuditSession(Long userId, String sessionId, LocalDateTime expiresAt) {
        UserSessionEntity session = new UserSessionEntity();
        session.setUserId(userId);
        session.setToken(sessionId);
        session.setExpiresAt(expiresAt);
        userSessionMapper.insert(session);
    }

    private void markAuditSessionDeleted(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        LambdaUpdateWrapper<UserSessionEntity> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(UserSessionEntity::getToken, sessionId)
                .set(UserSessionEntity::getDeleted, 1)
                .set(UserSessionEntity::getUpdatedAt, LocalDateTime.now());
        userSessionMapper.update(null, wrapper);
    }

    private UserMeResponse buildMeResponse(Long userId) {
        UserAccountEntity user = userAccountMapper.selectById(userId);
        if (user == null || user.getDeleted() != null && user.getDeleted() == 1) {
            throw new BusinessException(40100, "TOKEN_INVALID");
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

    private Long currentUserIdFromSecurity() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CustomUserDetails user) {
            return user.userId();
        }
        return null;
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
        String t = normalizeTokenOrNull(token);
        if (!StringUtils.hasText(t)) {
            throw new BusinessException(40100, "TOKEN_INVALID");
        }
        return t;
    }

    private String normalizeTokenOrNull(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        String t = token.trim();
        return t.length() < 16 ? null : t;
    }

    private void assertEnabled(UserAccountEntity user) {
        if (!STATUS_ENABLED.equalsIgnoreCase(user.getStatus())) {
            throw new BusinessException(40300, "ACCOUNT_DISABLED");
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
