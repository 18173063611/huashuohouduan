package com.huashuo.video.service.impl;

import com.huashuo.common.exception.BusinessException;
import com.huashuo.video.DTO.ImageDTO;
import com.huashuo.video.DTO.ImageFirstLastFrameDTO;
import com.huashuo.video.DTO.ImageReferenceDTO;
import com.huashuo.video.DTO.TextDTO;
import com.huashuo.video.VO.VideoTaskVO;
import com.huashuo.video.service.VideoService;
import com.volcengine.ark.runtime.model.content.generation.CreateContentGenerationTaskRequest;
import com.volcengine.ark.runtime.model.content.generation.CreateContentGenerationTaskRequest.Content;
import com.volcengine.ark.runtime.model.content.generation.CreateContentGenerationTaskRequest.ImageUrl;
import com.volcengine.ark.runtime.model.content.generation.CreateContentGenerationTaskResult;
import com.volcengine.ark.runtime.model.content.generation.GetContentGenerationTaskRequest;
import com.volcengine.ark.runtime.model.content.generation.GetContentGenerationTaskResponse;
import com.volcengine.ark.runtime.service.ArkService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 视频生成服务实现：通过火山方舟 Ark Runtime SDK 调用 Seedance 系列模型创建异步视频生成任务。
 * 控制器调用本服务时为同步语义：内部创建任务后立即轮询「查询视频生成任务」接口，
 * 仅当任务 succeeded 且 videoUrl 非空时才返回；其他终态（failed / cancelled / expired / 超时）抛出业务异常。
 */
@Service
@Slf4j
public class VideoServiceImpl implements VideoService {

    private static final String TYPE_TEXT = "text";
    private static final String TYPE_IMAGE_URL = "image_url";

    private static final String ROLE_FIRST_FRAME = "first_frame";
    private static final String ROLE_LAST_FRAME = "last_frame";
    private static final String ROLE_REFERENCE_IMAGE = "reference_image";

    private static final String STATUS_SUCCEEDED = "succeeded";
    private static final String STATUS_FAILED = "failed";
    private static final String STATUS_CANCELLED = "cancelled";
    private static final String STATUS_EXPIRED = "expired";

    private final ArkService arkService;
    private final String defaultModel;
    private final String referenceModel;
    private final long pollIntervalMillis;
    private final long pollTimeoutMillis;

    public VideoServiceImpl(
            ArkService seedanceArkService,
            @Value("${volcengine.seedance.model:doubao-seedance-1-5-pro}") String defaultModel,
            @Value("${volcengine.seedance.reference-model:doubao-seedance-1-0-lite-i2v-250428}") String referenceModel,
            @Value("${volcengine.seedance.poll-interval-seconds:5}") long pollIntervalSeconds,
            @Value("${volcengine.seedance.poll-timeout-seconds:600}") long pollTimeoutSeconds
    ) {
        this.arkService = seedanceArkService;
        this.defaultModel = defaultModel;
        this.referenceModel = referenceModel;
        this.pollIntervalMillis = Math.max(1L, pollIntervalSeconds) * 1000L;
        this.pollTimeoutMillis = Math.max(60L, pollTimeoutSeconds) * 1000L;
    }

    @Override
    public VideoTaskVO generateText(TextDTO request) {
        if (request == null || !StringUtils.hasText(request.getPrompt())) {
            throw new BusinessException(40000, "prompt 不能为空");
        }

        // 文生视频：content 仅包含一段 text。
        List<Content> contents = new ArrayList<>();
        contents.add(buildText(request.getPrompt()));

        String model = StringUtils.hasText(request.getModel()) ? request.getModel() : defaultModel;
        CreateContentGenerationTaskRequest req = baseBuilder(model, contents)
                .duration(toLong(request.getDuration()))
                .seed(toLong(-1))
                .cameraFixed(false)
                .watermark(false)
                .generateAudio(true)
                .build();

        return submitAndPoll(req);
    }

