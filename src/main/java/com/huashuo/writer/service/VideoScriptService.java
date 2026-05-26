package com.huashuo.writer.service;

import com.huashuo.writer.vo.ScriptVO;

import java.util.List;

/**
 * 视频理解 / 分镜解析：调用火山方舟 doubao-seed-2-0-lite 多模态模型。
 * <p>带 {@code ownerUserId} 的 {@link #scriptAnalyze(String, Long, Long, String, String)} /
 * {@link #scriptAnalyzeByUrl(String, Long, Long, String, String)} 会先创建本地 {@code VIDEO_PARSE} 任务并预扣，
 * 适用于「独立视频理解」入口；异步队列中的 {@code VIDEO_SCRIPT_*} 父任务已预扣时，请使用
 * {@link #executeScriptAnalyzeForParentTask(String, String)}，不再创建子任务、不二次预扣。</p>
 */
public interface VideoScriptService {

    /**
     * 已由外层 {@code VIDEO_SCRIPT_ANALYZE} / {@code VIDEO_SCRIPT_URL_ANALYZE} 任务预扣时使用：
     * 仅执行 Ark / TOS 解析链路，不创建 {@code VIDEO_PARSE} 子任务。
     *
     * @param url             公网可访问或已发布到 TOS 的视频地址
     * @param parentTaskType  {@link com.huashuo.task.enums.TaskTypeCode#VIDEO_SCRIPT_ANALYZE} 或
     *                        {@link com.huashuo.task.enums.TaskTypeCode#VIDEO_SCRIPT_URL_ANALYZE}
     */
    List<ScriptVO> executeScriptAnalyzeForParentTask(String url, String parentTaskType);

    List<ScriptVO> executeScriptAnalyzeForParentTask(String url, String parentTaskType, String platform);

    /**
     * @param url             公网视频地址，由模型直接读取
     * @param ownerUserId     发起用户；null 视作匿名（不扣费）
     * @param projectId       归属项目；null 视为跨项目任务
     * @param traceId         链路 ID，写入 task.traceId
     * @param idempotencyKey  HTTP 幂等键
     */
    List<ScriptVO> scriptAnalyze(String url, Long ownerUserId, Long projectId, String traceId, String idempotencyKey);

    /**
     * 抖音 URL 入口：先解析为可被模型识别的 TOS 视频地址，再调用 Ark。
     * 带 {@code ownerUserId} 时会创建 {@code VIDEO_PARSE} 任务并预扣（独立入口用）。
     */
    List<ScriptVO> scriptAnalyzeByUrl(String url, Long ownerUserId, Long projectId, String traceId, String idempotencyKey);
}
