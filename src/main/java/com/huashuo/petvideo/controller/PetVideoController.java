package com.huashuo.petvideo.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.petvideo.dto.PetWorkDownloadResponse;
import com.huashuo.petvideo.dto.PetWorkForkRequest;
import com.huashuo.petvideo.dto.PetVideoEstimateResponse;
import com.huashuo.petvideo.dto.PetVideoPreviewResponse;
import com.huashuo.petvideo.dto.PetVideoTaskResponse;
import com.huashuo.petvideo.dto.PetWorkResponse;
import com.huashuo.petvideo.service.PetVideoService;
import com.huashuo.user.service.UserFeaturePermissionService;
import com.huashuo.user.util.CurrentUser;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/pet-videos")
public class PetVideoController {

    private final PetVideoService petVideoService;
    private final UserFeaturePermissionService featurePermissionService;

    public PetVideoController(PetVideoService petVideoService,
                              UserFeaturePermissionService featurePermissionService) {
        this.petVideoService = petVideoService;
        this.featurePermissionService = featurePermissionService;
    }

    @PostMapping("/script")
    public ApiResponse<JsonNode> generateScript(@RequestBody JsonNode draft) {
        Long userId = requirePetUser();
        return ApiResponse.success(petVideoService.generateScript(draft, userId), traceId());
    }

    @PostMapping("/storyboard")
    public ApiResponse<JsonNode> generateStoryboard(@RequestBody JsonNode draft) {
        Long userId = requirePetUser();
        return ApiResponse.success(petVideoService.generateStoryboard(draft, userId), traceId());
    }

    @PostMapping("/estimate")
    public ApiResponse<PetVideoEstimateResponse> estimate(@RequestBody JsonNode draft) {
        Long userId = requirePetUser();
        return ApiResponse.success(petVideoService.estimate(draft, userId), traceId());
    }

    @PostMapping("/tasks/preview")
    public ApiResponse<PetVideoPreviewResponse> previewTask(@RequestBody JsonNode draft) {
        Long userId = requirePetUser();
        return ApiResponse.success(petVideoService.previewTask(draft, userId), traceId());
    }

    @PostMapping("/tasks")
    public ApiResponse<PetVideoTaskResponse> createTask(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody JsonNode draft
    ) {
        Long userId = requirePetUser();
        return ApiResponse.success(petVideoService.createTask(draft, userId, traceId(), idempotencyKey), traceId());
    }

    @GetMapping("/tasks/{taskId}")
    public ApiResponse<PetVideoTaskResponse> getTask(@PathVariable Long taskId) {
        Long userId = requirePetUser();
        return ApiResponse.success(petVideoService.getTask(taskId, userId), traceId());
    }

    @GetMapping("/works")
    public ApiResponse<List<PetWorkResponse>> listWorks(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String petType
    ) {
        Long userId = requirePetUser();
        return ApiResponse.success(petVideoService.listWorks(userId, status, keyword, petType), traceId());
    }

    @PostMapping("/works/{workId}/fork")
    public ApiResponse<PetWorkResponse> forkWork(
            @PathVariable Long workId,
            @RequestBody(required = false) PetWorkForkRequest request
    ) {
        Long userId = requirePetUser();
        return ApiResponse.success(petVideoService.forkWork(workId, request, userId), traceId());
    }

    @PostMapping("/works/{workId}/regenerate")
    public ApiResponse<PetVideoTaskResponse> regenerateWork(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @PathVariable Long workId
    ) {
        Long userId = requirePetUser();
        return ApiResponse.success(petVideoService.regenerateWork(workId, userId, traceId(), idempotencyKey), traceId());
    }

    @DeleteMapping("/works/{workId}")
    public ApiResponse<Void> deleteWork(@PathVariable Long workId) {
        Long userId = requirePetUser();
        petVideoService.deleteWork(workId, userId);
        return ApiResponse.success(null, traceId());
    }

    @GetMapping("/works/{workId}/download")
    public ApiResponse<PetWorkDownloadResponse> downloadWork(@PathVariable Long workId) {
        Long userId = requirePetUser();
        return ApiResponse.success(petVideoService.downloadWork(workId, userId), traceId());
    }

    private Long requirePetUser() {
        Long userId = CurrentUser.nullableUserId();
        featurePermissionService.assertPetCreationAccess(userId);
        return userId;
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