    @Override
    public VideoTaskVO generateFirstFrame(ImageDTO request) {
        if (request == null || !StringUtils.hasText(request.getImageUrl())) {
            throw new BusinessException(40000, "imageUrl 不能为空");
        }

        // 图生视频-首帧：content = 可选 text + 一张 first_frame 图（role 不填默认即首帧）。
        List<Content> contents = new ArrayList<>();
        if (StringUtils.hasText(request.getPrompt())) {
            contents.add(buildText(request.getPrompt()));
        }
        contents.add(buildImage(request.getImageUrl(), ROLE_FIRST_FRAME));

        String model = StringUtils.hasText(request.getModel()) ? request.getModel() : defaultModel;
        CreateContentGenerationTaskRequest req = baseBuilder(model, contents)
                .duration(toLong(request.getDuration()))
                .seed(toLong(-1))
                .cameraFixed(false)
                .watermark(false)
                .generateAudio(true)
                .build();

        return submitAndPoll(req);
    }

    @Override
    public VideoTaskVO generateFirstLastFrame(ImageFirstLastFrameDTO request) {
        if (request == null
                || !StringUtils.hasText(request.getFirstFrameUrl())
                || !StringUtils.hasText(request.getLastFrameUrl())) {
            throw new BusinessException(40000, "firstFrameUrl 与 lastFrameUrl 均不能为空");
        }

        // 图生视频-首尾帧：content = 可选 text + 两张图，role 必填为 first_frame / last_frame。
        List<Content> contents = new ArrayList<>();
        if (StringUtils.hasText(request.getPrompt())) {
            contents.add(buildText(request.getPrompt()));
        }
        contents.add(buildImage(request.getFirstFrameUrl(), ROLE_FIRST_FRAME));
        contents.add(buildImage(request.getLastFrameUrl(), ROLE_LAST_FRAME));

        String model = StringUtils.hasText(request.getModel()) ? request.getModel() : defaultModel;
        CreateContentGenerationTaskRequest req = baseBuilder(model, contents)
                .duration(toLong(request.getDuration()))
                .seed(toLong(-1))
                .cameraFixed(false)
                .watermark(false)
                .generateAudio(true)
                .build();

        return submitAndPoll(req);
    }

    @Override
    public VideoTaskVO generateReference(ImageReferenceDTO request) {
        if (request == null || request.getImageUrls() == null || request.getImageUrls().isEmpty()) {
            throw new BusinessException(40000, "imageUrls 不能为空");
        }

        // 图生视频-参照图：每张图 role = reference_image；Seedance 1.5 pro 不支持，默认走 1.0 lite i2v。
        List<Content> contents = new ArrayList<>();
        if (StringUtils.hasText(request.getPrompt())) {
            contents.add(buildText(request.getPrompt()));
        }
        for (String url : request.getImageUrls()) {
            if (StringUtils.hasText(url)) {
                contents.add(buildImage(url, ROLE_REFERENCE_IMAGE));
            }
        }

        String model = StringUtils.hasText(request.getModel()) ? request.getModel() : referenceModel;
        // 参照图场景不支持 cameraFixed，故不传该字段。
        CreateContentGenerationTaskRequest req = baseBuilder(model, contents)
                .duration(toLong(request.getDuration()))
                .seed(toLong(-1))
                .cameraFixed(false)
                .watermark(false)
                .generateAudio(true)
                .build();

        return submitAndPoll(req);
    }

    private CreateContentGenerationTaskRequest.Builder baseBuilder(String model, List<Content> contents) {
        return CreateContentGenerationTaskRequest.builder()
                .model(model)
                .content(contents);
    }

    private Content buildText(String text) {
        Content content = new Content();
        content.setType(TYPE_TEXT);
        content.setText(text);
        return content;
    }

    private Content buildImage(String url, String role) {
        ImageUrl imageUrl = new ImageUrl();
        imageUrl.setUrl(url);
        Content content = new Content();
        content.setType(TYPE_IMAGE_URL);
        content.setImageUrl(imageUrl);
        if (StringUtils.hasText(role)) {
            content.setRole(role);
        }
        return content;
    }

    private Long toLong(Integer value) {
        return value == null ? null : value.longValue();
    }

