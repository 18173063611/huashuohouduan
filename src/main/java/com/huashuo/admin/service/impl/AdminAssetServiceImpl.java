package com.huashuo.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.huashuo.admin.service.AdminAssetService;
import com.huashuo.admin.service.AdminOperationAuditService;
import com.huashuo.admin.service.AdminOperationContext;
import com.huashuo.asset.entity.AssetEntity;
import com.huashuo.asset.mapper.AssetMapper;
import com.huashuo.asset.service.AssetService;
import com.huashuo.asset.vo.AssetItem;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.common.response.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class AdminAssetServiceImpl implements AdminAssetService {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String VISIBILITY_PUBLIC = "PUBLIC";
    private static final String VISIBILITY_PRIVATE = "PRIVATE";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_REMOVED = "REMOVED";
    private static final String STATUS_PENDING_SAVE = "PENDING_SAVE";
    private static final Set<String> ALLOWED_VISIBILITIES = Set.of(VISIBILITY_PUBLIC, VISIBILITY_PRIVATE);
    private static final Set<String> ALLOWED_STATUSES = Set.of(STATUS_ACTIVE, STATUS_REMOVED, STATUS_PENDING_SAVE);

    private final AssetMapper assetMapper;
    private final AssetService assetService;
    private final AdminOperationAuditService auditService;

    public AdminAssetServiceImpl(AssetMapper assetMapper, AssetService assetService,
                                 AdminOperationAuditService auditService) {
        this.assetMapper = assetMapper;
        this.assetService = assetService;
        this.auditService = auditService;
    }

    @Override
    public PageResult<AssetItem> listAssets(Long ownerUserId, String visibility, String status, String assetType,
                                            String sourceType, String assetGroup, String keyword, Integer pageNo, Integer pageSize) {
        int page = normalizePage(pageNo);
        int size = normalizePageSize(pageSize);
        LambdaQueryWrapper<AssetEntity> wrapper = new LambdaQueryWrapper<>();
        if (ownerUserId != null) {
            wrapper.eq(AssetEntity::getOwnerUserId, ownerUserId);
        }
        String normalizedVisibility = normalizeVisibilityFilter(visibility);
        if (normalizedVisibility != null) {
            applyVisibilityFilter(wrapper, normalizedVisibility);
        }
        String normalizedStatus = normalizeStatusFilter(status);
        if (normalizedStatus != null) {
            applyStatusFilter(wrapper, normalizedStatus);
        }
        String normalizedType = trimToUpper(assetType);
        if (normalizedType != null) {
            wrapper.eq(AssetEntity::getAssetType, normalizedType);
        }
        String normalizedSource = trimToNull(sourceType);
        if (normalizedSource != null) {
            wrapper.eq(AssetEntity::getSourceType, normalizedSource);
        }
        String normalizedGroup = trimToNull(assetGroup);
        if (normalizedGroup != null) {
            wrapper.eq(AssetEntity::getAssetGroup, normalizedGroup);
        }
        applyKeywordFilter(wrapper, trimToNull(keyword));
        long total = assetMapper.selectCount(wrapper);
        wrapper.orderByDesc(AssetEntity::getUpdatedAt)
                .orderByDesc(AssetEntity::getCreatedAt)
                .orderByDesc(AssetEntity::getAssetId)
                .last("LIMIT " + ((long) (page - 1) * size) + "," + size);
        List<AssetItem> records = assetMapper.selectList(wrapper).stream()
                .map(entity -> assetService.getAsset(entity.getAssetId()))
                .toList();
        return new PageResult<>(records, page, size, total);
    }

    @Override
    @Transactional
    public AssetItem setVisibility(Long assetId, String visibility, AdminOperationContext context) {
        AssetEntity entity = requireAsset(assetId);
        AssetItem before = assetService.getAsset(assetId);
        String targetVisibility = normalizeVisibilityRequired(visibility);
        if (VISIBILITY_PRIVATE.equals(targetVisibility) && entity.getOwnerUserId() == null) {
            throw new BusinessException(40000, "缺少归属用户，不能设为私有资产");
        }
        LambdaUpdateWrapper<AssetEntity> update = new LambdaUpdateWrapper<>();
        update.eq(AssetEntity::getAssetId, assetId)
                .set(AssetEntity::getVisibility, targetVisibility)
                .set(AssetEntity::getStatus, STATUS_ACTIVE)
                .set(AssetEntity::getPublishedAt,
                        VISIBILITY_PUBLIC.equals(targetVisibility) ? LocalDateTime.now() : null)
                .set(AssetEntity::getUpdatedAt, LocalDateTime.now());
        if (VISIBILITY_PUBLIC.equals(targetVisibility) && entity.getCreatedByUserId() == null
                && entity.getOwnerUserId() != null) {
            update.set(AssetEntity::getCreatedByUserId, entity.getOwnerUserId());
        }
        assetMapper.update(null, update);
        AssetItem after = assetService.getAsset(assetId);
        auditService.record(context, "ASSET_VISIBILITY", "ASSET", assetId, before, after);
        return after;
    }

    @Override
    @Transactional
    public AssetItem setStatus(Long assetId, String status, AdminOperationContext context) {
        AssetEntity entity = requireAsset(assetId);
        AssetItem before = assetService.getAsset(assetId);
        String targetStatus = normalizeStatusRequired(status);
        LambdaUpdateWrapper<AssetEntity> update = new LambdaUpdateWrapper<>();
        update.eq(AssetEntity::getAssetId, assetId)
                .set(AssetEntity::getStatus, targetStatus)
                .set(AssetEntity::getUpdatedAt, LocalDateTime.now());
        if (STATUS_ACTIVE.equals(targetStatus) && VISIBILITY_PUBLIC.equalsIgnoreCase(safeVisibility(entity))
                && entity.getPublishedAt() == null) {
            update.set(AssetEntity::getPublishedAt, LocalDateTime.now());
        }
        assetMapper.update(null, update);
        AssetItem after = assetService.getAsset(assetId);
        auditService.record(context, "ASSET_STATUS", "ASSET", assetId, before, after);
        return after;
    }

    @Override
    @Transactional
    public void deleteAsset(Long assetId, AdminOperationContext context) {
        requireAsset(assetId);
        AssetItem before = assetService.getAsset(assetId);
        LambdaUpdateWrapper<AssetEntity> update = new LambdaUpdateWrapper<>();
        update.eq(AssetEntity::getAssetId, assetId)
                .set(AssetEntity::getDeleted, 1)
                .set(AssetEntity::getUpdatedAt, LocalDateTime.now());
        assetMapper.update(null, update);
        auditService.record(context, "ASSET_DELETE", "ASSET", assetId, before, null);
    }

    private AssetEntity requireAsset(Long assetId) {
        if (assetId == null) {
            throw new BusinessException(40000, "资产 ID 不能为空");
        }
        AssetEntity entity = assetMapper.selectById(assetId);
        if (entity == null) {
            throw new BusinessException(40400, "Asset does not exist");
        }
        return entity;
    }

    private int normalizePage(Integer pageNo) {
        return pageNo == null || pageNo < 1 ? 1 : pageNo;
    }

    private int normalizePageSize(Integer pageSize) {
        return pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private String normalizeVisibilityFilter(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim().toUpperCase();
        return ALLOWED_VISIBILITIES.contains(normalized) ? normalized : null;
    }

    private String normalizeStatusFilter(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim().toUpperCase();
        return ALLOWED_STATUSES.contains(normalized) ? normalized : null;
    }

    private String normalizeVisibilityRequired(String value) {
        String normalized = normalizeVisibilityFilter(value);
        if (normalized == null) {
            throw new BusinessException(40000, "资产可见性仅支持 PUBLIC 或 PRIVATE");
        }
        return normalized;
    }

    private String normalizeStatusRequired(String value) {
        String normalized = normalizeStatusFilter(value);
        if (normalized == null) {
            throw new BusinessException(40000, "资产状态仅支持 ACTIVE、REMOVED 或 PENDING_SAVE");
        }
        return normalized;
    }

    private void applyVisibilityFilter(LambdaQueryWrapper<AssetEntity> wrapper, String visibility) {
        if (VISIBILITY_PUBLIC.equals(visibility)) {
            wrapper.and(q -> q.eq(AssetEntity::getVisibility, VISIBILITY_PUBLIC)
                    .or(n -> n.isNull(AssetEntity::getVisibility).isNull(AssetEntity::getOwnerUserId)));
            return;
        }
        wrapper.and(q -> q.eq(AssetEntity::getVisibility, VISIBILITY_PRIVATE)
                .or(n -> n.isNull(AssetEntity::getVisibility).isNotNull(AssetEntity::getOwnerUserId)));
    }

    private void applyStatusFilter(LambdaQueryWrapper<AssetEntity> wrapper, String status) {
        if (STATUS_ACTIVE.equals(status)) {
            wrapper.and(q -> q.eq(AssetEntity::getStatus, STATUS_ACTIVE).or().isNull(AssetEntity::getStatus));
            return;
        }
        wrapper.eq(AssetEntity::getStatus, status);
    }

    private void applyKeywordFilter(LambdaQueryWrapper<AssetEntity> wrapper, String normalizedKeyword) {
        if (wrapper == null || normalizedKeyword == null) {
            return;
        }
        String pattern = "%" + normalizedKeyword.toLowerCase(Locale.ROOT) + "%";
        wrapper.and(q -> q
                .apply("lower(coalesce(file_name, '')) like {0}", pattern)
                .or().apply("lower(coalesce(asset_group, '')) like {0}", pattern)
                .or().apply("lower(coalesce(source_type, '')) like {0}", pattern)
                .or().apply("lower(coalesce(asset_type, '')) like {0}", pattern)
                .or().apply("lower(coalesce(kind, '')) like {0}", pattern)
                .or().apply("lower(coalesce(metadata_json, '')) like {0}", pattern));
    }

    private String safeVisibility(AssetEntity entity) {
        if (entity == null || !StringUtils.hasText(entity.getVisibility())) {
            return entity != null && entity.getOwnerUserId() == null ? VISIBILITY_PUBLIC : VISIBILITY_PRIVATE;
        }
        return entity.getVisibility().trim().toUpperCase();
    }

    private String trimToUpper(String value) {
        String text = trimToNull(value);
        return text == null ? null : text.toUpperCase();
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
