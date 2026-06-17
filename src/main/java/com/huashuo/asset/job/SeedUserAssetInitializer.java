package com.huashuo.asset.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.huashuo.asset.entity.AssetEntity;
import com.huashuo.asset.mapper.AssetMapper;
import com.huashuo.storage.StorageService;
import com.huashuo.storage.UploadResult;
import com.huashuo.upload.config.UploadProperties;
import com.huashuo.upload.tos.VolcengineTosProperties;
import com.huashuo.user.entity.UserAccountEntity;
import com.huashuo.user.mapper.UserAccountMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;

/**
 * 初始化演示用户与演示资产。
 * 启用 TOS 时种子文件流式上传 Bucket；否则回落到本地 uploads/seed（兼容无密钥环境）。
 */
@Component
@ConditionalOnProperty(prefix = "huashuo.seed.initializer", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class SeedUserAssetInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedUserAssetInitializer.class);

    private static final String DEMO_PASSWORD_HASH = "$2a$10$FpjChVSxXshPiX0E62qP5eWjtXi7AfhCgQLJyF9zkwAhvG1zakTIG";
    private static final String SEED_IMAGE_FILE = "avatar-upload-16d01549-407a-4c2e-a5b9-39dcc0e04956.png";
    private static final String SEED_CAR_IMAGE_FILE = "car-sales-demo-car.png";
    private static final String SEED_CAR_BUNDLE_FILE = "demo-car-model-bundle.json";
    private static final String GROUP_CAR_MODEL_BUNDLE = "汽车素材包";

    private final UploadProperties uploadProperties;
    private final VolcengineTosProperties volcengineTosProperties;
    private final StorageService storageService;
    private final UserAccountMapper userAccountMapper;
    private final AssetMapper assetMapper;

    private UploadResult seedImageUpload;
    private UploadResult seedCarImageUpload;
    private UploadResult seedAliceTextUpload;
    private UploadResult seedBobVoiceJsonUpload;
    private UploadResult seedBobVideoJsonUpload;

    public SeedUserAssetInitializer(
            UploadProperties uploadProperties,
            VolcengineTosProperties volcengineTosProperties,
            StorageService storageService,
            UserAccountMapper userAccountMapper,
            AssetMapper assetMapper
    ) {
        this.uploadProperties = uploadProperties;
        this.volcengineTosProperties = volcengineTosProperties;
        this.storageService = storageService;
        this.userAccountMapper = userAccountMapper;
        this.assetMapper = assetMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            UserAccountEntity alice = ensureUser("alice", "Alice 运营");
            UserAccountEntity bob = ensureUser("bob", "Bob 设计");
            UserAccountEntity demo = ensureUser("demo", "演示用户");

            ensureSeedFilesExist();
            softDeleteLegacyMissingSeed();
            repairDemoAssetsMissingLocalPath();

            ensureSeedImageAsset(alice);
            ensureSeedTextAsset(alice);
            ensureSeedJsonAsset(bob);
            ensureSeedBobVideoJson(bob);
            AssetEntity demoCarImage = ensureDemoCarImageAsset(demo);
            ensureDemoCarModelBundleAsset(demo, demoCarImage);
        } catch (Exception e) {
            log.warn("Seed initializer skipped due to error: {}", e.getMessage());
        }
    }

    private UserAccountEntity ensureUser(String username, String displayName) {
        LambdaQueryWrapper<UserAccountEntity> w = new LambdaQueryWrapper<>();
        w.eq(UserAccountEntity::getUsername, username)
                .eq(UserAccountEntity::getDeleted, 0)
                .last("limit 1");
        UserAccountEntity existing = userAccountMapper.selectOne(w);
        if (existing != null) {
            return existing;
        }
        UserAccountEntity entity = new UserAccountEntity();
        entity.setUsername(username);
        entity.setPasswordHash(DEMO_PASSWORD_HASH);
        entity.setDisplayName(displayName);
        userAccountMapper.insert(entity);
        return userAccountMapper.selectById(entity.getUserId());
    }

    private void ensureSeedFilesExist() throws Exception {
        seedImageUpload = null;
        seedCarImageUpload = null;
        seedAliceTextUpload = null;
        seedBobVoiceJsonUpload = null;
        seedBobVideoJsonUpload = null;

        if (volcengineTosProperties.enabled()) {
            try {
                uploadSeedPayloadsToTos();
                return;
            } catch (Exception e) {
                log.warn("Seed TOS upload failed, fallback local uploads: {}", e.getMessage());
            }
        }
        legacyEnsureSeedFilesExist();
    }

    private void uploadSeedPayloadsToTos() throws Exception {
        Path worktreeCandidate = Path.of("..", SEED_IMAGE_FILE).normalize().toAbsolutePath();
        if (Files.exists(worktreeCandidate)) {
            try (InputStream in = Files.newInputStream(worktreeCandidate)) {
                seedImageUpload = storageService.upload(in, Files.size(worktreeCandidate), SEED_IMAGE_FILE, "image/png", "seed");
            }
        } else {
            log.warn("Seed image not found at {}, skip TOS image seed", worktreeCandidate);
        }
        Path carImageCandidate = Path.of("..", "huashuoqianduan", "src", "assets", "car.png").normalize().toAbsolutePath();
        if (Files.exists(carImageCandidate)) {
            try (InputStream in = Files.newInputStream(carImageCandidate)) {
                seedCarImageUpload = storageService.upload(in, Files.size(carImageCandidate), SEED_CAR_IMAGE_FILE, "image/png", "seed");
            }
        } else {
            log.warn("Seed car image not found at {}, skip TOS car image seed", carImageCandidate);
        }

        byte[] alice = "【演示文案】\n大家好，欢迎来到 AI 数字人工作台。\n".getBytes(StandardCharsets.UTF_8);
        seedAliceTextUpload = storageService.upload(
                new ByteArrayInputStream(alice), alice.length, "seed-alice-script.txt", "text/plain", "seed");

        byte[] bobVoice = "{\"seed\":true,\"type\":\"voice\",\"note\":\"用于联调展示\"}\n".getBytes(StandardCharsets.UTF_8);
        seedBobVoiceJsonUpload = storageService.upload(
                new ByteArrayInputStream(bobVoice), bobVoice.length, "seed-bob-voice.json", "application/json", "seed");

        byte[] bobVideo = "{\"seed\":true,\"type\":\"video\",\"note\":\"演示占位 JSON\"}\n".getBytes(StandardCharsets.UTF_8);
        seedBobVideoJsonUpload = storageService.upload(
                new ByteArrayInputStream(bobVideo), bobVideo.length, "seed-bob-video.json", "application/json", "seed");
    }

    private void legacyEnsureSeedFilesExist() throws Exception {
        Path root = Path.of(uploadProperties.localRoot()).toAbsolutePath();
        Path seedDir = root.resolve("seed");
        Files.createDirectories(seedDir);

        Path targetImage = seedDir.resolve(SEED_IMAGE_FILE);
        if (!Files.exists(targetImage)) {
            Path worktreeCandidate = Path.of("..", SEED_IMAGE_FILE).normalize().toAbsolutePath();
            if (Files.exists(worktreeCandidate)) {
                Files.copy(worktreeCandidate, targetImage, StandardCopyOption.REPLACE_EXISTING);
                log.info("Copied seed image from {} to {}", worktreeCandidate, targetImage);
            } else {
                log.warn("Seed image not found at {}, skip copying", worktreeCandidate);
            }
        }
        Path targetCarImage = seedDir.resolve(SEED_CAR_IMAGE_FILE);
        if (!Files.exists(targetCarImage)) {
            Path worktreeCandidate = Path.of("..", "huashuoqianduan", "src", "assets", "car.png").normalize().toAbsolutePath();
            if (Files.exists(worktreeCandidate)) {
                Files.copy(worktreeCandidate, targetCarImage, StandardCopyOption.REPLACE_EXISTING);
                log.info("Copied seed car image from {} to {}", worktreeCandidate, targetCarImage);
            } else {
                log.warn("Seed car image not found at {}, skip copying", worktreeCandidate);
            }
        }

        Path textFile = seedDir.resolve("seed-alice-script.txt");
        if (!Files.exists(textFile)) {
            Files.writeString(textFile, "【演示文案】\n大家好，欢迎来到 AI 数字人工作台。\n", StandardCharsets.UTF_8);
        }
        Path jsonFile = seedDir.resolve("seed-bob-voice.json");
        if (!Files.exists(jsonFile)) {
            Files.writeString(jsonFile, "{\"seed\":true,\"type\":\"voice\",\"note\":\"用于联调展示\"}\n", StandardCharsets.UTF_8);
        }
    }

    private void softDeleteLegacyMissingSeed() {
        LambdaUpdateWrapper<AssetEntity> uw = new LambdaUpdateWrapper<>();
        uw.eq(AssetEntity::getFileName, "seed-alice-cover.png")
                .eq(AssetEntity::getDeleted, 0)
                .set(AssetEntity::getDeleted, 1)
                .set(AssetEntity::getUpdatedAt, LocalDateTime.now());
        assetMapper.update(null, uw);
    }

    private void repairDemoAssetsMissingLocalPath() {
        fixDemoAssetPath(SEED_IMAGE_FILE, "IMAGE");
        fixDemoAssetPath(SEED_CAR_IMAGE_FILE, "IMAGE");
        fixDemoAssetPath(SEED_CAR_BUNDLE_FILE, "JSON");
        fixDemoAssetPath("seed-alice-script.txt", "TEXT");
        fixDemoAssetPath("seed-bob-voice.json", "JSON");
        fixDemoAssetPath("seed-bob-video.json", "JSON");
    }

    private void fixDemoAssetPath(String fileName, String expectedAssetType) {
        LambdaQueryWrapper<AssetEntity> w = new LambdaQueryWrapper<>();
        w.eq(AssetEntity::getFileName, fileName).eq(AssetEntity::getDeleted, 0).last("limit 1");
        AssetEntity row = assetMapper.selectOne(w);
        if (row == null || row.getAssetType() == null) {
            return;
        }
        if (!expectedAssetType.equalsIgnoreCase(row.getAssetType())) {
            return;
        }
        String fu = row.getFileUrl();
        if (StringUtils.hasText(fu) && fu.trim().startsWith("http")) {
            return;
        }
        Path abs = Path.of(uploadProperties.localRoot()).toAbsolutePath().resolve("seed").resolve(fileName);
        String pathStr = abs.toString();
        boolean pathBad = row.getFilePath() == null || row.getFilePath().isBlank();
        if (!pathBad) {
            try {
                if (!Files.isRegularFile(Path.of(row.getFilePath()))) {
                    pathBad = true;
                }
            } catch (Exception e) {
                pathBad = true;
            }
        }
        if (!pathBad) {
            return;
        }
        long size = 0L;
        try {
            size = Files.exists(abs) ? Files.size(abs) : 0L;
        } catch (IOException e) {
            log.warn("Could not stat seed file {}: {}", abs, e.getMessage());
        }
        LambdaUpdateWrapper<AssetEntity> uw = new LambdaUpdateWrapper<>();
        uw.eq(AssetEntity::getAssetId, row.getAssetId())
                .set(AssetEntity::getFilePath, pathStr)
                .set(AssetEntity::getFileSize, size)
                .set(AssetEntity::getUpdatedAt, LocalDateTime.now());
        assetMapper.update(null, uw);
        log.info("Repaired demo asset file_path for {} -> {}", fileName, pathStr);
    }

    private void ensureSeedImageAsset(UserAccountEntity owner) throws Exception {
        if (owner == null || owner.getUserId() == null) {
            return;
        }
        if (existsAsset(SEED_IMAGE_FILE)) {
            return;
        }
        if (volcengineTosProperties.enabled() && seedImageUpload != null) {
            insertAsset(null, "IMAGE", SEED_IMAGE_FILE,
                    seedImageUpload.objectKey(),
                    seedImageUpload.url(),
                    seedImageUpload.url(),
                    "image/png",
                    seedImageUpload.size(),
                    "MANUAL_CREATED",
                    "{\"seed\":true,\"createdBy\":{\"userId\":" + owner.getUserId() + ",\"username\":\"" + owner.getUsername() + "\"},\"tag\":\"cover\"}");
            return;
        }
        String fileUrl = "/uploads/seed/" + SEED_IMAGE_FILE;
        Path file = Path.of(uploadProperties.localRoot()).toAbsolutePath().resolve("seed").resolve(SEED_IMAGE_FILE);
        long size = Files.exists(file) ? Files.size(file) : 0L;
        insertAsset(null, "IMAGE", SEED_IMAGE_FILE, file.toString(), fileUrl, fileUrl, "image/png", size,
                "MANUAL_CREATED",
                "{\"seed\":true,\"createdBy\":{\"userId\":" + owner.getUserId() + ",\"username\":\"" + owner.getUsername() + "\"},\"tag\":\"cover\"}");
    }

    private void ensureSeedTextAsset(UserAccountEntity owner) throws Exception {
        String fileName = "seed-alice-script.txt";
        if (existsAsset(fileName)) {
            return;
        }
        if (volcengineTosProperties.enabled() && seedAliceTextUpload != null) {
            insertAsset(null, "TEXT", fileName,
                    seedAliceTextUpload.objectKey(),
                    seedAliceTextUpload.url(),
                    null,
                    "text/plain",
                    seedAliceTextUpload.size(),
                    "MANUAL_CREATED",
                    "{\"seed\":true,\"createdBy\":{\"userId\":" + owner.getUserId() + ",\"username\":\"" + owner.getUsername() + "\"},\"description\":\"演示文案资产\"}");
            return;
        }
        String fileUrl = "/uploads/seed/" + fileName;
        Path file = Path.of(uploadProperties.localRoot()).toAbsolutePath().resolve("seed").resolve(fileName);
        long size = Files.exists(file) ? Files.size(file) : 0L;
        insertAsset(null, "TEXT", fileName, file.toString(), fileUrl, null, "text/plain", size,
                "MANUAL_CREATED",
                "{\"seed\":true,\"createdBy\":{\"userId\":" + owner.getUserId() + ",\"username\":\"" + owner.getUsername() + "\"},\"description\":\"演示文案资产\"}");
    }

    private void ensureSeedJsonAsset(UserAccountEntity owner) throws Exception {
        String fileName = "seed-bob-voice.json";
        if (existsAsset(fileName)) {
            return;
        }
        if (volcengineTosProperties.enabled() && seedBobVoiceJsonUpload != null) {
            insertAsset(null, "JSON", fileName,
                    seedBobVoiceJsonUpload.objectKey(),
                    seedBobVoiceJsonUpload.url(),
                    null,
                    "application/json",
                    seedBobVoiceJsonUpload.size(),
                    "MANUAL_CREATED",
                    "{\"seed\":true,\"createdBy\":{\"userId\":" + owner.getUserId() + ",\"username\":\"" + owner.getUsername() + "\"},\"note\":\"用于联调展示\"}");
            return;
        }
        String fileUrl = "/uploads/seed/" + fileName;
        Path file = Path.of(uploadProperties.localRoot()).toAbsolutePath().resolve("seed").resolve(fileName);
        long size = Files.exists(file) ? Files.size(file) : 0L;
        insertAsset(null, "JSON", fileName, file.toString(), fileUrl, null, "application/json", size,
                "MANUAL_CREATED",
                "{\"seed\":true,\"createdBy\":{\"userId\":" + owner.getUserId() + ",\"username\":\"" + owner.getUsername() + "\"},\"note\":\"用于联调展示\"}");
    }

    private void ensureSeedBobVideoJson(UserAccountEntity owner) throws Exception {
        String fileName = "seed-bob-video.json";
        if (existsAsset(fileName)) {
            return;
        }
        if (volcengineTosProperties.enabled() && seedBobVideoJsonUpload != null) {
            insertAsset(null, "JSON", fileName,
                    seedBobVideoJsonUpload.objectKey(),
                    seedBobVideoJsonUpload.url(),
                    null,
                    "application/json",
                    seedBobVideoJsonUpload.size(),
                    "MANUAL_CREATED",
                    "{\"seed\":true,\"createdBy\":{\"userId\":" + owner.getUserId() + ",\"username\":\"" + owner.getUsername() + "\"},\"note\":\"视频占位改为 JSON，避免不存在的二进制文件\"}");
            return;
        }
        String fileUrl = "/uploads/seed/" + fileName;
        Path file = Path.of(uploadProperties.localRoot()).toAbsolutePath().resolve("seed").resolve(fileName);
        if (!Files.exists(file)) {
            Files.writeString(file, "{\"seed\":true,\"type\":\"video\",\"note\":\"演示占位 JSON\"}\n", StandardCharsets.UTF_8);
        }
        long size = Files.exists(file) ? Files.size(file) : 0L;
        insertAsset(null, "JSON", fileName, file.toString(), fileUrl, null, "application/json", size,
                "MANUAL_CREATED",
                "{\"seed\":true,\"createdBy\":{\"userId\":" + owner.getUserId() + ",\"username\":\"" + owner.getUsername() + "\"},\"note\":\"视频占位改为 JSON，避免不存在的二进制文件\"}");
    }

    private AssetEntity ensureDemoCarImageAsset(UserAccountEntity owner) throws Exception {
        if (owner == null || owner.getUserId() == null) {
            return null;
        }
        AssetEntity existing = findAsset(owner.getUserId(), SEED_CAR_IMAGE_FILE);
        if (existing != null) {
            return existing;
        }
        String metadata = "{\"seed\":true,\"from\":\"car_model_bundle_image\",\"assetRole\":\"car_exterior_front\","
                + "\"assetGroup\":\"" + GROUP_CAR_MODEL_BUNDLE + "\",\"brandModel\":\"捷途 G700\","
                + "\"hiddenInPublicAssetCenter\":true,\"carModelBundleComponent\":true}";
        if (volcengineTosProperties.enabled() && seedCarImageUpload != null) {
            return insertAsset(owner.getUserId(), "IMAGE", SEED_CAR_IMAGE_FILE,
                    seedCarImageUpload.objectKey(),
                    seedCarImageUpload.url(),
                    seedCarImageUpload.url(),
                    "image/png",
                    seedCarImageUpload.size(),
                    "DEMO",
                    GROUP_CAR_MODEL_BUNDLE,
                    metadata);
        }
        String fileUrl = "/uploads/seed/" + SEED_CAR_IMAGE_FILE;
        Path file = Path.of(uploadProperties.localRoot()).toAbsolutePath().resolve("seed").resolve(SEED_CAR_IMAGE_FILE);
        long size = Files.exists(file) ? Files.size(file) : 0L;
        return insertAsset(owner.getUserId(), "IMAGE", SEED_CAR_IMAGE_FILE, file.toString(), fileUrl, fileUrl,
                "image/png", size, "DEMO", GROUP_CAR_MODEL_BUNDLE, metadata);
    }

    private void ensureDemoCarModelBundleAsset(UserAccountEntity owner, AssetEntity carImage) throws Exception {
        if (owner == null || owner.getUserId() == null || carImage == null || carImage.getAssetId() == null) {
            return;
        }
        if (findAsset(owner.getUserId(), SEED_CAR_BUNDLE_FILE) != null) {
            return;
        }
        String coverUrl = firstNonBlank(carImage.getThumbnailUrl(), carImage.getFileUrl(), "/uploads/seed/" + SEED_CAR_IMAGE_FILE);
        long carImageId = carImage.getAssetId();
        String now = LocalDateTime.now().toString();
        String contentJson = "{\n"
                + "  \"bundleType\": \"car_model\",\n"
                + "  \"assetRole\": \"car_model_bundle\",\n"
                + "  \"brandModel\": \"捷途 G700\",\n"
                + "  \"color\": \"银灰 展厅版\",\n"
                + "  \"notes\": \"演示账号默认车型素材包，用于资产复用创作验收。\",\n"
                + "  \"coverUrl\": \"" + jsonEscape(coverUrl) + "\",\n"
                + "  \"images\": [\n"
                + "    {\n"
                + "      \"assetId\": " + carImageId + ",\n"
                + "      \"role\": \"car_exterior_front\",\n"
                + "      \"label\": \"外观正面\",\n"
                + "      \"url\": \"" + jsonEscape(coverUrl) + "\",\n"
                + "      \"thumbnailUrl\": \"" + jsonEscape(coverUrl) + "\"\n"
                + "    }\n"
                + "  ],\n"
                + "  \"createdAt\": \"" + jsonEscape(now) + "\",\n"
                + "  \"updatedAt\": \"" + jsonEscape(now) + "\"\n"
                + "}\n";
        String metadataJson = "{\"seed\":true,\"from\":\"car_model_bundle\",\"assetRole\":\"car_model_bundle\","
                + "\"assetGroup\":\"" + GROUP_CAR_MODEL_BUNDLE + "\",\"bundleType\":\"car_model\","
                + "\"brandModel\":\"捷途 G700\",\"color\":\"银灰 展厅版\","
                + "\"coverUrl\":\"" + jsonEscape(coverUrl) + "\",\"thumbnailUrl\":\"" + jsonEscape(coverUrl) + "\","
                + "\"imageCount\":1,\"componentCount\":1,\"componentAssetIds\":[" + carImageId + "]}";
        byte[] bytes = contentJson.getBytes(StandardCharsets.UTF_8);

        if (volcengineTosProperties.enabled()) {
            UploadResult uploaded = storageService.upload(
                    new ByteArrayInputStream(bytes),
                    bytes.length,
                    SEED_CAR_BUNDLE_FILE,
                    "application/json",
                    "seed"
            );
            insertAsset(owner.getUserId(), "JSON", SEED_CAR_BUNDLE_FILE,
                    uploaded.objectKey(),
                    uploaded.url(),
                    coverUrl,
                    "application/json",
                    uploaded.size(),
                    "DEMO",
                    GROUP_CAR_MODEL_BUNDLE,
                    metadataJson);
            return;
        }

        Path file = Path.of(uploadProperties.localRoot()).toAbsolutePath().resolve("seed").resolve(SEED_CAR_BUNDLE_FILE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contentJson, StandardCharsets.UTF_8);
        String fileUrl = "/uploads/seed/" + SEED_CAR_BUNDLE_FILE;
        insertAsset(owner.getUserId(), "JSON", SEED_CAR_BUNDLE_FILE, file.toString(), fileUrl, coverUrl,
                "application/json", bytes.length, "DEMO", GROUP_CAR_MODEL_BUNDLE, metadataJson);
    }

    private AssetEntity findAsset(String fileName) {
        LambdaQueryWrapper<AssetEntity> w = new LambdaQueryWrapper<>();
        w.eq(AssetEntity::getFileName, fileName)
                .eq(AssetEntity::getDeleted, 0)
                .last("limit 1");
        return assetMapper.selectOne(w);
    }

    private AssetEntity findAsset(Long ownerUserId, String fileName) {
        LambdaQueryWrapper<AssetEntity> w = new LambdaQueryWrapper<>();
        w.eq(AssetEntity::getOwnerUserId, ownerUserId)
                .eq(AssetEntity::getFileName, fileName)
                .eq(AssetEntity::getDeleted, 0)
                .last("limit 1");
        return assetMapper.selectOne(w);
    }

    private boolean existsAsset(String fileName) {
        LambdaQueryWrapper<AssetEntity> w = new LambdaQueryWrapper<>();
        w.eq(AssetEntity::getFileName, fileName)
                .eq(AssetEntity::getDeleted, 0)
                .last("limit 1");
        return assetMapper.selectOne(w) != null;
    }

    private AssetEntity insertAsset(Long ownerUserId,
                                    String assetType,
                                    String fileName,
                                    String filePath,
                                    String fileUrl,
                                    String thumbnailUrl,
                                    String mimeType,
                                    long fileSize,
                                    String sourceType,
                                    String metadataJson) {
        return insertAsset(ownerUserId, assetType, fileName, filePath, fileUrl, thumbnailUrl, mimeType, fileSize,
                sourceType, null, metadataJson);
    }

    private AssetEntity insertAsset(Long ownerUserId,
                                    String assetType,
                                    String fileName,
                                    String filePath,
                                    String fileUrl,
                                    String thumbnailUrl,
                                    String mimeType,
                                    long fileSize,
                                    String sourceType,
                                    String assetGroup,
                                    String metadataJson) {
        AssetEntity entity = new AssetEntity();
        entity.setOwnerUserId(ownerUserId);
        entity.setProjectId(null);
        entity.setTaskId(null);
        entity.setAssetType(assetType);
        entity.setKind("MATERIAL");
        entity.setVisibility(ownerUserId == null ? "PUBLIC" : "PRIVATE");
        entity.setStatus("ACTIVE");
        entity.setPublishedAt(ownerUserId == null ? LocalDateTime.now() : null);
        entity.setFileName(fileName);
        entity.setFilePath(filePath);
        entity.setFileUrl(fileUrl);
        entity.setThumbnailUrl(thumbnailUrl);
        entity.setMimeType(mimeType);
        entity.setFileSize(fileSize);
        entity.setSourceType(sourceType);
        entity.setAssetGroup(assetGroup);
        entity.setMetadataJson(metadataJson);
        assetMapper.insert(entity);
        return entity;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
}
