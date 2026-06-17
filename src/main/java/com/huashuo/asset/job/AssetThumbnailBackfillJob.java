package com.huashuo.asset.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.asset.entity.AssetEntity;
import com.huashuo.asset.mapper.AssetMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Persists thumbnails for legacy assets that can already derive a cover from metadata.
 */
@Component
@Order(20)
@ConditionalOnProperty(prefix = "huashuo.asset.thumbnail-backfill", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class AssetThumbnailBackfillJob implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AssetThumbnailBackfillJob.class);
    private static final String STATUS_ACTIVE = "ACTIVE";

    private final AssetMapper assetMapper;
    private final ObjectMapper objectMapper;
    private final int batchSize;

    public AssetThumbnailBackfillJob(AssetMapper assetMapper,
                                     ObjectMapper objectMapper,
                                     @Value("${huashuo.asset.thumbnail-backfill.batch-size:500}") int batchSize) {
        this.assetMapper = assetMapper;
        this.objectMapper = objectMapper;
        this.batchSize = Math.max(1, Math.min(batchSize, 2000));
    }

    @Override
    public void run(ApplicationArguments args) {
        LambdaQueryWrapper<AssetEntity> query = new LambdaQueryWrapper<>();
        query.eq(AssetEntity::getStatus, STATUS_ACTIVE)
                .and(q -> q.isNull(AssetEntity::getThumbnailUrl)
                        .or()
                        .eq(AssetEntity::getThumbnailUrl, ""))
                .orderByDesc(AssetEntity::getUpdatedAt)
                .last("limit " + batchSize);

        List<AssetEntity> assets = assetMapper.selectList(query);
        if (assets.isEmpty()) {
            return;
        }

        int updated = 0;
        for (AssetEntity asset : assets) {
            ThumbnailCandidate candidate = resolveCandidate(asset);
            if (candidate == null || !StringUtils.hasText(candidate.url())) {
                continue;
            }
            LambdaUpdateWrapper<AssetEntity> update = new LambdaUpdateWrapper<>();
            update.eq(AssetEntity::getAssetId, asset.getAssetId())
                    .and(q -> q.isNull(AssetEntity::getThumbnailUrl)
                            .or()
                            .eq(AssetEntity::getThumbnailUrl, ""))
                    .set(AssetEntity::getThumbnailUrl, candidate.url())
                    .set(AssetEntity::getUpdatedAt, LocalDateTime.now());
            if (assetMapper.update(null, update) > 0) {
                updated++;
            }
        }

        if (updated > 0) {
            log.info("Backfilled asset thumbnails: updated={}, scanned={}", updated, assets.size());
        }
    }

    private ThumbnailCandidate resolveCandidate(AssetEntity asset) {
        if (asset == null) {
            return null;
        }
        JsonNode metadata = parseMetadata(asset.getMetadataJson());
        ThumbnailCandidate candidate = firstCandidate(
                candidate(textAt(metadata, "thumbnailUrl"), "metadata.thumbnailUrl"),
                candidate(textAt(metadata, "coverUrl"), "metadata.coverUrl"),
                candidate(textAt(metadata, "firstFrameUrl"), "metadata.firstFrameUrl"),
                candidate(textAt(metadata, "posterUrl"), "metadata.posterUrl"),
                candidate(textAt(metadata, "/input/carImageUrls/0"), "input.carImageUrls[0]"),
                candidate(textAt(metadata, "/input/scenes/0/referenceImage"), "input.scenes[0].referenceImage"),
                candidate(textAt(metadata, "/input/scenes/0/imageUrls/0"), "input.scenes[0].imageUrls[0]"),
                candidate(textAt(metadata, "/input/scene/referenceImage"), "input.scene.referenceImage"),
                candidate(textAt(metadata, "/input/scene/imageUrls/0"), "input.scene.imageUrls[0]"),
                candidate(textAt(metadata, "/input/segmentRequest/imageUrl"), "input.segmentRequest.imageUrl"),
                candidate(textAt(metadata, "/assetRoleBindings/0/url"), "assetRoleBindings[0].url"),
                candidate(textAt(metadata, "/input/seedanceDiagnostics/assetRoleBindings/0/url"),
                        "input.seedanceDiagnostics.assetRoleBindings[0].url"),
                candidate(textAt(metadata, "/segmentVideos/0/firstFrameUrl"), "segmentVideos[0].firstFrameUrl")
        );
        if (candidate != null) {
            return candidate;
        }
        if (isImageAsset(asset) && StringUtils.hasText(asset.getFileUrl())) {
            return candidate(asset.getFileUrl(), "image.fileUrl");
        }
        return null;
    }

    private JsonNode parseMetadata(String metadataJson) {
        if (!StringUtils.hasText(metadataJson)) {
            return null;
        }
        try {
            return objectMapper.readTree(metadataJson);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String textAt(JsonNode root, String pointer) {
        if (root == null || !StringUtils.hasText(pointer)) {
            return null;
        }
        JsonNode node = pointer.startsWith("/") ? root.at(pointer) : root.path(pointer);
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            String text = node.asText();
            return StringUtils.hasText(text) ? text.trim() : null;
        }
        if (node.isNumber() || node.isBoolean()) {
            return node.asText();
        }
        return null;
    }

    private boolean isImageAsset(AssetEntity asset) {
        String type = asset.getAssetType() == null ? "" : asset.getAssetType().trim().toUpperCase();
        String mime = asset.getMimeType() == null ? "" : asset.getMimeType().trim().toLowerCase();
        String name = asset.getFileName() == null ? "" : asset.getFileName().trim().toLowerCase();
        return "IMAGE".equals(type)
                || mime.startsWith("image/")
                || name.endsWith(".png")
                || name.endsWith(".jpg")
                || name.endsWith(".jpeg")
                || name.endsWith(".webp");
    }

    private ThumbnailCandidate candidate(String url, String source) {
        return StringUtils.hasText(url) ? new ThumbnailCandidate(url.trim(), source) : null;
    }

    private ThumbnailCandidate firstCandidate(ThumbnailCandidate... candidates) {
        if (candidates == null) {
            return null;
        }
        for (ThumbnailCandidate candidate : candidates) {
            if (candidate != null && StringUtils.hasText(candidate.url())) {
                return candidate;
            }
        }
        return null;
    }

    private record ThumbnailCandidate(String url, String source) {
    }
}
