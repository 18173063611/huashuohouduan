package com.huashuo.writer.pojo;


import lombok.Data;

@Data
public class DouyinVideoParseRequest{
    Long projectId;
    String url;
    String platform;
    String title;
    String sourceType;
    String filePath;
}
