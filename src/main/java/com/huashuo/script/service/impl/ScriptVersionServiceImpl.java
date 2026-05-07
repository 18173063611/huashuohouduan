package com.huashuo.script.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.script.entity.ScriptVersionEntity;
import com.huashuo.script.mapper.ScriptVersionMapper;
import com.huashuo.script.service.ScriptVersionService;
import com.huashuo.script.vo.ScriptVersionItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.OptionalLong;

@Service
/**
 * 脚本版本服务实现：负责脚本版本入库、按项目排序查询，并校验脚本是否属于当前项目。
 */
public class ScriptVersionServiceImpl implements ScriptVersionService {

    private final ScriptVersionMapper scriptVersionMapper;

    public ScriptVersionServiceImpl(ScriptVersionMapper scriptVersionMapper) {
        this.scriptVersionMapper = scriptVersionMapper;
    }

    @Override
    public List<ScriptVersionItem> listByProject(Long projectId, OptionalLong viewerUserId) {
        LambdaQueryWrapper<ScriptVersionEntity> w = new LambdaQueryWrapper<>();
        if (projectId != null) {
            w.eq(ScriptVersionEntity::getProjectId, projectId);
        } else {
            applyGlobalScriptVisibility(w, viewerUserId);
        }
        w.orderByDesc(ScriptVersionEntity::getVersionNo, ScriptVersionEntity::getScriptVersionId);
        return scriptVersionMapper.selectList(w).stream().map(this::toItem).toList();
    }

    /**
     * projectId 为空（全局列表）：未登录仅公共脚本；已登录为 公共 ∪ 本人。
     */
    private void applyGlobalScriptVisibility(LambdaQueryWrapper<ScriptVersionEntity> w, OptionalLong viewerUserId) {
        if (viewerUserId.isEmpty()) {
            w.isNull(ScriptVersionEntity::getOwnerUserId);
        } else {
            long uid = viewerUserId.getAsLong();
            w.and(q -> q.isNull(ScriptVersionEntity::getOwnerUserId).or().eq(ScriptVersionEntity::getOwnerUserId, uid));
        }
    }

    @Override
    public ScriptVersionItem requireForProject(Long projectId, Long scriptVersionId, OptionalLong viewerUserId) {
        ScriptVersionEntity entity = scriptVersionMapper.selectById(scriptVersionId);
        if (entity == null) {
            throw new BusinessException(40400, "Script version does not exist for this project");
        }
        if (projectId != null) {
            if (entity.getProjectId() == null || !entity.getProjectId().equals(projectId)) {
                throw new BusinessException(40400, "Script version does not exist for this project");
            }
        }
        assertScriptReadable(entity, viewerUserId);
        return toItem(entity);
    }

    private void assertScriptReadable(ScriptVersionEntity entity, OptionalLong viewerUserId) {
        Long owner = entity.getOwnerUserId();
        if (owner == null) {
            return;
        }
        if (viewerUserId.isEmpty()) {
            throw new BusinessException(40100, "请先登录后再访问该脚本");
        }
        if (!owner.equals(viewerUserId.getAsLong())) {
            throw new BusinessException(40400, "Script version does not exist for this project");
        }
    }

    @Override
    @Transactional
    public ScriptVersionItem createVersion(Long projectId, String content, String sourceType, Long ownerUserId) {
        int nextNo = nextVersionNo(projectId, ownerUserId);
        ScriptVersionEntity entity = new ScriptVersionEntity();
        entity.setProjectId(projectId);
        entity.setOwnerUserId(ownerUserId);
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

    private int nextVersionNo(Long projectId, Long ownerUserId) {
        LambdaQueryWrapper<ScriptVersionEntity> w = new LambdaQueryWrapper<>();
        if (projectId == null) {
            w.isNull(ScriptVersionEntity::getProjectId);
        } else {
            w.eq(ScriptVersionEntity::getProjectId, projectId);
        }
        if (ownerUserId == null) {
            w.isNull(ScriptVersionEntity::getOwnerUserId);
        } else {
            w.eq(ScriptVersionEntity::getOwnerUserId, ownerUserId);
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
                entity.getOwnerUserId(),
                entity.getVersionNo(),
                entity.getContent(),
                entity.getSourceType(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
