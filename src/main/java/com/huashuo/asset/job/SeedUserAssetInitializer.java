package com.huashuo.asset.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.huashuo.asset.entity.AssetEntity;
import com.huashuo.asset.mapper.AssetMapper;
import com.huashuo.user.entity.UserAccountEntity;
import com.huashuo.user.mapper.UserAccountMapper;
import com.huashuo.upload.config.UploadProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;

@Component
/**
 * 初始化演示用户与演示资产：
 * - 解决 MySQL 场景下 schema.sql 幂等 insert 不会覆盖旧数据导致“看不到 seed 资产”的问题。
 * - 把工作区里存在的图片复制到 uploads/seed 下，确保前端预览不 404。
 */
public class SeedUserAssetInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedUserAssetInitializer.class);

    private static final String DEMO_PASSWORD_HASH = "$2a$10$FpjChVSxXshPiX0E62qP5eWjtXi7AfhCgQLJyF9zkwAhvG1zakTIG";
    private static final String SEED_IMAGE_FILE = "avatar-upload-16d01549-407a-4c2e-a5b9-39dcc0e04956.png";

    private final UploadProperties uploadProperties;
    private final UserAccountMapper userAccountMapper;
    private final AssetMapper assetMapper;

    public SeedUserAssetInitializer(UploadProperties uploadProperties, UserAccountMapper userAccountMapper, AssetMapper assetMapper) {
        this.uploadProperties = uploadProperties;
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

            ensureSeedImageAsset(alice);
            ensureSeedTextAsset(alice);
            ensureSeedJsonAsset(bob);
        } catch (Exception e) {
            // Seed 失败不应阻断服务启动，避免影响现有功能。
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
        Path root = Path.of(uploadProperties.localRoot()).toAbsolutePath();
        Path seedDir = root.resolve("seed");
        Files.createDirectories(seedDir);

        // 1) 图片：从工作区根目录复制到 uploads/seed 下，避免 seed 记录指向不存在文件
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

        // 2) 文本/JSON：直接生成到 uploads/seed 下，保证预览可用
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
        // 你反馈过 seed-alice-cover.png 不存在但仍展示：如果历史库里有这条 seed，直接软删避免干扰。
        LambdaUpdateWrapper<AssetEntity> uw = new LambdaUpdateWrapper<>();
        uw.eq(AssetEntity::getFileName, "seed-alice-cover.png")
                .eq(AssetEntity::getDeleted, 0)
                .set(AssetEntity::getDeleted, 1)
                .set(AssetEntity::getUpdatedAt, LocalDateTime.now());
        assetMapper.update(null, uw);
    }

    private void ensureSeedImageAsset(UserAccountEntity owner) throws Exception {
        if (owner == null || owner.getUserId() == null) {
            return;
        }
        String fileUrl = "/uploads/seed/" + SEED_IMAGE_FILE;
        if (existsAsset(SEED_IMAGE_FILE)) {
            return;
        }
        Path file = Path.of(uploadProperties.localRoot()).toAbsolutePath().resolve("seed").resolve(SEED_IMAGE_FILE);
        long size = Files.exists(file) ? Files.size(file) : 0L;
        insertAsset("IMAGE", SEED_IMAGE_FILE, file.toString(), fileUrl, fileUrl, "image/png", size,
                "MANUAL_CREATED",
                "{\"seed\":true,\"createdBy\":{\"userId\":" + owner.getUserId() + ",\"username\":\"" + owner.getUsername() + "\"},\"tag\":\"cover\"}");
    }

    private void ensureSeedTextAsset(UserAccountEntity owner) throws Exception {
        String fileName = "seed-alice-script.txt";
        String fileUrl = "/uploads/seed/" + fileName;
        if (existsAsset(fileName)) {
            return;
        }
        Path file = Path.of(uploadProperties.localRoot()).toAbsolutePath().resolve("seed").resolve(fileName);
        long size = Files.exists(file) ? Files.size(file) : 0L;
        insertAsset("TEXT", fileName, file.toString(), fileUrl, null, "text/plain", size,
                "MANUAL_CREATED",
                "{\"seed\":true,\"createdBy\":{\"userId\":" + owner.getUserId() + ",\"username\":\"" + owner.getUsername() + "\"},\"description\":\"演示文案资产\"}");
    }

    private void ensureSeedJsonAsset(UserAccountEntity owner) throws Exception {
        String fileName = "seed-bob-voice.json";
        String fileUrl = "/uploads/seed/" + fileName;
        if (existsAsset(fileName)) {
            return;
        }
        Path file = Path.of(uploadProperties.localRoot()).toAbsolutePath().resolve("seed").resolve(fileName);
        long size = Files.exists(file) ? Files.size(file) : 0L;
        insertAsset("JSON", fileName, file.toString(), fileUrl, null, "application/json", size,
                "MANUAL_CREATED",
                "{\"seed\":true,\"createdBy\":{\"userId\":" + owner.getUserId() + ",\"username\":\"" + owner.getUsername() + "\"},\"note\":\"用于联调展示\"}");
    }

    private boolean existsAsset(String fileName) {
        LambdaQueryWrapper<AssetEntity> w = new LambdaQueryWrapper<>();
        w.eq(AssetEntity::getFileName, fileName)
                .eq(AssetEntity::getDeleted, 0)
                .last("limit 1");
        return assetMapper.selectOne(w) != null;
    }

    private void insertAsset(String assetType,
                             String fileName,
                             String filePath,
                             String fileUrl,
                             String thumbnailUrl,
                             String mimeType,
                             long fileSize,
                             String sourceType,
                             String metadataJson) {
        AssetEntity entity = new AssetEntity();
        entity.setOwnerUserId(null);
        entity.setProjectId(null);
        entity.setTaskId(null);
        entity.setAssetType(assetType);
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

