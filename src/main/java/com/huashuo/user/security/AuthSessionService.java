package com.huashuo.user.security;

import com.huashuo.common.exception.BusinessException;
import com.huashuo.user.entity.UserAccountEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AuthSessionService {

    private static final Logger log = LoggerFactory.getLogger(AuthSessionService.class);

    private static final String SESSION_PREFIX = "auth:session:";
    private static final String USER_SESSIONS_PREFIX = "auth:user:";
    private static final String BLACKLIST_PREFIX = "auth:blacklist:";
    private static final String TOKEN_VERSION_SUFFIX = ":tokenVersion";
    private static final int USER_WEB_MAX_SESSIONS = 3;
    private static final int ADMIN_WEB_MAX_SESSIONS = 1;
    private static final long REDIS_WARNING_INTERVAL_MILLIS = 30_000L;

    private final StringRedisTemplate redisTemplate;
    private final JwtTokenProvider jwtTokenProvider;
    private final boolean redisRequired;
    private final boolean statelessFallbackEnabled;
    private final long redisCircuitBreakerMillis;
    private volatile long lastRedisWarningAt;
    private volatile long redisDisabledUntil;

    public AuthSessionService(
            StringRedisTemplate redisTemplate,
            JwtTokenProvider jwtTokenProvider,
            @Value("${huashuo.auth.session.redis-required:true}") boolean redisRequired,
            @Value("${huashuo.auth.session.stateless-fallback-enabled:false}") boolean statelessFallbackEnabled,
            @Value("${huashuo.auth.session.redis-circuit-breaker-seconds:15}") long redisCircuitBreakerSeconds
    ) {
        this.redisTemplate = redisTemplate;
        this.jwtTokenProvider = jwtTokenProvider;
        this.redisRequired = redisRequired;
        this.statelessFallbackEnabled = statelessFallbackEnabled;
        this.redisCircuitBreakerMillis = Math.max(1L, redisCircuitBreakerSeconds) * 1000L;
    }

    public CreatedSession createSession(UserAccountEntity user, String role, AuthClientType clientType, String deviceId) {
        String sessionId = UUID.randomUUID().toString();
        String jti = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(jwtTokenProvider.accessTokenTtl());
        long tokenVersion = currentTokenVersionOrFallback(user.getUserId(), "createSession");
        String accessToken = jwtTokenProvider.generateAccessToken(user, role, clientType, sessionId, jti, tokenVersion);

        Map<String, String> session = Map.ofEntries(
                Map.entry("userId", String.valueOf(user.getUserId())),
                Map.entry("username", nullToEmpty(user.getUsername())),
                Map.entry("role", nullToEmpty(role)),
                Map.entry("clientType", clientType.name()),
                Map.entry("deviceId", StringUtils.hasText(deviceId) ? deviceId.trim() : sessionId),
                Map.entry("loginTime", String.valueOf(now.toEpochMilli())),
                Map.entry("lastActiveAt", String.valueOf(now.toEpochMilli())),
                Map.entry("tokenVersion", String.valueOf(tokenVersion)),
                Map.entry("revoked", "false"),
                Map.entry("jti", jti),
                Map.entry("expiresAt", String.valueOf(expiresAt.toEpochMilli()))
        );
        try {
            if (redisTemporarilyDisabled()) {
                warnRedisFallback("createSession:circuit-open", null);
                return new CreatedSession(accessToken, sessionId, jti, tokenVersion, expiresAt);
            }
            String sessionKey = sessionKey(sessionId);
            redisTemplate.opsForHash().putAll(sessionKey, session);
            redisTemplate.expire(sessionKey, sessionTtl());
            String userSessionsKey = userSessionsKey(user.getUserId(), clientType.name());
            redisTemplate.opsForZSet().add(userSessionsKey, sessionId, now.toEpochMilli());
            redisTemplate.expire(userSessionsKey, sessionTtl().plusMinutes(5));
            enforceSessionLimit(user.getUserId(), clientType, sessionId);
            markRedisHealthy();
        } catch (RuntimeException ex) {
            handleRedisWriteFailure("createSession", ex);
        }

        return new CreatedSession(accessToken, sessionId, jti, tokenVersion, expiresAt);
    }

    public ValidatedSession validateAccessToken(String token) {
        JwtClaims claims = jwtTokenProvider.parseAndValidate(token);
        if (!StringUtils.hasText(claims.jti())) {
            throw JwtAuthException.revoked();
        }
        if (redisTemporarilyDisabled()) {
            return sessionFromClaims(claims);
        }
        try {
            if (isBlacklisted(claims.jti())) {
                throw JwtAuthException.revoked();
            }
            Map<Object, Object> raw = redisTemplate.opsForHash().entries(sessionKey(claims.sessionId()));
            if (raw == null || raw.isEmpty()) {
                if (allowStatelessFallback()) {
                    warnRedisFallback("validateAccessToken:missing-session", null);
                    return sessionFromClaims(claims);
                }
                throw JwtAuthException.revoked();
            }
            String revoked = value(raw, "revoked");
            if ("true".equalsIgnoreCase(revoked)) {
                throw JwtAuthException.revoked();
            }
            if (!String.valueOf(claims.userId()).equals(value(raw, "userId"))
                    || !claims.clientType().equals(value(raw, "clientType"))
                    || !claims.role().equals(value(raw, "role"))
                    || !String.valueOf(claims.tokenVersion()).equals(value(raw, "tokenVersion"))
                    || !claims.jti().equals(value(raw, "jti"))) {
                throw JwtAuthException.revoked();
            }
            if (currentTokenVersion(claims.userId()) != claims.tokenVersion()) {
                throw JwtAuthException.revoked();
            }
            long now = Instant.now().toEpochMilli();
            redisTemplate.opsForHash().put(sessionKey(claims.sessionId()), "lastActiveAt", String.valueOf(now));
            redisTemplate.opsForZSet().add(userSessionsKey(claims.userId(), claims.clientType()), claims.sessionId(), now);
            markRedisHealthy();
            return sessionFromClaims(claims);
        } catch (JwtAuthException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            if (allowStatelessFallback()) {
                openRedisCircuit();
                warnRedisFallback("validateAccessToken", ex);
                return sessionFromClaims(claims);
            }
            warnRedisFallback("validateAccessToken", ex);
            throw JwtAuthException.unavailable();
        }
    }

    public void logout(String token) {
        if (!StringUtils.hasText(token)) {
            return;
        }
        try {
            JwtClaims claims = jwtTokenProvider.parseAndValidate(token);
            revokeSession(claims.userId(), claims.clientType(), claims.sessionId());
        } catch (JwtAuthException ignored) {
            // Invalid or expired tokens have no active Redis session to revoke.
        }
    }

    public void revokeAll(Long userId, AuthClientType clientType) {
        if (userId == null || clientType == null) {
            return;
        }
        String key = userSessionsKey(userId, clientType.name());
        try {
            if (redisTemporarilyDisabled()) {
                warnRedisFallback("revokeAll:circuit-open", null);
                return;
            }
            Set<String> sessionIds = redisTemplate.opsForZSet().range(key, 0, -1);
            if (sessionIds != null) {
                for (String sessionId : sessionIds) {
                    revokeSession(userId, clientType.name(), sessionId);
                }
            }
            redisTemplate.delete(key);
            markRedisHealthy();
        } catch (RuntimeException ex) {
            handleRedisWriteFailure("revokeAll", ex);
        }
    }

    public long incrementTokenVersion(Long userId) {
        if (userId == null) {
            return 1L;
        }
        try {
            if (redisTemporarilyDisabled()) {
                warnRedisFallback("incrementTokenVersion:circuit-open", null);
                return 1L;
            }
            Long next = redisTemplate.opsForValue().increment(tokenVersionKey(userId));
            markRedisHealthy();
            return next == null ? 1L : next;
        } catch (RuntimeException ex) {
            if (allowStatelessFallback()) {
                openRedisCircuit();
                warnRedisFallback("incrementTokenVersion", ex);
                return 1L;
            }
            warnRedisFallback("incrementTokenVersion", ex);
            throw new BusinessException(50300, "AUTH_SESSION_STORE_UNAVAILABLE");
        }
    }

    private void enforceSessionLimit(Long userId, AuthClientType clientType, String currentSessionId) {
        String key = userSessionsKey(userId, clientType.name());
        int max = clientType == AuthClientType.ADMIN_WEB ? ADMIN_WEB_MAX_SESSIONS : USER_WEB_MAX_SESSIONS;
        Long count = redisTemplate.opsForZSet().zCard(key);
        while (count != null && count > max) {
            Set<String> oldest = redisTemplate.opsForZSet().range(key, 0, count);
            if (oldest == null || oldest.isEmpty()) {
                return;
            }
            String sessionId = oldest.stream()
                    .filter(candidate -> !currentSessionId.equals(candidate))
                    .findFirst()
                    .orElse(null);
            if (!StringUtils.hasText(sessionId)) {
                return;
            }
            revokeSession(userId, clientType.name(), sessionId);
            count = redisTemplate.opsForZSet().zCard(key);
        }
    }

    private void revokeSession(Long userId, String clientType, String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        try {
            if (redisTemporarilyDisabled()) {
                warnRedisFallback("revokeSession:circuit-open", null);
                return;
            }
            String key = sessionKey(sessionId);
            Map<Object, Object> raw = redisTemplate.opsForHash().entries(key);
            if (raw != null && !raw.isEmpty()) {
                String jti = value(raw, "jti");
                long expiresAt = parseLong(value(raw, "expiresAt"), Instant.now().toEpochMilli());
                blacklist(jti, expiresAt);
            }
            redisTemplate.delete(key);
            if (userId != null && StringUtils.hasText(clientType)) {
                redisTemplate.opsForZSet().remove(userSessionsKey(userId, clientType), sessionId);
            }
            markRedisHealthy();
        } catch (RuntimeException ex) {
            handleRedisWriteFailure("revokeSession", ex);
        }
    }

    private void blacklist(String jti, long expiresAtMillis) {
        if (!StringUtils.hasText(jti)) {
            return;
        }
        long ttlMillis = Math.max(1000, expiresAtMillis - Instant.now().toEpochMilli());
        redisTemplate.opsForValue().set(BLACKLIST_PREFIX + jti, "true", Duration.ofMillis(ttlMillis));
    }

    private boolean isBlacklisted(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + jti));
    }

    private long currentTokenVersion(Long userId) {
        String key = tokenVersionKey(userId);
        String value = redisTemplate.opsForValue().get(key);
        if (StringUtils.hasText(value)) {
            return parseLong(value, 1L);
        }
        redisTemplate.opsForValue().setIfAbsent(key, "1");
        return 1L;
    }

    private long currentTokenVersionOrFallback(Long userId, String action) {
        try {
            if (redisTemporarilyDisabled()) {
                warnRedisFallback(action + ":tokenVersion:circuit-open", null);
                return 1L;
            }
            long version = currentTokenVersion(userId);
            markRedisHealthy();
            return version;
        } catch (RuntimeException ex) {
            if (allowStatelessFallback()) {
                openRedisCircuit();
                warnRedisFallback(action + ":tokenVersion", ex);
                return 1L;
            }
            warnRedisFallback(action + ":tokenVersion", ex);
            throw new BusinessException(50300, "AUTH_SESSION_STORE_UNAVAILABLE");
        }
    }

    private ValidatedSession sessionFromClaims(JwtClaims claims) {
        return new ValidatedSession(
                claims.userId(),
                claims.username(),
                claims.role(),
                AuthClientType.from(claims.clientType()),
                claims.sessionId(),
                claims.jti(),
                claims.tokenVersion(),
                claims.expiresAt()
        );
    }

    private void handleRedisWriteFailure(String action, RuntimeException ex) {
        if (allowStatelessFallback()) {
            openRedisCircuit();
            warnRedisFallback(action, ex);
            return;
        }
        warnRedisFallback(action, ex);
        throw new BusinessException(50300, "AUTH_SESSION_STORE_UNAVAILABLE");
    }

    private boolean allowStatelessFallback() {
        return !redisRequired && statelessFallbackEnabled;
    }

    private boolean redisTemporarilyDisabled() {
        return allowStatelessFallback() && System.currentTimeMillis() < redisDisabledUntil;
    }

    private void openRedisCircuit() {
        redisDisabledUntil = System.currentTimeMillis() + redisCircuitBreakerMillis;
    }

    private void markRedisHealthy() {
        redisDisabledUntil = 0L;
    }

    private void warnRedisFallback(String action, RuntimeException ex) {
        long now = System.currentTimeMillis();
        if (now - lastRedisWarningAt < REDIS_WARNING_INTERVAL_MILLIS) {
            return;
        }
        lastRedisWarningAt = now;
        if (allowStatelessFallback()) {
            log.warn("Redis auth session store unavailable, using stateless JWT fallback, action={}, reason={}",
                    action, ex == null ? "missing Redis session" : ex.getMessage());
            return;
        }
        log.warn("Redis auth session store unavailable, action={}, reason={}",
                action, ex == null ? "missing Redis session" : ex.getMessage());
    }

    private Duration sessionTtl() {
        return jwtTokenProvider.accessTokenTtl().plusMinutes(5);
    }

    private String sessionKey(String sessionId) {
        return SESSION_PREFIX + sessionId;
    }

    private String userSessionsKey(Long userId, String clientType) {
        return USER_SESSIONS_PREFIX + userId + ":" + clientType + ":sessions";
    }

    private String tokenVersionKey(Long userId) {
        return USER_SESSIONS_PREFIX + userId + TOKEN_VERSION_SUFFIX;
    }

    private String value(Map<Object, Object> raw, String key) {
        Object value = raw.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (Exception ex) {
            return fallback;
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record CreatedSession(
            String accessToken,
            String sessionId,
            String jti,
            long tokenVersion,
            Instant expiresAt
    ) {
        public LocalDateTime expiresAtLocalDateTime() {
            return LocalDateTime.ofInstant(expiresAt, ZoneId.systemDefault());
        }
    }

    public record ValidatedSession(
            Long userId,
            String username,
            String role,
            AuthClientType clientType,
            String sessionId,
            String jti,
            long tokenVersion,
            Instant expiresAt
    ) {
    }
}
