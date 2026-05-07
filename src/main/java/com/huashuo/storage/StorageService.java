package com.huashuo.storage;

import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

/**
 * 统一对象存储抽象：优先流式直传 TOS，不落业务目录 ./data/uploads。
 */
public interface StorageService {

    UploadResult upload(MultipartFile file, String category);

    UploadResult upload(InputStream inputStream, long contentLength,
                        String filename, String contentType, String category);

    String getPublicUrl(String objectKey);

    boolean delete(String objectKey);
}
