package com.huashuo.template.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.template.entity.TemplateAssetRelEntity;
import com.huashuo.template.entity.TemplateEntity;
import com.huashuo.template.mapper.TemplateAssetRelMapper;
import com.huashuo.template.mapper.TemplateMapper;
import com.huashuo.template.service.TemplateService;
import com.huashuo.template.vo.TemplateCreateRequest;
import com.huashuo.template.vo.TemplateItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

@Service
public class TemplateServiceImpl implements TemplateService {

    private static final String VISIBILITY_PUBLIC = "PUBLIC";
    private static final String VISIBILITY_PRIVATE = "PRIVATE";
    private static final String STATUS_ACTIVE = "ACTIVE";

    private final TemplateMapper templateMapper;
    private final TemplateAssetRelMapper relMapper;

    public TemplateServiceImpl(TemplateMapper templateMapper, TemplateAssetRelMapper relMapper) {
        this.templateMapper = templateMapper;
        this.relMapper = relMapper;
    }

    @Override
    public List<TemplateItem> listTemplates(OptionalLong viewerUserId, String scope, String keyword, String sort, String tag) {
        String normalizedScope = normalizeScope(scope);
        String normalizedKeyword = normalizeKeyword(keyword);
        String normalizedTag = normalizeKeyword(tag);
        String normalizedSort = normalizeSort(sort);

        LambdaQueryWrapper<TemplateEntity> w = new LambdaQueryWrapper<>();
        applyVisibilityScope(w, viewerUserId, normalizedScope);
        w.eq(TemplateEntity::getStatus, STATUS_ACTIVE);
        if (normalizedKeyword != null) {
            w.apply("lower(title) like {0}", "%" + normalizedKeyword.toLowerCase() + "%");
        }
        if (normalizedTag != null) {
            w.apply("lower(tags) like {0}", "%" + normalizedTag.toLowerCase() + "%");
        }
        applySort(w, normalizedSort);
        List<TemplateEntity> rows = templateMapper.selectList(w);
        return rows.stream().map(this::toItem).toList();
    }

    @Override
    public TemplateItem getTemplateForViewer(Long templateId, OptionalLong viewerUserId) {
        TemplateEntity entity = templateMapper.selectById(templateId);
        if (entity == null) {
            throw new BusinessException(40400, "Template does not exist");
        }
        assertReadable(entity, viewerUserId);
        return toItem(entity);
    }

    @Override
    @Transactional
    public TemplateItem createTemplate(OptionalLong viewerUserId, TemplateCreateRequest request) {
        if (viewerUserId.isEmpty()) {
            throw new BusinessException(40100, "请先登录后再创建模板");
        }
        long uid = viewerUserId.getAsLong();
        TemplateEntity entity = new TemplateEntity();
        entity.setOwnerUserId(uid);
        entity.setCreatedByUserId(uid);
        entity.setVisibility(VISIBILITY_PRIVATE);
        entity.setStatus(STATUS_ACTIVE);
        entity.setPublishedAt(null);
        entity.setVersionNo(1);
        entity.setTitle(request.title());
        entity.setDescription(request.description());
        entity.setCoverAssetId(request.coverAssetId());
        entity.setTags(request.tags());
        entity.setMetadataJson(request.metadataJson());
        templateMapper.insert(entity);

        Long templateId = entity.getTemplateId();
        if (templateId == null) {
            throw new BusinessException(50000, "Failed to create template");
        }
        if (request.assets() != null) {
            for (TemplateCreateRequest.TemplateAssetBind bind : request.assets()) {
                if (bind == null || bind.assetId() == null) {
                    continue;
                }
                TemplateAssetRelEntity rel = new TemplateAssetRelEntity();
                rel.setTemplateId(templateId);
                rel.setAssetId(bind.assetId());
                rel.setAssetRole(bind.role() == null || bind.role().isBlank() ? "MATERIAL" : bind.role().trim());
                relMapper.insert(rel);
            }
        }
        TemplateEntity loaded = templateMapper.selectById(templateId);
        if (loaded == null) {
            throw new BusinessException(50000, "Failed to load template after create");
        }
        return toItem(loaded);
    }