    /**
     * 创建任务后立即开始内部轮询，仅当任务 succeeded 且 videoUrl 非空时返回；
     * 任务 failed / cancelled / expired 或超出 pollTimeoutMillis 时抛出业务异常。
     */
    private VideoTaskVO submitAndPoll(CreateContentGenerationTaskRequest req) {
        String taskId;
        try {
            CreateContentGenerationTaskResult result = arkService.createContentGenerationTask(req);
            if (result == null || !StringUtils.hasText(result.getId())) {
                throw new BusinessException(50100, "火山方舟未返回任务 ID");
            }
            taskId = result.getId();
            log.info("Seedance 视频任务创建成功 taskId={} model={}", taskId, req.getModel());
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("Seedance 视频任务创建失败 model={}", req.getModel(), e);
            throw new BusinessException(50100, "视频生成任务创建失败：" + e.getMessage());
        }

        long deadline = System.currentTimeMillis() + pollTimeoutMillis;
        while (true) {
            sleep(pollIntervalMillis);

            VideoTaskVO task = queryTask(taskId);
            String status = task.getStatus();
            log.debug("Seedance 任务轮询 taskId={} status={}", taskId, status);

            // succeeded 且 videoUrl 已就绪才视为最终成功（满足用户要求：仅 videoUrl 非空时返回）。
            if (STATUS_SUCCEEDED.equalsIgnoreCase(status) && StringUtils.hasText(task.getVideoUrl())) {
                return task;
            }

            // 终态失败：抛出业务异常给上层。
            if (STATUS_FAILED.equalsIgnoreCase(status)) {
                throw new BusinessException(50300,
                        "视频生成任务失败：" + safeErrorMessage(task));
            }
            if (STATUS_CANCELLED.equalsIgnoreCase(status)) {
                throw new BusinessException(50300, "视频生成任务已取消 taskId=" + taskId);
            }
            if (STATUS_EXPIRED.equalsIgnoreCase(status)) {
                throw new BusinessException(50300, "视频生成任务已超时 taskId=" + taskId);
            }

            if (System.currentTimeMillis() > deadline) {
                throw new BusinessException(50300,
                        "视频生成轮询超时 taskId=" + taskId + " 最近状态=" + status);
            }
        }
    }

    private VideoTaskVO queryTask(String taskId) {
        try {
            GetContentGenerationTaskRequest req = GetContentGenerationTaskRequest.builder()
                    .taskId(taskId)
                    .build();
            GetContentGenerationTaskResponse resp = arkService.getContentGenerationTask(req);
            if (resp == null) {
                throw new BusinessException(40400, "任务不存在或已过期 taskId=" + taskId);
            }
            return toTaskVO(resp);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("Seedance 视频任务查询失败 taskId={}", taskId, e);
            throw new BusinessException(50100, "视频任务查询失败：" + e.getMessage());
        }
    }

    private VideoTaskVO toTaskVO(GetContentGenerationTaskResponse resp) {
        VideoTaskVO.VideoTaskVOBuilder builder = VideoTaskVO.builder()
                .taskId(resp.getId())
                .model(resp.getModel())
                .status(resp.getStatus())
                .createdAt(resp.getCreatedAt())
                .updatedAt(resp.getUpdatedAt());

        // 仅 status=succeeded 时 content 才会带 videoUrl；其他状态可能为 null。
        GetContentGenerationTaskResponse.Content content = resp.getContent();
        if (content != null) {
            builder.videoUrl(content.getVideoUrl())
                    .lastFrameUrl(content.getLastFrameUrl());
        }

        if (resp.getUsage() != null) {
            builder.completionTokens(resp.getUsage().getCompletionTokens());
        }

        GetContentGenerationTaskResponse.ContentGenerationError error = resp.getError();
        if (error != null) {
            builder.errorCode(error.getCode())
                    .errorMessage(error.getMessage());
        }

        return builder.build();
    }

    private String safeErrorMessage(VideoTaskVO task) {
        if (StringUtils.hasText(task.getErrorMessage())) {
            return task.getErrorMessage();
        }
        if (StringUtils.hasText(task.getErrorCode())) {
            return task.getErrorCode();
        }
        return "未知原因";
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50000, "视频任务轮询被中断");
        }
    }
}
