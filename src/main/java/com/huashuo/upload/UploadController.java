package com.huashuo.upload;

import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.upload.vo.UploadedFileItem;
import jakarta.validation.constraints.NotNull;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/files")
public class UploadController {

    private final UploadService uploadService;

    public UploadController(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping("/upload")
    public ApiResponse<UploadedFileItem> upload(
            @RequestParam @NotNull Long projectId,
            @RequestParam @NotNull MultipartFile file
    ) {
        return ApiResponse.success(uploadService.upload(projectId, file), traceId());
    }

    @GetMapping
    public ApiResponse<List<UploadedFileItem>> listProjectFiles(@RequestParam @NotNull Long projectId) {
        return ApiResponse.success(uploadService.listProjectFiles(projectId), traceId());
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