    @Override
    @Transactional
    public TemplateItem publishTemplate(Long templateId, OptionalLong viewerUserId) {
        if (viewerUserId.isEmpty()) {
            throw new BusinessException(40100, "请先登录后再发布模板");
        }
        long uid = viewerUserId.getAsLong();
        TemplateEntity entity = templateMapper.selectById(templateId);
        if (entity == null) {
            throw new BusinessException(40400, "Template does not exist");
        }
        if (entity.getOwnerUserId() == null || !entity.getOwnerUserId().equals(uid)) {
            throw new BusinessException(40300, "无权发布该模板");
        }
        if (VISIBILITY_PUBLIC.equalsIgnoreCase(safeVisibility(entity))) {
            return toItem(entity);
        }
        LambdaUpdateWrapper<TemplateEntity> uw = new LambdaUpdateWrapper<>();
        uw.eq(TemplateEntity::getTemplateId, templateId)
                .set(TemplateEntity::getVisibility, VISIBILITY_PUBLIC)
                .set(TemplateEntity::getPublishedAt, LocalDateTime.now())
                .set(TemplateEntity::getUpdatedAt, LocalDateTime.now());
        templateMapper.update(null, uw);
        TemplateEntity loaded = templateMapper.selectById(templateId);
        if (loaded == null) {
            throw new BusinessException(50000, "Failed to load template after publish");
        }
        return toItem(loaded);
    }

    @Override
    @Transactional
    public TemplateItem forkTemplate(Long templateId, OptionalLong viewerUserId) {
        if (viewerUserId.isEmpty()) {
            throw new BusinessException(40100, "请先登录后再复制模板");
        }
        long uid = viewerUserId.getAsLong();
        TemplateEntity entity = templateMapper.selectById(templateId);
        if (entity == null) {
            throw new BusinessException(40400, "Template does not exist");
        }
        assertReadable(entity, viewerUserId);

        // 复制模板主体
        TemplateEntity copy = new TemplateEntity();
        copy.setOwnerUserId(uid);
        copy.setCreatedByUserId(uid);
        copy.setVisibility(VISIBILITY_PRIVATE);
        copy.setStatus(STATUS_ACTIVE);
        copy.setPublishedAt(null);
        copy.setVersionNo(1);
        copy.setTitle(entity.getTitle());
        copy.setDescription(entity.getDescription());
        copy.setCoverAssetId(entity.getCoverAssetId());
        copy.setTags(entity.getTags());
        copy.setMetadataJson(entity.getMetadataJson());
        templateMapper.insert(copy);

        Long newId = copy.getTemplateId();
        if (newId == null) {
            throw new BusinessException(50000, "Failed to fork template");
        }

        // 复制关联资产
        List<TemplateAssetRelEntity> rels = listRels(templateId);
        for (TemplateAssetRelEntity rel : rels) {
            TemplateAssetRelEntity relCopy = new TemplateAssetRelEntity();
            relCopy.setTemplateId(newId);
            relCopy.setAssetId(rel.getAssetId());
            relCopy.setAssetRole(rel.getAssetRole());
            relMapper.insert(relCopy);
        }

        TemplateEntity loaded = templateMapper.selectById(newId);
        if (loaded == null) {
            throw new BusinessException(50000, "Failed to load template after fork");
        }
        return toItem(loaded);
    }

