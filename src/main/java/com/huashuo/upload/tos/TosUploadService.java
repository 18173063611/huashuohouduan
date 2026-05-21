package com.huashuo.upload.tos;

import com.huashuo.common.exception.BusinessException;
import com.volcengine.tos.TOSV2;
import com.volcengine.tos.TosException;
import com.volcengine.tos.model.object.GetObjectToFileInput;
import com.volcengine.tos.model.object.ObjectMetaRequestOptions;
import com.volcengine.tos.model.object.PreSignedURLInput;
import com.volcengine.tos.model.object.PutObjectInput;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.nio.file.Path;

/**
 * TOS 上传封装：{@link #putPublicObject} 的 {@code objectKey} 为 Bucket 内完整对象键（新规范如 {@code avatar/2026/05/07/uuid.png}，历史亦可能为 {@code uploads/...}）。
 */
@Service
public class TosUploadService {

    private final VolcengineTosProperties properties;
    private final ObjectProvider<TOSV2> tosClient;

    public TosUploadService(VolcengineTosProperties properties, ObjectProvider<TOSV2> tosClient) {
        this.properties = properties;
        this.tosClient = tosClient;
    }

    /**
     * 将 {@code /uploads/...} 形式的预览路径转为 TOS 对象键（去掉前导 {@code /}）。
     */
    public static String previewUrlToObjectKey(String previewUrl) {
        if (!StringUtils.hasText(previewUrl)) {
            throw new BusinessException(40000, "Preview URL is empty");
        }
        String t = previewUrl.trim();
        if (!t.startsWith("/")) {
            throw new BusinessException(40000, "Preview URL must start with /");
        }
        return t.substring(1);
    }

    /**
     * 开启 TOS 时把对象写入 Bucket；与本地 {@code previewUrl} 使用同一套路径，便于 {@link UploadPublicBaseProvider} 拼接公网 URL。
     */
    public void putPublicObject(String objectKey, InputStream content, long contentLength, String contentType) {
        if (!properties.enabled()) {
            return;
        }
        TOSV2 client = requireClient();
        PutObjectInput input = new PutObjectInput()
                .setBucket(properties.bucket())
                .setKey(normalizeObjectKey(objectKey))
                .setContent(content)
                .setContentLength(contentLength);
        if (StringUtils.hasText(contentType)) {
            input.setOptions(new ObjectMetaRequestOptions().setContentType(contentType.trim()));
        }
        try {
            client.putObject(input);
        } catch (TosException e) {
            throw new BusinessException(50000, "TOS 上传失败: " + e.getMessage());
        }
    }

    /**
     * 通过 TOS SDK 把对象下载到本地临时文件，避免后端再绕公网 URL 回读自己刚上传的对象。
     */
    public void getPublicObjectToFile(String objectKey, Path targetFile) {
        if (!properties.enabled()) {
            throw new BusinessException(50000, "TOS 未启用，无法读取对象存储文件。");
        }
        if (targetFile == null) {
            throw new BusinessException(40000, "targetFile is required");
        }
        TOSV2 client = requireClient();
        try {
            client.getObjectToFile(new GetObjectToFileInput()
                    .setBucket(properties.bucket())
                    .setKey(normalizeObjectKey(objectKey))
                    .setFilePath(targetFile.toString()));
        } catch (TosException e) {
            throw new BusinessException(50214, "TOS 文件读取失败: " + e.getMessage());
        }
    }

    public String publicBaseUrl() {
        if (!StringUtils.hasText(properties.publicBaseUrl())) {
            return "";
        }
        String value = properties.publicBaseUrl().trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    public String createPreSignedGetUrl(String objectKey, long expiresSeconds) {
        if (!properties.enabled()) {
            throw new BusinessException(50000, "TOS 未启用，无法生成签名访问地址。");
        }
        try {
            return requireClient().preSignedURL(new PreSignedURLInput()
                    .setBucket(properties.bucket())
                    .setKey(normalizeObjectKey(objectKey))
                    .setHttpMethod("GET")
                    .setExpires(Math.max(expiresSeconds, 60L)))
                    .getSignedUrl();
        } catch (TosException e) {
            throw new BusinessException(50000, "TOS 签名地址生成失败: " + e.getMessage());
        }
    }

    private TOSV2 requireClient() {
        TOSV2 client = tosClient.getIfAvailable();
        if (client == null) {
            throw new BusinessException(50000, "TOS 已开启但未初始化客户端，请检查配置与依赖。");
        }
        return client;
    }

    private String normalizeObjectKey(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            throw new BusinessException(40000, "TOS objectKey is empty");
        }
        String key = objectKey.trim();
        while (key.startsWith("/")) {
            key = key.substring(1);
        }
        if (key.contains("..") || key.contains("\\") || key.contains("://")) {
            throw new BusinessException(40000, "非法 TOS objectKey");
        }
        return key;
    }
}
