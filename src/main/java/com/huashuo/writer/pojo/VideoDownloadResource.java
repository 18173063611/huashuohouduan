package com.huashuo.writer.pojo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 短视频下载代理打开后的远程流。由 Controller 负责写回浏览器，避免前端直接请求平台直链时被 CORS 拦截。
 */
public record VideoDownloadResource(
        String fileName,
        String contentType,
        long contentLength,
        long maxBytes,
        InputStream inputStream
) implements AutoCloseable {

    public void writeTo(OutputStream outputStream) throws IOException {
        long copied = 0L;
        byte[] buffer = new byte[1024 * 64];
        try (InputStream in = inputStream) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                copied += read;
                if (maxBytes > 0 && copied > maxBytes) {
                    throw new IOException("video download exceeds max bytes");
                }
                outputStream.write(buffer, 0, read);
            }
        }
    }

    @Override
    public void close() throws IOException {
        inputStream.close();
    }
}
