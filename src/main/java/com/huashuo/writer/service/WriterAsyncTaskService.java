package com.huashuo.writer.service;

import com.huashuo.task.vo.TaskItem;
import com.huashuo.writer.dto.RewriteDTO;
import com.huashuo.writer.pojo.DouyinVideoParseRequest;
import com.huashuo.writer.pojo.DouyinVideoTranscriptRequest;

public interface WriterAsyncTaskService {

    default TaskItem createVideoScriptAnalyzeTask(String url, String traceId, Long ownerUserId) {
        return createVideoScriptAnalyzeTask(url, traceId, ownerUserId, null, null);
    }

    default TaskItem createVideoScriptUrlAnalyzeTask(String url, String traceId, Long ownerUserId) {
        return createVideoScriptUrlAnalyzeTask(url, traceId, ownerUserId, null, null);
    }

    TaskItem createVideoScriptAnalyzeTask(String url, String traceId, Long ownerUserId,
                                          Long projectId, String idempotencyKey);

    TaskItem createVideoScriptUrlAnalyzeTask(String url, String traceId, Long ownerUserId,
                                             Long projectId, String idempotencyKey);

    TaskItem createDouyinParseTranscriptTask(DouyinVideoParseRequest request, String traceId, Long ownerUserId);

    TaskItem createDouyinRewriteTask(RewriteDTO request, String traceId, Long ownerUserId);

    TaskItem createDouyinTranscriptTask(DouyinVideoTranscriptRequest request, String traceId, Long ownerUserId);
}
