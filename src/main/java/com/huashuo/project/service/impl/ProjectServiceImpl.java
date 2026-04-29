package com.huashuo.project.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.common.response.PageResult;
import com.huashuo.project.dto.CreateProjectRequest;
import com.huashuo.project.dto.UpdateProjectRequest;
import com.huashuo.project.entity.ProjectEntity;
import com.huashuo.project.mapper.ProjectMapper;
import com.huashuo.project.service.ProjectService;
import com.huashuo.project.vo.ProjectItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
/**
 * 项目服务实现：负责项目数据校验、分页查询、状态更新和软删除等核心业务逻辑。
 */
public class ProjectServiceImpl implements ProjectService {

    private static final Set<String> ALLOWED_PROJECT_STATUSES = Set.of("DRAFT", "MAKING", "DONE");

    private final ProjectMapper projectMapper;

    public ProjectServiceImpl(ProjectMapper projectMapper) {
        this.projectMapper = projectMapper;
    }

    @Override
    @Transactional
    public ProjectItem createProject(CreateProjectRequest request) {
        ProjectEntity entity = new ProjectEntity();
        entity.setProjectName(request.projectName());
        entity.setDescription(request.description());
        entity.setStatus("DRAFT");
        projectMapper.insert(entity);
        ProjectEntity loaded = projectMapper.selectById(entity.getProjectId());
        if (loaded == null) {
            throw new BusinessException(50000, "Failed to load project after insert");
        }
        return toItem(loaded);
    }

    @Override
    public PageResult<ProjectItem> listProjects(int pageNo, int pageSize, String keyword) {
        int safePageNo = Math.max(pageNo, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), 100);
        String searchKeyword = normalizeKeyword(keyword);

        LambdaQueryWrapper<ProjectEntity> wrapper = new LambdaQueryWrapper<>();
        if (searchKeyword != null) {
            wrapper.and(w -> w.like(ProjectEntity::getProjectName, searchKeyword)
                    .or()
                    .like(ProjectEntity::getDescription, searchKeyword));
        }
        wrapper.orderByDesc(ProjectEntity::getUpdatedAt, ProjectEntity::getProjectId);

        long total = projectMapper.selectCount(wrapper);
        int offset = (safePageNo - 1) * safePageSize;
        wrapper.last("limit " + safePageSize + " offset " + offset);
        List<ProjectItem> records = projectMapper.selectList(wrapper).stream().map(this::toItem).toList();
        return new PageResult<>(records, safePageNo, safePageSize, total);
    }

    @Override
    @Transactional
    public ProjectItem updateProject(Long projectId, UpdateProjectRequest request) {
        ProjectItem existingProject = getProject(projectId);
        String status = request.status() == null || request.status().isBlank() ? existingProject.status() : request.status().trim();
        if (!ALLOWED_PROJECT_STATUSES.contains(status)) {
            throw new BusinessException(40000, "Unsupported project status");
        }
        LambdaUpdateWrapper<ProjectEntity> uw = new LambdaUpdateWrapper<>();
        uw.eq(ProjectEntity::getProjectId, projectId)
                .set(ProjectEntity::getProjectName, request.projectName())
                .set(ProjectEntity::getDescription, request.description())
                .set(ProjectEntity::getStatus, status)
                .set(ProjectEntity::getUpdatedAt, LocalDateTime.now());
        projectMapper.update(null, uw);
        return getProject(projectId);
    }

    @Override
    @Transactional
    public void deleteProject(Long projectId) {
        getProject(projectId);
        projectMapper.deleteById(projectId);
    }

    @Override
    public ProjectItem getProject(Long projectId) {
        ProjectEntity entity = projectMapper.selectById(projectId);
        if (entity == null) {
            throw new BusinessException(40400, "Project does not exist");
        }
        return toItem(entity);
    }

    private ProjectItem toItem(ProjectEntity entity) {
        return new ProjectItem(
                entity.getProjectId(),
                entity.getProjectName(),
                entity.getDescription(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        return "%" + keyword.trim() + "%";
    }
}
