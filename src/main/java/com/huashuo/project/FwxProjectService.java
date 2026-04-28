package com.huashuo.project;

import com.huashuo.common.exception.FwxBusinessException;
import com.huashuo.common.response.FwxPageResult;
import com.huashuo.project.dto.FwxCreateProjectRequest;
import com.huashuo.project.vo.FwxProjectItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FwxProjectService {

    private final FwxProjectRepository projectRepository;

    public FwxProjectService(FwxProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    @Transactional
    public FwxProjectItem createProject(FwxCreateProjectRequest request) {
        return projectRepository.create(request);
    }

    public FwxPageResult<FwxProjectItem> listProjects(int pageNo, int pageSize) {
        int safePageNo = Math.max(pageNo, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), 100);
        int offset = (safePageNo - 1) * safePageSize;
        return new FwxPageResult<>(
                projectRepository.findPage(safePageSize, offset),
                safePageNo,
                safePageSize,
                projectRepository.count()
        );
    }

    public FwxProjectItem getProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new FwxBusinessException(40400, "项目不存在"));
    }
}
