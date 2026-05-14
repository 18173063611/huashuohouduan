package com.huashuo.writer.vo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScriptVO {
    private int order;
    private String time;
    private String content;
    private String backgroundMusic;
    private String page;
    private String highlight;
}
