package com.huashuo.writer.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.asset.service.AssetService;
import com.huashuo.asset.vo.AssetItem;
import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.common.exception.RetryableException;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import com.huashuo.writer.dto.RewriteDTO;
import com.huashuo.writer.pojo.DouyinVideoParseRequest;
import com.huashuo.writer.pojo.DouyinVideoParseResponse;
import com.huashuo.writer.pojo.DouyinVideoParseWithTranscriptEvent;
import com.huashuo.writer.pojo.DouyinVideoTranscriptRequest;
import com.huashuo.writer.pojo.WriterVO;
import com.huashuo.writer.service.WriterService;
import com.huashuo.writer.sse.DouyinParseTranscriptSseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

@Component("writerAiTaskExecutor")
public class WriterTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(WriterTaskExecutor.class);

    private final TaskService taskService;
    private final WriterService writerService;
    private final ObjectMapper objectMapper;
    private final DouyinParseTranscriptSseService sseService;
    private final AssetService assetService;

    public WriterTaskExecutor(TaskService taskService, WriterService writerService, ObjectMapper objectMapper,
                              DouyinParseTranscriptSseService sseService, AssetService assetService) {
        this.taskService = taskService;
        this.writerService = writerService;
        this.objectMapper = objectMapper;
        this.sseService = sseService;
        this.assetService = assetService;
    }

    public void run(Long taskId) {
        try {
            taskService.startTask(taskId);
        } catch (Exception e) {
            log.warn("Writer task {} cannot start: {}", taskId, e.getMessage());
            return;
        }

        String taskType = null;
        try {
            var task = taskService.getTask(taskId);
            if (StringUtils.hasText(task.traceId())) {
                MDC.put(TraceIdFilter.TRACE_ID, task.traceId());
            }
            taskType = task.taskType();

            if (TaskTypeCode.DOUYIN_PARSE_TRANSCRIPT.equals(taskType)) {
                // SSE 流程自管理失败：捕获异常后通过 SSE 推 error 事件并落库 FAILED，方法不抛出。
                parseWithTranscript(task);
                return;
            }

            Object output;
            if (TaskTypeCode.DOUYIN_REWRITE.equals(taskType)) {
                output = writerService.rewriteDouyinVideo(objectMapper.readValue(task.inputJson(), RewriteDTO.class));
            } else if (TaskTypeCode.DOUYIN_TRANSCRIPT.equals(taskType)) {
                JsonNode input = objectMapper.readTree(task.inputJson() == null ? "{}" : task.inputJson());
                output = writerService.extractDouyinVideoTranscript(
                        new DouyinVideoTranscriptRequest(input.path("playUrl").asText("")));
            } else {
                throw new BusinessException(40000, "Unsupported writer task type: " + taskType);
            }

            taskService.completeTask(taskId, objectMapper.writeValueAsString(output));
        } catch (BusinessException ex) {
            log.warn("Writer task {} ({}) failed: {}", taskId, taskType, ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            log.error("Writer task {} ({}) error", taskId, taskType, ex);
            throw ex;
        } catch (Exception ex) {
            log.error("Writer task {} ({}) error", taskId, taskType, ex);
            throw new RetryableException(ex.getMessage() == null ? "Writer task error" : ex.getMessage(), ex);
        } finally {
            MDC.remove(TraceIdFilter.TRACE_ID);
        }
    }

    private void parseWithTranscript(TaskItem task) {
        Long taskId = task.taskId();
        DouyinVideoParseResponse parseResult = null;
        try {
            DouyinVideoParseRequest request = objectMapper.readValue(task.inputJson(), DouyinVideoParseRequest.class);
            parseResult = writerService.parseDouyinVideo(request);
            log.info("parseResult: {}", parseResult == null ? null : parseResult.getPlayUrl());
            sseService.send(
                    taskId,
                    "parsed",
                    new DouyinVideoParseWithTranscriptEvent("parsed", taskId, parseResult, null),
                    "解析成功，前端可先展示 playUrl 和封面，后端继续转写"
            );

            String playUrl = parseResult == null ? null : parseResult.getPlayUrl();
            if (!StringUtils.hasText(playUrl)) {
                throw new BusinessException(50202, "TikHub parse succeeded but playUrl is empty");
            }

            sseService.send(
                    taskId,
                    "transcribing",
                    new DouyinVideoParseWithTranscriptEvent("transcribing", taskId, parseResult, null),
                    "正在转写视频文案"
            );

            WriterVO transcriptResult = writerService.extractDouyinVideoTranscript(new DouyinVideoTranscriptRequest(playUrl));

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("parseResult", parseResult);
            output.put("transcriptResult", transcriptResult);
            AssetItem asset = createBenchmarkAsset(task, parseResult, transcriptResult, output);
            output.put("resultAssetId", asset.assetId());
            output.put("previewUrl", asset.fileUrl());
            taskService.completeTask(taskId, objectMapper.writeValueAsString(output));

            sseService.send(
                    taskId,
                    "completed",
                    new DouyinVideoParseWithTranscriptEvent("completed", taskId, parseResult, transcriptResult),
                    "转写完成"
            );
            sseService.complete(taskId);
        } catch (Exception exception) {
            fail(taskId, exception);
            try {
                sseService.sendError(
                        taskId,
                        exception,
                        new DouyinVideoParseWithTranscriptEvent("error", taskId, parseResult, null)
                );
                sseService.complete(taskId);
            } catch (RuntimeException runtimeException) {
                sseService.completeWithError(taskId, runtimeException);
            }
        }
    }

    private AssetItem createBenchmarkAsset(TaskItem task, DouyinVideoParseResponse parseResult,
                                           WriterVO transcriptResult, Map<String, Object> output) throws Exception {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("taskType", TaskTypeCode.DOUYIN_PARSE_TRANSCRIPT);
        meta.put("videoId", parseResult == null ? null : parseResult.getVideoId());
        meta.put("title", parseResult == null ? null : parseResult.getTitle());
        meta.put("durationSeconds", parseResult == null ? null : parseResult.getDurationSeconds());
        meta.put("sourceEndpoint", parseResult == null ? null : parseResult.getSourceEndpoint());
        meta.put("hasTranscript", transcriptResult != null && StringUtils.hasText(transcriptResult.getOriginalText()));
        return assetService.createGeneratedJsonAsset(
                task.ownerUserId(),
                task.projectId(),
                task.taskId(),
                "douyin-benchmark-task-" + task.taskId() + ".json",
                objectMapper.writeValueAsString(output),
                "writer",
                "DOUYIN_BENCHMARK",
                objectMapper.writeValueAsString(meta)
        );
    }

    private void fail(Long taskId, Exception ex) {
        try {
            taskService.failTask(taskId, ex.getMessage() == null ? "Writer task failed" : ex.getMessage());
        } catch (Exception ignored) {
        }
    }
}
