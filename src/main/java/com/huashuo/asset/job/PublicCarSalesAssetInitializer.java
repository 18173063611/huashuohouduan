package com.huashuo.asset.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.asset.entity.AssetEntity;
import com.huashuo.asset.mapper.AssetMapper;
import com.huashuo.storage.StorageService;
import com.huashuo.storage.UploadResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Order(Ordered.LOWEST_PRECEDENCE - 20)
@ConditionalOnProperty(prefix = "huashuo.seed.initializer", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class PublicCarSalesAssetInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PublicCarSalesAssetInitializer.class);

    private static final String TEMPLATE_VERSION = "2026-06-01-binyue-host-v2";
    private static final String CONTENT_TYPE = "application/json";

    private final StorageService storageService;
    private final AssetMapper assetMapper;
    private final ObjectMapper objectMapper;

    public PublicCarSalesAssetInitializer(StorageService storageService,
                                          AssetMapper assetMapper,
                                          ObjectMapper objectMapper) {
        this.storageService = storageService;
        this.assetMapper = assetMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            for (TemplateAsset template : templates()) {
                try {
                    refreshTemplate(template);
                } catch (Exception e) {
                    log.warn("Public car-sales asset {} refresh skipped: {}", template.assetId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Public car-sales asset initializer skipped: {}", e.getMessage());
        }
    }

    private void refreshTemplate(TemplateAsset template) throws Exception {
        AssetEntity existing = findTargetAsset(template);
        if (existing == null) {
            return;
        }
        if (alreadyCurrent(existing)) {
            return;
        }
        byte[] bytes = template.content().getBytes(StandardCharsets.UTF_8);
        UploadResult stored = storageService.upload(
                new ByteArrayInputStream(bytes),
                bytes.length,
                template.fileName(),
                CONTENT_TYPE,
                template.storageCategory()
        );

        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<AssetEntity> update = new LambdaUpdateWrapper<>();
        update.eq(AssetEntity::getAssetId, existing.getAssetId())
                .set(AssetEntity::getAssetType, "JSON")
                .set(AssetEntity::getKind, "MATERIAL")
                .set(AssetEntity::getVisibility, "PUBLIC")
                .set(AssetEntity::getStatus, "ACTIVE")
                .set(AssetEntity::getPublishedAt, existing.getPublishedAt() == null ? now : existing.getPublishedAt())
                .set(AssetEntity::getFileName, template.fileName())
                .set(AssetEntity::getFilePath, stored.objectKey())
                .set(AssetEntity::getFileUrl, stored.url())
                .set(AssetEntity::getThumbnailUrl, null)
                .set(AssetEntity::getMimeType, stored.contentType())
                .set(AssetEntity::getFileSize, stored.size())
                .set(AssetEntity::getSourceType, template.sourceType())
                .set(AssetEntity::getAssetGroup, template.assetGroup())
                .set(AssetEntity::getMetadataJson, template.metadataJson())
                .set(AssetEntity::getUpdatedAt, now)
                .set(AssetEntity::getDeleted, 0);
        assetMapper.update(null, update);
        log.info("Refreshed public car-sales asset {} {}", existing.getAssetId(), template.fileName());
    }

    private AssetEntity findTargetAsset(TemplateAsset template) {
        AssetEntity byId = assetMapper.selectById(template.assetId());
        if (byId != null) {
            return byId;
        }
        LambdaQueryWrapper<AssetEntity> query = new LambdaQueryWrapper<>();
        query.eq(AssetEntity::getFileName, template.fileName())
                .eq(AssetEntity::getDeleted, 0)
                .last("limit 1");
        return assetMapper.selectOne(query);
    }

    private boolean alreadyCurrent(AssetEntity existing) {
        if (!StringUtils.hasText(existing.getMetadataJson())) {
            return false;
        }
        try {
            JsonNode metadata = objectMapper.readTree(existing.getMetadataJson());
            return TEMPLATE_VERSION.equals(metadata.path("publicAssetTemplateVersion").asText());
        } catch (Exception ignored) {
            return false;
        }
    }

    private List<TemplateAsset> templates() throws Exception {
        String script04 = "今天到店看缤越，年轻人第一台SUV别只看价格，要看好不好开、坐着舒不舒服、配置够不够用。"
                + "这台车外观运动，车身尺寸灵活，城市停车不费劲；进到车里，座椅包裹感不错，中控屏、智能语音、倒车影像这些高频配置都给到。"
                + "动力响应轻快，日常通勤和周末出游都合适。想看实车和落地价，直接私信我，今天到店还能安排试驾。";
        String script05 = "Looking for a compact SUV that feels sporty, practical, and easy to drive? This is the Geely Binyue. "
                + "Sharp exterior lines, a confident stance, and a smart cabin make it a great choice for daily commuting and weekend trips. "
                + "You get responsive power, comfortable seats, and useful tech for navigation, parking, and everyday safety. "
                + "Book a test drive with us today. Send a message, and we will help you check availability, colors, and the latest offer.";

        return List.of(
                benchmark(527L,
                        "爆款对标04-缤越门店主播出镜-提取文案",
                        "04",
                        "zh-CN",
                        528L,
                        script04,
                        "门店主播出镜，围绕外观、座舱、动力和到店试驾转化展开。"),
                storyboard(528L,
                        "分镜脚本04-缤越门店主播出镜",
                        "04",
                        "zh-CN",
                        527L,
                        List.of(
                                shot(1, "00:00-00:08", "门店主播站在缤越车头45度，先建立真实到店感和车型身份。",
                                        "销售顾问在门店或展厅入口自然出镜，缤越车头45度入画，镜头轻推近，人物只做简短开场手势。",
                                        "今天到店看缤越，年轻人第一台SUV别只看价格，要看好不好开、坐着舒不舒服、配置够不够用。"),
                                shot(2, "00:08-00:16", "绕车展示运动外观和灵活车身，突出城市代步友好。",
                                        "镜头从前脸滑到侧身，捕捉车身线条、轮毂和灯组，主播退到画面边缘，不遮挡车辆主体。",
                                        "这台车外观运动，车身尺寸灵活，城市停车不费劲；"),
                                shot(3, "00:16-00:24", "切入座舱和高频配置，让内容与口播配置点对应。",
                                        "进入车内展示前排座椅、中控屏、方向盘和倒车影像界面，镜头稳定、干净、无字幕文字。",
                                        "进到车里，座椅包裹感不错，中控屏、智能语音、倒车影像这些高频配置都给到。"),
                                shot(4, "00:24-00:32", "用动力和试驾邀约收束，回到主播出镜转化。",
                                        "车辆准备驶出或门店外静态收尾，主播在车旁做邀请手势，结尾留给后期字幕和CTA。",
                                        "动力响应轻快，日常通勤和周末出游都合适。想看实车和落地价，直接私信我，今天到店还能安排试驾。")
                        )),
                benchmark(529L,
                        "爆款对标05-缤越英文试驾邀约出镜-提取文案",
                        "05",
                        "en-US",
                        530L,
                        script05,
                        "English presenter test-drive invitation for exterior, cabin, tech and offer inquiry."),
                storyboard(530L,
                        "分镜脚本05-缤越英文试驾邀约出镜",
                        "05",
                        "en-US",
                        529L,
                        List.of(
                                shot(1, "00:00-00:08", "English presenter opens beside the Geely Binyue and introduces the compact SUV angle.",
                                        "Presenter stands beside the Geely Binyue in a showroom or dealership forecourt, car front three-quarter view, confident greeting, no on-screen text.",
                                        "Looking for a compact SUV that feels sporty, practical, and easy to drive? This is the Geely Binyue."),
                                shot(2, "00:08-00:16", "Exterior walkaround matches the sporty design line in the English copy.",
                                        "Smooth walkaround from front fascia to side profile, showing sharp lines, stance, wheels and light details while the presenter stays secondary.",
                                        "Sharp exterior lines, a confident stance, and a smart cabin make it a great choice for daily commuting and weekend trips."),
                                shot(3, "00:16-00:24", "Cabin and technology shots support comfort, navigation, parking and safety points.",
                                        "Interior sequence with front seats, center screen, steering wheel and parking view; clean premium lighting, no readable captions in frame.",
                                        "You get responsive power, comfortable seats, and useful tech for navigation, parking, and everyday safety."),
                                shot(4, "00:24-00:32", "Presenter returns for the test-drive CTA and latest-offer inquiry.",
                                        "Presenter by the open door or next to the vehicle invites the viewer to book a test drive; end on a stable vehicle-and-host frame.",
                                        "Book a test drive with us today. Send a message, and we will help you check availability, colors, and the latest offer.")
                        ))
        );
    }

    private TemplateAsset benchmark(Long assetId, String fileName, String seriesNo, String language,
                                    Long pairedAssetId, String script, String summary) throws Exception {
        Map<String, Object> payload = basePayload(fileName, "benchmark_extraction", seriesNo, language, pairedAssetId);
        payload.put("summary", summary);
        payload.put("content", script);
        payload.put("script", script);
        payload.put("voiceText", script);
        payload.put("durationTargetSeconds", 32);
        payload.put("usageNote", "Use as the paired voiceover benchmark for storyboard asset " + pairedAssetId + ".");
        String content = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        return new TemplateAsset(assetId, fileName, "爆款对标", "DOUYIN_BENCHMARK_EXTRACT",
                "writer", content, metadata(fileName, "benchmark_extraction", seriesNo, language,
                pairedAssetId, script));
    }

    private TemplateAsset storyboard(Long assetId, String fileName, String seriesNo, String language,
                                     Long pairedAssetId, List<Map<String, Object>> shots) throws Exception {
        Map<String, Object> payload = basePayload(fileName, "storyboard", seriesNo, language, pairedAssetId);
        payload.put("durationTargetSeconds", 32);
        payload.put("scripts", shots);
        payload.put("storyboard", shots);
        String content = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        String preview = shots.stream()
                .map(shot -> String.valueOf(shot.getOrDefault("content", "")))
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(fileName);
        return new TemplateAsset(assetId, fileName, "分镜脚本", "STORYBOARD_GENERATE",
                "storyboard", content, metadata(fileName, "storyboard", seriesNo, language,
                pairedAssetId, preview));
    }

    private Map<String, Object> basePayload(String title, String role, String seriesNo,
                                            String language, Long pairedAssetId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("assetRole", role);
        payload.put("brandModel", "吉利缤越");
        payload.put("seriesNo", seriesNo);
        payload.put("language", language);
        payload.put("pairedAssetId", pairedAssetId);
        payload.put("publicAssetTemplateVersion", TEMPLATE_VERSION);
        return payload;
    }

    private Map<String, Object> shot(int order, String time, String page,
                                     String visualPrompt, String content) {
        Map<String, Object> shot = new LinkedHashMap<>();
        shot.put("order", order);
        shot.put("time", time);
        shot.put("page", page);
        shot.put("visualPrompt", visualPrompt);
        shot.put("prompt", visualPrompt);
        shot.put("backgroundMusic", "");
        shot.put("content", content);
        shot.put("voiceText", content);
        shot.put("highlight", page);
        return shot;
    }

    private String metadata(String fileName, String role, String seriesNo, String language,
                            Long pairedAssetId, String previewText) throws Exception {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "PUBLIC_CAR_SALES_TEMPLATE");
        metadata.put("assetRole", role);
        metadata.put("assetGroup", role.contains("storyboard") ? "分镜脚本" : "爆款对标");
        metadata.put("brandModel", "吉利缤越");
        metadata.put("seriesNo", seriesNo);
        metadata.put("language", language);
        metadata.put("pairedAssetId", pairedAssetId);
        metadata.put("previewText", previewText);
        metadata.put("fileName", fileName);
        metadata.put("publicAssetTemplateVersion", TEMPLATE_VERSION);
        return objectMapper.writeValueAsString(metadata);
    }

    private record TemplateAsset(
            Long assetId,
            String fileName,
            String assetGroup,
            String sourceType,
            String storageCategory,
            String content,
            String metadataJson
    ) {
    }
}
