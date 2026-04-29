package com.huashuo.task.enums;

public final class TaskStatusCode {

    private TaskStatusCode() {
    }

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String RETRYABLE = "RETRYABLE";
}
