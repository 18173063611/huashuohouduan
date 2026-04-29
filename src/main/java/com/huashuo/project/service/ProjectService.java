package com.huashuo.project.service;

import com.huashuo.common.response.PageResult;
import com.huashuo.project.dto.CreateProjectRequest;
import com.huashuo.project.dto.UpdateProjectRequest;
import com.huashuo.project.vo.ProjectItem;

/**
 * 项目服务接口：定义项目生命周期能力，具体业务规则由 ProjectServiceImpl 实现。
 */
public interface ProjectService {

    ProjectItem createProject(CreateProjectRequest request);

    PageResult<ProjectItem> listProjects(int pageNo, int pageSize, String keyword);

    ProjectItem updateProject(Long projectId, UpdateProjectRequest request);

    void deleteProject(Long projectId);

    ProjectItem getProject(Long projectId);
}
