package com.huashuo.upload.tos;

import com.huashuo.common.exception.BusinessException;
import com.volcengine.tos.TOSV2;
import com.volcengine.tos.TosException;
import com.volcengine.tos.model.object.ObjectMetaRequestOptions;
import com.volcengine.tos.model.object.PutObjectInput;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;

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
        TOSV2 client = tosClient.getIfAvailable();
        if (client == null) {
            throw new BusinessException(50000, "TOS 已开启但未初始化客户端，请检查配置与依赖。");
        }
        PutObjectInput input = new PutObjectInput()
                .setBucket(properties.bucket())
                .setKey(objectKey)
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
}
