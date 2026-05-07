package com.huashuo.task.enums;

/**
 * 任务状态常量：统一约束任务只能在排队、运行、成功、失败、可重试这五类状态中流转。
 */
public final class TaskStatusCode {

    private TaskStatusCode() {
    }

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String RETRYABLE = "RETRYABLE";

    /** 用户取消或系统终止后的终态 */
    public static final String CANCELED = "CANCELED";
}
