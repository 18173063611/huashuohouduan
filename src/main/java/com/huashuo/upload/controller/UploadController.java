package com.huashuo.upload.controller;

import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.upload.service.UploadService;
import com.huashuo.upload.vo.UploadedFileItem;
import com.huashuo.user.service.UserAuthService;
import jakarta.validation.constraints.NotNull;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.OptionalLong;

@Validated
/**
 * 文件上传接口：接收用户上传的视频、音频或素材文件，并把上传结果交给服务层保存为文件与资产记录。
 */
@RestController
@RequestMapping("/api/v1/uploads")
public class UploadController {

    private final UploadService uploadService;
    private final UserAuthService userAuthService;

    public UploadController(UploadService uploadService, UserAuthService userAuthService) {
        this.uploadService = uploadService;
        this.userAuthService = userAuthService;
    }

    @PostMapping
    public ApiResponse<UploadedFileItem> upload(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @RequestParam(required = false) Long projectId,
            @RequestParam @NotNull MultipartFile file
    ) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        Long ownerId = viewer.isPresent() ? viewer.getAsLong() : null;
        return ApiResponse.success(uploadService.upload(projectId, file, ownerId), traceId());
    }

    @GetMapping
    public ApiResponse<List<UploadedFileItem>> listProjectFiles(@RequestParam(required = false) Long projectId) {
        return ApiResponse.success(uploadService.listProjectFiles(projectId), traceId());
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