    private TemplateItem toItem(TemplateEntity entity) {
        List<TemplateAssetRelEntity> rels = listRels(entity.getTemplateId());
        List<TemplateItem.TemplateAssetRef> refs = new ArrayList<>();
        for (TemplateAssetRelEntity rel : rels) {
            refs.add(new TemplateItem.TemplateAssetRef(rel.getAssetId(), rel.getAssetRole()));
        }
        return new TemplateItem(
                entity.getTemplateId(),
                entity.getOwnerUserId(),
                entity.getCreatedByUserId(),
                safeVisibility(entity),
                safeStatus(entity),
                entity.getPublishedAt(),
                entity.getVersionNo(),
                entity.getTitle(),
                entity.getDescription(),
                entity.getCoverAssetId(),
                entity.getTags(),
                entity.getMetadataJson(),
                refs,
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private List<TemplateAssetRelEntity> listRels(Long templateId) {
        if (templateId == null) {
            return List.of();
        }
        LambdaQueryWrapper<TemplateAssetRelEntity> w = new LambdaQueryWrapper<>();
        w.eq(TemplateAssetRelEntity::getTemplateId, templateId);
        return relMapper.selectList(w);
    }

    private void assertReadable(TemplateEntity entity, OptionalLong viewerUserId) {
        if (VISIBILITY_PUBLIC.equalsIgnoreCase(safeVisibility(entity))) {
            if (!STATUS_ACTIVE.equalsIgnoreCase(safeStatus(entity))) {
                throw new BusinessException(40400, "Template does not exist");
            }
            return;
        }
        if (viewerUserId.isEmpty()) {
            throw new BusinessException(40400, "Template does not exist");
        }
        Long owner = entity.getOwnerUserId();
        if (owner == null || !owner.equals(viewerUserId.getAsLong())) {
            throw new BusinessException(40400, "Template does not exist");
        }
    }

    private String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return "all";
        }
        String s = scope.trim().toLowerCase();
        return switch (s) {
            case "public", "global" -> "public";
            case "private", "mine" -> "private";
            default -> "all";
        };
    }

    private void applyVisibilityScope(LambdaQueryWrapper<TemplateEntity> w, OptionalLong viewerUserId, String normalizedScope) {
        switch (normalizedScope) {
            case "public":
                w.eq(TemplateEntity::getVisibility, VISIBILITY_PUBLIC);
                break;
            case "private":
                if (viewerUserId.isEmpty()) {
                    w.eq(TemplateEntity::getTemplateId, -1);
                } else {
                    w.eq(TemplateEntity::getVisibility, VISIBILITY_PRIVATE)
                            .eq(TemplateEntity::getOwnerUserId, viewerUserId.getAsLong());
                }
                break;
            case "all":
            default:
                if (viewerUserId.isEmpty()) {
                    w.eq(TemplateEntity::getVisibility, VISIBILITY_PUBLIC);
                } else {
                    long uid = viewerUserId.getAsLong();
                    w.and(q -> q.eq(TemplateEntity::getVisibility, VISIBILITY_PUBLIC)
                            .or()
                            .eq(TemplateEntity::getVisibility, VISIBILITY_PRIVATE).eq(TemplateEntity::getOwnerUserId, uid));
                }
        }
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String trimmed = keyword.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return "published_desc";
        }
        String s = sort.trim();
        return switch (s) {
            case "publishedAtDesc", "published_desc" -> "published_desc";
            case "createdAtDesc", "created_desc" -> "created_desc";
            case "createdAtAsc", "created_asc" -> "created_asc";
            default -> "published_desc";
        };
    }

    private void applySort(LambdaQueryWrapper<TemplateEntity> w, String normalizedSort) {
        switch (normalizedSort) {
            case "created_asc":
                w.orderByAsc(TemplateEntity::getCreatedAt).orderByAsc(TemplateEntity::getTemplateId);
                break;
            case "created_desc":
                w.orderByDesc(TemplateEntity::getCreatedAt).orderByDesc(TemplateEntity::getTemplateId);
                break;
            case "published_desc":
            default:
                w.orderByDesc(TemplateEntity::getPublishedAt)
                        .orderByDesc(TemplateEntity::getCreatedAt)
                        .orderByDesc(TemplateEntity::getTemplateId);
        }
    }

    private String safeVisibility(TemplateEntity entity) {
        if (entity.getVisibility() == null || entity.getVisibility().isBlank()) {
            return entity.getOwnerUserId() == null ? VISIBILITY_PUBLIC : VISIBILITY_PRIVATE;
        }
        return entity.getVisibility().trim().toUpperCase();
    }

    private String safeStatus(TemplateEntity entity) {
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            return STATUS_ACTIVE;
        }
        return entity.getStatus().trim().toUpperCase();
    }
}

