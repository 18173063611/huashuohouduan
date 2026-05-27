package com.huashuo.task.mq;

public final class AiTaskQueueNames {

    private AiTaskQueueNames() {
    }

    public static final String EXCHANGE = "ai.task.exchange";
    public static final String QUEUE = "ai.task.queue";
    public static final String ROUTING_KEY = "ai.task.submit";
    public static final String TTS_GENERATE_QUEUE = "ai.tts.generate.queue";
    public static final String TTS_GENERATE_ROUTING_KEY = "ai.tts.generate";
    public static final String WRITER_QUEUE = "ai.writer.queue";
    public static final String WRITER_ROUTING_KEY = "ai.writer";
    public static final String VIDEO_GENERATE_QUEUE = "ai.video.generate.queue";
    public static final String VIDEO_GENERATE_ROUTING_KEY = "ai.video.generate";
    public static final String QUICK_RENDER_QUEUE = "ai.video.quick-render.queue";
    public static final String QUICK_RENDER_ROUTING_KEY = "ai.video.quick-render";
    public static final String AVATAR_GENERATE_QUEUE = "ai.avatar.generate.queue";
    public static final String AVATAR_GENERATE_ROUTING_KEY = "ai.avatar.generate";
    public static final String DOUYIN_PARSE_TRANSCRIPT_QUEUE = "ai.task.douyin.parse-transcript.queue";
    public static final String DOUYIN_PARSE_TRANSCRIPT_ROUTING_KEY = "ai.task.douyin.parse-transcript";

    public static final String DLX_EXCHANGE = "ai.task.dlx";
    public static final String RETRY_QUEUE = "ai.task.retry.queue";
    public static final String RETRY_ROUTING_KEY = "ai.task.retry";
    public static final String TTS_RETRY_QUEUE = "ai.tts.generate.retry.queue";
    public static final String TTS_RETRY_ROUTING_KEY = "ai.tts.generate.retry";
    public static final String WRITER_RETRY_QUEUE = "ai.writer.retry.queue";
    public static final String WRITER_RETRY_ROUTING_KEY = "ai.writer.retry";
    public static final String VIDEO_RETRY_QUEUE = "ai.video.generate.retry.queue";
    public static final String VIDEO_RETRY_ROUTING_KEY = "ai.video.generate.retry";
    public static final String QUICK_RENDER_RETRY_QUEUE = "ai.video.quick-render.retry.queue";
    public static final String QUICK_RENDER_RETRY_ROUTING_KEY = "ai.video.quick-render.retry";
    public static final String AVATAR_RETRY_QUEUE = "ai.avatar.generate.retry.queue";
    public static final String AVATAR_RETRY_ROUTING_KEY = "ai.avatar.generate.retry";
    public static final String DEAD_QUEUE = "ai.task.dead.queue";
    public static final String DEAD_ROUTING_KEY = "ai.task.dead";

    /** 重试队列消息存活时间：到期后回主队列重新消费，相当于延迟重投 30 秒。 */
    public static final int RETRY_TTL_MILLIS = 30_000;

    /** 单条消息自动重试上限：达到后投递到死信队列并把任务标记为 FAILED。 */
    public static final int MAX_AUTO_RETRY = 3;
}
