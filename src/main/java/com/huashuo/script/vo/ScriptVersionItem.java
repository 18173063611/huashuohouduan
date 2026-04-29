package com.huashuo.script.vo;

import java.time.LocalDateTime;

public record ScriptVersionItem(
        Long scriptVersionId,
        Long projectId,
        Integer versionNo,
        String content,
        String sourceType,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
