package com.huashuo.common.response;

/**
 * 统一响应对象：所有 Controller 都返回该结构，确保前端能稳定读取 code、message、data 和 traceId。
 */
public record ApiResponse<T>(
        int code,
        String message,
        T data,
        String traceId
) {
    public static <T> ApiResponse<T> success(T data, String traceId) {
        return new ApiResponse<>(0, "success", data, traceId);
    }

    public static <T> ApiResponse<T> failure(int code, String message, String traceId) {
        return new ApiResponse<>(code, message, null, traceId);
    }
}
