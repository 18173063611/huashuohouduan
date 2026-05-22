package com.huashuo.writer.pojo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 短视频下载代理打开后的远程流。由 Controller 负责写回浏览器，避免前端直接请求平台直链时被 CORS 拦截。
 */
public record VideoDownloadResource(
        String fileName,
        String contentType,
        long contentLength,
        long maxBytes,
        InputStream inputStream,
        Path cleanupPath
) implements AutoCloseable {

    public VideoDownloadResource(String fileName, String contentType, long contentLength, long maxBytes,
                                 InputStream inputStream) {
        this(fileName, contentType, contentLength, maxBytes, inputStream, null);
    }

    public void writeTo(OutputStream outputStream) throws IOException {
        long copied = 0L;
        long unflushed = 0L;
        byte[] buffer = new byte[1024 * 64];
        try (InputStream in = inputStream) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                copied += read;
                if (maxBytes > 0 && copied > maxBytes) {
                    throw new IOException("video download exceeds max bytes");
                }
                outputStream.write(buffer, 0, read);
                unflushed += read;
                if (unflushed >= 1024 * 1024) {
                    outputStream.flush();
                    unflushed = 0L;
                }
            }
            outputStream.flush();
            if (contentLength > 0 && copied < contentLength) {
                throw new IOException("video download closed before expected content length");
            }
        }
    }

    @Override
    public void close() throws IOException {
        IOException closeException = null;
        try {
            inputStream.close();
        } catch (IOException exception) {
            closeException = exception;
        }
        if (cleanupPath != null) {
            try {
                Files.deleteIfExists(cleanupPath);
            } catch (IOException exception) {
                if (closeException != null) {
                    closeException.addSuppressed(exception);
                } else {
                    closeException = exception;
                }
            }
        }
        if (closeException != null) {
            throw closeException;
        }
    }
}
