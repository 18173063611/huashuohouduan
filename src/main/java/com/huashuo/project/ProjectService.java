package com.huashuo.project;

import com.huashuo.common.exception.BusinessException;
import com.huashuo.common.response.PageResult;
import com.huashuo.project.dto.CreateProjectRequest;
import com.huashuo.project.vo.ProjectItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;

    public ProjectService(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    @Transactional
    public ProjectItem createProject(CreateProjectRequest request) {
        return projectRepository.create(request);
    }

    public PageResult<ProjectItem> listProjects(int pageNo, int pageSize) {
        // 分页参数在服务层做边界保护，避免前端传入 0、负数或过大的 pageSize。
        int safePageNo = Math.max(pageNo, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), 100);
        int offset = (safePageNo - 1) * safePageSize;
        return new PageResult<>(
                projectRepository.findPage(safePageSize, offset),
                safePageNo,
                safePageSize,
                projectRepository.count()
        );
    }

    public ProjectItem getProject(Long projectId) {
        // 项目是后续任务、资产、上传的根对象，依赖它的接口都必须先确认项目存在。
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(40400, "Project does not exist"));
    }
}
