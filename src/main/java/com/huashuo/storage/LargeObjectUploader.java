package com.huashuo.storage;

import java.io.InputStream;

/**
 * 预留：超大对象分片上传（Multipart Upload）。当前未接入实现，避免大文件一次性读入内存。
 */
public interface LargeObjectUploader {

    /**
     * @param knownContentLength 未知时可传 -1，由实现决定是否缓冲临时文件
     */
    UploadResult uploadStreaming(String objectKey, InputStream inputStream, long knownContentLength,
                                 String contentType);
}
