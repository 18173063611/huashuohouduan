package com.huashuo.video.controller;

import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.task.vo.TaskItem;
import com.huashuo.user.util.CurrentUser;
import com.huashuo.video.DTO.ImageDTO;
import com.huashuo.video.DTO.ImageFirstLastFrameDTO;
import com.huashuo.video.DTO.ImageReferenceDTO;
import com.huashuo.video.DTO.TextDTO;
import com.huashuo.video.DTO.DigitalHumanDTO;
import com.huashuo.video.VO.VideoTaskVO;
import com.huashuo.video.DTO.DigitalHumanGenerateResponse;
import com.huashuo.video.DTO.DigitalHumanTaskDetailResponse;
import com.huashuo.video.service.VideoAsyncTaskService;
import com.huashuo.video.service.ViduDigitalHumanService;
import com.huashuo.video.service.VideoService;
import com.huashuo.user.service.UserAuthService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.OptionalLong;

/**
 * 视频生成接口：四个独立入口对应前端「文生视频」与「图生视频」三种子模式。
 * 接口为同步语义：服务端创建任务后内部自动轮询，直到拿到 videoUrl 才返回；
 * 任务失败 / 取消 / 超时 / 轮询超时时返回业务错误码。
 */
@Validated
@RestController
@RequestMapping("/api/v1/video")
@Slf4j
public class VideoController {

    @Autowired
    private VideoAsyncTaskService videoAsyncTaskService;

    @Autowired
    private ViduDigitalHumanService viduDigitalHumanService;

    @Autowired
    private UserAuthService userAuthService;

    /**
     * 文生视频。
     */
    @PostMapping("/generate/text")
    public ApiResponse<TaskItem> generateText(@Valid @RequestBody TextDTO request) {
        return ApiResponse.success(
                videoAsyncTaskService.createTextVideoTask(request, traceId(), CurrentUser.nullableUserId()),
                traceId()
        );
    }

    /**
     * 图生视频-首帧生成。
     */
    @PostMapping("/generate/image/first-frame")
    public ApiResponse<TaskItem> generateFirstFrame(@Valid @RequestBody ImageDTO request) {
        return ApiResponse.success(
                videoAsyncTaskService.createFirstFrameVideoTask(request, traceId(), CurrentUser.nullableUserId()),
                traceId()
        );
    }

    /**
     * 图生视频-首尾帧生成。
     */
    @PostMapping("/generate/image/first-last-frame")
    public ApiResponse<TaskItem> generateFirstLastFrame(@Valid @RequestBody ImageFirstLastFrameDTO request) {
        return ApiResponse.success(
                videoAsyncTaskService.createFirstLastFrameVideoTask(request, traceId(), CurrentUser.nullableUserId()),
                traceId()
        );
    }

    /**
     * 图生视频-参照图生成。
     */
    @PostMapping("/generate/image/reference")
    public ApiResponse<TaskItem> generateReference(@Valid @RequestBody ImageReferenceDTO request) {
        return ApiResponse.success(
                videoAsyncTaskService.createReferenceVideoTask(request, traceId(), CurrentUser.nullableUserId()),
                traceId()
        );
    }

    @PostMapping("/generate/digital-human")
    public ApiResponse<DigitalHumanGenerateResponse> generateDigitalHuman(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyHeader,
            @Valid @RequestBody DigitalHumanDTO request
    ) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        Long ownerUserId = viewer.isPresent() ? viewer.getAsLong() : null;
        String idem = StringUtils.hasText(idempotencyHeader) ? idempotencyHeader.trim() : null;
        return ApiResponse.success(viduDigitalHumanService.generate(request, traceId(), ownerUserId, idem), traceId());
    }

    @GetMapping("/generate/digital-human/{taskId}")
    public ApiResponse<DigitalHumanTaskDetailResponse> getDigitalHumanTask(@PathVariable Long taskId) {
        return ApiResponse.success(viduDigitalHumanService.getGenerateTask(taskId), traceId());
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
