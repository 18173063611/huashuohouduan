package com.huashuo.writer.dto;

/**
 * 分镜解析任务提交体：仅承载公网可访问或已上传后的视频 URL。
 */
public record VideoScriptSubmitRequest(String url, String platform) {

    public VideoScriptSubmitRequest(String url) {
        this(url, null);
    }
}
