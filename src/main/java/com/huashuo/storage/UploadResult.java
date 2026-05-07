package com.huashuo.storage;

/**
 * 对象存储上传结果：新数据统一写入完整公网 URL 与 objectKey。
 */
public record UploadResult(
        String objectKey,
        String url,
        String filename,
        long size,
        String contentType
) {
}
