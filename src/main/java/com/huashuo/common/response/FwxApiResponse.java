package com.huashuo.common.response;

public record FwxApiResponse<T>(
        int code,
        String message,
        T data,
        String traceId
) {
    public static <T> FwxApiResponse<T> success(T data, String traceId) {
        return new FwxApiResponse<>(0, "success", data, traceId);
    }

    public static <T> FwxApiResponse<T> failure(int code, String message, String traceId) {
        return new FwxApiResponse<>(code, message, null, traceId);
    }
}
