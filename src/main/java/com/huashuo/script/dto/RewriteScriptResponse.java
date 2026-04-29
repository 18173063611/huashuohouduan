package com.huashuo.script.dto;

public record RewriteScriptResponse(
        Long taskId,
        String status,
        Long scriptVersionId,
        Integer versionNo,
        String rewrittenText
) {
}
