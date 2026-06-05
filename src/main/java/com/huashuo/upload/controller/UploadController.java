package com.huashuo.upload.controller;

import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.common.response.PageResult;
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
            @RequestParam(required = false) String storage,
            @RequestParam @NotNull MultipartFile file
    ) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        Long ownerId = viewer.isPresent() ? viewer.getAsLong() : null;
        boolean localStorage = storage != null && storage.equalsIgnoreCase("local");
        UploadedFileItem uploaded = localStorage
                ? uploadService.uploadLocal(projectId, file, ownerId)
                : uploadService.upload(projectId, file, ownerId);
        return ApiResponse.success(uploaded, traceId());
    }

    @GetMapping
    public ApiResponse<PageResult<UploadedFileItem>> listProjectFiles(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageNo,
            @RequestParam(defaultValue = "20") int pageSize
    ) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        int currentPage = pageNo != null ? pageNo : (page != null ? page : 1);
        return ApiResponse.success(uploadService.listProjectFiles(viewer, projectId, currentPage, pageSize), traceId());
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
