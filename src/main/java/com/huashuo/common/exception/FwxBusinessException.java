package com.huashuo.common.exception;

public class FwxBusinessException extends RuntimeException {

    private final int code;

    public FwxBusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
