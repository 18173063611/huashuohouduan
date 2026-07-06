package com.huashuo.petvideo.dto;

public record PetWorkDownloadResponse(
        String fileName,
        String url,
        String content,
        String mimeType
) {
}
