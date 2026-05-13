package com.huashuo.task.enums;

/**
 * 任务类型常量：统一标识视频解析、文案改写、分镜生成、TTS 等业务任务类型。
 */
public final class TaskTypeCode {

    private TaskTypeCode() {
    }

    public static final String VIDEO_PARSE = "VIDEO_PARSE";
    public static final String SCRIPT_REWRITE = "SCRIPT_REWRITE";
    public static final String STORYBOARD_GENERATE = "STORYBOARD_GENERATE";
    public static final String TTS_GENERATE = "TTS_GENERATE";
    /** 音色试听：首次生成试听音频并持久化缓存。 */
    public static final String VOICE_SAMPLE = "VOICE_SAMPLE";
    public static final String AVATAR_GENERATE = "AVATAR_GENERATE";
    public static final String DIGITAL_HUMAN_GENERATE = "DIGITAL_HUMAN_GENERATE";

    /** 爆款对标：抖音链接解析 + ASR 转写（SSE 流程） */
    public static final String DOUYIN_PARSE_TRANSCRIPT = "DOUYIN_PARSE_TRANSCRIPT";

    // ---- Seedance 视频生成 task_type（与 ai_billing_step_config 种子一一对应） ----

    /** 文生视频：Seedance 1.5 pro（doubao-seedance-1-5-pro-251215）。 */
    public static final String TEXT_TO_VIDEO_SEEDANCE_1_5 = "TEXT_TO_VIDEO_SEEDANCE_1_5";

    /** 文生视频：Seedance 2.0 pro（doubao-seedance-2-0-pro）。 */
    public static final String TEXT_TO_VIDEO_SEEDANCE_2_0 = "TEXT_TO_VIDEO_SEEDANCE_2_0";

    /** 图生视频：Seedance 1.5 pro（首帧 / 首尾帧 / Seedance 1.0 lite i2v 参考图共享同一计费）。 */
    public static final String IMAGE_TO_VIDEO_SEEDANCE_1_5 = "IMAGE_TO_VIDEO_SEEDANCE_1_5";

    /** 图生视频：Seedance 2.0（doubao-seedance-2-0）。 */
    public static final String IMAGE_TO_VIDEO_SEEDANCE_2_0 = "IMAGE_TO_VIDEO_SEEDANCE_2_0";

    /** 图生视频：Seedance 2.0 fast（doubao-seedance-2-0-fast），用于参考图视频生成。 */
    public static final String IMAGE_TO_VIDEO_SEEDANCE_2_0_FAST = "IMAGE_TO_VIDEO_SEEDANCE_2_0_FAST";
}
