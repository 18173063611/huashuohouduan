package com.huashuo.project;

import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.common.response.PageResult;
import com.huashuo.project.dto.CreateProjectRequest;
import com.huashuo.project.dto.UpdateProjectRequest;
import com.huashuo.project.vo.ProjectItem;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping
    public ApiResponse<PageResult<ProjectItem>> listProjects(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageNo,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String keyword
    ) {
        int currentPage = pageNo != null ? pageNo : (page != null ? page : 1);
        return ApiResponse.success(projectService.listProjects(currentPage, pageSize, keyword), traceId());
    }

    @PostMapping
    public ApiResponse<ProjectItem> createProject(@Valid @RequestBody CreateProjectRequest request) {
        return ApiResponse.success(projectService.createProject(request), traceId());
    }

    @GetMapping("/{projectId}")
    public ApiResponse<ProjectItem> getProject(@PathVariable Long projectId) {
        return ApiResponse.success(projectService.getProject(projectId), traceId());
    }

    @PutMapping("/{projectId}")
    public ApiResponse<ProjectItem> updateProject(
            @PathVariable Long projectId,
            @Valid @RequestBody UpdateProjectRequest request
    ) {
        return ApiResponse.success(projectService.updateProject(projectId, request), traceId());
    }

    @DeleteMapping("/{projectId}")
    public ApiResponse<Void> deleteProject(@PathVariable Long projectId) {
        projectService.deleteProject(projectId);
        return ApiResponse.success(null, traceId());
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
