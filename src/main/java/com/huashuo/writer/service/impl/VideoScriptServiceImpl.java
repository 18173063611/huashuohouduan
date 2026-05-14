package com.huashuo.writer.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.billing.model.UsageActualResult;
import com.huashuo.billing.model.UsageUnit;
import com.huashuo.billing.service.CreditBillingService;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import com.huashuo.upload.tos.TosUploadService;
import com.huashuo.upload.tos.VolcengineTosProperties;
import com.huashuo.writer.vo.ScriptVO;
import com.huashuo.writer.pojo.DouyinVideoParseRequest;
import com.huashuo.writer.pojo.DouyinVideoParseResponse;
import com.huashuo.writer.service.VideoScriptService;
import com.huashuo.writer.service.WriterService;
import com.volcengine.tos.TOSV2;
import com.volcengine.tos.TosException;
import com.volcengine.tos.model.object.HeadObjectV2Input;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@Slf4j
public class VideoScriptServiceImpl implements VideoScriptService {
    @Autowired
    private WriterService writerService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private CreditBillingService creditBillingService;

    private static final String SCRIPT_VIDEO_OBJECT_PREFIX = "writer/script-video/";
    private static final String VIDEO_CONTENT_TYPE = "video/mp4";
    private static final String DOWNLOAD_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final String STEP_SCRIPT_ANALYZE = "scriptAnalyze";
    private static final String STEP_SCRIPT_ANALYZE_BY_URL = "scriptAnalyzeByUrl";

    private static final String SCRIPT_ANALYZE_PROMPT = """
            你是专业的短视频分镜解析助手。请对输入视频按镜头切换或场景变化进行分镜切分，并对每一个分镜片段输出以下字段：
            - order: 分镜序号，从 1 开始的整数；
            - time: 该分镜的起止时间或时长，格式形如 "00:00:03-00:00:08"；
            - page: 画面内容，描述该分镜的人物、场景、动作、画面元素等视觉信息；
            - backgroundMusic: 背景音乐风格、节奏或具体音乐，无则填 "无"；
            - content: 口播文案，即该分镜中的旁白、人物原话或字幕原文，无则填 "无"；
            - highlight: 突出点，该分镜的核心亮点或表达意图。

            严格按下述 JSON 数组格式输出，不要输出任何额外解释、不要使用 markdown 代码块包裹、不要在 JSON 前后添加任何文字：
            [
              {"order": 1, "time": "...", "page": "...", "backgroundMusic": "...", "content": "...", "highlight": "..."}
            ]
            """;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String arkBaseUrl;
    private final String arkApiKey;
    private final String arkVideoModel;
    private final float videoFps;
    private final TosUploadService tosUploadService;
    private final VolcengineTosProperties tosProperties;
    private final ObjectProvider<TOSV2> tosClient;
    private final long videoTransferMaxBytes;
    private final int videoDownloadConnectTimeoutMillis;
    private final int videoDownloadReadTimeoutMillis;

