package com.huashuo.petasset.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.asset.service.AssetService;
import com.huashuo.asset.vo.AssetItem;
import com.huashuo.avatar.client.DoubaoImageClient;
import com.huashuo.avatar.config.VolcengineImageProperties;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.petasset.dto.PetImageGenerateRequest;
import com.huashuo.petasset.dto.PetImageGenerateResponse;
import com.huashuo.storage.StorageService;
import com.huashuo.storage.UploadResult;
import com.huashuo.upload.tos.UploadPublicBaseProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

@Service
public class PetImageAssetService {

    private static final String KIND_PET = "pet";
    private static final String KIND_BACKGROUND = "background";

    private final DoubaoImageClient doubaoImageClient;
    private final VolcengineImageProperties imageProperties;
    private final AssetService assetService;
    private final StorageService storageService;
    private final UploadPublicBaseProvider uploadPublicBaseProvider;
    private final ObjectMapper objectMapper;

    public PetImageAssetService(
            DoubaoImageClient doubaoImageClient,
            VolcengineImageProperties imageProperties,
            AssetService assetService,
            StorageService storageService,
            UploadPublicBaseProvider uploadPublicBaseProvider,
            ObjectMapper objectMapper
    ) {
        this.doubaoImageClient = doubaoImageClient;
        this.imageProperties = imageProperties;
        this.assetService = assetService;
        this.storageService = storageService;
        this.uploadPublicBaseProvider = uploadPublicBaseProvider;
        this.objectMapper = objectMapper;
    }

    public PetImageGenerateResponse generate(PetImageGenerateRequest request, long ownerUserId) {
        if (!imageProperties.configured()) {
            throw new BusinessException(50100, "Volcengine image credentials missing; set volcengine.image.api-key");
        }
        String kind = normalizeKind(request.kind());
        int imageCount = Math.max(1, Math.min(request.imageCount() == null ? 2 : request.imageCount(), 4));
        String size = DoubaoImageClient.normalizeSizeForProvider(request.size(), imageProperties.effectiveDefaultSize());
        List<Long> referenceAssetIds = request.referenceAssetIds() == null ? List.of() : request.referenceAssetIds();
        List<String> referenceImageUrls = resolveReferenceImageUrls(referenceAssetIds, ownerUserId);
        String prompt = buildPrompt(request.prompt(), request.style(), kind);

        try {
            List<String> remoteUrls = doubaoImageClient.generateImages(prompt, referenceImageUrls, imageCount, size);
            List<Long> assetIds = new ArrayList<>();
            List<String> previewUrls = new ArrayList<>();
            List<AssetItem> assets = new ArrayList<>();
            String safeName = safeName(request.name(), kind);
            for (int i = 0; i < remoteUrls.size(); i++) {
                String remoteUrl = remoteUrls.get(i);
                HttpResponse<InputStream> imgResp = doubaoImageClient.openImageDownload(remoteUrl);
                String contentType = imgResp.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse("image/png");
                long contentLen = imgResp.headers().firstValue(HttpHeaders.CONTENT_LENGTH)
                        .map(Long::parseLong).orElse(-1L);
                String fileName = fileNameFor(kind, safeName, i + 1);
                UploadResult stored;
                try (InputStream in = imgResp.body()) {
                    stored = storageService.upload(in, contentLen, fileName, contentType, "image");
                }
                AssetItem asset = assetService.createAvatarImageAsset(
                        ownerUserId,
                        null,
                        null,
                        fileName,
                        stored.objectKey(),
                        stored.url(),
                        stored.contentType(),
                        stored.size(),
                        sourceTypeFor(kind),
                        metadataJson(request, kind, prompt, remoteUrl, i + 1, referenceAssetIds)
                );
                assetIds.add(asset.assetId());
                previewUrls.add(asset.fileUrl());
                assets.add(asset);
            }
            return new PetImageGenerateResponse(assetIds, previewUrls, remoteUrls, assets);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(50100, "宠物图片生成失败: " + ex.getMessage());
        }
    }

    private List<String> resolveReferenceImageUrls(List<Long> referenceAssetIds, long ownerUserId) {
        List<String> urls = new ArrayList<>();
        OptionalLong viewer = OptionalLong.of(ownerUserId);
        for (Long assetId : referenceAssetIds) {
            if (assetId == null) {
                continue;
            }
            AssetItem asset = assetService.getAssetForViewer(assetId, viewer);
            if (!"IMAGE".equals(asset.assetType()) && !"COVER".equals(asset.assetType())) {
                throw new BusinessException(40000, "参考素材必须是图片");
            }
            if (!isPetAsset(asset)) {
                throw new BusinessException(40300, "只能使用宠物资产作为参考图");
            }
            urls.add(toPublicReferenceUrl(asset.fileUrl()));
        }
        return urls;
    }

    private boolean isPetAsset(AssetItem asset) {
        String metadata = asset.metadataJson() == null ? "" : asset.metadataJson().toLowerCase();
        return metadata.contains("\"businessdomain\":\"pet\"")
                || metadata.contains("\"domain\":\"pet_creation\"")
                || metadata.contains("\"businessDomain\":\"pet\"")
                || metadata.contains("\"domain\":\"pet_creation\"");
    }

