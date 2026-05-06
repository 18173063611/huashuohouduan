package com.huashuo.script.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.project.service.ProjectService;
import com.huashuo.script.entity.ScriptVersionEntity;
import com.huashuo.script.mapper.ScriptVersionMapper;
import com.huashuo.script.service.ScriptVersionService;
import com.huashuo.script.vo.ScriptVersionItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
/**
 * 脚本版本服务实现：负责脚本版本入库、按项目排序查询，并校验脚本是否属于当前项目。
 */
public class ScriptVersionServiceImpl implements ScriptVersionService {

    private final ScriptVersionMapper scriptVersionMapper;
    private final ProjectService projectService;

    public ScriptVersionServiceImpl(ScriptVersionMapper scriptVersionMapper, ProjectService projectService) {
        this.scriptVersionMapper = scriptVersionMapper;
        this.projectService = projectService;
    }

    @Override
    public List<ScriptVersionItem> listByProject(Long projectId) {
        LambdaQueryWrapper<ScriptVersionEntity> w = new LambdaQueryWrapper<>();
        if (projectId != null) {
            projectService.getProject(projectId);
            w.eq(ScriptVersionEntity::getProjectId, projectId);
        }
        w.orderByDesc(ScriptVersionEntity::getVersionNo, ScriptVersionEntity::getScriptVersionId);
        return scriptVersionMapper.selectList(w).stream().map(this::toItem).toList();
    }

    @Override
    public ScriptVersionItem requireForProject(Long projectId, Long scriptVersionId) {
        ScriptVersionEntity entity = scriptVersionMapper.selectById(scriptVersionId);
        if (entity == null) {
            throw new BusinessException(40400, "Script version does not exist for this project");
        }
        if (projectId != null) {
            projectService.getProject(projectId);
            if (entity.getProjectId() == null || !entity.getProjectId().equals(projectId)) {
                throw new BusinessException(40400, "Script version does not exist for this project");
            }
        }
        return toItem(entity);
    }

    @Override
    @Transactional
    public ScriptVersionItem createVersion(Long projectId, String content, String sourceType) {
        if (projectId != null) {
            projectService.getProject(projectId);
        }
        int nextNo = nextVersionNo(projectId);
        ScriptVersionEntity entity = new ScriptVersionEntity();
        entity.setProjectId(projectId);
        entity.setVersionNo(nextNo);
        entity.setContent(content);
        entity.setSourceType(sourceType);
        scriptVersionMapper.insert(entity);
        ScriptVersionEntity loaded = scriptVersionMapper.selectById(entity.getScriptVersionId());
        if (loaded == null) {
            throw new BusinessException(50000, "Failed to load script version after insert");
        }
        return toItem(loaded);
    }

    private int nextVersionNo(Long projectId) {
        LambdaQueryWrapper<ScriptVersionEntity> w = new LambdaQueryWrapper<>();
        if (projectId == null) {
            w.isNull(ScriptVersionEntity::getProjectId);
        } else {
            w.eq(ScriptVersionEntity::getProjectId, projectId);
        }
        w.orderByDesc(ScriptVersionEntity::getVersionNo)
                .last("limit 1");
        ScriptVersionEntity latest = scriptVersionMapper.selectOne(w);
        if (latest == null || latest.getVersionNo() == null) {
            return 1;
        }
        return latest.getVersionNo() + 1;
    }

    private ScriptVersionItem toItem(ScriptVersionEntity entity) {
        return new ScriptVersionItem(
                entity.getScriptVersionId(),
                entity.getProjectId(),
                entity.getVersionNo(),
                entity.getContent(),
                entity.getSourceType(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
