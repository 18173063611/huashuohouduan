package com.huashuo.writer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.project.service.ProjectService;
import com.huashuo.script.entity.ScriptVersionEntity;
import com.huashuo.script.mapper.ScriptVersionMapper;
import com.huashuo.writer.dto.ApplyWriterScriptRequest;
import com.huashuo.writer.dto.UpdateWriterScriptRequest;
import com.huashuo.writer.service.WriterScriptService;
import com.huashuo.writer.vo.WriterScriptItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
/**
 * 文案改写页面脚本服务实现：复用 script_version 表保存当前项目最终采用文案。
 */
public class WriterScriptServiceImpl implements WriterScriptService {

    private static final String CURRENT_STEP = "SCRIPT_REWRITE";
    private static final String NEXT_STEP = "VOICE_GENERATE";
    private static final String SOURCE_TYPE = "WRITER_APPLY";

    private final ScriptVersionMapper scriptVersionMapper;
    private final ProjectService projectService;

    public WriterScriptServiceImpl(ScriptVersionMapper scriptVersionMapper, ProjectService projectService) {
        this.scriptVersionMapper = scriptVersionMapper;
        this.projectService = projectService;
    }

    @Override
    @Transactional
    public WriterScriptItem apply(ApplyWriterScriptRequest request) {
        projectService.getProject(request.projectId());

        ScriptVersionEntity entity = new ScriptVersionEntity();
        entity.setProjectId(request.projectId());
        entity.setParseId(request.parseId());
        entity.setVersionNo(nextVersionNo(request.projectId()));
        entity.setSourceScript(request.sourceScript());
        entity.setContent(request.finalScript());
        entity.setSourceType(SOURCE_TYPE);
        entity.setRewriteStyle(normalizeRewriteStyle(request.rewriteStyle()));
        scriptVersionMapper.insert(entity);

        ScriptVersionEntity loaded = scriptVersionMapper.selectById(entity.getScriptVersionId());
        if (loaded == null) {
            throw new BusinessException(50000, "Failed to load writer script after apply");
        }
        return toItem(loaded);
    }

    @Override
    public WriterScriptItem getCurrent(Long projectId) {
        projectService.getProject(projectId);
        ScriptVersionEntity latest = latestForProject(projectId);
        return latest == null ? null : toItem(latest);
    }

    @Override
    @Transactional
    public WriterScriptItem update(Long scriptId, UpdateWriterScriptRequest request) {
        projectService.getProject(request.projectId());
        ScriptVersionEntity existing = scriptVersionMapper.selectById(scriptId);
        if (existing == null || existing.getProjectId() == null || !existing.getProjectId().equals(request.projectId())) {
            throw new BusinessException(40400, "Writer script does not exist for this project");
        }

        LambdaUpdateWrapper<ScriptVersionEntity> uw = new LambdaUpdateWrapper<>();
        uw.eq(ScriptVersionEntity::getScriptVersionId, scriptId)
                .eq(ScriptVersionEntity::getProjectId, request.projectId())
                .set(ScriptVersionEntity::getContent, request.finalScript())
                .set(ScriptVersionEntity::getUpdatedAt, LocalDateTime.now());
        scriptVersionMapper.update(null, uw);

        ScriptVersionEntity loaded = scriptVersionMapper.selectById(scriptId);
        if (loaded == null) {
            throw new BusinessException(50000, "Failed to load writer script after update");
        }
        return toItem(loaded);
    }

    private int nextVersionNo(Long projectId) {
        ScriptVersionEntity latest = latestForProject(projectId);
        if (latest == null || latest.getVersionNo() == null) {
            return 1;
        }
        return latest.getVersionNo() + 1;
    }

    private ScriptVersionEntity latestForProject(Long projectId) {
        LambdaQueryWrapper<ScriptVersionEntity> w = new LambdaQueryWrapper<>();
        w.eq(ScriptVersionEntity::getProjectId, projectId)
                .orderByDesc(ScriptVersionEntity::getVersionNo, ScriptVersionEntity::getScriptVersionId)
                .last("limit 1");
        return scriptVersionMapper.selectOne(w);
    }

    private String normalizeRewriteStyle(String rewriteStyle) {
        if (rewriteStyle == null || rewriteStyle.isBlank()) {
            return "口语化风格";
        }
        return rewriteStyle.trim();
    }

    private WriterScriptItem toItem(ScriptVersionEntity entity) {
        return new WriterScriptItem(
                entity.getScriptVersionId(),
                entity.getProjectId(),
                entity.getParseId(),
                entity.getVersionNo(),
                CURRENT_STEP,
                NEXT_STEP,
                entity.getSourceScript(),
                entity.getContent(),
                entity.getRewriteStyle(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
