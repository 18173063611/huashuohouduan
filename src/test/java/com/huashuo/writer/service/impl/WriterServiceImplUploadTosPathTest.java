package com.huashuo.writer.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.upload.config.UploadProperties;
import com.huashuo.upload.tos.TosUploadService;
import com.huashuo.upload.tos.UploadPublicBaseProvider;
import com.huashuo.upload.tos.VolcengineTosProperties;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WriterServiceImplUploadTosPathTest {

    @Test
    @SuppressWarnings("unchecked")
    void resolvesUploadObjectKeyWhenTosPublicBaseUrlHasNoPath() throws Exception {
        VolcengineTosProperties tosProperties = new VolcengineTosProperties(
                true,
                "cn-guangzhou",
                "https://tos-cn-guangzhou.volces.com",
                "bucket",
                "https://ceshichucun.tos-cn-guangzhou.volces.com",
                "ak",
                "sk",
                10_000,
                60_000,
                60_000,
                64,
                1,
                30_000
        );
        UploadProperties uploadProperties = new UploadProperties("./target/test-uploads", "/uploads", "", false);
        WriterServiceImpl service = new WriterServiceImpl(
                new ObjectMapper(),
                uploadProperties,
                new TosUploadService(tosProperties, null),
                new UploadPublicBaseProvider(uploadProperties, tosProperties),
                "https://api.tikhub.io",
                "test-api-key",
                "",
                "",
                "volc_auc_common",
                "https://ark.cn-beijing.volces.com/api/v3",
                "",
                "doubao-seed-2-0-mini-260215",
                true,
                "ffmpeg",
                1.2D,
                900L,
                524288000L,
                300L
        );

        Method method = WriterServiceImpl.class.getDeclaredMethod("resolveTosObjectKey", String.class);
        method.setAccessible(true);

        Optional<String> result = (Optional<String>) method.invoke(
                service,
                "https://ceshichucun.tos-cn-guangzhou.volces.com/upload/2026/05/21/video.mp4"
        );

        assertEquals(Optional.of("upload/2026/05/21/video.mp4"), result);
    }
}
