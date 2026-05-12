package com.huashuo.task.mq;

public final class AiTaskQueueNames {

    private AiTaskQueueNames() {
    }

    public static final String EXCHANGE = "ai.task.exchange";
    public static final String QUEUE = "ai.task.queue";
    public static final String ROUTING_KEY = "ai.task.submit";

    public static final String DLX_EXCHANGE = "ai.task.dlx";
    public static final String RETRY_QUEUE = "ai.task.retry.queue";
    public static final String RETRY_ROUTING_KEY = "ai.task.retry";
    public static final String DEAD_QUEUE = "ai.task.dead.queue";
    public static final String DEAD_ROUTING_KEY = "ai.task.dead";

    /** 重试队列消息存活时间：到期后回主队列重新消费，相当于延迟重投 30 秒。 */
    public static final int RETRY_TTL_MILLIS = 30_000;

    /** 单条消息自动重试上限：达到后投递到死信队列并把任务标记为 FAILED。 */
    public static final int MAX_AUTO_RETRY = 3;
}