    public VideoScriptServiceImpl(
            ObjectMapper objectMapper,
            TosUploadService tosUploadService,
            VolcengineTosProperties tosProperties,
            ObjectProvider<TOSV2> tosClient,
            @Value("${volcengine.arks.base-url:${VOLCENGINE_ARKS_BASE_URL:https://ark.cn-beijing.volces.com/api/v3}}") String arkBaseUrl,
            @Value("${volcengine.arks.api-key:${VOLCENGINE_ARKS_API_KEY:}}") String arkApiKey,
            @Value("${volcengine.arks.video-model:${VOLCENGINE_ARKS_VIDEO_MODEL:doubao-seed-2-0-lite-260215}}") String arkVideoModel,
            @Value("${volcengine.arks.video-fps:1.0}") float videoFps,
            @Value("${volcengine.arks.video-transfer.max-bytes:52428800}") long videoTransferMaxBytes,
            @Value("${volcengine.arks.video-transfer.connect-timeout-seconds:15}") long videoDownloadConnectTimeoutSeconds,
            @Value("${volcengine.arks.video-transfer.read-timeout-seconds:60}") long videoDownloadReadTimeoutSeconds
    ) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(60))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.arkBaseUrl = trimTrailingSlash(arkBaseUrl);
        this.arkApiKey = arkApiKey;
        this.arkVideoModel = StringUtils.hasText(arkVideoModel) ? arkVideoModel.trim() : "doubao-seed-2-0-lite-260215";
        this.videoFps = videoFps;
        this.tosUploadService = tosUploadService;
        this.tosProperties = tosProperties;
        this.tosClient = tosClient;
        this.videoTransferMaxBytes = videoTransferMaxBytes > 0 ? videoTransferMaxBytes : 52_428_800L;
        this.videoDownloadConnectTimeoutMillis = secondsToMillis(videoDownloadConnectTimeoutSeconds, 15);
        this.videoDownloadReadTimeoutMillis = secondsToMillis(videoDownloadReadTimeoutSeconds, 60);
    }

    @Override
    public List<ScriptVO> executeScriptAnalyzeForParentTask(String url, String parentTaskType) {
        if (!StringUtils.hasText(url)) {
            throw new BusinessException(40000, "url is required");
        }
        if (!StringUtils.hasText(arkApiKey)) {
            throw new BusinessException(50001, "Volcengine Ark api key is not configured");
        }
        if (TaskTypeCode.VIDEO_SCRIPT_URL_ANALYZE.equals(parentTaskType)) {
            return executeShareLinkToScriptInvocation(url.trim()).scripts();
        }
        if (TaskTypeCode.VIDEO_SCRIPT_ANALYZE.equals(parentTaskType)) {
            return doScriptAnalyze(url.trim()).scripts();
        }
        throw new BusinessException(40000, "unsupported parent task type for script analyze: " + parentTaskType);
    }

    @Override
    public List<ScriptVO> scriptAnalyze(String url, Long ownerUserId, Long projectId, String traceId,
                                        String idempotencyKey) {
        if (!StringUtils.hasText(url)) {
            throw new BusinessException(40000, "url is required");
        }
        if (!StringUtils.hasText(arkApiKey)) {
            throw new BusinessException(50001, "Volcengine Ark api key is not configured");
        }
        return runVideoParseTask(STEP_SCRIPT_ANALYZE, url, ownerUserId, projectId, traceId, idempotencyKey,
                () -> doScriptAnalyze(url));
    }

    @Override
    public List<ScriptVO> scriptAnalyzeByUrl(String url, Long ownerUserId, Long projectId, String traceId,
                                             String idempotencyKey) {
        if (!StringUtils.hasText(url)) {
            throw new BusinessException(40000, "url is required");
        }
        if (!StringUtils.hasText(arkApiKey)) {
            throw new BusinessException(50001, "Volcengine Ark api key is not configured");
        }
        return runVideoParseTask(STEP_SCRIPT_ANALYZE_BY_URL, url, ownerUserId, projectId, traceId, idempotencyKey,
                () -> executeShareLinkToScriptInvocation(url));
    }

    private VideoParseInvocation executeShareLinkToScriptInvocation(String url) {
        DouyinVideoParseRequest request = new DouyinVideoParseRequest();
        request.setUrl(url);
        DouyinVideoParseResponse parseResult = writerService.parseDouyinVideo(request);
        log.info("parseResult:{}", parseResult.getPlayUrl());
        String modelVideoUrl = publishDouyinPlayUrlForModel(parseResult, url);
        return doScriptAnalyze(modelVideoUrl);
    }

    /**
     * 任务台账包装：每次外部调用仅创建一次 VIDEO_PARSE 任务，统一预扣积分并在成功/失败时回写状态。
     * usage 字段在 Ark 响应里不强制存在，故 {@code promptTokens/completionTokens/totalTokens} 允许为 {@code null}；
     * {@code responseSummaryJson} 是用于 {@code ai_usage_log.raw_usage_json} 的精简响应摘要。
     */
    private record VideoParseInvocation(List<ScriptVO> scripts,
                                        Integer promptTokens,
                                        Integer completionTokens,
                                        Integer totalTokens,
                                        String responseSummaryJson) {
        boolean hasUsage() {
            return (totalTokens != null && totalTokens > 0)
                    || (promptTokens != null && promptTokens > 0)
                    || (completionTokens != null && completionTokens > 0);
        }
    }

    /**
     * 任务台账包装：每次外部调用仅创建一次 VIDEO_PARSE 任务，统一预扣积分并在成功/失败时回写状态。
     * 内部复用方若已有外层任务预扣，应改用 {@link #executeScriptAnalyzeForParentTask(String, String)}，避免二次建 task。
     */
    private List<ScriptVO> runVideoParseTask(String step, String url, Long ownerUserId, Long projectId,
                                             String traceId, String idempotencyKey,
                                             VideoParseExecution execution) {
        String inputJson = toTaskInputJson(step, url, projectId);
        TaskItem item;
        try {
            item = taskService.createTask(projectId, TaskTypeCode.VIDEO_PARSE, inputJson, traceId, ownerUserId,
                    arkVideoModel, null, StringUtils.hasText(idempotencyKey) ? idempotencyKey.trim() : null);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception ex) {
            log.error("VIDEO_PARSE 创建本地任务失败 step={} reason={}", step, ex.getMessage(), ex);
            throw new BusinessException(50000, "视频理解任务创建失败：" + ex.getMessage());
        }

        Long localTaskId = item.taskId();
        if (localTaskId == null) {
            // 兜底：未拿到 taskId 时按旧行为执行，不影响业务可用性
            return execution.execute().scripts();
        }
        try {
            taskService.startTask(localTaskId);
        } catch (Exception ignored) {
            log.warn("VIDEO_PARSE startTask 失败 taskId={} reason={}", localTaskId, ignored.getMessage());
        }
        VideoParseInvocation invocation;
        try {
            invocation = execution.execute();
        } catch (BusinessException be) {
            safelyFailTask(localTaskId, be.getMessage());
            throw be;
        } catch (Exception ex) {
            log.error("VIDEO_PARSE 任务执行异常 taskId={}", localTaskId, ex);
            safelyFailTask(localTaskId, ex.getMessage());
            throw new BusinessException(50220, "视频理解失败：" + ex.getMessage());
        }

        // 成功路径：先写 ai_usage_log usage_phase=ACTUAL。
        //   - 拿到 usage.{prompt,completion,total}_tokens：调 settle，由 settle 内部按 ai_model_price 计算
        //     actual_credit_cost 并补扣/退差额，settlement_status 推进到 SETTLED/PARTIAL_REFUNDED；
        //   - 没有 usage：调 recordActual 写占位行（actual_credit_cost=0），保留 raw_usage_json 摘要做对账，
        //     不动余额、不改 settlement_status，由后续真有用量再 settle。
        // 任意结算失败都不能影响业务返回与 completeTask；用 try/catch 兜底打 WARN 即可。
        try {
            recordVideoParseActualUsage(localTaskId, invocation);
        } catch (Exception billingEx) {
            log.warn("VIDEO_PARSE 写 actual usage 失败 taskId={} reason={}", localTaskId, billingEx.getMessage());
        }

        try {
            taskService.completeTask(localTaskId,
                    objectMapper.writeValueAsString(Map.of("scriptCount",
                            invocation.scripts() == null ? 0 : invocation.scripts().size())));
        } catch (Exception completeEx) {
            log.warn("VIDEO_PARSE completeTask 失败 taskId={} reason={}", localTaskId, completeEx.getMessage());
        }
        return invocation.scripts();
    }

    /**
     * 写 VIDEO_PARSE 任务的真实用量行。{@code raw_usage_json} 始终带 Ark 响应摘要，
     * 即使本次没拿到 usage tokens 也保留摘要供对账复盘。
     */
    private void recordVideoParseActualUsage(Long taskId, VideoParseInvocation invocation) {
        if (taskId == null || invocation == null) {
            return;
        }
        Integer prompt = invocation.promptTokens();
        Integer completion = invocation.completionTokens();
        Integer total = invocation.totalTokens();
        // 容错：拿到 prompt+completion 但没 total 时主动相加，便于报表的 total_tokens 列。
        if (total == null && prompt != null && completion != null) {
            total = prompt + completion;
        }
        UsageActualResult actual = new UsageActualResult(
                "VOLCENGINE",
                arkVideoModel,
                UsageUnit.TOKEN,
                prompt,
                completion,
                total,
                null,
                null,
                java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO,
                // settle 路径让其内部按 ai_model_price * tokens 算实际成本；
                // recordActual 路径直接当成 0（保留预扣作为最终成本由报表回退到 ESTIMATE）。
                invocation.hasUsage() ? null : 0L,
                invocation.responseSummaryJson()
        );
        if (invocation.hasUsage()) {
            creditBillingService.settle(taskId, actual);
        } else {
            creditBillingService.recordActual(taskId, actual);
        }
    }

    private void safelyFailTask(Long taskId, String message) {
        if (taskId == null) {
            return;
        }
        try {
            String safe = message == null ? null : (message.length() > 480 ? message.substring(0, 480) : message);
            taskService.failTask(taskId, safe, false, true);
        } catch (Exception ex) {
            log.warn("VIDEO_PARSE failTask 失败 taskId={} reason={}", taskId, ex.getMessage());
        }
    }

    private String toTaskInputJson(String step, String url, Long projectId) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("step", step);
        snapshot.put("url", url);
        snapshot.put("model", arkVideoModel);
        snapshot.put("projectId", projectId);
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (IOException ignored) {
            return null;
        }
    }

    @FunctionalInterface
    private interface VideoParseExecution {
        VideoParseInvocation execute();
    }

    /**
     * 原 Ark 视频理解调用：与历史实现一致，仅由 {@link #runVideoParseTask} 包裹任务台账后调用。
     * 在解析业务结果（{@link ScriptVO} 列表）外，额外抽取 Ark 响应的 usage 字段与摘要，供计费侧
     * 写入 {@code ai_usage_log usage_phase=ACTUAL}。
     */
    private VideoParseInvocation doScriptAnalyze(String url) {
        try {
            Map<String, Object> body = Map.of(
                    "model", arkVideoModel,
                    "messages", List.of(Map.of(
                            "role", "user",
                            "content", List.of(
                                    Map.of(
                                            "type", "video_url",
                                            "video_url", Map.of(
                                                    "url", url,
                                                    "fps", videoFps
                                            )
                                    ),
                                    Map.of(
                                            "type", "text",
                                            "text", SCRIPT_ANALYZE_PROMPT
                                    )
                            )
                    ))
            );

            log.info("开始传给大模型进行分镜解析，time={}", LocalDateTime.now());
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(arkBaseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(180))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + arkApiKey)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(50220,
                        "Doubao video understanding failed: " + arkErrorMessage(response));
            }
            String responseBody = response.body();
            String content = extractMessageContent(responseBody);
            if (!StringUtils.hasText(content)) {
                throw new BusinessException(50220, "Doubao video understanding returned empty content");
            }
            log.info("分镜解析完成，time={}", LocalDateTime.now());
            List<ScriptVO> scripts = parseScriptList(content);
            return buildInvocation(scripts, responseBody);
        } catch (IOException exception) {
            throw new BusinessException(50220, "Doubao video understanding failed: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50220, "Doubao video understanding was interrupted");
        }
    }

    /**
     * 从 Ark 响应 body 抽取 usage 字段并构造 {@link VideoParseInvocation}。
     * 异常容错：任意一步失败都退化为 "no usage" 模式（仍写一个不带 token 的 ACTUAL 占位）。
     */
    private VideoParseInvocation buildInvocation(List<ScriptVO> scripts, String rawResponseBody) {
        Integer prompt = null;
        Integer completion = null;
        Integer total = null;
        String responseId = null;
        String finishReason = null;
        try {
            JsonNode root = objectMapper.readTree(rawResponseBody);
            JsonNode usageNode = root.path("usage");
            if (usageNode != null && !usageNode.isMissingNode()) {
                prompt = nonNegativeOrNull(usageNode.path("prompt_tokens"));
                completion = nonNegativeOrNull(usageNode.path("completion_tokens"));
                total = nonNegativeOrNull(usageNode.path("total_tokens"));
            }
            JsonNode idNode = root.path("id");
            if (idNode != null && idNode.isTextual()) {
                responseId = idNode.asText();
            }
            JsonNode finishNode = root.at("/choices/0/finish_reason");
            if (finishNode != null && !finishNode.isMissingNode() && finishNode.isTextual()) {
                finishReason = finishNode.asText();
            }
        } catch (IOException ex) {
            log.warn("VIDEO_PARSE 抽取 Ark usage 失败，将走 recordActual 占位。reason={}", ex.getMessage());
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("model", arkVideoModel);
        summary.put("responseId", responseId);
        summary.put("finishReason", finishReason);
        Map<String, Object> usageSummary = new LinkedHashMap<>();
        usageSummary.put("promptTokens", prompt);
        usageSummary.put("completionTokens", completion);
        usageSummary.put("totalTokens", total);
        summary.put("usage", usageSummary);
        summary.put("scriptCount", scripts == null ? 0 : scripts.size());
        String responseSummaryJson;
        try {
            responseSummaryJson = objectMapper.writeValueAsString(summary);
        } catch (IOException ex) {
            log.warn("VIDEO_PARSE 序列化 raw_usage_json 摘要失败，使用兜底字符串。reason={}", ex.getMessage());
            responseSummaryJson = "{\"model\":\"" + arkVideoModel + "\",\"usage\":null}";
        }
        return new VideoParseInvocation(scripts, prompt, completion, total, responseSummaryJson);
    }

    private static Integer nonNegativeOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isNumber()) {
            return null;
        }
        int value = node.intValue();
        return value >= 0 ? value : null;
    }

    private String publishDouyinPlayUrlForModel(DouyinVideoParseResponse parseResult, String sourceUrl) {
        String playUrl = parseResult == null ? null : parseResult.getPlayUrl();
        if (!StringUtils.hasText(playUrl)) {
            throw new BusinessException(50202, "TikHub parse succeeded but playUrl is empty");
        }
        ensureTosAvailable();

        String objectKey = buildScriptVideoObjectKey(parseResult, sourceUrl);
        String publicUrl = buildTosPublicUrl(objectKey);
        if (tosObjectExists(objectKey)) {
            log.info("Script video TOS cache hit, objectKey={} url={}", objectKey, publicUrl);
            return publicUrl;
        }

        log.info("开始下载视频，time={} objectKey={}", LocalDateTime.now(), objectKey);
        HttpURLConnection connection = null;
        try {
            URL downloadUrl = URI.create(playUrl.trim()).toURL();
            connection = (HttpURLConnection) downloadUrl.openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(videoDownloadConnectTimeoutMillis);
            connection.setReadTimeout(videoDownloadReadTimeoutMillis);
            connection.setRequestProperty(HttpHeaders.USER_AGENT, DOWNLOAD_USER_AGENT);
            connection.setRequestProperty(HttpHeaders.ACCEPT, "video/mp4,video/*,*/*");

            int statusCode = connection.getResponseCode();
            if (statusCode < 200 || statusCode >= 300) {
                throw new BusinessException(50221, "Douyin playUrl download failed, HTTP " + statusCode);
            }
            long contentLength = connection.getContentLengthLong();
            if (contentLength > videoTransferMaxBytes) {
                throw new BusinessException(50221, "Douyin video is too large for model analysis: " + contentLength + " bytes");
            }
            String contentType = StringUtils.hasText(connection.getContentType())
                    ? connection.getContentType().trim()
                    : VIDEO_CONTENT_TYPE;
            log.info("视频下载响应已建立，time={} objectKey={} httpStatus={} contentLength={}",
                    LocalDateTime.now(), objectKey, statusCode, contentLength);
            if (contentLength >= 0) {
                uploadKnownLengthStream(objectKey, connection.getInputStream(), contentLength, contentType);
            } else {
                uploadUnknownLengthStream(objectKey, connection.getInputStream(), contentType);
            }
            log.info("视频下载完成，time={} objectKey={} contentLength={}", LocalDateTime.now(), objectKey, contentLength);
            log.info("Script video published to TOS, objectKey={} url={}", objectKey, publicUrl);
            return publicUrl;
        } catch (IOException exception) {
            throw new BusinessException(50221, "Douyin playUrl transfer to TOS failed: " + exception.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void uploadKnownLengthStream(String objectKey, InputStream body, long contentLength, String contentType) {
        if (contentLength <= 0) {
            closeQuietly(body);
            throw new BusinessException(50221, "Douyin video download returned empty content");
        }
        try (InputStream in = body) {
            tosUploadService.putPublicObject(objectKey, in, contentLength, contentType);
        } catch (IOException exception) {
            throw new BusinessException(50221, "Douyin video stream close failed: " + exception.getMessage());
        }
    }

    private void uploadUnknownLengthStream(String objectKey, InputStream body, String contentType) {
        Path tempFile = null;
        try (InputStream in = body) {
            tempFile = Files.createTempFile("huashuo-script-video-", ".mp4");
            long copied = copyWithLimit(in, tempFile, videoTransferMaxBytes);
            if (copied <= 0) {
                throw new BusinessException(50221, "Douyin video download returned empty content");
            }
            try (InputStream fileIn = Files.newInputStream(tempFile)) {
                tosUploadService.putPublicObject(objectKey, fileIn, copied, contentType);
            }
        } catch (IOException exception) {
            throw new BusinessException(50221, "Douyin video temp transfer failed: " + exception.getMessage());
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException exception) {
                    log.warn("Failed to delete script video temp file {}: {}", tempFile, exception.getMessage());
                }
            }
        }
    }

    private long copyWithLimit(InputStream inputStream, Path targetFile, long maxBytes) throws IOException {
        long copied = 0L;
        byte[] buffer = new byte[1024 * 1024];
        try (OutputStream out = Files.newOutputStream(targetFile)) {
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                copied += read;
                if (copied > maxBytes) {
                    throw new BusinessException(50221, "Douyin video is too large for model analysis: over " + maxBytes + " bytes");
                }
                out.write(buffer, 0, read);
            }
        }
        return copied;
    }

    private void ensureTosAvailable() {
        if (!tosProperties.enabled()) {
            throw new BusinessException(50001, "TOS is required for Douyin video script analysis; enable volcengine.tos.enabled");
        }
        if (tosClient.getIfAvailable() == null) {
            throw new BusinessException(50001, "TOS client is not initialized; configure VOLCENGINE_TOS_ACCESS_KEY_ID and secret");
        }
        if (!StringUtils.hasText(tosProperties.bucket())) {
            throw new BusinessException(50001, "volcengine.tos.bucket is not configured");
        }
        if (!StringUtils.hasText(tosProperties.publicBaseUrl())) {
            throw new BusinessException(50001, "volcengine.tos.public-base-url is not configured");
        }
    }

    private boolean tosObjectExists(String objectKey) {
        TOSV2 client = tosClient.getIfAvailable();
        if (client == null) {
            return false;
        }
        try {
            var output = client.headObject(new HeadObjectV2Input()
                    .setBucket(tosProperties.bucket())
                    .setKey(objectKey));
            return output != null && output.getContentLength() > 0;
        } catch (TosException exception) {
            if (exception.getStatusCode() == 404) {
                return false;
            }
            throw new BusinessException(50000, "TOS object cache check failed: " + exception.getMessage());
        }
    }

    private String buildScriptVideoObjectKey(DouyinVideoParseResponse parseResult, String sourceUrl) {
        String cacheSeed = firstNonBlank(
                parseResult == null ? null : parseResult.getVideoId(),
                sourceUrl,
                parseResult == null ? null : parseResult.getPlayUrl()
        );
        return SCRIPT_VIDEO_OBJECT_PREFIX + safeCacheKey(cacheSeed) + ".mp4";
    }

    private String safeCacheKey(String value) {
        String trimmed = StringUtils.hasText(value) ? value.trim() : "unknown";
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        if (normalized.matches("[a-z0-9][a-z0-9._-]{0,120}")) {
            return normalized;
        }
        return sha256Hex(trimmed);
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new BusinessException(50000, "SHA-256 is not available");
        }
    }

    private String buildTosPublicUrl(String objectKey) {
        String base = tosProperties.publicBaseUrl().trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/" + objectKey;
    }

    private int secondsToMillis(long seconds, int fallbackSeconds) {
        long safeSeconds = seconds > 0 ? seconds : fallbackSeconds;
        long millis = safeSeconds * 1000L;
        return millis > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) millis;
    }

    private String firstNonBlank(String... values) {
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

    private void closeQuietly(InputStream inputStream) {
        if (inputStream == null) {
            return;
        }
        try {
            inputStream.close();
        } catch (IOException ignored) {
        }
    }

    private String extractMessageContent(String body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        JsonNode contentNode = root.at("/choices/0/message/content");
        if (contentNode.isMissingNode() || contentNode.isNull()) {
            return null;
        }
        return contentNode.asText();
    }

    private List<ScriptVO> parseScriptList(String content) {
        String json = stripJsonWrapper(content);
        try {
            return objectMapper.readValue(json, new TypeReference<List<ScriptVO>>() {});
        } catch (IOException exception) {
            log.warn("Failed to parse script JSON, raw content: {}", content);
            throw new BusinessException(50220,
                    "Doubao video understanding returned non-parsable content: " + abbreviate(content));
        }
    }

    private String stripJsonWrapper(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
            trimmed = trimmed.trim();
        }
        int firstBracket = trimmed.indexOf('[');
        int lastBracket = trimmed.lastIndexOf(']');
        if (firstBracket >= 0 && lastBracket > firstBracket) {
            return trimmed.substring(firstBracket, lastBracket + 1);
        }
        return trimmed;
    }

    private String arkErrorMessage(HttpResponse<String> response) {
        String body = response.body();
        if (!StringUtils.hasText(body)) {
            return "http=" + response.statusCode() + ", empty response body";
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            String message = textByPaths(root, "/error/message", "/message");
            if (StringUtils.hasText(message)) {
                return "http=" + response.statusCode() + ", message=" + message;
            }
        } catch (IOException ignored) {
        }
        return "http=" + response.statusCode() + ", body=" + abbreviate(body);
    }

    private String textByPaths(JsonNode node, String... paths) {
        if (node == null) {
            return null;
        }
        for (String path : paths) {
            JsonNode value = node.at(path);
            if (value != null && !value.isMissingNode() && !value.isNull() && value.isTextual()) {
                String text = value.asText();
                if (StringUtils.hasText(text)) {
                    return text.trim();
                }
            }
        }
        return null;
    }

    private String abbreviate(String value) {
        if (value == null || value.length() <= 500) {
            return value;
        }
        return value.substring(0, 500);
    }

    private static String trimTrailingSlash(String value) {
        if (!StringUtils.hasText(value)) {
            return "https://ark.cn-beijing.volces.com/api/v3";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
