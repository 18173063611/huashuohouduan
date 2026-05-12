package com.huashuo.common.exception;

/**
 * 可重试异常：外部依赖瞬时故障、业务超时等场景抛出，由 AI 任务消费者识别后投递到 DLX 重试队列。
 */
public class RetryableException extends RuntimeException {

    public RetryableException(String message) {
        super(message);
    }

    public RetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}
