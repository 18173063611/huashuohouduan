package com.huashuo.writer.dto;

import lombok.Data;

@Data
public class RewriteDTO {
    public String originalText; // 原文案
    public String style; // 风格
    public String introduce; // 补充说明
}
