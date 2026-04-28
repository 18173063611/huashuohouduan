package com.huashuo.upload;

import com.huashuo.common.config.FwxTraceIdFilter;
import com.huashuo.common.response.FwxApiResponse;
import com.huashuo.upload.vo.FwxUploadedFileItem;
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
public class FwxUploadController {

    private final FwxUploadService uploadService;

    public FwxUploadController(FwxUploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping("/upload")
    public FwxApiResponse<FwxUploadedFileItem> upload(
            @RequestParam @NotNull Long projectId,
            @RequestParam @NotNull MultipartFile file
    ) {
        return FwxApiResponse.success(uploadService.upload(projectId, file), traceId());
    }

    @GetMapping
    public FwxApiResponse<List<FwxUploadedFileItem>> listProjectFiles(@RequestParam @NotNull Long projectId) {
        return FwxApiResponse.success(uploadService.listProjectFiles(projectId), traceId());
    }

    private String traceId() {
        return MDC.get(FwxTraceIdFilter.TRACE_ID);
    }
}
