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
    public static final String AVATAR_GENERATE = "AVATAR_GENERATE";
    public static final String VIDEO_SCRIPT_ANALYZE = "VIDEO_SCRIPT_ANALYZE";
    public static final String VIDEO_SCRIPT_URL_ANALYZE = "VIDEO_SCRIPT_URL_ANALYZE";
    public static final String DOUYIN_REWRITE = "DOUYIN_REWRITE";
    public static final String DOUYIN_TRANSCRIPT = "DOUYIN_TRANSCRIPT";
    public static final String SEEDANCE_TEXT_VIDEO = "SEEDANCE_TEXT_VIDEO";
    public static final String SEEDANCE_FIRST_FRAME_VIDEO = "SEEDANCE_FIRST_FRAME_VIDEO";
    public static final String SEEDANCE_FIRST_LAST_FRAME_VIDEO = "SEEDANCE_FIRST_LAST_FRAME_VIDEO";
    public static final String SEEDANCE_REFERENCE_VIDEO = "SEEDANCE_REFERENCE_VIDEO";

    /** 爆款对标：抖音链接解析 + ASR 转写（SSE 流程） */
    public static final String DOUYIN_PARSE_TRANSCRIPT = "DOUYIN_PARSE_TRANSCRIPT";

    /** Vidu 数字人视频生成 */
    public static final String DIGITAL_HUMAN_GENERATE = "DIGITAL_HUMAN_GENERATE";

    /** 音色试听音频生成（首次会回写 voice_profile.sample_url 缓存） */
    public static final String VOICE_SAMPLE = "VOICE_SAMPLE";
}
