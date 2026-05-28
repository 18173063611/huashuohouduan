package com.huashuo.user.security;

import org.springframework.http.HttpStatus;

public class JwtAuthException extends RuntimeException {

    private final HttpStatus status;
    private final int code;

    public JwtAuthException(HttpStatus status, int code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public int code() {
        return code;
    }

    public static JwtAuthException expired() {
        return new JwtAuthException(HttpStatus.UNAUTHORIZED, 40100, "TOKEN_EXPIRED");
    }

    public static JwtAuthException invalid() {
        return new JwtAuthException(HttpStatus.UNAUTHORIZED, 40100, "TOKEN_INVALID");
    }

    public static JwtAuthException revoked() {
        return new JwtAuthException(HttpStatus.UNAUTHORIZED, 40100, "TOKEN_REVOKED");
    }

    public static JwtAuthException denied() {
        return new JwtAuthException(HttpStatus.FORBIDDEN, 40300, "PERMISSION_DENIED");
    }

    public static JwtAuthException disabled() {
        return new JwtAuthException(HttpStatus.FORBIDDEN, 40300, "ACCOUNT_DISABLED");
    }

    public static JwtAuthException unavailable() {
        return new JwtAuthException(HttpStatus.SERVICE_UNAVAILABLE, 50300, "AUTH_SESSION_STORE_UNAVAILABLE");
    }
}
