package com.huashuo.storage.impl;

import com.huashuo.common.exception.BusinessException;
import com.huashuo.storage.StorageService;
import com.huashuo.storage.UploadResult;
import com.huashuo.upload.tos.TosUploadService;
import com.huashuo.upload.tos.VolcengineTosProperties;
import com.volcengine.tos.TOSV2;
import com.volcengine.tos.TosException;
import com.volcengine.tos.model.object.DeleteObjectInput;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * TOS 流式上传实现：不落 ./data/uploads；未启用 TOS 或未注入客户端时明确报错。
 */
@Service
public class ObjectStorageService implements StorageService {

    private static final Set<String> CATEGORY_WHITELIST = Set.of(
            "upload", "tts", "avatar", "seed", "storyboard", "video", "image",
            "voice-sample"
    );

    private static final Pattern SAFE_FILENAME = Pattern.compile("^[a-zA-Z0-9._-]+$");
    private static final Set<String> DENIED_EXTENSIONS = Set.of(
            "exe", "bat", "cmd", "sh", "jsp", "jspx", "php", "asp", "aspx", "dll", "msi",
            "html", "htm", "svg", "js", "mjs", "xml"
    );
    private static final Set<String> DENIED_CONTENT_TYPES = Set.of(
            "text/html", "image/svg+xml", "application/javascript", "text/javascript",
            "application/xml", "text/xml"
    );

    private static final DateTimeFormatter DAY_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final VolcengineTosProperties tosProperties;
    private final TosUploadService tosUploadService;
    private final ObjectProvider<TOSV2> tosClient;

    public ObjectStorageService(
            VolcengineTosProperties tosProperties,
            TosUploadService tosUploadService,
            ObjectProvider<TOSV2> tosClient
    ) {
        this.tosProperties = tosProperties;
        this.tosUploadService = tosUploadService;
        this.tosClient = tosClient;
    }

