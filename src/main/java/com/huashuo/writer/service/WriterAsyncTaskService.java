package com.huashuo.writer.service;

import com.huashuo.task.vo.TaskItem;
import com.huashuo.writer.dto.RewriteDTO;
import com.huashuo.writer.dto.VideoScriptSubmitRequest;
import com.huashuo.writer.pojo.DouyinVideoParseRequest;
import com.huashuo.writer.pojo.DouyinVideoTranscriptRequest;

public interface WriterAsyncTaskService {

    /**
     * 上传视频后的分镜解析；{@code ownerUserId} 必须为已登录用户 ID。
     */
    TaskItem createVideoScriptAnalyzeTask(VideoScriptSubmitRequest request, long ownerUserId,
                                          Long projectId, String traceId, String idempotencyKey);

    /**
     * 公网分享链接分镜解析；{@code ownerUserId} 必须为已登录用户 ID。
     */
    TaskItem createVideoScriptUrlAnalyzeTask(VideoScriptSubmitRequest request, long ownerUserId,
                                             Long projectId, String traceId, String idempotencyKey);

    default TaskItem createDouyinParseTranscriptTask(DouyinVideoParseRequest request, String traceId, Long ownerUserId) {
        return createDouyinParseTranscriptTask(request, traceId, ownerUserId, null);
    }

    TaskItem createDouyinParseTranscriptTask(DouyinVideoParseRequest request, String traceId, Long ownerUserId,
                                             String idempotencyKey);

    default TaskItem createDouyinRewriteTask(RewriteDTO request, String traceId, Long ownerUserId) {
        return createDouyinRewriteTask(request, traceId, ownerUserId, null);
    }

    TaskItem createDouyinRewriteTask(RewriteDTO request, String traceId, Long ownerUserId, String idempotencyKey);

    default TaskItem createDouyinTranscriptTask(DouyinVideoTranscriptRequest request, String traceId, Long ownerUserId) {
        return createDouyinTranscriptTask(request, traceId, ownerUserId, null);
    }

    TaskItem createDouyinTranscriptTask(DouyinVideoTranscriptRequest request, String traceId, Long ownerUserId,
                                        String idempotencyKey);
}
