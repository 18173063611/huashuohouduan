package com.huashuo.user.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.user.entity.UserAccountEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class JwtTokenProvider {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final ObjectMapper objectMapper;
    private final byte[] secret;
    private final Duration accessTokenTtl;

    public JwtTokenProvider(
            ObjectMapper objectMapper,
            @Value("${huashuo.jwt.secret:}") String secret,
            @Value("${huashuo.jwt.access-token-ttl-seconds:7200}") long ttlSeconds
    ) {
        if (!StringUtils.hasText(secret) || secret.trim().length() < 32) {
            throw new IllegalStateException("huashuo.jwt.secret must be provided from environment/config and be at least 32 chars");
        }
        this.objectMapper = objectMapper;
        this.secret = secret.trim().getBytes(StandardCharsets.UTF_8);
        this.accessTokenTtl = Duration.ofSeconds(Math.max(60, ttlSeconds));
    }

    public String generateAccessToken(UserAccountEntity user, String role, AuthClientType clientType,
                                      String sessionId, String jti, long tokenVersion) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(accessTokenTtl);
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", String.valueOf(user.getUserId()));
        payload.put("userId", user.getUserId());
        payload.put("username", user.getUsername());
        payload.put("role", role);
        payload.put("clientType", clientType.name());
        payload.put("sessionId", sessionId);
        payload.put("tokenVersion", tokenVersion);
        payload.put("jti", jti);
        payload.put("iat", now.getEpochSecond());
        payload.put("exp", expiresAt.getEpochSecond());

        String encodedHeader = encodeJson(header);
        String encodedPayload = encodeJson(payload);
        String signingInput = encodedHeader + "." + encodedPayload;
        return signingInput + "." + sign(signingInput);
    }

    public JwtClaims parseAndValidate(String token) {
        if (!StringUtils.hasText(token)) {
            throw JwtAuthException.invalid();
        }
        String[] parts = token.trim().split("\\.");
        if (parts.length != 3) {
            throw JwtAuthException.invalid();
        }
        String signingInput = parts[0] + "." + parts[1];
        if (!MessageDigest.isEqual(sign(signingInput).getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8))) {
            throw JwtAuthException.invalid();
        }
        Map<String, Object> payload = decodeJson(parts[1]);
        Instant expiresAt = Instant.ofEpochSecond(number(payload.get("exp")));
        if (!expiresAt.isAfter(Instant.now())) {
            throw JwtAuthException.expired();
        }
        return new JwtClaims(
                number(payload.get("userId")),
                string(payload.get("username")),
                string(payload.get("role")),
                string(payload.get("clientType")),
                string(payload.get("sessionId")),
                number(payload.get("tokenVersion")),
                string(payload.get("jti")),
                Instant.ofEpochSecond(number(payload.get("iat"))),
                expiresAt
        );
    }

    public Duration accessTokenTtl() {
        return accessTokenTtl;
    }

    private String encodeJson(Map<String, Object> value) {
        try {
            return base64Url(objectMapper.writeValueAsBytes(value));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to encode JWT", ex);
        }
    }

    private Map<String, Object> decodeJson(String value) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(value);
            return objectMapper.readValue(bytes, new TypeReference<>() {
            });
        } catch (Exception ex) {
            throw JwtAuthException.invalid();
        }
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return base64Url(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign JWT", ex);
        }
    }

    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ex) {
            throw JwtAuthException.invalid();
        }
    }
}
