package com.huashuo.writer.pojo;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DouyinVideoParseResponse{
    String videoId;
    String playUrl;
    String title;
    DouyinAuthorInfo author;
    String coverUrl;
    Long durationSeconds;
    String sourceEndpoint;
    String requestId;
    JsonNode rawData;
}
