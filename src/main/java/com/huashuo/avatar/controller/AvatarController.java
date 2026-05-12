package com.huashuo.avatar.controller;

import com.huashuo.avatar.dto.AvatarGenerateRequest;
import com.huashuo.avatar.dto.AvatarGenerateResponse;
import com.huashuo.avatar.dto.AvatarTaskDetailResponse;
import com.huashuo.avatar.dto.AvatarUpdateRequest;
import com.huashuo.avatar.service.AvatarService;
import com.huashuo.avatar.vo.AvatarItem;
import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.user.service.UserAuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.OptionalLong;

@Validated
@RestController
@RequestMapping("/api/v1/avatars")
/**
 * 数字人形象接口：上传形象照、提交 AI 生成任务、查询和设置项目形象。
 */
public class AvatarController {

    private final AvatarService avatarService;
    private final UserAuthService userAuthService;

    public AvatarController(AvatarService avatarService, UserAuthService userAuthService) {
        this.avatarService = avatarService;
        this.userAuthService = userAuthService;
    }

    @PostMapping("/upload")
    public ApiResponse<AvatarItem> upload(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String avatarName,
            @RequestParam @NotNull MultipartFile file
    ) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        Long ownerId = viewer.isPresent() ? viewer.getAsLong() : null;
        return ApiResponse.success(avatarService.upload(projectId, avatarName, file, ownerId), traceId());
    }

    @PostMapping("/generate")
    public ApiResponse<AvatarGenerateResponse> generate(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyHeader,
            @Valid @RequestBody AvatarGenerateRequest request
    ) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        Long requestingUserId = viewer.isPresent() ? viewer.getAsLong() : null;
        String idem = StringUtils.hasText(idempotencyHeader) ? idempotencyHeader.trim() : null;
        return ApiResponse.success(avatarService.generate(request, traceId(), requestingUserId, idem), traceId());
    }

    @GetMapping("/generate/{taskId}")
    public ApiResponse<AvatarTaskDetailResponse> getGenerateTask(@PathVariable Long taskId) {
        return ApiResponse.success(avatarService.getGenerateTask(taskId), traceId());
    }

    @GetMapping
    public ApiResponse<List<AvatarItem>> listProjectAvatars(@RequestParam(required = false) Long projectId) {
        return ApiResponse.success(avatarService.listProjectAvatars(projectId), traceId());
    }

    @GetMapping("/{avatarId}")
    public ApiResponse<AvatarItem> getAvatar(@PathVariable Long avatarId) {
        return ApiResponse.success(avatarService.getAvatar(avatarId), traceId());
    }

    @PatchMapping("/{avatarId}")
    public ApiResponse<AvatarItem> updateAvatar(
            @PathVariable Long avatarId,
            @Valid @RequestBody AvatarUpdateRequest request
    ) {
        return ApiResponse.success(avatarService.updateAvatar(avatarId, request), traceId());
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