    private String toPublicReferenceUrl(String fileUrl) {
        if (!StringUtils.hasText(fileUrl)) {
            throw new BusinessException(40000, "参考图 URL 为空");
        }
        String trimmed = fileUrl.trim();
        if (isPublicHttpUrl(trimmed)) {
            return trimmed;
        }
        String publicBase = uploadPublicBaseProvider.effectivePublicBaseUrl();
        if (trimmed.startsWith("/") && StringUtils.hasText(publicBase)) {
            String resolved = publicBase + trimmed;
            if (isPublicHttpUrl(resolved)) {
                return resolved;
            }
        }
        throw new BusinessException(40000, "参考图需要公网可访问，请重新上传到宠物资产中心或不选择参考图。");
    }

    private boolean isPublicHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return false;
            }
            if (!StringUtils.hasText(host)) {
                return false;
            }
            String normalizedHost = host.toLowerCase();
            return !"localhost".equals(normalizedHost)
                    && !normalizedHost.startsWith("127.")
                    && !normalizedHost.startsWith("10.")
                    && !normalizedHost.startsWith("192.168.");
        } catch (Exception ignored) {
            return false;
        }
    }

    private String normalizeKind(String value) {
        String normalized = StringUtils.hasText(value) ? value.trim().toLowerCase() : KIND_PET;
        if (KIND_BACKGROUND.equals(normalized) || "scene".equals(normalized)) {
            return KIND_BACKGROUND;
        }
        return KIND_PET;
    }

    private String buildPrompt(String rawPrompt, String style, String kind) {
        List<String> parts = new ArrayList<>();
        parts.add(rawPrompt.trim());
        if (KIND_BACKGROUND.equals(kind)) {
            parts.add("生成宠物短视频可用的背景图或场景参考图，画面干净、空间层次清晰，适合后续放入猫狗等宠物主体。");
            parts.add("不要出现人物、车辆销售元素、门店促销牌、文字、水印、logo 或说明卡片。");
        } else {
            parts.add("生成清晰可爱的宠物主体参考图，宠物外观、毛色、花纹、脸型和体态稳定，适合后续宠物视频生成。");
            parts.add("不要出现车辆销售顾问、数字人、文字、水印、说明卡片或多余主体。");
        }
        if (StringUtils.hasText(style)) {
            parts.add("风格要求：" + style.trim());
        }
        parts.add("图片质感自然，主体边界清楚，避免低清、畸形、融合身体和多余肢体。");
        return String.join("\n", parts);
    }

    private String safeName(String name, String kind) {
        String fallback = KIND_BACKGROUND.equals(kind) ? "宠物背景图" : "AI宠物";
        String value = StringUtils.hasText(name) ? name.trim() : fallback;
        return value.length() <= 80 ? value : value.substring(0, 80);
    }

    private String fileNameFor(String kind, String safeName, int index) {
        String prefix = KIND_BACKGROUND.equals(kind) ? "pet-background" : "ai-pet";
        String safe = safeName.replaceAll("[^\\p{IsHan}a-zA-Z0-9_-]+", "-");
        if (!StringUtils.hasText(safe)) {
            safe = prefix;
        }
        return prefix + "-" + safe + "-" + System.currentTimeMillis() + "-" + index + ".png";
    }

    private String sourceTypeFor(String kind) {
        return KIND_BACKGROUND.equals(kind) ? "PET_BACKGROUND_GENERATE" : "PET_IMAGE_GENERATE";
    }

    private String metadataJson(PetImageGenerateRequest request, String kind, String prompt, String remoteUrl, int index,
                                List<Long> referenceAssetIds) throws Exception {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("businessDomain", "pet");
        meta.put("domain", "pet_creation");
        meta.put("from", KIND_BACKGROUND.equals(kind) ? "pet_background_generate" : "pet_image_generate");
        meta.put("assetGroup", KIND_BACKGROUND.equals(kind) ? "宠物背景图" : "AI宠物素材");
        meta.put("assetRole", KIND_BACKGROUND.equals(kind) ? "scene" : "main_pet");
        meta.put("materialRole", KIND_BACKGROUND.equals(kind) ? "scene" : "main_pet");
        meta.put("kind", kind);
        meta.put("name", safeName(request.name(), kind));
        meta.put("style", request.style() == null ? "" : request.style().trim());
        meta.put("size", request.size() == null ? imageProperties.effectiveDefaultSize() : request.size().trim());
        meta.put("model", imageProperties.effectiveModel());
        meta.put("prompt", prompt);
        meta.put("rawPrompt", request.prompt().trim());
        meta.put("referenceAssetIds", referenceAssetIds);
        meta.put("remoteImageUrl", remoteUrl);
        meta.put("imageIndex", index);
        meta.put("source", "DOUBAO_SEEDREAM");
        return objectMapper.writeValueAsString(meta);
    }
}
