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

    private final UploadProperties uploadProperties;
    private final VolcengineTosProperties volcengineTosProperties;
    private final StorageService storageService;
    private final UserAccountMapper userAccountMapper;
    private final AssetMapper assetMapper;

    private UploadResult seedImageUpload;
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
            ensureUser("demo", "演示用户");

            ensureSeedFilesExist();
            softDeleteLegacyMissingSeed();
            repairDemoAssetsMissingLocalPath();

            ensureSeedImageAsset(alice);
            ensureSeedTextAsset(alice);
            ensureSeedJsonAsset(bob);
            ensureSeedBobVideoJson(bob);
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

    private AssetEntity findAsset(String fileName) {
        LambdaQueryWrapper<AssetEntity> w = new LambdaQueryWrapper<>();
        w.eq(AssetEntity::getFileName, fileName)
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

    private void insertAsset(Long ownerUserId,
                             String assetType,
                             String fileName,
                             String filePath,
                             String fileUrl,
                             String thumbnailUrl,
                             String mimeType,
                             long fileSize,
                             String sourceType,
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
        entity.setMetadataJson(metadataJson);
        assetMapper.insert(entity);
    }
}
