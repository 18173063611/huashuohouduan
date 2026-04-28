package com.huashuo.project;

import com.huashuo.common.config.FwxTraceIdFilter;
import com.huashuo.common.response.FwxApiResponse;
import com.huashuo.common.response.FwxPageResult;
import com.huashuo.project.dto.FwxCreateProjectRequest;
import com.huashuo.project.vo.FwxProjectItem;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects")
public class FwxProjectController {

    private final FwxProjectService projectService;

    public FwxProjectController(FwxProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping
    public FwxApiResponse<FwxPageResult<FwxProjectItem>> listProjects(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize
    ) {
        return FwxApiResponse.success(projectService.listProjects(pageNo, pageSize), traceId());
    }

    @PostMapping
    public FwxApiResponse<FwxProjectItem> createProject(@Valid @RequestBody FwxCreateProjectRequest request) {
        return FwxApiResponse.success(projectService.createProject(request), traceId());
    }

    @GetMapping("/{projectId}")
    public FwxApiResponse<FwxProjectItem> getProject(@PathVariable Long projectId) {
        return FwxApiResponse.success(projectService.getProject(projectId), traceId());
    }

    private String traceId() {
        return MDC.get(FwxTraceIdFilter.TRACE_ID);
    }
}
