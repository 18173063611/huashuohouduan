package com.huashuo.writer.service;

import com.huashuo.writer.VO.ScriptVO;

import java.util.List;

/**
 * 视频理解 / 分镜解析：调用火山方舟 doubao-seed-2-0-lite 多模态模型。
 * <p>带任务上下文的重载会先创建本地 task 并按 {@code ai_billing_step_config}
 * （task_type = VIDEO_PARSE）预扣积分，再执行原 Ark 调用与 TOS 流水转储逻辑；
 * 默认重载保留旧契约用于内部复用与非用户态调用。</p>
 */
public interface VideoScriptService {

    default List<ScriptVO> scriptAnalyze(String url) {
        return scriptAnalyze(url, null, null, null, null);
    }

    /**
     * @param url             公网视频地址，由模型直接读取
     * @param ownerUserId     发起用户；null 视作匿名（不扣费）
     * @param projectId       归属项目；null 视为跨项目任务
     * @param traceId         链路 ID，写入 task.traceId
     * @param idempotencyKey  HTTP 幂等键
     */
    List<ScriptVO> scriptAnalyze(String url, Long ownerUserId, Long projectId, String traceId, String idempotencyKey);

    default List<ScriptVO> scriptAnalyzeByUrl(String url) {
        return scriptAnalyzeByUrl(url, null, null, null, null);
    }

    /**
     * 抖音 URL 入口：先解析为可被模型识别的 TOS 视频地址，再调用 {@link #scriptAnalyze}。
     * 整个流程视为一次 VIDEO_PARSE 任务，仅创建并扣费一次（避免重复扣费）。
     */
    List<ScriptVO> scriptAnalyzeByUrl(String url, Long ownerUserId, Long projectId, String traceId, String idempotencyKey);
}
