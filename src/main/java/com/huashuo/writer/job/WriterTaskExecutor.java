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
import java.util.Locale;
import java.util.Map;

@Component("writerAiTaskExecutor")
public class WriterTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(WriterTaskExecutor.class);
    private static final int EMPTY_TRANSCRIPT_CODE = 50215;
    private static final String EMPTY_TRANSCRIPT_MESSAGE = "视频里没有识别到可转写的口播文案，可以手动输入原文后继续改写";
    private static final String NO_PLAYABLE_VIDEO_MESSAGE = "已解析到笔记信息，但没有检测到可转写的视频内容。可能是图文笔记，可手动输入原文后继续改写";
    private static final String PROVIDER_PARSE_REJECTED_MESSAGE =
            "平台暂未返回可解析的视频数据，请确认视频是公开可访问的视频，并尽量复制分享内容中的完整 http(s) 链接或完整分享文案后重试";

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
                if (hasParsedMetadata(parseResult)) {
                    completeWithEmptyTranscript(task, parseResult, noPlayableVideoMessage(parseResult));
                    return;
                }
                throw new BusinessException(50202, "已解析到视频信息，但没有拿到可转写的视频地址。请确认链接为公开视频，或更换分享链接后重试");
            }

            sseService.send(
                    taskId,
                    "transcribing",
                    new DouyinVideoParseWithTranscriptEvent("transcribing", taskId, parseResult, null),
                    "正在转写视频文案"
            );

            WriterVO transcriptResult = writerService.extractDouyinVideoTranscript(parseResult);

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
            if (isEmptyTranscript(exception) && parseResult != null) {
                try {
                    completeWithEmptyTranscript(task, parseResult, EMPTY_TRANSCRIPT_MESSAGE);
                    return;
                } catch (Exception completeException) {
                    log.warn("Writer task {} empty transcript completion failed: {}", taskId, completeException.getMessage());
                    exception = completeException;
                }
            }
            if (isDirectVideoTranscriptUnavailable(exception, parseResult)) {
                try {
                    completeWithEmptyTranscript(
                            task,
                            parseResult,
                            "本地视频已上传并解析完成，但暂时未能自动转写；请手动输入原文后继续改写"
                    );
                    return;
                } catch (Exception completeException) {
                    log.warn("Writer task {} direct video fallback completion failed: {}", taskId, completeException.getMessage());
                    exception = completeException;
                }
            }
            if (isBilibiliTranscriptUnavailable(exception, parseResult)) {
                try {
                    completeWithEmptyTranscript(task, parseResult, bilibiliNoPlayableMessage());
                    return;
                } catch (Exception completeException) {
                    log.warn("Writer task {} bilibili fallback completion failed: {}", taskId, completeException.getMessage());
                    exception = completeException;
                }
            }
            Exception userFacingException = toUserFacingException(exception, parseResult);
            fail(taskId, userFacingException);
            try {
                sseService.sendError(
                        taskId,
                        userFacingException,
                        new DouyinVideoParseWithTranscriptEvent("error", taskId, parseResult, null)
                );
                sseService.complete(taskId);
            } catch (RuntimeException runtimeException) {
                sseService.completeWithError(taskId, runtimeException);
            }
        }
    }

    private boolean hasParsedMetadata(DouyinVideoParseResponse parseResult) {
        if (parseResult == null) {
            return false;
        }
        return StringUtils.hasText(parseResult.getVideoId())
                || StringUtils.hasText(parseResult.getTitle())
                || StringUtils.hasText(parseResult.getCoverUrl())
                || parseResult.getAuthor() != null;
    }

    private String noPlayableVideoMessage(DouyinVideoParseResponse parseResult) {
        return isBilibiliParseResult(parseResult) ? bilibiliNoPlayableMessage() : NO_PLAYABLE_VIDEO_MESSAGE;
    }

    private String bilibiliNoPlayableMessage() {
        return "已解析到 B 站视频信息，但暂未拿到可转写的视频地址；可手动输入原文后继续改写";
    }

    private void completeWithEmptyTranscript(TaskItem task, DouyinVideoParseResponse parseResult,
                                             String message) throws Exception {
        Long taskId = task.taskId();
        WriterVO transcriptResult = new WriterVO("", null);
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
                StringUtils.hasText(message) ? message : EMPTY_TRANSCRIPT_MESSAGE
        );
        sseService.complete(taskId);
    }

    private boolean isEmptyTranscript(Exception exception) {
        return exception instanceof BusinessException businessException
                && businessException.getCode() == EMPTY_TRANSCRIPT_CODE;
    }

    private Exception toUserFacingException(Exception exception) {
        return toUserFacingException(exception, null);
    }

    private Exception toUserFacingException(Exception exception, DouyinVideoParseResponse parseResult) {
        String message = userFacingErrorMessage(exception, parseResult);
        if (!StringUtils.hasText(message) || message.equals(exception.getMessage())) {
            return exception;
        }
        int code = exception instanceof BusinessException businessException ? businessException.getCode() : 50200;
        return new BusinessException(code, message);
    }

    private String userFacingErrorMessage(Exception exception) {
        return userFacingErrorMessage(exception, null);
    }

    private String userFacingErrorMessage(Exception exception, DouyinVideoParseResponse parseResult) {
        String message = exception == null ? null : exception.getMessage();
        if (!StringUtils.hasText(message)) {
            return "解析或转写失败，请稍后重试";
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("volcengine asr query succeeded but returned empty text")
                || (normalized.contains("asr") && normalized.contains("empty text"))) {
            return EMPTY_TRANSCRIPT_MESSAGE;
        }
        if (isDirectVideoParseResult(parseResult)
                && (normalized.contains("volcengine asr submit failed")
                || normalized.contains("volcengine asr query failed")
                || normalized.contains("source video download failed")
                || normalized.contains("asr audio preprocess failed")
                || normalized.contains("upload public base url")
                || normalized.contains("tos"))) {
            return "本地视频已上传，但转写服务暂时无法读取该视频文件。请检查 TOS 公网访问地址与桶读权限，或稍后重试";
        }
        if (normalized.contains("tikhub parse failed")
                || normalized.contains("tikhub request failed with http 400")
                || normalized.contains("hybrid error")
                || message.contains("平台解析接口拒绝了当前链接")) {
            return PROVIDER_PARSE_REJECTED_MESSAGE;
        }
        return message;
    }

    private boolean isDirectVideoParseResult(DouyinVideoParseResponse parseResult) {
        if (parseResult == null || !StringUtils.hasText(parseResult.getSourceEndpoint())) {
            return false;
        }
        return parseResult.getSourceEndpoint().startsWith("direct-");
    }

    private boolean isBilibiliParseResult(DouyinVideoParseResponse parseResult) {
        return parseResult != null
                && StringUtils.hasText(parseResult.getSourceEndpoint())
                && parseResult.getSourceEndpoint().startsWith("bilibili-");
    }

    private boolean isBilibiliTranscriptUnavailable(Exception exception, DouyinVideoParseResponse parseResult) {
        if (!isBilibiliParseResult(parseResult) || exception == null || !StringUtils.hasText(exception.getMessage())) {
            return false;
        }
        String normalized = exception.getMessage().toLowerCase(Locale.ROOT);
        return normalized.contains("http connect timed out")
                || normalized.contains("source video download failed")
                || normalized.contains("asr audio preprocess failed")
                || normalized.contains("asr audio extract failed")
                || normalized.contains("volcengine asr submit failed")
                || normalized.contains("volcengine asr query failed");
    }

    private boolean isDirectVideoTranscriptUnavailable(Exception exception, DouyinVideoParseResponse parseResult) {
        if (!isDirectVideoParseResult(parseResult) || exception == null || !StringUtils.hasText(exception.getMessage())) {
            return false;
        }
        String normalized = exception.getMessage().toLowerCase(Locale.ROOT);
        return normalized.contains("volcengine asr submit failed")
                || normalized.contains("volcengine asr query failed")
                || normalized.contains("source video download failed")
                || normalized.contains("asr audio preprocess failed")
                || normalized.contains("asr audio extract failed")
                || normalized.contains("upload public base url")
                || normalized.contains("tos");
    }

    private AssetItem createBenchmarkAsset(TaskItem task, DouyinVideoParseResponse parseResult,
                                           WriterVO transcriptResult, Map<String, Object> output) throws Exception {
        DouyinVideoParseRequest request = readParseRequest(task.inputJson());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("taskType", TaskTypeCode.DOUYIN_PARSE_TRANSCRIPT);
        meta.put("videoId", parseResult == null ? null : parseResult.getVideoId());
        meta.put("title", parseResult == null ? null : parseResult.getTitle());
        meta.put("sourceTitle", firstText(parseResult == null ? null : parseResult.getTitle(), request == null ? null : request.getTitle()));
        meta.put("sourceUrl", firstText(request == null ? null : request.getUrl(), parseResult == null ? null : parseResult.getPlayUrl()));
        meta.put("playUrl", parseResult == null ? null : parseResult.getPlayUrl());
        meta.put("coverUrl", parseResult == null ? null : parseResult.getCoverUrl());
        meta.put("durationSeconds", parseResult == null ? null : parseResult.getDurationSeconds());
        meta.put("sourceEndpoint", parseResult == null ? null : parseResult.getSourceEndpoint());
        meta.put("authorName", parseResult == null || parseResult.getAuthor() == null ? null : parseResult.getAuthor().getNickname());
        meta.put("hasTranscript", transcriptResult != null && StringUtils.hasText(transcriptResult.getOriginalText()));
        meta.put("assetRole", "benchmark_json");
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

    private DouyinVideoParseRequest readParseRequest(String inputJson) {
        if (!StringUtils.hasText(inputJson)) {
            return null;
        }
        try {
            return objectMapper.readValue(inputJson, DouyinVideoParseRequest.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private void fail(Long taskId, Exception ex) {
        try {
            taskService.failTask(taskId, userFacingErrorMessage(ex));
        } catch (Exception ignored) {
        }
    }
}