    @Override
    public UploadResult upload(MultipartFile file, String category) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(40000, "Empty multipart file");
        }
        String filename = safeOriginalName(file.getOriginalFilename());
        String contentType = StringUtils.hasText(file.getContentType()) ? file.getContentType() : "application/octet-stream";
        validateCategory(category);
        validateExtensionAndMime(filename);
        validateContentType(contentType);
        long size = file.getSize();
        if (size <= 0) {
            throw new BusinessException(40000, "Invalid file size");
        }
        String objectKey = buildObjectKey(category, filename);
        ensureTosAvailable();
        try (InputStream in = file.getInputStream()) {
            tosUploadService.putPublicObject(objectKey, in, size, contentType);
        } catch (IOException e) {
            throw new BusinessException(50000, "读取上传流失败: " + e.getMessage());
        }
        return new UploadResult(objectKey, buildPublicUrl(objectKey), filename, size, contentType);
    }

    @Override
    public UploadResult upload(InputStream inputStream, long contentLength, String filename, String contentType,
                               String category) {
        if (inputStream == null) {
            throw new BusinessException(40000, "InputStream is required");
        }
        String safeName = safeOriginalName(filename);
        String mime = StringUtils.hasText(contentType) ? contentType : "application/octet-stream";
        validateCategory(category);
        validateExtensionAndMime(safeName);
        validateContentType(mime);
        ensureTosAvailable();

        if (contentLength >= 0) {
            String objectKey = buildObjectKey(category, safeName);
            tosUploadService.putPublicObject(objectKey, inputStream, contentLength, mime);
            return new UploadResult(objectKey, buildPublicUrl(objectKey), safeName, contentLength, mime);
        }

        Path tmp = null;
        try {
            tmp = Files.createTempFile("huashuo-stor-", "-" + safeName);
            long copied = Files.copy(inputStream, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            String objectKey = buildObjectKey(category, safeName);
            try (InputStream in = Files.newInputStream(tmp)) {
                tosUploadService.putPublicObject(objectKey, in, copied, mime);
            }
            return new UploadResult(objectKey, buildPublicUrl(objectKey), safeName, copied, mime);
        } catch (IOException e) {
            throw new BusinessException(50000, "流式上传临时缓冲失败: " + e.getMessage());
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                }
            }
        }
    }

    @Override
    public String getPublicUrl(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            throw new BusinessException(40000, "objectKey is required");
        }
        validateObjectKey(objectKey);
        return buildPublicUrl(objectKey.trim());
    }

    @Override
    public boolean delete(String objectKey) {
        if (!tosProperties.enabled()) {
            return false;
        }
        TOSV2 client = tosClient.getIfAvailable();
        if (client == null || !StringUtils.hasText(objectKey)) {
            return false;
        }
        validateObjectKey(objectKey);
        try {
            client.deleteObject(new DeleteObjectInput()
                    .setBucket(tosProperties.bucket())
                    .setKey(objectKey.trim()));
            return true;
        } catch (TosException e) {
            return false;
        }
    }

    private void ensureTosAvailable() {
        if (!tosProperties.enabled()) {
            throw new BusinessException(50001, "对象存储未启用：请将 volcengine.tos.enabled 设为 true 并配置密钥。");
        }
        if (tosClient.getIfAvailable() == null) {
            throw new BusinessException(50001, "TOS 客户端未初始化：请配置 VOLCENGINE_TOS_ACCESS_KEY_ID 等密钥。");
        }
        if (!StringUtils.hasText(publicBase())) {
            throw new BusinessException(50001, "未配置 volcengine.tos.public-base-url，无法生成访问 URL。");
        }
    }

    private String publicBase() {
        String u = tosProperties.publicBaseUrl();
        return u == null ? "" : u.trim();
    }

    private String buildPublicUrl(String objectKey) {
        String base = trimTrailingSlash(publicBase());
        return base + "/" + objectKey;
    }

    private static String trimTrailingSlash(String base) {
        String v = base;
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    private void validateCategory(String category) {
        if (!StringUtils.hasText(category)) {
            throw new BusinessException(40000, "category is required");
        }
        String c = category.trim().toLowerCase(Locale.ROOT);
        if (!CATEGORY_WHITELIST.contains(c)) {
            throw new BusinessException(40000, "非法存储分类: " + category);
        }
    }

    private void validateObjectKey(String objectKey) {
        if (objectKey.contains("..") || objectKey.contains("\\")) {
            throw new BusinessException(40000, "非法 object key");
        }
    }

    private void validateContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return;
        }
        String normalized = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (DENIED_CONTENT_TYPES.contains(normalized)) {
            throw new BusinessException(40000, "Unsupported upload content type");
        }
    }

    private void validateExtensionAndMime(String filename) {
        String ext = extensionOf(filename);
        if (DENIED_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT))) {
            throw new BusinessException(40000, "不允许的文件扩展名");
        }
    }

    private static String safeOriginalName(String original) {
        String raw = StringUtils.hasText(original) ? original.trim() : "file.bin";
        String base = raw;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0 && slash < base.length() - 1) {
            base = base.substring(slash + 1);
        }
        if (!SAFE_FILENAME.matcher(base).matches()) {
            String ext = extensionOf(base);
            base = "file-" + UUID.randomUUID() + (ext.isEmpty() ? "" : "." + ext.toLowerCase(Locale.ROOT));
        }
        return base;
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1);
    }

    private static String buildObjectKey(String category, String filename) {
        String c = category.trim().toLowerCase(Locale.ROOT);
        String ext = extensionOf(filename);
        String day = LocalDate.now().format(DAY_PATH);
        String uuid = UUID.randomUUID().toString();
        String suffix = ext.isEmpty() ? "" : "." + ext.toLowerCase(Locale.ROOT);
        return c + "/" + day + "/" + uuid + suffix;
    }
}
